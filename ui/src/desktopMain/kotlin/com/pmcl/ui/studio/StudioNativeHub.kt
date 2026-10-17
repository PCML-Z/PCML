package com.pmcl.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.download.DownloadQueueManager
import com.pmcl.core.download.DownloadQueueManager.TaskStatus as S
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.pmcl.core.gamecontent.ConfigFileManager
import com.pmcl.core.gamecontent.DatapackManager
import com.pmcl.core.gamecontent.OfflineSkinLibrary
import com.pmcl.core.gamecontent.ResourcePackManager
import com.pmcl.core.gamecontent.ShaderPackManager
import com.pmcl.core.i18n.I18n
import com.pmcl.core.market.ModProject
import com.pmcl.core.modloader.ModLoader
import com.pmcl.core.news.NewsItem
import com.pmcl.core.plugin.PluginManager
import com.pmcl.core.runtime.RuntimeManager
import com.pmcl.core.stats.PlayTimeTracker
import com.pmcl.ui.navigation.PmclIcons
import com.pmcl.ui.page.WikiWebController
import com.pmcl.ui.page.WikiWebView
import com.pmcl.ui.viewmodel.ManagedProjection
import com.pmcl.ui.viewmodel.QueueTaskSnapshot
import com.pmcl.ui.viewmodel.applyOfflineSkin
import com.pmcl.ui.viewmodel.clearArticle
import com.pmcl.ui.viewmodel.closeConfigFile
import com.pmcl.ui.viewmodel.closeModDetail
import com.pmcl.ui.viewmodel.deleteDatapack
import com.pmcl.ui.viewmodel.deleteMod
import com.pmcl.ui.viewmodel.deleteOfflineSkin
import com.pmcl.ui.viewmodel.deleteProjection
import com.pmcl.ui.viewmodel.deleteResourcePack
import com.pmcl.ui.viewmodel.deleteShaderPack
import com.pmcl.ui.viewmodel.deployOfflineSkins
import com.pmcl.ui.viewmodel.disableDatapack
import com.pmcl.ui.viewmodel.disableMod
import com.pmcl.ui.viewmodel.disableResourcePack
import com.pmcl.ui.viewmodel.disableShaderPack
import com.pmcl.ui.viewmodel.enableDatapack
import com.pmcl.ui.viewmodel.enableMod
import com.pmcl.ui.viewmodel.enableResourcePack
import com.pmcl.ui.viewmodel.enableShaderPack
import com.pmcl.ui.viewmodel.enterConfigDir
import com.pmcl.ui.viewmodel.importDatapack
import com.pmcl.ui.viewmodel.importMod
import com.pmcl.ui.viewmodel.importOfflineSkin
import com.pmcl.ui.viewmodel.importProjection
import com.pmcl.ui.viewmodel.importResourcePack
import com.pmcl.ui.viewmodel.importShaderPack
import com.pmcl.ui.viewmodel.installModWithDeps
import com.pmcl.ui.viewmodel.loadArticle
import com.pmcl.ui.viewmodel.navigateConfigUp
import com.pmcl.ui.viewmodel.offlineSkinFile
import com.pmcl.ui.viewmodel.openConfigDir
import com.pmcl.ui.viewmodel.openDatapacksDir
import com.pmcl.ui.viewmodel.openModDetail
import com.pmcl.ui.viewmodel.openModsDir
import com.pmcl.ui.viewmodel.openNewsLink
import com.pmcl.ui.viewmodel.openOfflineSkinsDir
import com.pmcl.ui.viewmodel.openProjectionsDir
import com.pmcl.ui.viewmodel.openResourcePacksDir
import com.pmcl.ui.viewmodel.openShaderPacksDir
import com.pmcl.ui.viewmodel.readConfigFile
import com.pmcl.ui.viewmodel.refreshConfigFiles
import com.pmcl.ui.viewmodel.refreshDatapacks
import com.pmcl.ui.viewmodel.refreshInstalledMods
import com.pmcl.ui.viewmodel.refreshNews
import com.pmcl.ui.viewmodel.refreshOfflineSkins
import com.pmcl.ui.viewmodel.refreshProjections
import com.pmcl.ui.viewmodel.refreshResourcePacks
import com.pmcl.ui.viewmodel.refreshShaderPacks
import com.pmcl.ui.viewmodel.refreshWorlds
import com.pmcl.ui.viewmodel.saveConfigFile
import com.pmcl.ui.viewmodel.searchMods
import com.pmcl.ui.viewmodel.selectDatapackWorld
import com.pmcl.ui.viewmodel.updateOfflineSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Paths
import com.pmcl.core.market.ModFile as MarketFile
import com.pmcl.core.mods.ModMeta as InstalledMod

// ───────────────────────────── 下载 ─────────────────────────────

@Composable
internal fun DownloadModule(ctx: ModuleCtx) {
    when (ctx.section) {
        "market" -> MarketSection(ctx)
        "queue" -> QueueSection(ctx)
        "wiki" -> WikiSection(ctx)
        else -> VersionsSection(ctx)
    }
}

private val installableLoaders = listOf(ModLoader.FABRIC, ModLoader.FORGE, ModLoader.NEOFORGE, ModLoader.QUILT, ModLoader.OPTIFINE, ModLoader.LEGACY_FABRIC)

@Composable
private fun VersionsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val versions by vm.versions.collectAsState()
    val loading by vm.loading.collectAsState()
    val locals by vm.localVersionInfos.collectAsState()
    val status by vm.status.collectAsState()
    LaunchedEffect(Unit) { if (versions.isEmpty()) vm.refreshVersions() }
    var kind by rememberSaveable { mutableStateOf("release") }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val installed = remember(locals) { locals.map { it.id }.toSet() }
    val q = query.trim()
    val shown = versions.filter { v ->
        (kind == "all" || v.type == kind || (kind == "old" && v.type.orEmpty().startsWith("old"))) && (q.isEmpty() || v.id.contains(q, true))
    }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("versions_title"), mt("versions_sub", versions.size, locals.size), ctx.color, PmclIcons.Download) {
            HeroButton(mt("refresh"), Icons.Filled.Refresh, ctx.color, { vm.refreshVersions() }, enabled = !loading)
        }
        TileRow {
            ColorTile(mt("latest_release"), versions.firstOrNull { it.type == "release" }?.id ?: "—", Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Download,
                { selectedId = versions.firstOrNull { it.type == "release" }?.id }, index = 0)
            ColorTile(mt("latest_snapshot"), versions.firstOrNull { it.type == "snapshot" }?.id ?: "—", Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.WbSunny,
                { kind = "snapshot"; selectedId = versions.firstOrNull { it.type == "snapshot" }?.id }, index = 1)
            ColorTile(mt("tile_installed"), locals.size.toString(), Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Inventory2, index = 2)
        }
        Toolbar {
            Chips(listOf("release" to mt("release"), "snapshot" to mt("snapshot"), "old" to mt("old_versions"), "all" to mt("all")), kind, { kind = it }, ctx.color, scrollable = false)
            Spacer(Modifier.width(8.dp))
            FlatField(query, { query = it }, mt("filter"), Modifier.width(220.dp), icon = Icons.Filled.Search)
            if (status.isNotBlank()) FlatText(status, color = p.muted, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (loading) ProgressLine(null, ctx.color)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (shown.isEmpty()) CenterNote(if (loading) I18n.t("studio.loading") else mt("nothing_here"))
                else LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { _, v -> v.id }) { i, v ->
                        val have = v.id in installed
                        ItemRow(
                            v.id,
                            listOf(typeLabel(v.type), v.releaseTime.orEmpty().take(10), if (have) mt("installed") else "").filter { it.isNotBlank() }.joinToString(" · "),
                            if (v.type == "release") ctx.color else if (v.type == "snapshot") Tiles.Navy else Tiles.Slate,
                            i,
                            badge = v.id.take(4),
                            selected = v.id == selectedId,
                            height = 44.dp,
                            onClick = { selectedId = v.id }
                        ) {
                            RowButton(Icons.Filled.Download, { vm.enqueueVersionInstall(v.id) }, if (have) null else ctx.color, label = if (have) mt("reinstall") else mt("install"))
                        }
                    }
                }
            }
            val sel = selectedId
            if (sel != null) LoaderPanel(ctx, sel, sel in installed) { selectedId = null }
        }
    }
}

