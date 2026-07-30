package com.pmcl.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 玻璃主题（无壁纸）时卡片容器默认透明度 */
private const val GLASS_CARD_ALPHA = 0.55f

/** 自定义/视差壁纸上的卡片透明度（需更低，否则仍像实色方块） */
private const val WALLPAPER_CARD_ALPHA = 0.38f

/** 玻璃主题开启时 surfaceVariant 类容器默认透明度 */
private const val GLASS_VARIANT_ALPHA = 0.45f

/**
 * 仅由「玻璃主题」开关控制半透明卡片。
 * 自定义/视差壁纸不再强制玻璃效果，否则关闭玻璃主题看起来不生效。
 */
private fun ThemeState.wantsTranslucentCards(): Boolean = glassTheme

/** 当前场景应使用的卡片透明度（仅 glassTheme 开启时有意义） */
private fun ThemeState.cardAlpha(): Float = when {
    !glassTheme -> 1f
    // 有壁纸时更透，避免仍像实色方块
    customBackground || parallaxBackground -> WALLPAPER_CARD_ALPHA
    else -> GLASS_CARD_ALPHA
}

/**
 * 玻璃主题辅助：返回 Card 在当前主题下应使用的 colors。
 *
 * - glassTheme 关闭：MaterialTheme 标准实色 CardColors
 * - glassTheme + 壁纸：低透明 (0.38)
 * - glassTheme 无壁纸：半透明 (0.55)
 */
@Composable
fun glassCardColors(
    containerColor: Color? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
): CardColors {
    val theme = LocalThemeState.current
    if (!theme.wantsTranslucentCards()) {
        return if (containerColor != null) {
            CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
        } else {
            CardDefaults.cardColors()
        }
    }
    val base = containerColor ?: MaterialTheme.colorScheme.surface
    val alpha = theme.cardAlpha()
    // 已带透明通道且更透的颜色保留调用方意图，否则套用场景透明度
    val resolved = if (base.alpha < alpha) base else base.copy(alpha = alpha)
    return CardDefaults.cardColors(containerColor = resolved, contentColor = contentColor)
}

/**
 * 玻璃主题下把任意实色容器转为半透明；关闭玻璃主题时原样返回。
 * 用于 Surface / Box background 等非 Card 容器，保持与卡片视觉一致。
 */
@Composable
fun glassContainerColor(
    solid: Color = MaterialTheme.colorScheme.surface,
    glassAlpha: Float = -1f
): Color {
    val theme = LocalThemeState.current
    if (!theme.wantsTranslucentCards()) return solid
    val alpha = if (glassAlpha > 0f) glassAlpha else theme.cardAlpha()
    if (solid.alpha < alpha) return solid
    return solid.copy(alpha = alpha)
}

/**
 * surfaceVariant 风格容器色：玻璃主题下半透明，否则实色。
 */
@Composable
fun glassSurfaceVariantColor(
    glassAlpha: Float = GLASS_VARIANT_ALPHA
): Color = glassContainerColor(
    solid = MaterialTheme.colorScheme.surfaceVariant,
    glassAlpha = glassAlpha
)

/**
 * 返回 Card 在当前主题下应使用的 elevation（阴影深度）。
 *
 * - 透出背景时：0.dp 无阴影（避免实色投影块）
 * - 默认：1.dp
 *
 * 返回 CardElevation 类型，可直接传入 Card 的 elevation 参数。
 */
@Composable
fun glassCardElevation(defaultElevation: Dp = 1.dp): CardElevation {
    val theme = LocalThemeState.current
    val dp = if (theme.wantsTranslucentCards()) 0.dp else defaultElevation
    return CardDefaults.cardElevation(defaultElevation = dp)
}

/**
 * 玻璃主题边框 Modifier：玻璃主题下无边框（返回 this 不添加任何 border）。
 *
 * 仅在 glassTheme 开启时生效（即不画边框）。
 * 默认主题同样返回无修改 Modifier。
 *
 * 用法：
 * ```
 * Card(modifier = Modifier.glassCardBorder(), ...) { ... }
 * ```
 */
@Composable
fun Modifier.glassCardBorder(cornerRadius: Dp = 12.dp): Modifier {
    // 玻璃主题不绘制任何边框，仅靠阴影和透明度区分卡片
    return this
}

/**
 * 统一卡片：自动应用透明/玻璃主题的 colors、elevation、border。
 * 新界面与改造中的界面应优先使用本组件，避免再出现纯色卡片与玻璃卡片混用。
 */
@Composable
fun PmclCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: CardColors = glassCardColors(),
    elevation: CardElevation = glassCardElevation(),
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = modifier.glassCardBorder()
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    } else {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    }
}
