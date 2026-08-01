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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
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
import com.lash.pmcl.ui.screens.LaunchScreen
import com.lash.pmcl.ui.screens.SettingsScreen
import com.lash.pmcl.ui.screens.VersionsScreen

private enum class NavTab(val label: String, val icon: ImageVector) {
    LAUNCH("启动", Icons.Outlined.PlayArrow),
    VERSIONS("版本", Icons.Outlined.Storage),
    DOWNLOADS("下载", Icons.Outlined.Download),
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
