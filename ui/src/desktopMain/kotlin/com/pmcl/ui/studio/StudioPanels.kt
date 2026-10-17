package com.pmcl.ui.studio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.selectInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun ProjectPanel(
    vm: LauncherViewModel,
    current: StudioTarget?,
    onPick: (StudioTarget) -> Unit,
    onRun: (StudioTarget) -> Unit,
    favorites: List<String>,
    onFavorite: (StudioTarget) -> Unit,
) {
    val p = LocalStudio.current
    val versions by vm.localVersionInfos.collectAsState()
    val instances by vm.instances.collectAsState()
    val running by vm.runningInstances.collectAsState()
    var query by remember { mutableStateOf("") }
    var versionsOpen by remember { mutableStateOf(true) }
    var instancesOpen by remember { mutableStateOf(true) }
    var favoritesOpen by remember { mutableStateOf(true) }
    val (versionTargets, instanceTargets) = remember(versions, instances) { targetsOf(versions, instances) }
    val q = query.trim()
    val shownVersions = versionTargets.filter { q.isEmpty() || it.name.contains(q, true) || it.detail.contains(q, true) }
    val shownInstances = instanceTargets.filter { q.isEmpty() || it.name.contains(q, true) || it.versionId.contains(q, true) }
    val runningVersions = running.map { it.versionId }.toSet()
    val favoriteSet = favorites.toSet()
    val shownFavorites = (shownVersions + shownInstances).filter { favoriteKey(it) in favoriteSet }
    fun iconOf(t: StudioTarget) = if (t.kind == TargetKind.VERSION) Icons.Filled.ViewInAr else Icons.Filled.Inventory2
    fun tintOf(t: StudioTarget) = if (t.kind == TargetKind.VERSION) Tiles.Blue else Tiles.Navy

    Column(Modifier.fillMaxSize().background(p.panel)) {
        PanelHeader(I18n.t("studio.project")) {
            Block("", onClick = { vm.refreshLocalVersions(); vm.loadInstances() }, icon = Icons.Filled.Refresh, height = 32.dp)
        }
        FlatField(query, { query = it }, I18n.t("studio.filter"), Modifier.fillMaxWidth(), icon = Icons.Filled.Search)
        LazyColumn(Modifier.weight(1f)) {
            if (shownFavorites.isNotEmpty()) {
                item(key = "g-favorites") {
                    GroupRow(I18n.t("studio.favorites"), shownFavorites.size, favoritesOpen, Tiles.Sky) { favoritesOpen = !favoritesOpen }
                }
                if (favoritesOpen) {
                    itemsIndexed(shownFavorites, key = { _, t -> "f-" + favoriteKey(t) }) { index, target ->
                        TargetRow(
                            target, iconOf(target), tintOf(target), current == target,
                            target.kind == TargetKind.VERSION && target.id in runningVersions,
                            true, index, { onPick(target) }, { onRun(target) }, { onFavorite(target) }
                        )
                    }
                }
            }
            item(key = "g-versions") {
                GroupRow(I18n.t("studio.versions"), shownVersions.size, versionsOpen, Tiles.Blue) { versionsOpen = !versionsOpen }
            }
            if (versionsOpen) {
                itemsIndexed(shownVersions, key = { _, t -> "v-" + t.id }) { index, target ->
                    TargetRow(
                        target, Icons.Filled.ViewInAr, Tiles.Blue, current == target, target.id in runningVersions,
                        favoriteKey(target) in favoriteSet, index, { onPick(target) }, { onRun(target) }, { onFavorite(target) }
                    )
                }
            }
            item(key = "g-instances") {
                GroupRow(I18n.t("studio.instances"), shownInstances.size, instancesOpen, Tiles.Navy) { instancesOpen = !instancesOpen }
            }
            if (instancesOpen) {
                itemsIndexed(shownInstances, key = { _, t -> "i-" + t.id }) { index, target ->
                    TargetRow(
                        target, Icons.Filled.Inventory2, Tiles.Navy, current == target, false,
                        favoriteKey(target) in favoriteSet, index, { onPick(target) }, { onRun(target) }, { onFavorite(target) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupRow(label: String, count: Int, open: Boolean, color: Color, onToggle: () -> Unit) {
    val p = LocalStudio.current
    Row(
        Modifier.fillMaxWidth().height(30.dp).background(p.panelAlt).clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Icon(
            if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = p.muted,
            modifier = Modifier.padding(start = 4.dp).size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        FlatText(label, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        FlatText(
            count.toString(),
            color = Color.White,
            size = 11,
            modifier = Modifier.fillMaxHeight().background(color).padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun TargetRow(
    target: StudioTarget,
    icon: ImageVector,
    tint: Color,
    selected: Boolean,
    running: Boolean,
    favorite: Boolean,
    index: Int,
    onPick: () -> Unit,
    onRun: () -> Unit,
    onFavorite: () -> Unit,
) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> tint
            hovered -> p.hover
            else -> Color.Transparent
        },
        tween(160),
        label = "targetRow"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .entrance(index)
            .background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(30.dp).background(if (selected) tint.lift(0.14f) else tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (selected) Color.White else tint, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        FlatText(target.name, color = if (selected) Color.White else p.text, modifier = Modifier.weight(1f))
        val detail = if (target.kind == TargetKind.INSTANCE) target.versionId else target.detail
        if (detail.isNotBlank()) {
            FlatText(detail, color = if (selected) Color.White.copy(alpha = 0.8f) else p.muted, size = 11, modifier = Modifier.padding(horizontal = 6.dp))
        }
        if (running) Box(Modifier.size(8.dp).background(Tiles.Orange))
        if (favorite || hovered || selected) {
            Box(Modifier.size(30.dp).clickable(onClick = onFavorite), contentAlignment = Alignment.Center) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = I18n.t("studio.favorite"),
                    tint = when {
                        selected -> Color.White
                        favorite -> Tiles.Sky
                        else -> p.muted
                    },
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        if (selected) {
            Box(Modifier.size(30.dp).background(Tiles.Forest).clickable(onClick = onRun), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = I18n.t("studio.run"), tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
internal fun FilesPanel(
    root: Path?,
    onOpen: (Path, Path) -> Unit,
    onReveal: (Path) -> Unit,
) {
    val p = LocalStudio.current
    Column(Modifier.fillMaxSize().background(p.panel)) {
        PanelHeader(I18n.t("studio.files")) {
            if (root != null) Block("", onClick = { onReveal(root) }, icon = Icons.Filled.FolderOpen, height = 32.dp)
        }
        if (root == null) {
            FlatText(I18n.t("studio.pick_target"), color = p.muted, lines = 3, modifier = Modifier.padding(12.dp))
            return@Column
        }
        FlatText(
            root.toString(),
            color = Color.White,
            size = 10,
            modifier = Modifier.fillMaxWidth().background(Tiles.Gold).padding(horizontal = 10.dp, vertical = 5.dp)
        )
        val expanded = remember(root) { mutableStateMapOf<Path, Boolean>() }
        val children = remember(root) { mutableStateMapOf<Path, List<Path>>() }
        LaunchedEffect(root) {
            children[root] = withContext(Dispatchers.IO) { listChildren(root) }
        }
        val rows = ArrayList<Pair<Path, Int>>()
        fun walk(dir: Path, depth: Int) {
            for (child in children[dir].orEmpty()) {
                rows.add(child to depth)
                if (expanded[child] == true) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.first.toString() }) { (path, depth) ->
                val isDir = Files.isDirectory(path)
                val open = expanded[path] == true
                if (isDir && open && children[path] == null) {
                    LaunchedEffect(path) {
                        children[path] = withContext(Dispatchers.IO) { listChildren(path) }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .background(if (open) p.panelAlt else Color.Transparent)
                        .clickable { if (isDir) expanded[path] = !open else onOpen(root, path) }
                        .padding(start = (6 + depth * 14).dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isDir) {
                        Icon(
                            if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = p.muted,
                            modifier = Modifier.size(14.dp)
                        )
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = Tiles.Gold, modifier = Modifier.size(14.dp))
                    } else {
                        Spacer(Modifier.width(14.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = when {
                                isDocumentFile(path) -> docKindOf(path)?.let { docColor(it) } ?: Tiles.Blue
                                isTextFile(path) -> Tiles.Sky
                                else -> p.muted
                            },
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    FlatText(path.fileName?.toString().orEmpty(), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun RunPanel(
    vm: LauncherViewModel,
    target: StudioTarget?,
    onRun: (StudioTarget) -> Unit,
    onOverview: (StudioTarget) -> Unit,
) {
    val p = LocalStudio.current
    val account by vm.account.collectAsState()
    val running by vm.runningInstances.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    val javas by vm.javaInstallations.collectAsState()
    val required by produceState(0, target) {
        value = target?.let { t ->
            withContext(Dispatchers.IO) {
                try { vm.core.profileBuilder().getRequiredJavaVersion(t.versionId) } catch (_: Throwable) { 0 }
            }
        } ?: 0
    }
    val time = remember { SimpleDateFormat("HH:mm:ss") }
    Column(Modifier.fillMaxSize().background(p.panel)) {
        if (target == null) {
            FlatText(I18n.t("studio.pick_target"), color = p.muted, lines = 3, modifier = Modifier.padding(12.dp))
        } else {
            val color = if (target.kind == TargetKind.VERSION) Tiles.Blue else Tiles.Navy
            Column(
                Modifier.fillMaxWidth().height(92.dp).background(color).clickable { onOverview(target) }.padding(14.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                FlatText(
                    if (target.kind == TargetKind.VERSION) I18n.t("studio.kind_version") else I18n.t("studio.kind_instance") + " · " + target.versionId,
                    color = Color.White.copy(alpha = 0.85f),
                    size = 11
                )
                FlatText(target.name, color = Color.White, size = 18, weight = FontWeight.Light)
            }
            Block(
                I18n.t("studio.run"),
                onClick = { onRun(target) },
                icon = Icons.Filled.PlayArrow,
                fill = Tiles.Forest,
                modifier = Modifier.fillMaxWidth(),
                height = 46.dp
            )
        }
        Row(Modifier.fillMaxWidth().height(40.dp).background(p.panelAlt), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Tiles.Plum), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.padding(horizontal = 10.dp)) {
                FlatText(I18n.t("studio.account"), color = p.muted, size = 10)
                FlatText(account?.username ?: I18n.t("studio.no_account"), weight = FontWeight.Medium)
            }
        }
        Row(Modifier.fillMaxWidth().height(32.dp).background(p.strip), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(Tiles.Teal), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Coffee, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            FlatText(
                if (required > 0) I18n.t("studio.java_need", required.toString()) else I18n.t("studio.java_list"),
                weight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp).weight(1f)
            )
            Block("", onClick = { vm.scanJavaInstallations() }, icon = Icons.Filled.Refresh, height = 32.dp)
        }
        LazyColumn(Modifier.weight(1f)) {
            val sorted = javas.sortedWith(compareBy({ it.majorVersion != required }, { -it.majorVersion }))
            items(sorted, key = { "j-" + it.path }) { java ->
                val match = required > 0 && java.majorVersion == required
                Row(
                    Modifier.fillMaxWidth().height(34.dp).background(if (match) Tiles.Blue.copy(alpha = 0.12f) else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlatText(
                        java.majorVersion.toString(),
                        color = Color.White,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.width(40.dp).fillMaxHeight().background(if (match) Tiles.Blue else Tiles.Slate).padding(horizontal = 8.dp, vertical = 9.dp)
                    )
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        FlatText(java.path, size = 11)
                        FlatText(listOf(java.architecture, java.source).filter { !it.isNullOrBlank() }.joinToString(" · "), color = p.muted, size = 10)
                    }
                }
            }
            item(key = "running-header") {
                Row(Modifier.fillMaxWidth().height(32.dp).background(p.strip), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(if (gameRunning) Tiles.Orange else Tiles.Graphite), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                    FlatText(I18n.t("studio.running"), weight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp).weight(1f))
                    FlatText(running.size.toString(), color = p.muted, modifier = Modifier.padding(end = 12.dp))
                }
            }
            if (running.isEmpty()) {
                item(key = "running-none") {
                    FlatText(
                        if (gameRunning) I18n.t("studio.running_one") else I18n.t("studio.running_none"),
                        color = p.muted,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(running, key = { "r-" + it.id }) { inst ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(if (inst.active) Tiles.Blue else p.panelAlt)
                        .clickable { vm.selectInstance(inst.id) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val ink = if (inst.active) Color.White else p.text
                    Column(Modifier.weight(1f)) {
                        FlatText(inst.versionId, color = ink, weight = FontWeight.Medium)
                        FlatText(inst.accountName + " · " + time.format(Date(inst.startTime)), color = ink.copy(alpha = 0.75f), size = 11)
                    }
                }
            }
        }
    }
}