private fun typeLabel(type: String?): String = when (type) {
    "release" -> mt("release")
    "snapshot" -> mt("snapshot")
    "old_beta" -> "Beta"
    "old_alpha" -> "Alpha"
    else -> type.orEmpty()
}

@Composable
private fun LoaderPanel(ctx: ModuleCtx, game: String, installed: Boolean, onClose: () -> Unit) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val loaderVersions by vm.modLoaderVersions.collectAsState()
    val loadingLoaders by vm.modLoaderVersionsLoading.collectAsState()
    var loader by remember(game) { mutableStateOf<ModLoader?>(null) }
    LaunchedEffect(game, loader) {
        val l = loader
        if (l == null) vm.clearModLoaderVersions() else vm.listModLoaderVersions(l, game)
    }
    Inspector(game, ctx.color, onClose, Modifier.width(360.dp)) {
        Block(if (installed) mt("reinstall_vanilla") else mt("install_vanilla"), { vm.enqueueVersionInstall(game) }, Modifier.fillMaxWidth(), Icons.Filled.Download, fill = ctx.color, height = 40.dp)
        LabelBar(mt("loaders"), ctx.color)
        Box(Modifier.padding(8.dp)) {
            Chips(installableLoaders.filter { it.isInstallable }.map { it.name to it.displayName }, loader?.name.orEmpty(), { id -> loader = ModLoader.valueOf(id) }, ctx.color)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val l = loader
            when {
                l == null -> CenterNote(mt("pick_loader"))
                loadingLoaders -> CenterNote(I18n.t("studio.loading"))
                loaderVersions.isEmpty() -> CenterNote(mt("no_loader_versions"))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(loaderVersions, key = { i, v -> "$i:${v.loaderVersion}" }) { i, v ->
                        ItemRow(v.loaderVersion, if (v.isStable) mt("stable") else mt("beta"), if (v.isStable) ctx.color else Tiles.Slate, i, badge = l.displayName.take(1), height = 40.dp) {
                            RowButton(Icons.Filled.Download, { vm.enqueueModLoaderInstall(l.name, game, v.loaderVersion) }, ctx.color, label = mt("install"))
                        }
                    }
                }
            }
        }
        FlatText(mt("install_queue_hint"), color = p.muted, size = 11, lines = 3, modifier = Modifier.padding(10.dp))
    }
}

private val marketKinds = listOf("mod", "modpack", "resourcepack", "shader")

@Composable
private fun MarketSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val results by vm.marketResults.collectAsState()
    val total by vm.marketTotal.collectAsState()
    val loading by vm.marketLoading.collectAsState()
    val detail by vm.detailProject.collectAsState()
    val selectedVersion by vm.selectedVersion.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("mod") }
    var source by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("default") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val filters = remember(selectedVersion) { vm.resolveMarketFilters() }
    var compatible by rememberSaveable { mutableStateOf(true) }
    fun search(reset: Boolean = true) {
        if (reset) page = 0
        vm.searchMods(
            query.trim(),
            gameVersion = if (compatible) filters.gameVersion.ifBlank { null } else null,
            loader = if (compatible && kind == "mod") filters.loader.ifBlank { null } else null,
            sort = sort,
            projectType = kind,
            source = source.ifBlank { null },
            offset = page * 20,
            limit = 20,
        )
    }
    LaunchedEffect(kind, source, sort, compatible) { search() }

    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("market_title"), mt("market_sub", selectedVersion ?: "—", total), ctx.color, Icons.Filled.CloudDownload)
        Toolbar {
            FlatField(query, { query = it }, mt("market_search"), Modifier.width(280.dp), icon = Icons.Filled.Search,
                onKey = { e -> if (e.key == Key.Enter && e.type == KeyEventType.KeyDown) { search(); true } else false })
            Block(mt("search"), { search() }, icon = Icons.Filled.Search, fill = ctx.color, height = 36.dp)
            Spacer(Modifier.width(8.dp))
            Chips(marketKinds.map { it to mt("kind_$it") }, kind, { kind = it }, ctx.color, scrollable = false)
        }
        Toolbar {
            Chips(listOf("" to mt("all_sources"), "modrinth" to "Modrinth", "curseforge" to "CurseForge"), source, { source = it }, Tiles.Navy, scrollable = false)
            Spacer(Modifier.width(8.dp))
            Chips(listOf("default" to mt("sort_default"), "downloads" to mt("sort_downloads"), "updated" to mt("sort_updated"), "newest" to mt("sort_newest")), sort, { sort = it }, Tiles.Slate, scrollable = false)
            Spacer(Modifier.width(8.dp))
            Block(if (compatible) mt("compatible_only", filters.gameVersion.ifBlank { "—" }) else mt("show_all"), { compatible = !compatible },
                icon = if (compatible) Icons.Filled.Check else null, fill = if (compatible) Tiles.Teal else p.field, height = 30.dp)
        }
        if (loading) ProgressLine(null, ctx.color)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (results.isEmpty()) CenterNote(if (loading) I18n.t("studio.loading") else mt("nothing_here"))
                    else LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(results, key = { i, r -> "$i:${r.source}:${r.id}" }) { i, r ->
                            val icon = rememberUrlImage(r.iconUrl)
                            ItemRow(
                                r.name.orEmpty(),
                                listOf(r.author.orEmpty(), mt("downloads_n", compactNumber(r.downloadCount)), r.source.orEmpty(), r.summary.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                                ctx.color,
                                i,
                                icon = Icons.Filled.Extension,
                                image = icon,
                                height = 56.dp,
                                selected = detail?.id == r.id && detail?.source == r.source,
                                onClick = { vm.openModDetail(r, if (compatible) filters.gameVersion.ifBlank { null } else null, if (compatible && kind == "mod") filters.loader.ifBlank { null } else null) }
                            )
                        }
                    }
                }
                Toolbar {
                    Block("", { page = (page - 1).coerceAtLeast(0); search(false) }, icon = Icons.AutoMirrored.Filled.ArrowBack, enabled = page > 0 && !loading, height = 36.dp)
                    FlatText(mt("page_n", page + 1, ((total + 19) / 20).coerceAtLeast(1)), modifier = Modifier.padding(horizontal = 10.dp))
                    Block("", { page++; search(false) }, icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = (page + 1) * 20 < total && !loading, height = 36.dp)
                }
            }
            val d = detail
            if (d != null) MarketDetail(ctx, d, kind, filters.gameVersion) { vm.closeModDetail() }
        }
    }
}

