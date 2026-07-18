package com.pmcl.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.core.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.MouseInfo
import java.awt.Point
import java.awt.geom.RoundRectangle2D

/**
 * 性能 HUD 浮窗：半透明、置顶、可拖动的实时性能监控小窗。
 * 指标从 [metrics] 解析（逗号分隔：CPU/MEM/GPU/FPS），由调用方传入。
 */
@Composable
fun PerfHudWindow(
    metrics: String,
    onClose: () -> Unit
) {
    val enabledMetrics = remember(metrics) {
        metrics.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
    }

    val state = rememberWindowState(width = 220.dp, height = 180.dp)

    Window(
        onCloseRequest = onClose,
        title = "PMCL Performance HUD",
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false
    ) {
        // 圆角 shape（边缘抗锯齿）
        DisposableEffect(Unit) {
            val update = {
                window.background = java.awt.Color(0, 0, 0, 0)
                window.shape = RoundRectangle2D.Double(
                    0.0, 0.0,
                    window.width.toDouble(), window.height.toDouble(),
                    12.0, 12.0
                )
            }
            update()
            val listener = object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) = update()
            }
            window.addComponentListener(listener)
            onDispose { window.removeComponentListener(listener) }
        }

        PerfHudContent(enabledMetrics, window, onClose)
    }
}

@Composable
private fun PerfHudContent(
    enabledMetrics: Set<String>,
    awtWindow: java.awt.Window,
    onClose: () -> Unit
) {
    val runtime = remember { RuntimeManager() }

    // 采样状态
    var cpuLoad by remember { mutableStateOf(0.0) }
    var memLoad by remember { mutableStateOf(0.0) }
    var memUsedMb by remember { mutableStateOf(0L) }
    var memTotalMb by remember { mutableStateOf(0L) }
    var gpuName by remember { mutableStateOf("") }
    var gpuVramMb by remember { mutableStateOf(-1L) }
    var fps by remember { mutableStateOf(0) }

    // 历史折线（最多 30 点）
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val memHistory = remember { mutableStateListOf<Float>() }
    val fpsHistory = remember { mutableStateListOf<Float>() }

    // CPU/内存/GPU 采样协程：1.5s 一次
    LaunchedEffect(Unit) {
        while (true) {
            val s = withContext(Dispatchers.IO) {
                PerfSample(
                    cpu = runtime.getCpuLoad(),
                    mem = runtime.getMemoryLoad(),
                    memUsed = runtime.getAvailableMemoryMb(), // 已用 = 总量 - 可用
                    memTotal = runtime.getTotalMemoryMb(),
                    gpuName = runtime.getPrimaryGpuName(),
                    gpuVram = runtime.getPrimaryGpuVramMb()
                )
            }
            cpuLoad = s.cpu
            memLoad = s.mem
            memUsedMb = s.memTotal - s.memUsed // 已用 = 总量 - 可用
            memTotalMb = s.memTotal
            gpuName = s.gpuName
            gpuVramMb = s.gpuVram

            cpuHistory.add((s.cpu * 100).toFloat())
            memHistory.add((s.mem * 100).toFloat())
            while (cpuHistory.size > 30) cpuHistory.removeAt(0)
            while (memHistory.size > 30) memHistory.removeAt(0)
            kotlinx.coroutines.delay(1500)
        }
    }

    // FPS 统计：withFrameNanos 每秒统计一次帧数
    LaunchedEffect(Unit) {
        var frameCount = 0
        var lastSecondNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastSecondNanos == 0L) {
                    lastSecondNanos = now
                    return@withFrameNanos
                }
                frameCount++
                val elapsed = now - lastSecondNanos
                if (elapsed >= 1_000_000_000L) {
                    fps = (frameCount * 1_000_000_000L / elapsed).toInt()
                    fpsHistory.add(fps.toFloat())
                    while (fpsHistory.size > 30) fpsHistory.removeAt(0)
                    frameCount = 0
                    lastSecondNanos = now
                }
            }
        }
    }

    // 深色半透明背景
    Surface(
        color = Color(0xCC101014),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // 标题行：可拖动 + 关闭按钮
            Row(
                Modifier.fillMaxWidth().hudDraggable(awtWindow),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "性能监控",
                    color = Color(0xFFE0E0E0),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(
                        Icons.Filled.Close, "关闭",
                        tint = Color(0xFFB0B0B0),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // 指标行
            if ("CPU" in enabledMetrics) {
                MetricRow(
                    label = "CPU",
                    value = "%.0f%%".format(cpuLoad * 100),
                    history = cpuHistory,
                    color = Color(0xFF64B5F6)
                )
                Spacer(Modifier.height(4.dp))
            }
            if ("MEM" in enabledMetrics) {
                MetricRow(
                    label = "MEM",
                    value = "%.0f%%".format(memLoad * 100),
                    subValue = "%.1f/%.1fG".format(memUsedMb / 1024.0, memTotalMb / 1024.0),
                    history = memHistory,
                    color = Color(0xFF81C784)
                )
                Spacer(Modifier.height(4.dp))
            }
            if ("GPU" in enabledMetrics) {
                val gpuText = if (gpuVramMb > 0) "%.1f/%.1fG".format(gpuVramMb / 1024.0, gpuVramMb / 1024.0) else "N/A"
                MetricRow(
                    label = "GPU",
                    value = gpuText,
                    history = emptyList(),
                    color = Color(0xFFBA68C8),
                    subValue = gpuName.take(20)
                )
                Spacer(Modifier.height(4.dp))
            }
            if ("FPS" in enabledMetrics) {
                MetricRow(
                    label = "FPS",
                    value = fps.toString(),
                    history = fpsHistory,
                    color = Color(0xFFFFD54F)
                )
            }
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    history: List<Float>,
    color: Color,
    subValue: String? = null
) {
    Row(
        Modifier.fillMaxWidth().height(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(30.dp)
        )
        Text(
            value,
            color = Color(0xFFEEEEEE),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(72.dp)
        )
        if (history.isNotEmpty()) {
            MiniSparkline(
                values = history,
                color = color,
                modifier = Modifier.weight(1f).height(18.dp)
            )
        } else if (subValue != null) {
            Text(
                subValue,
                color = Color(0xFF909090),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MiniSparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val maxVal = (values.maxOrNull() ?: 100f).coerceAtLeast(1f)
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - (v / maxVal) * h * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5f)
        )
    }
}

/**
 * HUD 窗口拖动：用屏幕绝对坐标差值更新窗口位置（复用主窗口拖拽思路）。
 */
private fun Modifier.hudDraggable(awtWindow: java.awt.Window): Modifier = this.pointerInput(Unit) {
    var initialMouse: Point? = null
    var initialWindowLoc: Point? = null
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val mouseLocation = MouseInfo.getPointerInfo()?.location
            if (event.buttons.isPrimaryPressed) {
                if (initialMouse == null && mouseLocation != null) {
                    initialMouse = mouseLocation
                    initialWindowLoc = Point(awtWindow.x, awtWindow.y)
                }
                val im = initialMouse
                val iwl = initialWindowLoc
                if (event.type == PointerEventType.Move && im != null && iwl != null && mouseLocation != null) {
                    awtWindow.setLocation(iwl.x + mouseLocation.x - im.x, iwl.y + mouseLocation.y - im.y)
                }
            } else {
                initialMouse = null
                initialWindowLoc = null
            }
        }
    }
}

private data class PerfSample(
    val cpu: Double,
    val mem: Double,
    val memUsed: Long,   // 可用内存（MB）
    val memTotal: Long,  // 总内存（MB）
    val gpuName: String,
    val gpuVram: Long
)
