package com.pmcl.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.auth.Account
import com.pmcl.core.auth.ExternalAuthServerStore
import com.pmcl.core.backup.TimeMachine
import com.pmcl.core.gamecontent.RecordingManager
import com.pmcl.core.gamecontent.ScreenshotManager
import com.pmcl.core.gamecontent.WorldManager
import com.pmcl.core.i18n.I18n
import com.pmcl.core.instance.InstanceInfo
import com.pmcl.ui.navigation.PmclIcons
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.addExternalAuthServer
import com.pmcl.ui.viewmodel.addFavoriteServer
import com.pmcl.ui.viewmodel.backupWorld
import com.pmcl.ui.viewmodel.copyScreenshotToClipboard
import com.pmcl.ui.viewmodel.deleteRecording
import com.pmcl.ui.viewmodel.deleteScreenshot
import com.pmcl.ui.viewmodel.deleteTimeMachineSnapshot
import com.pmcl.ui.viewmodel.deleteWorld
import com.pmcl.ui.viewmodel.directConnectServer
import com.pmcl.ui.viewmodel.importRecordings
import com.pmcl.ui.viewmodel.importWorld
import com.pmcl.ui.viewmodel.lastOfflineUsername
import com.pmcl.ui.viewmodel.listBackups
import com.pmcl.ui.viewmodel.listExternalAuthServers
import com.pmcl.ui.viewmodel.loadFavoriteServers
import com.pmcl.ui.viewmodel.loginOffline
import com.pmcl.ui.viewmodel.openRecording
import com.pmcl.ui.viewmodel.openRecordingFolder
import com.pmcl.ui.viewmodel.openRecordingsDir
import com.pmcl.ui.viewmodel.openScreenshotFolder
import com.pmcl.ui.viewmodel.openScreenshotsDir
import com.pmcl.ui.viewmodel.openWorldFolder
import com.pmcl.ui.viewmodel.pingAllServersFull
import com.pmcl.ui.viewmodel.pingServerFull
import com.pmcl.ui.viewmodel.refreshRecordings
import com.pmcl.ui.viewmodel.refreshScreenshots
import com.pmcl.ui.viewmodel.refreshWorlds
import com.pmcl.ui.viewmodel.removeAccount
import com.pmcl.ui.viewmodel.removeExternalAuthServer
import com.pmcl.ui.viewmodel.removeFavoriteServer
import com.pmcl.ui.viewmodel.resetMicrosoftSkin
import com.pmcl.ui.viewmodel.restoreTimeMachine
import com.pmcl.ui.viewmodel.restoreWorld
import com.pmcl.ui.viewmodel.saveTimeMachineSettings
import com.pmcl.ui.viewmodel.serverStatusKey
import com.pmcl.ui.viewmodel.setOfflineSkin
import com.pmcl.ui.viewmodel.setTimeMachineNote
import com.pmcl.ui.viewmodel.setTimeMachinePinned
import com.pmcl.ui.viewmodel.startGitHubLogin
import com.pmcl.ui.viewmodel.startMicrosoftLogin
import com.pmcl.ui.viewmodel.startTimeMachineBackup
import com.pmcl.ui.viewmodel.startYggdrasilLogin
import com.pmcl.ui.viewmodel.switchAccount
import com.pmcl.ui.viewmodel.updateFavoriteServer
import com.pmcl.ui.viewmodel.uploadMicrosoftSkin
import com.pmcl.ui.viewmodel.uploadYggdrasilSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

// ───────────────────────────── 实例 ─────────────────────────────

@Composable
internal fun InstancesModule(ctx: ModuleCtx) {
    val vm = ctx.vm
    val instances by vm.instances.collectAsState()
    val launching by vm.instanceLaunching.collectAsState()
    val status by vm.instanceStatus.collectAsState()
    val running by vm.runningInstances.collectAsState()
    val locals by vm.localVersionInfos.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.loadInstances() }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val q = query.trim()
    val shown = instances.filter { q.isEmpty() || it.name.contains(q, true) || it.baseVersionId.orEmpty().contains(q, true) }
    val selected = instances.firstOrNull { it.instanceId == selectedId }
    val totalSeconds = instances.sumOf { it.totalPlayTimeSeconds }
    val recent = instances.filter { it.lastPlayedAt > 0 }.maxByOrNull { it.lastPlayedAt }

    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("instances_title"), mt("instances_sub", instances.size), ctx.color, PmclIcons.Instances) {
            HeroButton(mt("new_instance"), Icons.Filled.Add, ctx.color, { creating = true; selectedId = null })
            HeroButton(mt("import"), Icons.Filled.FileOpen, ctx.color, {
                pickFile(mt("import"), setOf("zip", "mrpack")).firstOrNull()?.let { path -> scope.launch { vm.importInstance(path) } }
            })
            HeroButton(mt("refresh"), Icons.Filled.Refresh, ctx.color, { vm.loadInstances() })
        }
        TileRow {
            ColorTile(mt("tile_instances"), instances.size.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), PmclIcons.Instances, index = 0)
            ColorTile(mt("tile_running"), running.size.toString(), if (running.isNotEmpty()) Tiles.Orange else Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.PlayArrow, index = 1)
            ColorTile(mt("tile_playtime"), formatDuration(totalSeconds), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), PmclIcons.History, index = 2)
            ColorTile(mt("tile_recent"), recent?.name ?: "—", Tiles.Blue, Modifier.weight(1.4f).fillMaxHeight(), Icons.Filled.PlayArrow,
                recent?.let { r -> { selectedId = r.instanceId; creating = false } }, valueSize = 16, index = 3)
        }
        Toolbar {
            FlatField(query, { query = it }, mt("filter"), Modifier.width(260.dp), icon = Icons.Filled.Search)
            if (status.isNotBlank()) FlatText(status, color = LocalStudio.current.muted, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (shown.isEmpty()) {
                    EmptyState(mt("instances_empty"), PmclIcons.Instances, mt("new_instance"), ctx.color) { creating = true }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(shown, key = { _, it -> it.instanceId }) { i, info ->
                            val busy = launching == info.instanceId
                            ItemRow(
                                info.name,
                                listOfNotNull(info.baseVersionId, info.loader?.takeIf { it.isNotBlank() }, info.lastPlayedAt.takeIf { it > 0 }?.let { mt("last_played", fmtDate(it)) })
                                    .joinToString(" · "),
                                if (info.type == InstanceInfo.Type.MODPACK) Tiles.Purple else ctx.color,
                                i,
                                badge = info.name.take(1).uppercase(),
                                selected = info.instanceId == selectedId,
                                onClick = { selectedId = info.instanceId; creating = false }
                            ) {
                                RowButton(Icons.Filled.FolderOpen, { vm.openDir(info.instanceDir.toFile()) })
                                RowButton(Icons.Filled.PlayArrow, { vm.launchInstance(info.instanceId) }, Tiles.Forest, enabled = launching == null && info.isLaunchable,
                                    label = if (busy) I18n.t("studio.loading") else mt("launch"))
                            }
                        }
                    }
                }
            }
            if (creating) {
                CreateInstancePanel(ctx, locals.filter { it.isLaunchable }.map { it.id }) { creating = false }
            } else if (selected != null) {
                InstanceInspector(ctx, selected, launching == null) { selectedId = null }
            }
        }
    }
}

