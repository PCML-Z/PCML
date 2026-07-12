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

@Composable
fun LauncherTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColorScheme: ColorScheme? = null,
    uiScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    // 主题颜色平滑过渡（约 400ms）
    // 莫奈取色开启时使用动态 ColorScheme，否则用固定配色
    val targetColors = dynamicColorScheme ?: if (useDarkTheme) DarkColors else LightColors
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
 */
@Composable
private fun rememberScaledTypography(scale: Float): Typography {
    val base = Typography()
    if (scale == 1.0f) return base
    val s = { sp: TextUnit -> (sp.value * scale).sp }
    return Typography(
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

private fun TextStyle.scale(s: (TextUnit) -> TextUnit): TextStyle = copy(
    fontSize = s(fontSize),
    lineHeight = s(lineHeight)
)
