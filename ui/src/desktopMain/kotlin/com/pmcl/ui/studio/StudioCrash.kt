package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

private const val MAX_CRASH_BYTES = 512 * 1024
private const val MAX_STACK_LINES = 40

private data class CrashFile(val path: Path, val name: String, val modified: Long, val size: Long)

/** 崩溃报告的解析结果：描述、异常、原因链、可疑模组、建议、堆栈头。 */
private data class CrashAnalysis(
    val description: String,
    val exceptionLine: String,
    val causes: List<String>,
    val suspectMods: List<String>,
    val advice: List<String>,
    val stackHead: List<String>,
)

private fun listCrashReports(gameDir: Path?): List<CrashFile> {
    val dir = gameDir?.resolve("crash-reports") ?: return emptyList()
    return try {
        Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".txt") }
                .toList()
                .map {
                    CrashFile(
                        it,
                        it.fileName.toString(),
                        runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L),
                        runCatching { Files.size(it) }.getOrDefault(0L)
                    )
                }
                .sortedByDescending { it.modified }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 从模组 jar 收集 modId 列表，用于和堆栈帧的包名做匹配。实时日志的错误行也用它反查模组。 */
internal fun collectModIds(gameDir: Path?): List<Pair<String, String>> {
    val mods = listMods(gameDir?.resolve("mods"))
    val out = ArrayList<Pair<String, String>>(mods.size)
    for (mod in mods) {
        if (!mod.enabled) continue
        val meta = runCatching { readModMeta(mod.path) }.getOrNull()
        val id = meta?.id.orEmpty()
        if (id.isNotBlank()) out += id to (meta?.name?.ifBlank { id } ?: id)
    }
    return out
}

private fun analyzeCrash(text: String, modIds: List<Pair<String, String>>): CrashAnalysis {
    val lines = text.split('\n')
    var description = ""
    var exceptionLine = ""
    val causes = ArrayList<String>()
    val stackHead = ArrayList<String>()
    var inStack = false
    for (raw in lines) {
        val line = raw.trimEnd('\r')
        val trimmed = line.trim()
        if (description.isEmpty() && trimmed.startsWith("Description:")) {
            description = trimmed.removePrefix("Description:").trim()
            continue
        }
        if (exceptionLine.isEmpty() && trimmed.matches(Regex("^(java|com|net|kotlin)\\.[\\w.]+(Exception|Error|Throwable)(:.*)?"))) {
            exceptionLine = trimmed
            inStack = true
            continue
        }
        if (trimmed.startsWith("Caused by:")) {
            causes += trimmed.removePrefix("Caused by:").trim()
            inStack = true
            continue
        }
        if (inStack && trimmed.startsWith("at ")) {
            if (stackHead.size < MAX_STACK_LINES) stackHead += trimmed
            continue
        }
        if (trimmed.isNotEmpty() && !trimmed.startsWith("at ")) inStack = false
    }

    // 官方崩溃报告带 "Suspected Mods" 段时优先采用
    val suspects = LinkedHashSet<String>()
    run {
        var inSection = false
        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("Suspected Mods")) {
                inSection = true
                continue
            }
            if (inSection) {
                if (trimmed.isEmpty() || trimmed.startsWith("--")) break
                Regex("""([\w.-]+)\s*\(([^)]+)\)""").findAll(trimmed).forEach { suspects += it.groupValues[2] }
            }
        }
    }
    // 启发式：堆栈帧的包名里出现 modId，就怀疑这个模组
    if (suspects.isEmpty() && modIds.isNotEmpty()) {
        for (frame in stackHead) {
            val pkg = frame.removePrefix("at ").substringBefore('(').substringBeforeLast('.', "")
            for ((id, name) in modIds) {
                if (id.length >= 3 && pkg.contains(id, ignoreCase = true)) suspects += name
            }
        }
    }

    val haystack = (description + "\n" + exceptionLine + "\n" + causes.joinToString("\n") + "\n" + stackHead.joinToString("\n")).lowercase()
    val advice = ArrayList<String>()
    when {
        "outofmemoryerror" in haystack -> advice += I18n.t("studio.advice_memory")
        "mixin" in haystack && (suspects.isNotEmpty() || "apply" in haystack) -> advice += I18n.t("studio.advice_mixin")
        "has failed to load correctly" in haystack || "modloading" in haystack.replace(" ", "") -> advice += I18n.t("studio.advice_modload")
        "ticking" in haystack -> advice += I18n.t("studio.advice_ticking")
        "rendering" in haystack || "screen" in description.lowercase() -> advice += I18n.t("studio.advice_render")
    }
    if (suspects.isNotEmpty()) advice += I18n.t("studio.advice_suspects", suspects.joinToString("、"))
    if (advice.isEmpty()) advice += I18n.t("studio.advice_generic")

    return CrashAnalysis(description, exceptionLine, causes, suspects.toList(), advice, stackHead)
}

