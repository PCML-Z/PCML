package com.lash.pmcl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 下载队列页面（Android）：对齐桌面版 DownloadsPage。
 *
 * 注意：Android 版 DownloadManager 为无状态下载工具，不提供队列管理 API
 * （无任务列表 / 暂停 / 继续 / 取消 / 移除）。因此队列操作按钮均显示但禁用，
 * 并标注「（不支持）」；任务列表数据源暂缺，显示空态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen() {
    // Android DownloadManager 无队列管理 API，当前无任务数据源
    val tasks: List<QueueTask> = emptyList()
    val summary = QueueSummary()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("下载") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(16.dp),
        ) {
            // ===== 总览卡片 =====
            QueueSummaryCard(
                summary = summary,
                onPauseAll = {},
                onResumeAll = {},
                onCancelAll = {},
                onClearFinished = {},
            )

            Spacer(Modifier.height(16.dp))

            // ===== 任务列表 =====
            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "暂无下载任务",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "在「版本」或「模组」页面点击安装即可加入队列",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        QueueTaskCard(
                            task = task,
                            onPause = {},
                            onResume = {},
                            onCancel = {},
                            onRemove = {},
                        )
                    }
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
    summary: QueueSummary,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "下载队列",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append("共 ${summary.total()} 项")
                            if (summary.active() > 0) append("  |  进行中 ${summary.active()}")
                            if (summary.done > 0) append("  |  已完成 ${summary.done}")
                            if (summary.failed > 0) append("  |  失败 ${summary.failed}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 整体进度百分比
                if (summary.totalBytes > 0) {
                    Text(
                        "${(summary.overallProgress() * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 总进度条
            if (summary.totalBytes > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { summary.overallProgress().toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatBytes(summary.completedBytes)} / ${formatBytes(summary.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 批量操作按钮
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPauseAll,
                    enabled = QUEUE_SUPPORTED && summary.active() > 0,
                ) {
                    Icon(Icons.Filled.Pause, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(queueLabel("全部暂停"))
                }
                OutlinedButton(
                    onClick = onResumeAll,
                    enabled = QUEUE_SUPPORTED && (summary.paused > 0 || summary.failed > 0),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(queueLabel("全部继续"))
                }
                OutlinedButton(
                    onClick = onCancelAll,
                    enabled = QUEUE_SUPPORTED && summary.active() > 0,
                ) {
                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(queueLabel("全部取消"))
                }
                OutlinedButton(
                    onClick = onClearFinished,
                    enabled = QUEUE_SUPPORTED &&
                        (summary.done > 0 || summary.cancelled > 0 || summary.failed > 0),
                ) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(queueLabel("清除已完成"))
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
    task: QueueTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧：状态指示点 + 名称 + 消息
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusIndicator(task.status)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            task.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            task.message ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor(task.status),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 右侧：进度百分比
                if (task.totalBytes > 0) {
                    Text(
                        "${(task.progress() * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor(task.status),
                    )
                }
            }

            // 进度条
            if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PAUSED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress().toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor(task.status),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                if (task.totalBytes > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${formatBytes(task.completedBytes)} / ${formatBytes(task.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 失败错误信息
            if (task.status == TaskStatus.FAILED && !task.errorMessage.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    task.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 控制按钮
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (task.status) {
                    TaskStatus.RUNNING, TaskStatus.QUEUED -> {
                        TextButton(onClick = onPause, enabled = QUEUE_SUPPORTED) {
                            Icon(Icons.Filled.Pause, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(queueLabel("暂停"))
                        }
                        TextButton(onClick = onCancel, enabled = QUEUE_SUPPORTED) {
                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(queueLabel("取消"))
                        }
                    }
                    TaskStatus.PAUSED, TaskStatus.FAILED -> {
                        TextButton(onClick = onResume, enabled = QUEUE_SUPPORTED) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(queueLabel("继续"))
                        }
                        TextButton(onClick = onRemove, enabled = QUEUE_SUPPORTED) {
                            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(queueLabel("移除"))
                        }
                    }
                    TaskStatus.DONE, TaskStatus.CANCELLED -> {
                        TextButton(onClick = onRemove, enabled = QUEUE_SUPPORTED) {
                            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(queueLabel("移除"))
                        }
                    }
                }
            }
        }
    }
}

/** 状态指示点 */
@Composable
private fun StatusIndicator(status: TaskStatus) {
    Surface(
        color = statusColor(status),
        shape = CircleShape,
        modifier = Modifier.size(10.dp),
    ) {}
}

/** 状态颜色：QUEUED/CANCELLED=灰，RUNNING/DONE=主色，PAUSED=三级色，FAILED=error */
@Composable
private fun statusColor(status: TaskStatus): Color {
    return when (status) {
        TaskStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        TaskStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
        TaskStatus.DONE -> MaterialTheme.colorScheme.primary
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
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

/**
 * Android 版 DownloadManager 是否提供队列管理 API。
 * 当前为 false：无任务列表 / 暂停 / 继续 / 取消 / 移除等能力。
 */
private val QUEUE_SUPPORTED = false

/** 队列操作按钮文案：不支持时追加「（不支持）」标注。 */
private fun queueLabel(base: String): String =
    if (QUEUE_SUPPORTED) base else "$base（不支持）"

// ===== 数据模型（对齐桌面版 DownloadQueueManager 的 QueueTask / QueueSummary / TaskStatus） =====

private enum class TaskStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

private data class QueueTask(
    val id: String,
    val name: String,
    val status: TaskStatus,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
    val errorMessage: String? = null,
) {
    /** 进度百分比 0~1 */
    fun progress(): Double =
        if (totalBytes <= 0) 0.0 else minOf(1.0, completedBytes.toDouble() / totalBytes)
}

private data class QueueSummary(
    val queued: Int = 0,
    val running: Int = 0,
    val paused: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val totalBytes: Long = 0L,
    val completedBytes: Long = 0L,
) {
    fun total(): Int = queued + running + paused + done + failed + cancelled
    fun active(): Int = queued + running
    fun overallProgress(): Double =
        if (totalBytes <= 0) 0.0 else minOf(1.0, completedBytes.toDouble() / totalBytes)
}