@Composable
private fun InstanceInspector(ctx: ModuleCtx, info: InstanceInfo, canLaunch: Boolean, onClose: () -> Unit) {
    val vm = ctx.vm
    var name by remember(info.instanceId) { mutableStateOf(info.name) }
    Inspector(info.name, ctx.color, onClose, Modifier.width(340.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                ColorTile(mt("launch"), "", Tiles.Forest, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.PlayArrow,
                    if (canLaunch && info.isLaunchable) ({ vm.launchInstance(info.instanceId) }) else null, index = 0)
                ColorTile(mt("open_folder"), "", Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.FolderOpen, { vm.openDir(info.instanceDir.toFile()) }, index = 1)
            }
            LabelBar(mt("rename"), ctx.color)
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                InputBox(name, { name = it }, mt("name"), Modifier.weight(1f))
                Block("", { if (name.isNotBlank()) vm.renameInstance(info.instanceId, name.trim()) }, icon = Icons.Filled.Check, fill = ctx.color, enabled = name.isNotBlank() && name != info.name, height = 30.dp)
            }
            LabelBar(mt("details"), ctx.color)
            val facts = listOf(
                mt("base_version") to info.baseVersionId.orEmpty(),
                mt("loader") to listOfNotNull(info.loader, info.loaderVersion).filter { it.isNotBlank() }.joinToString(" "),
                mt("type") to if (info.type == InstanceInfo.Type.MODPACK) mt("type_modpack") else mt("type_custom"),
                mt("created") to fmtDate(info.createdAt),
                mt("last_played_label") to fmtDate(info.lastPlayedAt),
                mt("tile_playtime") to formatDuration(info.totalPlayTimeSeconds),
                mt("folder") to info.instanceDir.toString(),
            )
            facts.forEachIndexed { i, (k, v) -> FactRow(k, v, i, lines = 3) }
            info.description?.takeIf { it.isNotBlank() }?.let { FactRow(mt("description"), it, facts.size, lines = 6) }
        }
        Row(Modifier.fillMaxWidth()) {
            Block(mt("duplicate"), { vm.copyInstance(info.instanceId, info.name + " " + mt("copy_suffix")) }, Modifier.weight(1f), Icons.Filled.ContentCopy, fill = Tiles.Navy, height = 36.dp)
            Block(mt("export"), {
                pickSaveFile(mt("export"), info.name + ".zip")?.let { vm.exportInstance(info.instanceId, it) }
            }, Modifier.weight(1f), Icons.Filled.Archive, fill = Tiles.Slate, height = 36.dp)
            Block(mt("delete"), {
                ctx.confirm(mt("delete_instance_title"), mt("delete_instance_body", info.name)) { vm.deleteInstance(info.instanceId); onClose() }
            }, Modifier.weight(1f), Icons.Filled.Delete, fill = Tiles.Red, height = 36.dp)
        }
    }
}

