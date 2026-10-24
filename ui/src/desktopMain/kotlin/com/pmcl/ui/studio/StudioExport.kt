package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.instance.InstanceExporter
import com.pmcl.core.install.InstallProgress
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出格式。core 已实现的三种走既有代码路径（包含范围由格式决定），
 * 只有 ZIP 是本地的完整打包，才用得上内容勾选。
 */
internal enum class ExportFormat(val ext: String, val usesCore: Boolean) {
    ZIP("zip", false),
    MRPACK("mrpack", true),
    CURSEFORGE("zip", true),
    INSTANCE("pmcl-instance", true),
}

/** 一个可选的导出内容项；rel 是相对游戏目录的路径。 */
private data class ExportItem(val id: String, val rel: String, val directory: Boolean, val defaultOn: Boolean)

private val EXPORT_ITEMS = listOf(
    ExportItem("mods", "mods", true, true),
    ExportItem("config", "config", true, true),
    ExportItem("options", "options.txt", false, true),
    ExportItem("saves", "saves", true, false),
    ExportItem("resourcepacks", "resourcepacks", true, false),
    ExportItem("shaderpacks", "shaderpacks", true, false),
    ExportItem("screenshots", "screenshots", true, false),
    ExportItem("logs", "logs", true, false),
)

private val DEFAULT_PICKS = EXPORT_ITEMS.filter { it.defaultOn }.joinToString(",") { it.id }

/** 超过这个体积给一句提示，因为流式打包会持续几十秒到几分钟。 */
private const val BIG_EXPORT_BYTES = 1L shl 30

private fun downloadsDir(): Path {
    val home = Paths.get(System.getProperty("user.home") ?: ".")
    val down = home.resolve("Downloads")
    return if (Files.isDirectory(down)) down else home
}

private fun safeName(name: String): String =
    name.trim().ifBlank { "pmcl" }.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60)

private fun defaultFileName(target: StudioTarget, fmt: ExportFormat): String =
    safeName(target.name) + "-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) + "." + fmt.ext

private fun sizeOfItem(gameDir: Path, item: ExportItem): Long {
    val path = gameDir.resolve(item.rel)
    return try {
        when {
            item.directory && Files.isDirectory(path) -> dirSize(path)
            Files.isRegularFile(path) -> Files.size(path)
            else -> 0L
        }
    } catch (_: Throwable) {
        0L
    }
}

/** 收集要打包的文件（ZIP 模式用），目录递归展开，文件直接收。 */
private fun collectExportFiles(gameDir: Path, picks: Set<String>): List<Path> {
    val out = ArrayList<Path>()
    for (item in EXPORT_ITEMS) {
        if (item.id !in picks) continue
        val path = gameDir.resolve(item.rel)
        try {
            if (item.directory) {
                if (!Files.isDirectory(path)) continue
                Files.walk(path).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { out.add(it) }
                }
            } else if (Files.isRegularFile(path)) {
                out.add(path)
            }
        } catch (_: Throwable) {
            // 单项收集失败不影响其余内容
        }
    }
    return out
}

/**
 * 流式打包。逐个文件写入，每 16 个回调一次进度；单个文件读写失败就跳过并计数，
 * 不让一个坏文件毁掉整个导出。返回 (写入数, 跳过数)。
 */
private suspend fun zipSelected(
    gameDir: Path,
    files: List<Path>,
    out: Path,
    onProgress: (done: Int, total: Int, name: String) -> Unit,
): Pair<Int, Int> = withContext(Dispatchers.IO) {
    out.parent?.let { runCatching { Files.createDirectories(it) } }
    var written = 0
    var skipped = 0
    val total = files.size
    ZipOutputStream(BufferedOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8).use { zos ->
        val seen = HashSet<String>()
        val buffer = ByteArray(64 * 1024)
        files.forEachIndexed { index, file ->
            currentCoroutineContext().ensureActive()
            val rel = gameDir.relativize(file).toString().replace('\\', '/')
            // 同名条目（符号链接或大小写冲突）会让 ZipOutputStream 抛异常，去重后跳过
            if (!seen.add(rel)) {
                skipped++
                return@forEachIndexed
            }
            try {
                zos.putNextEntry(ZipEntry(rel))
                Files.newInputStream(file).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        zos.write(buffer, 0, n)
                    }
                }
                zos.closeEntry()
                written++
            } catch (_: Throwable) {
                skipped++
                runCatching { zos.closeEntry() }
            }
            if (index % 16 == 0 || index == total - 1) {
                onProgress(index + 1, total, file.fileName.toString())
            }
        }
    }
    Pair(written, skipped)
}

