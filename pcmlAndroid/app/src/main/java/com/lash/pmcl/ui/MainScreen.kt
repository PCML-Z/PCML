package com.lash.pmcl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.ui.animation.AnimatedPageSwitch
import com.lash.pmcl.ui.animation.DownloadFlyLayer
import com.lash.pmcl.ui.animation.DownloadFlyState
import com.lash.pmcl.ui.animation.EntranceAnimation
import com.lash.pmcl.ui.animation.Rect
import com.lash.pmcl.ui.animation.SlideInFromStart
import com.lash.pmcl.ui.page.ContentHubPage
import com.lash.pmcl.ui.page.DownloadHubPage
import com.lash.pmcl.ui.page.SavesHubPage
import com.lash.pmcl.ui.screens.AccountsScreen
import com.lash.pmcl.ui.screens.CommandPaletteScreen
import com.lash.pmcl.ui.screens.InstancesScreen
import com.lash.pmcl.ui.screens.LaunchScreen
import com.lash.pmcl.ui.screens.MusicScreen
import com.lash.pmcl.ui.screens.MultiplayerScreen
import com.lash.pmcl.ui.screens.FriendScreen
import com.lash.pmcl.ui.screens.PluginScreen
import com.lash.pmcl.ui.screens.NbtEditorScreen
import com.lash.pmcl.ui.screens.NewsScreen
import com.lash.pmcl.ui.screens.ServersScreen
import com.lash.pmcl.ui.screens.SettingsScreen
import com.lash.pmcl.ui.screens.StatisticsScreen
import com.lash.pmcl.ui.screens.TerminalScreen
import com.lash.pmcl.ui.theme.LocalThemeState
import com.lash.pmcl.ui.widget.FloatingDownloadQueue

/**
 * 导航目标：与桌面端 NavDestination 完全一致。
 */
private sealed class NavTarget {
    abstract val label: String
    abstract val icon: ImageVector

    data object Launch : NavTarget() {
        override val label = "启动"
        override val icon = Icons.Filled.PlayArrow
    }
    data object News : NavTarget() {
        override val label = "新闻"
        override val icon = Icons.Filled.Info
    }
    data object Servers : NavTarget() {
        override val label = "服务器"
        override val icon = Icons.Filled.Dns
    }
    data object Download : NavTarget() {
        override val label = "下载"
        override val icon = Icons.Filled.Build
    }
    data object Content : NavTarget() {
        override val label = "内容"
        override val icon = Icons.Filled.Star
    }
    data object Saves : NavTarget() {
        override val label = "存档"
        override val icon = Icons.Filled.Search
    }
    data object Instances : NavTarget() {
        override val label = "实例"
        override val icon = Icons.Filled.Dashboard
    }
    data object Statistics : NavTarget() {
        override val label = "统计"
        override val icon = Icons.Filled.BarChart
    }
    data object Accounts : NavTarget() {
        override val label = "账号"
        override val icon = Icons.Filled.Person
    }
    data object Settings : NavTarget() {
        override val label = "设置"
        override val icon = Icons.Filled.Settings
    }
    data object NbtEditor : NavTarget() {
        override val label = "NBT"
        override val icon = Icons.Filled.AccountTree
    }
    data object Terminal : NavTarget() {
        override val label = "控制台"
        override val icon = Icons.Filled.Dns
    }
    data object Music : NavTarget() {
        override val label = "音乐"
        override val icon = Icons.Filled.Star
    }
    data object Multiplayer : NavTarget() {
        override val label = "联机"
        override val icon = Icons.Filled.Search
    }
    data object Friend : NavTarget() {
        override val label = "好友"
        override val icon = Icons.Filled.Person
    }
    data object Plugin : NavTarget() {
        override val label = "插件"
        override val icon = Icons.Filled.Build
    }
}

private val navItems = listOf<NavTarget>(
    NavTarget.Launch,
    NavTarget.News,
    NavTarget.Servers,
    NavTarget.Download,
    NavTarget.Content,
    NavTarget.Saves,
    NavTarget.Instances,
    NavTarget.Statistics,
    NavTarget.Accounts,
    NavTarget.Settings,
    NavTarget.NbtEditor,
    NavTarget.Terminal,
    NavTarget.Music,
)

