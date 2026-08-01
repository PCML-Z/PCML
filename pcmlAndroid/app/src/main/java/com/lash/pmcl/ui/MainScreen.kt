package com.lash.pmcl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.version.VersionManager
import com.lash.pmcl.ui.screens.DownloadsScreen
import com.lash.pmcl.ui.screens.SettingsScreen
import com.lash.pmcl.ui.screens.VersionsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    VERSIONS("版本", Icons.Outlined.Storage),
    DOWNLOADS("下载", Icons.Outlined.Download),
    SETTINGS("设置", Icons.Outlined.Settings),
}

@Composable
fun MainScreen(
    versionManager: VersionManager,
    downloadManager: DownloadManager,
    appVersion: String,
) {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = Tab.entries

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        when (tabs[selected]) {
            Tab.VERSIONS -> VersionsScreen(
                versionManager = versionManager,
                contentPadding = innerPadding,
            )
            Tab.DOWNLOADS -> {
                DownloadsScreen()
            }
            Tab.SETTINGS -> {
                SettingsScreen(
                    downloadManager = downloadManager,
                    appVersion = appVersion,
                )
            }
        }
    }
}
