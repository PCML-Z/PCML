package com.pmcl.ui.studio

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private val storageShades = listOf(Tiles.Blue, Tiles.Navy, Tiles.Sky, Tiles.Teal, Tiles.Plum, Tiles.Slate, Tiles.Graphite, Tiles.Gold)

/** 空间占用：游戏目录下每个文件夹占多少，用一条拼接色带和逐行长条展示。 */
@Composable
internal fun StorageEditor(vm: LauncherViewModel, target: StudioTarget, onReveal: (Path) -> Unit, onConfirm: (String, String, () -> Unit) -> Unit) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val dir by produceState<Path?>(null, target) { value = withContext(Dispatchers.IO) { vm.gameDirOf(target) } }
    val entries by produceState(emptyList<StorageEntry>(), dir, reload) {
        val d = dir
        value = if (d == null) emptyList() else withContext(Dispatchers.IO) { storageChildren(d) }
    }
    val sizes = remember(dir, reload) { mutableStateMapOf<Path, Long>() }
    LaunchedEffect(entries) {
        for (e in entries) {
            if (sizes.containsKey(e.path)) continue
            sizes[e.path] = withContext(Dispatchers.IO) {
                if (e.directory) dirSize(e.path, 300_000) else try { Files.size(e.path) } catch (_: Throwable) { 0L }
            }
        }
    }
    val oldLogs by produceState(0 to 0L, dir, reload) {
        val d = dir
        value = if (d == null) 0 to 0L else withContext(Dispatchers.IO) { countOldLogs(d) }
    }
    val done = entries.isNotEmpty() && entries.all { sizes.containsKey(it.path) }
    val sorted = entries.sortedByDescending { sizes[it.path] ?: -1L }
    val total = sizes.values.sum().coerceAtLeast(1L)
    val top = sorted.filter { (sizes[it.path] ?: 0L) > 0 }.take(storageShades.size)

    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(96.dp)) {
            ColorTile(
                I18n.t("studio.storage_total"),
                if (done) formatSize(sizes.values.sum()) else I18n.t("studio.storage_scanning", sizes.size.toString(), entries.size.toString()),
                Tiles.Blue,
                Modifier.weight(2f).fillMaxHeight(),
                Icons.Filled.Folder,
                valueSize = 24
            )
            ColorTile(I18n.t("studio.storage_items"), entries.size.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), index = 1)
            ColorTile(
                I18n.t("studio.storage_old_logs"),
                if (oldLogs.first > 0) formatSize(oldLogs.second) else "0",
                Tiles.Slate,
                Modifier.weight(1f).fillMaxHeight(),
                Icons.Filled.CleaningServices,
                onClick = if (oldLogs.first > 0) ({
                    val d = dir
                    if (d != null) {
                        onConfirm(
                            I18n.t("studio.clean_logs_title"),
                            I18n.t("studio.clean_logs_body", oldLogs.first.toString(), formatSize(oldLogs.second))
                        ) {
                            scope.launch {
                                val (count, freed) = withContext(Dispatchers.IO) { cleanOldLogs(d) }
                                message = I18n.t("studio.cleaned", count.toString(), formatSize(freed))
                                reload++
                            }
                        }
                    }
                }) else null,
                index = 2
            )
            Column(Modifier.width(56.dp).fillMaxHeight()) {
                Block("", onClick = { reload++ }, icon = Icons.Filled.Refresh, fill = Tiles.Graphite, modifier = Modifier.fillMaxWidth().weight(1f), height = 48.dp)
                Block("", onClick = { dir?.let(onReveal) }, icon = Icons.Filled.FolderOpen, fill = Tiles.Gold, modifier = Modifier.fillMaxWidth().weight(1f), height = 48.dp)
            }
        }
        message?.let {
            FlatText(it, color = Color.White, modifier = Modifier.fillMaxWidth().background(Tiles.Forest).padding(horizontal = 12.dp, vertical = 7.dp))
        }
        // 拼接色带：每段宽度按占比，长出来的动画
        BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp).background(p.panelAlt)) {
            val width = maxWidth
            Row(Modifier.fillMaxSize()) {
                top.forEachIndexed { i, e ->
                    val share = (sizes[e.path] ?: 0L).toFloat() / total
                    val grown by animateFloatAsState(if (done) share else 0f, tween(700, delayMillis = i * 60, easing = FastOutSlowInEasing), label = "share")
                    Box(Modifier.width(width * grown).fillMaxHeight().background(storageShades[i]), contentAlignment = Alignment.CenterStart) {
                        if (share > 0.08f) FlatText(e.name, color = Color.White, size = 10, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
        }
        val maxSize = (sizes.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(sorted, key = { _, e -> e.path.toString() }) { index, entry ->
                val size = sizes[entry.path]
                val shade = storageShades.getOrNull(top.indexOf(entry)) ?: Tiles.Slate.copy(alpha = 0.6f)
                val fraction by animateFloatAsState(
                    if (size == null) 0f else size.toFloat() / maxSize,
                    tween(600, easing = FastOutSlowInEasing),
                    label = "rowBar"
                )
                val source = remember { MutableInteractionSource() }
                val hovered by source.collectIsHoveredAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .entrance(index)
                        .background(if (hovered) p.hover else if (index % 2 == 0) p.editor else p.panel)
                        .hoverable(source)
                        .clickable(interactionSource = source, indication = null) { onReveal(entry.path) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(shade))
                    Icon(
                        if (entry.directory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = p.muted,
                        modifier = Modifier.padding(horizontal = 8.dp).size(15.dp)
                    )
                    FlatText(entry.name, modifier = Modifier.width(220.dp))
                    Box(Modifier.weight(1f).height(10.dp).background(p.panelAlt)) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(shade))
                    }
                    Text(
                        size?.let { formatSize(it) } ?: "…",
                        style = mono.copy(color = p.muted, textAlign = TextAlign.End),
                        modifier = Modifier.width(96.dp).padding(end = 12.dp)
                    )
                    Text(
                        size?.let { String.format("%.1f%%", it * 100.0 / total) } ?: "",
                        style = mono.copy(color = p.muted, textAlign = TextAlign.End),
                        modifier = Modifier.width(64.dp).padding(end = 12.dp)
                    )
                }
            }
        }
    }
}

