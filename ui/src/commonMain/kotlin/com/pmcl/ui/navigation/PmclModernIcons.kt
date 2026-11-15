package com.pmcl.ui.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 简洁一套：同一线宽，小尺寸仍靠剪影认得出的符号用实心。
 */
internal object PmclModernIcons {
    private const val STROKE = 1.7f

    val Launch: ImageVector = icon("Launch") {
        stroke { circle(12f, 12f, 7.5f) }
        fill {
            moveTo(10.15f, 8.7f)
            lineTo(15.55f, 12f)
            lineTo(10.15f, 15.3f)
            close()
        }
    }

    val News: ImageVector = icon("News") {
        stroke {
            roundRect(4.5f, 3.4f, 19.5f, 20.6f, 2.2f)
            moveTo(8f, 11.2f); lineTo(16f, 11.2f)
            moveTo(8f, 14.4f); lineTo(16f, 14.4f)
            moveTo(8f, 17.4f); lineTo(13.2f, 17.4f)
        }
        fill { roundRect(8f, 6.2f, 16f, 8.4f, 0.8f) }
    }

    val Tips: ImageVector = icon("Tips") {
        stroke {
            circle(12f, 9.2f, 4.4f)
            moveTo(9.8f, 13.4f); lineTo(14.2f, 13.4f)
            moveTo(10.2f, 15.6f); lineTo(13.8f, 15.6f)
            moveTo(10.7f, 17.8f); lineTo(13.3f, 17.8f)
        }
    }

    val Multiplayer: ImageVector = icon("Multiplayer") {
        stroke {
            circle(8.4f, 12f, 4.2f)
            circle(15.6f, 12f, 4.2f)
        }
    }

    val Servers: ImageVector = icon("Servers") {
        stroke {
            roundRect(4f, 3.2f, 20f, 8.2f, 1.7f)
            roundRect(4f, 9.5f, 20f, 14.5f, 1.7f)
            roundRect(4f, 15.8f, 20f, 20.8f, 1.7f)
        }
        fill {
            circle(7.1f, 5.7f, 0.85f)
            circle(7.1f, 12f, 0.85f)
            circle(7.1f, 18.3f, 0.85f)
        }
    }

    val Friends: ImageVector = icon("Friends") {
        stroke {
            circle(9f, 8.2f, 2.7f)
            moveTo(4.2f, 19.4f)
            curveTo(4.6f, 15.2f, 6.4f, 13.4f, 9f, 13.4f)
            curveTo(11.6f, 13.4f, 13.4f, 15.2f, 13.8f, 19.4f)
            circle(16.2f, 8.8f, 2.15f)
            moveTo(13.6f, 18.6f)
            curveTo(14f, 15.6f, 15.2f, 14.2f, 16.4f, 14.2f)
            curveTo(17.8f, 14.2f, 19.2f, 15.4f, 19.8f, 18.6f)
        }
    }

    val Download: ImageVector = icon("Download") {
        stroke {
            moveTo(12f, 3.4f); lineTo(12f, 13.6f)
            moveTo(7.4f, 9.4f); lineTo(12f, 14.2f); lineTo(16.6f, 9.4f)
            moveTo(5f, 16.2f)
            lineTo(5f, 18.4f)
            arcTo(2f, 2f, 0f, false, false, 7f, 20.4f)
            lineTo(17f, 20.4f)
            arcTo(2f, 2f, 0f, false, false, 19f, 18.4f)
            lineTo(19f, 16.2f)
        }
    }

    val Content: ImageVector = icon("Content") {
        stroke {
            roundRect(4.2f, 6.4f, 15.4f, 17.6f, 2f)
            roundRect(8.4f, 8.8f, 19.8f, 20f, 2f)
        }
    }

    val Saves: ImageVector = icon("Saves") {
        stroke {
            roundRect(4.2f, 8.6f, 19.8f, 19.8f, 2f)
            moveTo(4.8f, 8.8f)
            lineTo(4.8f, 6.6f)
            lineTo(11.2f, 6.6f)
            lineTo(12.8f, 8.8f)
        }
    }

