package com.pmcl.ui

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
import com.pmcl.ui.page.NbtEditorPage
import com.pmcl.ui.page.MultiplayerPage
import com.pmcl.ui.page.FriendPage
import com.pmcl.ui.page.NewsPage
import com.pmcl.ui.page.PluginPage
import com.pmcl.ui.page.QuickLaunchPage
import com.pmcl.ui.page.SavesHubPage
import com.pmcl.ui.page.SettingsPage
import com.pmcl.ui.page.StatisticsPage
import com.pmcl.ui.page.TerminalPage
import com.pmcl.ui.page.WelcomePage
import com.pmcl.ui.theme.LauncherTheme
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.ThemeState
import com.pmcl.ui.viewmodel.LauncherViewModel

@Composable
fun App(vm: LauncherViewModel) {
    val themeState = remember { ThemeState(initialDark = vm.preferences.isUseDarkTheme()) }

    // 直接把 vm.themeState 设为 themeState，让 ViewModel 的 refreshWallpaperColor 能更新它
    SideEffect {
        vm.themeState = themeState
    }

    // 启动时初始化动态颜色 + UI 缩放
    LaunchedEffect(Unit) {
        val customColor = vm.preferences.getCustomAccentColor()
        if (vm.preferences.isDynamicColor()) {
            // 莫奈取色模式
            themeState.enableDynamicColor(true)
            vm.refreshWallpaperColor(themeState)
        } else if (customColor != -1) {
            // 自定义强调色模式
            themeState.applyCustomAccentColor(customColor)
            val dark = vm.preferences.isUseDarkTheme()
            themeState.applySeedColor(customColor, dark)
        }
        // 应用 UI 缩放
        themeState.applyUiScale(vm.preferences.getUiScale())
        // 应用视差背景 / 玻璃主题初始状态
        themeState.applyParallaxBackground(vm.preferences.isParallaxBackground())
        themeState.applyGlassTheme(vm.preferences.isGlassTheme())
    }

    // 直接读取 themeState 的属性，Compose 会自动观察 mutableStateOf 的变化
    // 莫奈取色或自定义强调色时使用 dynamicColorScheme
    val effectiveScheme: androidx.compose.material3.ColorScheme? =
        if (themeState.dynamicColor || themeState.customAccentColor != -1) themeState.dynamicColorScheme else null

    LauncherTheme(
        useDarkTheme = themeState.useDark,
        dynamicColorScheme = effectiveScheme,
        uiScale = themeState.uiScale
    ) {
        CompositionLocalProvider(LocalThemeState provides themeState) {
            // 视差背景开启时 Surface 透明，让 Main.kt 的 ParallaxBackground 透出
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (themeState.parallaxBackground) androidx.compose.ui.graphics.Color.Transparent
                        else MaterialTheme.colorScheme.surface,
                tonalElevation = if (themeState.parallaxBackground) 0.dp else 1.dp
            ) {
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
                        QuickLaunchPage(
                            vm = vm,
                            onEnterMain = { enteredMain = true }
                        )
                    } else {
                        MainWindowContent(vm)
                    }
                }
            }
        }
    }
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
    var lastRevision by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) {
        // 延迟 1.5 秒加载插件，让首屏 UI 先完成渲染（插件加载涉及 JAR 读取+ClassLoader+反射，开销大）
        kotlinx.coroutines.delay(1500)
        try {
            vm.core.plugins().discoverAndLoadAll()
        } catch (e: Throwable) {
            // Non-fatal: plugins are optional
        }
        pluginPages = vm.core.plugins().getCustomPages()
        lastRevision = vm.core.plugins().getRevision()
    }
    // Poll for plugin changes (install/uninstall/enable/disable via terminal)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val rev = vm.core.plugins().getRevision()
            if (rev != lastRevision) {
                lastRevision = rev
                pluginPages = vm.core.plugins().getCustomPages()
            }
        }
    }

    // Build full nav list: built-in destinations + plugin pages
    val navItems = remember(pluginPages) {
        val builtIn = allDestinations.map { NavTarget.BuiltIn(it) }
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

    Row(Modifier.fillMaxSize()) {
        SlideInFromStart(delayMs = 0, durationMs = 400) {
            // 玻璃主题：侧边栏应用毛玻璃 + 半透明，搭配视差背景产生层次感
            val glassOn = themeState.glassTheme
            val railModifier = Modifier
                .fillMaxHeight()
                .then(if (glassOn) Modifier.blur(20.dp) else Modifier)
            NavigationRail(
                modifier = railModifier,
                containerColor = if (glassOn)
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
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
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                            val oldIndex = navItems.indexOf(current)
                            val newIndex = navItems.indexOf(target)
                            navDirection = if (newIndex > oldIndex) 1 else if (newIndex < oldIndex) -1 else 0
                            current = target
                        },
                            icon = {
                                when (target) {
                                    is NavTarget.BuiltIn -> Icon(target.dest.icon, contentDescription = I18n.t(target.dest.labelKey))
                                    is NavTarget.PluginPage -> Icon(Icons.Filled.Extension, contentDescription = target.page.title)
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

        Box(Modifier.weight(1f).fillMaxHeight()) {
            EntranceAnimation(delayMs = 120, durationMs = 400, offsetDp = 32) {
                AnimatedPageSwitch(targetState = current, direction = navDirection) { target ->
                    when (target) {
                        is NavTarget.BuiltIn -> when (target.dest) {
                            NavDestination.Launch      -> LaunchPage(vm)
                            NavDestination.News        -> NewsPage(vm)
                            NavDestination.Multiplayer -> MultiplayerPage(vm)
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
                        }
                        is NavTarget.PluginPage -> {
                            target.page.content.invoke()
                        }
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
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
        // 匹配目标页面
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
