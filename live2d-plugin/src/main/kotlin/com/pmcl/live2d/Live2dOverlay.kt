package com.pmcl.live2d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pmcl.plugin.PluginContext
import kotlin.math.roundToInt

/**
 * Live2D overlay 的 Composable 内容。
 *
 * 默认定位在右下角（BottomEnd），用户可通过拖拽模型移动位置。
 * 顶部有一个控制条（拖拽手柄 + 设置 + 隐藏按钮）。
 *
 * [offsetX] / [offsetY] 记录用户拖拽产生的位移偏移（相对于初始 BottomEnd 位置）。
 * 拖拽增量由 JavaScript 端检测后通过 dragBridge 回调到 [onDrag] lambda，
 * 再更新这两个 mutableFloatStateOf 触发 Compose 重组。
 *
 * Compose Desktop 的 snapshot 系统线程安全，mutableFloatStateOf 可在
 * 任意线程更新（参见项目记忆：不用 withContext(Dispatchers.Main)）。
 */
class Live2dOverlayContent(
    private val ctx: PluginContext,
    private val modelUrl: String
) : com.pmcl.plugin.ComposableContent {

    @Composable
    override fun invoke() = Live2dOverlay(ctx, modelUrl)
}

@Composable
private fun Live2dOverlay(ctx: PluginContext, modelUrl: String) {
    // 拖拽偏移（相对于 BottomEnd 初始位置）
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 是否可见（隐藏后只显示一个小按钮）
    var visible by remember { mutableStateOf(true) }

    if (!visible) {
        // 隐藏状态：显示一个圆形恢复按钮在右下角
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            Surface(
                modifier = Modifier
                    .padding(16.dp)
                    .size(40.dp)
                    .clickable { visible = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Show Live2D",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        return
    }

    // 可见状态：右下角显示控制条 + Live2D 模型
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            modifier = Modifier.padding(end = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.End
        ) {
            // 控制条：拖拽提示 + 隐藏按钮
            Row(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "Drag to move",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Hide Live2D",
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { visible = false },
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.size(4.dp))

            // SwingPanel 嵌入 JFXPanel（内含 WebView 渲染 Live2D）
            // 拖拽回调从 JS → Java bridge → onDrag lambda → 更新 offset
            SwingPanel(
                factory = {
                    Live2dWebView.getOrCreatePanel(modelUrl) { dx, dy ->
                        // Compose snapshot 线程安全，可直接更新
                        offsetX += dx
                        offsetY -= dy // Y 轴反转：JS screenY 向下为正，Compose offset 向上为正
                    }
                },
                modifier = Modifier
                    .size(width = 300.dp, height = 400.dp)
                    .offset {
                        // 应用拖拽偏移（像素单位），相对于 BottomEnd 初始位置
                        IntOffset(offsetX.roundToInt(), offsetY.roundToInt())
                    },
                background = Color(0, 0, 0, 0) // 透明背景
            )
        }
    }
}