@Composable
private fun CreateInstancePanel(ctx: ModuleCtx, versions: List<String>, onClose: () -> Unit) {
    val p = LocalStudio.current
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(versions.firstOrNull().orEmpty()) }
    var filter by remember { mutableStateOf("") }
    Inspector(mt("new_instance"), ctx.color, onClose, Modifier.width(340.dp)) {
        FormRow(mt("name"), 0) { InputBox(name, { name = it }, mt("instance_name_hint"), Modifier.weight(1f)) }
        LabelBar(mt("base_version"), ctx.color)
        FlatField(filter, { filter = it }, mt("filter"), Modifier.fillMaxWidth(), icon = Icons.Filled.Search)
        val shown = versions.filter { filter.isBlank() || it.contains(filter.trim(), true) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(shown, key = { _, v -> v }) { i, v ->
                val on = v == base
                FlatText(
                    v,
                    color = if (on) Color.White else p.text,
                    modifier = Modifier.fillMaxWidth().entrance(i).background(if (on) ctx.color else if (i % 2 == 0) p.panel else p.panelAlt)
                        .clickable { base = v; if (name.isBlank()) name = v }.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        if (versions.isEmpty()) FlatText(mt("no_local_versions"), color = p.muted, lines = 3, modifier = Modifier.padding(12.dp))
        Block(mt("create"), {
            ctx.vm.createInstance(name.trim(), base, null, null)
            onClose()
        }, Modifier.fillMaxWidth(), Icons.Filled.Add, fill = ctx.color, enabled = name.isNotBlank() && base.isNotEmpty(), height = 38.dp)
    }
}

// ───────────────────────────── 存档 ─────────────────────────────

@Composable
internal fun SavesModule(ctx: ModuleCtx) {
    when (ctx.section) {
        "timemachine" -> TimeMachineModule(ctx)
        "screenshots" -> ScreenshotsSection(ctx)
        "recordings" -> RecordingsSection(ctx)
        else -> WorldsSection(ctx)
    }
}

private fun gameModeLabel(mode: Int): String = when (mode) {
    0 -> mt("mode_survival")
    1 -> mt("mode_creative")
    2 -> mt("mode_adventure")
    3 -> mt("mode_spectator")
    else -> ""
}

private fun difficultyLabel(d: Int): String = when (d) {
    0 -> mt("diff_peaceful")
    1 -> mt("diff_easy")
    2 -> mt("diff_normal")
    3 -> mt("diff_hard")
    else -> ""
}

@Composable
private fun WorldsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val worlds by vm.worlds.collectAsState()
    val status by vm.status.collectAsState()
    LaunchedEffect(Unit) { vm.refreshWorlds() }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val q = query.trim()
    val shown = worlds.filter { q.isEmpty() || it.displayName.orEmpty().contains(q, true) || it.name.contains(q, true) }
    val selected = worlds.firstOrNull { it.dir.toString() == selectedKey }

    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("worlds_title"), mt("worlds_sub", worlds.size, formatSize(worlds.sumOf { it.sizeBytes })), ctx.color, PmclIcons.Saves) {
            HeroButton(mt("import"), Icons.Filled.FileOpen, ctx.color, { pickFile(mt("import"), setOf("zip")).firstOrNull()?.let { vm.importWorld(it) } })
            HeroButton(mt("refresh"), Icons.Filled.Refresh, ctx.color, { vm.refreshWorlds() })
        }
        TileRow {
            ColorTile(mt("tile_worlds"), worlds.size.toString(), Tiles.Teal, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Public, index = 0)
            ColorTile(mt("tile_size"), formatSize(worlds.sumOf { it.sizeBytes }), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Archive, index = 1)
            ColorTile(mt("mode_creative"), worlds.count { it.gameType == 1 }.toString(), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), index = 2)
            ColorTile(mt("hardcore"), worlds.count { it.isHardcore }.toString(), if (worlds.any { it.isHardcore }) Tiles.Red else Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), index = 3)
        }
        Toolbar {
            FlatField(query, { query = it }, mt("filter"), Modifier.width(260.dp), icon = Icons.Filled.Search)
            if (status.isNotBlank()) FlatText(status, color = LocalStudio.current.muted, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (shown.isEmpty()) EmptyState(mt("worlds_empty"), Icons.Filled.Public, color = ctx.color)
                else LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { _, w -> w.dir.toString() }) { i, w ->
                        val icon = rememberThumb(if (w.hasIcon()) w.dir.resolve("icon.png") else null, 96)
                        ItemRow(
                            w.displayName?.takeIf { it.isNotBlank() } ?: w.name,
                            listOf(gameModeLabel(w.gameType), difficultyLabel(w.difficulty), formatSize(w.sizeBytes), fmtDate(w.lastModified)).filter { it.isNotBlank() }.joinToString(" · "),
                            if (w.isHardcore) Tiles.Red else ctx.color,
                            i,
                            icon = Icons.Filled.Public,
                            image = icon,
                            selected = w.dir.toString() == selectedKey,
                            onClick = { selectedKey = w.dir.toString() }
                        ) {
                            RowButton(Icons.Filled.Archive, { vm.backupWorld(w) })
                            RowButton(Icons.Filled.FolderOpen, { vm.openWorldFolder(w) })
                        }
                    }
                }
            }
            if (selected != null) WorldInspector(ctx, selected) { selectedKey = null }
        }
    }
}

@Composable
private fun WorldInspector(ctx: ModuleCtx, w: WorldManager.WorldInfo, onClose: () -> Unit) {
    val vm = ctx.vm
    var reload by remember { mutableIntStateOf(0) }
    val backups by produceState(emptyList<Path>(), w.name, reload) { value = runCatching { vm.listBackups(w.name) }.getOrDefault(emptyList()) }
    Inspector(w.displayName?.takeIf { it.isNotBlank() } ?: w.name, ctx.color, onClose, Modifier.width(340.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val facts = listOf(
                mt("folder") to w.name,
                mt("game_mode") to gameModeLabel(w.gameType) + if (w.isHardcore) " · " + mt("hardcore") else "",
                mt("difficulty") to difficultyLabel(w.difficulty),
                mt("seed") to if (w.seed == Long.MIN_VALUE) "" else w.seed.toString(),
                mt("tile_size") to formatSize(w.sizeBytes),
                mt("modified") to fmtDate(w.lastModified),
                mt("source") to w.source.orEmpty(),
            )
            facts.forEachIndexed { i, (k, v) -> FactRow(k, v, i) }
            LabelBar(mt("world_backups", backups.size), ctx.color) {
                Block("", { reload++ }, icon = Icons.Filled.Refresh, height = 30.dp)
            }
            if (backups.isEmpty()) FlatText(mt("no_backups"), color = LocalStudio.current.muted, modifier = Modifier.padding(12.dp))
            backups.forEachIndexed { i, b ->
                ItemRow(b.fileName.toString(), runCatching { formatSize(b.toFile().length()) }.getOrDefault(""), Tiles.Slate, i, icon = Icons.Filled.Archive, height = 40.dp) {
                    RowButton(Icons.Filled.Restore, {
                        ctx.confirm(mt("restore_world_title"), mt("restore_world_body", w.name, b.fileName.toString())) { vm.restoreWorld(b, w.name) }
                    }, Tiles.Orange)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Block(mt("backup_now"), { vm.backupWorld(w).invokeOnCompletion { reload++ } }, Modifier.weight(1f), Icons.Filled.Archive, fill = ctx.color, height = 36.dp)
            Block(mt("delete"), {
                ctx.confirm(mt("delete_world_title"), mt("delete_world_body", w.name)) { vm.deleteWorld(w); onClose() }
            }, Modifier.weight(1f), Icons.Filled.Delete, fill = Tiles.Red, height = 36.dp)
        }
    }
}

@Composable
private fun ScreenshotsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val shots by vm.screenshots.collectAsState()
    LaunchedEffect(Unit) { vm.refreshScreenshots() }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = shots.firstOrNull { it.path.toString() == selectedKey }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("screenshots_title"), mt("screenshots_sub", shots.size, formatSize(shots.sumOf { it.size })), ctx.color, Icons.Filled.Image) {
            HeroButton(mt("open_folder"), Icons.Filled.FolderOpen, ctx.color, { vm.openScreenshotsDir() })
            HeroButton(mt("refresh"), Icons.Filled.Refresh, ctx.color, { vm.refreshScreenshots() })
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (shots.isEmpty()) EmptyState(mt("screenshots_empty"), Icons.Filled.Image, color = ctx.color)
                else LazyVerticalGrid(GridCells.Adaptive(190.dp), Modifier.fillMaxSize()) {
                    itemsIndexed(shots, key = { _, s -> s.path.toString() }) { i, s ->
                        val thumb = rememberThumb(s.path, 360)
                        val on = s.path.toString() == selectedKey
                        Column(
                            Modifier.fillMaxWidth().entrance(i).background(if (on) ctx.color else p.panel).clickable { selectedKey = s.path.toString() }.padding(3.dp)
                        ) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(p.field)) {
                                if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            FlatText(s.name, color = if (on) Color.White else p.text, size = 11, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                    }
                }
            }
            if (selected != null) ScreenshotInspector(ctx, selected) { selectedKey = null }
        }
    }
}

