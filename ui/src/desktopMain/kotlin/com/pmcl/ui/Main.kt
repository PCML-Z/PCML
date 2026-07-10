package com.pmcl.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.core.preferences.Preferences
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D
import java.nio.file.Paths

/**
 * 桌面端入口。
 *
 * 运行方式：./gradlew :ui:run
 */
fun main() = application {
    // 启动时读取偏好设置，决定是否使用无边框窗口
    val pref = remember {
        Preferences(Paths.get(System.getProperty("user.home"), ".pmcl", "preferences.json"))
    }
    val borderless = pref.isBorderlessWindow()

    val state = rememberWindowState(
        width = 1100.dp,
        height = 700.dp,
        position = WindowPosition.Aligned(Alignment.Center)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "PMCL — Minecraft Launcher",
        state = state,
        undecorated = borderless
    ) {
        if (borderless) {
            // 无边框窗口用 AWT shape 裁剪出圆角（最大化时用直角填满屏幕）
            DisposableEffect(Unit) {
                val updateShape = {
                    val arc = if (window.extendedState == Frame.MAXIMIZED_BOTH) 0.0 else 12.0
                    window.shape = RoundRectangle2D.Double(
                        0.0, 0.0,
                        window.width.toDouble(), window.height.toDouble(),
                        arc, arc
                    )
                }
                updateShape()
                val listener = object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent?) { updateShape() }
                    override fun componentMoved(e: ComponentEvent?) { updateShape() }
                }
                window.addComponentListener(listener)
                onDispose { window.removeComponentListener(listener) }
            }
            Column(Modifier.fillMaxSize()) {
                // 无边框模式下使用自定义标题栏
                val isDark = pref.isUseDarkTheme()
                MaterialTheme(
                    colorScheme = if (isDark) darkColorScheme() else lightColorScheme()
                ) {
                    BorderlessTitleBar(onClose = ::exitApplication)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    App()
                }
            }
        } else {
            App()
        }
    }
}

/**
 * 窗口拖拽修饰符：按住左键拖拽移动窗口（Compose 1.7 无 WindowDragArea，手动实现）。
 */
private fun WindowScope.windowDragModifier(): Modifier = Modifier.pointerInput(Unit) {
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
                }
                if (event.type == PointerEventType.Move &&
                    initialMouse != null && initialWindowLoc != null && mouseLocation != null
                ) {
                    val dx = mouseLocation.x - initialMouse!!.x
                    val dy = mouseLocation.y - initialMouse!!.y
                    window.setLocation(initialWindowLoc!!.x + dx, initialWindowLoc!!.y + dy)
                }
            } else {
                initialMouse = null
                initialWindowLoc = null
            }
        }
    }
}

/**
 * 无边框窗口自定义标题栏：可拖拽 + 最小化/最大化/关闭按钮。
 */
@Composable
private fun FrameWindowScope.BorderlessTitleBar(onClose: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().height(36.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 拖拽区域
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(windowDragModifier())
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "PMCL — Minecraft Launcher",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            // 最小化
            IconButton(
                onClick = { window.extendedState = Frame.ICONIFIED },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.Minimize, "最小化", modifier = Modifier.size(16.dp))
            }
            // 最大化/还原
            IconButton(
                onClick = {
                    window.extendedState =
                        if (window.extendedState == Frame.MAXIMIZED_BOTH)
                            Frame.NORMAL
                        else
                            Frame.MAXIMIZED_BOTH
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.CropSquare, "最大化/还原", modifier = Modifier.size(14.dp))
            }
            // 关闭
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(16.dp))
            }
        }
    }
}