@Composable
fun MainScreen(
    core: LauncherCore,
    appVersion: String,
) {
    val themeState = LocalThemeState.current
    var current by remember { mutableStateOf<NavTarget>(NavTarget.Launch) }
    var navDirection by remember { mutableIntStateOf(0) }

    // 下载队列状态（使用 core 层的 DownloadQueueState）
    var queueRect by remember { mutableStateOf<Rect?>(null) }
    var queueSize by remember { mutableStateOf(IntSize.Zero) }
    var flyAnimations by remember { mutableStateOf<List<DownloadFlyState>>(emptyList()) }
    var pulseTrigger by remember { mutableIntStateOf(0) }

    // 定时轮询下载状态
    var queueActive by remember { mutableIntStateOf(0) }
    var queueTotal by remember { mutableIntStateOf(0) }
    var queueProgress by remember { mutableFloatStateOf(0f) }
    var showCommandPalette by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            queueActive = com.lash.pmcl.core.download.DownloadQueueState.activeCount()
            queueTotal = com.lash.pmcl.core.download.DownloadQueueState.totalCount()
            queueProgress = com.lash.pmcl.core.download.DownloadQueueState.overallProgress()
            kotlinx.coroutines.delay(1000)
        }
    }
    val showQueue = queueTotal > 0

    Row(Modifier.fillMaxSize()) {
        // 侧边栏：SlideInFromStart + 玻璃主题
        SlideInFromStart(delayMs = 0, durationMs = 400) {
            val glassOn = themeState.glassTheme
            Box(Modifier.fillMaxHeight().let { if (glassOn) it.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)) else it }) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = if (glassOn)
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NavigationRailItem(
                            selected = false,
                            onClick = { showCommandPalette = true },
                            icon = { Icon(Icons.Filled.Search, "搜索") },
                            label = { Text("搜索") }
                        )
                        navItems.forEachIndexed { _, target ->
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
                                    Icon(target.icon, contentDescription = target.label)
                                },
                                label = { Text(target.label) }
                            )
                        }
                    }
                }
            }
        }

        // 内容区
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val show = showQueue || flyAnimations.isNotEmpty()
            val density = androidx.compose.ui.platform.LocalDensity.current

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        if (!show) {
                            val pos = coords.positionInWindow()
                            val padPx = with(density) { 16.dp.toPx() }
                            val cardW = with(density) { 160.dp.toPx() }.toInt()
                            val cardH = with(density) { 48.dp.toPx() }.toInt()
                            queueRect = Rect(
                                x = (pos.x + coords.size.width - padPx - cardW).toInt(),
                                y = (pos.y + coords.size.height - padPx - cardH).toInt(),
                                width = cardW,
                                height = cardH
                            )
                        }
                    }
            ) {
                EntranceAnimation(delayMs = 120, durationMs = 400, offsetDp = 32) {
                    AnimatedPageSwitch(targetState = current, direction = navDirection) { target ->
                        when (target) {
                            NavTarget.Launch -> LaunchScreen(
                                authService = core.authService,
                                launchManager = core.launchManager,
                                versionManager = core.versionManager,
                                preferences = core.preferences,
                                versionInstaller = core.versionInstaller,
                            )
                            NavTarget.News -> NewsScreen(newsClient = core.newsClient)
                            NavTarget.Servers -> ServersScreen(serverPinger = core.serverPinger)
                            NavTarget.Download -> DownloadHubPage(core)
                            NavTarget.Content -> ContentHubPage(core)
                            NavTarget.Saves -> SavesHubPage(core)
                            NavTarget.Instances -> InstancesScreen(instanceManager = core.instanceManager)
                            NavTarget.Statistics -> StatisticsScreen(playTimeTracker = core.playTimeTracker)
                            NavTarget.Accounts -> AccountsScreen(
                                authService = core.authService,
                                preferences = core.preferences,
                            )
                            NavTarget.Settings -> SettingsScreen(
                                downloadManager = core.downloadManager,
                                preferences = core.preferences,
                                appVersion = appVersion,
                            )
                            NavTarget.NbtEditor -> NbtEditorScreen(worldManager = core.worldManager)
                            NavTarget.Terminal -> TerminalScreen()
                            NavTarget.Music -> MusicScreen()
                            NavTarget.Multiplayer -> MultiplayerScreen()
                            NavTarget.Friend -> FriendScreen()
                            NavTarget.Plugin -> PluginScreen()
                        }
                    }
                }

                // 悬浮下载队列入口卡片（右下角）
                if (showQueue) {
                    FloatingDownloadQueue(
                        summary = com.lash.pmcl.ui.widget.QueueSummary(
                            active = queueActive,
                            total = queueTotal,
                            progress = queueProgress
                        ),
                        pulseTrigger = pulseTrigger,
                        forceVisible = flyAnimations.isNotEmpty(),
                        onClick = {
                            current = NavTarget.Download
                            navDirection = 1
                        },
                        onPositioned = { rect, size ->
                            queueRect = rect
                            queueSize = size
                        },
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp)
                    )
                }

                // 迷你音乐播放条（底部全宽）
                com.lash.pmcl.ui.widget.MiniMusicBar(
                    currentTrack = com.lash.pmcl.ui.screens.MusicState.currentTrack,
                    isPlaying = com.lash.pmcl.ui.screens.MusicState.isPlaying,
                    currentMs = com.lash.pmcl.ui.screens.MusicState.currentMs,
                    durationMs = com.lash.pmcl.ui.screens.MusicState.durationMs,
                    onPlayPause = { },
                    onNext = { },
                    onPrev = { },
                    onOpenMusic = { current = NavTarget.Music },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // 下载飞入动画浮层
                DownloadFlyLayer(
                    animations = flyAnimations,
                    onComplete = { anim ->
                        flyAnimations = flyAnimations.filter { it.id != anim.id }
                        // 触发脉冲
                        pulseTrigger++
                    }
                )
            }
        }
    }

    // 命令面板
    CommandPaletteScreen(
        visible = showCommandPalette,
        onDismiss = { showCommandPalette = false },
        onNavigate = { target ->
            showCommandPalette = false
            when (target) {
                "launch" -> current = NavTarget.Launch
                "news" -> current = NavTarget.News
                "servers" -> current = NavTarget.Servers
                "download" -> current = NavTarget.Download
                "content" -> current = NavTarget.Content
                "saves" -> current = NavTarget.Saves
                "instances" -> current = NavTarget.Instances
                "statistics" -> current = NavTarget.Statistics
                "accounts" -> current = NavTarget.Accounts
                "settings" -> current = NavTarget.Settings
                "terminal" -> current = NavTarget.Terminal
            }
        }
    )

}
