package com.pmcl.ui.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

internal fun contentColor(kind: ContentKind): Color = when (kind) {
    ContentKind.MODS -> Tiles.Teal
    ContentKind.RESOURCE_PACKS -> Tiles.Blue
    ContentKind.SHADERS -> Tiles.Sky
    ContentKind.WORLDS -> Tiles.Teal
    ContentKind.SCREENSHOTS -> Tiles.Plum
}

internal fun contentIcon(kind: ContentKind): ImageVector = when (kind) {
    ContentKind.MODS -> Icons.Filled.Extension
    ContentKind.RESOURCE_PACKS -> Icons.Filled.Palette
    ContentKind.SHADERS -> Icons.Filled.Tune
    ContentKind.WORLDS -> Icons.Filled.Public
    ContentKind.SCREENSHOTS -> Icons.Filled.Image
}

internal fun contentLabel(kind: ContentKind): String = when (kind) {
    ContentKind.MODS -> I18n.t("studio.mods")
    ContentKind.RESOURCE_PACKS -> I18n.t("studio.fact_resourcepacks")
    ContentKind.SHADERS -> I18n.t("studio.fact_shaders")
    ContentKind.WORLDS -> I18n.t("studio.fact_worlds")
    ContentKind.SCREENSHOTS -> I18n.t("studio.fact_screenshots")
}

private fun contentFolder(kind: ContentKind): String = when (kind) {
    ContentKind.MODS -> "mods"
    ContentKind.RESOURCE_PACKS -> "resourcepacks"
    ContentKind.SHADERS -> "shaderpacks"
    ContentKind.WORLDS -> "saves"
    ContentKind.SCREENSHOTS -> "screenshots"
}

