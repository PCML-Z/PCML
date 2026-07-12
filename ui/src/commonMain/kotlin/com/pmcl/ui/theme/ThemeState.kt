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

    /** 莫奈取色：是否使用动态颜色（跟随桌面壁纸） */
    var dynamicColor by mutableStateOf(false)
        private set

    /** 动态生成的 ColorScheme（莫奈取色或自定义强调色时非 null） */
    var dynamicColorScheme by mutableStateOf<ColorScheme?>(null)
        private set

    /** 自定义强调色 ARGB，-1 表示未设置 */
    var customAccentColor by mutableStateOf(-1)
        private set

    /** 当前种子色（用于显示），莫奈取色时为壁纸主色，自定义时为用户选择的颜色 */
    var seedColor by mutableStateOf(-1)
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

    /**
     * 从种子色生成完整 ColorScheme 并应用。
     * 莫奈取色和自定义强调色共用此方法，确保所有颜色角色协调一致。
     */
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

/** CompositionLocal 全局访问 */
val LocalThemeState = compositionLocalOf<ThemeState> {
    error("ThemeState not provided")
}
