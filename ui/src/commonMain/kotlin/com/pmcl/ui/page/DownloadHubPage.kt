package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 下载中心：Tab 切换 版本安装 / 模组市场 / 下载队列 / Wiki
 * 整合原 DownloadPage / ModsMarketPage / WikiPage
 */
@Composable
fun DownloadHubPage(vm: LauncherViewModel) {
    var tab by remember { mutableStateOf(0) }
    val summary by vm.queueSummary.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("版本", "市场", "队列", "Wiki").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label)
                            if (i == 2 && summary.active() > 0) {
                                Spacer(Modifier.width(6.dp))
                                Badge {
                                    Text("${summary.active()}")
                                }
                            }
                        }
                    }
                )
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                0 -> DownloadPage(vm)
                1 -> ModsMarketPage(vm)
                2 -> DownloadsPage(vm)
                3 -> WikiPage(vm)
            }
        }
    }
}
