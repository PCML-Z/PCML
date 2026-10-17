package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.navigation.PmclIcons
import com.pmcl.ui.navigation.SecondaryNavRegistry
import com.pmcl.ui.navigation.SecondarySection
import com.pmcl.ui.page.AccountsPage
import com.pmcl.ui.page.BackupPage
import com.pmcl.ui.page.ContentHubPage
import com.pmcl.ui.page.DownloadHubPage
import com.pmcl.ui.page.FriendPage
import com.pmcl.ui.page.InstancesPage
import com.pmcl.ui.page.MultiplayerPage
import com.pmcl.ui.page.MusicPage
import com.pmcl.ui.page.NbtEditorPage
import com.pmcl.ui.page.NewsPage
import com.pmcl.ui.page.PluginPage
import com.pmcl.ui.page.SavesHubPage
import com.pmcl.ui.page.ServersPage
import com.pmcl.ui.page.SettingsPage
import com.pmcl.ui.page.StatisticsPage
import com.pmcl.ui.viewmodel.LauncherViewModel

/** 主启动器里的一个模块，在 Studio 里作为标签页打开。 */
internal class StudioModule(
    val id: String,
    val labelKey: String,
    val icon: ImageVector,
    val color: Color,
    val sections: List<SecondarySection>,
    val render: @Composable (LauncherViewModel, String?) -> Unit,
) {
    val label: String get() = I18n.t(labelKey)
}

internal val studioModules: List<StudioModule> = listOf(
    StudioModule("download", "nav.download", PmclIcons.Download, Tiles.Sky, SecondaryNavRegistry.download.sections) { vm, s ->
        DownloadHubPage(vm, s ?: SecondaryNavRegistry.download.sections.first().id)
    },
    StudioModule("content", "nav.content", PmclIcons.Content, Tiles.Teal, SecondaryNavRegistry.content.sections) { vm, s ->
        ContentHubPage(vm, s ?: SecondaryNavRegistry.content.sections.first().id)
    },
    StudioModule("instances", "nav.instances", PmclIcons.Instances, Tiles.Navy, emptyList()) { vm, _ -> InstancesPage(vm) },
    StudioModule("saves", "nav.saves", PmclIcons.Saves, Tiles.Teal, SecondaryNavRegistry.saves.sections) { vm, s ->
        SavesHubPage(vm, s ?: SecondaryNavRegistry.saves.sections.first().id)
    },
    StudioModule("backup", "studio.module_backup", PmclIcons.History, Tiles.Navy, emptyList()) { vm, _ -> BackupPage(vm) },
    StudioModule("nbt", "nav.nbt", PmclIcons.Nbt, Tiles.Graphite, emptyList()) { vm, _ -> NbtEditorPage(vm) },
    StudioModule("multiplayer", "nav.multiplayer", PmclIcons.Multiplayer, Tiles.Sky, SecondaryNavRegistry.multiplayer.sections) { vm, s ->
        MultiplayerPage(vm, s ?: SecondaryNavRegistry.multiplayer.sections.first().id)
    },
    StudioModule("servers", "nav.servers", PmclIcons.Servers, Tiles.Slate, emptyList()) { vm, _ -> ServersPage(vm) },
    StudioModule("friends", "nav.friends", PmclIcons.Friends, Tiles.Purple, SecondaryNavRegistry.friends.sections) { vm, s ->
        FriendPage(vm, s ?: SecondaryNavRegistry.friends.sections.first().id)
    },
    StudioModule("accounts", "nav.accounts", PmclIcons.Accounts, Tiles.Plum, SecondaryNavRegistry.accounts.sections) { vm, s ->
        AccountsPage(vm, s ?: SecondaryNavRegistry.accounts.sections.first().id)
    },
    StudioModule("statistics", "nav.statistics", PmclIcons.Statistics, Tiles.Gold, SecondaryNavRegistry.statistics.sections) { vm, s ->
        StatisticsPage(vm, s ?: SecondaryNavRegistry.statistics.sections.first().id)
    },
    StudioModule("plugins", "nav.plugins", PmclIcons.Plugins, Tiles.Slate, SecondaryNavRegistry.plugins.sections) { vm, s ->
        PluginPage(vm, s ?: SecondaryNavRegistry.plugins.sections.first().id)
    },
    StudioModule("music", "nav.music", PmclIcons.Music, Tiles.Plum, SecondaryNavRegistry.music.sections) { vm, s ->
        MusicPage(vm, s ?: SecondaryNavRegistry.music.sections.first().id)
    },
    StudioModule("news", "nav.news", PmclIcons.News, Tiles.Blue, emptyList()) { vm, _ -> NewsPage(vm) },
    StudioModule("settings", "nav.settings", PmclIcons.Settings, Tiles.Graphite, SecondaryNavRegistry.settings.sections) { vm, s ->
        SettingsPage(vm, s ?: SecondaryNavRegistry.settings.sections.first().id)
    },
)

