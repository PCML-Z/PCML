package com.pmcl.ui.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.graphicsLayer
import java.nio.file.Paths
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CallToAction
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.LauncherTheme
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.ThemeState
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.clearGameLogs
import com.pmcl.ui.viewmodel.launch
import com.pmcl.ui.viewmodel.openGameLogFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val isMac = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")
private val modKey = if (isMac) "⌘" else "Ctrl+"
private const val MAX_RECENT = 12

/** IDEA 式工作站窗口：无边框，标题栏、命令带、活动栏、面板、编辑区、工具窗口全部用色块直接拼接。 */
@Composable
fun StudioWindow(vm: LauncherViewModel, themeState: ThemeState, windowId: Int = 1, onClose: () -> Unit) {
    val cascade = remember { vm.studioWindows.value.indexOf(windowId).coerceAtLeast(0) % 8 }
    val state = rememberWindowState(width = 1380.dp, height = 860.dp, position = studioWindowPosition(cascade))
    val scope = rememberCoroutineScope()
    val windows by vm.studioWindows.collectAsState()
    val versions by vm.localVersionInfos.collectAsState()
    val instances by vm.instances.collectAsState()
    val selectedVersion by vm.selectedVersion.collectAsState()
    val focus by vm.studioFocus.collectAsState()

    var target by remember { mutableStateOf<StudioTarget?>(null) }
    val stored = remember { StudioStore.load() }
    // 会话恢复：上次退出时打开的标签（固定的排前面）和激活标签
    val tabs = remember {
        mutableStateListOf<StudioTab>().apply {
            val restored = stored.openTabs.mapNotNull { decodeTab(it) }.distinctBy { it.key }
            val pinnedFirst = restored.sortedByDescending { it.key in stored.pinnedTabs }
            addAll(pinnedFirst)
        }
    }
    var activeKey by remember {
        mutableStateOf(stored.activeTab?.takeIf { key -> tabs.any { it.key == key } })
    }
    val pinned = remember { mutableStateListOf<String>().apply { addAll(stored.pinnedTabs) } }
    val buffers = remember { mutableStateMapOf<String, FileBuffer>() }
    var leftTool by remember { mutableStateOf(stored.leftTool?.let { n -> LeftTool.entries.firstOrNull { it.name == n } }) }
    var bottomTool by remember { mutableStateOf(stored.bottomTool?.let { n -> BottomTool.entries.firstOrNull { it.name == n } }) }
    var rightOpen by remember { mutableStateOf(stored.rightOpen) }
    var leftWidth by remember { mutableStateOf(clampDp(stored.leftWidth.dp, 200.dp, 560.dp)) }
    var rightWidth by remember { mutableStateOf(clampDp(stored.rightWidth.dp, 220.dp, 480.dp)) }
    var bottomHeight by remember { mutableStateOf(clampDp(stored.bottomHeight.dp, 120.dp, 560.dp)) }
    var ribbonTab by remember { mutableIntStateOf(0) }
    var ribbonOpen by remember { mutableStateOf(stored.ribbonOpen) }
    var dark by remember { mutableStateOf(stored.dark ?: themeState.useDark) }
    var intro by remember { mutableStateOf(true) }
    val favorites = StudioShared.favorites
    val recent = StudioShared.recent
    val javaProjects = StudioShared.javaProjects
    val recentDocs = StudioShared.recentDocs
    var reload by remember { mutableIntStateOf(0) }
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    val terminal = remember { StudioTerminalState(vm.core, scope) }
    val tabStates = rememberSaveableStateHolder()
    var paletteOpen by remember { mutableStateOf(false) }
    var gameDir by remember { mutableStateOf<Path?>(null) }

    LaunchedEffect(versions, instances, selectedVersion) {
        if (target == null) {
            val (v, i) = targetsOf(versions, instances)
            target = v.firstOrNull { it.id == selectedVersion } ?: v.firstOrNull() ?: i.firstOrNull()
        }
    }
    LaunchedEffect(target, reload) {
        val t = target
        gameDir = if (t == null) null else withContext(Dispatchers.IO) { vm.gameDirOf(t) }
    }
    val settings = StudioSettings(
        favorites = favorites.toList(),
        recent = recent.toList(),
        javaProjects = javaProjects.toList(),
        recentDocs = recentDocs.toList(),
        leftWidth = leftWidth.value,
        rightWidth = rightWidth.value,
        bottomHeight = bottomHeight.value,
        dark = dark.takeIf { it != themeState.useDark },
        ribbonOpen = ribbonOpen,
        leftTool = leftTool?.name,
        bottomTool = bottomTool?.name,
        rightOpen = rightOpen,
        pinnedTabs = pinned.toList(),
        openTabs = tabs.mapNotNull { encodeTab(it) },
        activeTab = activeKey,
    )
    LaunchedEffect(settings) {
        if (settings == stored) return@LaunchedEffect
        delay(600)
        withContext(Dispatchers.IO) { StudioStore.save(settings) }
    }

    fun openTab(tab: StudioTab) {
        val index = tabs.indexOfFirst { it.key == tab.key }
        if (index >= 0) tabs[index] = tab else tabs.add(tab)
        activeKey = tab.key
    }

    fun pick(t: StudioTarget) {
        target = t
        openTab(StudioTab.Overview(t))
    }

    fun rememberDoc(path: Path) {
        val key = path.toAbsolutePath().normalize().toString()
        recentDocs.remove(key)
        recentDocs.add(0, key)
        while (recentDocs.size > 24) recentDocs.removeAt(recentDocs.lastIndex)
    }

    fun openDocument(path: Path? = null) {
        val file = path ?: pickDocument() ?: return
        if (!Files.isRegularFile(file)) return
        rememberDoc(file)
        openTab(StudioTab.Document(file.toAbsolutePath().normalize().toString()))
        leftTool = LeftTool.DOCS
    }

    fun newMindMapDoc() {
        val file = pickSaveMindMap() ?: return
        try {
            Files.createDirectories(file.parent)
            writeAtomically(file, serializePmclMind(newMindMap()))
            openDocument(file)
        } catch (_: Throwable) {
        }
    }

    fun openFile(root: Path, file: Path, readOnly: Boolean = false, line: Int? = null) {
        if (Files.isDirectory(file)) return
        if (isDocumentFile(file)) {
            openDocument(file)
            return
        }
        val tab = StudioTab.FileTab(root, file, readOnly)
        val buffer = buffers.getOrPut(tab.key) { FileBuffer() }
        if (line != null) buffer.jumpLine = line
        openTab(tab)
        val entry = RecentFile(root.toString(), file.toString())
        recent.remove(entry)
        recent.add(0, entry)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
    }

    fun openRecent(entry: RecentFile) {
        val root = runCatching { Paths.get(entry.root) }.getOrNull() ?: return
        val file = runCatching { Paths.get(entry.file) }.getOrNull() ?: return
        if (!Files.isRegularFile(file)) {
            recent.remove(entry)
            return
        }
        openFile(root, file)
    }

    fun toggleFavorite(t: StudioTarget) {
        val key = favoriteKey(t)
        if (!favorites.remove(key)) favorites.add(key)
    }

    fun openStorage() {
        target?.let { openTab(StudioTab.Storage(it)) }
    }

    fun openDiagnose() {
        target?.let { openTab(StudioTab.Diagnose(it)) }
    }

    fun openCompare() {
        target?.let { openTab(StudioTab.Compare(it)) }
    }

    fun openConfigs() {
        target?.let { openTab(StudioTab.Configs(it)) }
    }

    fun openLiveLog() {
        target?.let { openTab(StudioTab.LiveLog(it)) }
    }

    fun openCrash() {
        target?.let { openTab(StudioTab.Crash(it)) }
    }

    fun openModUpdates() {
        target?.let { openTab(StudioTab.ModUpdates(it)) }
    }

    fun openOptions() {
        target?.let { openTab(StudioTab.Options(it)) }
    }

    fun openExport() {
        target?.let { openTab(StudioTab.Export(it)) }
    }

    /** 固定标签：固定的不能被关闭，重启后也最先恢复。 */
    fun togglePin(tab: StudioTab) {
        val key = tab.key
        if (!pinned.remove(key)) {
            pinned.add(key)
            val index = tabs.indexOfFirst { it.key == key }
            val firstUnpinned = tabs.indexOfFirst { it.key !in pinned }
            if (index >= 0 && firstUnpinned >= 0 && index > firstUnpinned) {
                tabs.add(firstUnpinned, tabs.removeAt(index))
            }
        }
    }

    fun rememberJavaProject(path: Path) {
        val key = path.toAbsolutePath().normalize().toString()
        javaProjects.remove(key)
        javaProjects.add(0, key)
        while (javaProjects.size > 24) javaProjects.removeAt(javaProjects.lastIndex)
    }

    fun openJavaProject(path: Path? = null) {
        val root = path ?: pickDirectory(I18n.t("studio.code_open")) ?: return
        if (!Files.isDirectory(root)) return
        rememberJavaProject(root)
        openTab(StudioTab.CodeProject(root.toAbsolutePath().normalize().toString()))
        leftTool = LeftTool.CODE
    }

    fun openContent(kind: ContentKind) {
        target?.let { openTab(StudioTab.Content(it, kind)) }
    }

    fun openSettings(category: String? = null) {
        val current = tabs.firstOrNull { it is StudioTab.Settings } as? StudioTab.Settings
        openTab(StudioTab.Settings(category ?: current?.category))
    }

    fun openTerminal() {
        openTab(StudioTab.Terminal)
        if (bottomTool == BottomTool.TERMINAL) bottomTool = null
    }

    var surveyPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SURVEY_PROMPT_DELAY_MS)
        surveyPrompt = withContext(Dispatchers.IO) { vm.core.launcherVersion() }
            .let { SurveyHub.shouldPrompt(it, System.currentTimeMillis()) && SurveyHub.claimPrompt() }
    }

    fun openSurvey(rating: Int = 0) {
        if (rating > 0) SurveyHub.draft.overall = rating
        surveyPrompt = false
        openTab(StudioTab.Survey)
    }

    fun openModule(module: StudioModule, section: String? = null) {
        if (module.id == "settings" && (section == null || section in nativeSettingSections)) {
            openSettings(section?.let { nativeSettingSections[it] })
            return
        }
        openTab(StudioTab.Module(module.id, section))
    }

    fun reveal(path: Path) {
        var p: Path? = path
        while (p != null && !Files.isDirectory(p)) p = p.parent
        if (p != null) vm.openDir(p.toFile())
    }

    fun diagnoseAction(action: String) {
        when (action) {
            "java" -> openSettings("java")
            "json" -> target?.let { openTab(StudioTab.VersionJson(it.versionId)) }
            "reveal" -> gameDir?.let { reveal(it) }
            "configs" -> openConfigs()
            "problems" -> bottomTool = BottomTool.PROBLEMS
            "mods" -> openContent(ContentKind.MODS)
            "storage" -> openStorage()
        }
    }

    fun runTarget(t: StudioTarget) {
        when (t.kind) {
            TargetKind.INSTANCE -> vm.launchInstance(t.id)
            TargetKind.VERSION -> {
                vm.selectVersion(t.id)
                vm.launch(forcedVersionId = t.id)
            }
        }
        if (bottomTool == null) bottomTool = BottomTool.RUN
    }

    fun refresh() {
        vm.refreshLocalVersions()
        vm.loadInstances()
        reload++
    }

    fun closeTab(tab: StudioTab) {
        if (tab.key in pinned) return
        val doClose = {
            val index = tabs.indexOfFirst { it.key == tab.key }
            if (index >= 0) tabs.removeAt(index)
            buffers.remove(tab.key)
            tabStates.removeState(tab.key)
            if (activeKey == tab.key) activeKey = tabs.getOrNull((index - 1).coerceAtLeast(0))?.key
        }
        if (buffers[tab.key]?.dirty == true) discard = doClose else doClose()
    }

    fun requestClose() {
        if (buffers.values.any { it.dirty }) discard = onClose else onClose()
    }

    fun activeTab(): StudioTab? = tabs.firstOrNull { it.key == activeKey }

    fun saveActive() {
        val tab = activeTab() as? StudioTab.FileTab ?: return
        val buffer = buffers[tab.key] ?: return
        scope.launch { saveBuffer(tab, buffer) }
    }

    fun toggleLeft(tool: LeftTool) {
        leftTool = if (leftTool == tool) null else tool
    }

    fun toggleBottom(tool: BottomTool) {
        bottomTool = if (bottomTool == tool) null else tool
    }

    fun resetLayout() {
        leftWidth = 290.dp; rightWidth = 290.dp; bottomHeight = 240.dp; dark = themeState.useDark
        leftTool = LeftTool.PROJECT; rightOpen = true; bottomTool = BottomTool.RUN; ribbonOpen = true
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val mod = if (isMac) e.isMetaPressed else e.isCtrlPressed
        if (!mod) {
            if (e.key == Key.Escape && paletteOpen) { paletteOpen = false; return true }
            return false
        }
        when (e.key) {
            Key.K, Key.P -> paletteOpen = !paletteOpen
            Key.S -> saveActive()
            Key.R -> if (e.isShiftPressed) refresh() else target?.let { runTarget(it) }
            Key.W -> activeTab()?.let { closeTab(it) }
            Key.F -> if (e.isShiftPressed) {
                bottomTool = BottomTool.SEARCH
            } else {
                val tab = activeTab() as? StudioTab.FileTab ?: return false
                buffers[tab.key]?.findOpen = true
            }
            Key.B -> leftTool = if (leftTool == null) LeftTool.PROJECT else null
            Key.J -> bottomTool = if (bottomTool == null) BottomTool.RUN else null
            Key.E -> rightOpen = !rightOpen
            Key.N -> if (e.isShiftPressed) vm.newStudioWindow() else return false
            else -> return false
        }
        return true
    }

    val scheme = if (themeState.dynamicColor || themeState.customAccentColor != -1) themeState.dynamicColorScheme else null
    Window(
        onCloseRequest = { requestClose() },
        title = "PMCL Studio" + (if (windows.size > 1) " · " + windowLabel(windowId) else "") + (target?.let { " — " + it.name } ?: ""),
        state = state,
        undecorated = true,
        resizable = true,
        onPreviewKeyEvent = { onKey(it) }
    ) {
        val frame = remember(window) { WindowFrame(window) }
        DisposableEffect(window) {
            window.minimumSize = java.awt.Dimension(MIN_WIDTH, MIN_HEIGHT)
            onDispose { }
        }
        LaunchedEffect(focus) {
            if (focus.first == windowId && focus.second > 0) {
                if (state.isMinimized) state.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }

        LauncherTheme(
            useDarkTheme = dark,
            dynamicColorScheme = scheme,
            uiScale = themeState.uiScale,
            launcherFont = themeState.launcherFont,
            themePreset = themeState.themePreset,
            colorMode = themeState.colorMode,
            customThemePack = themeState.customThemePack
        ) {
            CompositionLocalProvider(LocalThemeState provides themeState) {
                StudioColors(dark) {
                    val p = LocalStudio.current
                    val active = activeTab()
                    val drag = dragArea(frame) {}
                    Box(Modifier.fillMaxSize().background(p.window)) {
                        Column(Modifier.fillMaxSize()) {
                            // 标题栏
                            ChromeRow(40.dp, p.accent) {
                                Box(Modifier.fillMaxHeight().then(drag).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                    FlatText("PMCL Studio", color = Color.White, weight = FontWeight.SemiBold, size = 13)
                                }
                                if (windows.size > 1) {
                                    StatusSegment(windowLabel(windowId), p.accentDeep)
                                }
                                listOf(
                                    I18n.t("studio.tab_home"),
                                    I18n.t("studio.tab_view"),
                                    I18n.t("studio.tab_tools"),
                                    I18n.t("studio.modules"),
                                ).forEachIndexed { index, label ->
                                    TitleTab(label, ribbonOpen && ribbonTab == index) {
                                        if (ribbonOpen && ribbonTab == index) ribbonOpen = false
                                        else { ribbonTab = index; ribbonOpen = true }
                                    }
                                }
                                Box(Modifier.weight(1f).fillMaxHeight().then(drag))
                                Row(
                                    Modifier
                                        .width(300.dp)
                                        .fillMaxHeight()
                                        .background(p.accentDeep)
                                        .clickable { paletteOpen = true }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(8.dp))
                                    FlatText(I18n.t("studio.palette_hint"), color = Color.White.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
                                    FlatText(modKey + "K", color = Color.White.copy(alpha = 0.6f), size = 11)
                                }
                                Box(Modifier.width(24.dp).fillMaxHeight().then(drag))
                                val t = target
                                if (t != null) {
                                    StatusSegment(t.name, p.accentDeep, bold = true)
                                    WindowButton(Icons.Filled.PlayArrow, I18n.t("studio.run"), Tiles.Forest, Tiles.Forest.lift(0.15f), 44.dp) { runTarget(t) }
                                }
                                WindowButton(Icons.Filled.AddToQueue, I18n.t("studio.new_window"), p.accent, p.accent.lift(0.15f)) {
                                    vm.newStudioWindow()
                                }
                                WindowButton(Icons.Filled.Minimize, I18n.t("studio.minimize"), p.accent, p.accent.lift(0.15f)) {
                                    state.isMinimized = true
                                }
                                WindowButton(Icons.Filled.CropSquare, I18n.t("studio.maximize"), p.accent, p.accent.lift(0.15f)) {
                                    frame.toggleMaximize()
                                }
                                WindowButton(Icons.Filled.Close, I18n.t("studio.close_window"), p.accent, Color(0xFFE81123)) { requestClose() }
                            }

                            // 命令带
                            AnimatedVisibility(
                                ribbonOpen,
                                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().height(64.dp).background(p.panel).horizontalScroll(rememberScrollState())
                                ) {
                                    val has = target != null
                                    val dirOk = gameDir != null
                                    var n = 0
                                    when (ribbonTab) {
                                        0 -> {
                                            CommandBlock(I18n.t("studio.run"), Icons.Filled.PlayArrow, Tiles.Forest, { target?.let { runTarget(it) } }, enabled = has, index = n++)
                                            CommandBlock(I18n.t("studio.refresh"), Icons.Filled.Refresh, Tiles.Slate, { refresh() }, index = n++)
                                            CommandBlock(I18n.t("studio.save"), Icons.Filled.Save, Tiles.Navy, { saveActive() },
                                                enabled = (active as? StudioTab.FileTab)?.let { buffers[it.key]?.dirty } == true, index = n++)
                                            CommandBlock(I18n.t("studio.overview"), Icons.Filled.Dashboard, Tiles.Blue, { target?.let { openTab(StudioTab.Overview(it)) } }, enabled = has, index = n++)
                                            CommandBlock(I18n.t("studio.version_json"), Icons.Filled.Code, Tiles.Sky, { target?.let { openTab(StudioTab.VersionJson(it.versionId)) } }, enabled = has, index = n++)
                                            ContentKind.entries.forEach { kind ->
                                                CommandBlock(contentLabel(kind), contentIcon(kind), contentColor(kind), { openContent(kind) }, enabled = has, index = n++)
                                            }
                                            CommandBlock(I18n.t("studio.storage"), Icons.Filled.Storage, Tiles.Teal, { openStorage() }, enabled = has, index = n++)
                                            CommandBlock(I18n.t("studio.diagnose"), Icons.Filled.HealthAndSafety, Tiles.Orange, { openDiagnose() }, enabled = has, active = active is StudioTab.Diagnose, index = n++)
                                            CommandBlock(I18n.t("studio.compare"), Icons.Filled.CompareArrows, Tiles.Navy, { openCompare() }, enabled = has, active = active is StudioTab.Compare, index = n++)
                                            CommandBlock(I18n.t("studio.configs"), Icons.Filled.Tune, Tiles.Graphite, { openConfigs() }, enabled = has, active = active is StudioTab.Configs, index = n++)
                                            CommandBlock(I18n.t("studio.options_editor"), Icons.Filled.Tune, Tiles.Sky, { openOptions() }, enabled = has, active = active is StudioTab.Options, index = n++)
                                            CommandBlock(I18n.t("studio.live_log"), Icons.AutoMirrored.Filled.Article, Tiles.Teal, { openLiveLog() }, enabled = has, active = active is StudioTab.LiveLog, index = n++)
                                            CommandBlock(I18n.t("studio.crash"), Icons.Filled.BugReport, Tiles.Red, { openCrash() }, enabled = has, active = active is StudioTab.Crash, index = n++)
                                            CommandBlock(I18n.t("studio.mod_updates"), Icons.Filled.SystemUpdateAlt, Tiles.Orange, { openModUpdates() }, enabled = has, active = active is StudioTab.ModUpdates, index = n++)
                                            CommandBlock(I18n.t("studio.export"), Icons.Filled.Share, Tiles.Navy, { openExport() }, enabled = has, active = active is StudioTab.Export, index = n++)
                                            CommandBlock(I18n.t("studio.code"), Icons.Filled.Coffee, Tiles.Navy, { openJavaProject() }, active = active is StudioTab.CodeProject, index = n++)
                                            CommandBlock(I18n.t("studio.docs"), Icons.Filled.Description, Tiles.Blue, { openDocument() }, active = active is StudioTab.Document, index = n++)
                                            CommandBlock(I18n.t("studio.reveal_dir"), Icons.Filled.FolderOpen, Tiles.Gold, { gameDir?.let { reveal(it) } }, enabled = dirOk, index = n++)
                                            CommandBlock(I18n.t("studio.options_txt"), Icons.Filled.Settings, Tiles.Graphite, {
                                                gameDir?.let { openFile(it, it.resolve("options.txt")) }
                                            }, enabled = dirOk, index = n++)
                                            CommandBlock(I18n.t("studio.latest_log"), Icons.AutoMirrored.Filled.Article, Tiles.Slate.lift(0.08f), {
                                                gameDir?.let { openFile(it, it.resolve("logs").resolve("latest.log")) }
                                            }, enabled = dirOk, index = n++)
                                        }
                                        1 -> {
                                            CommandBlock(I18n.t("studio.project"), Icons.Filled.AccountTree, Tiles.Blue, { toggleLeft(LeftTool.PROJECT) }, active = leftTool == LeftTool.PROJECT, index = n++)
                                            CommandBlock(I18n.t("studio.files"), Icons.Filled.Folder, Tiles.Gold, { toggleLeft(LeftTool.FILES) }, active = leftTool == LeftTool.FILES, index = n++)
                                            CommandBlock(I18n.t("studio.code"), Icons.Filled.Coffee, Tiles.Navy, { toggleLeft(LeftTool.CODE) }, active = leftTool == LeftTool.CODE, index = n++)
                                            CommandBlock(I18n.t("studio.docs"), Icons.Filled.Description, Tiles.Blue, { toggleLeft(LeftTool.DOCS) }, active = leftTool == LeftTool.DOCS, index = n++)
                                            CommandBlock(I18n.t("studio.modules"), Icons.Filled.Apps, Tiles.Plum, { toggleLeft(LeftTool.MODULES) }, active = leftTool == LeftTool.MODULES, index = n++)
                                            CommandBlock(I18n.t("studio.run_panel"), Icons.Filled.ViewSidebar, Tiles.Navy, { rightOpen = !rightOpen }, active = rightOpen, index = n++)
                                            CommandBlock(I18n.t("studio.bottom_panel"), Icons.Filled.CallToAction, Tiles.Graphite, {
                                                bottomTool = if (bottomTool == null) BottomTool.RUN else null
                                            }, active = bottomTool != null, index = n++)
                                            CommandBlock(
                                                if (dark) I18n.t("studio.light") else I18n.t("studio.dark"),
                                                if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                                Tiles.Slate,
                                                { dark = !dark }, index = n++)
                                            CommandBlock(I18n.t("studio.reset_layout"), Icons.Filled.Dashboard, Tiles.Teal, { resetLayout() }, index = n++)
                                            CommandBlock(I18n.t("studio.new_window"), Icons.Filled.AddToQueue, Tiles.Blue, { vm.newStudioWindow() }, index = n++)
                                            if (windows.size > 1) windows.forEach { id ->
                                                CommandBlock(windowLabel(id), Icons.Filled.WebAsset, Tiles.Slate, { vm.focusStudio(id) }, active = id == windowId, index = n++)
                                            }
                                        }
                                        2 -> {
                                            BottomTool.entries.forEach { tool ->
                                                CommandBlock(bottomLabel(tool), bottomIcon(tool), bottomColor(tool), { bottomTool = tool }, active = bottomTool == tool, index = n++)
                                            }
                                            CommandBlock(I18n.t("studio.clear_logs"), Icons.Filled.DeleteSweep, Tiles.Slate, { vm.clearGameLogs() }, index = n++)
                                            CommandBlock(I18n.t("studio.log_folder"), Icons.Filled.FolderOpen, Tiles.Gold, { vm.openGameLogFolder() }, index = n++)
                                            CommandBlock(I18n.t("studio.palette"), Icons.Filled.Search, Tiles.Navy, { paletteOpen = true }, index = n++)
                                            CommandBlock(I18n.t("studio.term_tab"), Icons.Filled.Terminal, Tiles.Graphite, { openTerminal() }, active = active is StudioTab.Terminal, index = n++)
                                            CommandBlock(I18n.t("nav.settings"), Icons.Filled.Settings, Tiles.Slate, { openSettings() }, active = active is StudioTab.Settings, index = n++)
                                            CommandBlock(sv("title_short"), Icons.Filled.Poll, Tiles.Sky, { openSurvey() }, active = active is StudioTab.Survey, index = n++)
                                        }
                                        else -> {
                                            studioModules.forEach { module ->
                                                CommandBlock(module.label, module.icon, module.color, { openModule(module) },
                                                    active = (active as? StudioTab.Module)?.moduleId == module.id, index = n++)
                                            }
                                        }
                                    }
                                }
                            }

                            // 主体
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                Column(Modifier.width(44.dp).fillMaxHeight().background(p.activity)) {
                                    ActivityBlock(Icons.Filled.AccountTree, I18n.t("studio.project"), leftTool == LeftTool.PROJECT) { toggleLeft(LeftTool.PROJECT) }
                                    ActivityBlock(Icons.Filled.Folder, I18n.t("studio.files"), leftTool == LeftTool.FILES) { toggleLeft(LeftTool.FILES) }
                                    ActivityBlock(Icons.Filled.Coffee, I18n.t("studio.code"), leftTool == LeftTool.CODE || active is StudioTab.CodeProject) { toggleLeft(LeftTool.CODE) }
                                    ActivityBlock(Icons.Filled.Description, I18n.t("studio.docs"), leftTool == LeftTool.DOCS || active is StudioTab.Document) { toggleLeft(LeftTool.DOCS) }
                                    ActivityBlock(Icons.Filled.Apps, I18n.t("studio.modules"), leftTool == LeftTool.MODULES) { toggleLeft(LeftTool.MODULES) }
                                    Spacer(Modifier.weight(1f))
                                    BottomTool.entries.forEach { tool ->
                                        ActivityBlock(bottomIcon(tool), bottomLabel(tool), bottomTool == tool) { toggleBottom(tool) }
                                    }
                                    ActivityBlock(Icons.Filled.Settings, I18n.t("nav.settings"), active is StudioTab.Settings) { openSettings() }
                                }
                                var lastLeft by remember { mutableStateOf(leftTool ?: LeftTool.PROJECT) }
                                LaunchedEffect(leftTool) { leftTool?.let { lastLeft = it } }
                                AnimatedVisibility(
                                    leftTool != null,
                                    enter = expandHorizontally(tween(220), expandFrom = Alignment.Start) + fadeIn(tween(220)),
                                    exit = shrinkHorizontally(tween(180), shrinkTowards = Alignment.Start) + fadeOut(tween(180))
                                ) {
                                    Row(Modifier.fillMaxHeight()) {
                                        Crossfade(leftTool ?: lastLeft, Modifier.width(leftWidth).fillMaxHeight(), tween(160), label = "leftTool") { left ->
                                            when (left) {
                                                LeftTool.PROJECT -> ProjectPanel(vm, target, ::pick, ::runTarget, favorites, ::toggleFavorite)
                                                LeftTool.FILES -> FilesPanel(gameDir, { root, file -> openFile(root, file) }, ::reveal)
                                                LeftTool.CODE -> CodeProjectsPanel(
                                                    javaProjects.toList(),
                                                    onOpen = { openJavaProject(it) },
                                                    onPick = { openJavaProject() },
                                                    onRemove = { javaProjects.remove(it) }
                                                )
                                                LeftTool.DOCS -> DocsPanel(
                                                    recentDocs.toList(),
                                                    onOpen = { openDocument(it) },
                                                    onPick = { openDocument() },
                                                    onNewMind = { newMindMapDoc() },
                                                    onRemove = { recentDocs.remove(it) }
                                                )
                                                LeftTool.MODULES -> ModulesPanel { openModule(it) }
                                            }
                                        }
                                        Splitter(horizontal = true) { leftWidth = clampDp(leftWidth + it, 200.dp, 560.dp) }
                                    }
                                }
                                Column(Modifier.weight(1f).fillMaxHeight()) {
                                    EditorTabs(tabs, activeKey, buffers, pinned.toSet(), { activeKey = it }, ::closeTab, ::togglePin)
                                    // 按标签 key 淡入淡出：同一标签内切分类、换种类只换数据，不整页重建
                                    Crossfade(active?.key, Modifier.weight(1f).fillMaxWidth(), tween(180), label = "editor") { key ->
                                      val tab = if (key == null) null else tabs.firstOrNull { it.key == key }
                                      if (key != null && tab == null) {
                                          Box(Modifier.fillMaxSize().background(p.editor))
                                      } else tabStates.SaveableStateProvider(key ?: "welcome") {
                                        when (tab) {
                                            null -> Welcome(
                                                vm, target,
                                                favorites = favorites.toList(),
                                                recent = recent.toList(),
                                                onRun = { target?.let { runTarget(it) } },
                                                onOverview = { target?.let { openTab(StudioTab.Overview(it)) } },
                                                onContent = ::openContent,
                                                onModule = { openModule(it) },
                                                onPalette = { paletteOpen = true },
                                                onPick = ::pick,
                                                onRecent = ::openRecent,
                                                onStorage = { openStorage() },
                                                onDiagnose = { openDiagnose() },
                                                onCompare = { openCompare() },
                                                onConfigs = { openConfigs() },
                                                onJavaProject = { openJavaProject() },
                                                onDocs = { openDocument() },
                                            )
                                            is StudioTab.Overview -> OverviewEditor(
                                                vm, tab.target, reload,
                                                onRun = { runTarget(tab.target) },
                                                onOpenJson = { openTab(StudioTab.VersionJson(tab.target.versionId)) },
                                                onOpenContent = { kind -> openTab(StudioTab.Content(tab.target, kind)) },
                                                onOpenFile = { root, file -> openFile(root, file) },
                                                onReveal = ::reveal,
                                                onOpenStorage = { openTab(StudioTab.Storage(tab.target)) },
                                                onOpenDiagnose = { openTab(StudioTab.Diagnose(tab.target)) },
                                                onOpenCompare = { openTab(StudioTab.Compare(tab.target)) },
                                                onOpenConfigs = { openTab(StudioTab.Configs(tab.target)) },
                                                onOpenExport = { openTab(StudioTab.Export(tab.target)) },
                                            )
                                            is StudioTab.VersionJson -> VersionJsonEditor(vm, tab.versionId)
                                            is StudioTab.Content -> ContentEditor(
                                                vm, tab,
                                                onKind = { kind -> openTab(tab.copy(kind = kind)) },
                                                onReveal = ::reveal,
                                                onConfirm = { title, body, action ->
                                                    confirm = ConfirmRequest(title, body, I18n.t("studio.m.restore"), Tiles.Orange, action)
                                                }
                                            )
                                            is StudioTab.FileTab -> {
                                                val fallback = remember { FileBuffer() }
                                                FileEditor(tab, buffers[tab.key] ?: fallback, onSave = { saveActive() })
                                            }
                                            is StudioTab.Storage -> StorageEditor(vm, tab.target, ::reveal) { title, body, action ->
                                                confirm = ConfirmRequest(title, body, I18n.t("studio.clean"), Tiles.Red, action)
                                            }
                                            is StudioTab.Diagnose -> DiagnoseEditor(vm, tab.target, ::diagnoseAction)
                                            is StudioTab.Compare -> CompareEditor(
                                                vm, tab,
                                                onOther = { openTab(tab.copy(otherKey = it)) },
                                                onConfirm = { title, body, action ->
                                                    confirm = ConfirmRequest(title, body, I18n.t("studio.compare_copy"), Tiles.Blue, action)
                                                }
                                            )
                                            is StudioTab.Configs -> ConfigsEditor(vm, tab.target, { root, file -> openFile(root, file) }, ::reveal)
                                            is StudioTab.LiveLog -> LiveLogEditor(
                                                vm, tab.target,
                                                onOpenCrash = { openTab(StudioTab.Crash(tab.target)) },
                                                onOpenMods = { openTab(StudioTab.Content(tab.target, ContentKind.MODS)) },
                                            )
                                            is StudioTab.Crash -> CrashReportsEditor(vm, tab.target, onOpenMods = { openTab(StudioTab.Content(tab.target, ContentKind.MODS)) })
                                            is StudioTab.ModUpdates -> ModUpdatesEditor(vm, tab.target)
                                            is StudioTab.Options -> OptionsEditor(vm, tab.target)
                                            is StudioTab.Export -> ExportEditor(vm, tab.target, ::reveal)
                                            is StudioTab.CodeProject -> CodeProjectEditor(
                                                tab.root,
                                                onOpenFile = { root, file -> openFile(root, file) },
                                                onReveal = ::reveal,
                                                onOpenTerminal = { openTerminal() }
                                            )
                                            is StudioTab.Document -> DocumentEditor(tab.file, ::reveal)
                                            is StudioTab.Module -> ModuleEditor(
                                                vm, tab,
                                                onSection = { section -> openTab(tab.copy(sectionId = section)) },
                                                onConfirm = { title, body, action ->
                                                    confirm = ConfirmRequest(title, body, I18n.t("studio.m.confirm"), Tiles.Red, action)
                                                }
                                            )
                                            is StudioTab.Settings -> SettingsEditor(
                                                vm, themeState, tab.category,
                                                onCategory = { openTab(StudioTab.Settings(it)) },
                                                onClassic = { section -> openTab(StudioTab.Module("settings", section)) },
                                                onConfirm = { title, body, action ->
                                                    confirm = ConfirmRequest(title, body, I18n.t("studio.settings_enable"), Tiles.Orange, action)
                                                }
                                            )
                                            is StudioTab.Terminal -> TerminalView(terminal, Modifier.fillMaxSize())
                                            is StudioTab.Survey -> SurveyEditor(vm) { title, body, action ->
                                                confirm = ConfirmRequest(title, body, I18n.t("studio.m.delete"), Tiles.Red, action)
                                            }
                                        }
                                      }
                                    }
                                    var lastBottom by remember { mutableStateOf(bottomTool ?: BottomTool.RUN) }
                                    LaunchedEffect(bottomTool) { bottomTool?.let { lastBottom = it } }
                                    AnimatedVisibility(
                                        bottomTool != null,
                                        enter = expandVertically(tween(220), expandFrom = Alignment.Bottom) + fadeIn(tween(220)),
                                        exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Bottom) + fadeOut(tween(180))
                                    ) {
                                      val bottom = bottomTool ?: lastBottom
                                      Column(Modifier.fillMaxWidth()) {
                                        Splitter(horizontal = false) { bottomHeight = clampDp(bottomHeight - it, 120.dp, 560.dp) }
                                        Column(Modifier.height(bottomHeight).fillMaxWidth().background(p.panel)) {
                                            BottomTabs(bottom, { bottomTool = it }, { bottomTool = null })
                                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                                when (bottom) {
                                                    BottomTool.RUN -> RunLogPanel(vm)
                                                    BottomTool.SEARCH -> SearchPanel(gameDir) { root, file, line -> openFile(root, file, line = line) }
                                                    BottomTool.TERMINAL -> TerminalView(terminal, Modifier.fillMaxSize(), onExpand = { openTerminal() })
                                                    BottomTool.PROBLEMS -> ProblemsPanel(
                                                        vm, target, gameDir,
                                                        onOpen = { tab -> openFile(tab.root, tab.file, tab.readOnly) },
                                                        onOpenAtLine = { root, file, line -> openFile(root, file, line = line) },
                                                        onOpenTab = { openTab(it) },
                                                    )
                                                }
                                            }
                                        }
                                      }
                                    }
                                }
                                AnimatedVisibility(
                                    rightOpen,
                                    enter = expandHorizontally(tween(220), expandFrom = Alignment.End) + fadeIn(tween(220)),
                                    exit = shrinkHorizontally(tween(180), shrinkTowards = Alignment.End) + fadeOut(tween(180))
                                ) {
                                    Row(Modifier.fillMaxHeight()) {
                                        Splitter(horizontal = true) { rightWidth = clampDp(rightWidth - it, 220.dp, 480.dp) }
                                        Box(Modifier.width(rightWidth).fillMaxHeight()) {
                                            RunPanel(vm, target, ::runTarget) { openTab(StudioTab.Overview(it)) }
                                        }
                                    }
                                }
                                Column(Modifier.width(44.dp).fillMaxHeight().background(p.activity)) {
                                    ActivityBlock(Icons.Filled.PlayCircle, I18n.t("studio.run_panel"), rightOpen) { rightOpen = !rightOpen }
                                    ActivityBlock(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, I18n.t("studio.dark"), false) { dark = !dark }
                                    ActivityBlock(Icons.Filled.Poll, sv("title_short"), active is StudioTab.Survey) { openSurvey() }
                                    ActivityBlock(
                                        if (ribbonOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        I18n.t("studio.ribbon"),
                                        false
                                    ) { ribbonOpen = !ribbonOpen }
                                }
                            }
                            StatusBar(vm, target, active, buffers)
                        }

                        if (!frame.maximized) ResizeEdges(this)

                        SurveyPrompt(
                            visible = surveyPrompt && active !is StudioTab.Survey,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 36.dp),
                            onRate = { openSurvey(it) },
                            onLater = { surveyPrompt = false; SurveyHub.snooze() },
                            onNever = { surveyPrompt = false; SurveyHub.never() }
                        )

                        if (paletteOpen) {
                            CommandPalette(
                                paletteItems(
                                    vm = vm,
                                    target = target,
                                    tabs = tabs.toList(),
                                    dark = dark,
                                    onRun = { target?.let { runTarget(it) } },
                                    onRefresh = { refresh() },
                                    onSave = { saveActive() },
                                    onCloseTab = { activeTab()?.let { closeTab(it) } },
                                    onPick = ::pick,
                                    onOpenTab = ::openTab,
                                    onContent = ::openContent,
                                    onModule = { m, s -> openModule(m, s) },
                                    onLeft = ::toggleLeft,
                                    onBottom = { bottomTool = it },
                                    onRight = { rightOpen = !rightOpen },
                                    onDark = { dark = !dark },
                                    onReset = { resetLayout() },
                                    onReveal = { gameDir?.let { reveal(it) } },
                                    onOpenFile = { rel -> gameDir?.let { d -> openFile(d, d.resolve(rel)) } },
                                    onCloseStudio = { requestClose() },
                                    favorites = favorites.toList(),
                                    recent = recent.toList(),
                                    onRecent = ::openRecent,
                                    onStorage = { openStorage() },
                                    onFavorite = ::toggleFavorite,
                                    onDiagnose = { openDiagnose() },
                                    onCompare = { openCompare() },
                                    onConfigs = { openConfigs() },
                                    onJavaProject = { openJavaProject() },
                                    javaProjects = javaProjects.toList(),
                                    onOpenJava = { openJavaProject(Paths.get(it)) },
                                    onDocs = { openDocument() },
                                    onNewMind = { newMindMapDoc() },
                                    recentDocs = recentDocs.toList(),
                                    onOpenDoc = { openDocument(Paths.get(it)) },
                                    windowId = windowId,
                                    windows = windows,
                                    onNewWindow = { vm.newStudioWindow() },
                                    onFocusWindow = { vm.focusStudio(it) },
                                )
                            ) { paletteOpen = false }
                        }

                        discard?.let { action ->
                            ConfirmDialog(
                                ConfirmRequest(I18n.t("studio.discard_title"), I18n.t("studio.discard_body"), I18n.t("studio.discard"), Tiles.Red, action),
                                onCancel = { discard = null },
                                onConfirm = { discard = null; action() }
                            )
                        }
                        confirm?.let { request ->
                            ConfirmDialog(request, onCancel = { confirm = null }, onConfirm = { confirm = null; request.action() })
                        }
                    }
                }
            }
        }
    }
}

