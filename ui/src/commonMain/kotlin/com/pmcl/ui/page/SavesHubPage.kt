package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 存档中心：Tab 切换 世界 / 截图
 * 整合原 WorldsPage / ScreenshotsPage
 */
@Composable
fun SavesHubPage(vm: LauncherViewModel) {
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("世界", "截图").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label) }
                )
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                0 -> WorldsPage(vm)
                1 -> ScreenshotsPage(vm)
            }
        }
    }
}
