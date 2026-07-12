package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.download.DownloadQueueManager
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 下载队列页面：展示所有下载/安装任务，支持暂停/继续/取消。
 */
@Composable
fun DownloadsPage(vm: LauncherViewModel) {
    val tasks by vm.queueTasks.collectAsState()
    val summary by vm.queueSummary.collectAsState()

    // 进入页面时注册监听器
    LaunchedEffect(Unit) {
        vm.initDownloadQueue()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // ===== 总览卡片 =====
        QueueSummaryCard(summary,
            onPauseAll = { vm.pauseAllQueue() },
            onResumeAll = { vm.resumeAllQueue() },
            onCancelAll = { vm.cancelAllQueue() },
            onClearFinished = { vm.clearFinishedQueue() }
        )

        Spacer(Modifier.height(16.dp))

        // ===== 任务列表 =====
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(I18n.t("queue.empty"), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(I18n.t("queue.empty_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    QueueTaskCard(
                        task = task,
                        onPause = { vm.pauseQueueTask(task.id) },
                        onResume = { vm.resumeQueueTask(task.id) },
                        onCancel = { vm.cancelQueueTask(task.id) },
                        onRemove = { vm.removeQueueTask(task.id) }
                    )
                }
            }
        }
    }
}

/**
 * 队列总览卡片：显示整体进度和批量操作按钮。
 */
@Composable
private fun QueueSummaryCard(
    summary: DownloadQueueManager.QueueSummary,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(I18n.t("queue.title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(buildString {
                        append(I18n.t("queue.total_items", summary.total()))
                        if (summary.active() > 0) append("  |  " + I18n.t("queue.active", summary.active()))
                        if (summary.done > 0) append("  |  " + I18n.t("queue.done", summary.done))
                        if (summary.failed > 0) append("  |  " + I18n.t("queue.failed", summary.failed))
                    }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 整体进度百分比
                if (summary.totalBytes > 0) {
                    Text("${(summary.overallProgress() * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            // 总进度条
            if (summary.totalBytes > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { summary.overallProgress().toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text("${formatBytes(summary.completedBytes)} / ${formatBytes(summary.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 批量操作按钮
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPauseAll, enabled = summary.active() > 0) {
                    Icon(Icons.Filled.Pause, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("queue.pause_all"))
                }
                OutlinedButton(onClick = onResumeAll, enabled = summary.paused > 0 || summary.failed > 0) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("queue.resume_all"))
                }
                OutlinedButton(onClick = onCancelAll, enabled = summary.active() > 0) {
                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("queue.cancel_all"))
                }
                OutlinedButton(onClick = onClearFinished,
                    enabled = summary.done > 0 || summary.cancelled > 0 || summary.failed > 0) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("queue.clear_finished"))
                }
            }
        }
    }
}

/**
 * 单个任务卡片：显示名称、状态、进度、控制按钮。
 */
@Composable
private fun QueueTaskCard(
    task: DownloadQueueManager.QueueTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：类型图标 + 名称 + 状态
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusIndicator(task.status)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(task.message ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor(task.status),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                // 右侧：进度百分比
                if (task.totalBytes > 0) {
                    Text("${(task.progress() * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor(task.status))
                }
            }

            // 进度条
            if (task.status == DownloadQueueManager.TaskStatus.RUNNING
                || task.status == DownloadQueueManager.TaskStatus.PAUSED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress().toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor(task.status),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                if (task.totalBytes > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text("${formatBytes(task.completedBytes)} / ${formatBytes(task.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 失败错误信息
            if (task.status == DownloadQueueManager.TaskStatus.FAILED && task.errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(task.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }

            // 控制按钮
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                when (task.status) {
                    DownloadQueueManager.TaskStatus.RUNNING,
                    DownloadQueueManager.TaskStatus.QUEUED -> {
                        TextButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.pause"))
                        }
                        TextButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.cancel"))
                        }
                    }
                    DownloadQueueManager.TaskStatus.PAUSED,
                    DownloadQueueManager.TaskStatus.FAILED -> {
                        TextButton(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.resume"))
                        }
                        TextButton(onClick = onRemove) {
                            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.remove"))
                        }
                    }
                    DownloadQueueManager.TaskStatus.DONE,
                    DownloadQueueManager.TaskStatus.CANCELLED -> {
                        TextButton(onClick = onRemove) {
                            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.remove"))
                        }
                    }
                }
            }
        }
    }
}

/** 状态指示点 */
@Composable
private fun StatusIndicator(status: DownloadQueueManager.TaskStatus) {
    val color = statusColor(status)
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
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
