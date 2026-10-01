package com.pmcl.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 当前壁纸逐帧模糊后铺满窗口。模糊跟在壁纸自己的绘制后面，
 * 视频换帧、视差跟着鼠标动时，玻璃里的画面一起动。
 */
@Composable
fun LiveWallpaperGlass(
    modifier: Modifier = Modifier,
    useDark: Boolean,
    wallpaper: @Composable () -> Unit
) {
    val veil = if (useDark) Color(0xFF101218).copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f)
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .blur(14.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        ) {
            wallpaper()
        }
        Box(Modifier.fillMaxSize().background(veil))
    }
}
