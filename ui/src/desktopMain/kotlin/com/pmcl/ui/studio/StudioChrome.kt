package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit

internal const val MIN_WIDTH = 960
internal const val MIN_HEIGHT = 600

/** 无边框窗口的最大化：铺满当前屏幕的可用区域（去掉菜单栏、Dock、任务栏）。 */
internal class WindowFrame(private val window: java.awt.Window) {
    var restore by androidx.compose.runtime.mutableStateOf<Rectangle?>(null)

    val maximized: Boolean get() = restore != null

    fun toggleMaximize() {
        val saved = restore
        if (saved != null) {
            window.bounds = saved
            restore = null
        } else {
            restore = window.bounds
            val gc = window.graphicsConfiguration
            val screen = gc.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
            window.setBounds(
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom
            )
        }
    }
}

/** 拖动移动窗口，双击切换最大化。 */
internal fun FrameWindowScope.dragArea(frame: WindowFrame, onMaximizeChange: () -> Unit): Modifier =
    Modifier.pointerInput(frame) {
        var lastPress = 0L
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent()
                if (down.type != PointerEventType.Press || !down.buttons.isPrimaryPressed) continue
                val now = System.currentTimeMillis()
                if (now - lastPress < 350) {
                    lastPress = 0L
                    frame.toggleMaximize()
                    onMaximizeChange()
                    continue
                }
                lastPress = now
                val start = MouseInfo.getPointerInfo()?.location ?: continue
                val origin = window.location
                var moved = false
                while (true) {
                    val event = awaitPointerEvent()
                    if (!event.buttons.isPrimaryPressed) break
                    val mouse = MouseInfo.getPointerInfo()?.location ?: continue
                    val dx = mouse.x - start.x
                    val dy = mouse.y - start.y
                    if (!moved && (kotlin.math.abs(dx) > 3 || kotlin.math.abs(dy) > 3)) {
                        moved = true
                        if (frame.maximized) {
                            frame.toggleMaximize()
                            onMaximizeChange()
                        }
                    }
                    if (moved && !frame.maximized) window.setLocation(origin.x + dx, origin.y + dy)
                }
            }
        }
    }

@Composable
private fun FrameWindowScope.ResizeHandle(modifier: Modifier, cursor: Int, dx: Int, dy: Int) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(cursor)))
            .pointerInput(dx, dy) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        if (down.type != PointerEventType.Press || !down.buttons.isPrimaryPressed) continue
                        val start = MouseInfo.getPointerInfo()?.location ?: continue
                        val b = window.bounds
                        down.changes.forEach { it.consume() }
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!event.buttons.isPrimaryPressed) break
                            val m = MouseInfo.getPointerInfo()?.location ?: continue
                            val mx = m.x - start.x
                            val my = m.y - start.y
                            var x = b.x
                            var y = b.y
                            var w = b.width
                            var h = b.height
                            if (dx > 0) w = maxOf(MIN_WIDTH, b.width + mx)
                            if (dx < 0) { w = maxOf(MIN_WIDTH, b.width - mx); x = b.x + b.width - w }
                            if (dy > 0) h = maxOf(MIN_HEIGHT, b.height + my)
                            if (dy < 0) { h = maxOf(MIN_HEIGHT, b.height - my); y = b.y + b.height - h }
                            window.setBounds(x, y, w, h)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    )
}

/** 无边框窗口四边和四角的拖拽缩放区域。 */
@Composable
internal fun FrameWindowScope.ResizeEdges(scope: BoxScope) {
    val edge = 5.dp
    val corner = 12.dp
    with(scope) {
        ResizeHandle(Modifier.align(Alignment.CenterEnd).width(edge).fillMaxHeight(), Cursor.E_RESIZE_CURSOR, 1, 0)
        ResizeHandle(Modifier.align(Alignment.CenterStart).width(edge).fillMaxHeight(), Cursor.W_RESIZE_CURSOR, -1, 0)
        ResizeHandle(Modifier.align(Alignment.BottomCenter).height(edge).fillMaxWidth(), Cursor.S_RESIZE_CURSOR, 0, 1)
        ResizeHandle(Modifier.align(Alignment.TopCenter).height(3.dp).fillMaxWidth(), Cursor.N_RESIZE_CURSOR, 0, -1)
        ResizeHandle(Modifier.align(Alignment.BottomEnd).size(corner), Cursor.SE_RESIZE_CURSOR, 1, 1)
        ResizeHandle(Modifier.align(Alignment.BottomStart).size(corner), Cursor.SW_RESIZE_CURSOR, -1, 1)
        ResizeHandle(Modifier.align(Alignment.TopEnd).size(corner / 2), Cursor.NE_RESIZE_CURSOR, 1, -1)
        ResizeHandle(Modifier.align(Alignment.TopStart).size(corner / 2), Cursor.NW_RESIZE_CURSOR, -1, -1)
    }
}

/** 标题栏右侧的窗口按钮：整块色块，关闭悬停变红。 */
@Composable
internal fun WindowButton(icon: ImageVector, label: String, base: Color, hoverColor: Color, width: Dp = 46.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(if (hovered) hoverColor else base)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
internal fun TitleTab(label: String, active: Boolean, onClick: () -> Unit) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .fillMaxHeight()
            .background(
                when {
                    active -> p.panel
                    hovered -> p.accent.lift(0.12f)
                    else -> Color.Transparent
                }
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        FlatText(
            label,
            color = if (active) p.accent else Color.White,
            weight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
        )
    }
}

@Composable
internal fun StatusSegment(text: String, color: Color, modifier: Modifier = Modifier, bold: Boolean = false) {
    Row(modifier.fillMaxHeight().background(color), verticalAlignment = Alignment.CenterVertically) {
        FlatText(
            text,
            color = inkOn(color),
            size = 11,
            weight = if (bold) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

@Composable
internal fun ChromeRow(height: Dp, color: Color, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(height).background(color), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
