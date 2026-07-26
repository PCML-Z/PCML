package com.pmcl.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 用户自定义背景层（窗口级，渲染在所有内容之下）。
 *
 * - type == "image"：本地图片背景（Skia 解码，支持 png/jpg/webp/bmp/gif 首帧）
 * - type == "video"：本地视频背景（JavaCV/FFmpeg 解码，静音循环播放）
 *
 * 内容之上覆盖半透明遮罩保证可读性（同 ParallaxBackground 的思路，但透明度更低，
 * 让用户选择的画面保持可见）。
 */
@Composable
fun CustomBackground(
    type: String,
    imagePath: String,
    videoPath: String,
    useDark: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize()) {
        when (type) {
            "image" -> ImageBackgroundLayer(imagePath)
            "video" -> VideoBackgroundLayer(videoPath)
        }
        // 遮罩：约 45% 透明度，兼顾内容可读性与背景可见度
        val scrimColor = if (useDark) Color(0x730D1117) else Color(0x73F5F5F7)
        Canvas(Modifier.fillMaxSize()) { drawRect(scrimColor) }
    }
}

/** 图片背景层：解码一次，Crop 填满窗口。 */
@Composable
private fun ImageBackgroundLayer(path: String) {
    val bitmap: ImageBitmap? = remember(path) {
        try {
            val bytes = Files.readAllBytes(Paths.get(path))
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (e: Throwable) {
            System.err.println("[CustomBackground] 背景图加载失败: $path ($e)")
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

/** 解码宽度上限：更大的视频缩放解码，降低每帧 BufferedImage→ImageBitmap 转换开销 */
private const val MAX_DECODE_WIDTH = 1600

/**
 * 视频背景层：后台 IO 协程用 FFmpeg 逐帧解码，按视频帧率节流推送到 Compose 状态。
 * 播放到结尾自动 seek 回开头循环；组件销毁时协程取消并释放解码器。
 */
@Composable
private fun VideoBackgroundLayer(path: String) {
    var frame by remember(path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            var grabber: FFmpegFrameGrabber? = null
            val converter = Java2DFrameConverter()
            try {
                grabber = FFmpegFrameGrabber(path).apply {
                    audioChannels = 0  // 静音：跳过音频流解码
                    start()
                }
                // 高分辨率视频缩放解码，节省 CPU（swscale 在解码侧完成）
                if (grabber.imageWidth > MAX_DECODE_WIDTH) {
                    val w = MAX_DECODE_WIDTH
                    val h = (grabber.imageHeight.toLong() * w / grabber.imageWidth).toInt() and -2
                    grabber.stop(); grabber.release()
                    grabber = FFmpegFrameGrabber(path).apply {
                        audioChannels = 0
                        imageWidth = w
                        imageHeight = h
                        start()
                    }
                }
                val frameDelayMs =
                    if (grabber.frameRate > 1.0) (1000.0 / grabber.frameRate).toLong() else 33L
                var consecutiveNulls = 0
                while (isActive) {
                    val t0 = System.nanoTime()
                    val f = grabber.grabImage()
                    if (f == null) {
                        // 播放结束 → 回到开头循环；连续失败则等待重试避免忙循环
                        consecutiveNulls++
                        if (consecutiveNulls > 2) delay(500)
                        grabber.setTimestamp(0)
                        continue
                    }
                    consecutiveNulls = 0
                    val img = converter.convert(f) ?: continue
                    // Snapshot 状态线程安全，可直接从 IO 线程赋值
                    frame = img.toComposeImageBitmap()
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                    val wait = frameDelayMs - elapsedMs
                    if (wait > 0) delay(wait)
                }
            } catch (e: Throwable) {
                val arch = System.getProperty("os.arch", "?")
                val os = System.getProperty("os.name", "?")
                val hint = when {
                    e is UnsatisfiedLinkError || e.cause is UnsatisfiedLinkError ->
                        "（当前平台 $os/$arch 缺少 FFmpeg 原生库；Linux ARM 需 linux-arm64，Windows ARM 尚无官方制品）"
                    else -> ""
                }
                System.err.println("[CustomBackground] 背景视频播放失败: $path$hint ($e)")
            } finally {
                try { grabber?.stop() } catch (_: Throwable) {}
                try { grabber?.release() } catch (_: Throwable) {}
            }
        }
    }

    frame?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
