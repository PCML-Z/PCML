package com.lash.pmcl.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局主题状态：与桌面端 com.pmcl.ui.theme.ThemeState 对齐核心属性。
 * Android 版简化了壁纸取色、背景图片等功能，保留颜色预设、暗黑模式、玻璃主题、UI 缩放。
 */
class ThemeState(initialDark: Boolean = false) {
    var useDark by mutableStateOf(initialDark)

    // 主题预设
    var themePreset by mutableStateOf("default")
    var colorMode by mutableStateOf("normal")

    // 玻璃主题
    var glassTheme by mutableStateOf(false)

    // 视差背景
    var parallaxBackground by mutableStateOf(false)

    // 自定义背景
    var customBackground by mutableStateOf(false)

    // 锁屏启动页
    var lockscreenLaunchTheme by mutableStateOf(false)

    // UI 缩放 (0.75 ~ 1.5)
    var uiScale by mutableStateOf(1.0f)

    fun applyThemePreset(preset: String) {
        themePreset = preset
    }

    fun applyColorMode(mode: String) {
        colorMode = mode
    }

    fun applyUiScale(scale: Float) {
        uiScale = scale.coerceIn(0.75f, 1.5f)
    }

    fun applyGlassTheme(on: Boolean) {
        glassTheme = on
    }

    fun applyParallaxBackground(on: Boolean) {
        parallaxBackground = on
    }

    fun applyCustomBackground(on: Boolean) {
        customBackground = on
    }

    fun applyLockscreenLaunchTheme(on: Boolean) {
        lockscreenLaunchTheme = on
    }
}
