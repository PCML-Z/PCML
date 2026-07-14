package com.pmcl.ui.animation

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局动画 Token：统一所有动画的时长 / 缓动曲线，避免各页面自定义导致视觉不一致。
 *
 * 设计参考 Material Motion 规范：
 * - 小控件（按钮、卡片）使用 SHORT 时长
 * - 大区块（页面切换、列表加载）使用 MEDIUM 时长
 * - 持续性动画（如进度环）使用 LONG 时长
 */
object MotionTokens {
    // 时长
    const val DURATION_SHORT = 150          // 点击反馈、状态切换
    const val DURATION_MEDIUM = 300         // 控件加载、卡片入场
    const val DURATION_LONG = 450           // 页面切换
    const val DURATION_EXTRA_LONG = 600     // 复杂交错动画

    // 缓动曲线（Material Easing）
    val EasingEmphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EasingEmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EasingEmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val EasingStandard = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    val EasingLinear = LinearEasing

    // 默认 Spec 工厂
    fun <T> tweenDefault(durationMs: Int = DURATION_MEDIUM): TweenSpec<T> =
        tween(durationMillis = durationMs, easing = EasingStandard)

    fun <T> tweenEmphasized(durationMs: Int = DURATION_MEDIUM): TweenSpec<T> =
        tween(durationMillis = durationMs, easing = EasingEmphasized)

    fun <T> springDefault(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    // 滑动距离
    val SLIDE_OFFSET: Dp = 16.dp
    val SLIDE_OFFSET_LARGE: Dp = 32.dp
}

/**
 * 列表项交错动画的配置：每项延迟递增。
 */
object StaggerTokens {
    const val ITEM_DELAY_MS = 30            // 每项延迟（降低以加快列表入场体感）
    const val MAX_ITEMS_ANIMATED = 6        // 最多前 N 项有交错（之后立即显示，避免长列表等待）
}
