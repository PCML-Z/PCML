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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.navigation.NavDestination
import com.pmcl.ui.navigation.allDestinations
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 统一搜索项类型。
 *
 * @param title 主标题
 * @param subtitle 副标题/描述
 * @param icon 图标
 * @param group 分组标签（导航/版本/模组/操作）
 * @param matchText 用于匹配的文本（默认=title+subtitle+keywords）
 * @param keywords 额外搜索关键词
 * @param onSelect 选中时的回调
 */
data class SearchItem(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val group: String,
    val keywords: List<String> = emptyList(),
    val onSelect: () -> Unit
)

/** 搜索结果分组 */
data class SearchResultGroup(
    val label: String,
    val items: List<SearchItem>
)

// ===== 分组标签常量（按显示顺序） =====
private const val GROUP_ACTION = "action"
private const val GROUP_NAV = "navigation"
private const val GROUP_VERSION = "version"
private const val GROUP_MOD = "mod"
private const val GROUP_INSTANCE = "instance"

/**
 * 构建全部可搜索项索引（包含导航、版本、模组、快捷操作）。
 *
 * @param vm ViewModel，用于读取版本/模组列表并触发操作
 */
@Composable
fun buildSearchIndex(vm: LauncherViewModel): List<SearchItem> {
    val items = remember { mutableListOf<SearchItem>() }

    // ===== 快捷操作 =====
    items.add(SearchItem(
        title = I18n.t("search.action.launch"),
        subtitle = I18n.t("search.action.launch.desc"),
        icon = Icons.Filled.PlayArrow,
        group = GROUP_ACTION,
        keywords = listOf("启动", "launch", "play", "开始"),
        onSelect = {
            vm.requestNavigation("launch")
            vm.launch()
        }
    ))
    items.add(SearchItem(
        title = I18n.t("search.action.scan_versions"),
        subtitle = I18n.t("search.action.scan_versions.desc"),
        icon = Icons.Filled.Refresh,
        group = GROUP_ACTION,
        keywords = listOf("扫描", "scan", "刷新", "refresh", "版本"),
        onSelect = { vm.refreshLocalVersions() }
    ))
    items.add(SearchItem(
        title = I18n.t("search.action.scan_mods"),
        subtitle = I18n.t("search.action.scan_mods.desc"),
        icon = Icons.Filled.Extension,
        group = GROUP_ACTION,
        keywords = listOf("模组", "mod", "扫描", "scan"),
        onSelect = {
            vm.requestNavigation("content")
            vm.requestHubTab("content", 0)
            vm.refreshInstalledMods()
        }
    ))
    items.add(SearchItem(
        title = I18n.t("search.action.toggle_theme"),
        subtitle = I18n.t("search.action.toggle_theme.desc"),
        icon = Icons.Filled.DarkMode,
        group = GROUP_ACTION,
        keywords = listOf("主题", "theme", "暗色", "dark", "切换", "toggle"),
        onSelect = { vm.themeState?.toggle() }
    ))
    items.add(SearchItem(
        title = I18n.t("search.action.settings"),
        subtitle = I18n.t("search.action.settings.desc"),
        icon = Icons.Filled.Settings,
        group = GROUP_ACTION,
        keywords = listOf("设置", "settings", "偏好", "配置"),
        onSelect = { vm.requestNavigation("settings") }
    ))

    // ===== 顶层页面导航 =====
    for (dest in allDestinations) {
        val label = I18n.t(dest.labelKey)
        items.add(SearchItem(
            title = label,
            subtitle = describeRoute(dest.route),
            icon = dest.icon,
            group = GROUP_NAV,
            keywords = keywordsForRoute(dest.route, label),
            onSelect = { vm.requestNavigation(dest.route) }
        ))
    }

    // ===== Hub 子页面 =====
    // Content Hub
    items.add(SearchItem(I18n.t("nav.mods"), I18n.t("search.hub.mods"), Icons.Filled.Extension, GROUP_NAV, listOf("mod", "mods", "模组")) {
        vm.requestNavigation("content"); vm.requestHubTab("content", 0)
    })
    items.add(SearchItem(I18n.t("nav.modpacks"), I18n.t("search.hub.modpacks"), Icons.Filled.Inventory2, GROUP_NAV, listOf("modpack", "整合包")) {
        vm.requestNavigation("content"); vm.requestHubTab("content", 1)
    })
    items.add(SearchItem(I18n.t("nav.shaders"), I18n.t("search.hub.shaders"), Icons.Filled.WbSunny, GROUP_NAV, listOf("shader", "光影", "着色器")) {
        vm.requestNavigation("content"); vm.requestHubTab("content", 2)
    })
    items.add(SearchItem(I18n.t("nav.resourcepacks"), I18n.t("search.hub.resourcepacks"), Icons.Filled.Palette, GROUP_NAV, listOf("resource", "资源包", "材质")) {
        vm.requestNavigation("content"); vm.requestHubTab("content", 3)
    })
    items.add(SearchItem(I18n.t("nav.datapacks"), I18n.t("search.hub.datapacks"), Icons.Filled.Dataset, GROUP_NAV, listOf("datapack", "数据包")) {
        vm.requestNavigation("content"); vm.requestHubTab("content", 4)
    })
    items.add(SearchItem(I18n.t("nav.configs"), I18n.t("search.hub.configs"), Icons.Filled.Edit, GROUP_NAV, listOf("config", "配置", "编辑")) {
        vm.requestNavigation("content"); vm.requestHubTab("content", 5)
    })
    // Download Hub
    items.add(SearchItem(I18n.t("download.local_versions"), I18n.t("search.hub.local_versions"), Icons.Filled.Build, GROUP_NAV, listOf("version", "版本", "安装")) {
        vm.requestNavigation("download"); vm.requestHubTab("download", 0)
    })
    items.add(SearchItem(I18n.t("nav.market"), I18n.t("search.hub.market"), Icons.Filled.Store, GROUP_NAV, listOf("market", "市场", "商店")) {
        vm.requestNavigation("download"); vm.requestHubTab("download", 1)
    })
    items.add(SearchItem(I18n.t("nav.queue"), I18n.t("search.hub.queue"), Icons.Filled.Download, GROUP_NAV, listOf("queue", "队列", "下载")) {
        vm.requestNavigation("download"); vm.requestHubTab("download", 2)
    })
    items.add(SearchItem(I18n.t("nav.wiki"), I18n.t("search.hub.wiki"), Icons.AutoMirrored.Filled.MenuBook, GROUP_NAV, listOf("wiki", "百科")) {
        vm.requestNavigation("download"); vm.requestHubTab("download", 3)
    })
    // Saves Hub
    items.add(SearchItem(I18n.t("nav.worlds"), I18n.t("search.hub.worlds"), Icons.Filled.Public, GROUP_NAV, listOf("world", "世界", "存档")) {
        vm.requestNavigation("saves"); vm.requestHubTab("saves", 0)
    })
    items.add(SearchItem(I18n.t("nav.screenshots"), I18n.t("search.hub.screenshots"), Icons.Filled.Image, GROUP_NAV, listOf("screenshot", "截图")) {
        vm.requestNavigation("saves"); vm.requestHubTab("saves", 1)
    })

    // ===== 本地已安装版本（可启动） =====
    val localInfos by vm.localVersionInfos.collectAsState()
    localInfos.forEach { info ->
        val vid = info.getId()
        items.add(SearchItem(
            title = vid,
            subtitle = if (info.isLaunchable()) I18n.t("search.version.installed")
                       else I18n.t("search.version.not_installed"),
            icon = Icons.Filled.Build,
            group = GROUP_VERSION,
            keywords = listOf("version", "版本", "启动", "launch"),
            onSelect = {
                vm.selectVersion(vid)
                vm.requestNavigation("launch")
            }
        ))
    }

    // ===== 已安装模组 =====
    val mods by vm.installedMods.collectAsState()
    mods.forEach { mod ->
        val name = mod.getName() ?: mod.getModId() ?: mod.getJarFile() ?: ""
        if (name.isEmpty()) return@forEach
        items.add(SearchItem(
            title = name,
            subtitle = buildString {
                mod.getLoader()?.let { append(it).append(" ") }
                mod.getVersion()?.let { append("v").append(it) }
                if (mod.isDisabled()) append(" · " + I18n.t("search.mod.disabled"))
            },
            icon = Icons.Filled.Extension,
            group = GROUP_MOD,
            keywords = listOf("mod", "模组", mod.getModId() ?: "", name),
            onSelect = {
                vm.requestNavigation("content")
                vm.requestHubTab("content", 0)
            }
        ))
    }

    // ===== 独立实例（可直接启动） =====
    val instances by vm.instances.collectAsState()
    instances.forEach { inst ->
        val instName = inst.getName() ?: ""
        if (instName.isEmpty()) return@forEach
        items.add(SearchItem(
            title = instName,
            subtitle = buildString {
                append(inst.getBaseVersionId() ?: "")
                inst.getLoader()?.let { if (it.isNotEmpty()) append(" · $it") }
            },
            icon = Icons.Filled.Dashboard,
            group = GROUP_INSTANCE,
            keywords = listOf("instance", "实例", instName, inst.getBaseVersionId() ?: ""),
            onSelect = {
                vm.requestNavigation("instances")
                if (inst.isLaunchable()) vm.launchInstance(inst.getInstanceId())
            }
        ))
    }

    return items
}