private fun compactNumber(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun MarketDetail(ctx: ModuleCtx, project: ModProject, kind: String, gameVersion: String, onClose: () -> Unit) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val files by vm.currentModFiles.collectAsState()
    val loading by vm.marketFilesLoading.collectAsState()
    val selectedVersion by vm.selectedVersion.collectAsState()
    val instanceId by vm.selectedInstanceId.collectAsState()
    val cover = rememberUrlImage(project.coverUrl?.ifBlank { null } ?: project.iconUrl)
    fun install(file: MarketFile, deps: Boolean) {
        val game = file.gameVersions?.firstOrNull { it == gameVersion } ?: gameVersion.ifBlank { file.gameVersions?.firstOrNull().orEmpty() }
        when (kind) {
            "modpack" -> vm.enqueueMarketModpack(file, instanceId)
            "shader", "resourcepack" -> vm.enqueueMarketContent(file, kind, selectedVersion, instanceId)
            else -> if (deps) vm.installModWithDeps(file, game, selectedVersion, instanceId) else vm.enqueueModDownload(file, game, selectedVersion, instanceId)
        }
    }
    Inspector(project.name.orEmpty(), ctx.color, onClose, Modifier.width(400.dp)) {
        Box(Modifier.fillMaxWidth().height(120.dp).background(p.field)) {
            if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        FlatText(project.summary.orEmpty(), color = p.text, size = 12, lines = 4, modifier = Modifier.padding(12.dp))
        FactRow(mt("author"), project.author.orEmpty(), 0)
        FactRow(mt("downloads"), compactNumber(project.downloadCount), 1)
        FactRow(mt("updated"), fmtDate(project.dateModified), 2)
        if (!project.websiteUrl.isNullOrBlank()) {
            Block(mt("open_page"), { browse(project.websiteUrl) }, Modifier.fillMaxWidth(), Icons.AutoMirrored.Filled.OpenInNew, fill = Tiles.Slate, height = 32.dp)
        }
        LabelBar(mt("files_n", files.size), ctx.color) {
            FlatText(mt("install_to", selectedVersion ?: "—"), color = p.muted, size = 11, modifier = Modifier.padding(end = 8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading) CenterNote(I18n.t("studio.loading"))
            else if (files.isEmpty()) CenterNote(mt("no_files"))
            else LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(files, key = { i, f -> "$i:${f.fileId}" }) { i, f ->
                    ItemRow(
                        f.versionNumber?.ifBlank { null } ?: f.fileName.orEmpty(),
                        listOf(f.gameVersions.orEmpty().take(3).joinToString(", "), f.loaders.orEmpty().joinToString(", "), formatSize(f.fileSize)).filter { it.isNotBlank() }.joinToString(" · "),
                        when (f.releaseType?.lowercase()) { "beta" -> Tiles.Orange; "alpha" -> Tiles.Red; else -> ctx.color },
                        i,
                        icon = Icons.Filled.Download,
                        height = 46.dp
                    ) {
                        if (kind == "mod" && !f.dependencies.isNullOrEmpty()) RowButton(Icons.Filled.Extension, { install(f, true) }, label = mt("with_deps"))
                        RowButton(Icons.Filled.Download, { install(f, false) }, ctx.color)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val tasks by vm.queueTasks.collectAsState()
    val summary by vm.queueSummary.collectAsState()
    LaunchedEffect(Unit) {
        vm.initDownloadQueue()
        vm.refreshQueue()
    }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("queue_title"), mt("queue_sub", summary.active(), summary.total()), ctx.color, Icons.Filled.Download) {
            HeroButton(mt("resume_all"), Icons.Filled.PlayArrow, ctx.color, { vm.resumeAllQueue() })
            HeroButton(mt("pause_all"), Icons.Filled.Pause, ctx.color, { vm.pauseAllQueue() })
            HeroButton(mt("clear_done"), Icons.Filled.Check, ctx.color, { vm.clearFinishedQueue() })
            HeroButton(mt("cancel_all"), Icons.Filled.Close, ctx.color, { ctx.confirm(mt("cancel_all"), mt("cancel_all_body")) { vm.cancelAllQueue() } })
        }
        TileRow {
            ColorTile(mt("q_running"), summary.running.toString(), if (summary.running > 0) Tiles.Orange else Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), index = 0)
            ColorTile(mt("q_queued"), summary.queued.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), index = 1)
            ColorTile(mt("q_done"), summary.done.toString(), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), index = 2)
            ColorTile(mt("q_failed"), summary.failed.toString(), if (summary.failed > 0) Tiles.Red else Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), index = 3)
            ColorTile(mt("q_progress"), "${(summary.overallProgress() * 100).toInt()}%", Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), index = 4)
        }
        if (tasks.isEmpty()) EmptyState(mt("queue_empty"), Icons.Filled.Download, color = ctx.color)
        else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tasks, key = { _, t -> t.id }) { i, t -> QueueRow(ctx, t, i) }
        }
    }
}

@Composable
private fun QueueRow(ctx: ModuleCtx, t: QueueTaskSnapshot, index: Int) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val color = when (t.status) {
        S.RUNNING -> Tiles.Orange
        S.FAILED -> Tiles.Red
        S.DONE -> ctx.color
        S.PAUSED, S.CANCELLED -> Tiles.Slate
        else -> Tiles.Navy
    }
    Column(Modifier.fillMaxWidth()) {
        ItemRow(
            t.name,
            listOfNotNull(
                statusLabel(t.status),
                if (t.totalBytes > 0) "${formatSize(t.completedBytes)} / ${formatSize(t.totalBytes)}" else null,
                t.errorMessage?.takeIf { it.isNotBlank() } ?: t.message?.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            color,
            index,
            icon = Icons.Filled.Download
        ) {
            when (t.status) {
                S.RUNNING, S.QUEUED -> RowButton(Icons.Filled.Pause, { vm.pauseQueueTask(t.id) })
                S.PAUSED, S.FAILED -> RowButton(Icons.Filled.PlayArrow, { vm.resumeQueueTask(t.id) }, label = if (t.status == S.FAILED) mt("retry") else "")
                else -> {}
            }
            if (t.status == S.DONE || t.status == S.FAILED || t.status == S.CANCELLED) RowButton(Icons.Filled.Delete, { vm.removeQueueTask(t.id) })
            else RowButton(Icons.Filled.Close, { vm.cancelQueueTask(t.id) })
        }
        if (t.status == S.RUNNING || t.status == S.PAUSED) ProgressLine(t.progress().toFloat().takeIf { t.totalBytes > 0 }, color, Modifier.background(p.panel), 3.dp)
    }
}

private fun statusLabel(s: DownloadQueueManager.TaskStatus): String = mt("status_" + s.name.lowercase())

private val wikiHome = "https://minecraft.wiki/"
private val wikiZhHome = "https://zh.minecraft.wiki/"

@Composable
private fun WikiSection(ctx: ModuleCtx) {
    val p = LocalStudio.current
    val zh = remember { runCatching { ctx.vm.preferences.getLanguage() }.getOrDefault("").startsWith("zh") }
    val home = if (zh) wikiZhHome else wikiHome
    var address by rememberSaveable { mutableStateOf(home) }
    var url by rememberSaveable { mutableStateOf(home) }
    var title by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    val controller = remember { WikiWebController() }
    fun go(target: String) {
        val u = target.trim()
        val next = when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.isEmpty() -> home
            else -> home + "w/Special:Search?search=" + java.net.URLEncoder.encode(u, Charsets.UTF_8)
        }
        address = next
        url = next
    }
    val pages = listOf("Block", "Item", "Mob", "Crafting", "Enchanting", "Biome", "Commands")
    Column(Modifier.fillMaxSize()) {
        Toolbar {
            Block("", { controller.goBack() }, icon = Icons.AutoMirrored.Filled.ArrowBack, enabled = canBack, height = 36.dp)
            Block("", { controller.goForward() }, icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = canForward, height = 36.dp)
            Block("", { controller.reload() }, icon = Icons.Filled.Refresh, enabled = !loading, height = 36.dp)
            Block("", { go(home) }, icon = Icons.Filled.Home, height = 36.dp)
            FlatField(address, { address = it }, mt("wiki_address"), Modifier.width(420.dp), icon = Icons.Filled.Search,
                onKey = { e -> if (e.key == Key.Enter && e.type == KeyEventType.KeyDown) { go(address); true } else false })
            Block("", { browse(url) }, icon = Icons.AutoMirrored.Filled.OpenInNew, fill = Tiles.Slate, height = 36.dp)
        }
        Row(Modifier.fillMaxWidth().height(30.dp).background(p.panel)) {
            pages.forEach { pg -> Block(pg, { go(home + "w/" + pg) }, height = 30.dp) }
        }
        if (loading) ProgressLine(null, ctx.color, height = 3.dp)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            WikiWebView(
                url = url,
                controller = controller,
                onUrlChanged = { address = it; url = it },
                onTitleChanged = { title = it },
                onLoadingChanged = { loading = it },
                onNavigationStateChanged = { b, f -> canBack = b; canForward = f },
                modifier = Modifier.fillMaxSize()
            )
        }
        FlatText(if (loading) I18n.t("studio.loading") else title, color = p.muted, size = 11, modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

// ───────────────────────────── 内容 ─────────────────────────────

@Composable
internal fun ContentModule(ctx: ModuleCtx) {
    when (ctx.section) {
        "modpacks" -> ModpacksSection(ctx)
        "shaders" -> ShadersSection(ctx)
        "projections" -> ProjectionsSection(ctx)
        "resourcepacks" -> ResourcePacksSection(ctx)
        "datapacks" -> DatapacksSection(ctx)
        "configs" -> ConfigFilesSection(ctx)
        "skins" -> OfflineSkinsSection(ctx)
        else -> InstalledModsSection(ctx)
    }
}

/** 内容类分区共用的骨架：标题色块、统计磁贴、搜索条和列表。 */
@Composable
private fun <T> ContentListPage(
    ctx: ModuleCtx,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    items: List<T>,
    key: (T) -> String,
    matches: (T, String) -> Boolean,
    enabledCount: Int?,
    onImport: (() -> Unit)?,
    onOpenDir: (() -> Unit)?,
    onRefresh: () -> Unit,
    extraTiles: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    toolbar: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    row: @Composable (T, Int) -> Unit,
) {
    val p = LocalStudio.current
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val shown = if (q.isEmpty()) items else items.filter { matches(it, q) }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(title, mt("items_n", items.size), ctx.color, icon) {
            if (onImport != null) HeroButton(mt("import"), Icons.Filled.FileOpen, ctx.color, onImport)
            if (onOpenDir != null) HeroButton(mt("open_folder"), Icons.Filled.FolderOpen, ctx.color, onOpenDir)
            HeroButton(mt("refresh"), Icons.Filled.Refresh, ctx.color, onRefresh)
        }
        TileRow(72.dp) {
            ColorTile(mt("tile_total"), items.size.toString(), ctx.color, Modifier.weight(1f).fillMaxHeight(), icon, index = 0)
            if (enabledCount != null) {
                ColorTile(mt("tile_enabled"), enabledCount.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Check, index = 1)
                ColorTile(mt("tile_disabled"), (items.size - enabledCount).toString(), Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Block, index = 2)
            }
            extraTiles()
        }
        Toolbar {
            FlatField(query, { query = it }, mt("filter"), Modifier.width(260.dp), icon = Icons.Filled.Search)
            toolbar()
        }
        if (shown.isEmpty()) {
            EmptyState(if (items.isEmpty()) mt("nothing_here") else mt("no_match"), icon, if (items.isEmpty() && onImport != null) mt("import") else null, ctx.color) { onImport?.invoke() }
        } else {
            LazyColumn(Modifier.fillMaxSize().background(p.editor)) {
                itemsIndexed(shown, key = { _, it -> key(it) }) { i, it -> row(it, i) }
            }
        }
    }
}