private fun windowLabel(id: Int): String = I18n.t("studio.window_n", id.toString())

/** 第一个窗口居中，之后的按 32dp 阶梯错开，免得新窗口正好盖住旧窗口 */
private fun studioWindowPosition(cascade: Int): WindowPosition {
    if (cascade == 0) return WindowPosition(Alignment.Center)
    val bounds = try {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    } catch (_: Throwable) {
        return WindowPosition(Alignment.Center)
    }
    val offset = 32 * cascade
    val x = (bounds.x + (bounds.width - 1380) / 2 + offset).coerceAtLeast(bounds.x)
    val y = (bounds.y + (bounds.height - 860) / 2 + offset).coerceAtLeast(bounds.y)
    return WindowPosition(x.dp, y.dp)
}

private fun clampDp(value: Dp, min: Dp, max: Dp): Dp = when {
    value < min -> min
    value > max -> max
    else -> value
}

private fun paletteItems(
    vm: LauncherViewModel,
    target: StudioTarget?,
    tabs: List<StudioTab>,
    dark: Boolean,
    onRun: () -> Unit,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onCloseTab: () -> Unit,
    onPick: (StudioTarget) -> Unit,
    onOpenTab: (StudioTab) -> Unit,
    onContent: (ContentKind) -> Unit,
    onModule: (StudioModule, String?) -> Unit,
    onLeft: (LeftTool) -> Unit,
    onBottom: (BottomTool) -> Unit,
    onRight: () -> Unit,
    onDark: () -> Unit,
    onReset: () -> Unit,
    onReveal: () -> Unit,
    onOpenFile: (String) -> Unit,
    onCloseStudio: () -> Unit,
    favorites: List<String>,
    recent: List<RecentFile>,
    onRecent: (RecentFile) -> Unit,
    onStorage: () -> Unit,
    onFavorite: (StudioTarget) -> Unit,
    onDiagnose: () -> Unit,
    onCompare: () -> Unit,
    onConfigs: () -> Unit,
    onJavaProject: () -> Unit,
    javaProjects: List<String>,
    onOpenJava: (String) -> Unit,
    onDocs: () -> Unit,
    onNewMind: () -> Unit,
    recentDocs: List<String>,
    onOpenDoc: (String) -> Unit,
    windowId: Int,
    windows: List<Int>,
    onNewWindow: () -> Unit,
    onFocusWindow: (Int) -> Unit,
): List<PaletteItem> {
    val actions = I18n.t("studio.group_actions")
    val out = ArrayList<PaletteItem>()
    out += PaletteItem(I18n.t("studio.new_window"), I18n.t("studio.group_windows"), Icons.Filled.AddToQueue, Tiles.Blue, modKey + "⇧N", onNewWindow)
    windows.filter { it != windowId }.forEach { id ->
        out += PaletteItem(I18n.t("studio.switch_window", windowLabel(id)), I18n.t("studio.group_windows"), Icons.Filled.WebAsset, Tiles.Slate) { onFocusWindow(id) }
    }
    recent.forEach { r ->
        val name = r.file.substringAfterLast('/').substringAfterLast('\\')
        out += PaletteItem(name, I18n.t("studio.recent"), Icons.Filled.History, Tiles.Slate, r.file.removePrefix(r.root).trimStart('/', '\\')) { onRecent(r) }
    }
    if (target != null) {
        val fav = favoriteKey(target) in favorites
        out += PaletteItem(
            (if (fav) I18n.t("studio.unfavorite") else I18n.t("studio.favorite")) + " · " + target.name,
            actions, Icons.Filled.Star, Tiles.Sky
        ) { onFavorite(target) }
        out += PaletteItem(I18n.t("studio.storage") + " · " + target.name, actions, Icons.Filled.Storage, Tiles.Teal, action = onStorage)
        out += PaletteItem(I18n.t("studio.diagnose") + " · " + target.name, actions, Icons.Filled.HealthAndSafety, Tiles.Orange, action = onDiagnose)
        out += PaletteItem(I18n.t("studio.compare") + " · " + target.name, actions, Icons.Filled.CompareArrows, Tiles.Navy, action = onCompare)
        out += PaletteItem(I18n.t("studio.configs") + " · " + target.name, actions, Icons.Filled.Tune, Tiles.Graphite, action = onConfigs)
        out += PaletteItem(I18n.t("studio.options_editor") + " · " + target.name, actions, Icons.Filled.Tune, Tiles.Sky) { onOpenTab(StudioTab.Options(target)) }
        out += PaletteItem(I18n.t("studio.live_log") + " · " + target.name, actions, Icons.AutoMirrored.Filled.Article, Tiles.Teal) { onOpenTab(StudioTab.LiveLog(target)) }
        out += PaletteItem(I18n.t("studio.crash") + " · " + target.name, actions, Icons.Filled.BugReport, Tiles.Red) { onOpenTab(StudioTab.Crash(target)) }
        out += PaletteItem(I18n.t("studio.mod_updates") + " · " + target.name, actions, Icons.Filled.SystemUpdateAlt, Tiles.Orange) { onOpenTab(StudioTab.ModUpdates(target)) }
        out += PaletteItem(I18n.t("studio.export") + " · " + target.name, actions, Icons.Filled.Share, Tiles.Navy) { onOpenTab(StudioTab.Export(target)) }
        out += PaletteItem(I18n.t("studio.run") + " " + target.name, actions, Icons.Filled.PlayArrow, Tiles.Forest, modKey + "R", onRun)
        out += PaletteItem(I18n.t("studio.overview") + " · " + target.name, actions, Icons.Filled.Dashboard, Tiles.Blue) { onOpenTab(StudioTab.Overview(target)) }
        out += PaletteItem(I18n.t("studio.version_json") + " · " + target.versionId, actions, Icons.Filled.Code, Tiles.Sky) { onOpenTab(StudioTab.VersionJson(target.versionId)) }
        out += PaletteItem(I18n.t("studio.reveal_dir"), actions, Icons.Filled.FolderOpen, Tiles.Gold, action = onReveal)
        out += PaletteItem("options.txt", actions, Icons.Filled.Settings, Tiles.Graphite) { onOpenFile("options.txt") }
        out += PaletteItem(I18n.t("studio.latest_log"), actions, Icons.AutoMirrored.Filled.Article, Tiles.Slate) { onOpenFile("logs/latest.log") }
        ContentKind.entries.forEach { kind ->
            out += PaletteItem(contentLabel(kind) + " · " + target.name, I18n.t("studio.group_content"), contentIcon(kind), contentColor(kind)) { onContent(kind) }
        }
    }
    out += PaletteItem(I18n.t("studio.refresh"), actions, Icons.Filled.Refresh, Tiles.Slate, modKey + "⇧R", onRefresh)
    out += PaletteItem(I18n.t("studio.save"), actions, Icons.Filled.Save, Tiles.Navy, modKey + "S", onSave)
    out += PaletteItem(I18n.t("studio.close_tab"), actions, Icons.Filled.Close, Tiles.Graphite, modKey + "W", onCloseTab)
    out += PaletteItem(I18n.t("studio.project"), actions, Icons.Filled.AccountTree, Tiles.Blue, modKey + "B") { onLeft(LeftTool.PROJECT) }
    out += PaletteItem(I18n.t("studio.files"), actions, Icons.Filled.Folder, Tiles.Gold) { onLeft(LeftTool.FILES) }
    out += PaletteItem(I18n.t("studio.code"), actions, Icons.Filled.Coffee, Tiles.Navy) { onLeft(LeftTool.CODE) }
    out += PaletteItem(I18n.t("studio.code_open"), actions, Icons.Filled.FolderOpen, Tiles.Navy, action = onJavaProject)
    javaProjects.forEach { path ->
        val name = Paths.get(path).fileName?.toString() ?: path
        out += PaletteItem(name, I18n.t("studio.code_recent"), Icons.Filled.Coffee, Tiles.Navy, path) { onOpenJava(path) }
    }
    out += PaletteItem(I18n.t("studio.docs"), actions, Icons.Filled.Description, Tiles.Blue) { onLeft(LeftTool.DOCS) }
    out += PaletteItem(I18n.t("studio.doc_open"), actions, Icons.Filled.FolderOpen, Tiles.Blue, action = onDocs)
    out += PaletteItem(I18n.t("studio.doc_new_mind"), actions, Icons.Filled.AccountTree, Tiles.Purple, action = onNewMind)
    recentDocs.forEach { path ->
        val name = Paths.get(path).fileName?.toString() ?: path
        val kind = docKindOf(Paths.get(path))
        out += PaletteItem(
            name,
            I18n.t("studio.doc_recent"),
            kind?.let { docIcon(it) } ?: Icons.Filled.Description,
            kind?.let { docColor(it) } ?: Tiles.Blue,
            path
        ) { onOpenDoc(path) }
    }
    out += PaletteItem(I18n.t("studio.modules"), actions, Icons.Filled.Apps, Tiles.Plum) { onLeft(LeftTool.MODULES) }
    out += PaletteItem(I18n.t("studio.run_panel"), actions, Icons.Filled.ViewSidebar, Tiles.Navy, modKey + "E", onRight)
    BottomTool.entries.forEach { tool ->
        out += PaletteItem(
            bottomLabel(tool), actions, bottomIcon(tool), bottomColor(tool),
            if (tool == BottomTool.SEARCH) modKey + "⇧F" else ""
        ) { onBottom(tool) }
    }
    out += PaletteItem(if (dark) I18n.t("studio.light") else I18n.t("studio.dark"), actions, Icons.Filled.DarkMode, Tiles.Slate, action = onDark)
    out += PaletteItem(I18n.t("studio.reset_layout"), actions, Icons.Filled.Dashboard, Tiles.Teal, action = onReset)
    out += PaletteItem(I18n.t("studio.clear_logs"), actions, Icons.Filled.DeleteSweep, Tiles.Slate) { vm.clearGameLogs() }
    out += PaletteItem(I18n.t("studio.term_tab"), actions, Icons.Filled.Terminal, Tiles.Graphite) { onOpenTab(StudioTab.Terminal) }
    out += PaletteItem(sv("title_short"), actions, Icons.Filled.Poll, Tiles.Sky) { onOpenTab(StudioTab.Survey) }
    out += PaletteItem(I18n.t("studio.close_window"), actions, Icons.Filled.Close, Tiles.Graphite, action = onCloseStudio)
    tabs.forEach { tab ->
        out += PaletteItem(tabTitle(tab), I18n.t("studio.group_tabs"), Icons.Filled.Code, tabColor(tab)) { onOpenTab(tab) }
    }
    studioModules.forEach { module ->
        out += PaletteItem(module.label, I18n.t("studio.modules"), module.icon, module.color) { onModule(module, null) }
        module.sections.forEach { s ->
            out += PaletteItem(module.label + " › " + I18n.t(s.labelKey), I18n.t("studio.modules"), s.icon ?: module.icon, module.color) {
                onModule(module, s.id)
            }
        }
    }
    val (versions, instances) = targetsOf(vm.localVersionInfos.value, vm.instances.value)
    (versions + instances).filter { favoriteKey(it) in favorites }.forEach { t ->
        out += PaletteItem(t.name, I18n.t("studio.favorites"), Icons.Filled.Star, Tiles.Sky, t.versionId) { onPick(t) }
    }
    versions.forEach { t ->
        out += PaletteItem(t.name, I18n.t("studio.versions"), Icons.Filled.Code, Tiles.Blue, t.detail) { onPick(t) }
    }
    instances.forEach { t ->
        out += PaletteItem(t.name, I18n.t("studio.instances"), Icons.Filled.Folder, Tiles.Navy, t.versionId) { onPick(t) }
    }
    return out
}

