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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.launch.CrashAnalyzer
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.clearGameLogs
import com.pmcl.ui.viewmodel.openGameLogFolder
import com.pmcl.ui.viewmodel.scanAllCrashReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

internal fun bottomLabel(tool: BottomTool): String = when (tool) {
    BottomTool.RUN -> I18n.t("studio.tool_run")
    BottomTool.SEARCH -> I18n.t("studio.tool_search")
    BottomTool.TERMINAL -> I18n.t("studio.tool_terminal")
    BottomTool.PROBLEMS -> I18n.t("studio.tool_problems")
}

internal fun bottomIcon(tool: BottomTool): ImageVector = when (tool) {
    BottomTool.RUN -> Icons.AutoMirrored.Filled.Article
    BottomTool.SEARCH -> Icons.Filled.Search
    BottomTool.TERMINAL -> Icons.Filled.Terminal
    BottomTool.PROBLEMS -> Icons.Filled.BugReport
}

internal fun bottomColor(tool: BottomTool): Color = when (tool) {
    BottomTool.RUN -> Tiles.Blue
    BottomTool.SEARCH -> Tiles.Navy
    BottomTool.TERMINAL -> Tiles.Graphite
    BottomTool.PROBLEMS -> Tiles.Slate
}

@Composable
internal fun BottomTabs(active: BottomTool, onSelect: (BottomTool) -> Unit, onHide: () -> Unit) {
    val p = LocalStudio.current
    Row(Modifier.fillMaxWidth().height(30.dp).background(p.strip), verticalAlignment = Alignment.CenterVertically) {
        BottomTool.entries.forEach { tool ->
            val on = tool == active
            val color = bottomColor(tool)
            Row(
                Modifier
                    .fillMaxHeight()
                    .background(if (on) color else Color.Transparent)
                    .clickable { onSelect(tool) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(bottomIcon(tool), contentDescription = null, tint = if (on) Color.White else color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                FlatText(bottomLabel(tool), color = if (on) Color.White else p.text, weight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.weight(1f))
        Block("", onClick = onHide, icon = Icons.Filled.Close, height = 30.dp)
    }
}

@Composable
internal fun RunLogPanel(vm: LauncherViewModel) {
    val p = LocalStudio.current
    val clipboard = LocalClipboardManager.current
    val logs by vm.gameLogs.collectAsState()
    var query by remember { mutableStateOf("") }
    var level by remember { mutableIntStateOf(0) }
    var follow by remember { mutableStateOf(true) }
    val q = query.trim()
    val shown = remember(logs, q, level) {
        logs.filter { entry ->
            val t = entry.text
            val levelOk = when (level) {
                1 -> t.contains("WARN") || t.contains("ERROR") || t.contains("FATAL") || t.contains("Exception")
                2 -> t.contains("ERROR") || t.contains("FATAL") || t.contains("Exception")
                else -> true
            }
            levelOk && (q.isEmpty() || t.contains(q, ignoreCase = true))
        }
    }
    val counts = remember(logs) {
        var warn = 0
        var error = 0
        for (e in logs) {
            if (e.text.contains("ERROR") || e.text.contains("FATAL")) error++ else if (e.text.contains("WARN")) warn++
        }
        warn to error
    }
    val list = rememberLazyListState()
    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) list.scrollToItem(shown.size - 1)
    }
    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(30.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            FlatField(query, { query = it }, I18n.t("studio.filter"), Modifier.width(220.dp), Icons.Filled.Search)
            Block(I18n.t("studio.level_all") + " " + logs.size, onClick = { level = 0 }, selected = level == 0, height = 30.dp)
            Block(I18n.t("studio.level_warn") + " " + counts.first, onClick = { level = 1 }, fill = if (level == 1) Tiles.Orange else null, height = 30.dp)
            Block(I18n.t("studio.level_error") + " " + counts.second, onClick = { level = 2 }, fill = if (level == 2) Tiles.Red else null, height = 30.dp)
            Spacer(Modifier.weight(1f))
            Block("", onClick = { clipboard.setText(AnnotatedString(shown.joinToString("\n") { it.text })) }, icon = Icons.Filled.ContentCopy, height = 30.dp)
            Block("", onClick = { follow = !follow }, icon = Icons.Filled.VerticalAlignBottom, selected = follow, height = 30.dp)
            Block("", onClick = { vm.clearGameLogs() }, icon = Icons.Filled.DeleteSweep, height = 30.dp)
            Block("", onClick = { vm.openGameLogFolder() }, icon = Icons.Filled.FolderOpen, height = 30.dp)
        }
        if (shown.isEmpty()) {
            CenterNote(I18n.t("studio.log_empty"))
            return@Column
        }
        val hScroll = rememberScrollState()
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(hScroll), state = list) {
            items(shown, key = { it.seq }) { entry ->
                val color = lineColor(entry.text, p)
                Row {
                    Box(
                        Modifier.width(3.dp).height(16.dp).background(
                            when (color) {
                                p.error -> Tiles.Red
                                p.warn -> Tiles.Orange
                                else -> Color.Transparent
                            }
                        )
                    )
                    Text(entry.text, style = mono.copy(color = color), softWrap = false, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
internal fun ProblemsPanel(
    vm: LauncherViewModel,
    target: StudioTarget?,
    onOpen: (StudioTab.FileTab) -> Unit,
) {
    val p = LocalStudio.current
    var reload by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Path?>(null) }
    val reports by produceState<List<CrashAnalyzer.CrashReport>?>(null, target?.versionId, reload) {
        value = null
        value = withContext(Dispatchers.IO) {
            try { vm.scanAllCrashReports(target?.versionId) } catch (_: Throwable) { emptyList() }
        }
    }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    Row(Modifier.fillMaxSize().background(p.editor)) {
        Column(Modifier.weight(0.45f).fillMaxHeight().background(p.panel)) {
            Row(Modifier.fillMaxWidth().height(30.dp).background(p.panelAlt), verticalAlignment = Alignment.CenterVertically) {
                FlatText(
                    reports?.let { I18n.t("studio.problems_n", it.size.toString()) } ?: I18n.t("studio.loading"),
                    color = p.muted,
                    size = 11,
                    modifier = Modifier.padding(horizontal = 10.dp).weight(1f)
                )
                Block("", onClick = { reload++ }, icon = Icons.Filled.Refresh, height = 30.dp)
            }
            val list = reports.orEmpty()
            if (reports != null && list.isEmpty()) {
                CenterNote(I18n.t("studio.problems_none"), p.ok)
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { _, r -> r.file.toString() }) { index, report ->
                    val selected = picked == report.file
                    val modified = remember(report.file) {
                        try { Files.getLastModifiedTime(report.file).toMillis() } catch (_: Throwable) { 0L }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .background(if (selected) Tiles.Blue else if (index % 2 == 0) p.panel else p.panelAlt)
                            .clickable { picked = report.file },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(42.dp).background(if (selected) Tiles.Blue.lift(0.14f) else Tiles.Red.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = if (selected) Color.White else Tiles.Red, modifier = Modifier.size(16.dp))
                        }
                        val ink = if (selected) Color.White else p.text
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            FlatText(report.file.fileName.toString(), color = ink)
                            FlatText(
                                (report.causes.firstOrNull() ?: I18n.t("studio.cause_unknown")) +
                                    if (modified > 0) " · " + fmt.format(Date(modified)) else "",
                                color = ink.copy(alpha = 0.7f),
                                size = 11
                            )
                        }
                    }
                }
            }
        }
        val report = reports?.firstOrNull { it.file == picked }
        Column(Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (report == null) {
                FlatText(I18n.t("studio.problems_pick"), color = p.muted, modifier = Modifier.padding(12.dp))
                return@Column
            }
            FlatText(I18n.t("studio.causes"), color = Color.White, weight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().background(Tiles.Graphite).padding(horizontal = 12.dp, vertical = 7.dp))
            report.causes.ifEmpty { listOf(I18n.t("studio.cause_unknown")) }.forEach {
                FlatText(it, lines = 4, modifier = Modifier.fillMaxWidth().background(Tiles.Red.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            if (report.suggestions.isNotEmpty()) {
                FlatText(I18n.t("studio.suggestions"), color = Color.White, weight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().background(Tiles.Blue).padding(horizontal = 12.dp, vertical = 7.dp))
                report.suggestions.forEach {
                    FlatText(it, lines = 4, modifier = Modifier.fillMaxWidth().background(Tiles.Blue.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Block(
                I18n.t("studio.open_report"),
                onClick = {
                    val file = report.file
                    val parent = file.parent
                    val root = if (parent?.fileName?.toString() == "crash-reports") parent.parent ?: parent else parent
                    if (root != null) onOpen(StudioTab.FileTab(root, file, readOnly = true))
                },
                fill = p.accent,
                modifier = Modifier.fillMaxWidth(),
                height = 36.dp
            )
        }
    }
}
