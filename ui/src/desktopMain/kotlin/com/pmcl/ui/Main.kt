package com.pmcl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        undecorated = borderless,
        transparent = borderless
    ) {
        if (borderless) {
            // 无边框模式：transparent=true 让边缘像素 alpha 混合（抗锯齿），
            // 拖动期间临时设为不透明矩形避免 SwingPanel 重绘延迟导致闪烁
            val isDark = pref.isUseDarkTheme()
            val scheme = if (isDark) darkColorScheme() else lightColorScheme()
            val surfaceColor = scheme.surface
            val isDragging = remember { mutableStateOf(false) }

            DisposableEffect(Unit) {
                val updateShape = {
                    if (isDragging.value) {
                        // 拖动中：移除 shape 和透明，用不透明矩形避免闪烁
                        window.shape = null
                        window.background = java.awt.Color(
                            surfaceColor.red, surfaceColor.green, surfaceColor.blue
                        )
                    } else if (window.extendedState == Frame.MAXIMIZED_BOTH) {
                        // 最大化：直角填满屏幕
                        window.shape = null
                        window.background = java.awt.Color(
                            surfaceColor.red, surfaceColor.green, surfaceColor.blue
                        )
                    } else {
                        // 正常：透明背景 + 圆角 shape（边缘抗锯齿）
                        window.background = java.awt.Color(0, 0, 0, 0)
                        window.shape = RoundRectangle2D.Double(
                            0.0, 0.0,
                            window.width.toDouble(), window.height.toDouble(),
                            14.0, 14.0
                        )
                    }
                }
                updateShape()
                val listener = object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent?) { updateShape() }
                    override fun componentMoved(e: ComponentEvent?) { updateShape() }
                }
                window.addComponentListener(listener)
                onDispose { window.removeComponentListener(listener) }
            }
            // 拖动状态变化时刷新 shape
            LaunchedEffect(isDragging.value) {
                if (!isDragging.value) {
                    window.background = java.awt.Color(0, 0, 0, 0)
                    window.shape = if (window.extendedState == Frame.MAXIMIZED_BOTH) null
                    else RoundRectangle2D.Double(
                        0.0, 0.0,
                        window.width.toDouble(), window.height.toDouble(),
                        14.0, 14.0
                    )
                }
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(Modifier.fillMaxSize()) {
                        BorderlessTitleBar(onClose = ::exitApplication, isDragging = isDragging)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            App()
                        }
                    }
                }
            }
        } else {
            App()
        }
    }
}

/**
 * 窗口拖拽修饰符：按住左键拖拽移动窗口（Compose 1.7 无 WindowDragArea，手动实现）。
 * 拖动开始/结束 时更新 isDragging 状态，用于切换透明/不透明渲染避免闪烁。
 */
private fun WindowScope.windowDragModifier(isDragging: MutableState<Boolean>): Modifier =
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

/**
 * 无边框窗口自定义标题栏：可拖拽 + 最小化/最大化/关闭按钮。
 */
@Composable
private fun FrameWindowScope.BorderlessTitleBar(
    onClose: () -> Unit,
    isDragging: MutableState<Boolean>
) {
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
                    .then(windowDragModifier(isDragging))
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
