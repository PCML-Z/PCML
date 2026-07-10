package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 下载中心：Tab 切换 版本安装 / 模组市场 / Wiki
 * 整合原 DownloadPage / ModsMarketPage / WikiPage
 */
@Composable
fun DownloadHubPage(vm: LauncherViewModel) {
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("版本", "市场", "Wiki").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label) }
                )
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                0 -> DownloadPage(vm)
                1 -> ModsMarketPage(vm)
                2 -> WikiPage(vm)
            }
        }
    }
}
