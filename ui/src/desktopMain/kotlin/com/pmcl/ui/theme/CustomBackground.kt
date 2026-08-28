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
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
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

/** 解码宽度上限：更大的视频缩放解码，降低每帧像素搬运量 */
private const val MAX_DECODE_WIDTH = 1600

/**
 * 视频背景层：后台 IO 协程用 FFmpeg 逐帧解码，按视频帧率节流推送到 Compose 状态。
 *
 * 性能要点（视频壁纸卡顿修复）：
 * - 强制 swscale 输出 BGRA（[avutil.AV_PIX_FMT_BGRA]），与 Skia [ColorType.BGRA_8888]
 *   内存布局一致，像素可直接批量搬运，彻底移除 Java2DFrameConverter 与
 *   BufferedImage→ImageBitmap 转换（旧路径逐像素 getRGB 循环 + 每帧 2 次大内存分配）
 * - 双缓冲 skia [Bitmap] 交替刷新：IO 线程写后台位图、渲染线程读前台位图，
 *   installPixels 复用同一像素存储，全程零对象分配、无 GC 压力
 * - 帧状态只在 draw 阶段读取（Canvas 内），新帧仅触发重绘，不触发重组
 * - 播放到结尾自动 seek 回开头循环；组件销毁时协程取消并释放解码器
 */
@Composable
private fun VideoBackgroundLayer(path: String) {
    // 当前前台帧位图（首帧建立后仅在 draw 阶段读取；组合阶段不读，避免每帧重组）
    val frontFrame = remember(path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            var grabber: FFmpegFrameGrabber? = null
            try {
                grabber = FFmpegFrameGrabber(path).apply {
                    audioChannels = 0  // 静音：跳过音频流解码
                    // 强制输出 BGRA：与 Skia BGRA_8888 字节序一致，像素免转换直接搬运
                    pixelFormat = avutil.AV_PIX_FMT_BGRA
                    start()
                }
                // 高分辨率视频缩放解码，节省 CPU（swscale 在解码侧完成）
                if (grabber.imageWidth > MAX_DECODE_WIDTH) {
                    val w = MAX_DECODE_WIDTH
                    val h = (grabber.imageHeight.toLong() * w / grabber.imageWidth).toInt() and -2
                    grabber.stop(); grabber.release()
                    grabber = FFmpegFrameGrabber(path).apply {
                        audioChannels = 0
                        pixelFormat = avutil.AV_PIX_FMT_BGRA
                        imageWidth = w
                        imageHeight = h
                        start()
                    }
                }
                val frameDelayMs =
                    if (grabber.frameRate > 1.0) (1000.0 / grabber.frameRate).toLong() else 33L
                var consecutiveNulls = 0
                // 复用资源（仅分配一次）：双缓冲位图 + 各自的 Compose 包装 + 像素中转数组
                var info: ImageInfo? = null
                val bitmaps = arrayOfNulls<Bitmap>(2)
                val wrappers = arrayOfNulls<ImageBitmap>(2)
                var pixels: ByteArray? = null
                var front = 0
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
                    val buf = f.image[0] as? ByteBuffer ?: continue
                    // 首帧：确定尺寸并一次性分配全部复用资源
                    if (info == null) {
                        val w = f.imageWidth
                        val h = f.imageHeight
                        val stride = f.imageStride
                        if (w <= 0 || h <= 0 || stride < w * 4) continue
                        info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                        pixels = ByteArray(stride * h)
                        for (i in 0..1) {
                            val bm = Bitmap()
                            bitmaps[i] = bm
                            wrappers[i] = bm.asComposeImageBitmap()
                        }
                    }
                    val bm = bitmaps[front xor 1] ?: continue
                    val arr = pixels ?: continue
                    // 批量拷贝：帧缓冲（直接内存）→ 复用数组，一次 memcpy
                    val dup = buf.duplicate()
                    dup.position(0)
                    val copyBytes = minOf(dup.remaining(), arr.size)
                    if (copyBytes <= 0) continue
                    dup.get(arr, 0, copyBytes)
                    // 后台位图原地刷新像素并通知 Skia 世代变更（不新建任何位图对象）
                    bm.installPixels(info!!, arr, f.imageStride)
                    bm.notifyPixelsChanged()
                    front = front xor 1
                    // Snapshot 状态线程安全，可直接从 IO 线程赋值；
                    // 状态只在 draw 阶段读取 → 仅触发重绘
                    frontFrame.value = wrappers[front]
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

    // Canvas 无条件组合（视频加载失败时保持透明）；帧状态在 draw 阶段读取
    Canvas(Modifier.fillMaxSize()) {
        val bmp = frontFrame.value ?: return@Canvas
        val srcW = bmp.width.toFloat()
        val srcH = bmp.height.toFloat()
        if (srcW > 0f && srcH > 0f && size.width > 0f && size.height > 0f) {
            // ContentScale.Crop：等比放大填满窗口并居中
            val scale = maxOf(size.width / srcW, size.height / srcH)
            val dw = (srcW * scale).toInt().coerceAtLeast(1)
            val dh = (srcH * scale).toInt().coerceAtLeast(1)
            val dx = ((size.width - dw) / 2f).toInt()
            val dy = ((size.height - dh) / 2f).toInt()
            drawImage(
                image = bmp,
                dstOffset = IntOffset(dx, dy),
                dstSize = IntSize(dw, dh)
            )
        }
    }
}
