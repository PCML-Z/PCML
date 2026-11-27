package com.pmcl.ui.animation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp

/**
 * 横竖屏跟着窗口当场切换，内容按当前尺寸重排。
 * 收到竖屏时只在实况画面上短暂虚化，切回或被打断时立刻清晰。
 * 不用离开前的快照，否则拖动过程中看到的是定格画面。
 */
@Composable
fun PortraitModeSwitch(
    toPortrait: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Boolean) -> Unit,
) {
    val blur = remember { Animatable(0f) }
    LaunchedEffect(toPortrait) {
        if (!toPortrait) {
            blur.snapTo(0f)
            return@LaunchedEffect
        }
        blur.snapTo(10f)
        blur.animateTo(0f, tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasizedDecelerate))
    }
    val radius = if (toPortrait) blur.value else 0f
    Box(
        modifier
            .fillMaxSize()
            .then(if (radius > 0.5f) Modifier.blur(radius.dp) else Modifier)
    ) {
        content(toPortrait)
    }
}

/** 竖屏壳层内为 true。页面据此收紧横屏行，横屏布局不读这个值。 */
val LocalPortrait = staticCompositionLocalOf { false }

/** 竖屏壳已经画出页面标题时为 true，页内不再重复一条大标题。 */
val LocalPortraitOwnsTitle = staticCompositionLocalOf { false }

/**
 * 竖屏页面推进。列宽固定，只做短距横滑和淡入淡出。
 * 不使用横屏侧栏的快照虚化。
 *
 * motion：1 从右进入，-1 从左进入，0 只交叉淡入淡出。
 * 尺寸变换不使用直角裁切，否则滑动的那一层会把圆角窗口切成正方形。
 */
@Composable
fun <T> PortraitStage(
    targetState: T,
    motion: Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            val duration = MotionTokens.DURATION_MEDIUM
            val enterOffset = tween<androidx.compose.ui.unit.IntOffset>(
                durationMillis = duration,
                easing = MotionTokens.EasingEmphasizedDecelerate
            )
            val exitOffset = tween<androidx.compose.ui.unit.IntOffset>(
                durationMillis = duration * 2 / 3,
                easing = MotionTokens.EasingEmphasizedAccelerate
            )
            val fadeInSpec = tween<Float>(duration, easing = MotionTokens.EasingEmphasizedDecelerate)
            val fadeOutSpec = tween<Float>(duration / 2, easing = MotionTokens.EasingEmphasizedAccelerate)
            val shift = when {
                motion > 0 -> (slideInHorizontally(enterOffset) { it / 5 } + fadeIn(fadeInSpec)) togetherWith
                    (slideOutHorizontally(exitOffset) { -it / 8 } + fadeOut(fadeOutSpec))
                motion < 0 -> (slideInHorizontally(enterOffset) { -it / 5 } + fadeIn(fadeInSpec)) togetherWith
                    (slideOutHorizontally(exitOffset) { it / 8 } + fadeOut(fadeOutSpec))
                else -> fadeIn(fadeInSpec) togetherWith fadeOut(fadeOutSpec)
            }
            shift.using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "portraitStage"
    ) { state ->
        Box(Modifier.fillMaxSize()) { content(state) }
    }
}
