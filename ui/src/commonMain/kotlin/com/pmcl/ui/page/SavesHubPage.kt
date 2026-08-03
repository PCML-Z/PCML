package com.pmcl.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 存档中心：由二级侧栏切换 世界 / 截图
 */
@Composable
fun SavesHubPage(vm: LauncherViewModel, sectionId: String = "worlds") {
    Box(Modifier.fillMaxSize()) {
        when (sectionId) {
            "screenshots" -> ScreenshotsPage(vm)
            else -> WorldsPage(vm)
        }
    }
}
