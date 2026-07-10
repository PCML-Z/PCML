package com.pmcl.ui.animation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 用于互斥单选场景（如 GC 类型、语言、联机后端等），选中项之间有一个滑动的
 * 高亮背景块，切换时流畅过渡。
 *
 * 用法：
 * ```
 * AnimatedSegmentedSelector(
 *     items = listOf("G1GC", "ZGC", "ParallelGC"),
 *     selectedIndex = gcIndex,
 *     onSelect = { gcIndex = it }
 * )
 * ```
 *
 * 等宽模式（每个按钮平均占满宽度）：
 * ```
 * AnimatedSegmentedSelector(
 *     items = listOf("Terracotta", "EasyTier", "ConnectX"),
 *     selectedIndex = backendIndex,
 *     onSelect = { backendIndex = it },
 *     fillWidth = true
 * )
 * ```
 */
@Composable
fun AnimatedSegmentedSelector(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
    height: androidx.compose.ui.unit.Dp = 36.dp
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    // 记录每个按钮的宽度（px），用于计算滑动指示器位置
    val itemWidths = remember { mutableStateListOf<Float>().apply { repeat(items.size) { add(0f) } } }
    var containerWidth by remember { mutableStateOf(0f) }

    // 计算选中项的 x 偏移和宽度
    val targetOffset: Float
    val targetWidth: Float
    if (fillWidth && containerWidth > 0f) {
        // 等宽模式：按容器宽度平均分配
        val perWidth = containerWidth / items.size
        targetOffset = perWidth * selectedIndex.coerceIn(0, items.lastIndex)
        targetWidth = perWidth
    } else {
        // 自适应模式：按按钮实际测量宽度
        var acc = 0f
        for (i in 0 until selectedIndex.coerceIn(0, items.lastIndex)) {
            acc += itemWidths[i]
        }
        targetOffset = acc
        targetWidth = itemWidths[selectedIndex.coerceIn(0, items.lastIndex)]
    }

    val animatedOffset by animateFloatAsStateSmooth(
        targetValue = targetOffset,
        label = "indicator_offset"
    )
    val animatedWidth by animateFloatAsStateSmooth(
        targetValue = if (targetWidth > 0f) targetWidth else 1f,
        label = "indicator_width"
    )

    Box(
        modifier = modifier
            .onGloballyPositioned { containerWidth = it.size.width.toFloat() }
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // 滑动指示器背景
        if (animatedWidth > 0f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(animatedOffset.toInt(), 0) }
                    .width(with(density) { animatedWidth.toDp() })
                    .fillMaxHeight()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )
        }

        // 按钮行
        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { i, label ->
                val isSelected = i == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(if (fillWidth) 1f else 0f)
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
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 平滑的 animateFloatAsState 包装，使用 MotionTokens 的标准缓动曲线。
 */
@Composable
private fun animateFloatAsStateSmooth(
    targetValue: Float,
    label: String
): State<Float> = androidx.compose.animation.core.animateFloatAsState(
    targetValue = targetValue,
    animationSpec = tween(
        durationMillis = MotionTokens.DURATION_SHORT,
        easing = MotionTokens.EasingEmphasized
    ),
    label = label
)