@Composable
private fun InstalledModsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val mods by vm.installedMods.collectAsState()
    val selectedVersion by vm.selectedVersion.collectAsState()
    LaunchedEffect(selectedVersion) { vm.refreshInstalledMods() }
    ContentListPage<InstalledMod>(
        ctx, mt("mods_title", selectedVersion ?: "—"), Icons.Filled.Extension, mods,
        key = { it.jarFile },
        matches = { m, q -> m.name.orEmpty().contains(q, true) || m.modId.orEmpty().contains(q, true) || m.jarFile.contains(q, true) },
        enabledCount = mods.count { !it.isDisabled },
        onImport = { pickFile(mt("import"), setOf("jar"), multiple = true).forEach { vm.importMod(it.toString()) } },
        onOpenDir = { vm.openModsDir() },
        onRefresh = { vm.refreshInstalledMods() },
        toolbar = {
            Block(mt("enable_all"), { mods.filter { it.isDisabled }.forEach { vm.enableMod(it) } }, icon = Icons.Filled.Check, height = 36.dp)
            Block(mt("disable_all"), { mods.filter { !it.isDisabled }.forEach { vm.disableMod(it) } }, icon = Icons.Filled.Block, height = 36.dp)
        }
    ) { m, i ->
        ItemRow(
            m.name?.ifBlank { null } ?: m.jarFile,
            listOf(m.version.orEmpty(), m.loader.orEmpty(), m.authors.orEmpty(), m.jarFile).filter { it.isNotBlank() }.joinToString(" · "),
            ctx.color, i, badge = (m.name?.ifBlank { null } ?: m.jarFile).take(1).uppercase(), dim = m.isDisabled
        ) {
            RowButton(if (m.isDisabled) Icons.Filled.Check else Icons.Filled.Block, { if (m.isDisabled) vm.enableMod(m) else vm.disableMod(m) },
                label = if (m.isDisabled) mt("enable") else mt("disable"))
            RowButton(Icons.Filled.Delete, { ctx.confirm(mt("delete_file_title"), mt("delete_file_body", m.jarFile)) { vm.deleteMod(m) } })
        }
    }
}

@Composable
private fun ModpacksSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val packs by vm.modpacks.collectAsState()
    val busy by vm.modpackBusy.collectAsState()
    LaunchedEffect(Unit) { vm.refreshModpacks() }
    ContentListPage(
        ctx, mt("modpacks_title"), Icons.Filled.Inventory2, packs,
        key = { it.directoryName },
        matches = { m, q -> m.name.contains(q, true) || m.gameVersion.orEmpty().contains(q, true) },
        enabledCount = null,
        onImport = { pickFile(mt("import"), setOf("zip", "mrpack")).firstOrNull()?.let { vm.importModpack(it.toString()) } },
        onOpenDir = null,
        onRefresh = { vm.refreshModpacks() },
        extraTiles = {
            ColorTile(mt("tile_mods"), packs.sumOf { it.modCount }.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Extension, index = 1)
            ColorTile(mt("status"), if (busy) I18n.t("studio.loading") else "—", if (busy) Tiles.Orange else Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), valueSize = 14, index = 2)
        }
    ) { m, i ->
        ItemRow(m.name, listOf(m.gameVersion.orEmpty(), listOfNotNull(m.loader, m.loaderVersion).joinToString(" "), mt("mods_n", m.modCount), m.source.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · "), ctx.color, i, icon = Icons.Filled.Inventory2) {
            RowButton(Icons.Filled.FolderOpen, { vm.openDir(m.instanceDir.toFile()) })
            RowButton(Icons.Filled.Delete, { ctx.confirm(mt("delete_file_title"), mt("delete_file_body", m.name)) { vm.deleteModpack(m.directoryName) } })
        }
    }
}

@Composable
private fun ShadersSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val packs by vm.shaderPacks.collectAsState()
    LaunchedEffect(Unit) { vm.refreshShaderPacks() }
    ContentListPage<ShaderPackManager.ShaderPack>(
        ctx, mt("shaders_title"), Icons.Filled.WbSunny, packs,
        key = { it.path.toString() },
        matches = { s, q -> s.name.contains(q, true) },
        enabledCount = packs.count { !it.isDisabled },
        onImport = { pickFile(mt("import"), setOf("zip"), multiple = true).forEach { vm.importShaderPack(it.toString()) } },
        onOpenDir = { vm.openShaderPacksDir() },
        onRefresh = { vm.refreshShaderPacks() },
        extraTiles = {
            ColorTile(mt("active"), packs.firstOrNull { it.isActive }?.name ?: "—", Tiles.Blue, Modifier.weight(1.4f).fillMaxHeight(), valueSize = 14, index = 3)
        }
    ) { s, i ->
        ItemRow(s.name, listOf(formatSize(s.size), if (s.isActive) mt("active") else "", if (!s.isValid) mt("invalid_pack") else "", s.source.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · "), if (s.isActive) Tiles.Blue else ctx.color, i, icon = Icons.Filled.WbSunny, dim = s.isDisabled) {
            RowButton(if (s.isDisabled) Icons.Filled.Check else Icons.Filled.Block, { if (s.isDisabled) vm.enableShaderPack(s) else vm.disableShaderPack(s) },
                label = if (s.isDisabled) mt("enable") else mt("disable"))
            RowButton(Icons.Filled.Delete, { ctx.confirm(mt("delete_file_title"), mt("delete_file_body", s.name)) { vm.deleteShaderPack(s) } })
        }
    }
}

@Composable
private fun ResourcePacksSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val packs by vm.resourcePacks.collectAsState()
    LaunchedEffect(Unit) { vm.refreshResourcePacks() }
    ContentListPage<ResourcePackManager.Pack>(
        ctx, mt("resourcepacks_title"), Icons.Filled.Palette, packs,
        key = { it.path.toString() },
        matches = { s, q -> s.name.contains(q, true) || s.description.orEmpty().contains(q, true) },
        enabledCount = packs.count { !it.isDisabled },
        onImport = { pickFile(mt("import"), setOf("zip"), multiple = true).forEach { vm.importResourcePack(it.toString()) } },
        onOpenDir = { vm.openResourcePacksDir() },
        onRefresh = { vm.refreshResourcePacks() }
    ) { s, i ->
        val icon = rememberThumb(if (s.isZip) null else s.path.resolve("pack.png"), 96)
        ItemRow(s.name, listOf(s.description.orEmpty().replace(Regex("§."), ""), mt("format_n", s.packFormat)).filter { it.isNotBlank() }.joinToString(" · "),
            ctx.color, i, icon = Icons.Filled.Palette, image = icon, dim = s.isDisabled) {
            RowButton(if (s.isDisabled) Icons.Filled.Check else Icons.Filled.Block, { if (s.isDisabled) vm.enableResourcePack(s) else vm.disableResourcePack(s) },
                label = if (s.isDisabled) mt("enable") else mt("disable"))
            RowButton(Icons.Filled.Delete, { ctx.confirm(mt("delete_file_title"), mt("delete_file_body", s.name)) { vm.deleteResourcePack(s) } })
        }
    }
}

