package com.pmcl.ui.animation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 加载动画组件集合：
 * - [FadeIn]              渐显
 * - [SlideInFromBottom]   从下方滑入 + 渐显
 * - [StaggeredAppear]     列表项交错入场（按索引延迟）
 * - [AnimatedCard]        卡片入场（缩放 + 渐显）
 * - [PulseLoading]        加载占位符脉冲
 */

/** 简单渐显：进入时透明度 0 → 1 */
@Composable
fun FadeIn(
    visible: Boolean = true,
    durationMs: Int = MotionTokens.DURATION_MEDIUM,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate)),
        exit = fadeOut(animationSpec = tween(durationMs / 2, easing = MotionTokens.EasingEmphasizedAccelerate))
    ) {
        content()
    }
}

/** 从下方滑入 + 渐显（适合卡片、列表项） */
@Composable
fun SlideInFromBottom(
    visible: Boolean = true,
    durationMs: Int = MotionTokens.DURATION_MEDIUM,
    offsetDp: Int = 16,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate),
            initialOffsetY = { offsetDp }
        ) + fadeIn(tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate)),
        exit = slideOutVertically(
            animationSpec = tween(durationMs / 2, easing = MotionTokens.EasingEmphasizedAccelerate),
            targetOffsetY = { offsetDp }
        ) + fadeOut(tween(durationMs / 2))
    ) {
        content()
    }
}

/**
 * 列表项交错入场：根据 index 计算延迟，前 N 项有动画，之后立即显示。
 * 用法：
 * ```
 * itemsIndexed(items) { index, item ->
 *     StaggeredAppear(index) {
 *         Card { ... }
 *     }
 * }
 * ```
 */
@Composable
fun StaggeredAppear(
    index: Int,
    durationMs: Int = MotionTokens.DURATION_MEDIUM,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val delay = (index * StaggerTokens.ITEM_DELAY_MS).coerceAtMost(
            StaggerTokens.MAX_ITEMS_ANIMATED * StaggerTokens.ITEM_DELAY_MS
        )
        kotlinx.coroutines.delay(delay.toLong())
        visible = true
    }
    SlideInFromBottom(
        visible = visible,
        durationMs = durationMs,
        offsetDp = 24
    ) { content() }
}

/**
 * 卡片入场：缩放 + 渐显 + 轻微上滑
 */
@Composable
fun AnimatedCard(
    visible: Boolean = true,
    durationMs: Int = MotionTokens.DURATION_MEDIUM,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            animationSpec = tween(durationMs, easing = MotionTokens.EasingEmphasized),
            initialScale = 0.92f
        ) + fadeIn(tween(durationMs, easing = MotionTokens.EasingEmphasized)) +
                slideInVertically(
                    animationSpec = tween(durationMs, easing = MotionTokens.EasingEmphasized),
                    initialOffsetY = { 20 }
                ),
        exit = fadeOut(tween(durationMs / 2))
    ) {
        content()
    }
}

/**
 * 加载占位符脉冲效果：alpha 在 0.3 - 0.7 之间循环。
 * 用作骨架屏背景。
 */
@Composable
fun Modifier.pulseLoading(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = MotionTokens.EasingStandard),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    this.alpha(alpha)
}

/**
 * 点击缩放反馈：按下时缩放至 0.97
 */
fun Modifier.pressScale(
    pressed: Boolean,
    scale: Float = 0.97f
): Modifier = this.graphicsLayer {
    val target = if (pressed) scale else 1f
    scaleX = target
    scaleY = target
}

/**
 * 通用入场动画：窗口/页面打开时控件交错出现。
 * 根据 [delayMs] 延迟后，从下方滑入 + 渐显 + 缩放。
 *
 * @param delayMs 入场延迟（用于交错效果，按控件顺序递增）
 * @param durationMs 动画时长
 * @param offsetDp 滑入距离
 */
@Composable
fun EntranceAnimation(
    delayMs: Int = 0,
    durationMs: Int = MotionTokens.DURATION_MEDIUM,
    offsetDp: Int = 24,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate),
            initialOffsetY = { offsetDp }
        ) + fadeIn(tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate)) +
            scaleIn(
                animationSpec = tween(durationMs, easing = MotionTokens.EasingEmphasized),
                initialScale = 0.96f
            ),
        exit = fadeOut(tween(durationMs / 2))
    ) {
        content()
    }
}

/**
 * 窗口入场动画：用于 NavigationRail 等侧边控件，
 * 从左侧滑入 + 渐显。
 */
@Composable
fun SlideInFromStart(
    delayMs: Int = 0,
    durationMs: Int = MotionTokens.DURATION_MEDIUM,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.slideInHorizontally(
            animationSpec = tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate),
            initialOffsetX = { -40 }
        ) + fadeIn(tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate)),
        exit = fadeOut(tween(durationMs / 2))
    ) {
        content()
    }
}
