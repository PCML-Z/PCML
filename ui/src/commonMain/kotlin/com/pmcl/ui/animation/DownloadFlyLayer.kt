package com.pmcl.ui.animation

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
 *
 * 抛物线轨迹用二次贝塞尔曲线计算：
 *   P(t) = (1-t)²·P0 + 2(1-t)t·P1 + t²·P2
 *   其中 P0=源中心, P1=控制点(源与目标中点上方抬升), P2=目标中心
 *
 * 源/目标使用窗口坐标；浮层可能不在窗口原点（例如在侧边栏右侧），
 * 因此定位前会减去本层的 positionInWindow，转换到本地坐标。
 *
 * 同时缩放 0.9→0.25、透明度 1→0、到达目标后触发 onDone。
 *
 * @param animations 当前飞行中的动画列表
 * @param onComplete 动画结束回调（移除动画 + 触发下载入队 + 目标反馈）
 */
@Composable
fun DownloadFlyLayer(
    animations: List<DownloadFlyState>,
    onComplete: (DownloadFlyState) -> Unit
) {
    if (animations.isEmpty()) return

    val density = LocalDensity.current
    // 浮层左上角在窗口中的位置，用于把窗口坐标转成本地 offset
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

                // 二次贝塞尔曲线插值（窗口坐标）
                val srcCenter = anim.source.center
                val tgtCenter = anim.target.center
                // 控制点：源和目标中点，Y 轴小幅抬升（避免弧顶飞出窗口）
                val arcHeight = abs(tgtCenter.y - srcCenter.y) * 0.2f + 48f
                val ctrlX = (srcCenter.x + tgtCenter.x) / 2f
                val ctrlY = min(srcCenter.y, tgtCenter.y) - arcHeight

                val oneMinusT = 1f - t
                val curX = oneMinusT * oneMinusT * srcCenter.x +
                        2 * oneMinusT * t * ctrlX + t * t * tgtCenter.x
                val curY = oneMinusT * oneMinusT * srcCenter.y +
                        2 * oneMinusT * t * ctrlY + t * t * tgtCenter.y

                // 缩放：0.9 → 0.25（起点略缩小，减少占位）
                val scale = 0.9f - 0.65f * t
                // 透明度：1.0 → 0.0（最后 30% 加速消失）
                val alpha = if (t < 0.7f) 1f else 1f - (t - 0.7f) / 0.3f

                // 飞行卡片：固定较小尺寸，避免整行宽度的大卡片撑出窗口
                val maxFlyW = with(density) { 160.dp.toPx() }
                val maxFlyH = with(density) { 40.dp.toPx() }
                val flyW = min(anim.source.width * 0.45f, maxFlyW)
                val flyH = min(anim.source.height * 0.7f, maxFlyH)
                val cardW = with(density) { flyW.toDp() }
                val cardH = with(density) { flyH.toDp() }

                // 窗口坐标 → 浮层本地坐标（以飞行卡片中心对齐轨迹点）
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
