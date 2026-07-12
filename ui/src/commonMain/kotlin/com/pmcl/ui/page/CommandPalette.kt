package com.pmcl.ui.page

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val route: String,
    val tabIndex: Int = -1,
    val keywords: List<String> = emptyList()
)

/**
 * 构建全部可搜索操作索引。
 * 包含：11 个顶层页面 + Hub 子页面。
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
    actions.add(SearchableAction(I18n.t("nav.wiki"), "下载 - Wiki", Icons.AutoMirrored.Filled.MenuBook, "download", 3, listOf("wiki", "百科")))

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
 * 标题栏内嵌搜索框。
 *
 * 使用 BasicTextField 手写，完全控制高度和文字显示。
 * compact 模式：无边框填充样式，与窗口标题栏融合。
 * 聚焦时背景色平滑过渡动画。
 *
 * @param modifier 布局修饰符
 * @param focusRequester 外部传入的 FocusRequester，用于 Ctrl+K 快捷键聚焦
 * @param onNavigate 导航回调 (route, tabIndex)
 * @param compact 紧凑模式：与窗口标题栏融合
 */
@Composable
fun TopBarSearchField(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
    onNavigate: (route: String, tabIndex: Int) -> Unit,
    compact: Boolean = false
) {
    val allActions = remember { buildSearchableActions() }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val filtered = remember(query, allActions) {
        if (query.isBlank()) emptyList()
        else {
            val q = query.lowercase().trim()
            allActions.filter { action ->
                action.title.lowercase().contains(q) ||
                action.description.lowercase().contains(q) ||
                action.keywords.any { it.lowercase().contains(q) }
            }
        }
    }

    // 查询变化时重置选中项并更新展开状态
    LaunchedEffect(query) {
        selectedIndex = 0
        expanded = query.isNotBlank() && filtered.isNotEmpty()
    }

    // 聚焦时背景色动画过渡
    val bgTarget = if (compact) {
        if (isFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    } else {
        if (isFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.surface
    }
    val bgColor by animateColorAsState(
        targetValue = bgTarget,
        animationSpec = tween(200),
        label = "searchBg"
    )

    val iconColor = if (isFocused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
    val iconColorAnim by animateColorAsState(
        targetValue = iconColor,
        animationSpec = tween(200),
        label = "searchIcon"
    )

    val height = if (compact) 30.dp else 44.dp
    val cornerRadius = if (compact) 8.dp else 12.dp
    val iconSize = if (compact) 14.dp else 18.dp
    val fontSize = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    val textColor = if (compact) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface

    Box(modifier) {
        // 搜索框容器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(cornerRadius))
                .background(bgColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 搜索图标
            Icon(
                Icons.Filled.Search,
                null,
                tint = iconColorAnim,
                modifier = Modifier.padding(start = 10.dp).size(iconSize)
            )
            // 输入区域
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        I18n.t("search.placeholder"),
                        style = fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = fontSize.copy(color = textColor),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions.Default,
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.Enter -> {
                                    if (filtered.isNotEmpty()) {
                                        val action = filtered[selectedIndex.coerceIn(0, filtered.lastIndex)]
                                        onNavigate(action.route, action.tabIndex)
                                        query = ""
                                        expanded = false
                                    }
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (filtered.isNotEmpty() && selectedIndex < filtered.lastIndex) {
                                        selectedIndex++
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (selectedIndex > 0) selectedIndex--
                                    true
                                }
                                Key.Escape -> {
                                    if (query.isNotEmpty()) {
                                        query = ""
                                        expanded = false
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                )
            }
            // 清除按钮
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Clear,
                    I18n.t("common.close"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(if (compact) 14.dp else 18.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { query = ""; expanded = false }
                        .padding(2.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
        }

        // 下拉搜索结果（带淡入动画）
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SearchResultsContent(filtered, selectedIndex, onNavigate) {
                query = ""; expanded = false
            }
        }
    }
}

/**
 * 搜索结果下拉列表内容。
 */
@Composable
private fun SearchResultsContent(
    filtered: List<SearchableAction>,
    selectedIndex: Int,
    onNavigate: (String, Int) -> Unit,
    onClear: () -> Unit
) {
    if (filtered.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(I18n.t("search.no_results"), color = MaterialTheme.colorScheme.outline)
        }
    } else {
        Text(
            I18n.t("search.results_count", filtered.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        HorizontalDivider()
        Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            filtered.forEachIndexed { index, action ->
                val isSelected = index == selectedIndex
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onNavigate(action.route, action.tabIndex)
                            onClear()
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
                    }
                }
            }
        }
    }
}