/** 崩溃分析器：左边是报告列表，右边是解析结果和修复建议。 */
@Composable
internal fun CrashReportsEditor(
    vm: LauncherViewModel,
    target: StudioTarget,
    onOpenMods: () -> Unit,
) {
    val p = LocalStudio.current
    var reload by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable(target.id) { mutableStateOf<String?>(null) }
    val dir by produceState<Path?>(null, target) {
        value = withContext(Dispatchers.IO) { vm.gameDirOf(target) }
    }
    val reports by produceState(emptyList<CrashFile>(), dir, reload) {
        value = withContext(Dispatchers.IO) { listCrashReports(dir) }
    }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss") }
    val current = reports.firstOrNull { it.path.toString() == selected } ?: reports.firstOrNull()
    val analysis by produceState<CrashAnalysis?>(null, current, reload) {
        val file = current ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            try {
                val text = Files.newInputStream(file.path).use { ins ->
                    String(ins.readNBytes(MAX_CRASH_BYTES), StandardCharsets.UTF_8)
                }
                analyzeCrash(text, collectModIds(dir))
            } catch (_: Throwable) {
                null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(36.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp).fillMaxHeight().background(Tiles.Red), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            FlatText(I18n.t("studio.crash") + " · " + target.name, weight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp))
            FlatText(I18n.t("studio.crash_count", reports.size.toString()), color = p.muted, size = 11)
            Spacer(Modifier.weight(1f))
            Block("", onClick = { reload++ }, icon = Icons.Filled.Refresh, height = 36.dp)
            Block(I18n.t("studio.fact_mods"), onClick = onOpenMods, icon = Icons.Filled.Extension, fill = Tiles.Blue, height = 36.dp)
            Block("", onClick = { dir?.let { vm.openDir(it.resolve("crash-reports").toFile()) } }, icon = Icons.Filled.FolderOpen, enabled = dir != null, height = 36.dp)
        }
        if (dir == null) {
            CenterNote(I18n.t("studio.loading"))
            return@Column
        }
        if (reports.isEmpty()) {
            EmptyState(I18n.t("studio.crash_empty"), Icons.Filled.HealthAndSafety, color = Tiles.Forest)
            return@Column
        }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(280.dp).fillMaxHeight().background(p.panel)) {
                itemsIndexed(reports, key = { _, r -> r.path.toString() }) { index, report ->
                    val on = report.path == current?.path
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(if (on) Tiles.Red.copy(alpha = 0.22f) else if (index % 2 == 0) p.panel else p.panelAlt)
                            .clickable { selected = report.path.toString() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(if (on) Tiles.Red else Color.Transparent))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            FlatText(report.name.removeSuffix(".txt"), weight = if (on) FontWeight.SemiBold else FontWeight.Normal, size = 12)
                            FlatText(
                                (if (report.modified > 0) fmt.format(Date(report.modified)) else "—") + " · " + formatSize(report.size),
                                color = p.muted,
                                size = 11
                            )
                        }
                    }
                }
            }
            val a = analysis
            if (a == null) {
                CenterNote(I18n.t("studio.loading"))
                return@Row
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(18.dp)) {
                FlatText(
                    a.description.ifBlank { current?.name.orEmpty() },
                    size = 18,
                    weight = FontWeight.Light,
                    lines = 3
                )
                if (a.exceptionLine.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(a.exceptionLine, style = mono.copy(color = p.error), softWrap = true)
                }
                Spacer(Modifier.height(16.dp))

                LabelBar(I18n.t("studio.crash_advice"), Tiles.Orange)
                Column(Modifier.fillMaxWidth().background(p.panel).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    a.advice.forEachIndexed { i, tip ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = Tiles.Orange, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            FlatText(tip, color = p.text, size = 12, lines = 4)
                        }
                    }
                }

                if (a.suspectMods.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    LabelBar(I18n.t("studio.crash_suspects"), Tiles.Red)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        a.suspectMods.forEach { name ->
                            Block(name, onClick = onOpenMods, icon = Icons.Filled.Extension, fill = Tiles.Red, height = 30.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }

                if (a.causes.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    LabelBar(I18n.t("studio.crash_causes"), Tiles.Navy)
                    Column(Modifier.fillMaxWidth().background(p.panel).padding(10.dp)) {
                        a.causes.forEach { Text(it, style = mono.copy(color = p.text), softWrap = true) }
                    }
                }

                if (a.stackHead.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    LabelBar(I18n.t("studio.crash_stack") + " (" + a.stackHead.size + ")", Tiles.Slate)
                    Column(Modifier.fillMaxWidth().background(p.panel).horizontalScroll(rememberScrollState()).padding(10.dp)) {
                        a.stackHead.forEach { frame ->
                            Text(frame, style = mono.copy(color = p.muted), softWrap = false)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
