package com.pmcl.ui.animation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.with
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 页面外壳：统一所有页面的入场动画。
 *
 * 进入时：渐显 + 轻微上滑
 * 切换时：交叉淡入淡出
 */
@Composable
fun PageShell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    SlideInFromBottom(
        visible = visible,
        durationMs = MotionTokens.DURATION_LONG,
        offsetDp = 24
    ) {
        Column(modifier.fillMaxSize()) {
            content()
        }
    }
}

/**
 * 页面切换过渡：根据导航方向滑动 + 淡入淡出。
 * @param direction 1=前进（新页从右滑入），-1=后退（新页从左滑入），0=同级切换（交叉淡入淡出）
 */
@Composable
fun <T> AnimatedPageSwitch(
    targetState: T,
    direction: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (direction >= 0) {
                // 前进：新页从右滑入，旧页向左滑出
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(MotionTokens.DURATION_LONG, easing = MotionTokens.EasingEmphasized)
                ) + fadeIn(tween(MotionTokens.DURATION_LONG, delayMillis = 100)) togetherWith
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(MotionTokens.DURATION_LONG, easing = MotionTokens.EasingEmphasized)
                        ) + fadeOut(tween(MotionTokens.DURATION_LONG / 2))
            } else {
                // 后退：新页从左滑入，旧页向右滑出
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(MotionTokens.DURATION_LONG, easing = MotionTokens.EasingEmphasized)
                ) + fadeIn(tween(MotionTokens.DURATION_LONG, delayMillis = 100)) togetherWith
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(MotionTokens.DURATION_LONG, easing = MotionTokens.EasingEmphasized)
                        ) + fadeOut(tween(MotionTokens.DURATION_LONG / 2))
            }
        },
        label = "pageSwitch"
    ) { state ->
        content(state)
    }
}
