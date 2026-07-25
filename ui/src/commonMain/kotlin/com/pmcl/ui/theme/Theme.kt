package com.pmcl.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.pmcl.ui.animation.MotionTokens

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D8BFF),
    secondary = Color(0xFF55C57A),
    tertiary = Color(0xFFFA8C16),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5BA0FF),
    secondary = Color(0xFF6ED68A),
    tertiary = Color(0xFFFFA940),
    background = Color(0xFF1A1D23),
    surface = Color(0xFF22262E),
)

/**
 * 预设主题方案：每个预设返回 (lightScheme, darkScheme)。
 * 复用 WallpaperColorProvider 生成完整协调配色，保证与莫奈/自定义色一致性。
 */
private fun presetSchemes(preset: String): Pair<ColorScheme, ColorScheme> {
    // 预设种子色（RGB，不含 alpha）
    val seedRgb = when (preset) {
        "ocean"    -> 0x0277BD
        "forest"   -> 0x2E7D32
        "sunset"   -> 0xE65100
        "lavender" -> 0x6A1B9A
        "sakura"   -> 0xD81B60
        "midnight" -> 0x263238
        else       -> 0x3D8BFF  // default
    }
    val paletteLight = com.pmcl.core.theme.WallpaperColorProvider.generateFullPalette(seedRgb, false)
    val paletteDark = com.pmcl.core.theme.WallpaperColorProvider.generateFullPalette(seedRgb, true)
    val toColor = { rgb: Int -> Color(rgb or 0xFF000000.toInt()) }
    val build = { p: com.pmcl.core.theme.WallpaperColorProvider.FullPalette, dark: Boolean ->
        if (dark) darkColorScheme(
            primary = toColor(p.primary), onPrimary = toColor(p.onPrimary),
            primaryContainer = toColor(p.primaryContainer), onPrimaryContainer = toColor(p.onPrimaryContainer),
            secondary = toColor(p.secondary), onSecondary = toColor(p.onSecondary),
            tertiary = toColor(p.tertiary),
            background = toColor(p.background), onBackground = toColor(p.onBackground),
            surface = toColor(p.surface), onSurface = toColor(p.onSurface),
            surfaceVariant = toColor(p.surfaceVariant), onSurfaceVariant = toColor(p.onSurfaceVariant),
            outline = toColor(p.outline),
            error = toColor(p.error), onError = toColor(p.onError)
        ) else lightColorScheme(
            primary = toColor(p.primary), onPrimary = toColor(p.onPrimary),
            primaryContainer = toColor(p.primaryContainer), onPrimaryContainer = toColor(p.onPrimaryContainer),
            secondary = toColor(p.secondary), onSecondary = toColor(p.onSecondary),
            tertiary = toColor(p.tertiary),
            background = toColor(p.background), onBackground = toColor(p.onBackground),
            surface = toColor(p.surface), onSurface = toColor(p.onSurface),
            surfaceVariant = toColor(p.surfaceVariant), onSurfaceVariant = toColor(p.onSurfaceVariant),
            outline = toColor(p.outline),
            error = toColor(p.error), onError = toColor(p.onError)
        )
    }
    return build(paletteLight, false) to build(paletteDark, true)
}

/**
 * 特殊色彩模式后处理：对 ColorScheme 进行变换以实现 AMOLED/高对比/柔护眼效果。
 */
private fun applyColorMode(scheme: ColorScheme, mode: String, dark: Boolean): ColorScheme {
    return when (mode) {
        "amoled" -> if (dark) {
            // AMOLED 纯黑：背景/表面改为纯黑，surfaceVariant 接近黑
            scheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF0A0A0A),
                onSurfaceVariant = Color(0xFFB0B0B0)
            )
        } else scheme
        "high_contrast" -> {
            // 高对比度：加深前景色，提亮背景
            val boost = if (dark) 1.3f else 0.85f
            val adjust: (Color) -> Color = { c ->
                val r = (c.red * boost).coerceIn(0f, 1f)
                val g = (c.green * boost).coerceIn(0f, 1f)
                val b = (c.blue * boost).coerceIn(0f, 1f)
                Color(r, g, b, c.alpha)
            }
            scheme.copy(
                primary = adjust(scheme.primary),
                onPrimary = adjust(scheme.onPrimary),
                onBackground = adjust(scheme.onBackground),
                onSurface = adjust(scheme.onSurface)
            )
        }
        "soft" -> {
            // 柔护眼：降低饱和度（向灰色靠拢），减少蓝光
            val desat: (Color) -> Color = { c ->
                val gray = (c.red + c.green + c.blue) / 3f
                val r = c.red * 0.75f + gray * 0.25f
                val g = c.green * 0.75f + gray * 0.25f
                // 降低蓝色分量，减少蓝光
                val b = (c.blue * 0.75f + gray * 0.25f) * 0.85f
                Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), c.alpha)
            }
            scheme.copy(
                primary = desat(scheme.primary),
                secondary = desat(scheme.secondary),
                tertiary = desat(scheme.tertiary),
                background = desat(scheme.background),
                surface = desat(scheme.surface)
            )
        }
        else -> scheme
    }
}

