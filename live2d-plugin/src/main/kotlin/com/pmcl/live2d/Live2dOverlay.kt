package com.pmcl.live2d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pmcl.plugin.PluginContext
import kotlin.math.roundToInt

/**
 * Live2D overlay 的 Composable 内容。
 *
 * 默认定位在右下角（BottomEnd），用户可通过拖拽模型移动位置。
 * 顶部控制条含拖拽手柄、设置按钮、隐藏按钮。
 * 点击设置按钮打开配置弹窗，可修改模型 URL 和缩放比例。
 */
class Live2dOverlayContent(
    private val ctx: PluginContext,
    private val initialModelUrl: String
) : com.pmcl.plugin.ComposableContent {

    @Composable
    override fun invoke() = Live2dOverlay(ctx, initialModelUrl)
}

@Composable
private fun Live2dOverlay(ctx: PluginContext, initialModelUrl: String) {
    // 从插件配置读取持久化设置
    var modelUrl by remember { mutableStateOf(ctx.getConfig("modelUrl") ?: initialModelUrl) }
    var scale by remember { mutableFloatStateOf((ctx.getConfig("scale") ?: "1.0").toFloatOrNull() ?: 1.0f) }

    // 拖拽偏移
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 是否可见
    var visible by remember { mutableStateOf(true) }
    // 设置弹窗
    var showSettings by remember { mutableStateOf(false) }

    if (!visible) {
        // 隐藏状态：右下角圆形恢复按钮
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
    } else {
        // 可见状态：右下角显示控制条 + Live2D 模型
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            Column(
                modifier = Modifier.padding(end = 16.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.End
            ) {
                // 控制条：拖拽提示 + 设置 + 隐藏
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = "Drag to move",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(
                        onClick = { visible = false },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Hide",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(Modifier.size(4.dp))

                // SwingPanel 嵌入 JFXPanel（内含 WebView 渲染 Live2D）
                SwingPanel(
                    factory = {
                        Live2dWebView.getOrCreatePanel(modelUrl) { dx, dy ->
                            offsetX += dx
                            offsetY -= dy
                        }
                    },
                    modifier = Modifier
                        .size(width = 300.dp, height = 400.dp)
                        .offset {
                            IntOffset(offsetX.roundToInt(), offsetY.roundToInt())
                        },
                    background = Color(0, 0, 0, 0)
                )
            }
        }
    }

    // 设置弹窗
    if (showSettings) {
        Live2dSettingsDialog(
            modelUrl = modelUrl,
            scale = scale,
            onDismiss = { showSettings = false },
            onApply = { newUrl, newScale ->
                val urlChanged = newUrl != modelUrl
                modelUrl = newUrl
                scale = newScale
                // 持久化到插件配置
                ctx.setConfig("modelUrl", newUrl)
                ctx.setConfig("scale", newScale.toString())
                // 应用更改
                if (urlChanged) {
                    Live2dWebView.reloadModel(newUrl)
                } else {
                    Live2dWebView.setScale(newScale)
                }
                showSettings = false
            },
            onReset = {
                val defaultUrl = "https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display/test/assets/shizuku/shizuku.model.json"
                modelUrl = defaultUrl
                scale = 1.0f
                ctx.setConfig("modelUrl", defaultUrl)
                ctx.setConfig("scale", "1.0")
                Live2dWebView.reloadModel(defaultUrl)
                showSettings = false
            }
        )
    }
}

@Composable
private fun Live2dSettingsDialog(
    modelUrl: String,
    scale: Float,
    onDismiss: () -> Unit,
    onApply: (String, Float) -> Unit,
    onReset: () -> Unit
) {
    var editUrl by remember { mutableStateOf(modelUrl) }
    var editScale by remember { mutableFloatStateOf(scale) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Live2D 设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("模型 URL", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = editUrl,
                    onValueChange = { editUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://... .model3.json") },
                    supportingText = { Text("支持 Cubism 2/3/4 模型文件 URL") }
                )

                Spacer(Modifier.size(4.dp))

                Text("缩放比例: ${"%.1f".format(editScale)}", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("0.3", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Slider(
                        value = editScale,
                        onValueChange = { editScale = it },
                        valueRange = 0.3f..3.0f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("3.0", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }

                Spacer(Modifier.size(4.dp))

                // 常用模型快速选择
                Text("快速选择", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PresetModelButton("Shizuku (Cubism 2)", "https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display/test/assets/shizuku/shizuku.model.json") { editUrl = it }
                    PresetModelButton("Histoire (Cubism 4)", "https://cdn.jsdelivr.net/gh/Eikanya/Live2d-model/Live2D/Senko_Normals/senko.model3.json") { editUrl = it }
                    PresetModelButton("Pio (Cubism 2)", "https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display/test/assets/haru/haru_pro.model3.json") { editUrl = it }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(editUrl, editScale) }) {
                Text("应用")
            }
        },
        dismissButton = {
            Row {
                OutlinedButton(onClick = onReset) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重置")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
private fun PresetModelButton(label: String, url: String, onSelect: (String) -> Unit) {
    OutlinedButton(
        onClick = { onSelect(url) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Start)
    }
}
