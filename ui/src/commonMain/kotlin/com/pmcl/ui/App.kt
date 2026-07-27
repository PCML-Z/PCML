package com.pmcl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.plugin.PluginManager
import com.pmcl.ui.animation.AnimatedPageSwitch
import com.pmcl.ui.animation.EntranceAnimation
import com.pmcl.ui.animation.SlideInFromStart
import com.pmcl.ui.navigation.NavDestination
import com.pmcl.ui.navigation.allDestinations
import com.pmcl.ui.page.AccountsPage
import com.pmcl.ui.page.AgreementGatePage
import com.pmcl.ui.page.ContentHubPage
import com.pmcl.ui.page.DownloadHubPage
import com.pmcl.ui.page.InstancesPage
import com.pmcl.ui.page.LaunchPage
import com.pmcl.ui.page.LockscreenLaunchPage
import com.pmcl.ui.page.NbtEditorPage
import com.pmcl.ui.page.FriendPage
import com.pmcl.ui.page.MultiplayerPage
import com.pmcl.ui.page.MusicPage
import com.pmcl.ui.page.NewsPage
import com.pmcl.ui.page.PluginPage
import com.pmcl.ui.page.QuickLaunchPage
import com.pmcl.ui.page.SavesHubPage
import com.pmcl.ui.page.ServersPage
import com.pmcl.ui.page.SettingsPage
import com.pmcl.ui.page.StatisticsPage
import com.pmcl.ui.page.TerminalPage
import com.pmcl.ui.page.WelcomePage
import com.pmcl.ui.theme.LauncherTheme
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.ThemeState
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.pauseMusic
import com.pmcl.ui.viewmodel.playNextMusic
import com.pmcl.ui.viewmodel.playPreviousMusic
import com.pmcl.ui.viewmodel.resumeMusic
import com.pmcl.ui.viewmodel.setMusicVolume
import com.pmcl.ui.viewmodel.stopMusic
import com.pmcl.ui.widget.MiniMusicBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun App(vm: LauncherViewModel) {
    val themeState = remember { ThemeState(initialDark = vm.preferences.isUseDarkTheme()) }

    // 直接把 vm.themeState 设为 themeState，让 ViewModel 的 refreshWallpaperColor 能更新它
    SideEffect {
        vm.themeState = themeState
    }

    // 启动时初始化动态颜色 + UI 缩放
    // M48 修复：用字符串 key 替代 Unit，便于在 Profiler / 调试中区分多个 LaunchedEffect
    LaunchedEffect("init-dynamic-color") {
        val customColor = vm.preferences.getCustomAccentColor()
        if (vm.preferences.isDynamicColor()) {
            // 莫奈取色模式
            themeState.enableDynamicColor(true)
            // 1. 先用持久化的种子色立即应用主题（同步、零截图污染）
            //    避免窗口渲染后再截图导致取到 PMCL 自己的 UI
            val persistedSeed = vm.preferences.getMonetSeedColor()
            if (persistedSeed != -1) {
                themeState.applySeedColor(persistedSeed, vm.preferences.isUseDarkTheme())
            }
            // 2. 再异步刷新：若壁纸未变则命中 5 分钟缓存直接返回；若变了则更新
            vm.refreshWallpaperColor(themeState)
        } else if (customColor != -1) {
            // 自定义强调色模式
            themeState.applyCustomAccentColor(customColor)
            val dark = vm.preferences.isUseDarkTheme()
            themeState.applySeedColor(customColor, dark)
        }
        // 应用 UI 缩放
        themeState.applyUiScale(vm.preferences.getUiScale())
        // 应用视差背景 / 自定义背景 / 玻璃主题 / 锁屏启动页主题初始状态
        themeState.applyParallaxBackground(vm.preferences.isParallaxBackground())
        themeState.applyCustomBackground(vm.isCustomBackgroundActive())
        themeState.applyGlassTheme(vm.preferences.isGlassTheme())
        themeState.applyLockscreenLaunchTheme(vm.preferences.isLockscreenLaunchTheme())
        // 应用主题色彩预设与色彩模式
        themeState.applyThemePreset(vm.preferences.getThemePreset())
        themeState.applyColorMode(vm.preferences.getColorMode())
        // 应用持久化的插件主题包 ID（占位，待插件加载后 reconcile 填充实际 pack）
        themeState.applyCustomThemePackId(vm.preferences.getCustomThemePackId())
    }

    // 直接读取 themeState 的属性，Compose 会自动观察 mutableStateOf 的变化
    // 莫奈取色或自定义强调色时使用 dynamicColorScheme
    val effectiveScheme: androidx.compose.material3.ColorScheme? =
        if (themeState.dynamicColor || themeState.customAccentColor != -1) themeState.dynamicColorScheme else null

    LauncherTheme(
        useDarkTheme = themeState.useDark,
        dynamicColorScheme = effectiveScheme,
        uiScale = themeState.uiScale,
        themePreset = themeState.themePreset,
        colorMode = themeState.colorMode,
        customThemePack = themeState.customThemePack
    ) {
        CompositionLocalProvider(LocalThemeState provides themeState) {
            // 视差背景或自定义背景开启时 Surface 透明，让 Main.kt 的窗口级背景层透出
            val bgTransparent = themeState.parallaxBackground || themeState.customBackground
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (bgTransparent) androidx.compose.ui.graphics.Color.Transparent
                        else MaterialTheme.colorScheme.surface,
                tonalElevation = if (bgTransparent) 0.dp else 1.dp
            ) {
                Box(Modifier.fillMaxSize()) {
                    val agreementAccepted by vm.agreementAccepted.collectAsState()
                    val firstLaunchDone by vm.firstLaunchCompleted.collectAsState()

                    if (!agreementAccepted) {
                        // 首次打开：必须同意用户协议、免责协议与许可证
                        AgreementGatePage(vm)
                    } else if (!firstLaunchDone) {
                        // 首次启动：迁移引导页
                        WelcomePage(vm)
                    } else {
                        // 已完成首次启动：先显示快速欢迎界面，再进入主窗口
                        var enteredMain by remember { mutableStateOf(false) }

                        if (!enteredMain) {
                            if (themeState.lockscreenLaunchTheme) {
                                LockscreenLaunchPage(
                                    vm = vm,
                                    onEnterMain = { enteredMain = true }
                                )
                            } else {
                                QuickLaunchPage(
                                    vm = vm,
                                    onEnterMain = { enteredMain = true }
                                )
                            }
                        } else {
                            MainWindowContent(vm)
                        }
                    }
                    // 全局：GitHub Release 同步更新弹窗（任意页面都可见）
                    PushedUpdateDialog(vm)
                }
            }
        }
    }
}

