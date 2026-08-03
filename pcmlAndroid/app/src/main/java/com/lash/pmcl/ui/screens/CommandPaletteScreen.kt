package com.lash.pmcl.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class CommandItem(
    val title: String,
    val subtitle: String = "",
    val icon: ImageVector,
    val group: String = "",
    val keywords: List<String> = emptyList(),
    val onSelect: () -> Unit
)

private const val GROUP_ACTION = "快捷操作"
private const val GROUP_NAV = "页面导航"

@Composable
fun CommandPaletteScreen(
    visible: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit
) {
    if (!visible) return

    val focusRequester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }

    val items = remember {
        listOf(
            CommandItem("启动游戏", "打开启动页面", Icons.Filled.PlayArrow, GROUP_ACTION,
                listOf("play","launch","start","开始","游戏","start")) { onNavigate("launch") },
            CommandItem("下载版本", "安装 Minecraft 版本", Icons.Filled.Build, GROUP_ACTION,
                listOf("download","install","版本","安装")) { onNavigate("download") },
            CommandItem("设置", "打开设置页面", Icons.Filled.Settings, GROUP_ACTION,
                listOf("settings","config","设置","配置")) { onNavigate("settings") },
            CommandItem("控制台", "查看游戏日志", Icons.Filled.Dns, GROUP_ACTION,
                listOf("console","log","日志","终端","terminal")) { onNavigate("terminal") },
            CommandItem("切换深色模式", "切换明暗主题", Icons.Filled.DarkMode, GROUP_ACTION,
                listOf("dark","theme","主题","暗黑","暗色")) { onNavigate("theme") },

            CommandItem("启动", "游戏启动页", Icons.Filled.PlayArrow, GROUP_NAV,
                listOf("launch","play","启动")) { onNavigate("launch") },
            CommandItem("新闻", "PMCL 新闻", Icons.Filled.Info, GROUP_NAV,
                listOf("news","新闻","资讯")) { onNavigate("news") },
            CommandItem("服务器", "服务器列表", Icons.Filled.Dns, GROUP_NAV,
                listOf("server","服务器","联机")) { onNavigate("servers") },
            CommandItem("下载", "版本管理·模组市场·下载队列", Icons.Filled.Download, GROUP_NAV,
                listOf("download","下载","版本","mods")) { onNavigate("download") },
            CommandItem("内容", "模组·整合包·光影·资源包·数据包·配置", Icons.Filled.Folder, GROUP_NAV,
                listOf("content","内容","mod","shader","resource")) { onNavigate("content") },
            CommandItem("存档", "世界管理·截图", Icons.Filled.Public, GROUP_NAV,
                listOf("saves","存档","世界","world","screenshot")) { onNavigate("saves") },
            CommandItem("实例", "独立实例管理", Icons.Filled.Dashboard, GROUP_NAV,
                listOf("instances","实例","instance")) { onNavigate("instances") },
            CommandItem("统计", "游戏时长统计", Icons.Filled.BarChart, GROUP_NAV,
                listOf("stats","统计","statistics")) { onNavigate("statistics") },
            CommandItem("账号", "账号管理", Icons.Filled.Person, GROUP_NAV,
                listOf("account","账号","登录","login")) { onNavigate("accounts") },
        )
    }

    val filtered = remember(query, items) {
        if (query.isBlank()) items
        else {
            val q = query.lowercase().trim()
            items.mapNotNull { item ->
                val score = matchScore(q, item)
                if (score > 0) item to score else null
            }.sortedByDescending { it.second }.map { it.first }
        }
    }

    val groups = remember(filtered) {
        val actionItems = filtered.filter { it.group == GROUP_ACTION }
        val navItems = filtered.filter { it.group == GROUP_NAV }
        listOfNotNull(
            if (actionItems.isNotEmpty()) GROUP_ACTION to actionItems else null,
            if (navItems.isNotEmpty()) GROUP_NAV to navItems else null,
        )
    }

    LaunchedEffect(query) { selectedIndex = 0 }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                // 搜索框
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, Modifier.size(22.dp),
                         tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("搜索页面或操作...", color = MaterialTheme.colorScheme.outline,
                                 style = MaterialTheme.typography.bodyMedium)
                        }
                        BasicTextField(
                            value = query, onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, "清除", Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // 结果
                LazyColumn {
                    groups.forEach { (label, groupItems) ->
                        item(key = "header-$label") {
                            Text(label, style = MaterialTheme.typography.labelSmall,
                                 fontWeight = FontWeight.SemiBold,
                                 color = MaterialTheme.colorScheme.primary,
                                 modifier = Modifier.padding(vertical = 4.dp))
                        }
                        items(groupItems, key = { it.title + it.subtitle }) { cmd ->
                            Surface(
                                color = Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { cmd.onSelect(); onDismiss() }
                            ) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(cmd.icon, null, Modifier.size(20.dp),
                                         tint = MaterialTheme.colorScheme.outline)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(highlightMatch(cmd.title, query),
                                             style = MaterialTheme.typography.bodyMedium,
                                             fontWeight = FontWeight.Medium)
                                        if (cmd.subtitle.isNotEmpty()) {
                                            Text(cmd.subtitle, style = MaterialTheme.typography.labelSmall,
                                                 color = MaterialTheme.colorScheme.outline)
                                        }
                                    }
                                    Icon(Icons.Filled.KeyboardArrowRight, null, Modifier.size(16.dp),
                                         tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }
            }
        }

        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

private fun matchScore(query: String, item: CommandItem): Int {
    val q = query.lowercase().trim()
    if (q.isEmpty()) return 0
    val title = item.title.lowercase()
    var score = 0
    if (title == q) score += 100
    if (item.subtitle.lowercase() == q) score += 50
    if (item.keywords.any { it.lowercase() == q }) score += 60
    if (title.startsWith(q)) score += 40
    if (title.contains(q)) score += 15
    if (item.keywords.any { it.lowercase().contains(q) }) score += 10
    // 拼音首字母简写
    if (q.length in 1..4 && q.all { it.isLetter() }) {
        val abbr = title.mapNotNull { c -> pinyinFirstLetter(c) }.joinToString("")
        if (abbr.startsWith(q)) score += 30
    }
    return score
}

private fun pinyinFirstLetter(c: Char): String? {
    val map = mapOf(
        '启' to "q", '动' to "d", '新' to "x", '闻' to "w", '联' to "l", '机' to "j",
        '服' to "f", '务' to "w", '器' to "q", '下' to "x", '载' to "z", '安' to "a",
        '装' to "z", '内' to "n", '容' to "r", '存' to "c", '档' to "d", '统' to "t",
        '计' to "j", '账' to "z", '号' to "h", '登' to "d", '录' to "l", '微' to "w",
        '软' to "r", '设' to "s", '置' to "z", '终' to "z", '端' to "d", '命' to "m",
        '令' to "l", '模' to "m", '组' to "z", '整' to "z", '合' to "h", '包' to "b",
        '光' to "g", '影' to "y", '世' to "s", '界' to "j", '实' to "s", '例' to "l",
        '截' to "j", '图' to "t", '主' to "z", '题' to "t", '暗' to "a", '黑' to "h",
        '切' to "q", '换' to "h", '版' to "b", '本' to "b", '管' to "g", '理' to "l",
        '市' to "s", '场' to "c", '列' to "l", '表' to "b", '制' to "z", '台' to "t",
    )
    if (c.code > 127) return map[c] ?: c.toString().lowercase()
    return if (c.isLetter()) c.lowercaseChar().toString() else null
}

@Composable
private fun highlightMatch(text: String, query: String) = buildAnnotatedString {
    if (query.isBlank()) { append(text); return@buildAnnotatedString }
    val q = query.lowercase().trim()
    var i = 0
    while (i < text.length) {
        val idx = text.lowercase().substring(i).indexOf(q)
        if (idx < 0) { append(text.substring(i)); break }
        if (idx > 0) append(text.substring(i, i + idx))
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
            append(text.substring(i + idx, i + idx + q.length))
        }
        i += idx + q.length
    }
}