@Composable
private fun ScreenshotInspector(ctx: ModuleCtx, s: ScreenshotManager.Screenshot, onClose: () -> Unit) {
    val vm = ctx.vm
    val big = rememberThumb(s.path, 900)
    Inspector(s.name, ctx.color, onClose, Modifier.width(380.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(LocalStudio.current.field)) {
            if (big != null) Image(big, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        FactRow(mt("tile_size"), formatSize(s.size), 0)
        FactRow(mt("modified"), fmtDate(s.modified), 1)
        FactRow(mt("source"), s.source.orEmpty(), 2)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            Block(mt("copy"), { vm.copyScreenshotToClipboard(s) }, Modifier.weight(1f), Icons.Filled.ContentCopy, fill = Tiles.Navy, height = 36.dp)
            Block(mt("open_folder"), { vm.openScreenshotFolder(s) }, Modifier.weight(1f), Icons.Filled.FolderOpen, fill = Tiles.Slate, height = 36.dp)
            Block(mt("delete"), { ctx.confirm(mt("delete_file_title"), mt("delete_file_body", s.name)) { vm.deleteScreenshot(s); onClose() } },
                Modifier.weight(1f), Icons.Filled.Delete, fill = Tiles.Red, height = 36.dp)
        }
    }
}

@Composable
private fun RecordingsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val recs by vm.recordings.collectAsState()
    LaunchedEffect(Unit) { vm.refreshRecordings() }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("recordings_title"), mt("recordings_sub", recs.size, formatSize(recs.sumOf { it.size })), ctx.color, Icons.Filled.Movie) {
            HeroButton(mt("import"), Icons.Filled.FileOpen, ctx.color, {
                val files = pickFile(mt("import"), setOf("mp4", "mkv", "mov", "webm", "avi", "flv"), multiple = true)
                if (files.isNotEmpty()) vm.importRecordings(files.map { it.toString() })
            })
            HeroButton(mt("open_folder"), Icons.Filled.FolderOpen, ctx.color, { vm.openRecordingsDir() })
            HeroButton(mt("refresh"), Icons.Filled.Refresh, ctx.color, { vm.refreshRecordings() })
        }
        if (recs.isEmpty()) EmptyState(mt("recordings_empty"), Icons.Filled.Movie, color = ctx.color)
        else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(recs, key = { _, r -> r.path.toString() }) { i, r: RecordingManager.Recording ->
                ItemRow(r.name, listOf(formatSize(r.size), fmtDate(r.modified), r.source.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), ctx.color, i,
                    icon = Icons.Filled.Movie, onClick = { vm.openRecording(r) }) {
                    RowButton(Icons.Filled.PlayArrow, { vm.openRecording(r) }, Tiles.Forest)
                    RowButton(Icons.Filled.FolderOpen, { vm.openRecordingFolder(r) })
                    RowButton(Icons.Filled.Delete, { ctx.confirm(mt("delete_file_title"), mt("delete_file_body", r.name)) { vm.deleteRecording(r) } })
                }
            }
        }
    }
}

// ───────────────────────────── 时间机器 ─────────────────────────────

private class TmLoad(
    val settings: TimeMachine.Settings,
    val snapshots: List<TimeMachine.Snapshot>,
    val stored: Long,
    val nextAt: Long,
)

private fun snapshotLabel(code: String?): String = when (code) {
    "auto" -> I18n.t("backup.label_auto")
    "before-restore" -> I18n.t("backup.label_before")
    else -> I18n.t("backup.label_now")
}

private val tmScopes = listOf("worlds", "mods", "configs", "resourcepacks", "shaders", "screenshots", "skins", "launcher")

private fun copySettings(s: TimeMachine.Settings): TimeMachine.Settings = TimeMachine.Settings().also {
    it.schedule = s.schedule
    it.keep = s.keep
    it.worlds = s.worlds
    it.mods = s.mods
    it.configs = s.configs
    it.resourcepacks = s.resourcepacks
    it.shaders = s.shaders
    it.screenshots = s.screenshots
    it.skins = s.skins
    it.launcher = s.launcher
}

private fun TimeMachine.Settings.with(scope: String, on: Boolean): TimeMachine.Settings = copySettings(this).also {
    when (scope) {
        "worlds" -> it.worlds = on
        "mods" -> it.mods = on
        "configs" -> it.configs = on
        "resourcepacks" -> it.resourcepacks = on
        "shaders" -> it.shaders = on
        "screenshots" -> it.screenshots = on
        "skins" -> it.skins = on
        "launcher" -> it.launcher = on
    }
}

private fun changeColor(change: String?): Color = when (change) {
    "added" -> Tiles.Forest
    "modified" -> Tiles.Orange
    "removed" -> Tiles.Red
    else -> Tiles.Slate
}

