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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.pmcl.ui.theme.splashPingFangFamily
import kotlinx.coroutines.delay

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
 * 使用 [MutableTransitionState] 确保动画仅在首次进入组合时触发一次，
 * 避免列表项滚出可视区域再滚回时重播入场动画。
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
    val transitionState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    // 仅在首次进入组合时执行延迟，滚回时直接显示（targetState 已为 true）
    if (!transitionState.currentState) {
        LaunchedEffect(Unit) {
            val delay = (index * StaggerTokens.ITEM_DELAY_MS).coerceAtMost(
                StaggerTokens.MAX_ITEMS_ANIMATED * StaggerTokens.ITEM_DELAY_MS
            )
            kotlinx.coroutines.delay(delay.toLong())
        }
    }
    AnimatedVisibility(
        visibleState = transitionState,
        enter = slideInVertically(
            animationSpec = tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate),
            initialOffsetY = { 24 }
        ) + fadeIn(tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate)),
        exit = fadeOut(tween(durationMs / 2))
    ) {
        content()
    }
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
 * 点击缩放反馈：按下时缩放至 [scale]，松开时弹回 1.0。
 * 使用 spring 物理动画实现丝滑过渡和轻微回弹效果。
 */
fun Modifier.pressScale(
    pressed: Boolean,
    scale: Float = 0.97f
): Modifier = hoverPressScale(hovered = false, pressed = pressed, pressScale = scale)

/**
 * 悬停放大、按下缩小。桌面端按钮的主要指针反馈。
 */
fun Modifier.hoverPressScale(
    hovered: Boolean,
    pressed: Boolean,
    hoverScale: Float = 1.045f,
    pressScale: Float = 0.96f
): Modifier = composed {
    val animScale by animateFloatAsState(
        targetValue = when {
            pressed -> pressScale
            hovered -> hoverScale
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "hoverPressScale"
    )
    this.graphicsLayer {
        scaleX = animScale
        scaleY = animScale
    }
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

/** 关于页里的启动图。文字用苹方，随画面高度缩放。 */
@Composable
fun SplashArtwork(
    version: String,
    modifier: Modifier = Modifier
) {
    val pingFang = splashPingFangFamily()
    val label = version.trim().let { if (it.startsWith("v") || it.startsWith("V")) it else "v$it" }
    Box(modifier) {
        SplashPoster(Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth
            val height = maxHeight
            Column(Modifier.padding(start = width * 0.04f, top = height * 0.09f)) {
                Text(
                    "PMCL",
                    color = Color.White,
                    fontSize = (height.value * 0.16f).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = pingFang,
                    lineHeight = (height.value * 0.17f).sp
                )
                Spacer(Modifier.height(height * 0.02f))
                Text(
                    label,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = (height.value * 0.055f).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = pingFang
                )
            }
            Text(
                text = "By HCS X Pro 2 core",
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = width * 0.03f, bottom = height * 0.04f),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = (height.value * 0.032f).sp,
                fontWeight = FontWeight.Medium,
                fontFamily = pingFang
            )
        }
    }
}

/** 整窗启动图：左侧方块，右侧三角形、方形和菱形，全部直角。 */
@Composable
private fun SplashPoster(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val rows = 5
        val cell = h / rows
        val colors = arrayOf(
            Color(0xFFE11368),
            Color(0xFF1026C8),
            Color(0xFF6A1B9A),
            Color(0xFFFF2D78),
            Color(0xFF1A237E),
            Color(0xFFC2185B),
            Color(0xFF283593),
            Color(0xFF4A148C)
        )
        val pattern = arrayOf(
            intArrayOf(0, 4, 2, 0),
            intArrayOf(4, 5, 6, 2),
            intArrayOf(2, 1, 0, 4),
            intArrayOf(5, 6, 7, 1),
            intArrayOf(1, 0, 4, 5)
        )
        val mosaicW = cell * pattern[0].size
        drawRect(Color(0xFF123096))
        for (row in pattern.indices) {
            for (col in pattern[row].indices) {
                drawRect(
                    colors[pattern[row][col]],
                    topLeft = Offset(col * cell, row * cell),
                    size = Size(cell + 1f, cell + 1f)
                )
            }
        }
        drawPath(
            Path().apply {
                moveTo(w, 0f)
                lineTo(w, h * 0.78f)
                lineTo(mosaicW + (w - mosaicW) * 0.22f, 0f)
                close()
            },
            Color(0xFF3D7EFF)
        )
        drawPath(
            Path().apply {
                moveTo(mosaicW, h)
                lineTo(mosaicW + h * 0.62f, h)
                lineTo(mosaicW, h * 0.42f)
                close()
            },
            Color(0xFF0D1B6E)
        )
        val square = h * 0.26f
        drawRect(
            Color(0xFFFF4D8D),
            topLeft = Offset(mosaicW + (w - mosaicW) * 0.38f, h * 0.50f),
            size = Size(square, square)
        )
        val cx = mosaicW + (w - mosaicW) * 0.58f
        val cy = h * 0.22f
        val arm = h * 0.12f
        drawPath(
            Path().apply {
                moveTo(cx, cy - arm)
                lineTo(cx + arm, cy)
                lineTo(cx, cy + arm)
                lineTo(cx - arm, cy)
                close()
            },
            Color(0xFFFFC107)
        )
        drawRect(
            Color(0xFFFFD112),
            topLeft = Offset(mosaicW + cell * 0.45f, h * 0.84f),
            size = Size(h * 0.72f, h * 0.07f)
        )
    }
}
