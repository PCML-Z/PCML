package com.pmcl.ui

import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowScope
import java.awt.MouseInfo
import java.awt.Point

/**
 * 窗口拖拽修饰符：按住左键拖拽移动窗口（Compose 1.7 无 WindowDragArea，手动实现）。
 * 拖动开始/结束时更新 isDragging 状态，用于切换透明/不透明渲染避免闪烁。
 *
 * 仅挂在标题栏空白区（标题文字 / Spacer），不要挂整行 Row，
 * 这样按钮会先命中自身，不会整窗被拖走。
 *
 * 注意：不要用 Final pass + isConsumed 过滤——Compose Desktop 上许多事件在 Final
 * 时已被标记 consumed，会导致拖拽永远无法启动。
 */
internal fun WindowScope.windowDragModifier(isDragging: MutableState<Boolean>): Modifier =
    Modifier.pointerInput(Unit) {
        var initialMouse: Point? = null
        var initialWindowLoc: Point? = null

        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val mouseLocation = MouseInfo.getPointerInfo()?.location

                if (event.buttons.isPrimaryPressed) {
                    if (initialMouse == null && mouseLocation != null) {
                        initialMouse = mouseLocation
                        initialWindowLoc = Point(window.x, window.y)
                        isDragging.value = true
                    }
                    val im = initialMouse
                    val iwl = initialWindowLoc
                    if (event.type == PointerEventType.Move && im != null && iwl != null && mouseLocation != null) {
                        val dx = mouseLocation.x - im.x
                        val dy = mouseLocation.y - im.y
                        window.setLocation(iwl.x + dx, iwl.y + dy)
                    }
                } else {
                    if (initialMouse != null) {
                        isDragging.value = false
                    }
                    initialMouse = null
                    initialWindowLoc = null
                }
            }
        }
    }
