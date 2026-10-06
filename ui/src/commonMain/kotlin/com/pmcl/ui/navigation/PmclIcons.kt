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
 * 导航用的自制图标。实心形状后面垫一层错位的半透明副本，染色后仍能看出厚度。
 * 需要镂空时，用奇偶填充把孔和外形画在同一条路径里。
 */
object PmclIcons {
    val Launch: ImageVector = icon("Launch") {
        shade { circle(12.5f, 12.6f, 8.6f) }
        ink(PathFillType.EvenOdd) {
            circle(12f, 12f, 9f)
            circle(12f, 12f, 6.5f)
        }
        ink {
            moveTo(10.2f, 8.4f)
            lineTo(16.1f, 12f)
            lineTo(10.2f, 15.6f)
            close()
        }
    }

    val News: ImageVector = icon("News") {
        shade { roundRect(5.4f, 4.2f, 18.8f, 20.2f, 2.4f) }
        ink(PathFillType.EvenOdd) {
            roundRect(4.6f, 3.2f, 19.2f, 20.4f, 2.6f)
            roundRect(7.6f, 7f, 16.2f, 8.6f, 0.7f)
            roundRect(7.6f, 10.6f, 16.2f, 12f, 0.6f)
            roundRect(7.6f, 14.2f, 13.4f, 15.6f, 0.6f)
        }
    }

    val Tips: ImageVector = icon("Tips") {
        shade { circle(12.4f, 10.2f, 5.8f) }
        ink(PathFillType.EvenOdd) {
            circle(12f, 9.4f, 5.5f)
            circle(10.3f, 8.1f, 1.45f)
        }
        ink {
            roundRect(9.5f, 14.6f, 14.5f, 16.4f, 0.8f)
            roundRect(10.1f, 17f, 13.9f, 18.6f, 0.7f)
            roundRect(10.5f, 19.1f, 13.5f, 20.6f, 0.6f)
        }
    }

    val Multiplayer: ImageVector = icon("Multiplayer") {
        shade { circle(7.6f, 12.5f, 4f) }
        ink { circle(7.2f, 12f, 3.45f) }
        ink(alpha = 0.42f) { circle(16.7f, 12f, 3.45f) }
        ink { roundRect(8.8f, 10.7f, 15.2f, 13.3f, 1.2f) }
    }

    val Servers: ImageVector = icon("Servers") {
        shade { roundRect(4.8f, 4.6f, 19.4f, 10.4f, 2f) }
        ink(PathFillType.EvenOdd, alpha = 0.45f) {
            roundRect(4f, 3.2f, 20f, 9.2f, 2f)
            circle(7.2f, 6.2f, 1f)
        }
        ink(PathFillType.EvenOdd) {
            roundRect(4f, 10.2f, 20f, 16.2f, 2f)
            circle(7.2f, 13.2f, 1.05f)
        }
        ink(PathFillType.EvenOdd, alpha = 0.55f) {
            roundRect(4f, 17.2f, 20f, 21f, 1.6f)
            circle(7.2f, 19.1f, 0.8f)
        }
    }

    val Friends: ImageVector = icon("Friends") {
        ink(alpha = 0.4f) {
            circle(15.8f, 8.2f, 2.65f)
            moveTo(12.4f, 18.6f)
            curveTo(12.6f, 15.2f, 14.2f, 13.6f, 16.6f, 13.6f)
            curveTo(18.8f, 13.6f, 20f, 15f, 20.4f, 18.6f)
            close()
        }
        shade {
            circle(9.6f, 9.4f, 3.4f)
        }
        ink {
            circle(9.2f, 9f, 3.15f)
            moveTo(3.6f, 20.2f)
            curveTo(4f, 15.5f, 6.2f, 13.5f, 9.2f, 13.5f)
            curveTo(12.2f, 13.5f, 14.4f, 15.5f, 14.8f, 20.2f)
            close()
        }
    }

