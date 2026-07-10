package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 内容中心：Tab 切换 模组 / 光影包 / 资源包 / 数据包
 * 整合原 ModsPage / ShaderPacksPage / ResourcePacksPage / DatapacksPage
 */
@Composable
fun ContentHubPage(vm: LauncherViewModel) {
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("模组", "光影包", "资源包", "数据包").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label) }
                )
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                0 -> ModsPage(vm)
                1 -> ShaderPacksPage(vm)
                2 -> ResourcePacksPage(vm)
                3 -> DatapacksPage(vm)
            }
        }
    }
}
