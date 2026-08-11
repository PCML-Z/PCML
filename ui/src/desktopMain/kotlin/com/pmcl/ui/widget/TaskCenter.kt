package com.pmcl.ui.widget

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassContainerColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 任务中心：从右侧滑入的抽屉面板（内嵌于主窗口，非独立窗口）。
 * - 调度队列：当前活动任务（下载、安装、启动、游戏运行）
 * - 历史通知：近期日志、崩溃事件、状态变更
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
            crashEvent?.let {
                add(
                    HistoryItem(
                        time = System.currentTimeMillis(),
                        timeText = timeFmt.format(Date()),
                        icon = Icons.Filled.Warning,
                        title = "游戏崩溃 \u00b7 v${it.versionId}",
                        message = it.report?.causes?.firstOrNull()
                            ?: "exit code ${it.exitCode} \u00b7 ${it.recentLogs.lastOrNull() ?: ""}",
                        tone = HistoryTone.Error
                    )
                )
            }
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
                        message = vm.selectedVersion.value ?: "\u2014",
                        progress = 1f,
                        stage = "运行"
                    )
                )
            }
        }
    }

    val taskCount = activeTasks.size
    val notifCount = history.size

    // 与窗口共享同一 ThemeState：普通主题使用实色 surface，玻璃主题自动透出背景。
    val panelBg = glassContainerColor(MaterialTheme.colorScheme.surface, glassAlpha = 0.82f)

    Box(modifier.fillMaxSize()) {
        val enterEasing = remember { CubicBezierEasing(0.16f, 1f, 0.3f, 1f) }
        val exitEasing = remember { CubicBezierEasing(0.7f, 0f, 0.84f, 0f) }

        // 动画状态只在 GPU 图层读取，避免每帧重组整个任务列表。
        val panelTransition = updateTransition(
            targetState = visible,
            label = "taskCenterTransition"
        )
        val scrimAlpha = panelTransition.animateFloat(
            transitionSpec = {
                if (targetState) {
                    tween(220, easing = enterEasing)
                } else {
                    tween(170, easing = exitEasing)
                }
            },
            label = "scrim"
        ) { shown -> if (shown) 0.35f else 0f }
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

        // 单段连续曲线保持速度感，同时消除回弹与缩放造成的顿挫。
        val slideProgress = panelTransition.animateFloat(
            transitionSpec = {
                if (targetState) {
                    tween(300, easing = enterEasing)
                } else {
                    tween(210, easing = exitEasing)
                }
            },
            label = "panelSlide"
        ) { shown -> if (shown) 0f else 1f }
        val panelShape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
        Column(
            Modifier
                .fillMaxHeight()
                .width(400.dp)
                .align(Alignment.CenterEnd)
                .graphicsLayer {
                    val progress = slideProgress.value
                    translationX = this.size.width * progress
                    alpha = ((1f - progress) * 12.5f).coerceIn(0f, 1f)
                }
                .shadow(16.dp, panelShape, clip = false)
                .clip(panelShape)
                .background(panelBg)
        ) {
                // 标题栏
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "收起",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        "任务中心",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(18.dp))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // ===== 调度队列 =====
                    item { SectionHeader("调度队列", badgeCount = taskCount) }
                    if (activeTasks.isEmpty()) {
                        item { EmptyState(icon = Icons.Filled.Done, text = "无") }
                    } else {
                        items(activeTasks, key = { it.title }) { task ->
                            ActiveTaskCard(task)
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                    item {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                    }

                    // ===== 历史通知 =====
                    item { SectionHeader("历史通知", badgeCount = notifCount) }
                    if (history.isEmpty()) {
                        item { EmptyState(icon = Icons.Filled.NotificationsNone, text = "暂无通知") }
                    } else {
                        items(history, key = { "${it.time}_${it.title}_${it.message.hashCode()}" }) { item ->
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
        Icon(icon, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ActiveTaskCard(task: ActiveTask) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(task.icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    Text(
                        task.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Text(task.stage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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