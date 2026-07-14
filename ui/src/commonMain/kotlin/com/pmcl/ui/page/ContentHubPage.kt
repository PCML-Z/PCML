package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 内容中心：Tab 切换 模组 / 光影包 / 资源包 / 数据包
 * 整合原 ModsPage / ShaderPacksPage / ResourcePacksPage / DatapacksPage
 */
@Composable
fun ContentHubPage(vm: LauncherViewModel) {
    var tab by remember { mutableStateOf(0) }

    // 监听命令面板的 Tab 跳转请求
    val hubTabRequest by vm.hubTabRequest.collectAsState()
    LaunchedEffect(hubTabRequest) {
        val req = hubTabRequest ?: return@LaunchedEffect
        if (req.first == "content" && req.second in 0..5) {
            tab = req.second
            vm.clearHubTabRequest()
        }
    }

    val mods by vm.installedMods.collectAsState()
    val shaders by vm.shaderPacks.collectAsState()
    val resources by vm.resourcePacks.collectAsState()
    val datapacks by vm.datapacks.collectAsState()
    val modpacks by vm.modpacks.collectAsState()
    val configFiles by vm.configFiles.collectAsState()

    data class TabSpec(val label: String, val icon: ImageVector, val count: Int)
    // 用 derivedStateOf 只在 size 变化时才让 tabs 重组（而非任一流引用变化）
    val tabs by remember {
        derivedStateOf {
            listOf(
                TabSpec(I18n.t("nav.mods"), Icons.Filled.Extension, mods.size),
                TabSpec(I18n.t("nav.modpacks"), Icons.Filled.Inventory2, modpacks.size),
                TabSpec(I18n.t("nav.shaders"), Icons.Filled.WbSunny, shaders.size),
                TabSpec(I18n.t("nav.resourcepacks"), Icons.Filled.Palette, resources.size),
                TabSpec(I18n.t("nav.datapacks"), Icons.Filled.Dataset, datapacks.size),
                TabSpec(I18n.t("nav.configs"), Icons.Filled.Edit, configFiles.size)
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, spec ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                spec.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (tab == i) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                spec.label,
                                fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = if (tab == i)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                shape = CircleShape
                            ) {
                                Text(
                                    "${spec.count}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tab == i)
                                        MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                )
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                0 -> ModsPage(vm)
                1 -> ModpacksPage(vm)
                2 -> ShaderPacksPage(vm)
                3 -> ResourcePacksPage(vm)
                4 -> DatapacksPage(vm)
                5 -> ConfigEditorPage(vm)
            }
        }
    }
}
