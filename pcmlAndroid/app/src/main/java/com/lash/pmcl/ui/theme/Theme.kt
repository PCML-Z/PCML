package com.lash.pmcl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// ===== 基础配色 =====

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D8BFF),
    secondary = Color(0xFF55C57A),
    tertiary = Color(0xFFFA8C16),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E5EA),
    onSurface = Color(0xFF1A1C1E),
    onSurfaceVariant = Color(0xFF74777F),
    outline = Color(0xFF74777F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5BA0FF),
    secondary = Color(0xFF6ED68A),
    tertiary = Color(0xFFFFA940),
    background = Color(0xFF1A1D23),
    surface = Color(0xFF22262E),
    surfaceVariant = Color(0xFF2C2F35),
    onSurface = Color(0xFFE2E2E5),
    onSurfaceVariant = Color(0xFF8C9099),
    outline = Color(0xFF8C9099),
)

// ===== 颜色预设 =====

private fun presetSchemes(preset: String): Pair<ColorScheme, ColorScheme> {
    val seed = when (preset) {
        "ocean"    -> 0xFF0277BD.toInt()
        "forest"   -> 0xFF2E7D32.toInt()
        "sunset"   -> 0xFFE65100.toInt()
        "lavender" -> 0xFF6A1B9A.toInt()
        "sakura"   -> 0xFFD81B60.toInt()
        "midnight" -> 0xFF263238.toInt()
        else       -> 0xFF3D8BFF.toInt()
    }
    // 使用简化的色调映射生成 light/dark scheme
    val light = lightColorScheme(
        primary = Color(seed),
        secondary = Color(0xFF55C57A),
        tertiary = Color(0xFFFA8C16),
        background = Color(0xFFF5F7FA),
        surface = Color(0xFFFFFFFF),
    )
    val dark = darkColorScheme(
        primary = Color(seed).let { c ->
            Color(c.red * 0.8f + 0.2f, c.green * 0.8f + 0.2f, c.blue * 0.8f + 0.2f)
        },
        secondary = Color(0xFF6ED68A),
        tertiary = Color(0xFFFFA940),
        background = Color(0xFF1A1D23),
        surface = Color(0xFF22262E),
    )
    return light to dark
}

private fun applyColorMode(scheme: ColorScheme, mode: String, dark: Boolean): ColorScheme {
    return when (mode) {
        "amoled" -> if (dark) {
            scheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF0A0A0A),
                onSurfaceVariant = Color(0xFFB0B0B0)
            )
        } else scheme
        "high_contrast" -> {
            val boost = if (dark) 1.3f else 0.85f
            val adjust: (Color) -> Color = { c ->
                Color(
                    (c.red * boost).coerceIn(0f, 1f),
                    (c.green * boost).coerceIn(0f, 1f),
                    (c.blue * boost).coerceIn(0f, 1f),
                    c.alpha
                )
            }
            scheme.copy(
                primary = adjust(scheme.primary),
                onPrimary = adjust(scheme.onPrimary),
                onBackground = adjust(scheme.onBackground),
                onSurface = adjust(scheme.onSurface)
            )
        }
        "soft" -> {
            val desat: (Color) -> Color = { c ->
                val gray = (c.red + c.green + c.blue) / 3f
                Color(
                    c.red * 0.75f + gray * 0.25f,
                    c.green * 0.75f + gray * 0.25f,
                    (c.blue * 0.75f + gray * 0.25f) * 0.85f,
                    c.alpha
                )
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

// ===== PmclTheme =====

@Composable
fun PmclTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    themeState: ThemeState = LocalThemeState.current,
    content: @Composable () -> Unit,
) {
    val baseColors: ColorScheme = when {
        themeState.themePreset != "default" -> {
            val (light, dark) = remember(themeState.themePreset) { presetSchemes(themeState.themePreset) }
            if (darkTheme) dark else light
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val targetColors = remember(baseColors, themeState.colorMode, darkTheme) {
        applyColorMode(baseColors, themeState.colorMode, darkTheme)
    }

    val scaledTypography = rememberScaledTypography(themeState.uiScale)

    MaterialTheme(
        colorScheme = targetColors,
        typography = scaledTypography,
        content = content,
    )
}

@Composable
private fun rememberScaledTypography(scale: Float): Typography {
    return remember(scale) {
        val base = Typography()
        if (scale == 1.0f) return@remember base
        val s = { sp: TextUnit -> (sp.value * scale).sp }
        Typography(
            displayLarge = base.displayLarge.copy(fontSize = s(base.displayLarge.fontSize), lineHeight = s(base.displayLarge.lineHeight)),
            displayMedium = base.displayMedium.copy(fontSize = s(base.displayMedium.fontSize), lineHeight = s(base.displayMedium.lineHeight)),
            displaySmall = base.displaySmall.copy(fontSize = s(base.displaySmall.fontSize), lineHeight = s(base.displaySmall.lineHeight)),
            headlineLarge = base.headlineLarge.copy(fontSize = s(base.headlineLarge.fontSize), lineHeight = s(base.headlineLarge.lineHeight)),
            headlineMedium = base.headlineMedium.copy(fontSize = s(base.headlineMedium.fontSize), lineHeight = s(base.headlineMedium.lineHeight)),
            headlineSmall = base.headlineSmall.copy(fontSize = s(base.headlineSmall.fontSize), lineHeight = s(base.headlineSmall.lineHeight)),
            titleLarge = base.titleLarge.copy(fontSize = s(base.titleLarge.fontSize), lineHeight = s(base.titleLarge.lineHeight)),
            titleMedium = base.titleMedium.copy(fontSize = s(base.titleMedium.fontSize), lineHeight = s(base.titleMedium.lineHeight)),
            titleSmall = base.titleSmall.copy(fontSize = s(base.titleSmall.fontSize), lineHeight = s(base.titleSmall.lineHeight)),
            bodyLarge = base.bodyLarge.copy(fontSize = s(base.bodyLarge.fontSize), lineHeight = s(base.bodyLarge.lineHeight)),
            bodyMedium = base.bodyMedium.copy(fontSize = s(base.bodyMedium.fontSize), lineHeight = s(base.bodyMedium.lineHeight)),
            bodySmall = base.bodySmall.copy(fontSize = s(base.bodySmall.fontSize), lineHeight = s(base.bodySmall.lineHeight)),
            labelLarge = base.labelLarge.copy(fontSize = s(base.labelLarge.fontSize), lineHeight = s(base.labelLarge.lineHeight)),
            labelMedium = base.labelMedium.copy(fontSize = s(base.labelMedium.fontSize), lineHeight = s(base.labelMedium.lineHeight)),
            labelSmall = base.labelSmall.copy(fontSize = s(base.labelSmall.fontSize), lineHeight = s(base.labelSmall.lineHeight)),
        )
    }
}
