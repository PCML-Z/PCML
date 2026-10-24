package com.pmcl.ui.studio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val INTRO_MS = 980
private const val FADE_MS = 240
private const val SEED_END = 0.28f
private const val LAUNCH_START = 0.26f
private const val LAUNCH_GAP = 0.045f
private const val LAUNCH_LEN = 0.46f

private class IntroShape(val target: Rect, val color: Color, val order: Int)

/**
 * 窗口启动动画：中央一个旋转的方块长出来，随后分裂成各个区块的色块，
 * 依次飞到标题栏、侧栏、面板、编辑器的位置并把圆角磨平，最后整层淡出露出真实界面。
 * 点一下可跳过。
 */
@Composable
internal fun StudioIntro(
    ribbonOpen: Boolean,
    leftWidth: Dp?,
    rightWidth: Dp?,
    bottomHeight: Dp?,
    onDone: () -> Unit,
) {
    val p = LocalStudio.current
    val master = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var skipped by remember { mutableStateOf(false) }
    LaunchedEffect(skipped) {
        if (skipped) master.snapTo(1f) else master.animateTo(1f, tween(INTRO_MS, easing = LinearEasing))
        fade.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
        onDone()
    }

    Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { skipped = true } }) {
        val t = master.value
        val a = fade.value
        if (a <= 0f) return@Canvas
        drawRect(p.window, alpha = a)

        val w = size.width
        val h = size.height
        val title = 40.dp.toPx()
        val ribbon = if (ribbonOpen) 64.dp.toPx() else 0f
        val status = 24.dp.toPx()
        val act = 44.dp.toPx()
        val strip = 32.dp.toPx()
        val split = 3.dp.toPx()
        val top = title + ribbon
        val bottom = h - status
        val lp = leftWidth?.toPx()
        val rp = rightWidth?.toPx()
        val bh = bottomHeight?.toPx()
        val ex0 = act + (lp?.let { it + split } ?: 0f)
        val ex1 = w - act - (rp?.let { it + split } ?: 0f)
        val editorBottom = bh?.let { bottom - it - split } ?: bottom

        // 绘制顺序：大块垫底，小条压在上面，飞行途中交叠时才不会被编辑器盖住
        val shapes = buildList {
            add(IntroShape(Rect(ex0, top + strip, ex1, editorBottom), p.editor, 5))
            if (lp != null) add(IntroShape(Rect(act, top, act + lp, bottom), p.panel, 6))
            if (rp != null) add(IntroShape(Rect(ex1 + split, top, w - act, bottom), p.panel, 7))
            if (bh != null) add(IntroShape(Rect(ex0, bottom - bh, ex1, bottom), p.panel, 8))
            if (ribbonOpen) add(IntroShape(Rect(0f, title, w, top), p.panel, 9))
            add(IntroShape(Rect(ex0, top, ex1, top + strip), p.strip, 4))
            add(IntroShape(Rect(0f, top, act, bottom), p.activity, 2))
            add(IntroShape(Rect(w - act, top, w, bottom), p.activity, 3))
            add(IntroShape(Rect(0f, 0f, w, title), p.accent, 0))
            add(IntroShape(Rect(0f, bottom, w, h), p.accent, 1))
        }

        val center = Offset(w / 2f, h / 2f)
        val seedSize = 56.dp.toPx()
        val seedRect = Rect(center - Offset(seedSize / 2f, seedSize / 2f), Size(seedSize, seedSize))
        val seedCorner = 8.dp.toPx()

        val seedGrow = FastOutSlowInEasing.transform((t / SEED_END).coerceIn(0f, 1f))
        val seedFade = 1f - ((t - 0.5f) / 0.2f).coerceIn(0f, 1f)
        if (seedGrow > 0f && seedFade > 0f) {
            val s = seedSize * seedGrow
            rotate(45f * (1f - seedGrow), center) {
                drawRoundRect(
                    p.accent,
                    topLeft = center - Offset(s / 2f, s / 2f),
                    size = Size(s, s),
                    cornerRadius = CornerRadius(seedCorner * seedGrow),
                    alpha = a * seedFade,
                )
            }
        }

        shapes.forEach { shape ->
            val start = LAUNCH_START + shape.order * LAUNCH_GAP
            if (t < start) return@forEach
            val local = FastOutSlowInEasing.transform(((t - start) / LAUNCH_LEN).coerceIn(0f, 1f))
            drawMorph(seedRect, shape.target, p.accent, shape.color, local, seedCorner, if (shape.order % 2 == 0) 24f else -24f, a)
        }
    }
}

private fun DrawScope.drawMorph(
    from: Rect,
    to: Rect,
    fromColor: Color,
    toColor: Color,
    progress: Float,
    corner: Float,
    spin: Float,
    alpha: Float,
) {
    val rect = lerp(from, to, progress)
    val color = lerp(fromColor, toColor, progress)
    rotate(spin * (1f - progress), rect.center) {
        drawRoundRect(
            color,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(corner * (1f - progress)),
            alpha = alpha,
        )
    }
}