@Composable
internal fun TimeMachineModule(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val generation by vm.timeMachineGeneration.collectAsState()
    val busy by vm.timeMachineBusy.collectAsState()
    val done by vm.timeMachineDone.collectAsState()
    val total by vm.timeMachineTotal.collectAsState()
    val current by vm.timeMachineCurrent.collectAsState()
    val message by vm.timeMachineMessage.collectAsState()
    var localTick by remember { mutableIntStateOf(0) }
    val load by produceState<TmLoad?>(null, generation, localTick) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val tm = vm.core.timeMachine()
                val settings = tm.loadSettings()
                TmLoad(settings, tm.listSnapshots(), tm.storedSize(), tm.nextBackupAt(settings, System.currentTimeMillis()))
            }.getOrNull()
        }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val data = load
    val snapshots = data?.snapshots.orEmpty()
    val selected = snapshots.firstOrNull { it.id() == selectedId }

    Column(Modifier.fillMaxSize()) {
        val next = data?.let { d -> if (d.settings.schedule == "manual" || d.nextAt <= 0) I18n.t("backup.next_off") else mt("next_backup", fmtDate(d.nextAt)) } ?: ""
        ModuleHero(I18n.t("backup.title"), next, ctx.color, PmclIcons.History) {
            HeroButton(I18n.t("backup.now"), Icons.Filled.Archive, ctx.color, { vm.startTimeMachineBackup() }, enabled = !busy)
            HeroButton(mt("settings"), Icons.Filled.Settings, ctx.color, { showSettings = !showSettings; if (showSettings) selectedId = null })
            HeroButton(mt("open_folder"), Icons.Filled.FolderOpen, ctx.color, { vm.openDir(vm.core.timeMachine().storeDir().toFile()) })
        }
        if (busy || message.isNotBlank()) {
            Column(Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 16.dp, vertical = 8.dp)) {
                FlatText(if (busy) mt("tm_progress", done, total, current) else message, color = p.text, size = 11)
                if (busy) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(if (total > 0) done.toFloat() / total else null, ctx.color)
                }
            }
        }
        TileRow {
            ColorTile(mt("tile_snapshots"), snapshots.size.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), PmclIcons.History, index = 0)
            ColorTile(mt("tile_stored"), formatSize(data?.stored ?: 0), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Archive, index = 1)
            ColorTile(mt("tile_latest"), snapshots.firstOrNull()?.let { fmtDate(it.createdAt()) } ?: "—", Tiles.Blue, Modifier.weight(1.3f).fillMaxHeight(), valueSize = 16, index = 2)
            ColorTile(mt("tile_pinned"), "${snapshots.count { it.pinned() }} / ${TimeMachine.MAX_PINS}", Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.PushPin, index = 3)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (data == null) CenterNote(I18n.t("studio.loading"))
                else if (snapshots.isEmpty()) EmptyState(I18n.t("backup.last_none"), PmclIcons.History, I18n.t("backup.now"), ctx.color) { vm.startTimeMachineBackup() }
                else LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(snapshots, key = { _, s -> s.id() }) { i, s ->
                        ItemRow(
                            snapshotLabel(s.label()) + (s.note()?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            listOf(fmtDate(s.createdAt()), mt("files_n", s.files()), formatSize(s.logicalBytes())).joinToString(" · "),
                            if (s.pinned()) Tiles.Purple else ctx.color,
                            i,
                            icon = if (s.pinned()) Icons.Filled.PushPin else PmclIcons.History,
                            selected = s.id() == selectedId,
                            onClick = { selectedId = s.id(); showSettings = false }
                        ) {
                            RowButton(Icons.Filled.Restore, {
                                ctx.confirm(mt("restore_snapshot_title"), mt("restore_snapshot_body", fmtDate(s.createdAt()))) { vm.restoreTimeMachine(s.id(), null, false) }
                            }, enabled = !busy)
                        }
                    }
                }
            }
            if (showSettings && data != null) {
                TimeMachineSettingsPanel(ctx, data.settings, { localTick++ }) { showSettings = false }
            } else if (selected != null) {
                SnapshotInspector(ctx, selected, busy, { localTick++ }) { selectedId = null }
            }
        }
    }
}

@Composable
private fun SnapshotInspector(ctx: ModuleCtx, s: TimeMachine.Snapshot, busy: Boolean, onChanged: () -> Unit, onClose: () -> Unit) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val changes by produceState<List<TimeMachine.Entry>?>(null, s.id()) {
        value = withContext(Dispatchers.IO) { runCatching { vm.core.timeMachine().changes(s.id()) }.getOrDefault(emptyList()) }
    }
    var note by remember(s.id()) { mutableStateOf(s.note().orEmpty()) }
    Inspector(snapshotLabel(s.label()), ctx.color, onClose, Modifier.width(380.dp)) {
        FactRow(mt("created"), fmtDate(s.createdAt()), 0)
        FactRow(mt("files"), s.files().toString(), 1)
        FactRow(mt("logical_size"), formatSize(s.logicalBytes()), 2)
        FactRow(mt("stored_size"), formatSize(s.storedBytes()), 3)
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            InputBox(note, { note = it }, mt("note_hint"), Modifier.weight(1f))
            Block("", { vm.setTimeMachineNote(s.id(), note.trim()); onChanged() }, icon = Icons.Filled.Save, fill = ctx.color, enabled = note != s.note().orEmpty(), height = 30.dp)
        }
        val list = changes
        LabelBar(mt("changes", list?.size ?: 0), ctx.color)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list == null) CenterNote(I18n.t("studio.loading"))
            else if (list.isEmpty()) CenterNote(I18n.t("backup.unchanged"))
            else LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list.take(2000), key = { i, e -> "$i:${e.root()}:${e.path()}" }) { i, e ->
                    Row(Modifier.fillMaxWidth().background(if (i % 2 == 0) p.panel else p.panelAlt), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(30.dp).background(changeColor(e.change())))
                        FlatText(e.path(), size = 11, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        FlatText(formatSize(e.size()), color = p.muted, size = 10, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Block(mt("restore"), {
                ctx.confirm(mt("restore_snapshot_title"), mt("restore_snapshot_body", fmtDate(s.createdAt()))) { vm.restoreTimeMachine(s.id(), null, false) }
            }, Modifier.weight(1f), Icons.Filled.Restore, fill = Tiles.Orange, enabled = !busy, height = 36.dp)
            Block(if (s.pinned()) mt("unpin") else mt("pin"), { vm.setTimeMachinePinned(s.id(), !s.pinned()); onChanged() },
                Modifier.weight(1f), Icons.Filled.PushPin, fill = Tiles.Navy, height = 36.dp)
            Block(mt("delete"), {
                ctx.confirm(mt("delete_snapshot_title"), mt("delete_snapshot_body", fmtDate(s.createdAt()))) { vm.deleteTimeMachineSnapshot(s.id()); onClose() }
            }, Modifier.weight(1f), Icons.Filled.Delete, fill = Tiles.Red, enabled = !busy, height = 36.dp)
        }
    }
}

