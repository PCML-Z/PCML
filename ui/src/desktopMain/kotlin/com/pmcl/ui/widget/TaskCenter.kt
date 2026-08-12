package com.pmcl.ui.widget

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.download.DownloadQueueManager
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassContainerColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 任务中心：从右侧滑入的抽屉面板（内嵌于主窗口，非独立窗口）。
 * - 下载队列：接入 DownloadQueueManager 的真实任务，支持暂停/继续/取消/移除
 * - 运行中：游戏进程状态
 * - 通知：崩溃事件与重要状态变更（不再混入日志刷屏）
 * - 入场：连续高速缓出，无关键帧速度断点
 * - 出场：连续加速退出
 */
@Composable
fun TaskCenterPanel(
    visible: Boolean,
    vm: LauncherViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queueTasks by vm.queueTasks.collectAsState()
    val queueSummary by vm.queueSummary.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    val status by vm.status.collectAsState()
    val crashEvent by vm.crashEvent.collectAsState()

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss") }

    // 通知：只保留崩溃事件与当前状态变更，移除日志刷屏
    val notifications = remember(crashEvent, status) {
        buildList {
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
            if (status.isNotBlank()) {
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
        }.take(30)
    }

    val activeCount = queueSummary.active()
    val finishedRemovable = queueSummary.done + queueSummary.cancelled + queueSummary.failed
    val notifCount = notifications.size
    val selectedVersion = vm.selectedVersion.value

    // 纯色背景：与玻璃主题解耦，保证内容可读性
    val panelBg = MaterialTheme.colorScheme.surface

    Box(modifier.fillMaxSize()) {
        // 动画状态只在 GPU 图层读取，避免每帧重组整个任务列表。
        val panelTransition = updateTransition(
            targetState = visible,
            label = "taskCenterTransition"
        )
        // scrim 与面板共用 FastOutSlowInEasing + 相近时长，避免遮罩与滑入节奏错位
        val scrimAlpha = panelTransition.animateFloat(
            transitionSpec = {
                if (targetState) {
                    tween(240, easing = FastOutSlowInEasing)
                } else {
                    tween(200, easing = FastOutSlowInEasing)
                }
            },
            label = "scrim"
        ) { shown -> if (shown) 0.32f else 0f }
        if (panelTransition.currentState || panelTransition.targetState) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha.value }
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )
        }

        // FastOutSlowInEasing 对称缓动：起止速度平缓，中段加速，无突兀的冲入/急停
        val slideProgress = panelTransition.animateFloat(
            transitionSpec = {
                if (targetState) {
                    tween(260, easing = FastOutSlowInEasing)
                } else {
                    tween(200, easing = FastOutSlowInEasing)
                }
            },
            label = "panelSlide"
        ) { shown -> if (shown) 0f else 1f }
        // 浮动卡片：四周留边距，四角圆角，明显阴影
        val panelShape = RoundedCornerShape(14.dp)
        Column(
            Modifier
                .width(400.dp)
                .align(Alignment.CenterEnd)
                .padding(16.dp)
                .fillMaxHeight()
                .graphicsLayer {
                    val progress = slideProgress.value
                    translationX = (this.size.width + 16.dp.toPx()) * progress
                    // alpha 线性跟随滑动，消除前段瞬间变不透明的生硬感
                    alpha = 1f - progress
                }
                .shadow(20.dp, panelShape, clip = false)
                .clip(panelShape)
                .background(panelBg)
        ) {
            // 标题栏：左侧标题 + 活跃任务徽标，右侧仅保留一个关闭按钮
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "任务中心",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                if (activeCount > 0) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            activeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(18.dp))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 紧凑工具条：仅当有任务时显示，提供批量操作
            if (queueTasks.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append("活跃 ${queueSummary.active()}")
                            if (queueSummary.done > 0) append(" · 完成 ${queueSummary.done}")
                            if (queueSummary.failed > 0) append(" · 失败 ${queueSummary.failed}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { vm.pauseAllQueue() },
                        enabled = activeCount > 0,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Pause, "全部暂停", modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { vm.clearFinishedQueue() },
                        enabled = finishedRemovable > 0,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Delete, "清除已完成", modifier = Modifier.size(16.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // ===== 下载队列 =====
                item { SectionHeader("下载队列", badgeCount = queueTasks.size) }
                if (queueTasks.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.CloudDownload,
                            text = "暂无下载任务",
                            hint = "在版本或市场页面安装即可加入队列"
                        )
                    }
                } else {
                    items(queueTasks, key = { it.id }) { task ->
                        QueueTaskRow(
                            task = task,
                            onPause = { vm.pauseQueueTask(task.id) },
                            onResume = { vm.resumeQueueTask(task.id) },
                            onCancel = { vm.cancelQueueTask(task.id) },
                            onRemove = { vm.removeQueueTask(task.id) }
                        )
                    }
                }

                // ===== 运行中 =====
                if (gameRunning) {
                    item { Spacer(Modifier.height(8.dp)) }
                    item { SectionHeader("运行中", badgeCount = 1) }
                    item { GameRunningRow(selectedVersion) }
                }

                // ===== 通知 =====
                item { Spacer(Modifier.height(8.dp)) }
                item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                item { SectionHeader("通知", badgeCount = notifCount) }
                if (notifications.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.NotificationsNone,
                            text = "暂无通知"
                        )
                    }
                } else {
                    items(
                        notifications,
                        key = { "${it.time}_${it.title}_${it.message.hashCode()}" }
                    ) { item ->
                        HistoryItemCard(item)
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
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (badgeCount > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, text: String, hint: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 下载队列任务行：状态指示 + 名称 + 控制按钮，进度条与字节信息在下。
 */
@Composable
private fun QueueTaskRow(
    task: DownloadQueueManager.QueueTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    val sColor = statusColor(task.status)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).glassCardBorder(),
        shape = RoundedCornerShape(10.dp),
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIndicator(task.status)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val msg = task.message
                    if (!msg.isNullOrBlank()) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = sColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 控制按钮组
                when (task.status) {
                    DownloadQueueManager.TaskStatus.RUNNING,
                    DownloadQueueManager.TaskStatus.QUEUED -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Pause, "暂停", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "取消", modifier = Modifier.size(16.dp))
                        }
                    }
                    DownloadQueueManager.TaskStatus.PAUSED,
                    DownloadQueueManager.TaskStatus.FAILED -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.PlayArrow, "继续", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Delete, "移除", modifier = Modifier.size(16.dp))
                        }
                    }
                    DownloadQueueManager.TaskStatus.DONE,
                    DownloadQueueManager.TaskStatus.CANCELLED -> {
                        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Delete, "移除", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // 进度条
            if (task.status == DownloadQueueManager.TaskStatus.RUNNING
                || task.status == DownloadQueueManager.TaskStatus.PAUSED
            ) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress().toFloat() },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = sColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        statusText(task.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = sColor
                    )
                    if (task.totalBytes > 0) {
                        Text(
                            "${(task.progress() * 100).toInt()}% · ${formatBytes(task.completedBytes)} / ${formatBytes(task.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 失败错误信息
            if (task.status == DownloadQueueManager.TaskStatus.FAILED && task.errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    task.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 游戏运行中条目 */
@Composable
private fun GameRunningRow(version: String?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).glassCardBorder(),
        shape = RoundedCornerShape(10.dp),
        colors = glassCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        ),
        elevation = glassCardElevation()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "游戏运行中",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    version ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
    val bg = when (item.tone) {
        HistoryTone.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        HistoryTone.Warn -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        HistoryTone.Info -> Color.Transparent
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(item.icon, null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = tint)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(
                        item.timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                    Text(item.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = tint)
                }
                Spacer(Modifier.height(2.dp))
                Text(item.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 3)
            }
        }
    }
}