    val Download: ImageVector = icon("Download") {
        shade {
            roundRect(11.1f, 3.4f, 13.7f, 11f, 1f)
            moveTo(7.2f, 10.2f)
            lineTo(12.4f, 15.6f)
            lineTo(17.6f, 10.2f)
            close()
        }
        ink {
            roundRect(10.7f, 2.6f, 13.3f, 10.2f, 1.05f)
            moveTo(6.8f, 9.4f)
            lineTo(12f, 14.8f)
            lineTo(17.2f, 9.4f)
            close()
            moveTo(4.2f, 14.6f)
            lineTo(4.2f, 18.2f)
            arcTo(2.3f, 2.3f, 0f, false, false, 6.5f, 20.5f)
            lineTo(17.5f, 20.5f)
            arcTo(2.3f, 2.3f, 0f, false, false, 19.8f, 18.2f)
            lineTo(19.8f, 14.6f)
            lineTo(16.2f, 14.6f)
            lineTo(16.2f, 17.2f)
            lineTo(7.8f, 17.2f)
            lineTo(7.8f, 14.6f)
            close()
        }
    }

    val Content: ImageVector = icon("Content") {
        shade { roundRect(4.2f, 5.6f, 16f, 17.4f, 2.4f) }
        ink(alpha = 0.42f) { roundRect(6.2f, 7f, 18.2f, 19f, 2.4f) }
        ink(PathFillType.EvenOdd) {
            roundRect(8.2f, 8.4f, 20.4f, 20.6f, 2.5f)
            moveTo(14.3f, 11.4f)
            lineTo(15.15f, 13.2f)
            lineTo(17.1f, 13.4f)
            lineTo(15.5f, 14.7f)
            lineTo(16.05f, 16.6f)
            lineTo(14.3f, 15.5f)
            lineTo(12.55f, 16.6f)
            lineTo(13.1f, 14.7f)
            lineTo(11.5f, 13.4f)
            lineTo(13.45f, 13.2f)
            close()
        }
    }

    val Saves: ImageVector = icon("Saves") {
        shade { roundRect(4.6f, 9.6f, 19.4f, 19.8f, 2.2f) }
        ink(alpha = 0.45f) { roundRect(3.6f, 5.2f, 20.4f, 11.2f, 2.2f) }
        ink { roundRect(4f, 9.4f, 20f, 20.2f, 2.4f) }
        ink { roundRect(10.3f, 12.2f, 13.7f, 15.5f, 1f) }
    }

    val Statistics: ImageVector = icon("Statistics") {
        shade { roundRect(10.4f, 5.6f, 14.4f, 19.4f, 1.5f) }
        ink { roundRect(4.2f, 12.2f, 8.2f, 19.6f, 1.45f) }
        ink { roundRect(10f, 4.8f, 14f, 19.6f, 1.5f) }
        ink(alpha = 0.48f) { roundRect(15.8f, 8.6f, 19.8f, 19.6f, 1.45f) }
        ink(alpha = 0.32f) { roundRect(3.6f, 20.2f, 20.4f, 21.5f, 0.55f) }
    }

    val Accounts: ImageVector = icon("Accounts") {
        shade { circle(12.5f, 12.6f, 8.4f) }
        ink { circle(12f, 8.1f, 3.3f) }
        ink {
            moveTo(5.5f, 19.8f)
            curveTo(6.1f, 15.3f, 8.5f, 13.3f, 12f, 13.3f)
            curveTo(15.5f, 13.3f, 17.9f, 15.3f, 18.5f, 19.8f)
            close()
        }
    }

    val Settings: ImageVector = icon("Settings") {
        shade { gear(12.5f, 12.6f, 8, 8.6f, 6.6f) }
        ink(PathFillType.EvenOdd) {
            gear(12f, 12f, 8, 9.2f, 7.05f)
            circle(12f, 12f, 2.65f)
        }
    }

    val Terminal: ImageVector = icon("Terminal") {
        shade { roundRect(3.8f, 4.8f, 20.2f, 19.8f, 3f) }
        ink(PathFillType.EvenOdd) {
            roundRect(3.2f, 4f, 20.8f, 20f, 3.1f)
            roundRect(5.5f, 7.7f, 18.5f, 17.5f, 1.5f)
        }
        ink {
            moveTo(7.4f, 10.6f)
            lineTo(10.6f, 12.6f)
            lineTo(7.4f, 14.6f)
            lineTo(8.5f, 16f)
            lineTo(12.8f, 12.6f)
            lineTo(8.5f, 9.2f)
            close()
            roundRect(14f, 13.8f, 17.1f, 15.5f, 0.45f)
        }
    }

    val Plugins: ImageVector = icon("Plugins") {
        shade {
            roundRect(6.4f, 8f, 17.2f, 18.8f, 2f)
            circle(11.8f, 7.6f, 2.4f)
        }
        ink(PathFillType.EvenOdd) {
            roundRect(5.6f, 7.4f, 16.6f, 18.6f, 2.1f)
            circle(16.6f, 12.8f, 2.05f)
        }
        ink { circle(11f, 6.5f, 2.5f) }
    }

