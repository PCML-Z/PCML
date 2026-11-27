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
    val marketType = marketSectionProjectType(sectionId)
    Box(Modifier.fillMaxSize()) {
        when {
            marketType != null -> ModsMarketPage(vm, marketType)
            sectionId == "queue" -> DownloadsPage(vm)
            sectionId == "wiki" -> WikiPage(vm)
            else -> DownloadPage(vm)
        }
    }
}

/** 竖屏资源入口用的市场分区。横屏侧栏仍只有一个「市场」。 */
internal fun marketSectionProjectType(sectionId: String?): String? = when (sectionId) {
    "market" -> ""
    "market-mod" -> "mod"
    "market-modpack" -> "modpack"
    "market-resourcepack" -> "resourcepack"
    "market-shader" -> "shader"
    else -> null
}

internal fun marketSectionTitleKey(sectionId: String?): String? = when (sectionId) {
    "market" -> "nav.market"
    "market-mod" -> "nav.mods"
    "market-modpack" -> "nav.modpacks"
    "market-resourcepack" -> "nav.resourcepacks"
    "market-shader" -> "nav.shaders"
    else -> null
}
