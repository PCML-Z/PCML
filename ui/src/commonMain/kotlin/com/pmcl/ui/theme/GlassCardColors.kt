package com.pmcl.ui.theme

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 玻璃主题辅助：返回 Card 在当前主题下应使用的 colors。
 *
 * - 玻璃主题关闭：返回 MaterialTheme 默认 CardColors
 * - 玻璃主题开启：返回半透明 containerColor，让背景视差/壁纸透出
 *
 * 用法：
 * ```
 * Card(colors = glassCardColors()) { ... }
 * ```
 *
 * 透明度策略：surface 保持 72% 不透明度，既透出背景又不影响内容可读性。
 * 对深浅色主题均适用。
 */
@Composable
fun glassCardColors(): CardColors {
    val glassOn = LocalThemeState.current.glassTheme
    return if (!glassOn) {
        CardDefaults.cardColors()
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}