    val Instances: ImageVector = icon("Instances") {
        shade { roundRect(13.8f, 3.8f, 21.2f, 11.2f, 2.1f) }
        ink { roundRect(3.2f, 3.2f, 10.6f, 10.6f, 2.15f) }
        ink(alpha = 0.42f) { roundRect(13.4f, 3.2f, 20.8f, 10.6f, 2.15f) }
        ink(alpha = 0.42f) { roundRect(3.2f, 13.4f, 10.6f, 20.8f, 2.15f) }
        ink { roundRect(13.4f, 13.4f, 20.8f, 20.8f, 2.15f) }
    }

    val Nbt: ImageVector = icon("Nbt") {
        shade { circle(12.4f, 17.2f, 3.1f) }
        stroke(1.9f) {
            moveTo(8.4f, 8.2f)
            lineTo(15.2f, 8.4f)
            moveTo(8.2f, 8.6f)
            lineTo(10.8f, 14.8f)
            moveTo(15.4f, 9.4f)
            lineTo(13.4f, 14.6f)
        }
        ink { circle(6.6f, 6.8f, 2.5f) }
        ink(alpha = 0.45f) { circle(17.2f, 7.6f, 2.3f) }
        ink { circle(12f, 17.2f, 2.75f) }
    }

    val Music: ImageVector = icon("Music") {
        shade { circle(9.8f, 17.6f, 3.2f) }
        ink { circle(9.2f, 17.2f, 3.1f) }
        ink { roundRect(12.1f, 3.5f, 14.5f, 16.2f, 0.85f) }
        ink {
            moveTo(14.5f, 3.6f)
            curveTo(18f, 4.8f, 19.6f, 6.6f, 19.6f, 9f)
            lineTo(17.1f, 8.3f)
            curveTo(17.1f, 6.8f, 16.2f, 5.8f, 14.5f, 5.2f)
            close()
        }
    }

    val Palette: ImageVector = icon("Palette") {
        shade { circle(12.5f, 12.5f, 7.6f) }
        ink(PathFillType.EvenOdd) {
            circle(12f, 12f, 8.3f)
            circle(12f, 12f, 3f)
        }
        ink { circle(12f, 5.5f, 1.3f) }
        ink(alpha = 0.5f) { circle(17.5f, 9.1f, 1.3f) }
        ink(alpha = 0.72f) { circle(16.3f, 15.6f, 1.3f) }
        ink(alpha = 0.5f) { circle(7.5f, 15.3f, 1.3f) }
    }

    val Bolt: ImageVector = icon("Bolt") {
        shade {
            moveTo(14f, 3.4f)
            lineTo(7f, 13.2f)
            lineTo(11.6f, 13.2f)
            lineTo(10.2f, 21f)
            lineTo(18.2f, 10.2f)
            lineTo(13.4f, 10.2f)
            close()
        }
        ink {
            moveTo(13.2f, 2.2f)
            lineTo(5.6f, 13.4f)
            lineTo(11f, 13.4f)
            lineTo(9.3f, 21.6f)
            lineTo(18.6f, 9.6f)
            lineTo(13f, 9.6f)
            close()
        }
    }

    val Wrench: ImageVector = icon("Wrench") {
        shade { circle(8.2f, 8.2f, 4.2f) }
        ink(PathFillType.EvenOdd) {
            circle(7.6f, 7.6f, 4.2f)
            circle(7.6f, 7.6f, 1.65f)
        }
        ink {
            moveTo(10.1f, 10.4f)
            lineTo(16.8f, 17.2f)
            lineTo(18.6f, 15.4f)
            lineTo(11.8f, 8.6f)
            close()
            roundRect(15.8f, 16.2f, 20.4f, 19.2f, 1.15f)
        }
    }

    val Speed: ImageVector = icon("Speed") {
        shade {
            moveTo(5.4f, 17f)
            arcTo(8f, 8f, 0f, false, true, 19.4f, 17f)
        }
        stroke(2.3f) {
            moveTo(4.6f, 17f)
            arcTo(8.2f, 8.2f, 0f, false, true, 19.4f, 17f)
        }
        ink {
            moveTo(11.3f, 15.8f)
            lineTo(16.4f, 8.4f)
            lineTo(14.7f, 7.5f)
            lineTo(10.2f, 15.2f)
            close()
        }
        ink { circle(12f, 16.3f, 1.65f) }
    }

