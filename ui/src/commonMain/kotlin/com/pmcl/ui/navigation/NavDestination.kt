package com.pmcl.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 顶层导航目标（一级侧边栏）。
 *
 * 功能较多的入口钻入二级侧边栏（见 [SecondaryNavRegistry]）：
 * - Settings / Download / Content / Statistics / Multiplayer / Accounts / Saves / Plugins / Music
 */
sealed class NavDestination(val route: String, val labelKey: String) {
    val icon: ImageVector
        get() = when (this) {
            Launch -> PmclIcons.Launch
            News -> PmclIcons.News
            Tips -> PmclIcons.Tips
            Multiplayer -> PmclIcons.Multiplayer
            Servers -> PmclIcons.Servers
            Friends -> PmclIcons.Friends
            Download -> PmclIcons.Download
            Content -> PmclIcons.Content
            Saves -> PmclIcons.Saves
            Statistics -> PmclIcons.Statistics
            Accounts -> PmclIcons.Accounts
            Settings -> PmclIcons.Settings
            Terminal -> PmclIcons.Terminal
            Plugins -> PmclIcons.Plugins
            Ecxp -> PmclIcons.Ecxp
            Instances -> PmclIcons.Instances
            NbtEditor -> PmclIcons.Nbt
            Music -> PmclIcons.Music
        }

    data object Launch      : NavDestination("launch",      "nav.launch")
    data object News        : NavDestination("news",        "nav.news")
    data object Tips        : NavDestination("tips",        "nav.tips")
    data object Multiplayer : NavDestination("multiplayer", "nav.multiplayer")
    data object Servers     : NavDestination("servers",     "nav.servers")
    data object Friends     : NavDestination("friends",     "nav.friends")
    data object Download    : NavDestination("download",    "nav.download")
    data object Content     : NavDestination("content",     "nav.content")
    data object Saves       : NavDestination("saves",       "nav.saves")
    data object Statistics  : NavDestination("statistics",  "nav.statistics")
    data object Accounts    : NavDestination("accounts",    "nav.accounts")
    data object Settings    : NavDestination("settings",    "nav.settings")
    data object Terminal    : NavDestination("terminal",    "nav.terminal")
    data object Plugins     : NavDestination("plugins",     "nav.plugins")
    data object Ecxp        : NavDestination("ecxp",        "nav.ecxp")
    data object Instances   : NavDestination("instances",   "nav.instances")
    data object NbtEditor   : NavDestination("nbt",         "nav.nbt")
    data object Music       : NavDestination("music",       "nav.music")
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
    NavDestination.Ecxp,
    NavDestination.Instances,
    NavDestination.NbtEditor,
    NavDestination.Music
)
