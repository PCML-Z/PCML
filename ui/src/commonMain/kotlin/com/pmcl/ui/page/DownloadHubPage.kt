package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 下载中心：由二级侧栏切换 版本安装 / 模组市场 / 下载队列 / Wiki
 */
@Composable
fun DownloadHubPage(vm: LauncherViewModel, sectionId: String = "versions") {
    Box(Modifier.fillMaxSize()) {
        when (sectionId) {
            "market" -> ModsMarketPage(vm)
            "queue" -> DownloadsPage(vm)
            "wiki" -> WikiPage(vm)
            else -> DownloadPage(vm)
        }
    }
}
