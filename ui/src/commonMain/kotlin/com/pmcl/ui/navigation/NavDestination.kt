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
 * 顶层导航目标（一级侧边栏）。
 *
 * 功能较多的入口钻入二级侧边栏（见 [SecondaryNavRegistry]）：
 * - Settings / Download / Content / Statistics / Multiplayer / Accounts / Saves / Plugins / Music
 *
 * 注意：Compose Multiplatform 内置 material-icons 集合有限，仅使用稳定可用的图标。
 */
sealed class NavDestination(val route: String, val labelKey: String, val icon: ImageVector) {
    data object Launch      : NavDestination("launch",      "nav.launch",      Icons.Filled.PlayArrow)
    data object News        : NavDestination("news",        "nav.news",        Icons.Filled.Info)
    data object Multiplayer : NavDestination("multiplayer", "nav.multiplayer", Icons.Filled.Share)
    data object Servers     : NavDestination("servers",     "nav.servers",     Icons.Filled.Dns)
    data object Friends     : NavDestination("friends",     "nav.friends",     Icons.Filled.People)
    data object Download    : NavDestination("download",    "nav.download",    Icons.Filled.Build)
    data object Content     : NavDestination("content",     "nav.content",     Icons.Filled.Star)
    data object Saves       : NavDestination("saves",       "nav.saves",       Icons.Filled.Search)
    data object Statistics  : NavDestination("statistics",  "nav.statistics",  Icons.Filled.BarChart)
    data object Accounts    : NavDestination("accounts",    "nav.accounts",    Icons.Filled.Person)
    data object Settings    : NavDestination("settings",    "nav.settings",    Icons.Filled.Settings)
    data object Terminal    : NavDestination("terminal",    "nav.terminal",    Icons.Filled.Terminal)
    data object Plugins     : NavDestination("plugins",     "nav.plugins",     Icons.Filled.Extension)
    data object Instances   : NavDestination("instances",   "nav.instances",   Icons.Filled.Dashboard)
    data object NbtEditor   : NavDestination("nbt",         "nav.nbt",         Icons.Filled.AccountTree)
    data object Music       : NavDestination("music",       "nav.music",       Icons.Filled.MusicNote)
}

val allDestinations = listOf(
    NavDestination.Launch,
    NavDestination.News,
    NavDestination.Multiplayer,
    NavDestination.Servers,
    NavDestination.Friends,
    NavDestination.Download,
    NavDestination.Content,
    NavDestination.Saves,
    NavDestination.Statistics,
    NavDestination.Accounts,
    NavDestination.Settings,
    NavDestination.Terminal,
    NavDestination.Plugins,
    NavDestination.Instances,
    NavDestination.NbtEditor,
    NavDestination.Music
)
