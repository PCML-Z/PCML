package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 内容中心：Tab 切换 模组 / 光影包 / 资源包 / 数据包
 * 整合原 ModsPage / ShaderPacksPage / ResourcePacksPage / DatapacksPage
 *
 * 顶部菜单默认折叠为单行，展开后显示完整 TabRow，给浏览列表更多空间。
 */
@Composable
fun ContentHubPage(vm: LauncherViewModel) {
    var tab by remember { mutableStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var tabMenuOpen by remember { mutableStateOf(false) }

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
    val current = tabs.getOrNull(tab) ?: tabs.first()

    Column(Modifier.fillMaxSize()) {
        // 折叠态：单行当前分类 + 下拉切换 + 展开完整菜单
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { tabMenuOpen = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            current.icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            current.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ) {
                            Text(
                                "${current.count}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = I18n.t("common.switch_tab"),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = tabMenuOpen,
                        onDismissRequest = { tabMenuOpen = false }
                    ) {
                        tabs.forEachIndexed { i, spec ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(spec.icon, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            spec.label,
                                            fontWeight = if (i == tab) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${spec.count}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                },
                                onClick = {
                                    tab = i
                                    tabMenuOpen = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { menuExpanded = !menuExpanded },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        if (menuExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (menuExpanded) I18n.t("common.collapse_menu") else I18n.t("common.expand_menu"),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = menuExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
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
