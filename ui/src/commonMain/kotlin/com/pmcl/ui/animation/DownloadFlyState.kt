package com.pmcl.ui.animation

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 下载飞入动画状态。
 *
 * @param id       唯一标识，用于动画结束后移除
 * @param source   源卡片在窗口中的位置（左上角坐标 + 尺寸）
 * @param target   目标（下载队列卡片）在窗口中的位置
 * @param title    显示在飞行卡片上的标题
 * @param onDone   动画完成回调（触发实际下载入队）
 */
data class DownloadFlyState(
    val id: Long,
    val source: Rect,
    val target: Rect,
    val title: String,
    val onDone: () -> Unit
)

/** 窗口坐标矩形 */
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
