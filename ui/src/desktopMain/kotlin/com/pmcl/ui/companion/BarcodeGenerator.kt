package com.pmcl.ui.companion

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.EnumMap

/**
 * 条码生成工具：用 ZXing 生成二维码（QR Code）和一维码（Code 128）。
 * 配对码内容含字母和数字及分隔符，Code 128 可完整编码。
 */
object BarcodeGenerator {

    /** 生成二维码 BufferedImage */
    fun generateQrCode(content: String, size: Int = 240): BufferedImage {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val matrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        return toBufferedImage(matrix)
    }

    /** 生成一维码（Code 128）BufferedImage */
    fun generateBarcode(content: String, width: Int = 320, height: Int = 80): BufferedImage {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 1
        val matrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.CODE_128, width, height, hints)
        return toBufferedImage(matrix)
    }

    private fun toBufferedImage(matrix: BitMatrix): BufferedImage {
        val width = matrix.width
        val height = matrix.height
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) {
            for (y in 0 until height) {
                image.setRGB(x, y, if (matrix.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        return image
    }
}
