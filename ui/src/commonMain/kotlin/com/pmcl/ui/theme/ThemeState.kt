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

    /** 深浅色跟随系统外观 */
    var followSystem by mutableStateOf(false)
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

    /** UI 缩放系数，1.0 = 默认大小 */
    var uiScale by mutableStateOf(1.0f)
        private set
        
    var parallaxBackground by mutableStateOf(false)
        private set

    /** 自定义背景（图片/视频）是否激活：激活时 App 内容层透明，透出窗口级背景 */
    var customBackground by mutableStateOf(false)
        private set

    var glassTheme by mutableStateOf(false)
        private set

    /** 锁屏启动页主题：Origin OS2 风格方形卡片启动页 */
    var lockscreenLaunchTheme by mutableStateOf(false)
        private set

    /** 主题色彩预设 ID：default/ocean/forest/sunset/lavender/sakura/midnight */
    var themePreset by mutableStateOf("default")
        private set

    /** 色彩模式 ID：normal/amoled/high_contrast/soft */
    var colorMode by mutableStateOf("normal")
        private set

    /** 插件主题包 ID（空字符串表示未使用插件主题） */
    var customThemePackId by mutableStateOf("")
        private set

    /** 当前应用的插件主题包（null 表示未使用） */
    var customThemePack by mutableStateOf<com.pmcl.plugin.ThemePack?>(null)
        private set

    fun toggle() = set(!useDark)

    fun set(value: Boolean) {
        useDark = value
    }

    fun applyFollowSystem(enabled: Boolean) {
        followSystem = enabled
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

    fun applyCustomBackground(active: Boolean) {
        customBackground = active
    }

    fun applyGlassTheme(enabled: Boolean) {
        glassTheme = enabled
    }

    fun applyLockscreenLaunchTheme(enabled: Boolean) {
        lockscreenLaunchTheme = enabled
    }

    fun applyThemePreset(preset: String) {
        themePreset = preset
    }

    fun applyColorMode(mode: String) {
        colorMode = mode
    }

    /**
     * 应用插件主题包。
     * - pack 为 null 且 packId 为空：清除插件主题，回退到预设/默认
     * - pack 非空：设置 customThemePack，颜色由 Theme.kt 转换应用
     */
    fun applyCustomThemePack(pack: com.pmcl.plugin.ThemePack?) {
        customThemePack = pack
        customThemePackId = pack?.id ?: ""
    }

    /** 仅设置 packId（用于启动时占位，等待插件加载后填充 pack） */
    fun applyCustomThemePackId(packId: String) {
        customThemePackId = packId
        if (packId.isEmpty()) customThemePack = null
    }

    fun applySeedColor(seedRgb: Int, dark: Boolean) {
        // M42 修复：本方法可能从 IO 线程（refreshWallpaperColor 协程）调用。
        // Compose Desktop (JVM) 的 snapshot 系统本身线程安全，mutableStateOf 赋值
        // 可跨线程进行。先计算完整 scheme 再一次性赋值给 dynamicColorScheme，
        // 减少中间状态（seedColor 已变但 scheme 未更新）被观察到的窗口。
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
