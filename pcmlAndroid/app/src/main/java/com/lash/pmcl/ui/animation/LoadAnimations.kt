package com.lash.pmcl.ui.animation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 加载动画组件集合：与桌面端 com.pmcl.ui.animation.LoadAnimations 完全一致。
 */

// ===== SlideInFromStart =====

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
        enter = slideInHorizontally(
            animationSpec = tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate),
            initialOffsetX = { -40 }
        ) + fadeIn(tween(durationMs, easing = MotionTokens.EasingEmphasizedDecelerate)),
        exit = fadeOut(tween(durationMs / 2))
    ) {
        content()
    }
}

// ===== EntranceAnimation =====

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

// ===== SlideInFromBottom =====

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

// ===== pulseLoading =====

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

// ===== pressScale =====

fun Modifier.pressScale(
    pressed: Boolean,
    scale: Float = 0.97f
): Modifier = composed {
    val animScale by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pressScale"
    )
    this.graphicsLayer {
        scaleX = animScale
        scaleY = animScale
    }
}
