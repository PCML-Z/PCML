package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private const val LIVE_LOG_MAX_LINES = 5000
private const val LIVE_LOG_POLL_MS = 400L

private data class LiveLine(val seq: Long, val text: String)

/**
 * 实时日志查看器：轮询游戏目录下的 logs/latest.log，把新增内容追加进来。
 * 文件变小时视为日志轮替，从头重读；暂停时停止拉取，界面内容保留。
 * 点一行可选中，选中后底部给出「反查到的模组」和跳崩溃分析器的入口。
 */
@Composable
internal fun LiveLogEditor(
    vm: LauncherViewModel,
    target: StudioTarget,
    onOpenCrash: () -> Unit = {},
    onOpenMods: () -> Unit = {},
) {
    val p = LocalStudio.current
    val clipboard = LocalClipboardManager.current
    val dir by produceState<Path?>(null, target) {
        value = withContext(Dispatchers.IO) { vm.gameDirOf(target) }
    }
    // 读全部模组 jar 的元数据要好几秒，等用户真的点了某一行再扫
    var wantMods by remember { mutableStateOf(false) }
    val modIds by produceState<List<Pair<String, String>>>(emptyList(), dir, wantMods) {
        value = if (!wantMods) emptyList() else withContext(Dispatchers.IO) { collectModIds(dir) }
    }
    val lines = remember(target) { mutableStateListOf<LiveLine>() }
    var query by remember { mutableStateOf("") }
    var level by remember { mutableIntStateOf(0) }
    var follow by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var seq = remember { mutableLongStateOf(0L) }

    LaunchedEffect(dir, paused) {
        if (paused) return@LaunchedEffect
        val file = dir?.resolve("logs")?.resolve("latest.log")
        if (file == null) return@LaunchedEffect
        var offset = 0L
        var pending = ""
        while (true) {
            withContext(Dispatchers.IO) {
                try {
                    if (!Files.isRegularFile(file)) {
                        missing = true
                        return@withContext
                    }
                    missing = false
                    val size = Files.size(file)
                    if (size < offset) {
                        // 日志轮替：文件被截断或重写，从头再来
                        offset = 0L
                        pending = ""
                        lines.clear()
                    }
                    if (size > offset) {
                        val chunk = RandomAccessFile(file.toFile(), "r").use { raf ->
                            raf.seek(offset)
                            val want = (size - offset).coerceAtMost(8L * 1024 * 1024).toInt()
                            val bytes = ByteArray(want)
                            var read = 0
                            while (read < want) {
                                val n = raf.read(bytes, read, want - read)
                                if (n <= 0) break
                                read += n
                            }
                            if (read <= 0) "" else String(bytes, 0, read, StandardCharsets.UTF_8)
                        }
                        offset += chunk.toByteArray(StandardCharsets.UTF_8).size.toLong()
                        val parts = (pending + chunk).split('\n')
                        pending = parts.last()
                        for (i in 0 until parts.size - 1) {
                            lines.add(LiveLine(seq.longValue++, parts[i].trimEnd('\r')))
                        }
                        while (lines.size > LIVE_LOG_MAX_LINES) lines.removeAt(0)
                    }
                } catch (_: Throwable) {
                }
            }
            delay(LIVE_LOG_POLL_MS)
        }
    }

    val q = query.trim()
    val shown = remember(lines.size, q, level) {
        lines.filter { entry ->
            val t = entry.text
            val levelOk = when (level) {
                1 -> t.contains("WARN") || t.contains("ERROR") || t.contains("FATAL") || t.contains("Exception")
                2 -> t.contains("ERROR") || t.contains("FATAL") || t.contains("Exception")
                else -> true
            }
            levelOk && (q.isEmpty() || t.contains(q, ignoreCase = true))
        }
    }
    val counts = remember(lines.size) {
        var warn = 0
        var error = 0
        for (e in lines) {
            if (e.text.contains("ERROR") || e.text.contains("FATAL")) error++
            else if (e.text.contains("WARN")) warn++
        }
        warn to error
    }
    val list = rememberLazyListState()
    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) list.scrollToItem(shown.size - 1)
    }

    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(36.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(if (paused) Tiles.Orange else Tiles.Forest), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.Article,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            FlatText(
                target.name + " · logs/latest.log",
                weight = androidx.compose.ui.text.font.FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            if (paused) FlatText(I18n.t("studio.live_paused"), color = Tiles.Orange, size = 11)
            Spacer(Modifier.weight(1f))
            FlatField(query, { query = it }, I18n.t("studio.filter"), Modifier.width(200.dp), Icons.Filled.Search)
            Block(I18n.t("studio.level_all") + " " + lines.size, onClick = { level = 0 }, selected = level == 0, height = 36.dp)
            Block(I18n.t("studio.level_warn") + " " + counts.first, onClick = { level = 1 }, fill = if (level == 1) Tiles.Orange else null, height = 36.dp)
            Block(I18n.t("studio.level_error") + " " + counts.second, onClick = { level = 2 }, fill = if (level == 2) Tiles.Red else null, height = 36.dp)
            Block(
                I18n.t("studio.crash"),
                onClick = onOpenCrash,
                icon = Icons.Filled.BugReport,
                fill = if (counts.second > 0) Tiles.Red else null,
                height = 36.dp
            )
            Block(
                "",
                onClick = { clipboard.setText(AnnotatedString(shown.joinToString("\n") { it.text })) },
                icon = Icons.Filled.ContentCopy,
                enabled = shown.isNotEmpty(),
                height = 36.dp
            )
            Block("", onClick = { follow = !follow }, icon = Icons.Filled.VerticalAlignBottom, selected = follow, height = 36.dp)
            Block("", onClick = { lines.clear() }, icon = Icons.Filled.DeleteSweep, height = 36.dp)
            Block(
                "",
                onClick = { paused = !paused },
                icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                fill = if (paused) Tiles.Forest else null,
                height = 36.dp
            )
            Block("", onClick = { dir?.let { vm.openDir(it.resolve("logs").toFile()) } }, icon = Icons.Filled.FolderOpen, enabled = dir != null, height = 36.dp)
        }
        when {
            dir == null -> CenterNote(I18n.t("studio.loading"))
            missing && lines.isEmpty() -> CenterNote(I18n.t("studio.live_missing"))
            shown.isEmpty() -> CenterNote(I18n.t("studio.log_empty"))
            else -> {
                val hScroll = rememberScrollState()
                LazyColumn(Modifier.weight(1f).fillMaxWidth().horizontalScroll(hScroll), state = list) {
                    itemsIndexed(shown, key = { _, it -> it.seq }) { _, entry ->
                        val color = lineColor(entry.text, p)
                        val on = entry.seq == selected
                        Row(
                            Modifier
                                .background(if (on) p.selected else Color.Transparent)
                                .clickable {
                                    if (on) {
                                        selected = null
                                    } else {
                                        selected = entry.seq
                                        wantMods = true
                                    }
                                }
                        ) {
                            Box(
                                Modifier.width(3.dp).height(16.dp).background(
                                    when {
                                        on -> p.accent
                                        color == p.error -> Tiles.Red
                                        color == p.warn -> Tiles.Orange
                                        else -> Color.Transparent
                                    }
                                )
                            )
                            Text(entry.text, style = mono.copy(color = color), softWrap = false, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                val picked = shown.firstOrNull { it.seq == selected } ?: lines.firstOrNull { it.seq == selected }
                if (picked != null) {
                    // 反查这行里出现的模组：modId 要求整词匹配（"jei" 这类短 id 否则会误命中），
                    // 模组名长度够就按包含匹配。
                    val hits = remember(picked.text, modIds) {
                        val lower = picked.text.lowercase(Locale.ROOT)
                        modIds.filter { (id, name) ->
                            (id.length >= 2 && Regex("(?<![a-z0-9_])" + Regex.escape(id.lowercase(Locale.ROOT)) + "(?![a-z0-9_])").containsMatchIn(lower)) ||
                                (name.length >= 4 && lower.contains(name.lowercase(Locale.ROOT)))
                        }
                    }
                    Column(Modifier.fillMaxWidth().background(p.panel)) {
                        Row(
                            Modifier.fillMaxWidth().height(30.dp).background(p.panelAlt),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FlatText(
                                picked.text.trim().take(200),
                                color = p.text,
                                size = 11,
                                modifier = Modifier.padding(horizontal = 10.dp).weight(1f)
                            )
                            Block(
                                "",
                                onClick = { clipboard.setText(AnnotatedString(picked.text)) },
                                icon = Icons.Filled.ContentCopy,
                                height = 30.dp
                            )
                            Block(
                                I18n.t("studio.crash"),
                                onClick = onOpenCrash,
                                icon = Icons.Filled.BugReport,
                                fill = Tiles.Red,
                                height = 30.dp
                            )
                            Block("", onClick = { selected = null }, icon = Icons.Filled.Close, height = 30.dp)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FlatText(I18n.t("studio.log_mods_in_line"), color = p.muted, size = 11)
                            Spacer(Modifier.width(6.dp))
                            // 外层给 weight 定宽，内层才横向滚动；反过来会变成无限宽约束直接崩
                            Row(
                                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (hits.isEmpty()) {
                                    FlatText(
                                        if (modIds.isEmpty()) I18n.t("studio.loading") else I18n.t("studio.log_mods_none"),
                                        color = p.muted,
                                        size = 11
                                    )
                                } else {
                                    hits.forEach { (id, name) ->
                                        Block(
                                            name + "  " + id,
                                            onClick = { clipboard.setText(AnnotatedString(id)) },
                                            icon = Icons.Filled.Extension,
                                            fill = Tiles.Teal,
                                            height = 24.dp
                                        )
                                        Spacer(Modifier.width(5.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            Block(
                                I18n.t("studio.mods"),
                                onClick = onOpenMods,
                                icon = Icons.Filled.Extension,
                                height = 24.dp
                            )
                        }
                    }
                }
            }
        }
    }
}