private fun describeRoute(route: String): String = when (route) {
    "launch" -> I18n.t("search.nav.launch")
    "news" -> I18n.t("search.nav.news")
    "multiplayer" -> I18n.t("search.nav.multiplayer")
    "friends" -> I18n.t("search.nav.friends")
    "download" -> I18n.t("search.nav.download")
    "content" -> I18n.t("search.nav.content")
    "saves" -> I18n.t("search.nav.saves")
    "statistics" -> I18n.t("search.nav.statistics")
    "accounts" -> I18n.t("search.nav.accounts")
    "settings" -> I18n.t("search.nav.settings")
    "terminal" -> I18n.t("search.nav.terminal")
    "plugins" -> I18n.t("search.nav.plugins")
    "instances" -> I18n.t("search.nav.instances")
    else -> ""
}

private fun keywordsForRoute(route: String, label: String): List<String> = when (route) {
    "launch" -> listOf("启动", "launcher", "play", "游戏")
    "news" -> listOf("新闻", "资讯", "news")
    "multiplayer" -> listOf("联机", "服务器", "multiplayer", "server")
    "friends" -> listOf("好友", "聊天", "friend", "chat", "联系人", "contact", "QR", "二维码")
    "download" -> listOf("下载", "download", "安装")
    "content" -> listOf("内容", "content", "资源")
    "saves" -> listOf("存档", "saves", "save")
    "statistics" -> listOf("统计", "statistics", "stats")
    "accounts" -> listOf("账号", "account", "登录", "微软")
    "settings" -> listOf("设置", "settings", "偏好", "配置")
    "terminal" -> listOf("终端", "terminal", "命令行", "console")
    "plugins" -> listOf("插件", "plugin", "扩展")
    "instances" -> listOf("实例", "instance", "独立", "prism", "multimc")
    else -> listOf(label)
}