    val Update: ImageVector = icon("Update") {
        shade { circle(12.4f, 12.5f, 7.6f) }
        ink(PathFillType.EvenOdd) {
            circle(12f, 12f, 8.5f)
            circle(12f, 12f, 5.9f)
        }
        ink {
            moveTo(12f, 4.4f)
            lineTo(14.7f, 7.5f)
            lineTo(9.3f, 7.5f)
            close()
            roundRect(10.8f, 7f, 13.2f, 12.3f, 0.75f)
        }
    }

    val Shield: ImageVector = icon("Shield") {
        shade {
            moveTo(12.5f, 3.6f)
            lineTo(19.4f, 6.2f)
            curveTo(19.4f, 16.2f, 16.2f, 19.2f, 12.5f, 21f)
            curveTo(8.8f, 19.2f, 5.6f, 16.2f, 5.6f, 12f)
            lineTo(5.6f, 6.2f)
            close()
        }
        ink(PathFillType.EvenOdd) {
            moveTo(12f, 2.6f)
            lineTo(19.8f, 5.6f)
            lineTo(19.8f, 12f)
            curveTo(19.8f, 16.6f, 16.4f, 19.6f, 12f, 21.4f)
            curveTo(7.6f, 19.6f, 4.2f, 16.6f, 4.2f, 12f)
            lineTo(4.2f, 5.6f)
            close()
            moveTo(8.7f, 11.7f)
            lineTo(11f, 14.1f)
            lineTo(15.7f, 8.8f)
            lineTo(14.3f, 7.6f)
            lineTo(11f, 11.5f)
            lineTo(9.9f, 10.4f)
            close()
        }
    }

    val Article: ImageVector = icon("Article") {
        shade { roundRect(5.8f, 3.8f, 18f, 20.6f, 2f) }
        ink(PathFillType.EvenOdd) {
            roundRect(5.2f, 2.8f, 18.8f, 21f, 2.2f)
            roundRect(8f, 6.4f, 15.8f, 8f, 0.6f)
            roundRect(8f, 9.8f, 15.8f, 11.2f, 0.5f)
            roundRect(8f, 13f, 15.8f, 14.4f, 0.5f)
            roundRect(8f, 16.2f, 13.2f, 17.6f, 0.5f)
        }
    }

    val Qr: ImageVector = icon("Qr") {
        finder(3.2f, 3.2f)
        finder(13.8f, 3.2f)
        finder(3.2f, 13.8f)
        ink(alpha = 0.5f) {
            roundRect(13.8f, 13.8f, 16.6f, 16.6f, 0.6f)
            roundRect(18f, 13.8f, 20.8f, 16.6f, 0.6f)
            roundRect(13.8f, 18f, 16.6f, 20.8f, 0.6f)
            roundRect(18f, 18f, 20.8f, 20.8f, 0.6f)
        }
    }

    val Gavel: ImageVector = icon("Gavel") {
        shade { roundRect(4.4f, 17.2f, 19.8f, 20.2f, 1.1f) }
        ink { roundRect(3.4f, 17.6f, 20.6f, 20.6f, 1.15f) }
        ink {
            moveTo(6.4f, 12.6f)
            lineTo(11.6f, 6.6f)
            lineTo(15f, 9.6f)
            lineTo(9.8f, 15.6f)
            close()
        }
        ink(alpha = 0.48f) {
            moveTo(12.6f, 5.4f)
            lineTo(16.2f, 2.8f)
            lineTo(19.2f, 6.6f)
            lineTo(15.6f, 9.2f)
            close()
        }
        ink { roundRect(9.3f, 13.4f, 11.3f, 17.6f, 0.65f) }
    }

    val Modpack: ImageVector = icon("Modpack") {
        shade { roundRect(5f, 8.8f, 18.8f, 19.8f, 1.8f) }
        ink(alpha = 0.42f) { roundRect(6.4f, 5.2f, 17.6f, 8.6f, 1.2f) }
        ink(PathFillType.EvenOdd) {
            roundRect(4.2f, 8.4f, 19.8f, 20.4f, 2f)
            roundRect(7.1f, 11.3f, 11f, 15.2f, 0.7f)
            roundRect(13f, 11.3f, 16.9f, 15.2f, 0.7f)
        }
    }