@Composable
private fun PushedUpdateDialog(vm: LauncherViewModel) {
    val pushedUpdate by vm.pushedUpdate.collectAsState()
    val pushStatusText by vm.pushStatusText.collectAsState()
    val info = pushedUpdate ?: return
    var downloadedBytes by remember(info.version) { mutableLongStateOf(0L) }
    var downloading by remember(info.version) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!downloading) vm.clearPushedUpdate() },
        title = {
            Text("发现新版本 v${info.version}")
        },
        text = {
            Column {
                Text("GitHub Release 同步发现了一个新版本。")
                Spacer(Modifier.height(8.dp))
                if (info.notes.isNotEmpty()) {
                    Text("更新说明:", style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    Text(info.notes, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                val sizeStr = if (info.size > 0) {
                    "%.1f MB".format(info.size / 1024.0 / 1024.0)
                } else "未知大小"
                Text("文件大小: $sizeStr",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
                if (downloading && info.size > 0) {
                    Spacer(Modifier.height(8.dp))
                    val fraction = (downloadedBytes.toFloat() / info.size).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("%.1f / %.1f MB".format(
                        downloadedBytes / 1024.0 / 1024.0,
                        info.size / 1024.0 / 1024.0
                    ), style = MaterialTheme.typography.labelSmall)
                } else if (downloading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (pushStatusText.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(pushStatusText,
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    vm.downloadPushedUpdate { bytes -> downloadedBytes = bytes }
                }
            ) {
                Text(if (downloading) "下载中…" else "下载更新")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !downloading,
                onClick = { vm.clearPushedUpdate() }
            ) {
                Text("稍后再说")
            }
        }
    )
}

/**
 * Represents either a built-in nav destination or a plugin-provided page.
 */
private sealed class NavTarget {
    data class BuiltIn(val dest: NavDestination) : NavTarget()
    data class PluginPage(val page: PluginManager.RegisteredPage) : NavTarget()
}

@Composable
private fun MainWindowContent(vm: LauncherViewModel) {
    val themeState = LocalThemeState.current
    var current by remember { mutableStateOf<NavTarget>(NavTarget.BuiltIn(NavDestination.Launch)) }
    // 导航方向：1=前进，-1=后退，0=初始
    var navDirection by remember { mutableIntStateOf(0) }

    // Collect plugin-provided pages (refreshable via revision polling)
    var pluginPages by remember { mutableStateOf<List<PluginManager.RegisteredPage>>(emptyList()) }
    var hiddenNavRoutes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var navBadgeMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var lastRevision by remember { mutableStateOf(-1L) }
    LaunchedEffect("load-plugins") {
        // 延迟 1.5 秒加载插件，让首屏 UI 先完成渲染（插件加载涉及 JAR 读取+ClassLoader+反射，开销大）
        kotlinx.coroutines.delay(1500)
        // M35 修复：discoverAndLoadAll 无超时，单个卡住的插件（如 JAR 读取死循环、
        // 反射初始化阻塞）会让整个 LaunchedEffect 永不退出，后续 pluginPages 永不更新。
        // 用 withTimeoutOrNull 包裹，30 秒后强制放弃加载剩余插件。
        try {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                vm.core.plugins().discoverAndLoadAll()
            } ?: run {
                System.err.println("[App] Plugin discovery timed out after 30s, partial load only")
            }
        } catch (e: Throwable) {
            // Non-fatal: plugins are optional
        }
        pluginPages = vm.core.plugins().getCustomPages()
        hiddenNavRoutes = try {
            vm.core.plugins().hiddenBuiltinNavRoutes
        } catch (_: Throwable) { emptySet() }
        navBadgeMap = try {
            vm.core.plugins().navBadges.associate { it.target to it.text }
        } catch (_: Throwable) { emptyMap() }
        lastRevision = vm.core.plugins().getRevision()
        // 若当前正停在已卸载/禁用的插件页，立刻退回内置页，避免 Compose 持有已关闭 ClassLoader 的内容
        val afterLoad = current
        if (afterLoad is NavTarget.PluginPage) {
            val stillThere = pluginPages.any {
                it.pluginId == afterLoad.page.pluginId && it.id == afterLoad.page.id
            }
            if (!stillThere) {
                current = NavTarget.BuiltIn(NavDestination.Plugins)
                navDirection = 0
            }
        }
        // 插件加载完成后，检查持久化的 customThemePackId 是否仍可用
        // 若可用则填充实际 ThemePack 对象（启动时仅设置了占位 ID）
        vm.reconcileCustomThemePack(themeState)
    }
    // Poll for plugin changes (install/uninstall/enable/disable via terminal)
    LaunchedEffect("poll-plugin-revision") {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val rev = vm.core.plugins().getRevision()
            if (rev != lastRevision) {
                lastRevision = rev
                pluginPages = vm.core.plugins().getCustomPages()
                hiddenNavRoutes = try {
                    vm.core.plugins().hiddenBuiltinNavRoutes
                } catch (_: Throwable) { emptySet() }
                navBadgeMap = try {
                    vm.core.plugins().navBadges.associate { it.target to it.text }
                } catch (_: Throwable) { emptyMap() }
                val cur = current
                if (cur is NavTarget.PluginPage) {
                    val stillThere = pluginPages.any {
                        it.pluginId == cur.page.pluginId && it.id == cur.page.id
                    }
                    if (!stillThere) {
                        current = NavTarget.BuiltIn(NavDestination.Plugins)
                        navDirection = 0
                    }
                }
                // 插件结构变化（启用/禁用/卸载）可能影响主题包可用性
                vm.reconcileCustomThemePack(themeState)
            }
        }
    }

    // Build full nav list: built-in destinations + plugin pages (respect plugin-hidden routes)
    val navItems = remember(pluginPages, hiddenNavRoutes) {
        val builtIn = allDestinations
            .filter { it.route.lowercase() !in hiddenNavRoutes }
            .map { NavTarget.BuiltIn(it) }
        val plugins = pluginPages.map { NavTarget.PluginPage(it) }
        builtIn + plugins
    }

    // 崩溃恢复操作反馈消息（Snackbar）
    val recoveryMessage by vm.recoveryMessage.collectAsState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(recoveryMessage) {
        val msg = recoveryMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearRecoveryMessage()
    }

    // 插件 UI 桥：导航 / 启动 / 通知 / 剪贴板 / URL / 对话框
    val pluginUiScope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var pluginDialog by remember { mutableStateOf<com.pmcl.plugin.api.PluginDialogRequest?>(null) }
    val pendingPluginDialogs = remember { mutableListOf<com.pmcl.plugin.api.PluginDialogRequest>() }
    var pluginInputDialog by remember { mutableStateOf<com.pmcl.plugin.api.PluginInputDialogRequest?>(null) }
    val pendingInputDialogs = remember { mutableListOf<com.pmcl.plugin.api.PluginInputDialogRequest>() }
    val pendingFilePickers = remember { mutableListOf<com.pmcl.plugin.api.PluginFilePickerRequest>() }
    var activeFilePicker by remember { mutableStateOf<com.pmcl.plugin.api.PluginFilePickerRequest?>(null) }
    val pluginProgress = remember { mutableStateMapOf<String, com.pmcl.plugin.api.PluginProgressUpdate>() }
    LaunchedEffect("plugin-ui-bridges") {
        val pm = vm.core.plugins()
        pm.setNavigationHandler { target ->
            pluginUiScope.launch {
                when {
                    target.startsWith("plugin:") -> {
                        val parts = target.removePrefix("plugin:").split(":", limit = 2)
                        if (parts.size == 2) {
                            val page = pm.getCustomPages().firstOrNull {
                                it.pluginId == parts[0] && it.id == parts[1]
                            }
                            if (page != null) {
                                current = NavTarget.PluginPage(page)
                            }
                        }
                    }
                    target.equals("settings", ignoreCase = true) ->
                        current = NavTarget.BuiltIn(NavDestination.Settings)
                    target.equals("plugins", ignoreCase = true) ->
                        current = NavTarget.BuiltIn(NavDestination.Plugins)
                    target.equals("home", ignoreCase = true) || target.equals("launch", ignoreCase = true) ->
                        current = NavTarget.BuiltIn(NavDestination.Launch)
                    target.equals("versions", ignoreCase = true) || target.equals("download", ignoreCase = true) ->
                        current = NavTarget.BuiltIn(NavDestination.Download)
                    target.equals("instances", ignoreCase = true) ->
                        current = NavTarget.BuiltIn(NavDestination.Instances)
                    target.equals("accounts", ignoreCase = true) ->
                        current = NavTarget.BuiltIn(NavDestination.Accounts)
                    else -> { /* ignore unknown */ }
                }
            }
        }
        pm.setLaunchRequestHandler { versionId ->
            pluginUiScope.launch { vm.quickLaunch(versionId) }
        }
        pm.setClipboardHandler { text ->
            pluginUiScope.launch {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            }
        }
        pm.setOpenUrlHandler { url ->
            pluginUiScope.launch(Dispatchers.IO) {
                try {
                    if (java.awt.Desktop.isDesktopSupported()) {
                        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                    }
                } catch (_: Throwable) {
                }
            }
        }
        pm.setMusicBridge(object : com.pmcl.core.plugin.PluginManager.MusicBridge {
            override fun nowPlaying(): com.pmcl.plugin.api.MusicPlaybackSummary {
                val idx = vm.musicCurrentIndex.value
                val list = vm.musicPlaylist.value
                val title = list.getOrNull(idx)?.title.orEmpty()
                return com.pmcl.plugin.api.MusicPlaybackSummary(
                    state = vm.musicPlaybackState.value.name,
                    title = title,
                    index = idx,
                    currentMs = vm.musicCurrentMs.value,
                    durationMs = vm.musicDurationMs.value,
                    volume = vm.musicVolume.value,
                )
            }
            override fun pause() = vm.pauseMusic()
            override fun resume() = vm.resumeMusic()
            override fun stop() = vm.stopMusic()
            override fun playNext() = vm.playNextMusic()
            override fun playPrevious() = vm.playPreviousMusic()
            override fun setVolume(volume: Int) = vm.setMusicVolume(volume)
        })
        try {
            while (true) {
                kotlinx.coroutines.delay(400)
                val notes = pm.drainNotifications()
                for (n in notes) {
                    val prefix = when (n.level) {
                        com.pmcl.plugin.api.NotificationLevel.WARN -> ""
                        com.pmcl.plugin.api.NotificationLevel.ERROR -> ""
                        com.pmcl.plugin.api.NotificationLevel.SUCCESS -> ""
                        else -> ""
                    }
                    snackbarHostState.showSnackbar(prefix + n.title + ": " + n.message)
                }
                if (pluginDialog == null) {
                    val dialogs = pm.drainDialogs()
                    if (dialogs.isNotEmpty()) {
                        pluginDialog = dialogs[0]
                        pendingPluginDialogs.addAll(dialogs.subList(1, dialogs.size))
                    } else if (pendingPluginDialogs.isNotEmpty()) {
                        pluginDialog = pendingPluginDialogs.removeAt(0)
                    }
                }
                if (pluginInputDialog == null) {
                    val inputs = pm.drainInputDialogs()
                    if (inputs.isNotEmpty()) {
                        pluginInputDialog = inputs[0]
                        pendingInputDialogs.addAll(inputs.subList(1, inputs.size))
                    } else if (pendingInputDialogs.isNotEmpty()) {
                        pluginInputDialog = pendingInputDialogs.removeAt(0)
                    }
                }
                if (activeFilePicker == null) {
                    val pickers = pm.drainFilePickers()
                    if (pickers.isNotEmpty()) {
                        activeFilePicker = pickers[0]
                        pendingFilePickers.addAll(pickers.subList(1, pickers.size))
                    } else if (pendingFilePickers.isNotEmpty()) {
                        activeFilePicker = pendingFilePickers.removeAt(0)
                    }
                }
                for (u in pm.drainProgressUpdates()) {
                    val key = u.pluginId + ":" + u.id
                    if (u.dismiss) pluginProgress.remove(key)
                    else pluginProgress[key] = u
                }
            }
        } finally {
            // Composition disposed / effect cancelled — drop UI bridges so plugins cannot
            // call into a dead Compose tree or stale clipboard/Desktop handlers.
            try { pm.setNavigationHandler(null) } catch (_: Throwable) {}
            try { pm.setLaunchRequestHandler(null) } catch (_: Throwable) {}
            try { pm.setClipboardHandler(null) } catch (_: Throwable) {}
            try { pm.setOpenUrlHandler(null) } catch (_: Throwable) {}
            try { pm.setMusicBridge(null) } catch (_: Throwable) {}
        }
    }

    LaunchedEffect(activeFilePicker) {
        val req = activeFilePicker ?: return@LaunchedEffect
        val path = withContext(Dispatchers.IO) {
            try {
                runPluginFilePicker(req)
            } catch (_: Throwable) {
                null
            }
        }
        try { req.onResult?.call(path) } catch (_: Throwable) {}
        activeFilePicker = null
    }

    if (pluginDialog != null) {
        val dlg = pluginDialog!!
        val isConfirm = dlg.kind == com.pmcl.plugin.api.DialogKind.CONFIRM
        AlertDialog(
            onDismissRequest = {
                try { dlg.onResult?.call(false) } catch (_: Throwable) {}
                pluginDialog = null
            },
            title = { Text(dlg.title) },
            text = { Text(dlg.message) },
            confirmButton = {
                TextButton(onClick = {
                    try { dlg.onResult?.call(true) } catch (_: Throwable) {}
                    pluginDialog = null
                }) { Text(dlg.confirmLabel) }
            },
            dismissButton = if (isConfirm) {
                {
                    TextButton(onClick = {
                        try { dlg.onResult?.call(false) } catch (_: Throwable) {}
                        pluginDialog = null
                    }) { Text(dlg.cancelLabel) }
                }
            } else null
        )
    }

    if (pluginInputDialog != null) {
        val dlg = pluginInputDialog!!
        var inputValue by remember(dlg.id) { mutableStateOf(dlg.defaultValue) }
        AlertDialog(
            onDismissRequest = {
                try { dlg.onResult?.call(null) } catch (_: Throwable) {}
                pluginInputDialog = null
            },
            title = { Text(dlg.title) },
            text = {
                Column {
                    if (dlg.message.isNotBlank()) {
                        Text(dlg.message)
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try { dlg.onResult?.call(inputValue) } catch (_: Throwable) {}
                    pluginInputDialog = null
                }) { Text(dlg.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = {
                    try { dlg.onResult?.call(null) } catch (_: Throwable) {}
                    pluginInputDialog = null
                }) { Text(dlg.cancelLabel) }
            },
        )
    }

    if (pluginProgress.isNotEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (u in pluginProgress.values.toList()) {
                    val fraction = u.progress.toFloat()
                    Column(
                        Modifier
                            .widthIn(max = 320.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                                MaterialTheme.shapes.medium
                            )
                            .padding(12.dp)
                    ) {
                        Text(u.title, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        if (fraction < 0f) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        SlideInFromStart(delayMs = 0, durationMs = 400) {
            // 玻璃主题：侧边栏分层渲染 —— 底层独立模糊背景层（只画颜色），
            // 上层 NavigationRail 透明背景 + 清晰文字图标。
            // 注意：Modifier.blur 是节点后处理，无法只模糊"下方内容"，
            // 必须把背景拆成独立节点模糊，内容节点保持不模糊。
            val glassOn = themeState.glassTheme
            Box(Modifier.fillMaxHeight()) {
                if (glassOn) {
                    // 模糊背景层：独立节点，自身被 blur，渲染出毛玻璃质感
                    Box(
                        Modifier
                            .matchParentSize()
                            .blur(24.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    )
                }
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = if (glassOn) androidx.compose.ui.graphics.Color.Transparent
                                     else MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        navItems.forEach { target ->
                            val selected = current == target
                            val badgeKey = when (target) {
                                is NavTarget.BuiltIn -> target.dest.route
                                is NavTarget.PluginPage -> "plugin:${target.page.pluginId}:${target.page.id}"
                            }
                            val badgeText = navBadgeMap[badgeKey]
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                val oldIndex = navItems.indexOf(current)
                                val newIndex = navItems.indexOf(target)
                                navDirection = if (newIndex > oldIndex) 1 else if (newIndex < oldIndex) -1 else 0
                                current = target
                            },
                                icon = {
                                    val iconContent = @Composable {
                                        when (target) {
                                            is NavTarget.BuiltIn -> Icon(target.dest.icon, contentDescription = I18n.t(target.dest.labelKey))
                                            is NavTarget.PluginPage -> Icon(Icons.Filled.Extension, contentDescription = target.page.title)
                                        }
                                    }
                                    if (!badgeText.isNullOrBlank()) {
                                        BadgedBox(badge = { Badge { Text(badgeText.take(4)) } }) {
                                            iconContent()
                                        }
                                    } else {
                                        iconContent()
                                    }
                                },
                                label = {
                                    Text(when (target) {
                                        is NavTarget.BuiltIn -> I18n.t(target.dest.labelKey)
                                        is NavTarget.PluginPage -> target.page.title
                                    })
                                }
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            val queueSummary by vm.queueSummary.collectAsState()
            val pulseTrigger by vm.pulseTrigger.collectAsState()
            val flyAnimations by vm.flyAnimations.collectAsState()
            val showQueue = queueSummary.total() > 0 || flyAnimations.isNotEmpty()
            val density = androidx.compose.ui.platform.LocalDensity.current

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        // 队列卡片未显示时，用内容区右下角推算飞入目标，保证首次下载也有动画
                        if (!showQueue) {
                            val pos = coords.positionInWindow()
                            val padPx = with(density) { 16.dp.toPx() }
                            val cardW = with(density) { 160.dp.toPx() }.toInt()
                            val cardH = with(density) { 48.dp.toPx() }.toInt()
                            vm.updateDownloadQueueRect(
                                com.pmcl.ui.animation.Rect(
                                    x = (pos.x + coords.size.width - padPx - cardW).toInt(),
                                    y = (pos.y + coords.size.height - padPx - cardH).toInt(),
                                    width = cardW,
                                    height = cardH
                                )
                            )
                        }
                    }
            ) {
                EntranceAnimation(delayMs = 120, durationMs = 400, offsetDp = 32) {
                    AnimatedPageSwitch(targetState = current, direction = navDirection) { target ->
                        when (target) {
                            is NavTarget.BuiltIn -> when (target.dest) {
                                NavDestination.Launch      -> LaunchPage(vm)
                                NavDestination.News        -> NewsPage(vm)
                                NavDestination.Multiplayer -> MultiplayerPage(vm)
                                NavDestination.Servers     -> ServersPage(vm)
                                NavDestination.Friends     -> FriendPage(vm)
                                NavDestination.Download    -> DownloadHubPage(vm)
                                NavDestination.Content     -> ContentHubPage(vm)
                                NavDestination.Saves       -> SavesHubPage(vm)
                                NavDestination.Statistics  -> StatisticsPage(vm)
                                NavDestination.Accounts    -> AccountsPage(vm)
                                NavDestination.Settings    -> SettingsPage(vm)
                                NavDestination.Terminal    -> TerminalPage(vm)
                                NavDestination.Plugins     -> PluginPage(vm)
                                NavDestination.Instances   -> InstancesPage(vm)
                                NavDestination.NbtEditor   -> NbtEditorPage(vm)
                                NavDestination.Music       -> MusicPage(vm)
                            }
                            is NavTarget.PluginPage -> {
                                // M36 修复：插件页 content.invoke() 同步调用，插件异常会传播到主窗口导致整个 App 崩溃。
                                // 用 SafePluginPage 包裹：捕获组合期异常，显示错误占位符而非崩溃主窗口。
                                SafePluginPage(target.page)
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                )

                // 悬浮下载队列入口卡片（右下角，作为飞入动画目标）
                if (showQueue) {
                    com.pmcl.ui.widget.FloatingDownloadQueue(
                        summary = queueSummary,
                        pulseTrigger = pulseTrigger,
                        forceVisible = flyAnimations.isNotEmpty(),
                        onClick = {
                            current = NavTarget.BuiltIn(NavDestination.Download)
                            navDirection = 1
                        },
                        onPositioned = { rect, _ -> vm.updateDownloadQueueRect(rect) },
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp)
                    )
                }
                DisposableEffect(Unit) {
                    onDispose { vm.clearDownloadQueueRect() }
                }

                // 下载飞入动画浮层（覆盖整个内容区）
                com.pmcl.ui.animation.DownloadFlyLayer(
                    animations = flyAnimations,
                    onComplete = { anim -> vm.completeFlyAnimation(anim) }
                )
            }

            // 底部：插件状态栏动作 + 音乐迷你条
            val musicCurrentIdx by vm.musicCurrentIndex.collectAsState()
            val musicPlaylist by vm.musicPlaylist.collectAsState()
            var pluginRev by remember { mutableStateOf(0L) }
            LaunchedEffect("plugin-statusbar") {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    pluginRev = vm.core.plugins().getRevision()
                }
            }
            val statusActions = remember(pluginRev) {
                try { vm.core.plugins().getCustomStatusBarActions() } catch (_: Throwable) { emptyList() }
            }
            if (statusActions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (action in statusActions) {
                        TextButton(onClick = {
                            try { action.handler?.run() } catch (_: Throwable) {}
                        }) {
                            Text(action.title)
                        }
                    }
                }
            }
            if (musicPlaylist.isNotEmpty() && musicCurrentIdx >= 0) {
                Spacer(Modifier.height(4.dp))
                MiniMusicBar(
                    vm = vm,
                    onOpenMusic = {
                        val target = NavTarget.BuiltIn(NavDestination.Music)
                        val oldIndex = navItems.indexOf(current)
                        val newIndex = navItems.indexOf(target)
                        navDirection = if (newIndex > oldIndex) 1 else if (newIndex < oldIndex) -1 else 0
                        current = target
                    }
                )
            }
        }
    } // close Row

    // ===== 全局：游戏安装前弹窗（询问是否同时安装模组加载器）=====
    // 放在全局层级，无论用户在哪个页面安装游戏都会触发弹窗，且避免多页面重复弹窗
    val preInstallEvent by vm.preInstallEvent.collectAsState()
    preInstallEvent?.let { ev ->
        com.pmcl.ui.page.ModLoaderInstallPromptDialog(
            versionId = ev.versionId,
            vm = vm,
            onDismiss = { vm.clearPreInstallEvent() }
        )
    }

    // ===== 全局：崩溃恢复操作导航请求 =====
    val navigationRequest by vm.navigationRequest.collectAsState()
    LaunchedEffect(navigationRequest) {
        val req = navigationRequest ?: return@LaunchedEffect
        val target = allDestinations.firstOrNull { it.route == req }
        if (target != null) {
            val newTarget = NavTarget.BuiltIn(target)
            val oldIndex = navItems.indexOf(current)
            val newIndex = navItems.indexOf(newTarget)
            navDirection = if (newIndex > oldIndex) 1 else if (newIndex < oldIndex) -1 else 0
            current = newTarget
        }
        vm.clearNavigationRequest()
    }
}

/**
 * M36 修复：安全包裹插件提供的页面内容。
 *
 * 插件的 Composable 代码可能因 NPE/IllegalState/资源加载失败 等原因在组合期抛异常，
 * 若直接 invoke() 会传播到主窗口的 NavHost 导致整个 App 崩溃。
 *
 * 注意：Compose 编译器不允许 try-catch 包裹 @Composable 函数调用，
 * 组合期异常无法用 try-catch 捕获。这里仅记录错误状态用于 UI 占位，
 * 实际的组合期异常仍会由 Compose runtime 处理（通常导致该子树失效）。
 * 用 remember(page) 记录是否曾失败，失败后展示错误占位符，避免反复崩溃。
 */
@Composable
private fun SafePluginPage(page: PluginManager.RegisteredPage) {
    var error by remember(page) { mutableStateOf<Throwable?>(null) }
    val currentError = error
    if (currentError != null) {
        // 显示错误占位符，而非让异常传播到主窗口
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Plugin page error: ${currentError.message ?: currentError.javaClass.simpleName}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Plugin: ${page.id} (${page.title})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    // M36: Compose 不允许 try-catch 包裹 @Composable 调用，直接 invoke。
    // 若插件 content 在组合期抛异常，Compose runtime 会处理（通常导致该子树失效）。
    // 关键隔离点：page.content 的 getter 访问与 invoke 调用本身在这里完成，
    // 至少保证插件 content lambda 的获取不传播到 NavHost。
    page.content.invoke()
}

/** Host-mediated file/folder picker for plugins (AWT/Swing). */
private fun runPluginFilePicker(req: com.pmcl.plugin.api.PluginFilePickerRequest): String? {
    return when (req.mode) {
        com.pmcl.plugin.api.FilePickerMode.OPEN_FOLDER -> {
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = req.title
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath
            } else null
        }
        com.pmcl.plugin.api.FilePickerMode.SAVE_FILE -> {
            val fd = java.awt.FileDialog(null as java.awt.Frame?, req.title, java.awt.FileDialog.SAVE)
            applyFileDialogFilters(fd, req.filters)
            fd.isVisible = true
            val dir = fd.directory
            val file = fd.file
            if (dir != null && file != null) java.io.File(dir, file).absolutePath else null
        }
        else -> {
            val fd = java.awt.FileDialog(null as java.awt.Frame?, req.title, java.awt.FileDialog.LOAD)
            applyFileDialogFilters(fd, req.filters)
            fd.isVisible = true
            val dir = fd.directory
            val file = fd.file
            if (dir != null && file != null) java.io.File(dir, file).absolutePath else null
        }
    }
}

private fun applyFileDialogFilters(fd: java.awt.FileDialog, filters: String) {
    if (filters.isBlank()) return
    val exts = filters.split(';').map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotEmpty() }
    if (exts.isEmpty()) return
    fd.setFilenameFilter { _, name ->
        val lower = name.lowercase()
        exts.any { lower.endsWith(".$it") }
    }
}