/**
 * 模糊匹配评分：返回 0 表示不匹配，分数越高匹配度越好。
 *
 * - 精确匹配得分最高
 * - 前缀匹配次之
 * - 包含匹配再次之
 * - 多关键词命中累加
 */
private fun matchScore(query: String, item: SearchItem): Int {
    if (query.isEmpty()) return 0
    val q = query.lowercase().trim()
    if (q.isEmpty()) return 0

    val title = item.title.lowercase()
    val subtitle = item.subtitle.lowercase()
    val keywords = item.keywords.joinToString(" ").lowercase()

    var score = 0

    // 精确匹配
    if (title == q) score += 100
    if (subtitle == q) score += 50
    if (item.keywords.any { it.lowercase() == q }) score += 60

    // 前缀匹配
    if (title.startsWith(q)) score += 40
    if (subtitle.startsWith(q)) score += 20
    if (item.keywords.any { it.lowercase().startsWith(q) }) score += 25

    // 包含匹配
    if (title.contains(q)) score += 15
    if (subtitle.contains(q)) score += 8
    if (keywords.contains(q)) score += 10

    // 拼音首字母简写匹配（如 "sz" 匹配 "设置"）
    // 简单实现：对中文标题取拼音首字母（如果 query 全是字母且长度<=4）
    if (q.length in 1..4 && q.all { it.isLetter() } && title.any { it.code > 127 }) {
        val pinyinAbbr = title.mapNotNull { c ->
            if (c.code > 127) pinyinFirstLetter(c) else c.lowercaseChar().toString()
        }.joinToString("")
        if (pinyinAbbr.startsWith(q)) score += 35
        else if (pinyinAbbr.contains(q)) score += 12
    }

    return score
}