    val Sun: ImageVector = icon("Sun") {
        shade { circle(12.4f, 12.4f, 4.4f) }
        ink { circle(12f, 12f, 4.15f) }
        stroke(1.85f) {
            moveTo(12f, 2.8f); lineTo(12f, 6f)
            moveTo(12f, 18f); lineTo(12f, 21.2f)
            moveTo(2.8f, 12f); lineTo(6f, 12f)
            moveTo(18f, 12f); lineTo(21.2f, 12f)
            moveTo(5.5f, 5.5f); lineTo(7.7f, 7.7f)
            moveTo(16.3f, 16.3f); lineTo(18.5f, 18.5f)
            moveTo(18.5f, 5.5f); lineTo(16.3f, 7.7f)
            moveTo(7.7f, 16.3f); lineTo(5.5f, 18.5f)
        }
    }

    val Grid: ImageVector = icon("Grid") {
        ink { roundRect(3.2f, 3.2f, 9.4f, 9.4f, 1.6f) }
        ink(alpha = 0.45f) { roundRect(11f, 3.2f, 20.8f, 9.4f, 1.6f) }
        ink(alpha = 0.45f) { roundRect(3.2f, 11f, 9.4f, 20.8f, 1.6f) }
        ink { roundRect(11f, 11f, 14.6f, 14.6f, 1f) }
        ink(alpha = 0.7f) { roundRect(16.2f, 11f, 20.8f, 14.6f, 1f) }
        ink(alpha = 0.7f) { roundRect(11f, 16.2f, 14.6f, 20.8f, 1f) }
        ink { roundRect(16.2f, 16.2f, 20.8f, 20.8f, 1.15f) }
    }

    val Dataset: ImageVector = icon("Dataset") {
        shade { roundRect(4.4f, 6.4f, 19.4f, 11.2f, 1.8f) }
        ink { roundRect(3.6f, 3.4f, 20.4f, 8.8f, 2f) }
        ink(alpha = 0.55f) { roundRect(3.6f, 9.8f, 20.4f, 14.6f, 1.8f) }
        ink(alpha = 0.34f) { roundRect(3.6f, 15.6f, 20.4f, 20.4f, 1.8f) }
    }

    val Edit: ImageVector = icon("Edit") {
        shade {
            moveTo(14f, 5.6f)
            lineTo(18.8f, 10.4f)
            lineTo(9.4f, 19.8f)
            lineTo(4.6f, 19.8f)
            lineTo(4.6f, 15f)
            close()
        }
        ink {
            moveTo(14.2f, 3.6f)
            lineTo(20.4f, 9.8f)
            lineTo(9.4f, 20.8f)
            lineTo(3.4f, 20.8f)
            lineTo(3.4f, 14.8f)
            close()
        }
    }

    val Browser: ImageVector = icon("Browser") {
        shade { roundRect(3.8f, 4.6f, 20f, 19.8f, 2.6f) }
        ink(PathFillType.EvenOdd) {
            roundRect(3.2f, 3.4f, 20.8f, 20.2f, 2.7f)
            roundRect(5f, 8f, 19f, 18.4f, 1.2f)
        }
        ink {
            circle(6.1f, 5.8f, 0.8f)
            circle(8.6f, 5.8f, 0.8f)
            moveTo(10.6f, 11.2f)
            lineTo(16.2f, 11.2f)
            lineTo(16.2f, 12.8f)
            lineTo(13.4f, 12.8f)
            lineTo(15.8f, 16.2f)
            lineTo(14.4f, 17.2f)
            lineTo(11.2f, 13.2f)
            lineTo(11.2f, 16.4f)
            lineTo(9.6f, 16.4f)
            lineTo(9.6f, 11.2f)
            close()
        }
    }

    val Key: ImageVector = icon("Key") {
        shade { circle(8.4f, 9.2f, 4.2f) }
        ink(PathFillType.EvenOdd) {
            circle(8f, 8.6f, 4.5f)
            circle(8f, 8.6f, 1.75f)
        }
        ink {
            roundRect(11.2f, 10.2f, 20.2f, 12.7f, 1.05f)
            roundRect(16.4f, 12.3f, 18.5f, 15.5f, 0.65f)
            roundRect(13.4f, 12.3f, 15.2f, 14.5f, 0.55f)
        }
    }