@Composable
private fun DatapacksSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val worlds by vm.worlds.collectAsState()
    val world by vm.selectedDatapackWorld.collectAsState()
    val packs by vm.datapacks.collectAsState()
    LaunchedEffect(Unit) { vm.refreshWorlds() }
    LaunchedEffect(worlds) { if (world == null) worlds.firstOrNull()?.let { vm.selectDatapackWorld(it) } }
    val w = world
    ContentListPage<DatapackManager.Datapack>(
        ctx, mt("datapacks_title", w?.name ?: "—"), Icons.Filled.Storage, if (w == null) emptyList() else packs,
        key = { it.path.toString() },
        matches = { s, q -> s.name.contains(q, true) },
        enabledCount = packs.count { !it.isDisabled },
        onImport = if (w == null) null else ({ pickFile(mt("import"), setOf("zip"), multiple = true).forEach { vm.importDatapack(it.toString()) } }),
        onOpenDir = if (w == null) null else ({ vm.openDatapacksDir(w) }),
        onRefresh = { if (w != null) vm.refreshDatapacks(w.dir) else vm.refreshWorlds() },
        toolbar = {
            FlatText(mt("world"), color = p.muted, modifier = Modifier.padding(horizontal = 8.dp))
            worlds.forEach { o -> Block(o.name, { vm.selectDatapackWorld(o) }, fill = if (o.dir == w?.dir) ctx.color else null, height = 36.dp) }
        }
    ) { s, i ->
        ItemRow(s.name, listOf(s.description.orEmpty().replace(Regex("§."), ""), mt("format_n", s.packFormat)).filter { it.isNotBlank() }.joinToString(" · "),
            ctx.color, i, icon = Icons.Filled.Storage, dim = s.isDisabled) {
            RowButton(if (s.isDisabled) Icons.Filled.Check else Icons.Filled.Block, { if (s.isDisabled) vm.enableDatapack(s) else vm.disableDatapack(s) },
                label = if (s.isDisabled) mt("enable") else mt("disable"))
            RowButton(Icons.Filled.Delete, { ctx.confirm(mt("delete_file_title"), mt("delete_file_body", s.name)) { vm.deleteDatapack(s) } })
        }
    }
}

@Composable
private fun ProjectionsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val rows by vm.projections.collectAsState()
    LaunchedEffect(Unit) { vm.refreshProjections() }
    ContentListPage<ManagedProjection>(
        ctx, mt("projections_title"), Icons.Filled.Videocam, rows,
        key = { it.path },
        matches = { s, q -> s.title.contains(q, true) || s.fileName.contains(q, true) },
        enabledCount = null,
        onImport = { pickFile(mt("import"), setOf("litematic", "schematic", "schem", "nbt"), multiple = true).forEach { vm.importProjection(it.toString()) } },
        onOpenDir = { vm.openProjectionsDir() },
        onRefresh = { vm.refreshProjections() }
    ) { s, i ->
        ItemRow(s.title.ifBlank { s.fileName }, listOf(s.fileName, formatSize(s.size), fmtDate(s.modified), s.summary).filter { it.isNotBlank() }.joinToString(" · "),
            ctx.color, i, icon = Icons.Filled.Videocam) {
            RowButton(Icons.Filled.Delete, { ctx.confirm(mt("delete_file_title"), mt("delete_file_body", s.fileName)) { vm.deleteProjection(s) } })
        }
    }
}

@Composable
private fun OfflineSkinsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val skins by vm.offlineSkins.collectAsState()
    LaunchedEffect(Unit) { vm.refreshOfflineSkins() }
    var editing by remember { mutableStateOf<String?>(null) }
    ContentListPage<OfflineSkinLibrary.Skin>(
        ctx, mt("skins_title"), Icons.Filled.Person, skins,
        key = { it.id() },
        matches = { s, q -> s.name().contains(q, true) || s.player().orEmpty().contains(q, true) },
        enabledCount = null,
        onImport = { pickFile(mt("import"), setOf("png"), multiple = true).forEach { vm.importOfflineSkin(it) } },
        onOpenDir = { vm.openOfflineSkinsDir() },
        onRefresh = { vm.refreshOfflineSkins() },
        toolbar = { Block(mt("deploy_skins"), { vm.deployOfflineSkins() }, icon = Icons.Filled.Check, fill = ctx.color, height = 36.dp) }
    ) { s, i ->
        val face = rememberThumb(runCatching { Paths.get(vm.offlineSkinFile(s)) }.getOrNull(), 64)
        if (editing == s.id()) {
            SkinEditRow(ctx, s, i) { editing = null }
        } else {
            ItemRow(s.name(), listOf(s.player().orEmpty().ifBlank { mt("any_player") }, if (s.model() == "slim") mt("model_slim") else mt("model_classic"), fmtDate(s.addedAt()))
                .joinToString(" · "), ctx.color, i, icon = Icons.Filled.Person, image = face, onClick = { editing = s.id() }) {
                RowButton(Icons.Filled.Check, { vm.applyOfflineSkin(s.id(), s.name(), s.model()) }, ctx.color, label = mt("apply"))
                RowButton(Icons.Filled.Delete, { ctx.confirm(mt("delete_file_title"), mt("delete_file_body", s.name())) { vm.deleteOfflineSkin(s.id()) } })
            }
        }
    }
}

@Composable
private fun SkinEditRow(ctx: ModuleCtx, s: OfflineSkinLibrary.Skin, index: Int, onDone: () -> Unit) {
    val p = LocalStudio.current
    var name by remember { mutableStateOf(s.name()) }
    var player by remember { mutableStateOf(s.player().orEmpty()) }
    var model by remember { mutableStateOf(s.model() ?: "classic") }
    Row(Modifier.fillMaxWidth().background(p.selected).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        InputBox(name, { name = it }, mt("name"), Modifier.width(180.dp))
        Spacer(Modifier.width(6.dp))
        InputBox(player, { player = it.trim() }, mt("any_player"), Modifier.width(160.dp))
        Spacer(Modifier.width(6.dp))
        Chips(listOf("classic" to mt("model_classic"), "slim" to mt("model_slim")), model, { model = it }, ctx.color)
        Spacer(Modifier.weight(1f))
        Block("", { ctx.vm.updateOfflineSkin(s.id(), name.trim(), player, model); onDone() }, icon = Icons.Filled.Save, fill = ctx.color, height = 30.dp)
        Block("", onDone, icon = Icons.Filled.Close, height = 30.dp)
    }
}