/** 简单的中文拼音首字母映射（仅覆盖常用字，无法覆盖的返回原字符） */
private fun pinyinFirstLetter(c: Char): String {
    // 常见启动器相关汉字的拼音首字母映射
    val map = mapOf(
        '启' to "q", '动' to "d", '新' to "x", '闻' to "w", '联' to "l", '机' to "j",
        '服' to "f", '务' to "w", '器' to "q", '下' to "x", '载' to "z", '安' to "a",
        '装' to "z", '内' to "n", '容' to "r", '资' to "z", '源' to "y", '存' to "c",
        '档' to "d", '统' to "t", '计' to "j", '账' to "z", '号' to "h", '登' to "d",
        '录' to "l", '微' to "w", '软' to "r", '设' to "s", '置' to "z", '偏' to "p",
        '好' to "h", '配' to "p", '终' to "z", '端' to "d", '命' to "m", '令' to "l",
        '行' to "h", '插' to "c", '件' to "j", '扩' to "k", '展' to "z", '模' to "m",
        '组' to "z", '整' to "z", '合' to "h", '包' to "b", '光' to "g", '影' to "y",
        '着' to "z", '色' to "s", '材' to "c", '质' to "z", '数' to "s", '据' to "j",
        '编' to "b", '辑' to "j", '世' to "s", '界' to "j", '截' to "j", '图' to "t",
        '扫' to "s", '描' to "m", '刷' to "s", '新' to "x", '主' to "z", '题' to "t",
        '暗' to "a", '黑' to "h", '切' to "q", '换' to "h", '开' to "k", '始' to "s"
    )
    return map[c] ?: c.toString().lowercase()
}

/**
 * 对搜索项进行过滤 + 评分排序，并按分组组织。
 */
private fun filterAndGroup(query: String, items: List<SearchItem>): List<SearchResultGroup> {
    if (query.isBlank()) return emptyList()
    val q = query.lowercase().trim()

    val scored = items.mapNotNull { item ->
        val score = matchScore(q, item)
        if (score > 0) item to score else null
    }.sortedByDescending { it.second }

    if (scored.isEmpty()) return emptyList()

    // 按分组聚合，保持分组顺序：操作 > 导航 > 版本 > 模组 > 实例
    val groupOrder = listOf(GROUP_ACTION, GROUP_NAV, GROUP_VERSION, GROUP_MOD, GROUP_INSTANCE)
    val groupLabels = mapOf(
        GROUP_ACTION to I18n.t("search.group.action"),
        GROUP_NAV to I18n.t("search.group.navigation"),
        GROUP_VERSION to I18n.t("search.group.version"),
        GROUP_MOD to I18n.t("search.group.mod"),
        GROUP_INSTANCE to I18n.t("search.group.instance")
    )

    return groupOrder.mapNotNull { groupKey ->
        val groupItems = scored.filter { it.first.group == groupKey }.map { it.first }
        if (groupItems.isEmpty()) null
        else SearchResultGroup(groupLabels[groupKey] ?: groupKey, groupItems)
    }
}

