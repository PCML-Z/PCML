package com.pmcl.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃主题卡片：在内容下方铺设一层半透明 + 模糊的毛玻璃层，
 * 让背景视差/壁纸透出，营造 iOS Liquid Glass / macOS Frosted 风格。
 *
 * 项目内已有 5 处 Modifier.blur() 成功先例（ModsMarketPage、CompanionPairDialog 等），
 * Skia `ImageFilter::MakeBlur` 走 GPU 加速，性能开销可控。
 *
 * 仅在玻璃主题开启时由调用方使用。组件本身保持无状态。
 *
 * @param cornerRadius 圆角半径，默认 12dp（与 Card 默认一致）
 * @param tint 颜色叠加层，默认 surface 55% 不透明度
 * @param blurRadius 模糊半径，默认 16dp
 * @param modifier 父级修饰符
 * @param content 卡片内容
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    blurRadius: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            // 模糊背景：依赖后方内容（视差层/壁纸）经 Skia 高斯模糊后绘制
            .blur(blurRadius)
            .background(tint, RoundedCornerShape(cornerRadius))
    ) {
        content()
    }
}
