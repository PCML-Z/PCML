package com.pmcl.ui.animation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material3 NavigationRail 默认宽度 */
val PrimaryNavRailWidth = 80.dp

/**
 * 一级 / 二级侧栏宿主：宽度弹性展开 + 内容水平钻入过渡。
 *
 * 性能要点（保留视觉效果）：
 * - 宽度只由外层 [animateDpAsState] 驱动，内容固定按二级宽度布局后裁剪，避免 SizeTransform 双重 layout
 * - 玻璃模糊层固定为二级最大宽度，不随动画每帧重算 blur
 * - 滑入 / 淡入淡出过渡保持不变
 *
 * @param inSecondary true 时展开为二级宽栏
 * @param secondaryWidth 二级栏目标宽度
 * @param glassBlur 是否绘制固定尺寸玻璃模糊底
 * @param primary 一级 NavigationRail 内容
 * @param secondary 二级侧栏内容
 */
@Composable
fun AnimatedNavSidebar(
    inSecondary: Boolean,
    secondaryWidth: Dp,
    modifier: Modifier = Modifier,
    primaryWidth: Dp = PrimaryNavRailWidth,
    glassBlur: Boolean = false,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    val targetWidth = if (inSecondary) secondaryWidth else primaryWidth
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(
            durationMillis = MotionTokens.DURATION_LONG,
            easing = MotionTokens.EasingEmphasized
        ),
        label = "sidebarWidth"
    )

    Box(
        modifier
            .width(animatedWidth)
            .fillMaxHeight()
            .clipToBounds()
    ) {
        // 模糊层始终按二级最大宽度绘制，由外层裁剪；避免宽度动画时每帧重跑 blur
        if (glassBlur) {
            Box(
                Modifier
                    .width(secondaryWidth)
                    .fillMaxHeight()
                    .blur(12.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            )
        }
        AnimatedContent(
            targetState = inSecondary,
            // 内容按最大宽度布局，滑动/淡入在固定画布上发生，宽度变化只裁剪可视区域
            modifier = Modifier
                .width(secondaryWidth)
                .fillMaxHeight(),
            transitionSpec = {
                val duration = MotionTokens.DURATION_LONG
                val enterEase = MotionTokens.EasingEmphasizedDecelerate
                val exitEase = MotionTokens.EasingEmphasizedAccelerate
                val transition = if (targetState) {
                    // 钻入二级：新内容从右滑入，旧一级向左淡出
                    (slideInHorizontally(
                        animationSpec = tween(duration, easing = enterEase),
                        initialOffsetX = { full -> full / 3 }
                    ) + fadeIn(tween(duration, easing = enterEase))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(duration * 2 / 3, easing = exitEase),
                            targetOffsetX = { full -> -full / 4 }
                        ) + fadeOut(tween(duration / 2, easing = exitEase)))
                } else {
                    // 返回一级：新一级从左滑入，旧二级向右淡出
                    (slideInHorizontally(
                        animationSpec = tween(duration, easing = enterEase),
                        initialOffsetX = { full -> -full / 3 }
                    ) + fadeIn(tween(duration, easing = enterEase))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(duration * 2 / 3, easing = exitEase),
                            targetOffsetX = { full -> full / 4 }
                        ) + fadeOut(tween(duration / 2, easing = exitEase)))
                }
                // 尺寸已由外层宽度动画负责，这里禁止再插值 size
                transition.using(SizeTransform(clip = false) { _, _ -> snap() })
            },
            label = "sidebarMode"
        ) { secondaryMode ->
            if (secondaryMode) secondary() else primary()
        }
    }
}

/**
 * 二级分区内容切换：按索引方向轻微水平滑动 + 交叉淡入淡出。
 * @param direction 1=向下一项，-1=向上一项，0=交叉淡入
 */
@Composable
fun <T> AnimatedSecondarySection(
    targetState: T,
    direction: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            val duration = MotionTokens.DURATION_MEDIUM
            val transition = when {
                direction > 0 -> {
                    (slideInHorizontally(
                        animationSpec = tween(duration, easing = MotionTokens.EasingEmphasizedDecelerate),
                        initialOffsetX = { it / 5 }
                    ) + fadeIn(tween(duration))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(duration * 2 / 3, easing = MotionTokens.EasingEmphasizedAccelerate),
                            targetOffsetX = { -it / 6 }
                        ) + fadeOut(tween(duration / 2)))
                }
                direction < 0 -> {
                    (slideInHorizontally(
                        animationSpec = tween(duration, easing = MotionTokens.EasingEmphasizedDecelerate),
                        initialOffsetX = { -it / 5 }
                    ) + fadeIn(tween(duration))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(duration * 2 / 3, easing = MotionTokens.EasingEmphasizedAccelerate),
                            targetOffsetX = { it / 6 }
                        ) + fadeOut(tween(duration / 2)))
                }
                else -> {
                    fadeIn(tween(duration, easing = MotionTokens.EasingEmphasized)) togetherWith
                        fadeOut(tween(duration / 2, easing = MotionTokens.EasingEmphasizedAccelerate))
                }
            }
            transition.using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "secondarySection"
    ) { state ->
        content(state)
    }
}
