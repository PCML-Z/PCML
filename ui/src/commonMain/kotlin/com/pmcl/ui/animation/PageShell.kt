package com.pmcl.ui.animation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp

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
            val duration = MotionTokens.DURATION_MEDIUM
            val transition = when {
                direction > 0 -> {
                    // 前进 / 二级向下：新页从右轻滑入
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(duration, easing = MotionTokens.EasingEmphasizedDecelerate),
                        initialOffset = { it / 5 }
                    ) + fadeIn(tween(duration, easing = MotionTokens.EasingEmphasizedDecelerate)) togetherWith
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                animationSpec = tween(duration * 2 / 3, easing = MotionTokens.EasingEmphasizedAccelerate),
                                targetOffset = { it / 6 }
                            ) + fadeOut(tween(duration / 2))
                }
                direction < 0 -> {
                    // 后退 / 二级向上：新页从左轻滑入
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(duration, easing = MotionTokens.EasingEmphasizedDecelerate),
                        initialOffset = { it / 5 }
                    ) + fadeIn(tween(duration, easing = MotionTokens.EasingEmphasizedDecelerate)) togetherWith
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = tween(duration * 2 / 3, easing = MotionTokens.EasingEmphasizedAccelerate),
                                targetOffset = { it / 6 }
                            ) + fadeOut(tween(duration / 2))
                }
                else -> {
                    // 同级：交叉淡入淡出
                    fadeIn(
                        tween(duration, easing = MotionTokens.EasingEmphasizedDecelerate)
                    ) togetherWith fadeOut(
                        tween(duration * 2 / 3, easing = MotionTokens.EasingEmphasizedAccelerate)
                    )
                }
            }
            // 退出二级侧栏时与侧栏宽度动画并发，禁止再插值页面尺寸以免双重 layout
            transition.using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "pageSwitch"
    ) { state ->
        // 正常显示时把内容记录到 GPU GraphicsLayer。退出后停止重绘旧组合树，
        // 只复用最后一帧快照执行横向滑动、固定虚化与淡出，避免父布局变宽时反复 measure/draw。
        val exiting = state != targetState
        val snapshotLayer = rememberGraphicsLayer()
        Box(
            Modifier
                .fillMaxSize()
                .blur(if (exiting) 6.dp else 0.dp)
                .drawWithContent {
                    if (!exiting) {
                        snapshotLayer.record {
                            this@drawWithContent.drawContent()
                        }
                    }
                    drawLayer(snapshotLayer)
                }
        ) {
            content(state)
        }
    }
}
