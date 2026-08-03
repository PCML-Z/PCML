package com.lash.pmcl.ui.widget

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
import com.lash.pmcl.ui.animation.Rect

data class QueueSummary(
    val active: Int,
    val total: Int,
    val progress: Float  // 0.0 ~ 1.0
) {
    fun totalCount(): Int = total
    fun overallProgress(): Float = progress
}

/**
 * 悬浮下载队列入口卡片（右下角）。
 * 与桌面端 com.pmcl.ui.widget.FloatingDownloadQueue 完全一致。
 */
@Composable
fun FloatingDownloadQueue(
    summary: QueueSummary,
    pulseTrigger: Int,
    onClick: () -> Unit,
    onPositioned: (Rect, IntSize) -> Unit,
    modifier: Modifier = Modifier,
    forceVisible: Boolean = false
) {
    if (summary.totalCount() == 0 && !forceVisible) return

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
                    text = "下载队列",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${summary.active}/${summary.total} · ${(summary.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
            CircularProgressIndicator(
                progress = { summary.progress },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
