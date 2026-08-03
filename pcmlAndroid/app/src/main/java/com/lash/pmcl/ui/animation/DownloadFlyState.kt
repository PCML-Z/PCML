package com.lash.pmcl.ui.animation

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

data class DownloadFlyState(
    val id: Long,
    val source: Rect,
    val target: Rect,
    val title: String
)

data class Rect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    val center: IntOffset
        get() = IntOffset(x + width / 2, y + height / 2)

    companion object {
        fun from(position: IntOffset, size: IntSize): Rect =
            Rect(position.x, position.y, size.width, size.height)
    }
}
