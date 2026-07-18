package com.pmcl.ui.page

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import javax.imageio.ImageIO

/**
 * desktopMain 实现：用 ImageIO 读取图片文件，转换为 Compose ImageBitmap。
 */
internal actual fun loadPathImageBitmap(path: String): ImageBitmap? {
    return try {
        val image = ImageIO.read(File(path)) ?: return null
        image.toComposeImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
