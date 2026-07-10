package com.pmcl.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
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
    MaterialTheme(
        colorScheme = animatedColors,
        content = content
    )
}
