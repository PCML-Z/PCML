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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.install.IntegrityChecker
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 工程健康检查：Java、版本 JSON、崩溃、模组依赖、旧日志，外加一键完整性校验。 */
@Composable
internal fun DiagnoseEditor(
    vm: LauncherViewModel,
    target: StudioTarget,
    onAction: (String) -> Unit,
) {
    val p = LocalStudio.current
    var reload by remember { mutableIntStateOf(0) }
    var integrityBusy by remember { mutableStateOf(false) }
    var integrity by remember { mutableStateOf<IntegrityChecker.Result?>(null) }
    var integrityError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val items by produceState<List<DiagnoseItem>?>(null, target, reload) {
        value = null
        value = withContext(Dispatchers.IO) { vm.diagnoseOf(target) }
    }
    val list = items
    val errors = list?.count { it.level == DiagnoseLevel.ERROR } ?: 0
    val warns = list?.count { it.level == DiagnoseLevel.WARN } ?: 0
    val oks = list?.count { it.level == DiagnoseLevel.OK } ?: 0
    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(96.dp)) {
            ColorTile(
                I18n.t("studio.diagnose"),
                if (list == null) "…" else if (errors > 0) I18n.t("studio.diag_bad") else if (warns > 0) I18n.t("studio.diag_warn") else I18n.t("studio.diag_good"),
                when {
                    list == null -> Tiles.Slate
                    errors > 0 -> Tiles.Red
                    warns > 0 -> Tiles.Orange
                    else -> Tiles.Forest
                },
                Modifier.weight(2f).fillMaxHeight(),
                Icons.Filled.HealthAndSafety,
                valueSize = 22,
                index = 0
            )
            ColorTile(I18n.t("studio.diag_errors"), errors.toString(), Tiles.Red, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.ErrorOutline, index = 1)
            ColorTile(I18n.t("studio.diag_warns"), warns.toString(), Tiles.Orange, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.WarningAmber, index = 2)
            ColorTile(I18n.t("studio.diag_oks"), oks.toString(), Tiles.Forest, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.CheckCircle, index = 3)
        }
        Row(Modifier.fillMaxWidth().height(36.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            FlatText(target.name + " · " + target.versionId, color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 12.dp).weight(1f))
            Block(
                if (integrityBusy) I18n.t("studio.diag_checking") else I18n.t("studio.diag_integrity"),
                onClick = {
                    integrityBusy = true
                    integrityError = null
                    scope.launch {
                        try {
                            integrity = withContext(Dispatchers.IO) { vm.core.integrity().check(target.versionId) }
                        } catch (e: Throwable) {
                            integrity = null
                            integrityError = e.message ?: e.javaClass.simpleName
                        }
                        integrityBusy = false
                    }
                },
                fill = Tiles.Navy,
                enabled = !integrityBusy && target.versionId.isNotBlank(),
                height = 36.dp
            )
            Block("", onClick = { reload++; integrity = null; integrityError = null }, icon = Icons.Filled.Refresh, height = 36.dp)
            Block(
                "",
                onClick = {
                    val text = buildString {
                        appendLine("# " + target.name)
                        list.orEmpty().forEach { appendLine("- [${it.level}] ${it.title}: ${it.detail}") }
                        integrity?.let {
                            appendLine("- integrity missing=${it.missing.size} mismatch=${it.hashMismatch.size}")
                        }
                    }
                    clipboard.setText(AnnotatedString(text))
                },
                icon = Icons.Filled.ContentCopy,
                enabled = list != null,
                height = 36.dp
            )
        }
        integrityError?.let {
            FlatText(I18n.t("studio.diag_integrity_fail", it), color = Color.White, modifier = Modifier.fillMaxWidth().background(Tiles.Red).padding(10.dp))
        }
        integrity?.let { r ->
            val ok = r.isOk
            FlatText(
                if (ok) I18n.t("studio.diag_integrity_ok")
                else I18n.t("studio.diag_integrity_bad", r.issueCount.toString(), r.missing.size.toString(), r.hashMismatch.size.toString()),
                color = Color.White,
                modifier = Modifier.fillMaxWidth().background(if (ok) Tiles.Forest else Tiles.Orange).padding(10.dp)
            )
        }
        if (list == null) {
            CenterNote(I18n.t("studio.loading"))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list, key = { i, it -> it.title + i }) { index, item ->
                val color = when (item.level) {
                    DiagnoseLevel.OK -> Tiles.Forest
                    DiagnoseLevel.WARN -> Tiles.Orange
                    DiagnoseLevel.ERROR -> Tiles.Red
                    DiagnoseLevel.INFO -> Tiles.Sky
                }
                val icon = when (item.level) {
                    DiagnoseLevel.OK -> Icons.Filled.CheckCircle
                    DiagnoseLevel.WARN -> Icons.Filled.WarningAmber
                    DiagnoseLevel.ERROR -> Icons.Filled.ErrorOutline
                    DiagnoseLevel.INFO -> Icons.Filled.Info
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .entrance(index)
                        .background(if (index % 2 == 0) p.editor else p.panel)
                        .clickable(enabled = item.action != null) { item.action?.let(onAction) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(40.dp).background(color), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        FlatText(item.title, weight = FontWeight.Medium)
                        if (item.detail.isNotBlank()) FlatText(item.detail, color = p.muted, size = 11, lines = 3)
                    }
                    if (item.action != null) {
                        FlatText(I18n.t("studio.diag_open"), color = p.accent, size = 11)
                    }
                }
            }
        }
    }
}

