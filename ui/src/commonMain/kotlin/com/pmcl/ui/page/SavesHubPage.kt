package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 存档中心：Tab 切换 世界 / 截图
 * 整合原 WorldsPage / ScreenshotsPage
 */
@Composable
fun SavesHubPage(vm: LauncherViewModel) {
    var tab by remember { mutableStateOf(0) }

    // 监听命令面板的 Tab 跳转请求
    val hubTabRequest by vm.hubTabRequest.collectAsState()
    LaunchedEffect(hubTabRequest) {
        val req = hubTabRequest ?: return@LaunchedEffect
        if (req.first == "saves" && req.second in 0..1) {
            tab = req.second
            vm.clearHubTabRequest()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf(I18n.t("nav.worlds"), I18n.t("nav.screenshots")).forEachIndexed { i, label ->
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