@Composable
private fun ConfigFilesSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val files by vm.configFiles.collectAsState()
    val dir by vm.configCurrentDir.collectAsState()
    val content by vm.configFileContent.collectAsState()
    val path by vm.currentConfigPath.collectAsState()
    val selectedVersion by vm.selectedVersion.collectAsState()
    LaunchedEffect(selectedVersion) { vm.refreshConfigFiles() }
    var text by remember(path, content) { mutableStateOf(content.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    val dirty = content != null && text != content
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("configs_title", selectedVersion ?: "—"), "config/" + dir, ctx.color, Icons.Filled.Description) {
            HeroButton(mt("open_folder"), Icons.Filled.FolderOpen, ctx.color, { vm.openConfigDir() })
            HeroButton(mt("refresh"), Icons.Filled.Refresh, ctx.color, { vm.refreshConfigFiles(dir) })
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(320.dp).fillMaxHeight().background(p.panel)) {
                FlatField(query, { query = it }, mt("filter"), Modifier.fillMaxWidth(), icon = Icons.Filled.Search)
                if (dir.isNotEmpty()) ItemRow("..", mt("parent_dir"), Tiles.Slate, 0, icon = Icons.Filled.Folder, height = 36.dp, onClick = { vm.navigateConfigUp() })
                val shown = files.filter { query.isBlank() || it.fileName.contains(query.trim(), true) }.sortedWith(compareBy({ !it.isDirectory }, { it.fileName.lowercase() }))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(shown, key = { _, f -> f.relativePath }) { i, f: ConfigFileManager.ConfigFileEntry ->
                        ItemRow(f.fileName, if (f.isDirectory) mt("folder") else "${f.format.orEmpty()} · ${formatSize(f.size)}",
                            if (f.isDirectory) Tiles.Slate else ctx.color, i + 1, icon = if (f.isDirectory) Icons.Filled.Folder else Icons.Filled.Description, height = 36.dp,
                            selected = f.relativePath == path,
                            onClick = { if (f.isDirectory) vm.enterConfigDir(f.fileName) else vm.readConfigFile(f.relativePath) })
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (path == null) {
                    CenterNote(mt("pick_config"))
                } else {
                    Toolbar {
                        FlatText(path.orEmpty(), weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
                        if (dirty) FlatText("●", color = Tiles.Orange)
                        Block(mt("save"), { vm.saveConfigFile(text) }, icon = Icons.Filled.Save, fill = ctx.color, enabled = dirty, height = 36.dp)
                        Block(mt("revert"), { text = content.orEmpty() }, icon = Icons.Filled.Refresh, enabled = dirty, height = 36.dp)
                        Block("", { vm.closeConfigFile() }, icon = Icons.Filled.Close, height = 36.dp)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().background(p.editor).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        BasicTextField(
                            text, { text = it },
                            textStyle = TextStyle(color = p.text, fontSize = 12.sp, fontFamily = mono.fontFamily),
                            cursorBrush = SolidColor(p.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────────── 统计 ─────────────────────────────

@Composable
internal fun StatisticsModule(ctx: ModuleCtx) {
    when (ctx.section) {
        "overview" -> PlayOverviewSection(ctx)
        "sessions" -> SessionsSection(ctx)
        "breakdown" -> BreakdownSection(ctx)
        else -> PerformanceSection(ctx)
    }
}

private class PerfSample(
    val cpu: Double,
    val mem: Double,
    val heapUsed: Long,
    val heapMax: Long,
    val threads: Int,
    val disk: DoubleArray?,
    val net: DoubleArray?,
)

@Composable
private fun PerformanceSection(ctx: ModuleCtx) {
    val p = LocalStudio.current
    val runtime = remember { RuntimeManager() }
    val cpuHistory = remember { mutableStateListOf<Long>() }
    val memHistory = remember { mutableStateListOf<Long>() }
    val info by produceState<List<Pair<String, String>>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                listOf(
                    "CPU" to runtime.cpuName.orEmpty(),
                    mt("cores") to "${runtime.cpuPhysicalCores} / ${runtime.cpuLogicalCores}",
                    mt("memory") to "${runtime.totalMemoryMb} MB",
                    "GPU" to runtime.primaryGpuName.orEmpty(),
                    "VRAM" to "${runtime.primaryGpuVramMb} MB",
                    mt("uptime") to formatDuration(runtime.systemUptimeSeconds),
                )
            }.getOrDefault(emptyList())
        }
    }
    val sample by produceState<PerfSample?>(null) {
        while (isActive) {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    PerfSample(runtime.cpuLoad, runtime.memoryLoad, runtime.jvmHeapUsedMb, runtime.jvmHeapMaxMb, runtime.jvmThreadCount, runtime.pmclDiskUsage, runtime.networkSpeedKbS)
                }.getOrNull()
            }
            if (s != null) {
                value = s
                cpuHistory.add((s.cpu * 100).toLong().coerceAtLeast(0)); if (cpuHistory.size > 48) cpuHistory.removeAt(0)
                memHistory.add((s.mem * 100).toLong().coerceAtLeast(0)); if (memHistory.size > 48) memHistory.removeAt(0)
            }
            delay(1000)
        }
    }
    val s = sample
    fun pct(v: Double?) = v?.let { "${(it * 100).toInt()}%" } ?: "—"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ModuleHero(mt("perf_title"), mt("perf_sub"), ctx.color, Icons.Filled.Speed)
        TileRow {
            ColorTile("CPU", pct(s?.cpu), if ((s?.cpu ?: 0.0) > 0.85) Tiles.Orange else Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Memory, index = 0)
            ColorTile(mt("memory"), pct(s?.mem), if ((s?.mem ?: 0.0) > 0.9) Tiles.Orange else Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Storage, index = 1)
            ColorTile(mt("heap"), s?.let { "${it.heapUsed} / ${it.heapMax} MB" } ?: "—", Tiles.Blue, Modifier.weight(1.3f).fillMaxHeight(), valueSize = 16, index = 2)
            ColorTile(mt("threads"), s?.threads?.toString() ?: "—", Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), index = 3)
        }
        TileRow(72.dp) {
            val d = s?.disk
            ColorTile(mt("disk"), if (d != null && d.size >= 3) "%.1f / %.1f GB".format(d[0], d[1]) else "—", Tiles.Teal, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Storage, valueSize = 15, index = 4)
            val n = s?.net
            ColorTile(mt("network"), if (n != null && n.size >= 2) "↑ %.0f  ↓ %.0f KB/s".format(n[0], n[1]) else "—", Tiles.Purple, Modifier.weight(1.4f).fillMaxHeight(), valueSize = 15, index = 5)
        }
        LabelBar(mt("cpu_history"), ctx.color)
        BarChart(cpuHistory.toList(), emptyList(), Tiles.Navy, Modifier.height(110.dp), highlight = cpuHistory.size - 1)
        LabelBar(mt("mem_history"), ctx.color)
        BarChart(memHistory.toList(), emptyList(), Tiles.Sky, Modifier.height(110.dp), highlight = memHistory.size - 1)
        LabelBar(mt("system"), ctx.color)
        info.forEachIndexed { i, (k, v) -> FactRow(k, v, i) }
        Spacer(Modifier.height(12.dp).background(p.editor))
    }
}

@Composable
private fun PlayOverviewSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val overall by vm.playTimeStats.collectAsState()
    val daily by vm.dailyStats.collectAsState()
    val days by vm.statsDays.collectAsState()
    val weekdays by vm.weekdayDist.collectAsState()
    val records by vm.records.collectAsState()
    LaunchedEffect(Unit) { vm.refreshPlayTimeStats() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ModuleHero(mt("play_title"), mt("play_sub", days), ctx.color, PmclIcons.Statistics) {
            listOf(7, 14, 30, 90).forEach { d -> Block(mt("days_n", d), { vm.setStatsDays(d) }, fill = ctx.color.lift(if (d == days) 0.32f else 0.1f), height = 34.dp) }
        }
        TileRow {
            ColorTile(mt("total_time"), PlayTimeTracker.formatDuration(overall?.totalDuration ?: 0), Tiles.Navy, Modifier.weight(1.3f).fillMaxHeight(), Icons.Filled.Timer, valueSize = 18, index = 0)
            ColorTile(mt("sessions"), (overall?.totalSessions ?: 0).toString(), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), index = 1)
            ColorTile(mt("streak"), mt("days_n", records?.currentStreakDays ?: 0), Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), valueSize = 18, index = 2)
            ColorTile(mt("longest_streak"), mt("days_n", records?.longestStreakDays ?: 0), Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), valueSize = 18, index = 3)
        }
        TileRow(72.dp) {
            ColorTile(mt("longest_session"), records?.longestSession?.let { PlayTimeTracker.formatDuration(it.duration) } ?: "—", Tiles.Teal, Modifier.weight(1f).fillMaxHeight(), valueSize = 15, index = 4)
            ColorTile(mt("favorite_hour"), records?.mostPlayedHour?.ifBlank { null } ?: "—", Tiles.Purple, Modifier.weight(1f).fillMaxHeight(), valueSize = 15, index = 5)
            ColorTile(mt("first_play"), records?.firstPlayDate?.ifBlank { null } ?: "—", Tiles.Graphite, Modifier.weight(1f).fillMaxHeight(), valueSize = 15, index = 6)
        }
        LabelBar(mt("daily"), ctx.color)
        BarChart(daily.map { it.totalDuration }, daily.map { it.date.takeLast(2) }, ctx.color, Modifier.height(150.dp), highlight = daily.size - 1)
        LabelBar(mt("weekday"), ctx.color)
        BarChart(weekdays.map { it.totalDuration }, weekdays.map { it.dayName.take(3) }, Tiles.Navy, Modifier.height(130.dp))
        LabelBar(mt("by_version"), ctx.color)
        val versions = overall?.versions.orEmpty().sortedByDescending { it.totalDuration }
        val maxV = versions.maxOfOrNull { it.totalDuration }?.coerceAtLeast(1) ?: 1
        versions.take(20).forEachIndexed { i, v ->
            ShareRow(v.version, PlayTimeTracker.formatDuration(v.totalDuration) + " · " + mt("sessions_n", v.sessionCount), v.totalDuration.toFloat() / maxV, ctx.color, i)
        }
        if (versions.isEmpty()) CenterNote(mt("no_play_data"))
    }
}

