package com.pmcl.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pmcl.ui.theme.LocalThemeState
import kotlinx.coroutines.delay

private enum class ScrollbarMode { Off, OnScroll, Always }

@Composable
private fun scrollbarMode(): ScrollbarMode {
    val theme = LocalThemeState.current
    return when {
        theme.alwaysShowScrollbars -> ScrollbarMode.Always
        theme.showScrollbarsOnScroll -> ScrollbarMode.OnScroll
        else -> ScrollbarMode.Off
    }
}

@Composable
private fun scrollbarFade(scrolling: Boolean, mode: ScrollbarMode): Float {
    if (mode == ScrollbarMode.Off) return 0f
    if (mode == ScrollbarMode.Always) return 1f
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) {
        if (scrolling) shown = true
        else {
            delay(600)
            shown = false
        }
    }
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(180), label = "scrollbar")
    return fade
}

@Composable
fun Modifier.pmclVerticalScroll(state: ScrollState = rememberScrollState()): Modifier {
    val mode = scrollbarMode()
    val fade = scrollbarFade(state.isScrollInProgress, mode)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f * fade)
    return verticalScroll(state).then(
        if (mode == ScrollbarMode.Off) Modifier else Modifier.scrollThumb(state, vertical = true, color)
    )
}

@Composable
fun Modifier.pmclHorizontalScroll(state: ScrollState = rememberScrollState()): Modifier {
    val mode = scrollbarMode()
    val fade = scrollbarFade(state.isScrollInProgress, mode)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f * fade)
    return horizontalScroll(state).then(
        if (mode == ScrollbarMode.Off) Modifier else Modifier.scrollThumb(state, vertical = false, color)
    )
}

@Composable
fun PmclLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    val mode = scrollbarMode()
    val fade = scrollbarFade(state.isScrollInProgress, mode)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f * fade)
    LazyColumn(
        modifier = modifier.then(if (mode == ScrollbarMode.Off) Modifier else Modifier.lazyThumb(state, color)),
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        userScrollEnabled = userScrollEnabled,
        content = content
    )
}

private fun Modifier.scrollThumb(state: ScrollState, vertical: Boolean, color: Color): Modifier =
    drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0) return@drawWithContent
        val viewport = if (vertical) size.height else size.width
        val thumb = (viewport * viewport / (viewport + max)).coerceIn(24.dp.toPx(), viewport)
        val pos = state.value / max.toFloat() * (viewport - thumb).coerceAtLeast(0f)
        val thickness = 6.dp.toPx()
        val inset = 3.dp.toPx()
        if (vertical) {
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width - thickness - inset, pos),
                size = Size(thickness, thumb),
                cornerRadius = CornerRadius(thickness / 2)
            )
        } else {
            drawRoundRect(
                color = color,
                topLeft = Offset(pos, size.height - thickness - inset),
                size = Size(thumb, thickness),
                cornerRadius = CornerRadius(thickness / 2)
            )
        }
    }

private fun Modifier.lazyThumb(state: LazyListState, color: Color): Modifier =
    drawWithContent {
        drawContent()
        if (!state.canScrollForward && !state.canScrollBackward) return@drawWithContent
        val info = state.layoutInfo
        val total = info.totalItemsCount
        if (total <= 0 || size.height <= 0f) return@drawWithContent
        val visible = info.visibleItemsInfo.size.coerceAtLeast(1)
        val thumb = (size.height * visible / total.toFloat()).coerceIn(24.dp.toPx(), size.height * 0.9f)
        val denom = (total - visible).coerceAtLeast(1).toFloat()
        val pos = (state.firstVisibleItemIndex / denom).coerceIn(0f, 1f) * (size.height - thumb)
        val thickness = 6.dp.toPx()
        val inset = 3.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - thickness - inset, pos),
            size = Size(thickness, thumb),
            cornerRadius = CornerRadius(thickness / 2)
        )
    }