@Composable
private fun TimeMachineSettingsPanel(ctx: ModuleCtx, initial: TimeMachine.Settings, onChanged: () -> Unit, onClose: () -> Unit) {
    val vm = ctx.vm
    var settings by remember(initial) { mutableStateOf(copySettings(initial)) }
    fun publish(next: TimeMachine.Settings) {
        settings = next
        vm.saveTimeMachineSettings(next)
        onChanged()
    }
    Inspector(I18n.t("backup.settings"), ctx.color, onClose, Modifier.width(380.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            LabelBar(I18n.t("backup.schedule"), ctx.color)
            Box(Modifier.padding(10.dp)) {
                Chips(
                    listOf("manual", "hourly", "daily", "weekly").map { it to I18n.t("backup.schedule_$it") },
                    settings.schedule,
                    { publish(copySettings(settings).also { s -> s.schedule = it }) },
                    ctx.color
                )
            }
            LabelBar(mt("keep"), ctx.color)
            Box(Modifier.padding(10.dp)) {
                Chips(listOf(5, 10, 20, 50, 100).map { it.toString() to I18n.t("backup.keep_n", it) }, settings.keep.toString(),
                    { publish(copySettings(settings).also { s -> s.keep = it.toInt() }) }, ctx.color)
            }
            LabelBar(mt("scopes"), ctx.color)
            tmScopes.forEachIndexed { i, scope ->
                val on = settings.allows(scope)
                ItemRow(I18n.t("backup.scope_$scope"), "", if (on) ctx.color else Tiles.Slate, i, icon = if (on) Icons.Filled.Check else null, dim = !on, height = 38.dp,
                    onClick = { publish(settings.with(scope, !on)) })
            }
        }
    }
}

// ───────────────────────────── 服务器 ─────────────────────────────

@Composable
internal fun ServersModule(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val servers by vm.favoriteServers.collectAsState()
    val statuses by vm.serverStatuses.collectAsState()
    val pinging by vm.pingingServers.collectAsState()
    LaunchedEffect(Unit) {
        vm.loadFavoriteServers()
        vm.pingAllServersFull()
    }
    var editing by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("25565") }
    fun edit(index: Int?) {
        editing = index
        val s = index?.let { servers.getOrNull(it) }
        name = s?.name.orEmpty()
        host = s?.host.orEmpty()
        port = (s?.port ?: 25565).toString()
    }
    val online = servers.count { statuses[serverStatusKey(it.host, it.port, it.token)]?.isOnline == true }
    val players = servers.sumOf { statuses[serverStatusKey(it.host, it.port, it.token)]?.takeIf { s -> s.isOnline }?.onlinePlayers ?: 0 }
    val best = servers.mapNotNull { statuses[serverStatusKey(it.host, it.port, it.token)]?.takeIf { s -> s.isOnline && s.latency >= 0 }?.latency }.minOrNull()

    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("servers_title"), mt("servers_sub", servers.size, online), ctx.color, PmclIcons.Servers) {
            HeroButton(mt("add_server"), Icons.Filled.Add, ctx.color, { edit(-1) })
            HeroButton(mt("ping_all"), Icons.Filled.NetworkPing, ctx.color, { vm.pingAllServersFull() }, enabled = pinging.isEmpty())
        }
        TileRow {
            ColorTile(mt("tile_servers"), servers.size.toString(), Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), PmclIcons.Servers, index = 0)
            ColorTile(mt("tile_online"), online.toString(), if (online > 0) Tiles.Forest else Tiles.Graphite, Modifier.weight(1f).fillMaxHeight(), index = 1)
            ColorTile(mt("tile_players"), players.toString(), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Person, index = 2)
            ColorTile(mt("tile_best_ping"), best?.let { "$it ms" } ?: "—", Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.NetworkPing, index = 3)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (servers.isEmpty()) EmptyState(mt("servers_empty"), PmclIcons.Servers, mt("add_server"), ctx.color) { edit(-1) }
                else LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(servers, key = { i, s -> "$i:${s.host}:${s.port}" }) { i, s ->
                        val key = serverStatusKey(s.host, s.port, s.token)
                        val st = statuses[key]
                        val busy = key in pinging
                        val icon = remember(st?.iconBase64) { decodeBase64Image(st?.iconBase64) }
                        val sub = when {
                            busy -> mt("pinging")
                            st == null -> "${s.host}:${s.port}"
                            st.isOnline -> listOf("${s.host}:${s.port}", "${st.onlinePlayers}/${st.maxPlayers}", "${st.latency} ms", st.versionName.orEmpty(),
                                st.motd.orEmpty().replace(Regex("§."), "").replace('\n', ' ').trim()).filter { it.isNotBlank() }.joinToString(" · ")
                            else -> "${s.host}:${s.port} · " + mt("offline")
                        }
                        ItemRow(s.name.ifBlank { s.host }, sub, if (st?.isOnline == true) ctx.color else Tiles.Graphite, i, icon = PmclIcons.Servers, image = icon,
                            selected = editing == i, onClick = { edit(i) }) {
                            RowButton(Icons.Filled.NetworkPing, { vm.pingServerFull(s.host, s.port, s.token) }, enabled = !busy)
                            RowButton(Icons.Filled.PlayArrow, { vm.directConnectServer(s.name, s.host, s.port, false, s.token) }, Tiles.Forest, label = mt("join"))
                        }
                    }
                }
            }
            val idx = editing
            if (idx != null) {
                val existing = servers.getOrNull(idx)
                Inspector(if (existing == null) mt("add_server") else existing.name.ifBlank { existing.host }, ctx.color, { editing = null }, Modifier.width(340.dp)) {
                    FormRow(mt("name"), 0) { InputBox(name, { name = it }, mt("server_name_hint"), Modifier.weight(1f)) }
                    FormRow(mt("host"), 1) { InputBox(host, { host = it.trim() }, "play.example.com", Modifier.weight(1f)) }
                    FormRow(mt("port"), 2) { InputBox(port, { v -> port = v.filter { it.isDigit() }.take(5) }, "25565", Modifier.weight(1f)) }
                    val st = existing?.let { statuses[serverStatusKey(it.host, it.port, it.token)] }
                    if (st != null && st.isOnline) {
                        LabelBar(mt("status"), ctx.color)
                        FactRow(mt("version"), st.versionName.orEmpty(), 0)
                        FactRow(mt("tile_players"), "${st.onlinePlayers}/${st.maxPlayers}", 1)
                        FactRow("MOTD", st.motd.orEmpty().replace(Regex("§."), ""), 2, lines = 4)
                    }
                    Spacer(Modifier.weight(1f))
                    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
                    Row(Modifier.fillMaxWidth()) {
                        Block(mt("save"), {
                            val n = name.ifBlank { host }
                            if (existing == null) vm.addFavoriteServer(n, host, portNum ?: 25565)
                            else vm.updateFavoriteServer(idx, n, host, portNum ?: 25565, existing.token)
                            editing = null
                        }, Modifier.weight(1f), Icons.Filled.Save, fill = ctx.color, enabled = host.isNotBlank() && portNum != null, height = 36.dp)
                        if (existing != null) {
                            Block(mt("delete"), {
                                ctx.confirm(mt("delete_server_title"), mt("delete_server_body", existing.name.ifBlank { existing.host })) { vm.removeFavoriteServer(idx); editing = null }
                            }, Modifier.weight(1f), Icons.Filled.Delete, fill = Tiles.Red, height = 36.dp)
                        }
                    }
                }
            }
        }
        if (servers.isNotEmpty()) {
            FlatText(mt("servers_hint"), color = p.muted, size = 11, modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

// ───────────────────────────── 账户 ─────────────────────────────

private fun accountColor(type: Account.AccountType?): Color = when (type) {
    Account.AccountType.MICROSOFT -> Tiles.Blue
    Account.AccountType.GITHUB -> Tiles.Graphite
    Account.AccountType.YGGDRASIL -> Tiles.Purple
    else -> Tiles.Slate
}

private fun accountTypeLabel(type: Account.AccountType?): String = when (type) {
    Account.AccountType.MICROSOFT -> mt("acc_microsoft")
    Account.AccountType.GITHUB -> "GitHub"
    Account.AccountType.YGGDRASIL -> mt("acc_yggdrasil")
    else -> mt("acc_offline")
}

@Composable
internal fun AccountsModule(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val status by vm.status.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ctx.section) {
                "skin" -> SkinSection(ctx)
                "offline" -> OfflineLoginSection(ctx)
                "microsoft" -> DeviceLoginSection(ctx, github = false)
                "github" -> DeviceLoginSection(ctx, github = true)
                "yggdrasil" -> YggdrasilSection(ctx)
                else -> AccountListSection(ctx)
            }
        }
        if (status.isNotBlank()) {
            FlatText(status, color = p.muted, size = 11, modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun AccountListSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val accounts by vm.accounts.collectAsState()
    val current by vm.account.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("accounts_title"), current?.let { mt("current_account", it.username) } ?: mt("no_account"), ctx.color, PmclIcons.Accounts) {
            HeroButton(mt("acc_offline"), Icons.Filled.Person, ctx.color, { ctx.openSection("offline") })
            HeroButton(mt("acc_microsoft"), Icons.AutoMirrored.Filled.Login, ctx.color, { ctx.openSection("microsoft") })
        }
        TileRow {
            ColorTile(mt("tile_accounts"), accounts.size.toString(), Tiles.Plum, Modifier.weight(1f).fillMaxHeight(), PmclIcons.Accounts, index = 0)
            ColorTile(mt("acc_microsoft"), accounts.count { it.type == Account.AccountType.MICROSOFT }.toString(), Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), index = 1)
            ColorTile(mt("acc_offline"), accounts.count { it.type == Account.AccountType.OFFLINE }.toString(), Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), index = 2)
            ColorTile(mt("acc_other"), accounts.count { it.type == Account.AccountType.GITHUB || it.type == Account.AccountType.YGGDRASIL }.toString(), Tiles.Purple, Modifier.weight(1f).fillMaxHeight(), index = 3)
        }
        if (accounts.isEmpty()) EmptyState(mt("accounts_empty"), PmclIcons.Accounts, mt("acc_offline"), ctx.color) { ctx.openSection("offline") }
        else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(accounts, key = { _, a -> a.uuid }) { i, a ->
                val avatar = rememberUrlImage(a.avatarUrl)
                val on = a.uuid == current?.uuid
                ItemRow(
                    a.username,
                    listOfNotNull(accountTypeLabel(a.type), a.authServerUrl?.takeIf { it.isNotBlank() }, a.uuid).joinToString(" · "),
                    accountColor(a.type),
                    i,
                    icon = Icons.Filled.Person,
                    image = avatar,
                    selected = on,
                    height = 56.dp,
                    onClick = { if (!on) vm.switchAccount(a.uuid) }
                ) {
                    if (on) FlatText(mt("in_use"), color = Color.White, size = 11, modifier = Modifier.background(ctx.color).padding(horizontal = 8.dp, vertical = 6.dp))
                    else RowButton(Icons.Filled.Check, { vm.switchAccount(a.uuid) }, label = mt("use"))
                    RowButton(Icons.Filled.Delete, { ctx.confirm(mt("remove_account_title"), mt("remove_account_body", a.username)) { vm.removeAccount(a.uuid) } })
                }
            }
        }
    }
}