@Composable
fun LauncherTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColorScheme: ColorScheme? = null,
    uiScale: Float = 1.0f,
    themePreset: String = "default",
    colorMode: String = "normal",
    content: @Composable () -> Unit
) {
    // 主题颜色平滑过渡（约 400ms）
    // 莫奈取色或自定义强调色时使用动态 ColorScheme
    // 否则使用预设主题方案（若 themePreset != "default"）或默认固定配色
    val baseColors: ColorScheme = when {
        dynamicColorScheme != null -> dynamicColorScheme
        themePreset != "default" -> {
            val (light, dark) = remember(themePreset) { presetSchemes(themePreset) }
            if (useDarkTheme) dark else light
        }
        else -> if (useDarkTheme) DarkColors else LightColors
    }
    // 应用特殊色彩模式变换
    val targetColors = remember(baseColors, colorMode, useDarkTheme) {
        applyColorMode(baseColors, colorMode, useDarkTheme)
    }
    val animSpec = tween<Color>(
        durationMillis = MotionTokens.DURATION_LONG,
        easing = MotionTokens.EasingEmphasized
    )
    val primary by animateColorAsState(targetColors.primary, animSpec, label = "primary")
    val onPrimary by animateColorAsState(targetColors.onPrimary, animSpec, label = "onPrimary")
    val primaryContainer by animateColorAsState(targetColors.primaryContainer, animSpec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(targetColors.onPrimaryContainer, animSpec, label = "onPrimaryContainer")
    val secondary by animateColorAsState(targetColors.secondary, animSpec, label = "secondary")
    val onSecondary by animateColorAsState(targetColors.onSecondary, animSpec, label = "onSecondary")
    val tertiary by animateColorAsState(targetColors.tertiary, animSpec, label = "tertiary")
    val background by animateColorAsState(targetColors.background, animSpec, label = "background")
    val onBackground by animateColorAsState(targetColors.onBackground, animSpec, label = "onBackground")
    val surface by animateColorAsState(targetColors.surface, animSpec, label = "surface")
    val onSurface by animateColorAsState(targetColors.onSurface, animSpec, label = "onSurface")
    val surfaceVariant by animateColorAsState(targetColors.surfaceVariant, animSpec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(targetColors.onSurfaceVariant, animSpec, label = "onSurfaceVariant")
    val outline by animateColorAsState(targetColors.outline, animSpec, label = "outline")
    val error by animateColorAsState(targetColors.error, animSpec, label = "error")

    val animatedColors = targetColors.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        tertiary = tertiary,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        error = error
    )

    // 根据 uiScale 生成缩放后的 Typography
    val scaledTypography = rememberScaledTypography(uiScale)

    MaterialTheme(
        colorScheme = animatedColors,
        typography = scaledTypography,
        content = content
    )
}

/**
 * 根据 uiScale 缩放 Material3 Typography 中所有 TextStyle 的 fontSize。
 * 使用 remember 缓存，仅在 scale 变化时重建 15 个 TextStyle，避免每次重组都分配对象。
 */
@Composable
private fun rememberScaledTypography(scale: Float): Typography {
    // scale == 1.0f 时返回默认 Typography 单例，避免不必要的对象分配
    return remember(scale) {
        val base = Typography()
        if (scale == 1.0f) return@remember base
        val s = { sp: TextUnit -> (sp.value * scale).sp }
        Typography(
            displayLarge = base.displayLarge.scale(s),
            displayMedium = base.displayMedium.scale(s),
            displaySmall = base.displaySmall.scale(s),
            headlineLarge = base.headlineLarge.scale(s),
            headlineMedium = base.headlineMedium.scale(s),
            headlineSmall = base.headlineSmall.scale(s),
            titleLarge = base.titleLarge.scale(s),
            titleMedium = base.titleMedium.scale(s),
            titleSmall = base.titleSmall.scale(s),
            bodyLarge = base.bodyLarge.scale(s),
            bodyMedium = base.bodyMedium.scale(s),
            bodySmall = base.bodySmall.scale(s),
            labelLarge = base.labelLarge.scale(s),
            labelMedium = base.labelMedium.scale(s),
            labelSmall = base.labelSmall.scale(s)
        )
    }
}

private fun TextStyle.scale(s: (TextUnit) -> TextUnit): TextStyle = copy(
    fontSize = s(fontSize),
    lineHeight = s(lineHeight)
)
