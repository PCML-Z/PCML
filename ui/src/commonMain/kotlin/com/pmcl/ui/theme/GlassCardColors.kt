package com.pmcl.ui.theme

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃主题辅助：返回 Card 在当前主题下应使用的 colors。
 *
 * - glassTheme：半透明 (0.72)，让背景视差/壁纸透出
 * - 默认：MaterialTheme 标准 CardColors
 *
 * 用法：
 * ```
 * Card(
 *     colors = glassCardColors(),
 *     elevation = glassCardElevation(),
 *     modifier = Modifier.glassCardBorder()
 * ) { ... }
 * ```
 */
@Composable
fun glassCardColors(): CardColors {
    val theme = LocalThemeState.current
    return when {
        theme.glassTheme -> {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        }
        else -> {
            CardDefaults.cardColors()
        }
    }
}

/**
 * 返回 Card 在当前主题下应使用的 elevation（阴影深度）。
 *
 * - glassTheme：0.dp 无阴影
 * - 默认：1.dp
 *
 * 返回 CardElevation 类型，可直接传入 Card 的 elevation 参数。
 */
@Composable
fun glassCardElevation(): CardElevation {
    val theme = LocalThemeState.current
    val dp = if (theme.glassTheme) 0.dp else 1.dp
    return CardDefaults.cardElevation(defaultElevation = dp)
}

/**
 * 玻璃主题边框 Modifier：玻璃主题下无边框（返回 this 不添加任何 border）。
 *
 * 仅在 glassTheme 开启时生效（即不画边框）。
 * 默认主题同样返回无修改 Modifier。
 *
 * 用法：
 * ```
 * Card(modifier = Modifier.glassCardBorder(), ...) { ... }
 * ```
 */
@Composable
fun Modifier.glassCardBorder(cornerRadius: Dp = 12.dp): Modifier {
    // 玻璃主题不绘制任何边框，仅靠阴影和透明度区分卡片
    return this
}
