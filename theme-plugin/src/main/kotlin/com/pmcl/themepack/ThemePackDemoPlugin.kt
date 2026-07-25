package com.pmcl.themepack

import com.pmcl.plugin.PmclPlugin
import com.pmcl.plugin.PluginContext
import com.pmcl.plugin.ThemePack
import com.pmcl.plugin.ThemePalette

/**
 * 示例主题包插件：注册三个知名配色方案（Nord / Dracula / Solarized）。
 *
 * 演示如何通过 registerThemePack 向 PMCL 提供完整主题配色。
 * 用户安装并启用本插件后，在 设置 → 外观 → 插件主题包 中可选择切换。
 */
class ThemePackDemoPlugin : PmclPlugin {
    override val pluginId = "theme-pack-demo"

    override fun onEnable(ctx: PluginContext) {
        ctx.info("Theme Pack Demo plugin enabled!")

        // 注册三个主题包
        ctx.registerThemePack(NordThemePack)
        ctx.registerThemePack(DraculaThemePack)
        ctx.registerThemePack(SolarizedLightThemePack)

        ctx.info("Registered 3 theme packs: nord, dracula, solarized-light")
    }

    override fun onDisable() {
        println("[theme-pack-demo] Goodbye!")
    }
}

// ARGB 构造助手（alpha=0xFF）
private fun argb(rgb: Int): Int = rgb or 0xFF000000.toInt()

// ===== Nord 主题（极地灵感的冷色调） =====
// 官方配色：https://www.nordtheme.com/
val NordThemePack = ThemePack(
    id = "theme-pack-demo-nord",
    name = "Nord",
    author = "Nord Theme",
    description = "Arctic, north-bluish color palette",
    previewColor = argb(0x88C0D0),
    lightPalette = ThemePalette(
        primary = argb(0x5E81AC), onPrimary = argb(0xECEFF4),
        primaryContainer = argb(0xD8E6F5), onPrimaryContainer = argb(0x2E3440),
        secondary = argb(0x81A1C1), onSecondary = argb(0xECEFF4),
        tertiary = argb(0x88C0D0),
        background = argb(0xECEFF4), onBackground = argb(0x2E3440),
        surface = argb(0xE5E9F0), onSurface = argb(0x2E3440),
        surfaceVariant = argb(0xD8DEE9), onSurfaceVariant = argb(0x4C566A),
        outline = argb(0x4C566A),
        error = argb(0xBF616A), onError = argb(0xECEFF4)
    ),
    darkPalette = ThemePalette(
        primary = argb(0x88C0D0), onPrimary = argb(0x2E3440),
        primaryContainer = argb(0x3B4252), onPrimaryContainer = argb(0xD8E6F5),
        secondary = argb(0x81A1C1), onSecondary = argb(0x2E3440),
        tertiary = argb(0x5E81AC),
        background = argb(0x2E3440), onBackground = argb(0xD8DEE9),
        surface = argb(0x3B4252), onSurface = argb(0xECEFF4),
        surfaceVariant = argb(0x434C5E), onSurfaceVariant = argb(0xD8DEE9),
        outline = argb(0x88C0D0),
        error = argb(0xBF616A), onError = argb(0x2E3440)
    )
)

// ===== Dracula 主题（深色吸血鬼风） =====
// 官方配色：https://draculatheme.com/
val DraculaThemePack = ThemePack(
    id = "theme-pack-demo-dracula",
    name = "Dracula",
    author = "Dracula Theme",
    description = "Dark, vibrant color palette",
    previewColor = argb(0xBD93F9),
    lightPalette = ThemePalette(
        primary = argb(0x7C3AED), onPrimary = argb(0xFFFFFF),
        primaryContainer = argb(0xE9D5FF), onPrimaryContainer = argb(0x282A36),
        secondary = argb(0xFF79C6), onSecondary = argb(0xFFFFFF),
        tertiary = argb(0x50FA7B),
        background = argb(0xF8F8F2), onBackground = argb(0x282A36),
        surface = argb(0xFFFFFF), onSurface = argb(0x282A36),
        surfaceVariant = argb(0xE6E6E0), onSurfaceVariant = argb(0x44475A),
        outline = argb(0x6272A4),
        error = argb(0xFF5555), onError = argb(0xFFFFFF)
    ),
    darkPalette = ThemePalette(
        primary = argb(0xBD93F9), onPrimary = argb(0x282A36),
        primaryContainer = argb(0x44475A), onPrimaryContainer = argb(0xE9D5FF),
        secondary = argb(0xFF79C6), onSecondary = argb(0x282A36),
        tertiary = argb(0x50FA7B),
        background = argb(0x282A36), onBackground = argb(0xF8F8F2),
        surface = argb(0x44475A), onSurface = argb(0xF8F8F2),
        surfaceVariant = argb(0x383A4A), onSurfaceVariant = argb(0x8BE9FD),
        outline = argb(0x6272A4),
        error = argb(0xFF5555), onError = argb(0xF8F8F2)
    )
)

// ===== Solarized Light 主题（柔和护眼） =====
// 官方配色：https://ethanschoonover.com/solarized/
val SolarizedLightThemePack = ThemePack(
    id = "theme-pack-demo-solarized-light",
    name = "Solarized Light",
    author = "Ethan Schoonover",
    description = "Precision colors for machines and people",
    previewColor = argb(0x268BD2),
    lightPalette = ThemePalette(
        primary = argb(0x268BD2), onPrimary = argb(0xFDF6E3),
        primaryContainer = argb(0xB4D8F0), onPrimaryContainer = argb(0x073642),
        secondary = argb(0x2AA198), onSecondary = argb(0xFDF6E3),
        tertiary = argb(0xB58900),
        background = argb(0xFDF6E3), onBackground = argb(0x657B83),
        surface = argb(0xEEE8D5), onSurface = argb(0x586E75),
        surfaceVariant = argb(0xE6DDC4), onSurfaceVariant = argb(0x93A1A1),
        outline = argb(0x93A1A1),
        error = argb(0xDC322F), onError = argb(0xFDF6E3)
    ),
    darkPalette = ThemePalette(
        primary = argb(0x839496), onPrimary = argb(0x073642),
        primaryContainer = argb(0x073642), onPrimaryContainer = argb(0x93A1A1),
        secondary = argb(0x2AA198), onSecondary = argb(0x073642),
        tertiary = argb(0xB58900),
        background = argb(0x073642), onBackground = argb(0x93A1A1),
        surface = argb(0x002B36), onSurface = argb(0x839496),
        surfaceVariant = argb(0x073642), onSurfaceVariant = argb(0x93A1A1),
        outline = argb(0x586E75),
        error = argb(0xDC322F), onError = argb(0x073642)
    )
)