@Composable
internal fun ContentEditor(
    vm: LauncherViewModel,
    tab: StudioTab.Content,
    onKind: (ContentKind) -> Unit,
    onReveal: (Path) -> Unit,
) {
    val p = LocalStudio.current
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val dir by produceState<Path?>(null, tab.target, reload) { value = withContext(Dispatchers.IO) { vm.gameDirOf(tab.target) } }
    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(40.dp).background(p.panelAlt), verticalAlignment = Alignment.CenterVertically) {
            ContentKind.entries.forEach { kind ->
                val on = kind == tab.kind
                val color = contentColor(kind)
                Row(
                    Modifier
                        .fillMaxHeight()
                        .background(if (on) color else Color.Transparent)
                        .clickable { onKind(kind) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(contentIcon(kind), contentDescription = null, tint = if (on) Color.White else color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    FlatText(contentLabel(kind), color = if (on) Color.White else p.text, weight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.weight(1f))
            FlatField(query, { query = it }, I18n.t("studio.filter"), Modifier.width(220.dp), Icons.Filled.Search)
            Block("", onClick = { reload++ }, icon = Icons.Filled.Refresh, height = 40.dp)
            Block(
                "",
                onClick = { dir?.let { onReveal(it.resolve(contentFolder(tab.kind))) } },
                icon = Icons.Filled.FolderOpen,
                enabled = dir != null,
                height = 40.dp
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val gameDir = dir
            if (gameDir == null) {
                CenterNote(I18n.t("studio.loading"))
            } else when (tab.kind) {
                ContentKind.MODS -> ModsContent(gameDir, query, reload, onReveal)
                ContentKind.RESOURCE_PACKS -> PacksContent(vm, gameDir, query, reload, shaders = false)
                ContentKind.SHADERS -> PacksContent(vm, gameDir, query, reload, shaders = true)
                ContentKind.WORLDS -> WorldsContent(gameDir, query, reload, onReveal)
                ContentKind.SCREENSHOTS -> ScreenshotsContent(gameDir, query, reload, onReveal)
            }
        }
    }
}

@Composable
internal fun CenterNote(text: String, color: Color = LocalStudio.current.muted) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FlatText(text, color = color, lines = 3)
    }
}

@Composable
private fun ModsContent(gameDir: Path, query: String, reload: Int, onReveal: (Path) -> Unit) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    var localReload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var onlyDisabled by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val metas = remember(gameDir) { mutableStateMapOf<String, ModMeta?>() }
    val mods by produceState(emptyList<ModFile>(), gameDir, reload, localReload) {
        value = withContext(Dispatchers.IO) { listMods(gameDir) }
    }
    LaunchedEffect(mods) {
        for (mod in mods) {
            val key = mod.name.removeSuffix(".disabled")
            if (metas.containsKey(key)) continue
            metas[key] = withContext(Dispatchers.IO) { readModMeta(mod.path) }
        }
    }
    val q = query.trim()
    val shown = mods.filter { mod ->
        val meta = metas[mod.name.removeSuffix(".disabled")]
        (!onlyDisabled || !mod.enabled) &&
            (q.isEmpty() || mod.name.contains(q, true) || meta?.name?.contains(q, true) == true || meta?.id?.contains(q, true) == true)
    }
    fun toggle(mod: ModFile) {
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) { toggleMod(mod) }
                null
            } catch (e: Throwable) {
                I18n.t("studio.mod_toggle_failed", e.message ?: e.javaClass.simpleName)
            }
            localReload++
        }
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val metaReady = mods.all { metas.containsKey(it.name.removeSuffix(".disabled")) }
            val issues = remember(mods, metaReady) { if (metaReady) inspectMods(mods, metas) else emptyList() }
            val errors = issues.count { it.error }
            Row(Modifier.fillMaxWidth().height(30.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
                FlatText(
                    I18n.t("studio.mods_summary", mods.count { it.enabled }.toString(), mods.size.toString()) +
                        if (!metaReady) " · " + I18n.t("studio.reading_meta", metas.size.toString(), mods.size.toString()) else "",
                    color = p.muted,
                    size = 11,
                    modifier = Modifier.padding(horizontal = 12.dp).weight(1f)
                )
                Block(
                    when {
                        !metaReady -> I18n.t("studio.inspect")
                        issues.isEmpty() -> I18n.t("studio.inspect_ok")
                        else -> I18n.t("studio.inspect_n", issues.size.toString())
                    },
                    onClick = { showIssues = !showIssues },
                    fill = when {
                        !metaReady -> null
                        errors > 0 -> Tiles.Red
                        issues.isNotEmpty() -> Tiles.Orange
                        else -> Tiles.Forest
                    },
                    enabled = metaReady,
                    selected = showIssues,
                    height = 30.dp
                )
                Block(
                    if (copied) I18n.t("studio.copied") else I18n.t("studio.copy_list"),
                    onClick = {
                        clipboard.setText(AnnotatedString(modListMarkdown(mods, metas)))
                        copied = true
                    },
                    enabled = metaReady,
                    height = 30.dp
                )
                Block(I18n.t("studio.only_disabled"), onClick = { onlyDisabled = !onlyDisabled }, selected = onlyDisabled, height = 30.dp)
                Block(
                    I18n.t("studio.mods_enable_all"),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                mods.filter { !it.enabled }.forEach { runCatching { setModEnabled(it, true) } }
                            }
                            localReload++
                        }
                    },
                    enabled = mods.any { !it.enabled },
                    height = 30.dp
                )
                Block(
                    I18n.t("studio.mods_disable_all"),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                mods.filter { it.enabled }.forEach { runCatching { setModEnabled(it, false) } }
                            }
                            localReload++
                        }
                    },
                    enabled = mods.any { it.enabled },
                    height = 30.dp
                )
                Block(
                    I18n.t("studio.mods_delete_disabled"),
                    onClick = {
                        scope.launch {
                            message = try {
                                val n = withContext(Dispatchers.IO) {
                                    val dead = mods.filter { !it.enabled }
                                    dead.forEach { deleteMod(it) }
                                    dead.size
                                }
                                localReload++
                                I18n.t("studio.mods_deleted", n.toString())
                            } catch (e: Throwable) {
                                I18n.t("studio.mod_toggle_failed", e.message ?: e.javaClass.simpleName)
                            }
                        }
                    },
                    fill = if (mods.any { !it.enabled }) Tiles.Red else null,
                    enabled = mods.any { !it.enabled },
                    height = 30.dp
                )
            }
            LaunchedEffect(copied) {
                if (copied) {
                    kotlinx.coroutines.delay(1600)
                    copied = false
                }
            }
            AnimatedVisibility(showIssues && metaReady, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    if (issues.isEmpty()) {
                        FlatText(I18n.t("studio.inspect_none"), color = Color.White, modifier = Modifier.fillMaxWidth().background(Tiles.Forest).padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    issues.forEachIndexed { i, issue ->
                        Row(
                            Modifier.fillMaxWidth().entrance(i).background(if (i % 2 == 0) p.panelAlt else p.panel),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(4.dp).height(30.dp).background(if (issue.error) Tiles.Red else Tiles.Orange))
                            FlatText(issue.text, lines = 2, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            message?.let { FlatText(it, color = Color.White, modifier = Modifier.fillMaxWidth().background(Tiles.Red).padding(8.dp)) }
            if (shown.isEmpty()) {
                CenterNote(I18n.t("studio.mods_empty"))
                return@Column
            }
            // 启用/停用会改名 .jar ↔ .jar.disabled，key 去掉后缀才不会被当成新行重建
            val rowKeys = remember(shown) {
                val seen = HashMap<String, Int>()
                shown.map { m ->
                    val base = m.path.toString().removeSuffix(".disabled")
                    val n = (seen[base] ?: 0) + 1
                    seen[base] = n
                    if (n == 1) base else "$base#$n"
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { i, _ -> rowKeys[i] }) { index, mod ->
                    val key = mod.name.removeSuffix(".disabled")
                    val meta = metas[key]
                    val selected = picked == key
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .entrance(index)
                            .background(if (selected) p.selected else if (index % 2 == 0) p.editor else p.panel)
                            .clickable { picked = key },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(if (mod.enabled) Tiles.Teal else Color.Transparent))
                        Box(Modifier.size(44.dp).background(if (mod.enabled) Tiles.Teal.copy(alpha = 0.16f) else p.panelAlt), contentAlignment = Alignment.Center) {
                            val icon = meta?.icon
                            if (icon != null) {
                                Image(icon, contentDescription = null, modifier = Modifier.size(30.dp), filterQuality = FilterQuality.None)
                            } else {
                                Icon(Icons.Filled.Extension, contentDescription = null, tint = if (mod.enabled) Tiles.Teal else p.muted, modifier = Modifier.size(18.dp))
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            FlatText(meta?.name?.ifBlank { null } ?: key, color = if (mod.enabled) p.text else p.muted, weight = FontWeight.Medium)
                            FlatText(
                                listOfNotNull(meta?.loader, meta?.version?.ifBlank { null }, formatSize(mod.size)).joinToString(" · "),
                                color = p.muted,
                                size = 11
                            )
                        }
                        Block(
                            if (mod.enabled) I18n.t("studio.enabled") else I18n.t("studio.disabled"),
                            onClick = { toggle(mod) },
                            fill = if (mod.enabled) Tiles.Teal else p.strip,
                            modifier = Modifier.width(80.dp),
                            height = 44.dp
                        )
                    }
                }
            }
        }
        val mod = mods.firstOrNull { it.name.removeSuffix(".disabled") == picked }
        var lastPicked by remember { mutableStateOf<ModFile?>(null) }
        LaunchedEffect(mod) { if (mod != null) lastPicked = mod }
        AnimatedVisibility(mod != null, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
            val shownMod = mod ?: lastPicked
            if (shownMod != null) {
                ModInspector(
                    shownMod,
                    metas[shownMod.name.removeSuffix(".disabled")],
                    onToggle = { toggle(shownMod) },
                    onReveal = { onReveal(shownMod.path.parent) },
                    onClose = { picked = null }
                )
            }
        }
    }
}

@Composable
private fun ModInspector(mod: ModFile, meta: ModMeta?, onToggle: () -> Unit, onReveal: () -> Unit, onClose: () -> Unit) {
    val p = LocalStudio.current
    Column(Modifier.width(340.dp).fillMaxHeight().background(p.panel)) {
        Row(Modifier.fillMaxWidth().height(96.dp).background(Tiles.Teal), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).background(Tiles.Teal.lift(0.12f)), contentAlignment = Alignment.Center) {
                val icon = meta?.icon
                if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(64.dp), filterQuality = FilterQuality.None)
                else Icon(Icons.Filled.Extension, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                FlatText(meta?.name?.ifBlank { null } ?: mod.name, color = Color.White, size = 15, weight = FontWeight.SemiBold, lines = 2)
                FlatText(meta?.version.orEmpty(), color = Color.White.copy(alpha = 0.85f), size = 11)
            }
            Block("", onClick = onClose, icon = Icons.Filled.Close, fill = Tiles.Teal, height = 96.dp)
        }
        Row(Modifier.fillMaxWidth().height(36.dp)) {
            Block(
                if (mod.enabled) I18n.t("studio.disable") else I18n.t("studio.enable"),
                onClick = onToggle,
                fill = if (mod.enabled) Tiles.Graphite else Tiles.Blue,
                modifier = Modifier.weight(1f),
                height = 36.dp
            )
            Block(I18n.t("studio.reveal_dir"), onClick = onReveal, icon = Icons.Filled.FolderOpen, fill = Tiles.Gold, modifier = Modifier.weight(1f), height = 36.dp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (meta == null) {
                FlatText(I18n.t("studio.mod_no_meta"), color = p.muted, lines = 3, modifier = Modifier.padding(12.dp))
            } else {
                MetaRow(I18n.t("studio.mod_id"), meta.id, 0)
                MetaRow(I18n.t("studio.fact_loader"), meta.loader, 1)
                MetaRow(I18n.t("studio.mod_authors"), meta.authors.joinToString(", ").ifBlank { "—" }, 2)
                MetaRow(I18n.t("studio.mod_depends"), meta.depends.joinToString(", ").ifBlank { "—" }, 3)
                if (meta.homepage.isNotBlank()) MetaRow(I18n.t("studio.mod_homepage"), meta.homepage, 4)
                if (meta.description.isNotBlank()) {
                    SelectionContainer {
                        FlatText(meta.description, lines = 12, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            MetaRow(I18n.t("studio.col_name"), mod.name, 5)
            MetaRow(I18n.t("studio.col_size"), formatSize(mod.size), 6)
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String, index: Int) {
    val p = LocalStudio.current
    Row(Modifier.fillMaxWidth().background(if (index % 2 == 0) p.panel else p.panelAlt).padding(horizontal = 12.dp, vertical = 7.dp)) {
        FlatText(label, color = p.muted, size = 11, modifier = Modifier.width(76.dp))
        SelectionContainer(Modifier.weight(1f)) { FlatText(value, lines = 4) }
    }
}

@Composable
private fun PacksContent(vm: LauncherViewModel, gameDir: Path, query: String, reload: Int, shaders: Boolean) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    val gameRunning by vm.gameRunning.collectAsState()
    var localReload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val color = if (shaders) Tiles.Sky else Tiles.Blue
    val packs by produceState(emptyList<PackFile>(), gameDir, reload, localReload, shaders) {
        value = withContext(Dispatchers.IO) { listPacks(gameDir, if (shaders) "shaderpacks" else "resourcepacks") }
    }
    val enabled by produceState(emptyList<String>(), gameDir, reload, localReload, shaders) {
        value = withContext(Dispatchers.IO) {
            if (shaders) listOfNotNull(activeShader(gameDir)) else readEnabledPacks(gameDir)
        }
    }
    val canEdit by produceState(false, gameDir, reload, shaders) {
        value = withContext(Dispatchers.IO) {
            if (shaders) shaderConfig(gameDir) != null
            else java.nio.file.Files.isRegularFile(gameDir.resolve("options.txt"))
        }
    }
    val icons = remember(gameDir, shaders) { mutableStateMapOf<String, ImageBitmap?>() }
    val descriptions = remember(gameDir, shaders) { mutableStateMapOf<String, String>() }
    LaunchedEffect(packs) {
        if (shaders) return@LaunchedEffect
        for (pack in packs) {
            if (icons.containsKey(pack.name)) continue
            val (icon, desc) = withContext(Dispatchers.IO) { packIcon(pack) to packDescription(pack) }
            icons[pack.name] = icon
            descriptions[pack.name] = desc
        }
    }
    val q = query.trim()
    val shown = packs.filter { q.isEmpty() || it.name.contains(q, true) }
    fun isOn(pack: PackFile) = if (shaders) enabled.contains(pack.name) else enabled.contains("file/" + pack.name)
    fun toggle(pack: PackFile) {
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    if (shaders) setActiveShader(gameDir, if (isOn(pack)) null else pack.name)
                    else setPackEnabled(gameDir, pack.name, !isOn(pack))
                }
                null
            } catch (e: Throwable) {
                I18n.t("studio.pack_failed", e.message ?: e.javaClass.simpleName)
            }
            localReload++
        }
    }
    Column(Modifier.fillMaxSize()) {
        val note = when {
            !canEdit && shaders -> I18n.t("studio.shader_no_config")
            !canEdit -> I18n.t("studio.pack_no_options")
            gameRunning -> I18n.t("studio.pack_running")
            else -> I18n.t("studio.pack_note")
        }
        FlatText(
            note,
            color = if (!canEdit || gameRunning) Color.White else p.muted,
            size = 11,
            lines = 2,
            modifier = Modifier.fillMaxWidth().background(if (!canEdit || gameRunning) Tiles.Orange else p.panel).padding(horizontal = 12.dp, vertical = 7.dp)
        )
        message?.let { FlatText(it, color = Color.White, modifier = Modifier.fillMaxWidth().background(Tiles.Red).padding(8.dp)) }
        if (shown.isEmpty()) {
            CenterNote(I18n.t("studio.packs_empty"))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown, key = { _, pk -> pk.path.toString() }) { index, pack ->
                val on = isOn(pack)
                Row(
                    Modifier.fillMaxWidth().height(52.dp).entrance(index).background(if (index % 2 == 0) p.editor else p.panel),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(if (on) color else Color.Transparent))
                    Box(Modifier.size(52.dp).background(if (on) color.copy(alpha = 0.16f) else p.panelAlt), contentAlignment = Alignment.Center) {
                        val icon = icons[pack.name]
                        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(40.dp), filterQuality = FilterQuality.None)
                        else Icon(if (shaders) Icons.Filled.Tune else Icons.Filled.Palette, contentDescription = null, tint = if (on) color else p.muted, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        FlatText(pack.name.removeSuffix(".zip"), weight = FontWeight.Medium)
                        val desc = descriptions[pack.name].orEmpty()
                        FlatText(
                            desc.ifBlank { if (pack.directory) I18n.t("studio.pack_folder") else formatSize(pack.size) },
                            color = p.muted,
                            size = 11
                        )
                    }
                    Block(
                        if (on) I18n.t("studio.enabled") else I18n.t("studio.disabled"),
                        onClick = { toggle(pack) },
                        fill = if (on) color else p.strip,
                        enabled = canEdit,
                        modifier = Modifier.width(88.dp),
                        height = 52.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun WorldsContent(gameDir: Path, query: String, reload: Int, onReveal: (Path) -> Unit) {
    val p = LocalStudio.current
    val worlds by produceState<List<WorldInfo>?>(null, gameDir, reload) {
        value = withContext(Dispatchers.IO) { listWorlds(gameDir) }
    }
    val sizes = remember(gameDir, reload) { mutableStateMapOf<Path, Long>() }
    LaunchedEffect(worlds) {
        for (w in worlds.orEmpty()) {
            if (sizes.containsKey(w.dir)) continue
            sizes[w.dir] = withContext(Dispatchers.IO) { dirSize(w.dir) }
        }
    }
    val list = worlds
    if (list == null) {
        CenterNote(I18n.t("studio.loading"))
        return
    }
    val q = query.trim()
    val shown = list.filter { q.isEmpty() || it.name.contains(q, true) }
    if (shown.isEmpty()) {
        CenterNote(I18n.t("studio.worlds_empty"))
        return
    }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    val colors = listOf(Tiles.Blue, Tiles.Navy, Tiles.Teal, Tiles.Plum, Tiles.Slate, Tiles.Graphite)
    LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize()) {
        itemsIndexed(shown, key = { _, w -> w.dir.toString() }) { index, world ->
            val color = colors[index % colors.size]
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            val icon by produceState<ImageBitmap?>(null, world.icon) {
                value = world.icon?.let { withContext(Dispatchers.IO) { ThumbCache.load(it, 128) } }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .entrance(index)
                    .cardMotion(source, true, 1.02f)
                    .background(if (hovered) color.lift(0.08f) else color)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null) { onReveal(world.dir) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(104.dp).background(color.lift(0.1f)), contentAlignment = Alignment.Center) {
                    val bitmap = icon
                    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Filled.Public, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FlatText(world.name, color = Color.White, size = 14, weight = FontWeight.SemiBold)
                    FlatText(
                        if (world.modified > 0) fmt.format(Date(world.modified)) else "—",
                        color = Color.White.copy(alpha = 0.85f),
                        size = 11
                    )
                    FlatText(
                        listOfNotNull(
                            sizes[world.dir]?.let { formatSize(it) },
                            if (world.datapacks > 0) I18n.t("studio.datapacks_n", world.datapacks.toString()) else null
                        ).joinToString(" · ").ifBlank { "…" },
                        color = Color.White.copy(alpha = 0.85f),
                        size = 11
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenshotsContent(gameDir: Path, query: String, reload: Int, onReveal: (Path) -> Unit) {
    val p = LocalStudio.current
    var preview by remember { mutableStateOf<Path?>(null) }
    val shots by produceState<List<Path>?>(null, gameDir, reload) {
        value = withContext(Dispatchers.IO) { listScreenshots(gameDir) }
    }
    val list = shots
    if (list == null) {
        CenterNote(I18n.t("studio.loading"))
        return
    }
    val q = query.trim()
    val shown = list.filter { q.isEmpty() || it.fileName.toString().contains(q, true) }
    if (shown.isEmpty()) {
        CenterNote(I18n.t("studio.shots_empty"))
        return
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(220.dp), Modifier.fillMaxSize().background(Color.Black)) {
            itemsIndexed(shown, key = { _, s -> s.toString() }) { index, shot ->
                val source = remember { MutableInteractionSource() }
                val thumb by produceState<ImageBitmap?>(null, shot) {
                    value = withContext(Dispatchers.IO) { ThumbCache.load(shot, 360) }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .entrance(index)
                        .cardMotion(source, true, 1.04f)
                        .background(p.panelAlt)
                        .hoverable(source)
                        .clickable(interactionSource = source, indication = null) { preview = shot },
                    contentAlignment = Alignment.BottomStart
                ) {
                    val bitmap = thumb
                    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    FlatText(
                        shot.fileName.toString().substringBeforeLast('.'),
                        color = Color.White,
                        size = 10,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        val current = preview
        if (current != null) {
            val full by produceState<ImageBitmap?>(null, current) {
                value = withContext(Dispatchers.IO) { ThumbCache.load(current, 1920) }
            }
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Row(Modifier.fillMaxWidth().height(36.dp).background(Tiles.Plum), verticalAlignment = Alignment.CenterVertically) {
                    FlatText(current.fileName.toString(), color = Color.White, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp).weight(1f))
                    val index = shown.indexOf(current)
                    Block("‹", onClick = { shown.getOrNull(index - 1)?.let { preview = it } }, fill = Tiles.Plum, enabled = index > 0, height = 36.dp)
                    Block("›", onClick = { shown.getOrNull(index + 1)?.let { preview = it } }, fill = Tiles.Plum, enabled = index < shown.size - 1, height = 36.dp)
                    Block(I18n.t("studio.reveal_dir"), onClick = { onReveal(current.parent) }, icon = Icons.Filled.FolderOpen, fill = Tiles.Gold, height = 36.dp)
                    Block("", onClick = { preview = null }, icon = Icons.Filled.Close, fill = Tiles.Red, height = 36.dp)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val bitmap = full
                    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else FlatText(I18n.t("studio.loading"), color = Color.White)
                }
            }
        }
    }
}