@Composable
private fun SkinSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val current by vm.account.collectAsState()
    val acc = current
    var url by remember(acc?.uuid) { mutableStateOf(acc?.skinUrl.orEmpty()) }
    var model by remember(acc?.uuid) { mutableStateOf(acc?.skinModel?.takeIf { it.isNotBlank() } ?: "classic") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("skin_title"), acc?.let { "${it.username} · ${accountTypeLabel(it.type)}" } ?: mt("no_account"), ctx.color, Icons.Filled.Person)
        if (acc == null) {
            EmptyState(mt("no_account"), PmclIcons.Accounts, mt("accounts_title"), ctx.color) { ctx.openSection("list") }
            return@Column
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            val body = rememberUrlImage(acc.bodyRenderUrl)
            Box(Modifier.width(240.dp).fillMaxHeight().background(p.panel), contentAlignment = Alignment.Center) {
                if (body != null) Image(body, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp))
                else Icon(Icons.Filled.Person, null, tint = p.muted, modifier = Modifier.size(64.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                FormRow(mt("skin_model"), 0) {
                    Chips(listOf("classic" to mt("model_classic"), "slim" to mt("model_slim")), model, { model = it }, ctx.color)
                }
                when (acc.type) {
                    Account.AccountType.MICROSOFT -> {
                        FormRow(mt("skin_file"), 1) {
                            Block(mt("upload_skin"), { pickFile(mt("upload_skin"), setOf("png")).firstOrNull()?.let { vm.uploadMicrosoftSkin(it, model) } },
                                icon = Icons.Filled.Upload, fill = ctx.color, height = 30.dp)
                            Block(mt("reset_skin"), { vm.resetMicrosoftSkin() }, icon = Icons.Filled.Restore, fill = Tiles.Slate, height = 30.dp)
                        }
                    }
                    Account.AccountType.YGGDRASIL -> {
                        FormRow(mt("password"), 1) { InputBox(password, { password = it }, mt("password_hint"), Modifier.weight(1f), secret = true) }
                        FormRow(mt("skin_file"), 2) {
                            Block(mt("upload_skin"), { pickFile(mt("upload_skin"), setOf("png")).firstOrNull()?.let { vm.uploadYggdrasilSkin(it, model, password) } },
                                icon = Icons.Filled.Upload, fill = ctx.color, enabled = password.isNotEmpty(), height = 30.dp)
                        }
                    }
                    else -> {
                        FormRow(mt("skin_url"), 1) {
                            InputBox(url, { url = it }, mt("skin_url_hint"), Modifier.weight(1f))
                            Block(mt("apply"), { vm.setOfflineSkin(url.trim(), model) }, icon = Icons.Filled.Check, fill = ctx.color, height = 30.dp)
                        }
                        FlatText(mt("offline_skin_hint"), color = p.muted, size = 11, lines = 3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineLoginSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    var name by remember { mutableStateOf(runCatching { vm.lastOfflineUsername() }.getOrDefault("")) }
    val valid = name.length in 3..16 && name.all { it.isLetterOrDigit() || it == '_' }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("acc_offline"), mt("offline_sub"), Tiles.Slate, Icons.Filled.Person)
        FormRow(mt("username"), 0) {
            InputBox(name, { name = it.trim() }, "Steve", Modifier.width(280.dp), onSubmit = { if (valid) vm.loginOffline(name) })
            Block(mt("add_account"), { vm.loginOffline(name) }, icon = Icons.Filled.Add, fill = ctx.color, enabled = valid, height = 30.dp)
        }
        if (name.isNotEmpty() && !valid) FlatText(mt("username_rule"), color = Tiles.Red, size = 11, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        FlatText(mt("offline_hint"), color = p.muted, size = 11, lines = 4, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

@Composable
private fun DeviceLoginSection(ctx: ModuleCtx, github: Boolean) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val code by vm.deviceCode.collectAsState()
    val loggingIn by vm.loggingIn.collectAsState()
    val color = if (github) Tiles.Graphite else Tiles.Blue
    Column(Modifier.fillMaxSize()) {
        ModuleHero(if (github) mt("github_title") else mt("ms_title"), if (github) mt("github_sub") else mt("ms_sub"), color, Icons.AutoMirrored.Filled.Login) {
            HeroButton(if (loggingIn) I18n.t("studio.loading") else mt("start_login"), Icons.AutoMirrored.Filled.Login, color,
                { if (github) vm.startGitHubLogin() else vm.startMicrosoftLogin() }, enabled = !loggingIn)
        }
        val c = code
        if (!github && c != null) {
            TileRow(110.dp) {
                ColorTile(mt("device_code"), c.userCode.orEmpty(), Tiles.Navy, Modifier.weight(1.4f).fillMaxHeight(), valueSize = 30, index = 0)
                ColorTile(mt("copy_code"), "", Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.ContentCopy, { copyText(c.userCode.orEmpty()) }, index = 1)
                ColorTile(mt("open_page"), c.verificationUri.orEmpty(), Tiles.Blue, Modifier.weight(1.4f).fillMaxHeight(), Icons.AutoMirrored.Filled.OpenInNew,
                    { browse(c.verificationUri.orEmpty()) }, valueSize = 13, index = 2)
            }
        }
        if (loggingIn) ProgressLine(null, color)
        FlatText(if (github) mt("github_hint") else mt("ms_hint"), color = p.muted, size = 12, lines = 6, modifier = Modifier.padding(20.dp))
    }
}

@Composable
private fun YggdrasilSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    val loggingIn by vm.loggingIn.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val servers by produceState(emptyList<ExternalAuthServerStore.ExternalAuthServer>(), reload) {
        value = withContext(Dispatchers.IO) { runCatching { vm.listExternalAuthServers() }.getOrDefault(emptyList()) }
    }
    var selectedUrl by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(servers) { if (selectedUrl.isEmpty()) selectedUrl = servers.firstOrNull()?.url.orEmpty() }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("acc_yggdrasil"), mt("ygg_sub"), Tiles.Purple, Icons.AutoMirrored.Filled.Login)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(320.dp).fillMaxHeight().background(p.panel)) {
                LabelBar(mt("auth_servers", servers.size), Tiles.Purple)
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    InputBox(newUrl, { newUrl = it.trim() }, "https://littleskin.cn/api/yggdrasil", Modifier.weight(1f))
                    Block("", {
                        adding = true
                        error = ""
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { vm.addExternalAuthServer(newUrl) } }
                            adding = false
                            r.onSuccess { selectedUrl = it.url.orEmpty(); newUrl = ""; reload++ }.onFailure { error = it.message ?: it.toString() }
                        }
                    }, icon = Icons.Filled.Add, fill = Tiles.Purple, enabled = newUrl.startsWith("http") && !adding, height = 30.dp)
                }
                if (error.isNotBlank()) FlatText(error, color = Tiles.Red, size = 11, lines = 3, modifier = Modifier.padding(horizontal = 10.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(servers, key = { _, s -> s.url.orEmpty() }) { i, s ->
                        ItemRow(s.name.orEmpty().ifBlank { s.url.orEmpty() }, s.url.orEmpty(), Tiles.Purple, i, icon = Icons.Filled.Public, height = 44.dp,
                            selected = s.url == selectedUrl, onClick = { selectedUrl = s.url.orEmpty() }) {
                            RowButton(Icons.Filled.Delete, { vm.removeExternalAuthServer(s.url.orEmpty()); reload++ })
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                FormRow(mt("auth_server"), 0) { FlatText(selectedUrl.ifBlank { mt("pick_server") }, color = if (selectedUrl.isBlank()) p.muted else p.text) }
                FormRow(mt("username_email"), 1) { InputBox(username, { username = it }, "name@example.com", Modifier.weight(1f)) }
                FormRow(mt("password"), 2) { InputBox(password, { password = it }, mt("password_hint"), Modifier.weight(1f), secret = true) }
                Row(Modifier.padding(20.dp)) {
                    Block(if (loggingIn) I18n.t("studio.loading") else mt("login"), {
                        vm.startYggdrasilLogin(selectedUrl, username.trim(), password)
                        password = ""
                    }, icon = Icons.AutoMirrored.Filled.Login, fill = Tiles.Purple,
                        enabled = !loggingIn && selectedUrl.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), height = 34.dp)
                }
                if (loggingIn) ProgressLine(null, Tiles.Purple)
            }
        }
    }
}