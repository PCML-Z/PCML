package com.lash.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.ui.screens.ConfigEditorScreen
import com.lash.pmcl.ui.screens.InstancesScreen
import com.lash.pmcl.ui.screens.ModpacksScreen
import com.lash.pmcl.ui.screens.ModsScreen
import com.lash.pmcl.ui.screens.ResourcePacksScreen
import com.lash.pmcl.ui.screens.ShaderPacksScreen
import com.lash.pmcl.ui.theme.LocalThemeState

/**
 * 内容中心：Tab 切换 模组 / 整合包 / 光影包 / 资源包 / 数据包 / 配置
 * 与桌面端 com.pmcl.ui.page.ContentHubPage 完全一致：顶部菜单默认折叠为单行，展开后显示完整 TabRow。
 */
@Composable
fun ContentHubPage(core: LauncherCore) {
    var tab by remember { mutableStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var tabMenuOpen by remember { mutableStateOf(false) }
    val themeState = LocalThemeState.current

    data class TabSpec(val label: String, val icon: ImageVector)

    val tabs = listOf(
        TabSpec("模组", Icons.Filled.Extension),
        TabSpec("整合包", Icons.Filled.Inventory2),
        TabSpec("光影包", Icons.Filled.WbSunny),
        TabSpec("资源包", Icons.Filled.Palette),
        TabSpec("数据包", Icons.Filled.Dataset),
        TabSpec("配置", Icons.Filled.Edit),
    )
    val current = tabs.getOrNull(tab) ?: tabs.first()

    Column(Modifier.fillMaxSize()) {
        // 折叠态：单行当前分类 + 下拉切换 + 展开完整菜单
        Surface(
            color = if (themeState.glassTheme) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surface,
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
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "切换分类",
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
                        if (menuExpanded) "收起菜单" else "展开菜单",
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
                            }
                        }
                    )
                }
            }
        }

        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                0 -> ModsScreen(core = core)
                1 -> ModpacksScreen(modpackManager = core.modpackManager)
                2 -> ShaderPacksScreen(shaderPackManager = core.shaderPackManager)
                3 -> ResourcePacksScreen(resourcePackManager = core.resourcePackManager)
                4 -> DatapacksScreen(core = core)
                5 -> ConfigEditorScreen(configFileManager = core.configFileManager)
            }
        }
    }
}

/**
 * 数据包管理：先选择世界，再列出该世界的 datapacks。
 * 与桌面端 com.pmcl.ui.page.DatapacksPage 完全一致（Android 简化版）。
 */
@Composable
private fun DatapacksScreen(core: LauncherCore) {
    val dpManager = remember { com.lash.pmcl.core.gamecontent.DatapackManager() }
    com.lash.pmcl.ui.screens.DatapacksScreen(
        worldManager = core.worldManager,
        dpManager = dpManager
    )
}

@Composable
private fun LazyColumn(
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) = androidx.compose.foundation.lazy.LazyColumn(modifier = modifier, content = content)
