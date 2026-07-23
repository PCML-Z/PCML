package com.pmcl.ui.page

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import com.pmcl.ui.util.decodeSampledBitmap

/**
 * desktopMain 实现：用 ImageIO 读取图片文件，按屏幕分辨率降采样后转换为 Compose ImageBitmap。
 * 4K 截图（3840×2160）全尺寸解码后 ~33MB，降采样到 1920px 后仅 ~8MB。
 */
internal actual fun loadPathImageBitmap(path: String): ImageBitmap? {
    return try {
        val file = File(path)
        // 先用 ImageIO 读取图片尺寸
        val inputStream = ImageIO.createImageInputStream(file)
        if (inputStream == null) {
            // ImageIO 无法处理该格式，回退到字节解码
            return decodeSampledBitmap(file.readBytes(), 1920)
        }
        try {
            val readers = ImageIO.getImageReaders(inputStream)
            if (!readers.hasNext()) {
                return decodeSampledBitmap(file.readBytes(), 1920)
            }
            val reader = readers.next()
            reader.input = inputStream
            val w = reader.getWidth(0)
            val h = reader.getHeight(0)
            // 如果图片不大，直接解码
            if (w <= 1920 && h <= 1920) {
                val image = reader.read(0)
                return image?.toComposeImageBitmap()
            }
            // 计算采样率（2 的幂）
            var sample = 1
            while (w / (sample * 2) >= 1920 || h / (sample * 2) >= 1920) {
                sample *= 2
            }
            val param = reader.getDefaultReadParam()
            param.setSourceSubsampling(sample, sample, 0, 0)
            val image = reader.read(0, param)
            reader.dispose()
            image?.toComposeImageBitmap()
        } finally {
            inputStream.close()
        }
    } catch (_: Throwable) {
        null
    }
}
