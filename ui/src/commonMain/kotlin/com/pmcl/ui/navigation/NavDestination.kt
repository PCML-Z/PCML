package com.pmcl.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 顶层导航目标（一级侧边栏）。
 *
 * 功能较多的入口钻入二级侧边栏（见 [SecondaryNavRegistry]）：
 * - Settings / Download / Content / Statistics / Multiplayer / Accounts / Saves / Plugins / Music
 */
sealed class NavDestination(val route: String, val labelKey: String, val icon: ImageVector) {
    data object Launch      : NavDestination("launch",      "nav.launch",      PmclIcons.Launch)
    data object News        : NavDestination("news",        "nav.news",        PmclIcons.News)
    data object Tips        : NavDestination("tips",        "nav.tips",        PmclIcons.Tips)
    data object Multiplayer : NavDestination("multiplayer", "nav.multiplayer", PmclIcons.Multiplayer)
    data object Servers     : NavDestination("servers",     "nav.servers",     PmclIcons.Servers)
    data object Friends     : NavDestination("friends",     "nav.friends",     PmclIcons.Friends)
    data object Download    : NavDestination("download",    "nav.download",    PmclIcons.Download)
    data object Content     : NavDestination("content",     "nav.content",     PmclIcons.Content)
    data object Saves       : NavDestination("saves",       "nav.saves",       PmclIcons.Saves)
    data object Statistics  : NavDestination("statistics",  "nav.statistics",  PmclIcons.Statistics)
    data object Accounts    : NavDestination("accounts",    "nav.accounts",    PmclIcons.Accounts)
    data object Settings    : NavDestination("settings",    "nav.settings",    PmclIcons.Settings)
    data object Terminal    : NavDestination("terminal",    "nav.terminal",    PmclIcons.Terminal)
    data object Plugins     : NavDestination("plugins",     "nav.plugins",     PmclIcons.Plugins)
    data object Instances   : NavDestination("instances",   "nav.instances",   PmclIcons.Instances)
    data object NbtEditor   : NavDestination("nbt",         "nav.nbt",         PmclIcons.Nbt)
    data object Music       : NavDestination("music",       "nav.music",       PmclIcons.Music)
}

val allDestinations = listOf(
    NavDestination.Launch,
    NavDestination.News,
    NavDestination.Tips,
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
