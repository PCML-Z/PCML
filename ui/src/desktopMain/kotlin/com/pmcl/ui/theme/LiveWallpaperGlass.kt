package com.pmcl.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 壁纸整面模糊后，再盖一层薄霜。正中也是毛玻璃，能看见模糊后的颜色，不是挖空。
 */
@Composable
fun LiveWallpaperGlass(
    modifier: Modifier = Modifier,
    useDark: Boolean,
    blurWallpaper: Boolean = true,
    blurRadius: Dp = 22.dp,
    wallpaper: @Composable () -> Unit
) {
    val veil = if (useDark) Color(0xFF101218).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.18f)
    val blur = blurWallpaper && blurRadius > 0.dp
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (blur) Modifier.blur(blurRadius, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    else Modifier
                )
        ) {
            wallpaper()
        }
        Box(Modifier.fillMaxSize().background(veil))
    }
}