/**
 * 标题栏内嵌搜索框。
 *
 * 使用 BasicTextField 手写，完全控制高度和文字显示。
 * compact 模式：无边框填充样式，与窗口标题栏融合。
 * 聚焦时背景色平滑过渡动画。
 *
 * 支持搜索：快捷操作 / 页面导航 / 已安装版本 / 已安装模组
 *
 * @param modifier 布局修饰符
 * @param vm ViewModel，用于构建搜索索引
 * @param focusRequester 外部传入的 FocusRequester，用于 Ctrl+K 快捷键聚焦
 * @param compact 紧凑模式：与窗口标题栏融合
 */
@Composable
fun TopBarSearchField(
    modifier: Modifier = Modifier,
    vm: LauncherViewModel,
    focusRequester: FocusRequester,
    compact: Boolean = false
) {
    val allItems = buildSearchIndex(vm)
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // 过滤 + 分组 + 评分排序
    val groups = remember(query, allItems) { filterAndGroup(query, allItems) }
    // 扁平化结果列表，用于键盘导航
    val flatItems = remember(groups) { groups.flatMap { it.items } }

    // 查询变化时重置选中项并更新展开状态
    LaunchedEffect(query) {
        selectedIndex = 0
        expanded = query.isNotBlank() && flatItems.isNotEmpty()
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
                                    if (flatItems.isNotEmpty()) {
                                        val item = flatItems[selectedIndex.coerceIn(0, flatItems.lastIndex)]
                                        item.onSelect()
                                        query = ""
                                        expanded = false
                                    }
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (flatItems.isNotEmpty() && selectedIndex < flatItems.lastIndex) {
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

        // 下拉搜索结果（分组显示）
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SearchResultsContent(groups, flatItems, selectedIndex, query) {
                query = ""; expanded = false
            }
        }
    }
}

/**
 * 搜索结果下拉列表内容（分组显示 + 高亮匹配文本）。
 */
@Composable
private fun SearchResultsContent(
    groups: List<SearchResultGroup>,
    flatItems: List<SearchItem>,
    selectedIndex: Int,
    query: String,
    onClear: () -> Unit
) {
    if (flatItems.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(I18n.t("search.no_results"), color = MaterialTheme.colorScheme.outline)
        }
    } else {
        Text(
            I18n.t("search.results_count", flatItems.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        HorizontalDivider()
        Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            var globalIndex = 0
            groups.forEach { group ->
                // 分组标题
                Text(
                    group.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                group.items.forEach { item ->
                    val isCurrentSelected = globalIndex == selectedIndex
                    SearchItemRow(item, isCurrentSelected, query) {
                        item.onSelect()
                        onClear()
                    }
                    globalIndex++
                }
            }
        }
    }
}

/**
 * 单个搜索项行，支持高亮匹配文本。
 */
@Composable
private fun SearchItemRow(
    item: SearchItem,
    isSelected: Boolean,
    query: String,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                item.icon,
                null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = highlightMatch(item.title, query, isSelected),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.subtitle.isNotEmpty()) {
                    Text(
                        item.subtitle,
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

/**
 * 构建带高亮的 AnnotatedString：匹配的子串用 primary 色加粗显示。
 */
@Composable
private fun highlightMatch(text: String, query: String, isSelected: Boolean): androidx.compose.ui.text.AnnotatedString {
    if (query.isBlank()) {
        return buildAnnotatedString { append(text) }
    }
    val q = query.lowercase().trim()
    val textLower = text.lowercase()
    val matchColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                     else MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val remaining = textLower.substring(i)
            val idx = remaining.indexOf(q)
            if (idx < 0) {
                append(text.substring(i))
                break
            }
            // 普通文本段
            if (idx > 0) append(text.substring(i, i + idx))
            // 高亮匹配段
            withStyle(SpanStyle(color = matchColor, fontWeight = FontWeight.Bold)) {
                append(text.substring(i + idx, i + idx + q.length))
            }
            i += idx + q.length
        }
    }
}
