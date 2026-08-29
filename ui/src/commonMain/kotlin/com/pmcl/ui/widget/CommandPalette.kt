package com.pmcl.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.launch

/**
 * 命令面板条目：一级导航页、二级分区、插件页面或插件动作。
 *
 * @param id       全局唯一标识（用于列表 key），约定前缀 nav: / sec: / page: / act:
 * @param title    主标题（已本地化）
 * @param subtitle 副标题（路径 / 插件 ID / 描述）
 * @param keywords 搜索提示（来自 PluginMenuAction.keywords 或路由名）
 * @param icon     列表图标
 * @param onSelect 选中执行回调（导航或动作）
 */
class PaletteEntry(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val keywords: List<String> = emptyList(),
    val icon: ImageVector? = null,
    val onSelect: () -> Unit,
)

/**
 * 全局命令面板（Cmd/Ctrl+K 唤起）。
 *
 * 模糊搜索所有 [PaletteEntry]：标题前缀命中 > 标题包含 > 关键词 > 副标题。
 * 支持空格分词（每个词都必须命中）、↑↓ 键盘导航、Enter 执行、Esc 关闭。
 *
 * 宿主（App.kt）负责构建条目与执行导航；本组件只做搜索与展示。
 */
@Composable
fun CommandPaletteOverlay(
    entries: List<PaletteEntry>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val scrimInteraction = remember { MutableInteractionSource() }

    val results = remember(entries, query) { filterEntries(entries, query) }

    // 查询词或数据源变化后重置选中项
    LaunchedEffect(query, entries) { selected = 0 }

    // 打开即聚焦输入框
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun runEntry(entry: PaletteEntry) {
        onDismiss()
        entry.onSelect()
    }

    fun moveSelection(delta: Int) {
        if (results.isEmpty()) return
        selected = (selected + delta).mod(results.size)
        scope.launch { listState.animateScrollToItem(selected) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = scrimInteraction, indication = null) { onDismiss() }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp)
                .widthIn(min = 360.dp, max = 600.dp)
                .fillMaxWidth(0.75f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(I18n.t("palette.placeholder")) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { e ->
                            when {
                                e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                                    onDismiss(); true
                                }
                                e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> {
                                    moveSelection(+1); true
                                }
                                e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp -> {
                                    moveSelection(-1); true
                                }
                                e.type == KeyEventType.KeyDown && e.key == Key.Enter -> {
                                    results.getOrNull(selected)?.let { runEntry(it) }; true
                                }
                                else -> false
                            }
                        }
                )

                if (results.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            I18n.t("palette.no_results"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        contentPadding = PaddingValues(bottom = 6.dp)
                    ) {
                        itemsIndexed(results, key = { _, it -> it.id }) { idx, entry ->
                            PaletteResultRow(
                                entry = entry,
                                isSelected = idx == selected,
                                onClick = { runEntry(entry) }
                            )
                        }
                    }
                }

                Text(
                    text = I18n.t("palette.hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun PaletteResultRow(
    entry: PaletteEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
                MaterialTheme.shapes.medium
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = entry.icon ?: Icons.Filled.Search,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 默认条目图标按来源区分：插件动作用 PlayArrow，插件页面用 Extension。 */
object PaletteIcons {
    val ACTION = Icons.Filled.PlayArrow
    val PLUGIN_PAGE = Icons.Filled.Extension
}

/** 空查询返回全量；否则按空格分词，每个词都必须命中至少一个字段。 */
private fun filterEntries(entries: List<PaletteEntry>, rawQuery: String): List<PaletteEntry> {
    val q = rawQuery.trim().lowercase()
    if (q.isEmpty()) return entries
    val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return entries
    return entries
        .mapNotNull { e ->
            var total = 0
            for (t in tokens) {
                val s = scoreEntry(e, t)
                if (s <= 0) return@mapNotNull null
                total += s
            }
            total to e
        }
        .sortedByDescending { it.first }
        .map { it.second }
}

private fun scoreEntry(entry: PaletteEntry, token: String): Int {
    val title = entry.title.lowercase()
    return when {
        title.startsWith(token) -> 100
        title.contains(token) -> 60
        entry.keywords.any { it.lowercase().contains(token) } -> 30
        entry.subtitle.lowercase().contains(token) -> 20
        else -> 0
    }
}
