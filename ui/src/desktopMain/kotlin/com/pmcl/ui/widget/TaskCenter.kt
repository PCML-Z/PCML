package com.pmcl.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 任务中心：右侧抽屉式通知面板
 * 平时折叠为标题栏小按钮，点击展开为独立悬浮窗口
 * - 调度队列：当前活动任务（下载、安装、启动、游戏运行）
 * - 历史通知：近期日志、崩溃事件、状态变更
 */
@Composable
fun TaskCenterWindow(
    vm: LauncherViewModel,
    onDismiss: () -> Unit,
    parallaxBg: Boolean = false,
    glassOn: Boolean = false,
    useDark: Boolean = false
) {
    val installing by vm.installing.collectAsState()
    val installProgress by vm.installProgress.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    val status by vm.status.collectAsState()
    val gameLogs by vm.gameLogs.collectAsState()
    val crashEvent by vm.crashEvent.collectAsState()

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss") }

    // 历史通知：合并多个状态来源
    val history = remember(gameLogs, crashEvent, status) {
        buildList {
            // 崩溃事件优先
            crashEvent?.let {
                add(
                    HistoryItem(
                        time = System.currentTimeMillis(),
                        timeText = timeFmt.format(Date()),
                        icon = Icons.Filled.Warning,
                        title = "游戏崩溃 · v${it.versionId}",
                        message = it.report?.causes?.firstOrNull()
                            ?: "exit code ${it.exitCode} · ${it.recentLogs.lastOrNull() ?: ""}",
                        tone = HistoryTone.Error
                    )
                )
            }
            // 最近 10 条游戏日志
            gameLogs.takeLast(10).reversed().forEach { log ->
                add(
                    HistoryItem(
                        time = log.seq,
                        timeText = "#${log.seq}",
                        icon = Icons.Filled.Terminal,
                        title = "日志",
                        message = log.text.take(120),
                        tone = HistoryTone.Info
                    )
                )
            }
            // 最近状态文本（非空且与最新日志不重复）
            if (status.isNotBlank() && (gameLogs.isEmpty() || status != gameLogs.last().text)) {
                add(
                    HistoryItem(
                        time = System.currentTimeMillis(),
                        timeText = timeFmt.format(Date()),
                        icon = Icons.Filled.Info,
                        title = "状态",
                        message = status,
                        tone = HistoryTone.Info
                    )
                )
            }
        }.take(60)
    }

    // 调度队列：当前活动任务
    val activeTasks = remember(installing, installProgress, gameRunning) {
        buildList {
            if (installing) {
                val prog = installProgress
                add(
                    ActiveTask(
                        icon = Icons.Filled.CloudDownload,
                        title = "下载/安装版本",
                        message = prog?.message ?: "准备中...",
                        progress = (prog?.percent() ?: 0.0).toFloat() / 100f,
                        stage = if (prog != null) "进行中" else "排队中"
                    )
                )
            }
            if (gameRunning) {
                add(
                    ActiveTask(
                        icon = Icons.Filled.PlayArrow,
                        title = "游戏运行中",
                        message = vm.selectedVersion.value ?: "—",
                        progress = 1f,
                        stage = "运行"
                    )
                )
            }
        }
    }

    val taskCount = activeTasks.size
    val notifCount = history.size

    Window(
        onCloseRequest = onDismiss,
        title = "任务中心",
        undecorated = true,
        transparent = true,
        resizable = false,
        state = rememberWindowState(
            width = 420.dp,
            height = 560.dp,
            position = WindowPosition.Aligned(Alignment.CenterEnd)
        )
    ) {
        // 无边框窗口：圆角 14dp + 拖拽支持
        DisposableEffect(Unit) {
            val applyShape = {
                window.shape = RoundRectangle2D.Double(
                    0.0, 0.0,
                    window.width.toDouble(), window.height.toDouble(),
                    14.0, 14.0
                )
                window.background = java.awt.Color(0, 0, 0, 0)
            }
            applyShape()
            val listener = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) { applyShape() }
                override fun componentMoved(e: ComponentEvent?) { applyShape() }
            }
            window.addComponentListener(listener)
            onDispose { window.removeComponentListener(listener) }
        }

        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .border(0.5.dp, Color.Black.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                .background(
                    if (useDark) Color(0xFF1E1E1E)
                    else Color(0xFFFAFAFA)
                )
        ) {
            Column(Modifier.fillMaxSize()) {
                // ===== 标题栏：返回 + 标题 + 窗口控制 =====
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "收起",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        "任务中心",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { window.extendedState = Frame.ICONIFIED },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Minimize, "最小化", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(16.dp))
                    }
                }

                Divider(color = Color.Black.copy(alpha = 0.08f))

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // ===== 调度队列 =====
                    item {
                        SectionHeader("调度队列", badgeCount = taskCount)
                    }
                    if (activeTasks.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Filled.Done,
                                text = "无"
                            )
                        }
                    } else {
                        items(activeTasks, key = { it.title }) { task ->
                            ActiveTaskCard(task)
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                    item {
                        Divider(color = Color.Black.copy(alpha = 0.08f))
                        Spacer(Modifier.height(8.dp))
                    }

                    // ===== 历史通知 =====
                    item {
                        SectionHeader("历史通知", badgeCount = notifCount)
                    }
                    if (history.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Filled.NotificationsOff,
                                text = "暂无通知"
                            )
                        }
                    } else {
                        items(history, key = { it.time.toString() + it.title + it.message.hashCode() }) { item ->
                            HistoryItemCard(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, badgeCount: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (badgeCount > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun ActiveTaskCard(task: ActiveTask) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    task.icon,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        task.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Text(
                    task.stage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (task.progress > 0f && task.progress < 1f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
        }
    }
}

@Composable
private fun HistoryItemCard(item: HistoryItem) {
    val tint = when (item.tone) {
        HistoryTone.Error -> MaterialTheme.colorScheme.error
        HistoryTone.Warn -> MaterialTheme.colorScheme.tertiary
        HistoryTone.Info -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { /* 点击展开详情 */ }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            item.icon,
            null,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
            tint = tint
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    item.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = tint
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3
            )
        }
    }
}

private data class ActiveTask(
    val icon: ImageVector,
    val title: String,
    val message: String,
    val progress: Float,
    val stage: String
)

private enum class HistoryTone { Error, Warn, Info }

private data class HistoryItem(
    val time: Long,
    val timeText: String,
    val icon: ImageVector,
    val title: String,
    val message: String,
    val tone: HistoryTone
)