/** 对比当前目标与另一个版本/实例的模组，并可把对方缺失的模组复制过来。 */
@Composable
internal fun CompareEditor(
    vm: LauncherViewModel,
    tab: StudioTab.Compare,
    onOther: (String?) -> Unit,
    onConfirm: (String, String, () -> Unit) -> Unit,
) {
    val p = LocalStudio.current
    val versions by vm.localVersionInfos.collectAsState()
    val instances by vm.instances.collectAsState()
    val (vs, insts) = remember(versions, instances) { targetsOf(versions, instances) }
    val others = remember(vs, insts, tab.target) { (vs + insts).filter { it.kind != tab.target.kind || it.id != tab.target.id } }
    val other = others.firstOrNull { targetKey(it) == tab.otherKey } ?: others.firstOrNull()
    var reload by remember { mutableIntStateOf(0) }
    var filter by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val leftDir = remember(tab.target, reload) { vm.gameDirOf(tab.target) }
    val rightDir = remember(other, reload) { other?.let { vm.gameDirOf(it) } }
    val leftMods by produceState(emptyList<ModFile>(), leftDir, reload) {
        value = withContext(Dispatchers.IO) { listMods(leftDir) }
    }
    val rightMods by produceState(emptyList<ModFile>(), rightDir, reload) {
        value = withContext(Dispatchers.IO) { listMods(rightDir) }
    }
    val rows = remember(leftMods, rightMods) { compareModLists(leftMods, rightMods) }
    val shown = when (filter) {
        1 -> rows.filter { it.onlyLeft }
        2 -> rows.filter { it.onlyRight }
        3 -> rows.filter { it.both && it.sizeDiff }
        else -> rows
    }
    val onlyLeft = rows.count { it.onlyLeft }
    val onlyRight = rows.count { it.onlyRight }
    val diffSize = rows.count { it.sizeDiff }
    fun copyMissing(fromRight: Boolean) {
        val dest = if (fromRight) leftDir else rightDir
        val sourceRows = if (fromRight) rows.filter { it.onlyRight } else rows.filter { it.onlyLeft }
        if (dest == null || sourceRows.isEmpty()) return
        onConfirm(
            I18n.t("studio.compare_copy_title"),
            I18n.t("studio.compare_copy_body", sourceRows.size.toString(), if (fromRight) tab.target.name else other?.name.orEmpty())
        ) {
            scope.launch {
                val n = withContext(Dispatchers.IO) {
                    var count = 0
                    for (row in sourceRows) {
                        val src = if (fromRight) row.right else row.left
                        if (src != null && copyModInto(src.path, dest)) count++
                    }
                    count
                }
                message = I18n.t("studio.compare_copied", n.toString())
                reload++
            }
        }
    }
    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(84.dp)) {
            ColorTile(tab.target.name, leftMods.size.toString(), Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Extension, index = 0)
            ColorTile(I18n.t("studio.compare"), onlyLeft.toString() + " / " + onlyRight.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.CompareArrows, index = 1)
            ColorTile(other?.name ?: I18n.t("studio.compare_pick"), (rightMods.size).toString(), Tiles.Teal, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Extension, index = 2)
        }
        Row(Modifier.fillMaxWidth().height(36.dp).background(p.panel).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            FlatText(I18n.t("studio.compare_vs"), color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 10.dp))
            others.take(24).forEach { t ->
                Block(
                    t.name,
                    onClick = { onOther(targetKey(t)) },
                    selected = other?.let { targetKey(it) } == targetKey(t),
                    fill = if (other?.let { targetKey(it) } == targetKey(t)) Tiles.Teal else null,
                    height = 36.dp
                )
            }
            Spacer(Modifier.weight(1f))
            Block(I18n.t("studio.compare_all") + " " + rows.size, onClick = { filter = 0 }, selected = filter == 0, height = 36.dp)
            Block(I18n.t("studio.compare_only_left") + " " + onlyLeft, onClick = { filter = 1 }, selected = filter == 1, height = 36.dp)
            Block(I18n.t("studio.compare_only_right") + " " + onlyRight, onClick = { filter = 2 }, selected = filter == 2, height = 36.dp)
            Block(I18n.t("studio.compare_size") + " " + diffSize, onClick = { filter = 3 }, selected = filter == 3, height = 36.dp)
            Block(I18n.t("studio.compare_pull"), onClick = { copyMissing(true) }, fill = Tiles.Blue, enabled = onlyRight > 0 && leftDir != null, height = 36.dp)
            Block(I18n.t("studio.compare_push"), onClick = { copyMissing(false) }, fill = Tiles.Teal, enabled = onlyLeft > 0 && rightDir != null, height = 36.dp)
            Block("", onClick = { reload++ }, icon = Icons.Filled.Refresh, height = 36.dp)
        }
        message?.let { FlatText(it, color = Color.White, modifier = Modifier.fillMaxWidth().background(Tiles.Forest).padding(8.dp)) }
        if (other == null) {
            CenterNote(I18n.t("studio.compare_empty"))
            return@Column
        }
        if (shown.isEmpty()) {
            CenterNote(I18n.t("studio.compare_same"))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown, key = { _, r -> r.key }) { index, row ->
                val color = when {
                    row.onlyLeft -> Tiles.Blue
                    row.onlyRight -> Tiles.Teal
                    row.sizeDiff -> Tiles.Orange
                    else -> p.strip
                }
                Row(
                    Modifier.fillMaxWidth().height(40.dp).entrance(index).background(if (index % 2 == 0) p.editor else p.panel),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(color))
                    FlatText(row.left?.name ?: "—", modifier = Modifier.weight(1f).padding(horizontal = 10.dp), color = if (row.left == null) p.muted else p.text)
                    FlatText(
                        when {
                            row.onlyLeft -> I18n.t("studio.compare_mark_left")
                            row.onlyRight -> I18n.t("studio.compare_mark_right")
                            row.sizeDiff -> I18n.t("studio.compare_mark_diff")
                            else -> "="
                        },
                        color = Color.White,
                        size = 11,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.width(56.dp).background(color).padding(vertical = 10.dp),
                    )
                    FlatText(row.right?.name ?: "—", modifier = Modifier.weight(1f).padding(horizontal = 10.dp), color = if (row.right == null) p.muted else p.text)
                    FlatText(
                        listOfNotNull(
                            row.left?.let { formatSize(it.size) },
                            row.right?.let { formatSize(it.size) }
                        ).distinct().joinToString(" / ").ifBlank { "" },
                        color = p.muted,
                        size = 11,
                        modifier = Modifier.width(120.dp)
                    )
                }
            }
        }
    }
}

