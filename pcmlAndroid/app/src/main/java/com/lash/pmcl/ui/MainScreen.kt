package com.lash.pmcl.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.ui.screens.AccountsScreen
import com.lash.pmcl.ui.screens.DownloadsScreen
import com.lash.pmcl.ui.screens.InstancesScreen
import com.lash.pmcl.ui.screens.LaunchScreen
import com.lash.pmcl.ui.screens.ModpacksScreen
import com.lash.pmcl.ui.screens.ModsScreen
import com.lash.pmcl.ui.screens.SettingsScreen
import com.lash.pmcl.ui.screens.VersionsScreen
import com.lash.pmcl.ui.screens.WorldsScreen

private enum class NavTab(val label: String, val icon: ImageVector) {
    LAUNCH("启动", Icons.Outlined.PlayArrow),
    VERSIONS("版本", Icons.Outlined.Storage),
    DOWNLOADS("下载", Icons.Outlined.Download),
    MODS("模组", Icons.Outlined.Extension),
    WORLDS("存档", Icons.Outlined.Public),
    INSTANCES("实例", Icons.Outlined.Folder),
    MODPACKS("整合包", Icons.Outlined.Archive),
    ACCOUNTS("账号", Icons.Outlined.Person),
    SETTINGS("设置", Icons.Outlined.Settings),
}

@Composable
fun MainScreen(
    core: LauncherCore,
    appVersion: String,
) {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = NavTab.entries

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationRailItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "page-switch",
                ) { index ->
                    when (tabs[index]) {
                        NavTab.LAUNCH -> LaunchScreen(
                            authService = core.authService,
                            launchManager = core.launchManager,
                            versionManager = core.versionManager,
                            preferences = core.preferences,
                        )
                        NavTab.VERSIONS -> VersionsScreen(versionManager = core.versionManager)
                        NavTab.DOWNLOADS -> DownloadsScreen()
                        NavTab.MODS -> ModsScreen(modManager = core.modManager)
                        NavTab.WORLDS -> WorldsScreen(worldManager = core.worldManager)
                        NavTab.INSTANCES -> InstancesScreen(instanceManager = core.instanceManager)
                        NavTab.MODPACKS -> ModpacksScreen(modpackManager = core.modpackManager)
                        NavTab.ACCOUNTS -> AccountsScreen(
                            authService = core.authService,
                            preferences = core.preferences,
                        )
                        NavTab.SETTINGS -> SettingsScreen(
                            downloadManager = core.downloadManager,
                            preferences = core.preferences,
                            appVersion = appVersion,
                        )
                    }
                }
            }
        }
    }
}
