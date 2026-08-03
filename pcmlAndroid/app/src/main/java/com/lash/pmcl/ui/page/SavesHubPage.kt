package com.lash.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.ui.screens.ScreenshotsScreen
import com.lash.pmcl.ui.screens.WorldsScreen

/**
 * 存档中心：Tab 切换 世界 / 截图
 * 与桌面端 com.pmcl.ui.page.SavesHubPage 完全一致。
 */
@Composable
fun SavesHubPage(core: LauncherCore) {
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("世界存档", "截图").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label) }
                )
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                0 -> WorldsScreen(worldManager = core.worldManager)
                1 -> ScreenshotsScreen(screenshotManager = core.screenshotManager)
            }
        }
    }
}
