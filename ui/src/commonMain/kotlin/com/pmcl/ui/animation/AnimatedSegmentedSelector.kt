package com.pmcl.ui.animation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 带流体滑动指示器的分段选择器。
 *
 * 用于互斥单选场景，选中项之间有一个滑动的白色高亮背景块，
 * 切换时以 300ms 强调缓动曲线流畅过渡。
 *
 * @param scrollable 当选项过多时启用横向滚动（如模组分类标签栏）。
 *                   启用后按钮行按内容宽度排列，超出可视区域可横向滑动，
 *                   指示器随之滚动保持对齐。背景固定不随滚动移动。
 */
@Composable
fun AnimatedSegmentedSelector(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    scrollable: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
    height: androidx.compose.ui.unit.Dp = 36.dp,
    indicatorPadding: androidx.compose.ui.unit.Dp = 3.dp
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)
    val itemWidths = remember(items.size) { mutableStateListOf<Float>().apply { repeat(items.size) { add(0f) } } }
    var containerWidth by remember { mutableStateOf(0f) }
    val scrollState = rememberScrollState()

    // 指示器内缩量（px），让滑动块在轨道内有呼吸感
    val padPx = with(density) { indicatorPadding.toPx() }

    // 计算选中项的 x 偏移和宽度
    val targetOffset: Float
    val targetWidth: Float
    if (fillWidth && containerWidth > 0f) {
        val perWidth = containerWidth / items.size
        targetOffset = perWidth * safeIndex + padPx
        targetWidth = perWidth - padPx * 2
    } else {
        var acc = 0f
        for (i in 0 until safeIndex) { acc += itemWidths[i] }
        targetOffset = acc + padPx
        targetWidth = (itemWidths[safeIndex] - padPx * 2).coerceAtLeast(0f)
    }

    // 300ms 强调缓动，让滑动过程清晰可见
    val animSpec = tween<Float>(
        durationMillis = MotionTokens.DURATION_MEDIUM,
        easing = MotionTokens.EasingEmphasized
    )
    val animatedOffset by animateFloatAsState(targetValue = targetOffset, animationSpec = animSpec, label = "offset")
    val animatedWidth by animateFloatAsState(
        targetValue = if (targetWidth > 0f) targetWidth else 0f,
        animationSpec = animSpec,
        label = "width"
    )

    // 指示器圆角（比容器略小，视觉嵌套感）
    val indicatorShape = RoundedCornerShape(7.dp)

    Box(
        modifier = modifier
            .onGloballyPositioned { containerWidth = it.size.width.toFloat() }
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier)
    ) {
        // 滑动指示器：使用 shadow + surface 提升层次感
        if (animatedWidth > 0f) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 2.dp,
                shape = indicatorShape,
                modifier = Modifier
                    .offset { IntOffset(animatedOffset.toInt(), padPx.toInt()) }
                    .width(with(density) { animatedWidth.toDp() })
                    .height(with(density) { (height.toPx() - padPx * 2).toDp() })
            ) {}
        }

        // 按钮行：滚动模式下按内容宽度排列，否则填满容器
        val rowModifier = if (scrollable) {
            Modifier.wrapContentWidth(unbounded = false).fillMaxHeight()
        } else {
            Modifier.fillMaxSize()
        }
        Row(modifier = rowModifier) {
            items.forEachIndexed { i, label ->
                val isSelected = i == safeIndex
                // 自适应模式下不能用 weight(0f)（会抛异常），改用 wrapContentWidth
                val itemModifier = if (fillWidth && !scrollable) {
                    Modifier.weight(1f)
                } else {
                    Modifier.wrapContentWidth(unbounded = false)
                }
                Box(
                    modifier = itemModifier
                        .onGloballyPositioned { itemWidths[i] = it.size.width.toFloat() }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(i) }
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
}
