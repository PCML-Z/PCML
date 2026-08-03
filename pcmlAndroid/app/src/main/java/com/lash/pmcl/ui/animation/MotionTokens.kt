package com.lash.pmcl.ui.animation

import androidx.compose.animation.core.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局动画 Token：统一所有动画的时长 / 缓动曲线，避免各页面自定义导致视觉不一致。
 * 与桌面端 com.pmcl.ui.animation.MotionTokens 完全一致。
 */
object MotionTokens {
    const val DURATION_SHORT = 150
    const val DURATION_MEDIUM = 300
    const val DURATION_LONG = 450
    const val DURATION_EXTRA_LONG = 600

    val EasingEmphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EasingEmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EasingEmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val EasingStandard = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    val EasingLinear = LinearEasing

    fun <T> tweenDefault(durationMs: Int = DURATION_MEDIUM): TweenSpec<T> =
        tween(durationMillis = durationMs, easing = EasingStandard)

    fun <T> tweenEmphasized(durationMs: Int = DURATION_MEDIUM): TweenSpec<T> =
        tween(durationMillis = durationMs, easing = EasingEmphasized)

    fun <T> springDefault(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val SLIDE_OFFSET: Dp = 16.dp
    val SLIDE_OFFSET_LARGE: Dp = 32.dp
}

object StaggerTokens {
    const val ITEM_DELAY_MS = 30
    const val MAX_ITEMS_ANIMATED = 6
}
