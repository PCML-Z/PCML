package com.pmcl.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector

/** 二级侧栏中的一个子分区 */
data class SecondarySection(
    val id: String,
    val labelKey: String,
    val icon: ImageVector? = null,
)

/** 某个一级入口的二级导航规格 */
data class SecondaryNavSpec(
    val parentRoute: String,
    val parentLabelKey: String,
    val sections: List<SecondarySection>,
)

object SecondaryNavRegistry {
    val settings = SecondaryNavSpec(
        parentRoute = "settings",
        parentLabelKey = "nav.settings",
        sections = listOf(
            SecondarySection("launcher", "settings.section.launcher", PmclIcons.Settings),
            SecondarySection("accounts", "settings.section.accounts", PmclIcons.Accounts),
            SecondarySection("theme", "settings.section.theme", PmclIcons.Palette),
            SecondarySection("java", "settings.section.java", PmclIcons.Terminal),
            SecondarySection("automation", "settings.section.automation", PmclIcons.Bolt),
            SecondarySection("game", "settings.section.game", PmclIcons.Launch),
            SecondarySection("compile", "settings.section.compile", PmclIcons.Wrench),
            SecondarySection("mio", "settings.section.mio", PmclIcons.Speed),
            SecondarySection("network", "settings.section.network", PmclIcons.Multiplayer),
            SecondarySection("updates", "settings.section.updates", PmclIcons.Update),
            SecondarySection("git-tree", "settings.section.git_tree", PmclIcons.Nbt),
            SecondarySection("device", "settings.section.device", PmclIcons.Shield),
            SecondarySection("system", "settings.section.system", PmclIcons.News),
            SecondarySection("about", "settings.section.about", PmclIcons.Article),
            SecondarySection("feedback", "settings.section.feedback", PmclIcons.Qr),
            SecondarySection("licenses", "settings.section.licenses", PmclIcons.Gavel),
            SecondarySection("extensions", "settings.section.extensions", PmclIcons.Plugins),
        )
    )

    val download = SecondaryNavSpec(
        parentRoute = "download",
        parentLabelKey = "nav.download",
        sections = listOf(
            SecondarySection("versions", "download.local_versions", PmclIcons.Wrench),
            SecondarySection("market", "nav.market", PmclIcons.Content),
            SecondarySection("queue", "nav.queue", PmclIcons.Download),
            SecondarySection("wiki", "nav.wiki", PmclIcons.Article),
        )
    )

    val content = SecondaryNavSpec(
        parentRoute = "content",
        parentLabelKey = "nav.content",
        sections = listOf(
            SecondarySection("mods", "nav.mods", PmclIcons.Plugins),
            SecondarySection("modpacks", "nav.modpacks", PmclIcons.Modpack),
            SecondarySection("shaders", "nav.shaders", PmclIcons.Sun),
            SecondarySection("projections", "nav.projections", PmclIcons.Grid),
            SecondarySection("resourcepacks", "nav.resourcepacks", PmclIcons.Palette),
            SecondarySection("datapacks", "nav.datapacks", PmclIcons.Dataset),
            SecondarySection("configs", "nav.configs", PmclIcons.Edit),
            SecondarySection("skins", "nav.offline_skins", PmclIcons.Image),
        )
    )

    val statistics = SecondaryNavSpec(
        parentRoute = "statistics",
        parentLabelKey = "nav.statistics",
        sections = listOf(
            SecondarySection("performance", "stats.section.performance", PmclIcons.Speed),
            SecondarySection("overview", "stats.section.overview", PmclIcons.Statistics),
            SecondarySection("sessions", "stats.section.sessions", PmclIcons.Launch),
            SecondarySection("breakdown", "stats.section.breakdown", PmclIcons.Dataset),
        )
    )

    val multiplayer = SecondaryNavSpec(
        parentRoute = "multiplayer",
        parentLabelKey = "nav.multiplayer",
        sections = listOf(
            SecondarySection("room", "mp.section.room", PmclIcons.Multiplayer),
            SecondarySection("settings", "mp.section.settings", PmclIcons.Settings),
            SecondarySection("help", "mp.section.help", PmclIcons.News),
        )
    )

    val accounts = SecondaryNavSpec(
        parentRoute = "accounts",
        parentLabelKey = "nav.accounts",
        sections = listOf(
            SecondarySection("list", "accounts.section.list", PmclIcons.Accounts),
            SecondarySection("skin", "accounts.section.skin", PmclIcons.Palette),
            SecondarySection("offline", "accounts.section.offline", PmclIcons.Friends),
            SecondarySection("microsoft", "accounts.section.microsoft", PmclIcons.Browser),
            SecondarySection("github", "accounts.section.github", PmclIcons.Key),
            SecondarySection("yggdrasil", "accounts.section.yggdrasil", PmclIcons.Shield),
        )
    )

    val saves = SecondaryNavSpec(
        parentRoute = "saves",
        parentLabelKey = "nav.saves",
        sections = listOf(
            SecondarySection("worlds", "nav.worlds", PmclIcons.Globe),
            SecondarySection("timemachine", "nav.timemachine", PmclIcons.History),
            SecondarySection("screenshots", "nav.screenshots", PmclIcons.Image),
            SecondarySection("recordings", "nav.recordings", PmclIcons.Video),
        )
    )

    val plugins = SecondaryNavSpec(
        parentRoute = "plugins",
        parentLabelKey = "nav.plugins",
        sections = listOf(
            SecondarySection("installed", "plugins.section.installed", PmclIcons.Plugins),
            SecondarySection("actions", "plugins.section.actions", PmclIcons.Launch),
            SecondarySection("install", "plugins.section.install", PmclIcons.Plus),
        )
    )

    val friends = SecondaryNavSpec(
        parentRoute = "friends",
        parentLabelKey = "nav.friends",
        sections = listOf(
            SecondarySection("chat", "friend.section.chat", PmclIcons.Friends),
            SecondarySection("rooms", "friend.section.rooms", PmclIcons.Multiplayer),
        )
    )

    val music = SecondaryNavSpec(
        parentRoute = "music",
        parentLabelKey = "nav.music",
        sections = listOf(
            SecondarySection("player", "music.section.player", PmclIcons.Music),
            SecondarySection("playlist", "music.playlist", PmclIcons.Playlist),
            SecondarySection("history", "music.history", PmclIcons.History),
        )
    )

    private val byRoute = listOf(
        settings, download, content, statistics, multiplayer, accounts, saves, plugins, music, friends
    ).associateBy { it.parentRoute }

    fun savesSectionId(tabIndex: Int): String =
        saves.sections.getOrNull(tabIndex)?.id ?: saves.sections.first().id

    fun specFor(dest: NavDestination): SecondaryNavSpec? = byRoute[dest.route]

    fun specForRoute(route: String): SecondaryNavSpec? = byRoute[route]

    fun downloadSectionId(tabIndex: Int): String =
        download.sections.getOrNull(tabIndex)?.id ?: download.sections.first().id

    fun downloadTabIndex(sectionId: String): Int =
        download.sections.indexOfFirst { it.id == sectionId }.coerceAtLeast(0)

    fun contentSectionId(tabIndex: Int): String =
        content.sections.getOrNull(tabIndex)?.id ?: content.sections.first().id

    fun contentTabIndex(sectionId: String): Int =
        content.sections.indexOfFirst { it.id == sectionId }.coerceAtLeast(0)
}