internal data class DiffLine(val kind: Int, val oldNo: Int?, val newNo: Int?, val text: String)

private const val MAX_DIFF_CELLS = 2_500_000L

/** 行级 diff：先去掉相同的头尾，中间用 LCS。太大时返回 null。 */
internal fun diffLines(a: List<String>, b: List<String>): List<DiffLine>? {
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start++
    var endA = a.size
    var endB = b.size
    while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
    val m = endA - start
    val n = endB - start
    if (m.toLong() * n.toLong() > MAX_DIFF_CELLS) return null
    val out = ArrayList<DiffLine>()
    for (i in 0 until start) out += DiffLine(0, i + 1, i + 1, a[i])
    val table = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) for (j in n - 1 downTo 0) {
        table[i][j] = if (a[start + i] == b[start + j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
    }
    var i = 0
    var j = 0
    while (i < m || j < n) {
        when {
            i < m && j < n && a[start + i] == b[start + j] -> { out += DiffLine(0, start + i + 1, start + j + 1, a[start + i]); i++; j++ }
            j < n && (i >= m || table[i][j + 1] >= table[i + 1][j]) -> { out += DiffLine(1, null, start + j + 1, b[start + j]); j++ }
            else -> { out += DiffLine(-1, start + i + 1, null, a[start + i]); i++ }
        }
    }
    for (k in 0 until a.size - endA) out += DiffLine(0, endA + k + 1, endB + k + 1, a[endA + k])
    return out
}

/** 把没改动的长段折叠成一行，只留改动上下 3 行。 */
internal fun foldDiff(lines: List<DiffLine>, context: Int = 3): List<Any> {
    val keep = BooleanArray(lines.size)
    lines.forEachIndexed { idx, l ->
        if (l.kind != 0) for (k in (idx - context).coerceAtLeast(0)..(idx + context).coerceAtMost(lines.size - 1)) keep[k] = true
    }
    val out = ArrayList<Any>()
    var skipped = 0
    lines.forEachIndexed { idx, l ->
        if (keep[idx]) {
            if (skipped > 0) { out += skipped; skipped = 0 }
            out += l
        } else skipped++
    }
    if (skipped > 0) out += skipped
    return out
}

@Composable
internal fun DiffView(original: String, current: String, modifier: Modifier = Modifier) {
    val p = LocalStudio.current
    val diff = remember(original, current) { diffLines(original.lines(), current.lines()) }
    if (diff == null) {
        Box(modifier.background(p.editor)) { CenterNote(I18n.t("studio.diff_too_large")) }
        return
    }
    val rows = remember(diff) { foldDiff(diff) }
    val added = diff.count { it.kind == 1 }
    val removed = diff.count { it.kind == -1 }
    Column(modifier.background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(26.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            StatusSegment("+$added", Tiles.Forest, bold = true)
            StatusSegment("−$removed", Tiles.Red, bold = true)
            FlatText(I18n.t("studio.diff_hint"), color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 10.dp))
        }
        if (added == 0 && removed == 0) {
            CenterNote(I18n.t("studio.diff_same"))
            return@Column
        }
        val hScroll = rememberScrollState()
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            itemsIndexed(rows) { _, row ->
                when (row) {
                    is Int -> FlatText(
                        I18n.t("studio.diff_folded", row.toString()),
                        color = p.muted,
                        size = 11,
                        modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 12.dp, vertical = 3.dp)
                    )
                    is DiffLine -> Row(
                        Modifier.background(
                            when (row.kind) {
                                1 -> Tiles.Forest.copy(alpha = 0.14f)
                                -1 -> Tiles.Red.copy(alpha = 0.14f)
                                else -> Color.Transparent
                            }
                        )
                    ) {
                        Text(row.oldNo?.toString() ?: "", style = mono.copy(color = p.gutter, textAlign = TextAlign.End), modifier = Modifier.width(48.dp).padding(end = 6.dp))
                        Text(row.newNo?.toString() ?: "", style = mono.copy(color = p.gutter, textAlign = TextAlign.End), modifier = Modifier.width(48.dp).padding(end = 6.dp))
                        Box(
                            Modifier.width(18.dp).background(
                                when (row.kind) {
                                    1 -> Tiles.Forest
                                    -1 -> Tiles.Red
                                    else -> Color.Transparent
                                }
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when (row.kind) { 1 -> "+"; -1 -> "−"; else -> " " },
                                style = mono.copy(color = if (row.kind == 0) p.muted else Color.White, fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(row.text, style = mono.copy(color = p.text), softWrap = false)
                    }
                }
            }
        }
    }
}
