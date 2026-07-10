package com.pmcl.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 顶层导航目标（侧边栏）。
 *
 * 整合后从 10 项精简为 8 项，原细分页面通过 Hub 页面内的 Tab 切换：
 * - Download: 版本安装 / 模组市场 / Wiki
 * - Content:  模组 / 光影包 / 资源包
 * - Saves:    世界 / 截图
 *
 * 注意：Compose Multiplatform 内置 material-icons 集合有限，仅使用稳定可用的图标。
 */
sealed class NavDestination(val route: String, val label: String, val icon: ImageVector) {
    data object Launch      : NavDestination("launch",      "启动",   Icons.Filled.PlayArrow)
    data object News        : NavDestination("news",        "新闻",   Icons.Filled.Info)
    data object Multiplayer : NavDestination("multiplayer", "联机",   Icons.Filled.Share)
    data object Download    : NavDestination("download",    "下载",   Icons.Filled.Build)
    data object Content     : NavDestination("content",     "内容",   Icons.Filled.Star)
    data object Saves       : NavDestination("saves",       "存档",   Icons.Filled.Search)
    data object Accounts    : NavDestination("accounts",    "账号",   Icons.Filled.Person)
    data object Settings    : NavDestination("settings",    "设置",   Icons.Filled.Settings)
    data object Terminal    : NavDestination("terminal",    "终端",   Icons.Filled.Terminal)
    data object Plugins     : NavDestination("plugins",     "插件",   Icons.Filled.Extension)
}

val allDestinations = listOf(
    NavDestination.Launch,
    NavDestination.News,
    NavDestination.Multiplayer,
    NavDestination.Download,
    NavDestination.Content,
    NavDestination.Saves,
    NavDestination.Accounts,
    NavDestination.Settings,
    NavDestination.Terminal,
    NavDestination.Plugins
)