@Composable
private fun ShareRow(title: String, sub: String, fraction: Float, color: Color, index: Int) {
    val p = LocalStudio.current
    Column(Modifier.fillMaxWidth().entrance(index).background(if (index % 2 == 0) p.editor else p.panel).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatText(title, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            FlatText(sub, color = p.muted, size = 11)
        }
        Spacer(Modifier.height(4.dp))
        ProgressLine(fraction, color, height = 6.dp)
    }
}

@Composable
private fun SessionsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pageSize = 50
    val data by produceState<Pair<Int, List<PlayTimeTracker.Session>>?>(null, page) {
        value = withContext(Dispatchers.IO) {
            runCatching { val t = vm.core.playTimeTracker(); t.sessionCount to t.getSessions(page * pageSize, pageSize) }.getOrNull()
        }
    }
    val count = data?.first ?: 0
    val sessions = data?.second.orEmpty()
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("sessions_title"), mt("sessions_n", count), ctx.color, Icons.Filled.Timer)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (data == null) CenterNote(I18n.t("studio.loading"))
            else if (sessions.isEmpty()) EmptyState(mt("no_play_data"), Icons.Filled.Timer, color = ctx.color)
            else LazyColumn(Modifier.fillMaxSize(), rememberLazyListState()) {
                itemsIndexed(sessions, key = { i, s -> "$page:$i:${s.start}" }) { i, s ->
                    ItemRow(
                        s.version.orEmpty(),
                        listOf(fmtDate(s.start), s.worldName.orEmpty(), s.server.orEmpty(), if (s.modIds.isNullOrEmpty()) "" else mt("mods_n", s.modIds.size)).filter { it.isNotBlank() }.joinToString(" · "),
                        ctx.color, i, icon = Icons.Filled.PlayArrow
                    ) {
                        FlatText(PlayTimeTracker.formatDuration(s.duration), color = Color.White, weight = FontWeight.SemiBold,
                            modifier = Modifier.background(Tiles.Navy).padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
        Toolbar {
            Block("", { page = (page - 1).coerceAtLeast(0) }, icon = Icons.AutoMirrored.Filled.ArrowBack, enabled = page > 0, height = 36.dp)
            FlatText(mt("page_n", page + 1, ((count + pageSize - 1) / pageSize).coerceAtLeast(1)), modifier = Modifier.padding(horizontal = 10.dp))
            Block("", { page++ }, icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = (page + 1) * pageSize < count, height = 36.dp)
        }
    }
}

@Composable
private fun BreakdownSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    var kind by rememberSaveable { mutableStateOf("mods") }
    val rows by produceState<List<PlayTimeTracker.BreakdownStat>?>(null, kind) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val t = vm.core.playTimeTracker()
                when (kind) {
                    "worlds" -> t.worldBreakdown
                    "servers" -> t.serverBreakdown
                    "instances" -> t.instanceBreakdown
                    else -> t.getModBreakdown(50)
                }
            }.getOrDefault(emptyList())
        }
    }
    val list = rows.orEmpty()
    val max = list.maxOfOrNull { it.totalDuration }?.coerceAtLeast(1) ?: 1
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("breakdown_title"), mt("items_n", list.size), ctx.color, PmclIcons.Statistics)
        Toolbar {
            Chips(listOf("mods" to mt("by_mod"), "worlds" to mt("by_world"), "servers" to mt("by_server"), "instances" to mt("by_instance")), kind, { kind = it }, ctx.color, scrollable = false)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows == null) CenterNote(I18n.t("studio.loading"))
            else if (list.isEmpty()) EmptyState(mt("no_play_data"), PmclIcons.Statistics, color = ctx.color)
            else LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { i, r -> "$kind:$i:${r.key}" }) { i, r ->
                    ShareRow(r.displayName?.ifBlank { null } ?: r.key.orEmpty(),
                        PlayTimeTracker.formatDuration(r.totalDuration) + " · " + mt("sessions_n", r.sessionCount),
                        r.totalDuration.toFloat() / max, ctx.color, i)
                }
            }
        }
    }
}

// ───────────────────────────── 插件 ─────────────────────────────

@Composable
internal fun PluginsModule(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val pm = remember { vm.core.plugins() }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val revision by produceState(0L) {
        while (isActive) {
            value = runCatching { pm.revision }.getOrDefault(0L)
            delay(1500)
        }
    }
    val entries by produceState(emptyList<PluginManager.PluginEntry>(), revision, tick) {
        value = withContext(Dispatchers.IO) { runCatching { pm.loadedPlugins.toList() }.getOrDefault(emptyList()) }
    }
    val errors by produceState(emptyList<String>(), revision, tick) {
        value = withContext(Dispatchers.IO) { runCatching { pm.lastDiscoveryErrors.toList() }.getOrDefault(emptyList()) }
    }
    var message by remember { mutableStateOf("") }
    fun work(block: () -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            message = r.exceptionOrNull()?.let { it.message ?: it.toString() } ?: ""
            tick++
        }
    }
    Column(Modifier.fillMaxSize()) {
        when (ctx.section) {
            "actions" -> PluginActions(ctx, pm, revision)
            "install" -> PluginInstall(ctx, pm) { tick++ }
            else -> {
                ModuleHero(mt("plugins_title"), mt("plugins_sub", entries.size, entries.count { it.state == PluginManager.PluginState.ENABLED }), ctx.color, PmclIcons.Plugins) {
                    HeroButton(mt("rescan"), Icons.Filled.Refresh, ctx.color, { work { pm.discoverAndLoadAll() } })
                    HeroButton(mt("install"), Icons.Filled.Add, ctx.color, { ctx.openSection("install") })
                }
                TileRow(72.dp) {
                    ColorTile(mt("tile_total"), entries.size.toString(), ctx.color, Modifier.weight(1f).fillMaxHeight(), PmclIcons.Plugins, index = 0)
                    ColorTile(mt("tile_enabled"), entries.count { it.state == PluginManager.PluginState.ENABLED }.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), index = 1)
                    ColorTile(mt("failed"), entries.count { it.state == PluginManager.PluginState.FAILED }.toString(),
                        if (entries.any { it.state == PluginManager.PluginState.FAILED }) Tiles.Red else Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), index = 2)
                    ColorTile(mt("actions"), runCatching { pm.customMenuActions.size }.getOrDefault(0).toString(), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.PlayArrow,
                        { ctx.openSection("actions") }, index = 3)
                }
                if (message.isNotBlank()) FlatText(message, color = Tiles.Red, size = 11, lines = 3, modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 14.dp, vertical = 6.dp))
                if (entries.isEmpty()) EmptyState(mt("plugins_empty"), PmclIcons.Plugins, mt("install"), ctx.color) { ctx.openSection("install") }
                else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(entries, key = { _, e -> e.info.id }) { i, e ->
                        val info = e.info
                        val on = e.state == PluginManager.PluginState.ENABLED
                        val failed = e.state == PluginManager.PluginState.FAILED
                        ItemRow(
                            "${info.name} ${info.version}",
                            listOf(info.author, info.description, info.id).filter { it.isNotBlank() }.joinToString(" · "),
                            if (failed) Tiles.Red else ctx.color, i, badge = info.name.take(1).uppercase(), dim = !on && !failed, height = 56.dp
                        ) {
                            FlatText(pluginStateLabel(e.state), color = Color.White, size = 11,
                                modifier = Modifier.background(if (on) Tiles.Navy else if (failed) Tiles.Red else Tiles.Slate).padding(horizontal = 8.dp, vertical = 6.dp))
                            RowButton(if (on) Icons.Filled.Block else Icons.Filled.Check, { work { if (on) pm.disablePlugin(info.id) else pm.enablePlugin(info.id) } },
                                label = if (on) mt("disable") else mt("enable"))
                            RowButton(Icons.Filled.Refresh, { work { pm.reloadPlugin(info.id) } })
                            RowButton(Icons.Filled.Delete, { ctx.confirm(mt("uninstall_title"), mt("uninstall_body", info.name)) { work { pm.uninstallPlugin(info.id) } } })
                        }
                    }
                }
                if (errors.isNotEmpty()) {
                    LabelBar(mt("load_errors", errors.size), Tiles.Red)
                    Column(Modifier.fillMaxWidth().height(120.dp).verticalScroll(rememberScrollState()).background(p.panel).padding(10.dp)) {
                        errors.forEach { FlatText(it, color = Tiles.Red, size = 11, lines = 4) }
                    }
                }
            }
        }
    }
}