@Composable
private fun EditorTabs(
    tabs: List<StudioTab>,
    activeKey: String?,
    buffers: Map<String, FileBuffer>,
    pinned: Set<String>,
    onSelect: (String) -> Unit,
    onClose: (StudioTab) -> Unit,
    onPin: (StudioTab) -> Unit,
) {
    val p = LocalStudio.current
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(p.strip).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val on = tab.key == activeKey
            val isPinned = tab.key in pinned
            val color = tabColor(tab)
            Row(
                Modifier
                    .fillMaxHeight()
                    .background(if (on) color else Color.Transparent)
                    .clickable { onSelect(tab.key) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(if (on) color.lift(0.25f) else color))
                Spacer(Modifier.width(10.dp))
                FlatText(tabTitle(tab), color = if (on) Color.White else p.text, weight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                if (buffers[tab.key]?.dirty == true) FlatText("  ●", color = if (on) Color.White else p.accent)
                Box(
                    Modifier.size(28.dp).clickable { onPin(tab) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = I18n.t("studio.pin_tab"),
                        tint = if (isPinned) (if (on) Color.White else p.accent) else (if (on) Color.White.copy(alpha = 0.55f) else p.muted.copy(alpha = 0.55f)),
                        modifier = Modifier.size(12.dp)
                    )
                }
                if (!isPinned) {
                    Box(
                        Modifier.size(28.dp).clickable { onClose(tab) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = I18n.t("studio.close_tab"), tint = if (on) Color.White else p.muted, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

internal fun tabTitle(tab: StudioTab): String = when (tab) {
    is StudioTab.Overview -> tab.target.name
    is StudioTab.VersionJson -> tab.versionId + ".json"
    is StudioTab.Content -> contentLabel(tab.kind) + " · " + tab.target.name
    is StudioTab.FileTab -> tab.file.fileName?.toString().orEmpty()
    is StudioTab.Module -> moduleOf(tab.moduleId)?.label ?: tab.moduleId
    is StudioTab.Storage -> I18n.t("studio.storage") + " · " + tab.target.name
    is StudioTab.Diagnose -> I18n.t("studio.diagnose") + " · " + tab.target.name
    is StudioTab.Compare -> I18n.t("studio.compare") + " · " + tab.target.name
    is StudioTab.Configs -> I18n.t("studio.configs") + " · " + tab.target.name
    is StudioTab.LiveLog -> I18n.t("studio.live_log") + " · " + tab.target.name
    is StudioTab.Crash -> I18n.t("studio.crash") + " · " + tab.target.name
    is StudioTab.ModUpdates -> I18n.t("studio.mod_updates") + " · " + tab.target.name
    is StudioTab.Options -> I18n.t("studio.options_editor") + " · " + tab.target.name
    is StudioTab.Export -> I18n.t("studio.export") + " · " + tab.target.name
    is StudioTab.CodeProject -> {
        val name = runCatching { Paths.get(tab.root).fileName?.toString() }.getOrNull().orEmpty()
        I18n.t("studio.code") + if (name.isBlank()) "" else " · $name"
    }
    is StudioTab.Document -> runCatching { Paths.get(tab.file).fileName?.toString() }.getOrNull().orEmpty().ifBlank { I18n.t("studio.docs") }
    is StudioTab.Settings -> I18n.t("nav.settings")
    is StudioTab.Terminal -> I18n.t("studio.term_tab")
    is StudioTab.Survey -> sv("title_short")
}

internal fun tabColor(tab: StudioTab): Color = when (tab) {
    is StudioTab.Overview -> if (tab.target.kind == TargetKind.VERSION) Tiles.Blue else Tiles.Navy
    is StudioTab.VersionJson -> Tiles.Sky
    is StudioTab.Content -> contentColor(tab.kind)
    is StudioTab.FileTab -> Tiles.Purple
    is StudioTab.Module -> moduleOf(tab.moduleId)?.color ?: Tiles.Slate
    is StudioTab.Storage -> Tiles.Teal
    is StudioTab.Diagnose -> Tiles.Orange
    is StudioTab.Compare -> Tiles.Navy
    is StudioTab.Configs -> Tiles.Graphite
    is StudioTab.LiveLog -> Tiles.Teal
    is StudioTab.Crash -> Tiles.Red
    is StudioTab.ModUpdates -> Tiles.Orange
    is StudioTab.Options -> Tiles.Sky
    is StudioTab.Export -> Tiles.Navy
    is StudioTab.CodeProject -> Tiles.Navy
    is StudioTab.Document -> docKindOf(Paths.get(tab.file))?.let { docColor(it) } ?: Tiles.Blue
    is StudioTab.Settings -> Tiles.Slate
    is StudioTab.Terminal -> Tiles.Graphite
    is StudioTab.Survey -> Tiles.Sky
}

@Composable
private fun Welcome(
    vm: LauncherViewModel,
    target: StudioTarget?,
    favorites: List<String>,
    recent: List<RecentFile>,
    onRun: () -> Unit,
    onOverview: () -> Unit,
    onContent: (ContentKind) -> Unit,
    onModule: (StudioModule) -> Unit,
    onPalette: () -> Unit,
    onPick: (StudioTarget) -> Unit,
    onRecent: (RecentFile) -> Unit,
    onStorage: () -> Unit,
    onDiagnose: () -> Unit = {},
    onCompare: () -> Unit = {},
    onConfigs: () -> Unit = {},
    onJavaProject: () -> Unit = {},
    onDocs: () -> Unit = {},
) {
    val p = LocalStudio.current
    val versions by vm.localVersionInfos.collectAsState()
    val instances by vm.instances.collectAsState()
    val running by vm.runningInstances.collectAsState()
    val pinned = remember(versions, instances, favorites) {
        val (v, i) = targetsOf(versions, instances)
        (v + i).filter { favoriteKey(it) in favorites }.take(6)
    }
    Column(Modifier.fillMaxSize().background(p.editor).verticalScroll(rememberScrollState()).padding(40.dp)) {
        FlatText("PMCL Studio", size = 34, weight = FontWeight.Light, modifier = Modifier.entrance(0))
        FlatText(I18n.t("studio.welcome"), color = p.muted, size = 13, modifier = Modifier.entrance(1))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().height(120.dp)) {
            ColorTile(I18n.t("studio.run"), target?.name ?: "—", Tiles.Forest, Modifier.weight(2f).fillMaxHeight(), Icons.Filled.PlayArrow,
                if (target != null) onRun else null, valueSize = 24, index = 2)
            ColorTile(I18n.t("studio.versions"), versions.size.toString(), Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Code, valueSize = 30, index = 3)
            ColorTile(I18n.t("studio.instances"), instances.size.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Folder, valueSize = 30, index = 4)
            ColorTile(
                I18n.t("studio.running"), running.size.toString(),
                if (running.isNotEmpty()) Tiles.Orange else Tiles.Slate,
                Modifier.weight(1f).fillMaxHeight(), Icons.Filled.PlayCircle, valueSize = 30, index = 5
            )
        }
        Row(Modifier.fillMaxWidth().height(96.dp)) {
            ColorTile(I18n.t("studio.overview"), target?.name ?: "—", Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Dashboard,
                if (target != null) onOverview else null, index = 6)
            ContentKind.entries.forEachIndexed { i, kind ->
                ColorTile(contentLabel(kind), "", contentColor(kind), Modifier.weight(1f).fillMaxHeight(), contentIcon(kind),
                    if (target != null) ({ onContent(kind) }) else null, index = 7 + i)
            }
            ColorTile(I18n.t("studio.storage"), "", Tiles.Teal, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Storage,
                if (target != null) onStorage else null, index = 12)
        }
        Row(Modifier.fillMaxWidth().height(84.dp)) {
            ColorTile(I18n.t("studio.diagnose"), "", Tiles.Orange, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.HealthAndSafety,
                if (target != null) onDiagnose else null, index = 20)
            ColorTile(I18n.t("studio.compare"), "", Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.CompareArrows,
                if (target != null) onCompare else null, index = 21)
            ColorTile(I18n.t("studio.configs"), "", Tiles.Graphite, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Tune,
                if (target != null) onConfigs else null, index = 22)
            ColorTile(I18n.t("studio.code"), "", Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Coffee, onJavaProject, index = 23)
            ColorTile(I18n.t("studio.docs"), "", Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Description, onDocs, index = 24)
            ColorTile(I18n.t("studio.palette"), "", Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Search, onPalette, index = 25)
        }
        if (pinned.isNotEmpty()) {
            SectionLabel(I18n.t("studio.favorites"), Icons.Filled.Star)
            Row(Modifier.fillMaxWidth().height(72.dp)) {
                pinned.forEachIndexed { i, t ->
                    ColorTile(
                        if (t.kind == TargetKind.VERSION) I18n.t("studio.kind_version") else I18n.t("studio.kind_instance"),
                        t.name,
                        if (t.kind == TargetKind.VERSION) Tiles.Blue else Tiles.Navy,
                        Modifier.weight(1f).fillMaxHeight(),
                        Icons.Filled.Star,
                        { onPick(t) },
                        valueSize = 14,
                        index = 13 + i
                    )
                }
                repeat(6 - pinned.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        SectionLabel(I18n.t("studio.modules"), Icons.Filled.Apps)
        Row(Modifier.fillMaxWidth().height(80.dp)) {
            studioModules.take(7).forEachIndexed { i, m ->
                ColorTile(m.label, "", m.color, Modifier.weight(1f).fillMaxHeight(), m.icon, { onModule(m) }, index = 14 + i)
            }
        }
        if (recent.isNotEmpty()) {
            SectionLabel(I18n.t("studio.recent"), Icons.Filled.History)
            recent.take(8).forEachIndexed { i, r ->
                RecentRow(r, i) { onRecent(r) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(40.dp).background(p.panel).clickable { onPalette() }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(p.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            FlatText(I18n.t("studio.shortcuts", modKey), color = p.muted, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val p = LocalStudio.current
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = p.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        FlatText(text, color = p.muted, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecentRow(entry: RecentFile, index: Int, onOpen: () -> Unit) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) p.hover else if (index % 2 == 0) p.panel else p.panelAlt, tween(140), label = "recentRow")
    val bar by animateDpAsState(if (hovered) 8.dp else 4.dp, tween(160), label = "recentBar")
    val name = entry.file.substringAfterLast('/').substringAfterLast('\\')
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .entrance(20 + index)
            .background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(bar).fillMaxHeight().background(Tiles.Purple))
        Spacer(Modifier.width(10.dp))
        FlatText(name, weight = FontWeight.Medium, modifier = Modifier.width(220.dp))
        FlatText(entry.file, color = p.muted, size = 11, modifier = Modifier.weight(1f).padding(end = 12.dp))
    }
}

@Composable
private fun StatusBar(vm: LauncherViewModel, target: StudioTarget?, active: StudioTab?, buffers: Map<String, FileBuffer>) {
    val p = LocalStudio.current
    val status by vm.status.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    val running by vm.runningInstances.collectAsState()
    val logs by vm.gameLogs.collectAsState()
    val errors = remember(logs) { logs.count { it.text.contains("ERROR") || it.text.contains("FATAL") } }
    var used by remember { mutableLongStateOf(0L) }
    var max by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val rt = Runtime.getRuntime()
            used = rt.totalMemory() - rt.freeMemory()
            max = rt.maxMemory()
            delay(2000)
        }
    }
    val dirty = buffers.values.count { it.dirty }
    Row(Modifier.fillMaxWidth().height(24.dp).background(p.accent), verticalAlignment = Alignment.CenterVertically) {
        StatusSegment(status.ifBlank { I18n.t("studio.ready") }, p.accent, Modifier.weight(1f))
        if (dirty > 0) StatusSegment(I18n.t("studio.unsaved_n", dirty.toString()), Tiles.Purple)
        if (errors > 0) StatusSegment(I18n.t("studio.errors_n", errors.toString()), Tiles.Red)
        if (gameRunning || running.isNotEmpty()) {
            StatusSegment(I18n.t("studio.running_count", running.size.coerceAtLeast(1).toString()), Tiles.Orange, bold = true)
        }
        if (active != null) StatusSegment(tabTitle(active), tabColor(active).lift(0.06f))
        if (target != null) StatusSegment(target.name, Tiles.Navy)
        StatusSegment("${used / (1024 * 1024)} / ${max / (1024 * 1024)} MB", Tiles.Graphite)
    }
}

internal class ConfirmRequest(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val color: Color,
    val action: () -> Unit,
)

@Composable
private fun ConfirmDialog(request: ConfirmRequest, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val p = LocalStudio.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f * appear.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(440.dp)
                .graphicsLayer {
                    alpha = appear.value
                    val sc = 0.94f + 0.06f * appear.value
                    scaleX = sc
                    scaleY = sc
                }
                .background(p.panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            FlatText(
                request.title,
                color = Color.White,
                size = 15,
                weight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(request.color).padding(horizontal = 18.dp, vertical = 14.dp)
            )
            FlatText(request.body, color = p.muted, lines = 4, modifier = Modifier.padding(18.dp))
            Row(Modifier.fillMaxWidth().height(42.dp)) {
                Block(I18n.t("studio.cancel"), onClick = onCancel, fill = p.strip, modifier = Modifier.weight(1f), height = 42.dp)
                Block(request.confirmLabel, onClick = onConfirm, fill = request.color, modifier = Modifier.weight(1f), height = 42.dp)
            }
        }
    }
}
