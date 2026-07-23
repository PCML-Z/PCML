package com.pmcl.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
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
 * 导航分为 4 个分区：核心区常驻，其他区可折叠/隐藏。
 *
 * 注意：Compose Multiplatform 内置 material-icons 集合有限，仅使用稳定可用的图标。
 */
sealed class NavDestination(
    val route: String,
    val labelKey: String,
    val icon: ImageVector,
    val group: NavGroup = NavGroup.CORE
) {
    // ===== 核心区（常驻，不可隐藏）=====
    data object Launch      : NavDestination("launch",      "nav.launch",      Icons.Filled.PlayArrow,   NavGroup.CORE)
    data object Download    : NavDestination("download",    "nav.download",    Icons.Filled.Build,       NavGroup.CORE)
    data object Content     : NavDestination("content",     "nav.content",     Icons.Filled.Star,        NavGroup.CORE)
    data object Instances   : NavDestination("instances",   "nav.instances",   Icons.Filled.Dashboard,   NavGroup.CORE)
    data object Accounts    : NavDestination("accounts",    "nav.accounts",    Icons.Filled.Person,      NavGroup.CORE)
    data object Settings    : NavDestination("settings",    "nav.settings",    Icons.Filled.Settings,    NavGroup.CORE)

    // ===== 社区区（可折叠/隐藏）=====
    data object News        : NavDestination("news",        "nav.news",        Icons.Filled.Info,        NavGroup.COMMUNITY)
    data object Multiplayer : NavDestination("multiplayer", "nav.multiplayer", Icons.Filled.Share,       NavGroup.COMMUNITY)
    data object Servers     : NavDestination("servers",     "nav.servers",     Icons.Filled.Dns,         NavGroup.COMMUNITY)
    data object Friends     : NavDestination("friends",     "nav.friends",     Icons.Filled.People,      NavGroup.COMMUNITY)

    // ===== 娱乐区（可折叠/隐藏）=====
    data object Statistics  : NavDestination("statistics",  "nav.statistics",  Icons.Filled.BarChart,    NavGroup.ENTERTAINMENT)
    data object Music       : NavDestination("music",       "nav.music",       Icons.Filled.MusicNote,   NavGroup.ENTERTAINMENT)

    // ===== 工具区（可折叠/隐藏）=====
    data object Saves       : NavDestination("saves",       "nav.saves",       Icons.Filled.Search,      NavGroup.TOOLS)
    data object Terminal    : NavDestination("terminal",    "nav.terminal",    Icons.Filled.Terminal,    NavGroup.TOOLS)
    data object Plugins     : NavDestination("plugins",     "nav.plugins",     Icons.Filled.Extension,   NavGroup.TOOLS)
    data object NbtEditor   : NavDestination("nbt",         "nav.nbt",         Icons.Filled.AccountTree, NavGroup.TOOLS)
}

/**
 * 导航分区。核心区常驻显示，其他区可折叠。
 * 分区顺序由 [allGroups] 决定，区内条目顺序由 [allDestinations] 决定。
 */
enum class NavGroup(val labelKey: String) {
    CORE("nav.group.core"),
    COMMUNITY("nav.group.community"),
    ENTERTAINMENT("nav.group.entertainment"),
    TOOLS("nav.group.tools");

    /** 是否可折叠：核心区不可折叠 */
    val collapsible: Boolean get() = this != CORE
}

/** 分区显示顺序 */
val allGroups = listOf(NavGroup.CORE, NavGroup.COMMUNITY, NavGroup.ENTERTAINMENT, NavGroup.TOOLS)

/**
 * 全部内置导航项（按分区分组）。
 * 核心区：Launch / Download / Content / Instances / Accounts / Settings
 * 社区区：News / Multiplayer / Servers / Friends
 * 娱乐区：Statistics / Music
 * 工具区：Saves / Terminal / Plugins / NbtEditor
 */
val allDestinations = listOf(
    // 核心区
    NavDestination.Launch,
    NavDestination.Download,
    NavDestination.Content,
    NavDestination.Instances,
    NavDestination.Accounts,
    NavDestination.Settings,
    // 社区区
    NavDestination.News,
    NavDestination.Multiplayer,
    NavDestination.Servers,
    NavDestination.Friends,
    // 娱乐区
    NavDestination.Statistics,
    NavDestination.Music,
    // 工具区
    NavDestination.Saves,
    NavDestination.Terminal,
    NavDestination.Plugins,
    NavDestination.NbtEditor
)

/** 获取指定分区的导航项列表 */
fun destinationsByGroup(group: NavGroup): List<NavDestination> =
    allDestinations.filter { it.group == group }
