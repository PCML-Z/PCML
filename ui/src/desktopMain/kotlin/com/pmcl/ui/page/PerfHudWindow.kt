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
 * 参考 Apple UHD 风格：FPS 为主，其他指标紧凑横排，配色极简。
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

    // 根据启用指标动态计算窗口高度：FPS 行 + 指标行 + 标题
    val hasFps = "FPS" in enabledMetrics
    val otherMetrics = enabledMetrics.filter { it != "FPS" }
    // 高度：标题 18 + FPS 行 34 + 指标行（每行 14）+ padding
    val heightDp = when {
        hasFps && otherMetrics.isNotEmpty() -> 96.dp
        hasFps -> 78.dp
        else -> 64.dp
    }
    val state = rememberWindowState(width = 168.dp, height = heightDp)

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
                    10.0, 10.0
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

    // FPS 历史折线（最多 40 点）
    val fpsHistory = remember { mutableStateListOf<Float>() }

    // CPU/内存/GPU 采样协程：1.5s 一次
    LaunchedEffect(Unit) {
        while (true) {
            val s = withContext(Dispatchers.IO) {
                PerfSample(
                    cpu = runtime.getCpuLoad(),
                    mem = runtime.getMemoryLoad(),
                    memUsed = runtime.getAvailableMemoryMb(),
                    memTotal = runtime.getTotalMemoryMb(),
                    gpuName = runtime.getPrimaryGpuName(),
                    gpuVram = runtime.getPrimaryGpuVramMb()
                )
            }
            cpuLoad = s.cpu
            memLoad = s.mem
            memUsedMb = s.memTotal - s.memUsed
            memTotalMb = s.memTotal
            gpuName = s.gpuName
            gpuVramMb = s.gpuVram
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
                    while (fpsHistory.size > 40) fpsHistory.removeAt(0)
                    frameCount = 0
                    lastSecondNanos = now
                }
            }
        }
    }

    // 极简配色：主色白，次级灰，单一强调色（FPS 状态点）
    val primary = Color(0xFFF5F5F7)
    val secondary = Color(0xFF8E8E93)
    val accent = Color(0xFF30D158) // Apple green

    Surface(
        color = Color(0xE6000000),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            // 标题行 + 关闭按钮（可拖动）
            Row(
                Modifier.fillMaxWidth().hudDraggable(awtWindow),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "PERF",
                    color = secondary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Close, "关闭",
                        tint = secondary,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }

            // FPS 主显示行
            if ("FPS" in enabledMetrics) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        fps.toString(),
                        color = primary,
                        fontSize = 26.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "FPS",
                        color = secondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    // FPS 状态点：>=55 绿，>=30 黄，<30 红
                    val statusColor = when {
                        fps >= 55 -> accent
                        fps >= 30 -> Color(0xFFFFD60A)
                        else -> Color(0xFFFF453A)
                    }
                    Canvas(Modifier.size(6.dp)) {
                        drawCircle(statusColor)
                    }
                }
                // FPS 迷你折线
                if (fpsHistory.size >= 2) {
                    Spacer(Modifier.height(2.dp))
                    MiniSparkline(
                        values = fpsHistory,
                        color = secondary,
                        modifier = Modifier.fillMaxWidth().height(10.dp)
                    )
                }
            }

            // 其他指标横排（CPU/MEM/GPU）
            val otherMetrics = enabledMetrics.filter { it != "FPS" }
            if (otherMetrics.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                val parts = buildList {
                    if ("CPU" in enabledMetrics) add("CPU %.0f%%".format(cpuLoad * 100))
                    if ("MEM" in enabledMetrics) {
                        add("MEM %.1f/%.1fG".format(memUsedMb / 1024.0, memTotalMb / 1024.0))
                    }
                    if ("GPU" in enabledMetrics) {
                        if (gpuVramMb > 0) add("GPU %.1fG".format(gpuVramMb / 1024.0))
                        else add("GPU N/A")
                    }
                }
                Text(
                    parts.joinToString("  "),
                    color = secondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2
                )
            }
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
        val maxVal = (values.maxOrNull() ?: 60f).coerceAtLeast(1f)
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
            style = Stroke(width = 1f)
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
    val memUsed: Long,
    val memTotal: Long,
    val gpuName: String,
    val gpuVram: Long
)