internal fun targetKey(target: StudioTarget): String = (if (target.kind == TargetKind.VERSION) "V:" else "I:") + target.id

/** 当前目标游戏目录里的配置文件一览，可直接打开可编辑文本。 */
@Composable
internal fun ConfigsEditor(
    vm: LauncherViewModel,
    target: StudioTarget,
    onOpen: (Path, Path) -> Unit,
    onReveal: (Path) -> Unit,
) {
    val p = LocalStudio.current
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val gameDir = remember(target, reload) { vm.gameDirOf(target) }
    val entries by produceState(emptyList<ConfigEntry>(), gameDir, reload) {
        value = withContext(Dispatchers.IO) { listConfigEntries(gameDir) }
    }
    val q = query.trim()
    val shown = if (q.isEmpty()) entries else entries.filter {
        it.label.contains(q, true) || it.group.contains(q, true)
    }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    fun groupLabel(id: String) = when (id) {
        "game" -> I18n.t("studio.configs_game")
        "shaders" -> I18n.t("studio.configs_shaders")
        else -> I18n.t("studio.configs_mods")
    }
    fun groupColor(id: String) = when (id) {
        "game" -> Tiles.Slate
        "shaders" -> Tiles.Sky
        else -> Tiles.Teal
    }
    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(84.dp)) {
            ColorTile(I18n.t("studio.configs"), entries.size.toString(), Tiles.Graphite, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Tune, index = 0)
            ColorTile(I18n.t("studio.configs_game"), entries.count { it.group == "game" }.toString(), Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Settings, index = 1)
            ColorTile(I18n.t("studio.configs_mods"), entries.count { it.group == "mods" }.toString(), Tiles.Teal, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Extension, index = 2)
            ColorTile(I18n.t("studio.configs_shaders"), entries.count { it.group == "shaders" }.toString(), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Code, index = 3)
        }
        Row(Modifier.fillMaxWidth().height(36.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            FlatField(query, { query = it }, I18n.t("studio.filter"), Modifier.width(260.dp))
            Spacer(Modifier.weight(1f))
            Block("", onClick = { reload++ }, icon = Icons.Filled.Refresh, height = 36.dp)
            Block("", onClick = { gameDir?.let(onReveal) }, icon = Icons.Filled.FolderOpen, enabled = gameDir != null, height = 36.dp)
            Block("", onClick = { gameDir?.let { onReveal(it.resolve("config")) } }, icon = Icons.Filled.Storage, enabled = gameDir != null, height = 36.dp)
        }
        if (gameDir == null) {
            CenterNote(I18n.t("studio.pick_target"))
            return@Column
        }
        if (shown.isEmpty()) {
            CenterNote(I18n.t("studio.configs_empty"))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            var lastGroup = ""
            shown.forEachIndexed { index, entry ->
                if (entry.group != lastGroup) {
                    lastGroup = entry.group
                    item(key = "g:" + entry.group) {
                        FlatText(
                            groupLabel(entry.group),
                            color = Color.White,
                            weight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().background(groupColor(entry.group)).padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
                item(key = entry.path.toString()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .entrance(index.coerceAtMost(24))
                            .background(if (index % 2 == 0) p.editor else p.panel)
                            .clickable(enabled = entry.editable) {
                                if (entry.editable) onOpen(gameDir, entry.path)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(44.dp).background(groupColor(entry.group).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(
                                if (entry.editable) Icons.Filled.Code else Icons.Filled.Storage,
                                contentDescription = null,
                                tint = groupColor(entry.group),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            FlatText(entry.label, weight = FontWeight.Medium)
                            FlatText(
                                formatSize(entry.size) + if (entry.modified > 0) " · " + fmt.format(Date(entry.modified)) else "",
                                color = p.muted,
                                size = 11
                            )
                        }
                        if (entry.editable) {
                            Block(I18n.t("studio.configs_open"), onClick = { onOpen(gameDir, entry.path) }, fill = p.accent, height = 44.dp)
                        } else {
                            FlatText(I18n.t("studio.read_only"), color = p.muted, size = 11, modifier = Modifier.padding(end = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
