package com.pmcl.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    /** 动态生成的 ColorScheme（莫奈取色时非 null） */
    var dynamicColorScheme by mutableStateOf<ColorScheme?>(null)
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
}

/** CompositionLocal 全局访问 */
val LocalThemeState = compositionLocalOf<ThemeState> {
    error("ThemeState not provided")
}
