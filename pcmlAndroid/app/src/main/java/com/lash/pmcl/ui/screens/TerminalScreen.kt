package com.lash.pmcl.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lash.pmcl.core.launch.LogCollector
import kotlinx.coroutines.delay

/**
 * 控制台输出页面 — 从桌面端 TerminalPage 移植。
 * 实时显示游戏启动日志，支持搜索、过滤、清除、复制。
 */
@Composable
fun TerminalScreen() {
    var tick by remember { mutableIntStateOf(0) }
    var autoScroll by remember { mutableStateOf(true) }
    var filterText by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(true) }
    var showWarn by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) { tick++; delay(500) }
    }

    val entries = remember(tick) { LogCollector.all() }

    val filtered = remember(entries, filterText, showError, showInfo, showWarn) {
        entries.filter { entry ->
            (showInfo || !isInfoLine(entry.text)) &&
            (showWarn || !isWarnLine(entry.text)) &&
            (showError || !isErrorLine(entry.text)) &&
            (filterText.isBlank() || entry.text.contains(filterText, ignoreCase = true))
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 工具栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("控制台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("${entries.size} 行", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.weight(1f))
            IconToggleButton(checked = showInfo, onCheckedChange = { showInfo = !showInfo }) {
                Icon(Icons.Filled.Info, "信息", Modifier.size(18.dp),
                     tint = if (showInfo) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline)
            }
            IconToggleButton(checked = showWarn, onCheckedChange = { showWarn = !showWarn }) {
                val warnColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                Icon(Icons.Filled.Warning, "警告", Modifier.size(18.dp),
                     tint = if (showWarn) warnColor
                            else MaterialTheme.colorScheme.outline)
            }
            IconToggleButton(checked = showError, onCheckedChange = { showError = !showError }) {
                Icon(Icons.Filled.Error, "错误", Modifier.size(18.dp),
                     tint = if (showError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline)
            }
            IconToggleButton(checked = autoScroll, onCheckedChange = { autoScroll = !autoScroll }) {
                Icon(Icons.Filled.VerticalAlignBottom, "自动滚动", Modifier.size(18.dp),
                     tint = if (autoScroll) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { LogCollector.clear() }) {
                Icon(Icons.Filled.Delete, "清除", Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(4.dp))

        // 搜索栏
        OutlinedTextField(
            value = filterText, onValueChange = { filterText = it },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            placeholder = { Text("搜索日志...", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(16.dp)) },
            singleLine = true, shape = RoundedCornerShape(8.dp)
        )
        Spacer(Modifier.height(4.dp))

        // 日志列表
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Terminal, null, Modifier.size(48.dp),
                             tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text("暂无日志", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(4.dp))
                        Text("启动游戏后这里将显示控制台输出",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    }
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    ) {
                        items(filtered, key = { it.seq }) { entry ->
                            TerminalLogLine(entry.text)
                        }
                    }
                }
            }
        }

        if (filterText.isNotEmpty() && filtered.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text("${filtered.size}/${entries.size} 行匹配", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun TerminalLogLine(text: String) {
    val color = when {
        isErrorLine(text) -> MaterialTheme.colorScheme.error
        isWarnLine(text) -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        color = color,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
    )
}

private fun isErrorLine(text: String): Boolean {
    val lower = text.lowercase()
    return lower.contains("error") || lower.contains("exception") || lower.contains("fatal") ||
           lower.contains("crash") || lower.contains("could not") || lower.contains("failed")
}

private fun isWarnLine(text: String): Boolean {
    val lower = text.lowercase()
    return lower.contains("warn") || lower.contains("warning") || lower.contains("deprecated")
}

private fun isInfoLine(text: String): Boolean {
    return !isErrorLine(text) && !isWarnLine(text)
}
