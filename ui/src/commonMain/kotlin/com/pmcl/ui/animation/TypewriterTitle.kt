package com.pmcl.ui.animation

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

/**
 * 二级侧栏切换时的页面标题：打字机逐字显现。
 *
 * 用透明全文占位，避免打字过程中布局跳动；切换文案时重新开打。
 * 默认等页面滑入动画过半后再开打，避免与侧栏滚入抢戏。
 */
@Composable
fun TypewriterTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    fontWeight: FontWeight? = FontWeight.Bold,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    startDelayMs: Long = MotionTokens.DURATION_MEDIUM.toLong() + 80L,
    charDelayMs: Long = 26L,
    minCharDelayMs: Long = 12L,
    maxDurationMs: Long = 520L,
) {
    val chars = remember(text) { text.toList() }
    var visibleCount by remember(text) { mutableIntStateOf(0) }

    LaunchedEffect(text) {
        visibleCount = 0
        if (chars.isEmpty()) return@LaunchedEffect
        delay(startDelayMs)
        val delayPerChar = (maxDurationMs / chars.size.coerceAtLeast(1))
            .coerceIn(minCharDelayMs, charDelayMs)
        for (i in 1..chars.size) {
            visibleCount = i
            delay(delayPerChar)
        }
    }

    val displayed = if (visibleCount <= 0) "" else chars.take(visibleCount).joinToString("")

    Box(modifier = modifier) {
        // 占位：锁定最终尺寸，防止逐字变宽导致顶栏抖动
        Text(
            text = text,
            style = style,
            fontWeight = fontWeight,
            color = Color.Transparent,
            maxLines = maxLines,
            overflow = overflow
        )
        Text(
            text = displayed,
            style = style,
            fontWeight = fontWeight,
            color = color,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}
