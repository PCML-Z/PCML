package com.pmcl.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp

/**
 * 窗口级背景层：自定义背景（图片/视频）优先于视差背景，可叠加壁纸毛玻璃与材质霜面。
 *
 * 主窗口与独立窗口共用同一套绘制，保证「PMCL 的主题」在各自窗口里观感一致；
 * 调用方负责在其上盖一层透明 Surface（有背景层时不能再用不透明的 surface 色）。
 */
@Composable
fun WindowBackdrop(
    themeState: ThemeState,
    parallax: Boolean,
    customType: String,
    customImage: String,
    customVideo: String,
    materialOn: Boolean,
    liveGlassOn: Boolean,
    modifier: Modifier = Modifier
) {
    val useDark = themeState.useDark
    val customOn = (customType == "image" && customImage.isNotBlank()) ||
        (customType == "video" && customVideo.isNotBlank())
    val videoOn = customType == "video" && customVideo.isNotBlank()

    if (liveGlassOn) {
        // 壁纸毛玻璃：壁纸逐帧自己模糊，画面从玻璃里透出来
        LiveWallpaperGlass(
            modifier = Modifier.fillMaxSize().then(modifier),
            useDark = useDark,
            blurWallpaper = !videoOn
        ) {
            if (customOn) {
                CustomBackground(
                    type = customType,
                    imagePath = customImage,
                    videoPath = customVideo,
                    useDark = useDark,
                    scrimAlpha = if (videoOn) 0f else 0.06f,
                    frameBlur = videoOn
                )
            } else {
                ParallaxBackground(useDark = useDark, scrim = false)
            }
        }
    } else {
        // 材质主题：先模糊壁纸，再盖一层透出颜色的薄霜；视频逐帧模糊，不再叠 Modifier.blur
        val painted = if (materialOn && !videoOn) modifier.blur(22.dp) else modifier
        if (customOn) {
            CustomBackground(
                type = customType,
                imagePath = customImage,
                videoPath = customVideo,
                useDark = useDark,
                modifier = painted,
                scrimAlpha = if (materialOn) 0.06f else 0.45f,
                frameBlur = materialOn && videoOn
            )
        } else if (parallax || materialOn) {
            ParallaxBackground(
                modifier = painted,
                useDark = useDark,
                scrim = !materialOn
            )
        }
    }
}
