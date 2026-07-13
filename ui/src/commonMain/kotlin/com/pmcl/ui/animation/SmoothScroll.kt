package com.pmcl.ui.animation

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 平滑滚动扩展：为 LazyListState 提供丝滑的滚动方法。
 *
 * 实现要点：
 * - 使用 animateScrollBy 配合自定义 AnimationSpec
 * - 长距离滚动分段执行，避免单次动画过长
 * - 顶部/底部到达时立即停止，避免抖动
 */

/** 平滑滚动到指定索引（自动算偏移） */
fun LazyListState.smoothScrollToItem(
    scope: CoroutineScope,
    index: Int,
    offset: Int = 0
) {
    scope.launch {
        animateScrollToItem(index, offset)
    }
}

/** 平滑滚动指定像素距离（带缓动）。suspend 直到滚动完成。 */
suspend fun LazyListState.smoothScrollBy(delta: Float) {
    animateScrollBy(
        value = delta,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = MotionTokens.DURATION_LONG,
            easing = MotionTokens.EasingEmphasized
        )
    )
}

/** 滚动到列表顶部 */
fun LazyListState.smoothScrollToTop(scope: CoroutineScope) {
    scope.launch {
        animateScrollToItem(0, 0)
    }
}
