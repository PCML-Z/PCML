package com.pmcl.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/** 材质主页底栏高度。侧栏底部要留出同样一块，和内容区的白条接上。 */
val MaterialHomeBarHeight = 48.dp

fun materialHomeBarColor(dark: Boolean): Color =
    if (dark) Color(0xFF1A1C20).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.58f)

/**
 * 铺在模糊背景上的霜面。本身不涂实色，壁纸的模糊会从霜里透出来。
 * 两个角按设置画几何图形。
 */
@Composable
fun MaterialField(modifier: Modifier = Modifier, linesOnly: Boolean = false) {
    val theme = LocalThemeState.current
    val dark = theme.useDark
    val geometry = theme.fieldGeometry
    val lineStyle = theme.fieldLineStyle
    val frost = if (dark) Color(0xFF101218).copy(alpha = 0.26f) else Color.White.copy(alpha = 0.16f)
    val line = if (dark) Color.White.copy(alpha = 0.28f) else Color(0xFF3C4148).copy(alpha = 0.42f)
    Canvas(modifier.fillMaxSize()) {
        if (!linesOnly) {
            drawRect(frost)
        }
        engrave(line, 1.15.dp.toPx(), geometry, lineStyle)
    }
}

private fun DrawScope.engrave(color: Color, strokeWidth: Float, geometry: String, lineStyle: String) {
    val stroke = lineStroke(strokeWidth, lineStyle)
    val clipW = 460.dp.toPx()
    val clipH = 340.dp.toPx()
    clipRect(0f, 0f, clipW, clipH) {
        drawCorner(color, stroke, lineStyle, Offset.Zero, 1f, geometry, Jitter(seed(geometry, lineStyle, 1)))
    }
    clipRect(size.width - clipW, size.height - clipH, size.width, size.height) {
        drawCorner(
            color, stroke, lineStyle, Offset(size.width, size.height), -1f, geometry,
            Jitter(seed(geometry, lineStyle, 2))
        )
    }
}

private fun seed(geometry: String, lineStyle: String, corner: Int): Int {
    return geometry.hashCode() * 31 + lineStyle.hashCode() * 17 + corner * 97
}

/** 每个角一套固定序列，重绘时图案不变，两个角互不相同。 */
private class Jitter(seed: Int) {
    private var state = seed xor 0x5F3759DF.toInt()
    fun next(): Float {
        state = state * 1664525 + 1013904223
        return ((state ushr 8) and 0xFFFFFF) / 16777215f
    }
    fun around(scale: Float) = (next() * 2f - 1f) * scale
    fun span(min: Float, max: Float) = min + next() * (max - min)
    fun skip(chance: Float) = next() < chance
}

private fun lineStroke(width: Float, lineStyle: String): Stroke {
    val effect = when (lineStyle) {
        "DASHED" -> PathEffect.dashPathEffect(floatArrayOf(14f, 9f))
        "DOTTED" -> PathEffect.dashPathEffect(floatArrayOf(1.8f, 7f))
        else -> null
    }
    val cap = if (lineStyle == "DOTTED") StrokeCap.Round else StrokeCap.Butt
    return Stroke(width = width, pathEffect = effect, cap = cap)
}

private fun DrawScope.drawCorner(
    color: Color,
    stroke: Stroke,
    lineStyle: String,
    origin: Offset,
    sign: Float,
    geometry: String,
    jitter: Jitter
) {
    val extra = if (lineStyle == "DOUBLE") 4.5.dp.toPx() else 0f
    when (geometry) {
        "CIRCLES" -> cornerCircles(color, stroke, lineStyle, origin, sign, extra, jitter)
        "TRIANGLES" -> cornerTriangles(color, stroke, lineStyle, origin, sign, extra, jitter)
        "DIAMONDS" -> cornerDiamonds(color, stroke, lineStyle, origin, sign, extra, jitter)
        "GRID" -> cornerGrid(color, stroke, lineStyle, origin, sign, extra, jitter)
        "ARCS" -> cornerArcs(color, stroke, lineStyle, origin, sign, extra, jitter)
        "CROSS" -> cornerCross(color, stroke, lineStyle, origin, sign, extra, jitter)
        else -> cornerLines(color, stroke, lineStyle, origin, sign, extra, jitter)
    }
}

private fun ink(color: Color, jitter: Jitter): Color =
    color.copy(alpha = color.alpha * jitter.span(0.45f, 1f))

private fun phased(stroke: Stroke, lineStyle: String, phase: Float): Stroke {
    val effect = when (lineStyle) {
        "DASHED" -> PathEffect.dashPathEffect(floatArrayOf(14f, 9f), phase)
        "DOTTED" -> PathEffect.dashPathEffect(floatArrayOf(1.8f, 7f), phase)
        else -> null
    }
    return Stroke(width = stroke.width, pathEffect = effect, cap = stroke.cap)
}

