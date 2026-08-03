package com.lash.pmcl.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

/**
 * 下载飞入动画浮层：覆盖内容区，渲染所有正在飞行的卡片。
 * 与桌面端 com.pmcl.ui.animation.DownloadFlyLayer 完全一致。
 */
@Composable
fun DownloadFlyLayer(
    animations: List<DownloadFlyState>,
    onComplete: (DownloadFlyState) -> Unit
) {
    if (animations.isEmpty()) return

    val density = LocalDensity.current
    var layerOrigin by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                layerOrigin = IntOffset(pos.x.toInt(), pos.y.toInt())
            }
    ) {
        animations.forEach { anim ->
            key(anim.id) {
                val progress = remember { Animatable(0f) }

                LaunchedEffect(anim.id) {
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 650, easing = { it * it })
                    )
                    onComplete(anim)
                }

                val t = progress.value
                val srcCenter = anim.source.center
                val tgtCenter = anim.target.center
                val arcHeight = abs(tgtCenter.y - srcCenter.y) * 0.2f + 48f
                val ctrlX = (srcCenter.x + tgtCenter.x) / 2f
                val ctrlY = min(srcCenter.y, tgtCenter.y) - arcHeight

                val oneMinusT = 1f - t
                val curX = oneMinusT * oneMinusT * srcCenter.x +
                        2 * oneMinusT * t * ctrlX + t * t * tgtCenter.x
                val curY = oneMinusT * oneMinusT * srcCenter.y +
                        2 * oneMinusT * t * ctrlY + t * t * tgtCenter.y

                val scale = 0.9f - 0.65f * t
                val alpha = if (t < 0.7f) 1f else 1f - (t - 0.7f) / 0.3f

                val maxFlyW = with(density) { 160.dp.toPx() }
                val maxFlyH = with(density) { 40.dp.toPx() }
                val flyW = min(anim.source.width * 0.45f, maxFlyW)
                val flyH = min(anim.source.height * 0.7f, maxFlyH)
                val cardW = with(density) { flyW.toDp() }
                val cardH = with(density) { flyH.toDp() }

                val offsetX = (curX - flyW / 2f - layerOrigin.x).toInt()
                val offsetY = (curY - flyH / 2f - layerOrigin.y).toInt()

                Box(
                    modifier = Modifier
                        .offset { IntOffset(offsetX, offsetY) }
                        .size(width = cardW, height = cardH)
                        .graphicsLayer {
                            this.scaleX = scale
                            this.scaleY = scale
                            this.alpha = alpha.coerceIn(0f, 1f)
                        }
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 4.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = anim.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
