package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.navigation.NavDestination
import com.pmcl.ui.navigation.allDestinations

/**
 * 可搜索的操作项。
 *
 * @param title 显示标题
 * @param description 描述（可选，用于搜索匹配）
 * @param icon 图标
 * @param route 导航路由（NavDestination.route）
 * @param tabIndex Hub 页面的 Tab 索引（-1 表示无子 Tab）
 * @param keywords 额外搜索关键词
 */
data class SearchableAction(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
    val tabIndex: Int = -1,
    val keywords: List<String> = emptyList()
)

/**
 * 构建全部可搜索操作索引。
 * 包含：11 个顶层页面 + Hub 子页面 + 快捷操作。
 */
fun buildSearchableActions(): List<SearchableAction> {
    val actions = mutableListOf<SearchableAction>()

    // 顶层页面
    for (dest in allDestinations) {
        val label = I18n.t(dest.labelKey)
        actions.add(SearchableAction(
            title = label,
            description = describeRoute(dest.route),
            icon = dest.icon,
            route = dest.route,
            keywords = keywordsForRoute(dest.route, label)
        ))
    }

    // Content Hub 子页面
    actions.add(SearchableAction(I18n.t("nav.mods"), "内容中心 - 模组管理", Icons.Filled.Extension, "content", 0, listOf("mod", "mods", "模组")))
    actions.add(SearchableAction(I18n.t("nav.modpacks"), "内容中心 - 整合包", Icons.Filled.Inventory2, "content", 1, listOf("modpack", "整合包")))
    actions.add(SearchableAction(I18n.t("nav.shaders"), "内容中心 - 光影包", Icons.Filled.WbSunny, "content", 2, listOf("shader", "光影", "着色器")))
    actions.add(SearchableAction(I18n.t("nav.resourcepacks"), "内容中心 - 资源包", Icons.Filled.Palette, "content", 3, listOf("resource", "资源包", "材质")))
    actions.add(SearchableAction(I18n.t("nav.datapacks"), "内容中心 - 数据包", Icons.Filled.Dataset, "content", 4, listOf("datapack", "数据包")))
    actions.add(SearchableAction(I18n.t("nav.configs"), "内容中心 - 配置文件编辑", Icons.Filled.Edit, "content", 5, listOf("config", "配置", "编辑")))

    // Download Hub 子页面
    actions.add(SearchableAction(I18n.t("download.local_versions"), "下载 - 已安装版本", Icons.Filled.Build, "download", 0, listOf("version", "版本", "安装")))
    actions.add(SearchableAction(I18n.t("nav.market"), "下载 - 模组市场", Icons.Filled.Store, "download", 1, listOf("market", "市场", "商店")))
    actions.add(SearchableAction(I18n.t("nav.queue"), "下载 - 下载队列", Icons.Filled.Download, "download", 2, listOf("queue", "队列", "下载")))
    actions.add(SearchableAction(I18n.t("nav.wiki"), "下载 - Wiki", Icons.Filled.MenuBook, "download", 3, listOf("wiki", "百科", "文档")))

    // Saves Hub 子页面
    actions.add(SearchableAction(I18n.t("nav.worlds"), "存档 - 世界管理", Icons.Filled.Public, "saves", 0, listOf("world", "世界", "存档")))
    actions.add(SearchableAction(I18n.t("nav.screenshots"), "存档 - 截图", Icons.Filled.Image, "saves", 1, listOf("screenshot", "截图")))

    return actions
}

private fun describeRoute(route: String): String = when (route) {
    "launch" -> "启动游戏、版本选择、模组加载器"
    "news" -> "查看 Minecraft 最新资讯"
    "multiplayer" -> "联机服务器列表"
    "download" -> "版本安装、模组市场、下载队列"
    "content" -> "模组、整合包、光影包、资源包、配置文件"
    "saves" -> "世界存档、截图管理"
    "statistics" -> "游戏统计与成就"
    "accounts" -> "微软账号管理"
    "settings" -> "启动器设置、镜像、代理、外观"
    "terminal" -> "内置终端"
    "plugins" -> "插件管理"
    else -> ""
}

private fun keywordsForRoute(route: String, label: String): List<String> = when (route) {
    "launch" -> listOf("启动", "launcher", "play", "游戏")
    "news" -> listOf("新闻", "资讯", "news")
    "multiplayer" -> listOf("联机", "服务器", "multiplayer", "server")
    "download" -> listOf("下载", "download", "安装")
    "content" -> listOf("内容", "content", "资源")
    "saves" -> listOf("存档", "saves", "save")
    "statistics" -> listOf("统计", "statistics", "stats")
    "accounts" -> listOf("账号", "account", "登录", "微软")
    "settings" -> listOf("设置", "settings", "偏好", "配置")
    "terminal" -> listOf("终端", "terminal", "命令行", "console")
    "plugins" -> listOf("插件", "plugin", "扩展")
    else -> listOf(label)
}

/**
 * 命令面板：全局搜索覆盖层。
 * 输入关键词搜索启动器内的功能和页面，选中后自动导航。
 */
@Composable
fun CommandPalette(
    visible: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (route: String, tabIndex: Int) -> Unit
) {
    if (!visible) return

    val allActions = remember { buildSearchableActions() }
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val filtered = remember(query, allActions) {
        if (query.isBlank()) allActions
        else {
            val q = query.lowercase().trim()
            allActions.filter { action ->
                action.title.lowercase().contains(q) ||
                action.description.lowercase().contains(q) ||
                action.keywords.any { it.lowercase().contains(q) }
            }
        }
    }

    // 重置选中索引当搜索结果变化时
    LaunchedEffect(query) {
        selectedIndex = 0
    }

    // 自动聚焦搜索框
    LaunchedEffect(visible) {
        if (visible) {
            query = ""
            selectedIndex = 0
            focusRequester.requestFocus()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .wrapContentHeight(),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(4.dp)
            ) {
                // 搜索输入框
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(I18n.t("search.placeholder")) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, Modifier.size(20.dp)) {
                                Icon(Icons.Filled.Clear, "清除", Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .onKeyEvent { event ->
                            when {
                                event.type == KeyEventType.KeyDown -> {
                                    when (event.key) {
                                        Key.Enter -> {
                                            if (filtered.isNotEmpty()) {
                                                val action = filtered[selectedIndex.coerceIn(0, filtered.lastIndex)]
                                                onNavigate(action.route, action.tabIndex)
                                                onDismiss()
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            if (selectedIndex < filtered.lastIndex) selectedIndex++
                                            true
                                        }
                                        Key.DirectionUp -> {
                                            if (selectedIndex > 0) selectedIndex--
                                            true
                                        }
                                        Key.Escape -> {
                                            onDismiss()
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                else -> false
                            }
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (filtered.isNotEmpty()) {
                            val action = filtered[selectedIndex.coerceIn(0, filtered.lastIndex)]
                            onNavigate(action.route, action.tabIndex)
                            onDismiss()
                        }
                    }),
                    textStyle = MaterialTheme.typography.bodyLarge
                )

                HorizontalDivider()

                // 搜索结果列表
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(I18n.t("search.no_results"), color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp).padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(filtered.size) { index ->
                            val action = filtered[index]
                            val isSelected = index == selectedIndex
                            Surface(
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onNavigate(action.route, action.tabIndex)
                                        onDismiss()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        action.icon,
                                        null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            action.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                    else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (action.description.isNotEmpty()) {
                                            Text(
                                                action.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                                        else MaterialTheme.colorScheme.outline,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (action.tabIndex >= 0) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "Tab",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 底部提示
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        I18n.t("search.hint"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        I18n.t("search.results_count", filtered.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