private fun DrawScope.cornerLines(
    color: Color,
    stroke: Stroke,
    lineStyle: String,
    origin: Offset,
    sign: Float,
    extra: Float,
    jitter: Jitter
) {
    val gap = 13.dp.toPx()
    val length = 300.dp.toPx()
    var cursor = -4f
    repeat(14) {
        if (jitter.skip(0.16f)) {
            cursor += jitter.span(0.45f, 1.1f)
            return@repeat
        }
        val step = gap * jitter.span(0.62f, 1.45f)
        val dx = length * jitter.span(0.42f, 0.78f) * sign
        val dy = length * jitter.span(0.72f, 1.08f) * sign
        val span = sqrt(dx * dx + dy * dy)
        val px = -dy / span
        val py = dx / span
        val ox = origin.x + px * cursor * step + jitter.around(10.dp.toPx())
        val oy = origin.y + py * cursor * step + jitter.around(10.dp.toPx())
        markLine(ink(color, jitter), Offset(ox, oy), Offset(ox + dx, oy + dy), phased(stroke, lineStyle, jitter.span(0f, 24f)), extra)
        cursor += jitter.span(0.7f, 1.35f)
    }
}

private fun DrawScope.cornerCircles(
    color: Color,
    stroke: Stroke,
    lineStyle: String,
    origin: Offset,
    sign: Float,
    extra: Float,
    jitter: Jitter
) {
    repeat(7) { index ->
        if (jitter.skip(0.14f)) return@repeat
        val radius = (36.dp.toPx() + index * 32.dp.toPx()) * jitter.span(0.72f, 1.18f)
        val center = Offset(
            origin.x + jitter.span(0f, 26.dp.toPx()) * sign,
            origin.y + jitter.span(0f, 26.dp.toPx()) * sign
        )
        val paint = phased(stroke, lineStyle, jitter.span(0f, 20f))
        drawCircle(ink(color, jitter), radius, center, style = paint)
        if (extra > 0f) drawCircle(ink(color, jitter), radius + extra, center, style = paint)
    }
}

private fun DrawScope.cornerTriangles(
    color: Color,
    stroke: Stroke,
    lineStyle: String,
    origin: Offset,
    sign: Float,
    extra: Float,
    jitter: Jitter
) {
    repeat(6) { index ->
        if (jitter.skip(0.18f)) return@repeat
        val side = (52.dp.toPx() + index * 38.dp.toPx()) * jitter.span(0.7f, 1.2f)
        val paint = phased(stroke, lineStyle, jitter.span(0f, 18f))
        triangle(ink(color, jitter), paint, origin, sign, side, extra, jitter)
    }
}

private fun DrawScope.triangle(
    color: Color,
    stroke: Stroke,
    origin: Offset,
    sign: Float,
    side: Float,
    extra: Float,
    jitter: Jitter
) {
    val ax = side * jitter.span(0.75f, 1.15f) * sign
    val by = side * jitter.span(0.7f, 1.2f) * sign
    val skew = jitter.around(side * 0.18f)
    val ox = origin.x + jitter.around(8.dp.toPx())
    val oy = origin.y + jitter.around(8.dp.toPx())
    fun outline(grow: Float) = Path().apply {
        moveTo(ox, oy)
        lineTo(ox + ax + grow * sign, oy + skew)
        lineTo(ox + skew * 0.4f, oy + by + grow * sign)
        close()
    }
    drawPath(outline(0f), color, style = stroke)
    if (extra > 0f) drawPath(outline(extra * 2f), color, style = stroke)
}

private fun DrawScope.cornerDiamonds(
    color: Color,
    stroke: Stroke,
    lineStyle: String,
    origin: Offset,
    sign: Float,
    extra: Float,
    jitter: Jitter
) {
    repeat(5) { index ->
        if (jitter.skip(0.16f)) return@repeat
        val step = (48.dp.toPx() + index * 46.dp.toPx()) * jitter.span(0.82f, 1.2f)
        val radius = (16.dp.toPx() + index * 5.dp.toPx()) * jitter.span(0.7f, 1.35f)
        val center = Offset(
            origin.x + step * sign + jitter.around(14.dp.toPx()),
            origin.y + step * jitter.span(0.75f, 1.2f) * sign + jitter.around(14.dp.toPx())
        )
        val paint = phased(stroke, lineStyle, jitter.span(0f, 16f))
        val turn = jitter.around(0.6f)
        diamond(ink(color, jitter), paint, center, radius, turn)
        if (extra > 0f) diamond(ink(color, jitter), paint, center, radius + extra, turn)
    }
}

