package com.pmcl.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/** 材质主页底栏高度。侧栏底部要留出同样一块，和内容区的白条接上。 */
val MaterialHomeBarHeight = 48.dp

fun materialHomeBarColor(dark: Boolean): Color =
    if (dark) Color(0xFF1A1C20).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.58f)

/**
 * 铺在模糊背景上的霜面。本身不涂实色，壁纸的模糊会从霜里透出来。
 * 两个角刻一组平行线。
 */
@Composable
fun MaterialField(modifier: Modifier = Modifier, linesOnly: Boolean = false) {
    val theme = LocalThemeState.current
    val dark = theme.useDark
    val center = if (dark) Color(0xFF12141A).copy(alpha = 0.10f) else Color.White.copy(alpha = 0.06f)
    val edge = if (dark) Color(0xFF12141A).copy(alpha = 0.34f) else Color(0xFFF4F5F7).copy(alpha = 0.22f)
    val line = if (dark) Color.White.copy(alpha = 0.28f) else Color(0xFF3C4148).copy(alpha = 0.42f)
    Canvas(modifier.fillMaxSize()) {
        if (!linesOnly) {
            val radius = maxOf(size.width, size.height) * 0.78f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(center, center, edge),
                    center = Offset(size.width * 0.5f, size.height * 0.46f),
                    radius = radius
                )
            )
        }
        engrave(line, 1.15.dp.toPx())
    }
}

private fun DrawScope.engrave(color: Color, stroke: Float) {
    val gap = 13.dp.toPx()
    val length = 300.dp.toPx()
    val dx = length * 0.58f
    val dy = length
    val span = sqrt(dx * dx + dy * dy)
    val px = -dy / span * gap
    val py = dx / span * gap
    val clipW = 460.dp.toPx()
    val clipH = 340.dp.toPx()
    clipRect(0f, 0f, clipW, clipH) {
        repeat(14) { index ->
            val ox = px * (index - 4)
            val oy = py * (index - 4)
            drawLine(color, Offset(ox, oy), Offset(ox + dx, oy + dy), stroke)
        }
    }
    clipRect(size.width - clipW, size.height - clipH, size.width, size.height) {
        repeat(14) { index ->
            val ox = size.width + px * (index - 4)
            val oy = size.height + py * (index - 4)
            drawLine(color, Offset(ox, oy), Offset(ox - dx, oy - dy), stroke)
        }
    }
}