/** 实例导出分享包：四种格式，三种复用 core，ZIP 走本地流式打包。 */
@Composable
internal fun ExportEditor(vm: LauncherViewModel, target: StudioTarget, onReveal: (Path) -> Unit) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    val dir by produceState<Path?>(null, target) { value = withContext(Dispatchers.IO) { vm.gameDirOf(target) } }

    var formatName by rememberSaveable(target) { mutableStateOf(ExportFormat.ZIP.name) }
    var picksCsv by rememberSaveable(target) { mutableStateOf(DEFAULT_PICKS) }
    var outPath by rememberSaveable(target) { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var fraction by remember { mutableStateOf<Float?>(null) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var doneFile by remember { mutableStateOf<Path?>(null) }

    val fmt = runCatching { ExportFormat.valueOf(formatName) }.getOrDefault(ExportFormat.ZIP)
    val picks = remember(picksCsv) { picksCsv.split(",").filter { it.isNotBlank() }.toSet() }

    val instances by vm.instances.collectAsState()
    val instanceInfo = remember(target, instances) { instances.firstOrNull { it.instanceId == target.id } }

    // 各项体积，逐项后台算，避免一次卡住首屏
    val sizes = remember(dir) { mutableStateMapOf<String, Long>() }
    LaunchedEffect(dir) {
        val d = dir ?: return@LaunchedEffect
        for (item in EXPORT_ITEMS) {
            if (sizes.containsKey(item.id)) continue
            val bytes = withContext(Dispatchers.IO) { sizeOfItem(d, item) }
            sizes[item.id] = bytes
        }
    }

    val suggested = remember(target, fmt) { defaultFileName(target, fmt) }
    val effective = outPath ?: downloadsDir().resolve(suggested).toString()
    val selectedBytes = EXPORT_ITEMS.filter { it.id in picks }.sumOf { sizes[it.id] ?: 0L }
    val totalBytes = sizes.values.sum()
    val zipMode = fmt == ExportFormat.ZIP

    fun reset() {
        error = null
        summary = null
        doneFile = null
        fraction = null
        stage = ""
    }

    fun start() {
        val d = dir
        if (d == null || !Files.isDirectory(d)) {
            error = I18n.t("studio.export_no_dir")
            return
        }
        if (zipMode && picks.isEmpty()) {
            error = I18n.t("studio.export_empty")
            return
        }
        if (!zipMode && !Files.isDirectory(d.resolve("mods"))) {
            error = I18n.t("studio.export_no_mods")
            return
        }
        if (fmt == ExportFormat.INSTANCE && instanceInfo == null) {
            error = I18n.t("studio.export_no_instance")
            return
        }
        val out = runCatching { Paths.get(effective) }.getOrNull()
        if (out == null) {
            error = I18n.t("studio.export_bad_path")
            return
        }
        reset()
        running = true
        job = scope.launch {
            try {
                when (fmt) {
                    ExportFormat.ZIP -> {
                        stage = I18n.t("studio.export_collecting")
                        val files = withContext(Dispatchers.IO) { collectExportFiles(d, picks) }
                        if (files.isEmpty()) {
                            error = I18n.t("studio.export_nothing")
                        } else {
                            val (written, skipped) = zipSelected(d, files, out) { done, total, name ->
                                fraction = if (total <= 0) null else done.toFloat() / total
                                stage = I18n.t("studio.export_progress", done.toString(), total.toString(), name)
                            }
                            summary = if (skipped == 0) {
                                I18n.t("studio.export_written", written.toString())
                            } else {
                                I18n.t("studio.export_written_skipped", written.toString(), skipped.toString())
                            }
                            doneFile = out
                        }
                    }

                    ExportFormat.MRPACK, ExportFormat.CURSEFORGE -> withContext(Dispatchers.IO) {
                        val manager = vm.core.modpacks()
                        val onProgress = java.util.function.Consumer<InstallProgress> { pr ->
                            fraction = if (pr.total <= 0) null else pr.completed.toFloat() / pr.total
                            stage = pr.message ?: pr.stage.name
                        }
                        val future = if (fmt == ExportFormat.MRPACK) {
                            manager.exportModpack(target.id, out, onProgress)
                        } else {
                            manager.exportCurseForge(target.id, out, onProgress)
                        }
                        future.join()
                        doneFile = out
                    }

                    ExportFormat.INSTANCE -> {
                        val info = instanceInfo
                        if (info == null) {
                            error = I18n.t("studio.export_no_instance")
                        } else {
                            stage = I18n.t("studio.export_running")
                            val mods = withContext(Dispatchers.IO) { InstanceExporter.export(info, out) }
                            summary = I18n.t("studio.export_instance_done", mods.toString())
                            doneFile = out
                        }
                    }
                }
            } catch (ce: CancellationException) {
                stage = ""
                summary = I18n.t("studio.export_cancelled")
                runCatching { Files.deleteIfExists(out) }
                throw ce
            } catch (t: Throwable) {
                error = (t.message ?: t.javaClass.simpleName).take(400)
            } finally {
                running = false
                fraction = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.editor)) {
        ModuleHero(
            I18n.t("studio.export") + " · " + target.name,
            if (target.kind == TargetKind.INSTANCE) I18n.t("studio.export_sub_instance") else I18n.t("studio.export_sub_version", target.versionId),
            Tiles.Navy,
            Icons.Filled.Share
        ) {
            if (running) {
                HeroButton(I18n.t("studio.export_cancel"), Icons.Filled.Stop, Tiles.Red, { job?.cancel() })
            } else {
                HeroButton(I18n.t("studio.export_run"), Icons.Filled.Upload, Tiles.Forest, { start() })
            }
            HeroButton(I18n.t("studio.reveal_dir"), Icons.Filled.FolderOpen, Tiles.Gold, { dir?.let { onReveal(it) } }, enabled = dir != null)
        }

        TileRow {
            ColorTile(I18n.t("studio.export_total_size"), formatSize(totalBytes), Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Storage, index = 0)
            ColorTile(
                I18n.t("studio.export_selected_size"),
                if (zipMode) formatSize(selectedBytes) else "—",
                if (zipMode && selectedBytes > BIG_EXPORT_BYTES) Tiles.Orange else Tiles.Blue,
                Modifier.weight(1f).fillMaxHeight(),
                Icons.Filled.Inventory2,
                index = 1
            )
            ColorTile(I18n.t("studio.export_format"), fmt.name, Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Archive, index = 2)
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            LabelBar(I18n.t("studio.export_format"), Tiles.Navy)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExportFormat.entries.forEach { candidate ->
                    val enabled = candidate != ExportFormat.INSTANCE || target.kind == TargetKind.INSTANCE
                    Block(
                        candidate.name,
                        {
                            if (!enabled) return@Block
                            formatName = candidate.name
                            outPath = null
                            reset()
                        },
                        icon = Icons.Filled.Archive,
                        fill = if (candidate == fmt) Tiles.Navy else null,
                        enabled = enabled,
                        selected = candidate == fmt,
                        height = 32.dp
                    )
                }
            }
            FlatText(
                I18n.t(
                    when (fmt) {
                        ExportFormat.ZIP -> "studio.export_hint_zip"
                        ExportFormat.MRPACK -> "studio.export_hint_mrpack"
                        ExportFormat.CURSEFORGE -> "studio.export_hint_curseforge"
                        ExportFormat.INSTANCE -> "studio.export_hint_instance"
                    }
                ),
                color = p.muted,
                size = 11,
                lines = 4,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(Modifier.height(14.dp))
            LabelBar(I18n.t("studio.export_content"), Tiles.Sky)
            if (!zipMode) {
                FlatText(
                    I18n.t("studio.export_content_locked"),
                    color = p.muted,
                    size = 11,
                    lines = 3,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            EXPORT_ITEMS.forEachIndexed { index, item ->
                val bytes = sizes[item.id]
                val on = item.id in picks
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (index % 2 == 0) p.editor else p.panel)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlatSwitch(
                        checked = on && zipMode,
                        enabled = zipMode,
                        onChange = { checked ->
                            val next = picks.toMutableSet()
                            if (checked) next.add(item.id) else next.remove(item.id)
                            picksCsv = EXPORT_ITEMS.filter { it.id in next }.joinToString(",") { it.id }
                            reset()
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    FlatText(
                        if (item.directory) item.rel + "/" else item.rel,
                        color = if (zipMode) p.text else p.muted,
                        weight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    FlatText(
                        when (bytes) {
                            null -> "…"
                            0L -> I18n.t("studio.export_missing")
                            else -> formatSize(bytes)
                        },
                        color = p.muted,
                        size = 11
                    )
                }
            }
            if (zipMode && selectedBytes > BIG_EXPORT_BYTES) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = p.warn, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    FlatText(I18n.t("studio.export_big_warn", formatSize(selectedBytes)), color = p.warn, size = 11, lines = 3)
                }
            }

            Spacer(Modifier.height(14.dp))
            LabelBar(I18n.t("studio.export_output"), Tiles.Teal)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FlatText(effective, color = p.text, size = 11, lines = 2, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Block(
                    I18n.t("studio.export_browse"),
                    {
                        pickSaveFile(I18n.t("studio.export"), suggested)?.let { picked ->
                            outPath = picked.toString()
                            reset()
                        }
                    },
                    icon = Icons.Filled.FolderOpen,
                    height = 30.dp
                )
            }

            if (running || fraction != null || stage.isNotEmpty()) {
                ProgressLine(fraction, if (running) Tiles.Forest else Tiles.Slate, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                if (stage.isNotEmpty()) {
                    FlatText(stage, color = p.muted, size = 11, lines = 2, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
            if (!zipMode) {
                FlatText(
                    I18n.t("studio.export_core_cancel_warn"),
                    color = p.muted,
                    size = 10,
                    lines = 3,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            summary?.let { text ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = p.ok, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    val size = doneFile?.let { runCatching { Files.size(it) }.getOrDefault(0L) } ?: 0L
                    FlatText(text + (if (size > 0) " · " + formatSize(size) else ""), color = p.ok, size = 12, lines = 2, modifier = Modifier.weight(1f))
                    doneFile?.let { file ->
                        Block(
                            I18n.t("studio.export_show"),
                            { onReveal(file) },
                            icon = Icons.Filled.FolderOpen,
                            fill = Tiles.Forest,
                            height = 30.dp
                        )
                    }
                }
            }
            error?.let { msg ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = p.error, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    FlatText(msg, color = p.error, size = 12, lines = 4, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