private fun DrawScope.diamond(color: Color, stroke: Stroke, center: Offset, radius: Float, turn: Float) {
    val points = arrayOf(
        Offset(0f, -radius),
        Offset(radius, 0f),
        Offset(0f, radius),
        Offset(-radius, 0f)
    )
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = point.x * kotlin.math.cos(turn) - point.y * kotlin.math.sin(turn)
        val y = point.x * kotlin.math.sin(turn) + point.y * kotlin.math.cos(turn)
        if (index == 0) path.moveTo(center.x + x, center.y + y)
        else path.lineTo(center.x + x, center.y + y)
    }
    path.close()
    drawPath(path, color, style = stroke)
}

private fun DrawScope.cornerGrid(
    color: Color,
    stroke: Stroke,
    lineStyle: String,
    origin: Offset,
    sign: Float,
    extra: Float,
    jitter: Jitter
) {
    val reach = 320.dp.toPx()
    var cursor = 16.dp.toPx()
    while (cursor <= reach) {
        val wobble = jitter.around(8.dp.toPx())
        val reachJ = reach * jitter.span(0.55f, 1f)
        if (!jitter.skip(0.22f)) {
            markLine(
                ink(color, jitter),
                Offset(origin.x, origin.y + (cursor + wobble) * sign),
                Offset(origin.x + reachJ * sign, origin.y + (cursor - wobble) * sign),
                phased(stroke, lineStyle, jitter.span(0f, 20f)),
                extra
            )
        }
        if (!jitter.skip(0.22f)) {
            markLine(
                ink(color, jitter),
                Offset(origin.x + (cursor + wobble) * sign, origin.y),
                Offset(origin.x + (cursor - wobble) * sign, origin.y + reachJ * sign),
                phased(stroke, lineStyle, jitter.span(0f, 20f)),
                extra
            )
        }
        cursor += 22.dp.toPx() * jitter.span(0.55f, 1.55f)
    }
}

private fun DrawScope.cornerArcs(
    color: Color,
    stroke: Stroke,
    lineStyle: String,
    origin: Offset,
    sign: Float,
    extra: Float,
    jitter: Jitter
) {
    val start = if (sign > 0f) 0f else 180f
    repeat(7) { index ->
        if (jitter.skip(0.16f)) return@repeat
        val radius = (40.dp.toPx() + index * 30.dp.toPx()) * jitter.span(0.75f, 1.2f)
        val angle = start + jitter.around(28f)
        val sweep = jitter.span(48f, 128f)
        val paint = phased(stroke, lineStyle, jitter.span(0f, 18f))
        arc(ink(color, jitter), paint, origin, radius, angle, sweep)
        if (extra > 0f) arc(ink(color, jitter), paint, origin, radius + extra, angle, sweep * 0.85f)
    }
}

private fun DrawScope.arc(
    color: Color,
    stroke: Stroke,
    origin: Offset,
    radius: Float,
    start: Float,
    sweep: Float
) {
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(origin.x - radius, origin.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = stroke
    )
}

private fun DrawScope.cornerCross(
    color: Color,
    stroke: Stroke,
    lineStyle: String,
    origin: Offset,
    sign: Float,
    extra: Float,
    jitter: Jitter
) {
    val length = 280.dp.toPx()
    repeat(10) { index ->
        if (jitter.skip(0.2f)) return@repeat
        val shift = 16.dp.toPx() * (index - 2) * jitter.span(0.6f, 1.4f)
        val len = length * jitter.span(0.45f, 1f)
        markLine(
            ink(color, jitter),
            Offset(origin.x + shift * sign, origin.y + jitter.around(6.dp.toPx())),
            Offset(origin.x + len * sign, origin.y + (len - shift) * sign),
            phased(stroke, lineStyle, jitter.span(0f, 22f)),
            extra
        )
        markLine(
            ink(color, jitter),
            Offset(origin.x + jitter.around(6.dp.toPx()), origin.y + shift * sign),
            Offset(origin.x + (len - shift) * sign, origin.y + len * sign),
            phased(stroke, lineStyle, jitter.span(0f, 22f)),
            extra
        )
    }
}

private fun DrawScope.markLine(color: Color, start: Offset, end: Offset, stroke: Stroke, extra: Float) {
    drawLine(color, start, end, stroke.width, cap = stroke.cap, pathEffect = stroke.pathEffect)
    if (extra <= 0f) return
    val vx = end.x - start.x
    val vy = end.y - start.y
    val len = sqrt(vx * vx + vy * vy).coerceAtLeast(1f)
    val ox = -vy / len * extra
    val oy = vx / len * extra
    drawLine(
        color,
        Offset(start.x + ox, start.y + oy),
        Offset(end.x + ox, end.y + oy),
        stroke.width,
        cap = stroke.cap,
        pathEffect = stroke.pathEffect
    )
}