internal fun moduleOf(id: String): StudioModule? = studioModules.firstOrNull { it.id == id }

/** 左侧“模块”面板：所有启动器模块拼成一面色块墙。 */
@Composable
internal fun ModulesPanel(onOpen: (StudioModule) -> Unit) {
    val p = LocalStudio.current
    Column(Modifier.fillMaxSize().background(p.panel)) {
        PanelHeader(I18n.t("studio.modules"))
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize()) {
            itemsIndexed(studioModules, key = { _, m -> m.id }) { index, module ->
                ColorTile(module.label, "", module.color, Modifier.fillMaxWidth().height(78.dp), module.icon, { onOpen(module) }, index = index)
            }
        }
    }
}

/** 设置模块的原生界面是单独的设置标签页，这里只承载它的经典分区。 */
private val classicOnlyModules = setOf("settings")

@Composable
private fun NativeModule(ctx: ModuleCtx, moduleId: String) {
    when (moduleId) {
        "instances" -> InstancesModule(ctx)
        "saves" -> SavesModule(ctx)
        "backup" -> TimeMachineModule(ctx)
        "servers" -> ServersModule(ctx)
        "accounts" -> AccountsModule(ctx)
        "download" -> DownloadModule(ctx)
        "content" -> ContentModule(ctx)
        "statistics" -> StatisticsModule(ctx)
        "plugins" -> PluginsModule(ctx)
        "news" -> NewsModule(ctx)
        "multiplayer" -> MultiplayerModule(ctx)
        "friends" -> FriendsModule(ctx)
        "music" -> MusicModule(ctx)
        "nbt" -> NbtModule(ctx)
    }
}

/** 模块标签页：顶部一条分区色带，下面是 Studio 原生界面；右上角可切回主启动器的经典页面。 */
@Composable
internal fun ModuleEditor(
    vm: LauncherViewModel,
    tab: StudioTab.Module,
    onSection: (String) -> Unit,
    onConfirm: (String, String, () -> Unit) -> Unit,
) {
    val p = LocalStudio.current
    val module = moduleOf(tab.moduleId)
    if (module == null) {
        Box(Modifier.fillMaxSize().background(p.editor)) { CenterNote(tab.moduleId) }
        return
    }
    val section = tab.sectionId ?: module.sections.firstOrNull()?.id
    val classicOnly = module.id in classicOnlyModules
    var classic by rememberSaveable(module.id) { mutableStateOf(false) }
    val ctx = ModuleCtx(vm, module.color, section.orEmpty(), onConfirm, onSection)
    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(38.dp).background(p.panelAlt), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.fillMaxHeight().background(module.color).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(module.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                FlatText(module.label, color = Color.White, weight = FontWeight.SemiBold)
            }
            Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                module.sections.forEach { s ->
                    val on = s.id == section
                    Row(
                        Modifier
                            .fillMaxHeight()
                            .background(if (on) module.color.lift(0.18f) else Color.Transparent)
                            .clickable { onSection(s.id) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        s.icon?.let {
                            Icon(it, contentDescription = null, tint = if (on) Color.White else module.color, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        FlatText(I18n.t(s.labelKey), color = if (on) Color.White else p.text, weight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            if (!classicOnly) {
                Block(
                    if (classic) mt("native_ui") else mt("classic_ui"),
                    { classic = !classic },
                    icon = if (classic) Icons.Filled.Dashboard else Icons.AutoMirrored.Filled.OpenInNew,
                    fill = if (classic) module.color else null,
                    height = 38.dp
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.runtime.key(module.id, section, classic) {
                if (classic || classicOnly) module.render(vm, section) else NativeModule(ctx, module.id)
            }
        }
    }
}
