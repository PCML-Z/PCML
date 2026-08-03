package com.pmcl.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WbSunny
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
            SecondarySection("launcher", "settings.section.launcher", Icons.Filled.Settings),
            SecondarySection("theme", "settings.section.theme", Icons.Filled.Palette),
            SecondarySection("java", "settings.section.java", Icons.Filled.Terminal),
            SecondarySection("game", "settings.section.game", Icons.Filled.PlayArrow),
            SecondarySection("mio", "settings.section.mio", Icons.Filled.Speed),
            SecondarySection("network", "settings.section.network", Icons.Filled.Share),
            SecondarySection("updates", "settings.section.updates", Icons.Filled.SystemUpdate),
            SecondarySection("device", "settings.section.device", Icons.Filled.Shield),
            SecondarySection("system", "settings.section.system", Icons.Filled.Info),
            SecondarySection("about", "settings.section.about", Icons.Filled.Article),
            SecondarySection("extensions", "settings.section.extensions", Icons.Filled.Extension),
        )
    )

    val download = SecondaryNavSpec(
        parentRoute = "download",
        parentLabelKey = "nav.download",
        sections = listOf(
            SecondarySection("versions", "download.local_versions", Icons.Filled.Build),
            SecondarySection("market", "nav.market", Icons.Filled.Star),
            SecondarySection("queue", "nav.queue", Icons.Filled.Download),
            SecondarySection("wiki", "nav.wiki", Icons.Filled.Article),
        )
    )

    val content = SecondaryNavSpec(
        parentRoute = "content",
        parentLabelKey = "nav.content",
        sections = listOf(
            SecondarySection("mods", "nav.mods", Icons.Filled.Extension),
            SecondarySection("modpacks", "nav.modpacks", Icons.Filled.Inventory2),
            SecondarySection("shaders", "nav.shaders", Icons.Filled.WbSunny),
            SecondarySection("resourcepacks", "nav.resourcepacks", Icons.Filled.Palette),
            SecondarySection("datapacks", "nav.datapacks", Icons.Filled.Dataset),
            SecondarySection("configs", "nav.configs", Icons.Filled.Edit),
        )
    )

    val statistics = SecondaryNavSpec(
        parentRoute = "statistics",
        parentLabelKey = "nav.statistics",
        sections = listOf(
            SecondarySection("performance", "stats.section.performance", Icons.Filled.Speed),
            SecondarySection("overview", "stats.section.overview", Icons.Filled.BarChart),
            SecondarySection("sessions", "stats.section.sessions", Icons.Filled.PlayArrow),
            SecondarySection("breakdown", "stats.section.breakdown", Icons.Filled.Dataset),
        )
    )

    val multiplayer = SecondaryNavSpec(
        parentRoute = "multiplayer",
        parentLabelKey = "nav.multiplayer",
        sections = listOf(
            SecondarySection("room", "mp.section.room", Icons.Filled.Share),
            SecondarySection("settings", "mp.section.settings", Icons.Filled.Settings),
            SecondarySection("help", "mp.section.help", Icons.Filled.Info),
        )
    )

    val accounts = SecondaryNavSpec(
        parentRoute = "accounts",
        parentLabelKey = "nav.accounts",
        sections = listOf(
            SecondarySection("list", "accounts.section.list", Icons.Filled.Person),
            SecondarySection("skin", "accounts.section.skin", Icons.Filled.Palette),
            SecondarySection("offline", "accounts.section.offline", Icons.Filled.Person),
            SecondarySection("microsoft", "accounts.section.microsoft", Icons.Filled.OpenInBrowser),
            SecondarySection("github", "accounts.section.github", Icons.Filled.Key),
            SecondarySection("yggdrasil", "accounts.section.yggdrasil", Icons.Filled.Palette),
        )
    )

    val saves = SecondaryNavSpec(
        parentRoute = "saves",
        parentLabelKey = "nav.saves",
        sections = listOf(
            SecondarySection("worlds", "nav.worlds", Icons.Filled.Public),
            SecondarySection("screenshots", "nav.screenshots", Icons.Filled.Image),
        )
    )

    val plugins = SecondaryNavSpec(
        parentRoute = "plugins",
        parentLabelKey = "nav.plugins",
        sections = listOf(
            SecondarySection("installed", "plugins.section.installed", Icons.Filled.Extension),
            SecondarySection("actions", "plugins.section.actions", Icons.Filled.PlayArrow),
            SecondarySection("install", "plugins.section.install", Icons.Filled.Add),
        )
    )

    val music = SecondaryNavSpec(
        parentRoute = "music",
        parentLabelKey = "nav.music",
        sections = listOf(
            SecondarySection("player", "music.section.player", Icons.Filled.PlayArrow),
            SecondarySection("playlist", "music.playlist", Icons.Filled.LibraryMusic),
            SecondarySection("history", "music.history", Icons.Filled.History),
        )
    )

    private val byRoute = listOf(
        settings, download, content, statistics, multiplayer, accounts, saves, plugins, music
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