    val Statistics: ImageVector = icon("Statistics") {
        fill {
            roundRect(4.2f, 12.4f, 8.4f, 19.4f, 1.2f)
            roundRect(9.9f, 6.2f, 14.1f, 19.4f, 1.2f)
            roundRect(15.6f, 9.4f, 19.8f, 19.4f, 1.2f)
        }
        stroke { moveTo(3.6f, 20.2f); lineTo(20.4f, 20.2f) }
    }

    val Accounts: ImageVector = icon("Accounts") {
        stroke {
            circle(12f, 8f, 3f)
            moveTo(5.4f, 19.6f)
            curveTo(6f, 15.2f, 8.4f, 13.2f, 12f, 13.2f)
            curveTo(15.6f, 13.2f, 18f, 15.2f, 18.6f, 19.6f)
        }
    }

    val Settings: ImageVector = icon("Settings") {
        stroke {
            moveTo(4f, 8f); lineTo(20f, 8f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            moveTo(4f, 16f); lineTo(20f, 16f)
        }
        fill {
            circle(9f, 8f, 2.05f)
            circle(15f, 12f, 2.05f)
            circle(8f, 16f, 2.05f)
        }
    }

    val Terminal: ImageVector = icon("Terminal") {
        stroke {
            roundRect(3.4f, 4.2f, 20.6f, 19.8f, 2.4f)
            moveTo(7.2f, 9.6f); lineTo(10.6f, 12f); lineTo(7.2f, 14.4f)
            moveTo(12.2f, 14.6f); lineTo(16.6f, 14.6f)
        }
    }

    val Plugins: ImageVector = icon("Plugins") {
        stroke {
            roundRect(7f, 8.2f, 17f, 19.2f, 2f)
            moveTo(10f, 8.2f); lineTo(10f, 4.8f)
            moveTo(14f, 8.2f); lineTo(14f, 4.8f)
        }
    }

    val Ecxp: ImageVector = icon("Ecxp") {
        stroke {
            roundRect(3.6f, 3.6f, 10.4f, 10.4f, 1.8f)
            roundRect(13.6f, 3.6f, 20.4f, 10.4f, 1.8f)
            roundRect(3.6f, 13.6f, 10.4f, 20.4f, 1.8f)
        }
        fill { roundRect(13.6f, 13.6f, 20.4f, 20.4f, 1.8f) }
    }

    val Kernel: ImageVector = icon("Kernel") {
        stroke {
            roundRect(6.8f, 6.8f, 17.2f, 17.2f, 2.2f)
            moveTo(12f, 9.3f); lineTo(14.7f, 12f); lineTo(12f, 14.7f); lineTo(9.3f, 12f); close()
            moveTo(4f, 9.4f); lineTo(6.8f, 9.4f)
            moveTo(4f, 14.6f); lineTo(6.8f, 14.6f)
            moveTo(17.2f, 9.4f); lineTo(20f, 9.4f)
            moveTo(17.2f, 14.6f); lineTo(20f, 14.6f)
            moveTo(9.4f, 4f); lineTo(9.4f, 6.8f)
            moveTo(14.6f, 4f); lineTo(14.6f, 6.8f)
            moveTo(9.4f, 17.2f); lineTo(9.4f, 20f)
            moveTo(14.6f, 17.2f); lineTo(14.6f, 20f)
        }
    }

    val Instances: ImageVector = icon("Instances") {
        stroke {
            roundRect(3.6f, 3.6f, 10.4f, 10.4f, 1.8f)
            roundRect(13.6f, 3.6f, 20.4f, 10.4f, 1.8f)
            roundRect(3.6f, 13.6f, 10.4f, 20.4f, 1.8f)
            roundRect(13.6f, 13.6f, 20.4f, 20.4f, 1.8f)
        }
    }

    val Nbt: ImageVector = icon("Nbt") {
        stroke {
            moveTo(8.4f, 8.6f); lineTo(15.2f, 9.2f)
            moveTo(8.8f, 9.6f); lineTo(11.4f, 15f)
            moveTo(15.2f, 10.4f); lineTo(13f, 15.2f)
        }
        fill {
            circle(7.2f, 7.4f, 2.05f)
            circle(16.8f, 8.2f, 2.05f)
            circle(12f, 16.8f, 2.05f)
        }
    }

    val Music: ImageVector = icon("Music") {
        fill {
            circle(8.6f, 17.2f, 2.55f)
            roundRect(10.7f, 4f, 13.1f, 16.8f, 0.7f)
            moveTo(13.1f, 4.2f)
            curveTo(17.2f, 5.4f, 18.8f, 7.4f, 18.8f, 10f)
            lineTo(16.2f, 9.2f)
            curveTo(16.2f, 7.6f, 15.4f, 6.6f, 13.1f, 5.8f)
            close()
        }
    }

    val Palette: ImageVector = icon("Palette") {
        stroke { circle(12f, 12f, 7.6f) }
        fill {
            circle(12f, 7.2f, 1.25f)
            circle(16.4f, 13.6f, 1.25f)
            circle(8.2f, 14.2f, 1.25f)
        }
    }

    val Bolt: ImageVector = icon("Bolt") {
        fill {
            moveTo(13.2f, 2.4f)
            lineTo(5.8f, 13.2f)
            lineTo(11f, 13.2f)
            lineTo(9.6f, 21.6f)
            lineTo(18.4f, 9.8f)
            lineTo(13f, 9.8f)
            close()
        }
    }

    val Wrench: ImageVector = icon("Wrench") {
        stroke {
            circle(8f, 8f, 3.5f)
            moveTo(10.5f, 10.5f); lineTo(18.4f, 18.4f)
            moveTo(16.6f, 16.8f); lineTo(19.2f, 18.2f); lineTo(17.8f, 20.2f)
        }
    }

    val Speed: ImageVector = icon("Speed") {
        stroke {
            moveTo(4.8f, 16.8f)
            arcTo(8f, 8f, 0f, false, true, 19.2f, 16.8f)
            moveTo(12f, 15.6f); lineTo(16.2f, 8.6f)
        }
        fill { circle(12f, 16.2f, 1.45f) }
    }

    val Update: ImageVector = icon("Update") {
        stroke { circle(12f, 13f, 6.4f) }
        fill {
            moveTo(12f, 3f)
            lineTo(15.3f, 7.2f)
            lineTo(8.7f, 7.2f)
            close()
        }
    }

    val Shield: ImageVector = icon("Shield") {
        stroke {
            moveTo(12f, 3.2f)
            lineTo(19.2f, 6f)
            lineTo(19.2f, 12f)
            curveTo(19.2f, 16.2f, 16.2f, 18.8f, 12f, 20.8f)
            curveTo(7.8f, 18.8f, 4.8f, 16.2f, 4.8f, 12f)
            lineTo(4.8f, 6f)
            close()
            moveTo(8.7f, 12.1f); lineTo(11f, 14.4f); lineTo(15.5f, 9.4f)
        }
    }

    val Article: ImageVector = icon("Article") {
        stroke {
            moveTo(6f, 3.4f)
            lineTo(14.2f, 3.4f)
            lineTo(18.6f, 7.8f)
            lineTo(18.6f, 20.6f)
            lineTo(6f, 20.6f)
            close()
            moveTo(14.2f, 3.6f); lineTo(14.2f, 7.8f); lineTo(18.4f, 7.8f)
            moveTo(8.4f, 12f); lineTo(16f, 12f)
            moveTo(8.4f, 15.2f); lineTo(16f, 15.2f)
        }
    }

    val Qr: ImageVector = icon("Qr") {
        finder(3.4f, 3.4f)
        finder(13.6f, 3.4f)
        finder(3.4f, 13.6f)
        fill {
            roundRect(14f, 14f, 16.6f, 16.6f, 0.4f)
            roundRect(17.8f, 14f, 20.4f, 16.6f, 0.4f)
            roundRect(14f, 17.8f, 16.6f, 20.4f, 0.4f)
            roundRect(17.8f, 17.8f, 20.4f, 20.4f, 0.4f)
        }
    }

    val Gavel: ImageVector = icon("Gavel") {
        stroke {
            moveTo(3.8f, 19.4f); lineTo(20.2f, 19.4f)
            moveTo(7.2f, 14.8f); lineTo(12.2f, 8.6f); lineTo(15.4f, 11.2f); lineTo(10.4f, 17.4f); close()
            moveTo(13.2f, 7.4f); lineTo(17.6f, 4.2f)
            moveTo(10.2f, 15.2f); lineTo(11.4f, 18.8f)
        }
    }

    val Modpack: ImageVector = icon("Modpack") {
        stroke {
            roundRect(4f, 7.6f, 20f, 20.2f, 2f)
            moveTo(4.4f, 12f); lineTo(19.6f, 12f)
            moveTo(12f, 7.6f); lineTo(12f, 12f)
        }
    }

    val Sun: ImageVector = icon("Sun") {
        stroke {
            circle(12f, 12f, 3.5f)
            moveTo(12f, 2.8f); lineTo(12f, 5.6f)
            moveTo(12f, 18.4f); lineTo(12f, 21.2f)
            moveTo(2.8f, 12f); lineTo(5.6f, 12f)
            moveTo(18.4f, 12f); lineTo(21.2f, 12f)
            moveTo(5.4f, 5.4f); lineTo(7.4f, 7.4f)
            moveTo(16.6f, 16.6f); lineTo(18.6f, 18.6f)
            moveTo(18.6f, 5.4f); lineTo(16.6f, 7.4f)
            moveTo(7.4f, 16.6f); lineTo(5.4f, 18.6f)
        }
    }

    val Grid: ImageVector = icon("Grid") {
        stroke {
            roundRect(3.6f, 3.6f, 20.4f, 20.4f, 2.2f)
            moveTo(12f, 4.2f); lineTo(12f, 19.8f)
            moveTo(4.2f, 12f); lineTo(19.8f, 12f)
        }
    }

    val Dataset: ImageVector = icon("Dataset") {
        stroke {
            moveTo(4f, 8f); lineTo(12f, 4.6f); lineTo(20f, 8f); lineTo(12f, 11.4f); close()
            moveTo(4f, 12.2f); lineTo(12f, 15.6f); lineTo(20f, 12.2f)
            moveTo(4f, 16.2f); lineTo(12f, 19.6f); lineTo(20f, 16.2f)
        }
    }

    val Edit: ImageVector = icon("Edit") {
        stroke {
            moveTo(14.4f, 3.8f)
            lineTo(20.2f, 9.6f)
            lineTo(9.4f, 20.4f)
            lineTo(3.6f, 20.4f)
            lineTo(3.6f, 14.6f)
            close()
        }
    }

    val Browser: ImageVector = icon("Browser") {
        stroke {
            roundRect(3.4f, 4f, 20.6f, 20f, 2.3f)
            moveTo(3.8f, 8.2f); lineTo(20.2f, 8.2f)
            circle(12f, 14.2f, 3.1f)
        }
        fill {
            circle(6.2f, 6.1f, 0.7f)
            circle(8.6f, 6.1f, 0.7f)
        }
    }

    val Key: ImageVector = icon("Key") {
        stroke {
            circle(8.2f, 9.2f, 3.6f)
            moveTo(11.4f, 11.2f); lineTo(19.8f, 11.2f)
            moveTo(16.8f, 11.2f); lineTo(16.8f, 14.2f)
            moveTo(14f, 11.2f); lineTo(14f, 13.4f)
        }
    }

    val Globe: ImageVector = icon("Globe") {
        stroke {
            circle(12f, 12f, 7.6f)
            moveTo(12f, 4.4f)
            curveTo(9.2f, 8f, 9.2f, 16f, 12f, 19.6f)
            moveTo(12f, 4.4f)
            curveTo(14.8f, 8f, 14.8f, 16f, 12f, 19.6f)
            moveTo(4.6f, 12f); lineTo(19.4f, 12f)
        }
    }

    val History: ImageVector = icon("History") {
        stroke {
            circle(12f, 12f, 7.5f)
            moveTo(12f, 7.6f); lineTo(12f, 12.2f); lineTo(15.4f, 14.2f)
        }
    }

    val Image: ImageVector = icon("Image") {
        stroke {
            roundRect(3.4f, 4.4f, 20.6f, 19.6f, 2.2f)
            moveTo(4.2f, 16.2f)
            lineTo(8.6f, 12.2f)
            lineTo(12.2f, 15.2f)
            lineTo(15.6f, 12.2f)
            lineTo(19.8f, 16f)
        }
        fill { circle(8.2f, 8.4f, 1.25f) }
    }

    val Video: ImageVector = icon("Video") {
        stroke { roundRect(2.8f, 6.4f, 15.2f, 17.6f, 2f) }
        fill {
            moveTo(15.2f, 9.2f)
            lineTo(21.2f, 6.4f)
            lineTo(21.2f, 17.6f)
            lineTo(15.2f, 14.8f)
            close()
        }
    }

    val Plus: ImageVector = icon("Plus") {
        stroke {
            circle(12f, 12f, 7.5f)
            moveTo(12f, 8f); lineTo(12f, 16f)
            moveTo(8f, 12f); lineTo(16f, 12f)
        }
    }

    val Error: ImageVector = icon("Error") {
        stroke {
            circle(12f, 12f, 7.5f)
            moveTo(12f, 7.4f); lineTo(12f, 13.1f)
        }
        fill { circle(12f, 16.2f, 1.05f) }
    }

    val Star: ImageVector = icon("Star") {
        fill { star(12f, 12.2f, 8.2f, 3.4f) }
    }

    val Playlist: ImageVector = icon("Playlist") {
        stroke {
            moveTo(3.6f, 7f); lineTo(13.4f, 7f)
            moveTo(3.6f, 12f); lineTo(13.4f, 12f)
            moveTo(3.6f, 17f); lineTo(10.2f, 17f)
        }
        fill {
            circle(16.2f, 16.6f, 2.15f)
            roundRect(17.8f, 5.6f, 19.6f, 16.2f, 0.55f)
        }
    }

    val Mail: ImageVector = icon("Mail") {
        stroke {
            roundRect(3.4f, 5.8f, 20.6f, 18.2f, 2f)
            moveTo(4.2f, 7.2f); lineTo(12f, 12.4f); lineTo(19.8f, 7.2f)
        }
    }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    private fun ImageVector.Builder.stroke(block: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }

    private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    private fun ImageVector.Builder.finder(x: Float, y: Float) {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd
        ) {
            roundRect(x, y, x + 7f, y + 7f, 1.4f)
            roundRect(x + 2f, y + 2f, x + 5f, y + 5f, 0.5f)
        }
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx, cy - r)
        arcTo(r, r, 0f, false, true, cx, cy + r)
        arcTo(r, r, 0f, false, true, cx, cy - r)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        val rx = rad.coerceAtMost((r - l) / 2f).coerceAtLeast(0f)
        val ry = rad.coerceAtMost((b - t) / 2f).coerceAtLeast(0f)
        moveTo(l + rx, t)
        lineTo(r - rx, t)
        arcTo(rx, ry, 0f, false, true, r, t + ry)
        lineTo(r, b - ry)
        arcTo(rx, ry, 0f, false, true, r - rx, b)
        lineTo(l + rx, b)
        arcTo(rx, ry, 0f, false, true, l, b - ry)
        lineTo(l, t + ry)
        arcTo(rx, ry, 0f, false, true, l + rx, t)
        close()
    }

    private fun PathBuilder.star(cx: Float, cy: Float, outer: Float, inner: Float) {
        for (i in 0 until 10) {
            val ang = Math.toRadians(-90.0 + i * 36.0)
            val rad = if (i % 2 == 0) outer else inner
            val x = cx + cos(ang).toFloat() * rad
            val y = cy + sin(ang).toFloat() * rad
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
