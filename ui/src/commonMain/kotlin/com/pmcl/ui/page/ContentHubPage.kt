package com.pmcl.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 内容中心：由二级侧栏切换 模组 / 整合包 / 光影 / 资源包 / 数据包 / 配置
 */
@Composable
fun ContentHubPage(vm: LauncherViewModel, sectionId: String = "mods") {
    Box(Modifier.fillMaxSize()) {
        when (sectionId) {
            "modpacks" -> ModpacksPage(vm)
            "shaders" -> ShaderPacksPage(vm)
            "resourcepacks" -> ResourcePacksPage(vm)
            "datapacks" -> DatapacksPage(vm)
            "configs" -> ConfigEditorPage(vm)
            else -> ModsPage(vm)
        }
    }
}
