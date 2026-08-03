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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.download.DownloadQueueState

/**
 * 下载队列页面：连接 DownloadQueueState，实时显示下载进度。
 * 与桌面端 DownloadsPage 行为一致。
 */
@Composable
fun DownloadsScreen() {
    var tick by remember { mutableIntStateOf(0) }
    // 触发定时刷新
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            tick++
            kotlinx.coroutines.delay(1000)
        }
    }

    val items = remember(tick) { DownloadQueueState.allItems() }
    val active = remember(tick) { DownloadQueueState.activeCount() }
    val total = remember(tick) { DownloadQueueState.totalCount() }
    val progress = remember(tick) { DownloadQueueState.overallProgress() }

    val totalBytes = remember(tick) { items.sumOf { it.totalSize } }
    val downloadedBytes = remember(tick) { items.sumOf { it.downloaded } }

    val doneCount = items.count { it.done && it.error == null }
    val failedCount = items.count { it.done && it.error != null }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Download, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("暂无下载任务", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("在「本地版本」或「模组市场」中安装即可加入队列",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // 总览卡片
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween,
                        Alignment.CenterVertically) {
                        Text("下载队列", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        if (total > 0) {
                            Text("${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("共 $total 项  |  进行中 $active  |  已完成 $doneCount  |  失败 $failedCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                    if (downloadedBytes > 0 || totalBytes > 0) {
                        Text("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }

                    // 批量操作
                    val hasDone = items.any { it.done }
                    if (hasDone) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                items.filter { it.done }.forEach { DownloadQueueState.remove(it.id) }
                            }) {
                                Icon(Icons.Filled.Delete, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("清除已完成")
                            }
                        }
                    }
                    if (total > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { DownloadQueueState.clearCompleted() }) {
                            Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清除已完成")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 任务列表
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item ->
                    QueueTaskCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun QueueTaskCard(item: DownloadQueueState.Item) {
    val statusColor = when {
        item.error != null -> MaterialTheme.colorScheme.error
        item.done -> Color(0xFF55C57A)
        else -> MaterialTheme.colorScheme.primary
    }
    val fraction = if (item.totalSize > 0) (item.downloaded.toFloat() / item.totalSize).coerceIn(0f, 1f) else 0f
    val statusText = when {
        item.error != null -> item.error ?: "错误"
        item.done -> "已完成"
        item.downloaded > 0 -> "下载中 ${(fraction * 100).toInt()}%"
        else -> "等待中"
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        Modifier.size(10.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = statusColor
                    ) {}
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(statusText, style = MaterialTheme.typography.bodySmall,
                            color = statusColor, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (item.done && item.error == null) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp),
                        tint = Color(0xFF55C57A))
                } else if (item.error != null) {
                    Icon(Icons.Filled.Close, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                } else {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (!item.done && item.totalSize > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor,
                )
                if (item.downloaded > 0) {
                    Text("${formatBytes(item.downloaded)} / ${formatBytes(item.totalSize)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            if (item.done) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.End) {
                    TextButton(onClick = { DownloadQueueState.remove(item.id) }) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("移除")
                    }
                }
            }
            if (item.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(item.error!!, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}