    val Globe: ImageVector = icon("Globe") {
        shade { circle(12.4f, 12.4f, 7.6f) }
        ink(PathFillType.EvenOdd) {
            circle(12f, 12f, 8.5f)
            circle(12f, 12f, 6.6f)
        }
        stroke(1.55f) {
            moveTo(12f, 3.6f)
            curveTo(9.2f, 7.2f, 9.2f, 16.8f, 12f, 20.4f)
            moveTo(12f, 3.6f)
            curveTo(14.8f, 7.2f, 14.8f, 16.8f, 12f, 20.4f)
            moveTo(4.2f, 12f)
            lineTo(19.8f, 12f)
        }
    }

    val History: ImageVector = icon("History") {
        shade { circle(12.4f, 12.5f, 7.4f) }
        ink(PathFillType.EvenOdd) {
            circle(12f, 12f, 8.3f)
            circle(12f, 12f, 5.9f)
        }
        ink {
            roundRect(11.1f, 7.2f, 12.9f, 12.5f, 0.65f)
            roundRect(11.1f, 11.2f, 15.6f, 12.9f, 0.65f)
        }
    }

    val Image: ImageVector = icon("Image") {
        shade { roundRect(3.8f, 4.8f, 19.8f, 19.6f, 2.4f) }
        ink(PathFillType.EvenOdd) {
            roundRect(3.2f, 3.6f, 20.8f, 20.4f, 2.5f)
            circle(8.2f, 8.2f, 1.55f)
            moveTo(4.4f, 16.6f)
            lineTo(8.8f, 12.4f)
            lineTo(12.2f, 15.4f)
            lineTo(15.4f, 12.8f)
            lineTo(19.6f, 16.8f)
            lineTo(19.6f, 18.8f)
            lineTo(4.4f, 18.8f)
            close()
        }
    }

    val Video: ImageVector = icon("Video") {
        shade { roundRect(3.6f, 6.6f, 15.6f, 18f, 2.2f) }
        ink { roundRect(2.8f, 5.6f, 15.2f, 18.4f, 2.3f) }
        ink {
            moveTo(15.2f, 9.2f)
            lineTo(21.2f, 6.4f)
            lineTo(21.2f, 17.6f)
            lineTo(15.2f, 14.8f)
            close()
        }
    }

    val Plus: ImageVector = icon("Plus") {
        shade { circle(12.4f, 12.5f, 7.6f) }
        ink(PathFillType.EvenOdd) {
            circle(12f, 12f, 8.5f)
            circle(12f, 12f, 6.35f)
        }
        ink {
            roundRect(10.7f, 6.3f, 13.3f, 17.7f, 1f)
            roundRect(6.3f, 10.7f, 17.7f, 13.3f, 1f)
        }
    }

    val Playlist: ImageVector = icon("Playlist") {
        shade { circle(7.6f, 17f, 2.6f) }
        ink { circle(7.2f, 16.6f, 2.45f) }
        ink { roundRect(9.3f, 4.8f, 11.3f, 15.6f, 0.7f) }
        ink(alpha = 0.45f) {
            roundRect(13.2f, 6.2f, 20.6f, 8f, 0.7f)
            roundRect(13.2f, 10.1f, 20.6f, 11.9f, 0.7f)
            roundRect(13.2f, 14f, 18.4f, 15.8f, 0.7f)
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

    private fun ImageVector.Builder.ink(
        fillType: PathFillType = PathFillType.NonZero,
        alpha: Float = 1f,
        block: PathBuilder.() -> Unit
    ) {
        path(
            fill = SolidColor(Color.Black),
            fillAlpha = alpha,
            pathFillType = fillType,
            pathBuilder = block
        )
    }

    private fun ImageVector.Builder.shade(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), fillAlpha = 0.28f, pathBuilder = block)
    }

    private fun ImageVector.Builder.stroke(width: Float, block: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }

    private fun ImageVector.Builder.finder(x: Float, y: Float) {
        ink(PathFillType.EvenOdd) {
            roundRect(x, y, x + 7f, y + 7f, 1.5f)
            roundRect(x + 2f, y + 2f, x + 5f, y + 5f, 0.6f)
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

    private fun PathBuilder.gear(cx: Float, cy: Float, teeth: Int, outer: Float, root: Float) {
        val steps = teeth * 2
        for (i in 0 until steps) {
            val ang = Math.toRadians(-90.0 + (i + 0.5) * (360.0 / steps))
            val rad = if (i % 2 == 0) outer else root
            val x = cx + cos(ang).toFloat() * rad
            val y = cy + sin(ang).toFloat() * rad
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
