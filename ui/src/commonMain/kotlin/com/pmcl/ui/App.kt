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
import androidx.compose.ui.unit.dp
import com.pmcl.core.plugin.PluginManager
import com.pmcl.ui.animation.AnimatedPageSwitch
import com.pmcl.ui.animation.EntranceAnimation
import com.pmcl.ui.animation.SlideInFromStart
import com.pmcl.ui.navigation.NavDestination
import com.pmcl.ui.navigation.allDestinations
import com.pmcl.ui.page.AccountsPage
import com.pmcl.ui.page.ContentHubPage
import com.pmcl.ui.page.DownloadHubPage
import com.pmcl.ui.page.LaunchPage
import com.pmcl.ui.page.MultiplayerPage
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
fun App() {
    val vm = remember { LauncherViewModel() }
    val themeState = remember { ThemeState(initialDark = vm.preferences.isUseDarkTheme()) }

    // 直接把 vm.themeState 设为 themeState，让 ViewModel 的 refreshWallpaperColor 能更新它
    SideEffect {
        vm.themeState = themeState
    }

    // 启动时初始化动态颜色
    LaunchedEffect(Unit) {
        themeState.enableDynamicColor(vm.preferences.isDynamicColor())
        if (vm.preferences.isDynamicColor()) {
            vm.refreshWallpaperColor(themeState)
        }
    }

    // 直接读取 themeState 的属性，Compose 会自动观察 mutableStateOf 的变化
    val effectiveScheme: androidx.compose.material3.ColorScheme? =
        if (themeState.dynamicColor) themeState.dynamicColorScheme else null

    LauncherTheme(
        useDarkTheme = themeState.useDark,
        dynamicColorScheme = effectiveScheme
    ) {
        CompositionLocalProvider(LocalThemeState provides themeState) {
            Surface(modifier = Modifier.fillMaxSize()) {
                val firstLaunchDone by vm.firstLaunchCompleted.collectAsState()

                if (!firstLaunchDone) {
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
    abstract val label: String
    data class BuiltIn(val dest: NavDestination) : NavTarget() {
        override val label = dest.label
    }
    data class PluginPage(val page: PluginManager.RegisteredPage) : NavTarget() {
        override val label = page.title
    }
}

@Composable
private fun MainWindowContent(vm: LauncherViewModel) {
    var current by remember { mutableStateOf<NavTarget>(NavTarget.BuiltIn(NavDestination.Launch)) }

    // Collect plugin-provided pages (refreshable via revision polling)
    var pluginPages by remember { mutableStateOf<List<PluginManager.RegisteredPage>>(emptyList()) }
    var lastRevision by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) {
        // Discover and load plugins on startup
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

    Row(Modifier.fillMaxSize()) {
        SlideInFromStart(delayMs = 0, durationMs = 400) {
            NavigationRail {
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
                            onClick = { current = target },
                            icon = {
                                when (target) {
                                    is NavTarget.BuiltIn -> Icon(target.dest.icon, contentDescription = target.label)
                                    is NavTarget.PluginPage -> Icon(Icons.Filled.Extension, contentDescription = target.label)
                                }
                            },
                            label = { Text(target.label) }
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            EntranceAnimation(delayMs = 120, durationMs = 400, offsetDp = 32) {
                AnimatedPageSwitch(targetState = current) { target ->
                    when (target) {
                        is NavTarget.BuiltIn -> when (target.dest) {
                            NavDestination.Launch      -> LaunchPage(vm)
                            NavDestination.News        -> NewsPage(vm)
                            NavDestination.Multiplayer -> MultiplayerPage(vm)
                            NavDestination.Download    -> DownloadHubPage(vm)
                            NavDestination.Content     -> ContentHubPage(vm)
                            NavDestination.Saves       -> SavesHubPage(vm)
                            NavDestination.Statistics  -> StatisticsPage(vm)
                            NavDestination.Accounts    -> AccountsPage(vm)
                            NavDestination.Settings    -> SettingsPage(vm)
                            NavDestination.Terminal    -> TerminalPage(vm)
                            NavDestination.Plugins     -> PluginPage(vm)
                        }
                        is NavTarget.PluginPage -> {
                            // Render the plugin's composable content
                            target.page.content.invoke()
                        }
                    }
                }
            }
        }
    }

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
}
