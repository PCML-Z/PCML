package com.pmcl.ui.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Image as SkiaImage

/**
 * 解码图片并按目标最大边长降采样，避免原图位图常驻内存。
 *
 * 1920×1080 封面解码后 ~8MB，显示仅 90dp；降采样到 256px 后仅 ~256KB。
 * 原图 SkiaImage 在方法结束后即可被 GC，常驻的只是小尺寸 Bitmap。
 *
 * @param bytes       图片字节数据
 * @param maxDimension 目标最大边长（px），≤0 表示不降采样
 * @return 降采样后的 ImageBitmap，解码失败返回 null
 */
fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int = 0): ImageBitmap? {
    val image = SkiaImage.makeFromEncoded(bytes) ?: return null
    if (maxDimension <= 0) return image.toComposeImageBitmap()
    val w = image.width
    val h = image.height
    if (w <= maxDimension && h <= maxDimension) return image.toComposeImageBitmap()
    // 计算目标尺寸（保持宽高比）
    val ratio = maxDimension.toFloat() / maxOf(w, h)
    val targetW = (w * ratio).toInt().coerceAtLeast(1)
    val targetH = (h * ratio).toInt().coerceAtLeast(1)
    // 创建小尺寸 Bitmap，用 Canvas 绘制缩放后的图像
    val bitmap = Bitmap()
    bitmap.allocN32Pixels(targetW, targetH, false)
    val canvas = Canvas(bitmap)
    canvas.drawImageRect(
        image,
        Rect.makeWH(w.toFloat(), h.toFloat()),
        Rect.makeWH(targetW.toFloat(), targetH.toFloat()),
        null
    )
    return SkiaImage.makeFromBitmap(bitmap).toComposeImageBitmap()
}
