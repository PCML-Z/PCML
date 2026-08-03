package com.lash.pmcl.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局 ThemeState 的 CompositionLocal。
 */
val LocalThemeState = compositionLocalOf { ThemeState() }

/**
 * 玻璃主题卡片：在内容下方铺设一层半透明 + 模糊的毛玻璃层。
 * 与桌面端 com.pmcl.ui.theme.GlassCard 完全一致。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    blurRadius: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .blur(blurRadius)
            .background(tint, RoundedCornerShape(cornerRadius))
    ) {
        content()
    }
}

/**
 * 帮助函数：获取玻璃主题下的背景颜色
 */
@Composable
fun glassContainerColor(base: Color): Color {
    return if (LocalThemeState.current.glassTheme) {
        Color.Transparent
    } else {
        base
    }
}

@Composable
fun glassSurfaceVariantColor(): Color {
    return if (LocalThemeState.current.glassTheme) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
}

fun Modifier.glassCardBorder(cornerRadius: Dp = 12.dp): Modifier {
    return this  // Android 简化：通过透明背景实现毛玻璃效果，无需额外边框
}

@Composable
fun glassCardColors(
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
): androidx.compose.material3.CardColors {
    return if (LocalThemeState.current.glassTheme) {
        androidx.compose.material3.CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    } else {
        androidx.compose.material3.CardDefaults.cardColors(
            containerColor = containerColor
        )
    }
}

@Composable
fun glassCardElevation(): androidx.compose.material3.CardElevation {
    return if (LocalThemeState.current.glassTheme) {
        androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    } else {
        androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    }
}