private fun pluginStateLabel(s: PluginManager.PluginState?): String = mt("plugin_" + (s?.name ?: "LOADED").lowercase())

@Composable
private fun PluginActions(ctx: ModuleCtx, pm: PluginManager, revision: Long) {
    val p = LocalStudio.current
    val actions by produceState(emptyList<com.pmcl.plugin.api.PluginMenuAction>(), revision) {
        value = withContext(Dispatchers.IO) { runCatching { pm.customMenuActions.toList() }.getOrDefault(emptyList()) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = actions.filter { q.isEmpty() || it.title.contains(q, true) || it.description.contains(q, true) || it.keywords.any { k -> k.contains(q, true) } }
    ModuleHero(mt("plugin_actions_title"), mt("items_n", actions.size), ctx.color, Icons.Filled.PlayArrow)
    Toolbar { FlatField(query, { query = it }, mt("filter"), Modifier.width(260.dp), icon = Icons.Filled.Search) }
    if (message.isNotBlank()) FlatText(message, color = Tiles.Red, size = 11, modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 14.dp, vertical = 6.dp))
    if (shown.isEmpty()) EmptyState(mt("no_actions"), Icons.Filled.PlayArrow, color = ctx.color)
    else LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(shown, key = { _, a -> a.pluginId + ":" + a.id }) { i, a ->
            ItemRow(a.title, listOf(a.description, a.pluginId).filter { it.isNotBlank() }.joinToString(" · "), ctx.color, i, icon = Icons.Filled.PlayArrow,
                onClick = { message = runCatching { a.handler?.run() }.exceptionOrNull()?.message.orEmpty() }) {
                RowButton(Icons.Filled.PlayArrow, { message = runCatching { a.handler?.run() }.exceptionOrNull()?.message.orEmpty() }, ctx.color, label = mt("run"))
            }
        }
    }
}

@Composable
private fun PluginInstall(ctx: ModuleCtx, pm: PluginManager, onInstalled: () -> Unit) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    fun install(block: () -> com.pmcl.plugin.PluginInfo) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            busy = false
            ok = r.isSuccess
            message = r.fold({ mt("plugin_installed", it.name, it.version) }, { it.message ?: it.toString() })
            if (r.isSuccess) onInstalled()
        }
    }
    ModuleHero(mt("plugin_install_title"), mt("plugin_install_sub"), ctx.color, Icons.Filled.Add)
    TileRow(110.dp) {
        ColorTile(mt("from_file"), "", ctx.color, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.FileOpen,
            if (busy) null else ({ pickFile(mt("from_file"), setOf("jar", "zip")).firstOrNull()?.let { path -> install { pm.installFromPath(path) } } }), index = 0)
        ColorTile(mt("open_folder"), "", Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.FolderOpen,
            { ctx.vm.openDir(java.io.File(System.getProperty("user.home"), ".pmcl/plugins")) }, index = 1)
    }
    FormRow(mt("from_url"), 0) {
        InputBox(url, { url = it.trim() }, "https://…/plugin.jar", Modifier.weight(1f))
        Block(mt("install"), { install { pm.installFromUrl(url) } }, icon = Icons.Filled.Link, fill = ctx.color, enabled = !busy && url.startsWith("https://"), height = 30.dp)
    }
    if (busy) ProgressLine(null, ctx.color)
    if (message.isNotBlank()) FlatText(message, color = if (ok) p.ok else Tiles.Red, size = 12, lines = 4, modifier = Modifier.padding(20.dp))
    FlatText(mt("plugin_signature_hint"), color = p.muted, size = 11, lines = 4, modifier = Modifier.padding(horizontal = 20.dp))
}

// ───────────────────────────── 新闻 ─────────────────────────────

private fun htmlToText(html: String): String = html
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("(?i)</(p|div|h[1-6]|li|tr)>"), "\n\n")
    .replace(Regex("(?i)<li[^>]*>"), "• ")
    .replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), "")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

@Composable
internal fun NewsModule(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val items by vm.newsItems.collectAsState()
    val loading by vm.newsLoading.collectAsState()
    val article by vm.articleContent.collectAsState()
    val articleLoading by vm.articleLoading.collectAsState()
    val articleError by vm.articleError.collectAsState()
    LaunchedEffect(Unit) { if (items.isEmpty()) vm.refreshNews() }
    var selectedLink by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf("") }
    val categories = items.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }.distinct().take(8)
    val shown = items.filter { category.isEmpty() || it.category == category }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("news_title"), mt("items_n", items.size), ctx.color, PmclIcons.News) {
            HeroButton(mt("refresh"), Icons.Filled.Refresh, ctx.color, { vm.refreshNews() }, enabled = !loading)
        }
        if (categories.isNotEmpty()) Toolbar { Chips(listOf("" to mt("all")) + categories.map { it to it }, category, { category = it }, ctx.color, scrollable = false) }
        if (loading) ProgressLine(null, ctx.color)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box((if (selectedLink == null) Modifier.weight(1f) else Modifier.width(380.dp)).fillMaxHeight()) {
                if (shown.isEmpty()) CenterNote(if (loading) I18n.t("studio.loading") else mt("nothing_here"))
                else LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { i, n -> "$i:${n.link}" }) { i, n -> NewsRow(ctx, n, i, n.link == selectedLink) { selectedLink = n.link; vm.loadArticle(n.link) } }
                }
            }
            if (selectedLink != null) {
                val item = items.firstOrNull { it.link == selectedLink }
                Column(Modifier.weight(1.4f).fillMaxHeight().background(p.editor)) {
                    Toolbar {
                        Block("", { selectedLink = null; vm.clearArticle() }, icon = Icons.Filled.Close, height = 36.dp)
                        FlatText(article?.title ?: item?.title.orEmpty(), weight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Block(mt("open_page"), { vm.openNewsLink(article?.url ?: selectedLink.orEmpty()) }, icon = Icons.AutoMirrored.Filled.OpenInNew, fill = ctx.color, height = 36.dp)
                    }
                    val cover = rememberUrlImage(article?.coverImage ?: item?.imageUrl)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(2.2f))
                        when {
                            articleLoading -> CenterNote(I18n.t("studio.loading"))
                            articleError.isNotBlank() -> FlatText(articleError, color = Tiles.Red, lines = 4, modifier = Modifier.padding(20.dp))
                            else -> {
                                FlatText(article?.title ?: item?.title.orEmpty(), size = 22, weight = FontWeight.Light, lines = 3, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp))
                                FlatText(item?.pubDate.orEmpty(), color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 24.dp))
                                val body = remember(article?.bodyHtml) { htmlToText(article?.bodyHtml.orEmpty()) }
                                body.split("\n\n").filter { it.isNotBlank() }.forEachIndexed { i, para ->
                                    FlatText(para.trim(), size = 13, lines = 60, modifier = Modifier.fillMaxWidth().entrance(i).padding(horizontal = 24.dp, vertical = 6.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsRow(ctx: ModuleCtx, n: NewsItem, index: Int, selected: Boolean, onClick: () -> Unit) {
    val img = rememberUrlImage(n.imageUrl)
    ItemRow(n.title.orEmpty(), listOf(n.category.orEmpty(), n.pubDate.orEmpty().take(16), n.description.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
        ctx.color, index, icon = Icons.Filled.Image, image = img, selected = selected, height = 64.dp, onClick = onClick)
}