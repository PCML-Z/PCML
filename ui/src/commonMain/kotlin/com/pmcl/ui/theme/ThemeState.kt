package com.pmcl.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.pmcl.core.theme.WallpaperColorProvider

/**
 * 全局主题状态持有者，供 App 与 SettingsPage 共享。
 * 用 mutableStateOf 触发 recomposition。
 */
class ThemeState(initialDark: Boolean = false) {
    var useDark by mutableStateOf(initialDark)
        private set

    var dynamicColor by mutableStateOf(false)
        private set

    var dynamicColorScheme by mutableStateOf<ColorScheme?>(null)
        private set

    var customAccentColor by mutableStateOf(-1)
        private set

    var seedColor by mutableStateOf(-1)
        private set

    var uiScale by mutableStateOf(1.0f)
        private set

    var parallaxBackground by mutableStateOf(false)
        private set

    var glassTheme by mutableStateOf(false)
        private set

    /** 卡片主题：Origin OS2 锁屏风格方形卡片主页 */
    var cardTheme by mutableStateOf(false)
        private set

    fun toggle() = set(!useDark)

    fun set(value: Boolean) {
        useDark = value
    }

    fun enableDynamicColor(enabled: Boolean) {
        dynamicColor = enabled
    }

    fun updateDynamicColorScheme(scheme: ColorScheme?) {
        dynamicColorScheme = scheme
    }

    fun applyCustomAccentColor(color: Int) {
        customAccentColor = color
        seedColor = color
    }

    fun clearCustomAccentColor() {
        customAccentColor = -1
        seedColor = -1
    }

    fun applyUiScale(scale: Float) {
        uiScale = scale.coerceIn(0.7f, 1.6f)
    }

    fun applyParallaxBackground(enabled: Boolean) {
        parallaxBackground = enabled
    }

    fun applyGlassTheme(enabled: Boolean) {
        glassTheme = enabled
    }

    fun applyCardTheme(enabled: Boolean) {
        cardTheme = enabled
    }

    fun applySeedColor(seedRgb: Int, dark: Boolean) {
        seedColor = seedRgb
        val palette = WallpaperColorProvider.generateFullPalette(seedRgb, dark)
        val toColor = { rgb: Int -> Color(rgb or 0xFF000000.toInt()) }
        val scheme = if (dark) {
            darkColorScheme(
                primary = toColor(palette.primary),
                onPrimary = toColor(palette.onPrimary),
                primaryContainer = toColor(palette.primaryContainer),
                onPrimaryContainer = toColor(palette.onPrimaryContainer),
                secondary = toColor(palette.secondary),
                onSecondary = toColor(palette.onSecondary),
                tertiary = toColor(palette.tertiary),
                background = toColor(palette.background),
                onBackground = toColor(palette.onBackground),
                surface = toColor(palette.surface),
                onSurface = toColor(palette.onSurface),
                surfaceVariant = toColor(palette.surfaceVariant),
                onSurfaceVariant = toColor(palette.onSurfaceVariant),
                outline = toColor(palette.outline),
                error = toColor(palette.error),
                onError = toColor(palette.onError)
            )
        } else {
            lightColorScheme(
                primary = toColor(palette.primary),
                onPrimary = toColor(palette.onPrimary),
                primaryContainer = toColor(palette.primaryContainer),
                onPrimaryContainer = toColor(palette.onPrimaryContainer),
                secondary = toColor(palette.secondary),
                onSecondary = toColor(palette.onSecondary),
                tertiary = toColor(palette.tertiary),
                background = toColor(palette.background),
                onBackground = toColor(palette.onBackground),
                surface = toColor(palette.surface),
                onSurface = toColor(palette.onSurface),
                surfaceVariant = toColor(palette.surfaceVariant),
                onSurfaceVariant = toColor(palette.onSurfaceVariant),
                outline = toColor(palette.outline),
                error = toColor(palette.error),
                onError = toColor(palette.onError)
            )
        }
        dynamicColorScheme = scheme
    }
}


val LocalThemeState = compositionLocalOf<ThemeState> {
    error("ThemeState not provided")
}
