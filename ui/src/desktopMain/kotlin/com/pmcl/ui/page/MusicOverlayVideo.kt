package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.pmcl.core.util.SsrfChecker
import com.pmcl.music.player.PlaybackState
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
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.nio.ByteBuffer
import kotlin.math.abs

private const val OVERLAY_VIDEO_MAX_WIDTH = 640

/**
 * 音乐悬浮窗的静音视频层。音频仍由 MusicPlayer 单独播放，这里只解码画面，
 * 并在切歌、拖动进度或网络抖动后按播放器时间重新同步。
 */
@Composable
internal fun MusicOverlayVideo(
    url: String,
    headers: Map<String, String>,
    playback: PlaybackState,
    currentMs: Long,
    modifier: Modifier = Modifier
) {
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val latestPlayback by rememberUpdatedState(playback)
    val latestCurrentMs by rememberUpdatedState(currentMs)

    LaunchedEffect(url, headers) {
        frame = null
        if (url.isBlank()) return@LaunchedEffect
        if ((url.startsWith("http://") || url.startsWith("https://"))
            && SsrfChecker.validate(url) != null) {
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            var grabber: FFmpegFrameGrabber? = null
            try {
                grabber = createVideoGrabber(url, headers)
                grabber.start()
                if (grabber.imageWidth > OVERLAY_VIDEO_MAX_WIDTH) {
                    val width = OVERLAY_VIDEO_MAX_WIDTH
                    val height = (grabber.imageHeight.toLong() * width / grabber.imageWidth)
                        .toInt().coerceAtLeast(2) and -2
                    grabber.stop()
                    grabber.release()
                    grabber = createVideoGrabber(url, headers).apply {
                        imageWidth = width
                        imageHeight = height
                        start()
                    }
                }

                val initialMs = latestCurrentMs.coerceAtLeast(0L)
                if (initialMs > 0L) runCatching { grabber.setTimestamp(initialMs * 1000L) }
                val delayMs = if (grabber.frameRate > 1.0) {
                    (1000.0 / grabber.frameRate).toLong().coerceIn(16L, 100L)
                } else {
                    33L
                }

                var info: ImageInfo? = null
                val bitmaps = arrayOfNulls<Bitmap>(2)
                val wrappers = arrayOfNulls<ImageBitmap>(2)
                var pixels: ByteArray? = null
                var front = 0
                var lastPausedTarget = Long.MIN_VALUE
                var nullFrames = 0

                while (isActive) {
                    val state = latestPlayback
                    val targetMs = latestCurrentMs.coerceAtLeast(0L)
                    if (state != PlaybackState.PLAYING) {
                        if (lastPausedTarget == Long.MIN_VALUE || abs(targetMs - lastPausedTarget) >= 250L) {
                            runCatching { grabber.setTimestamp(targetMs * 1000L) }
                            lastPausedTarget = targetMs
                        } else {
                            delay(80)
                            continue
                        }
                    } else {
                        lastPausedTarget = Long.MIN_VALUE
                        val videoMs = grabber.timestamp / 1000L
                        if (targetMs > 0L && abs(videoMs - targetMs) > 1200L) {
                            runCatching { grabber.setTimestamp(targetMs * 1000L) }
                        }
                    }

                    val decoded = grabber.grabImage()
                    if (decoded == null) {
                        nullFrames++
                        if (nullFrames >= 3) delay(250) else delay(delayMs)
                        continue
                    }
                    nullFrames = 0
                    val buffer = decoded.image?.firstOrNull() as? ByteBuffer ?: continue
                    if (info == null) {
                        val width = decoded.imageWidth
                        val height = decoded.imageHeight
                        val stride = decoded.imageStride
                        if (width <= 0 || height <= 0 || stride < width * 4) continue
                        info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                        pixels = ByteArray(stride * height)
                        for (i in 0..1) {
                            bitmaps[i] = Bitmap()
                            wrappers[i] = bitmaps[i]!!.asComposeImageBitmap()
                        }
                    }
                    val bitmap = bitmaps[front xor 1] ?: continue
                    val bytes = pixels ?: continue
                    val copy = buffer.duplicate().apply { position(0) }
                    val length = minOf(copy.remaining(), bytes.size)
                    if (length <= 0) continue
                    copy.get(bytes, 0, length)
                    bitmap.installPixels(info!!, bytes, decoded.imageStride)
                    bitmap.notifyPixelsChanged()
                    front = front xor 1
                    frame = wrappers[front]
                    delay(delayMs)
                }
            } catch (e: Throwable) {
                System.err.println("[MusicOverlay] 视频预览失败: ${e.message}")
            } finally {
                runCatching { grabber?.stop() }
                runCatching { grabber?.release() }
            }
        }
    }

    frame?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

private fun createVideoGrabber(url: String, headers: Map<String, String>): FFmpegFrameGrabber =
    FFmpegFrameGrabber(url).apply {
        audioChannels = 0
        pixelFormat = avutil.AV_PIX_FMT_BGRA
        if (headers.isNotEmpty()) {
            setOption("headers", buildString {
                headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            })
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            setOption("http_max_redirects", "0")
            setOption("protocol_whitelist", "file,http,https,tcp,tls,crypto")
            setOption("rw_timeout", "20000000")
            setOption("timeout", "20000000")
            setOption("reconnect", "1")
            setOption("reconnect_streamed", "1")
            setOption("reconnect_on_network_error", "1")
            setOption("reconnect_on_http_error", "5xx")
            setOption("reconnect_delay_max", "3")
        }
    }
