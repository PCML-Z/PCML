package com.pmcl.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pmcl.core.download.DownloadQueueManager.TaskStatus
import com.pmcl.ui.viewmodel.QueueTaskSnapshot
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.Rect
import kotlinx.coroutines.delay

/**
 * 右下角小窗只统计这一轮下载。已完成的历史任务不计入 n/n。
 * 这一轮全部成功后短暂显示 n/n，然后关闭。失败或暂停时保持打开。
 */
@Composable
fun rememberFloatingQueueState(tasks: List<QueueTaskSnapshot>, flyActive: Boolean): FloatingQueueState {
    var batchIds by remember { mutableStateOf(emptySet<String>()) }
    val statusKey = tasks.joinToString(",") { "${it.id}:${it.status}" }
    LaunchedEffect(statusKey) {
        val incoming = tasks.filter { task ->
            task.id !in batchIds && (task.status == TaskStatus.QUEUED
                || task.status == TaskStatus.RUNNING
                || task.status == TaskStatus.PAUSED)
        }
        if (incoming.isNotEmpty()) {
            batchIds = batchIds + incoming.map { it.id }
            return@LaunchedEffect
        }
        if (batchIds.isEmpty()) return@LaunchedEffect
        val members = tasks.filter { it.id in batchIds }
        val busy = members.any {
            it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING || it.status == TaskStatus.PAUSED
        }
        val failed = members.any { it.status == TaskStatus.FAILED }
        if (busy || failed) return@LaunchedEffect
        val done = members.any { it.status == TaskStatus.DONE }
        if (!done) {
            batchIds = emptySet()
            return@LaunchedEffect
        }
        delay(1200)
        batchIds = emptySet()
    }
    val members = tasks.filter { it.id in batchIds && it.status != TaskStatus.CANCELLED }
    var completedBytes = 0L
    var totalBytes = 0L
    var finished = 0
    for (task in members) {
        completedBytes += task.completedBytes
        totalBytes += task.totalBytes
        if (task.status == TaskStatus.DONE) finished++
    }
    val progress = when {
        members.isNotEmpty() && finished == members.size -> 1f
        totalBytes <= 0L -> 0f
        else -> (completedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
    }
    return FloatingQueueState(
        visible = batchIds.isNotEmpty() || flyActive,
        finished = finished,
        total = members.size,
        progress = progress
    )
}

data class FloatingQueueState(
    val visible: Boolean,
    val finished: Int,
    val total: Int,
    val progress: Float
)

@Composable
fun FloatingDownloadQueue(
    finished: Int,
    total: Int,
    progress: Float,
    pulseTrigger: Int,
    onClick: () -> Unit,
    onPositioned: (Rect, IntSize) -> Unit,
    modifier: Modifier = Modifier,
    forceVisible: Boolean = false
) {
    if (total == 0 && !forceVisible) return

    // 脉冲缩放：轻微放大；从右下角原点缩放，避免放大后超出窗口
    var targetScale by remember { mutableFloatStateOf(1f) }
    val pulseScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        finishedListener = { if (targetScale > 1f) targetScale = 1f },
        label = "pulse"
    )
    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger > 0) {
            targetScale = 1.06f
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier
            .clickable { onClick() }
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                onPositioned(
                    Rect(
                        x = pos.x.toInt(),
                        y = pos.y.toInt(),
                        width = coords.size.width,
                        height = coords.size.height
                    ),
                    coords.size
                )
            }
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
                // 右下角锚定：放大向左上扩展，不顶出窗口边缘
                transformOrigin = TransformOrigin(1f, 1f)
            }
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(min = 120.dp, max = 200.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Queue, null, Modifier.size(16.dp),
                 tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(Modifier.weight(1f)) {
                Text(
                    text = I18n.t("download.queue_title"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (total > 0) {
                    Text(
                        text = "$finished/$total · ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
            // 微型进度环
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
