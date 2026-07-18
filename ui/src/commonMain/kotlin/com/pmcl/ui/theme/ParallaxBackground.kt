package com.pmcl.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 视差背景：多层鼠标视差背景。
 *
 * 用程序化生成的多层渐变球（不依赖图片资源），每层以不同系数跟随鼠标偏移，
 * 营造 3D 深度感。鼠标位置用 spring 动画平滑避免抖动。
 *
 * 层级设计（由远到近）：
 * - 远景：大半径低饱和渐变球，偏移系数 0.02（几乎不动）
 * - 中景：中半径中饱和渐变球，偏移系数 0.05
 * - 近景：小半径高饱和渐变球，偏移系数 0.10
 * - 遮罩：半透明纯色，保证内容可读性
 */
@Composable
fun ParallaxBackground(
    modifier: Modifier = Modifier,
    useDark: Boolean = true
) {
    // 鼠标归一化位置 (-1..1)，中心为 (0,0)
    var mouseTargetX by remember { mutableStateOf(0f) }
    var mouseTargetY by remember { mutableStateOf(0f) }

    // spring 平滑跟随，避免抖动
    val mouseX by animateFloatAsState(
        targetValue = mouseTargetX,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "parallaxX"
    )
    val mouseY by animateFloatAsState(
        targetValue = mouseTargetY,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "parallaxY"
    )

    val density = LocalDensity.current
    val farColor = if (useDark) Color(0xFF1A2332) else Color(0xFFDDE6F0)
    val midColor = if (useDark) Color(0xFF2D3A5F) else Color(0xFFB8C8E0)
    val nearColor = if (useDark) Color(0xFF4A5F8A) else Color(0xFF8AA0CC)
    val scrimColor = if (useDark) Color(0xCC0D1117) else Color(0xCCF5F5F7)

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move) {
                            val change = event.changes.firstOrNull() ?: continue
                            // 归一化到 -1..1
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w > 0 && h > 0) {
                                mouseTargetX = ((change.position.x / w) * 2f - 1f).coerceIn(-1f, 1f)
                                mouseTargetY = ((change.position.y / h) * 2f - 1f).coerceIn(-1f, 1f)
                            }
                        }
                    }
                }
            }
    ) {
        val wPx = with(density) { 600.dp.toPx() }
        val hPx = with(density) { 600.dp.toPx() }

        // 远景层：偏移系数 0.02
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * 0.3f + mouseX * 0.02f * size.width
            val cy = size.height * 0.4f + mouseY * 0.02f * size.height
            drawRadialGradient(
                color = farColor,
                center = Offset(cx, cy),
                radius = wPx
            )
        }

        // 中景层：偏移系数 0.05
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * 0.7f + mouseX * 0.05f * size.width
            val cy = size.height * 0.6f + mouseY * 0.05f * size.height
            drawRadialGradient(
                color = midColor,
                center = Offset(cx, cy),
                radius = wPx * 0.7f
            )
        }

        // 近景层：偏移系数 0.10
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f + mouseX * 0.10f * size.width
            val cy = size.height * 0.3f + mouseY * 0.10f * size.height
            drawRadialGradient(
                color = nearColor,
                center = Offset(cx, cy),
                radius = wPx * 0.5f
            )
        }

        // 遮罩：保证内容可读性
        Canvas(Modifier.fillMaxSize()) {
            drawRect(scrimColor)
        }
    }
}

/** 绘制径向渐变球 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRadialGradient(
    color: Color,
    center: Offset,
    radius: Float
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center,
            radius = radius
        ),
        center = center,
        radius = radius
    )
}
