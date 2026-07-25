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
import com.pmcl.core.download.DownloadQueueManager
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.Rect

/**
 * 悬浮下载队列入口卡片（右下角）。
 *
 * 作为飞入动画的目标。显示当前队列任务数和整体进度。
 * 卡片到达时通过 [pulseTrigger] 触发缩放反馈动画。
 *
 * @param summary    队列摘要
 * @param pulseTrigger 脉冲触发计数（每次+1 触发一次缩放反馈）
 * @param onClick    点击回调（跳转到下载页）
 * @param onPositioned 位置回调（向 ViewModel 上报窗口坐标，供飞入动画使用）
 * @param forceVisible 队列为空时是否仍显示（飞入动画进行中需要可见目标）
 */
@Composable
fun FloatingDownloadQueue(
    summary: DownloadQueueManager.QueueSummary,
    pulseTrigger: Int,
    onClick: () -> Unit,
    onPositioned: (Rect, IntSize) -> Unit,
    modifier: Modifier = Modifier,
    forceVisible: Boolean = false
) {
    if (summary.total() == 0 && !forceVisible) return

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
                Text(
                    text = "${summary.active()}/${summary.total()} · ${(summary.overallProgress() * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
            // 微型进度环
            CircularProgressIndicator(
                progress = { summary.overallProgress().toFloat() },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