/** 状态指示点 */
@Composable
private fun StatusIndicator(status: DownloadQueueManager.TaskStatus) {
    Surface(color = statusColor(status), shape = CircleShape, modifier = Modifier.size(10.dp)) {}
}

/** 状态颜色 */
@Composable
private fun statusColor(status: DownloadQueueManager.TaskStatus): Color {
    return when (status) {
        DownloadQueueManager.TaskStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
        DownloadQueueManager.TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        DownloadQueueManager.TaskStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
        DownloadQueueManager.TaskStatus.DONE -> MaterialTheme.colorScheme.primary
        DownloadQueueManager.TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        DownloadQueueManager.TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun statusText(status: DownloadQueueManager.TaskStatus): String = when (status) {
    DownloadQueueManager.TaskStatus.QUEUED -> "排队中"
    DownloadQueueManager.TaskStatus.RUNNING -> "运行中"
    DownloadQueueManager.TaskStatus.PAUSED -> "已暂停"
    DownloadQueueManager.TaskStatus.DONE -> "已完成"
    DownloadQueueManager.TaskStatus.FAILED -> "失败"
    DownloadQueueManager.TaskStatus.CANCELLED -> "已取消"
}

/** 格式化字节数 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

private enum class HistoryTone { Error, Warn, Info }

private data class HistoryItem(
    val time: Long,
    val timeText: String,
    val icon: ImageVector,
    val title: String,
    val message: String,
    val tone: HistoryTone
)
