package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.*
import com.pmcl.plugin.ComposableContent
import com.pmcl.plugin.DockHostPageFactories
import com.pmcl.plugin.DockHostPageFactory
import com.pmcl.plugin.EmbedHostBounds
import kotlin.math.roundToInt
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.atomic.AtomicReference

/**
 * `embed=window` 模式的"占位宿主区域"。
 *
 * <p>它本身只在 PMCL 主窗内画一块半透明占位（标注 LauncherX），并**把自身在屏幕上的
 * 全局矩形持续写入 [EmbedHostBounds]**。核心层停靠循环读取该矩形，把被停靠应用
 * （LauncherX.Avalonia）的真实窗口定位/缩放到这块区域之上，跟随 PMCL 移动/缩放。
 *
 * <p>坐标换算关键点：
 * <ul>
 *   <li>Compose 的布局坐标单位已是<b>逻辑点(points)</b>，与 AWT / AppleScript 一致，
 *       切勿再用 [androidx.compose.ui.platform.LocalDensity] 二次换算（Retina 上会缩水一半）。</li>
 *   <li>AWT 全局坐标：原点主屏左上、Y 向下；AppleScript/System Events：原点主屏左下、Y 向上。
 *       两者 X 同向，Y 相差主屏高度（需翻转）。副屏偏移由 AWT 全局坐标自然携带。</li>
 * </ul>
 *
 * <p>跟随移动 / 自适应大小的关键：
 * [androidx.compose.ui.layout.OnGloballyPositionedModifier] 只在<b>占位区自身布局变化</b>
 * 时触发；而拖动 PMCL 主窗时占位区相对窗口根的位置不变，回调不会重入，导致注册表停在原值、
 * LauncherX 窗口既不跟随也不缩放。因此这里给主窗口注册 [java.awt.event.ComponentListener]，
 * 监听 `move` / `resize`，每次主动用"当前主窗位置 + 占位区相对坐标"重算绝对坐标写回，
 * 停靠循环（200ms）随之跟上。
 */
@Composable
fun DockHost(pluginId: String, modifier: Modifier = Modifier) {
    val mainScreenHeight = Toolkit.getDefaultToolkit().screenSize.height

    // 跨重组存活的引用：主窗、占位区相对窗口根的坐标、已注册的监听
    val winRef = remember { AtomicReference<Frame?>(null) }
    val relRef = remember { AtomicReference<Rectangle?>(null) }
    val listenerRef = remember { AtomicReference<ComponentAdapter?>(null) }

    fun findMainWin(): Frame? =
        (Window.getWindows().firstOrNull { w -> w is Frame && w.title.startsWith("PMCL") }
            ?: Window.getWindows().firstOrNull { w -> w is Frame }) as? Frame

    // 用"当前主窗位置 + 占位区相对坐标"实时算出 AppleScript 全局坐标并写入注册表
    fun recompute() {
        val win = winRef.get() ?: return
        val rel = relRef.get() ?: return
        if (rel.width <= 0 || rel.height <= 0) return
        val awtLeft = win.x + win.insets.left + rel.x
        val awtTop = win.y + win.insets.top + rel.y
        val cocoaX = awtLeft
        val cocoaY = mainScreenHeight - awtTop
        EmbedHostBounds.set(
            pluginId,
            Rectangle(cocoaX, cocoaY, rel.width, rel.height)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .onGloballyPositioned { coords ->
                val win = findMainWin() ?: return@onGloballyPositioned
                winRef.set(win)
                val topLeft = coords.localToRoot(Offset.Zero)
                relRef.set(
                    Rectangle(topLeft.x.toInt(), topLeft.y.toInt(), coords.size.width, coords.size.height)
                )
                recompute()
                // 仅注册一次：监听主窗移动/缩放，主动重算绝对坐标 → 跟随 + 自适应大小
                if (listenerRef.get() == null) {
                    val listener = object : ComponentAdapter() {
                        override fun componentMoved(e: ComponentEvent) = recompute()
                        override fun componentResized(e: ComponentEvent) = recompute()
                    }
                    win.addComponentListener(listener)
                    listenerRef.set(listener)
                }
            }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "LauncherX",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    DisposableEffect(pluginId) {
        onDispose {
            listenerRef.get()?.let { winRef.get()?.removeComponentListener(it) }
            EmbedHostBounds.remove(pluginId)
        }
    }
}

/**
 * 注册 DockHost 页面工厂：外部运行时插件（embed=window）据此把自己的真实窗口
 * 停靠进 PMCL 主窗口成为普通页面（占位区 + 浮动真实窗口）。
 * 必须早于插件系统启用任何 embed=window 插件。
 */
fun registerDockHostFactory() {
    DockHostPageFactories.register(DockHostPageFactory { pluginId ->
        ComposableContent { DockHost(pluginId) }
    })
}
