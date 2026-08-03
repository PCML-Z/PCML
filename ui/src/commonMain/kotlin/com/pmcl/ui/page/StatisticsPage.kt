package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.runtime.RuntimeManager
import com.pmcl.core.stats.PlayTimeTracker
import com.pmcl.ui.animation.TypewriterTitle
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 统计页：左侧实时设备性能负载 + 右侧游玩统计。
 * 游玩数据采用 Apple Fitness / Screen Time 风格卡片：大数字、弱标签、圆角分组。
 */

/** Apple 风格主卡片圆角 */
private val AppleCardShape = RoundedCornerShape(20.dp)
/** 指标小方块圆角 */
private val AppleTileShape = RoundedCornerShape(16.dp)
/** 分段控件圆角 */
private val AppleSegmentShape = RoundedCornerShape(10.dp)

// ===== 动画工具函数 =====

/** 卡片入场动画进度（0→1），带可选延迟实现错峰效果 */
@Composable
private fun rememberEntranceProgress(
    delayMs: Int = 0,
    durationMs: Int = 500
): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return progress.asState()
}

/** 入场动画 Modifier：淡入 + 从下方滑入 */
private fun Modifier.entrance(progress: Float, slideDistance: Float = 40f): Modifier =
    this.graphicsLayer {
        val p = progress.coerceIn(0f, 1f)
        alpha = p
        translationY = (1f - p) * slideDistance
    }

/** 数值数据变化时的动画进度（数据刷新时重新触发） */
@Composable
private fun rememberDataAnimationProgress(key: Any?, delayMs: Int = 0, durationMs: Int = 700): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        if (delayMs > 0) delay(delayMs.toLong())
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return progress.asState()
}

/** 数字计数动画文本：从 0 计数到目标值并格式化显示 */
@Composable
private fun CountUpText(
    target: Long,
    modifier: Modifier = Modifier,
    durationMs: Int = 900,
    style: androidx.compose.ui.text.TextStyle? = null,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    format: (Long) -> String
) {
    val animated = remember(target) { Animatable(0f) }
    LaunchedEffect(target) {
        animated.snapTo(0f)
        animated.animateTo(target.toFloat(), tween(durationMs, easing = FastOutSlowInEasing))
    }
    val animatedValue by animated.asState()
    if (style != null) {
        Text(format(animatedValue.toLong()), modifier = modifier, style = style, fontWeight = fontWeight, color = color)
    } else {
        Text(format(animatedValue.toLong()), modifier = modifier, fontWeight = fontWeight, color = color)
    }
}

@Composable
fun StatisticsPage(vm: LauncherViewModel, sectionId: String = "performance") {
    val stats by vm.playTimeStats.collectAsState()
    val dailyStats by vm.dailyStats.collectAsState()
    val days by vm.statsDays.collectAsState()
    val heatmap by vm.heatmap.collectAsState()
    val weekdayDist by vm.weekdayDist.collectAsState()
    val records by vm.records.collectAsState()

    // 进入页面时刷新数据（refresh 内部已吞掉致命类加载/IO 异常，避免 Error 弹窗）
    LaunchedEffect(Unit) {
        try {
            if (stats == null) vm.refreshPlayTimeStats()
        } catch (e: Throwable) {
            System.err.println("[StatisticsPage] 初始化失败: $e")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val sectionTitleKey = when (sectionId) {
            "overview" -> "stats.section.overview"
            "sessions" -> "stats.section.sessions"
            "breakdown" -> "stats.section.breakdown"
            else -> "stats.section.performance"
        }
        TypewriterTitle(I18n.t(sectionTitleKey))

        when (sectionId) {
            "overview" -> {
                OverviewCard(stats, records, entranceDelay = 80)
                if (records != null) {
                    RecordsCard(records!!, entranceDelay = 160)
                }
                DaysSelector(days, onSelect = { vm.setStatsDays(it) }, entranceDelay = 220)
                if (dailyStats.isNotEmpty()) {
                    DailyTrendCard(dailyStats, days, entranceDelay = 280)
                }
                if (heatmap != null) {
                    HeatmapCard(heatmap!!, entranceDelay = 360)
                }
                if (weekdayDist.isNotEmpty()) {
                    WeekdayDistributionCard(weekdayDist, entranceDelay = 440)
                }
                if (stats != null && stats!!.versions.isNotEmpty()) {
                    VersionPieCard(stats!!.versions, entranceDelay = 520)
                }
            }
            "sessions" -> SessionListCard(vm, entranceDelay = 0)
            "breakdown" -> BreakdownCard(vm, entranceDelay = 0)
            else -> RealtimePerformanceCard(entranceDelay = 0)
        }
    }
}

// ===== 实时设备性能负载 =====

/** 性能采样数据 */
private data class PerformanceSample(
    val cpuLoad: Double,
    val memLoad: Double,
    val jvmHeapLoad: Double,
    val jvmHeapUsedMb: Long,
    val jvmHeapAllocatedMb: Long,
    val jvmHeapMaxMb: Long,
    val threadCount: Int,
    val diskLoad: Double,
    val cpuName: String,
    val cpuPhysicalCores: Int,
    val cpuLogicalCores: Int,
    val totalMemMb: Long,
    val availableMemMb: Long,
    val diskUsedGb: Double,
    val diskTotalGb: Double,
    val netUpKbS: Double,
    val netDownKbS: Double,
    val gpuName: String,
    val gpuVramMb: Long,
    val systemUptimeSec: Long
)

/** 历史峰值记录 */
private data class PeakRecords(
    var cpuPeak: Float = 0f,
    var memPeak: Float = 0f,
    var jvmPeak: Float = 0f,
    var netUpPeak: Double = 0.0,
    var netDownPeak: Double = 0.0
)

/**
 * 实时设备性能负载卡片：CPU、内存、JVM 堆、网络、GPU、磁盘的实时监控。
 * 每 1.5 秒采样一次，最多保留 40 个历史点用于绘制迷你折线图，同时记录峰值。
 */
@Composable
private fun RealtimePerformanceCard(entranceDelay: Int = 0) {
    // NoClassDefFoundError 也要吞掉：运行中覆盖 fat jar 后首次进系统信息页会缺 oshi 嵌套类
    val runtime = remember {
        try {
            RuntimeManager()
        } catch (t: Throwable) {
            System.err.println("[RealtimePerformance] RuntimeManager 初始化失败: ${t.javaClass.name}: ${t.message}")
            null
        }
    }
    var initFailed by remember { mutableStateOf(runtime == null) }

    val cpuHistory = remember { mutableStateListOf<Float>() }
    val memHistory = remember { mutableStateListOf<Float>() }
    val jvmHistory = remember { mutableStateListOf<Float>() }
    val netHistory = remember { mutableStateListOf<Float>() }

    var sample by remember { mutableStateOf<PerformanceSample?>(null) }
    val peaks = remember { PeakRecords() }

    LaunchedEffect(runtime) {
        val rm = runtime ?: return@LaunchedEffect
        while (true) {
            try {
                val s = withContext(Dispatchers.IO) {
                    val cpu = rm.getCpuLoad()
                    val mem = rm.getMemoryLoad()
                    val jvmHeapLoad = rm.getJvmHeapLoad()
                    val diskUsage = rm.getPmclDiskUsage()
                    val netSpeed = rm.getNetworkSpeedKbS()
                    PerformanceSample(
                        cpuLoad = cpu,
                        memLoad = mem,
                        jvmHeapLoad = jvmHeapLoad,
                        jvmHeapUsedMb = rm.getJvmHeapUsedMb(),
                        jvmHeapAllocatedMb = rm.getJvmHeapAllocatedMb(),
                        jvmHeapMaxMb = rm.getJvmHeapMaxMb(),
                        threadCount = rm.getJvmThreadCount(),
                        diskLoad = diskUsage?.get(2) ?: 0.0,
                        cpuName = rm.getCpuName(),
                        cpuPhysicalCores = rm.getCpuPhysicalCores(),
                        cpuLogicalCores = rm.getCpuLogicalCores(),
                        totalMemMb = rm.getTotalMemoryMb(),
                        availableMemMb = rm.getAvailableMemoryMb(),
                        diskUsedGb = diskUsage?.get(0) ?: 0.0,
                        diskTotalGb = diskUsage?.get(1) ?: 0.0,
                        netUpKbS = netSpeed[0],
                        netDownKbS = netSpeed[1],
                        gpuName = rm.getPrimaryGpuName(),
                        gpuVramMb = rm.getPrimaryGpuVramMb(),
                        systemUptimeSec = rm.getSystemUptimeSeconds()
                    )
                }
                sample = s

                // 更新峰值
                val cpuPct = (s.cpuLoad * 100).toFloat()
                val memPct = (s.memLoad * 100).toFloat()
                val jvmPct = (s.jvmHeapLoad * 100).toFloat()
                if (cpuPct > peaks.cpuPeak) peaks.cpuPeak = cpuPct
                if (memPct > peaks.memPeak) peaks.memPeak = memPct
                if (jvmPct > peaks.jvmPeak) peaks.jvmPeak = jvmPct
                if (s.netUpKbS > peaks.netUpPeak) peaks.netUpPeak = s.netUpKbS
                if (s.netDownKbS > peaks.netDownPeak) peaks.netDownPeak = s.netDownKbS

                cpuHistory.add(cpuPct)
                memHistory.add(memPct)
                jvmHistory.add(jvmPct)
                netHistory.add((s.netUpKbS + s.netDownKbS).toFloat())
                while (cpuHistory.size > 40) cpuHistory.removeAt(0)
                while (memHistory.size > 40) memHistory.removeAt(0)
                while (jvmHistory.size > 40) jvmHistory.removeAt(0)
                while (netHistory.size > 40) netHistory.removeAt(0)
            } catch (t: Throwable) {
                System.err.println("[RealtimePerformance] 采样失败: ${t.javaClass.name}: ${t.message}")
                initFailed = true
                break
            }

            delay(1500)
        }
    }

    AppleStatCard(entranceDelay = entranceDelay) {
        AppleEyebrow(I18n.t("stats.realtime_performance"))
        Spacer(Modifier.height(16.dp))

        if (initFailed && sample == null) {
            Text(
                I18n.t("stats.system_info_unavailable"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else if (sample == null) {
            Box(
                Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        sample?.let { s ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ApplePerfMetric(
                    label = "CPU",
                    valueText = "${"%.0f".format(s.cpuLoad * 100)}%",
                    caption = "${s.cpuPhysicalCores}P/${s.cpuLogicalCores}L · ${I18n.t("stats.peak", "${"%.0f".format(peaks.cpuPeak)}%")}",
                    detail = s.cpuName,
                    progress = s.cpuLoad.toFloat(),
                    history = cpuHistory,
                    accent = Color(0xFF007AFF),
                    modifier = Modifier.weight(1f)
                )
                ApplePerfMetric(
                    label = I18n.t("stats.system_memory"),
                    valueText = "${"%.0f".format(s.memLoad * 100)}%",
                    caption = "${((s.totalMemMb - s.availableMemMb) / 1024.0).format(1)} / ${(s.totalMemMb / 1024.0).format(1)} GB",
                    detail = I18n.t("stats.peak", "${"%.0f".format(peaks.memPeak)}%"),
                    progress = s.memLoad.toFloat(),
                    history = memHistory,
                    accent = Color(0xFF34C759),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ApplePerfMetric(
                    label = I18n.t("stats.jvm_heap"),
                    valueText = "${"%.0f".format(s.jvmHeapLoad * 100)}%",
                    caption = "${s.jvmHeapUsedMb} / ${s.jvmHeapAllocatedMb} MB",
                    detail = "${I18n.t("stats.threads", s.threadCount)} · ${I18n.t("stats.peak", "${"%.0f".format(peaks.jvmPeak)}%")}",
                    progress = s.jvmHeapLoad.toFloat(),
                    history = jvmHistory,
                    accent = Color(0xFFFF9F0A),
                    modifier = Modifier.weight(1f)
                )
                ApplePerfMetric(
                    label = I18n.t("stats.network"),
                    valueText = "up ${formatNetSpeed(s.netUpKbS)}",
                    caption = "down ${formatNetSpeed(s.netDownKbS)}",
                    detail = I18n.t("stats.net_peak", formatNetSpeed(peaks.netUpPeak), formatNetSpeed(peaks.netDownPeak)),
                    progress = ((s.netUpKbS + s.netDownKbS) / 1024.0).coerceIn(0.0, 1.0).toFloat(),
                    history = netHistory,
                    accent = Color(0xFF64D2FF),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))
            AppleInsetList {
                ApplePerfInsetRow(
                    label = I18n.t("stats.gpu"),
                    value = if (s.gpuVramMb > 0) "${s.gpuVramMb} MB" else "N/A",
                    caption = s.gpuName,
                    progress = -1f,
                    accent = Color(0xFFFF375F)
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 14.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                )
                ApplePerfInsetRow(
                    label = I18n.t("stats.disk"),
                    value = "${"%.0f".format(s.diskLoad * 100)}%",
                    caption = "${"%.1f".format(s.diskUsedGb)} / ${"%.1f".format(s.diskTotalGb)} GB",
                    progress = s.diskLoad.toFloat(),
                    accent = Color(0xFFBF5AF2)
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 14.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                )
                AppleInsetRow(
                    I18n.t("stats.system_uptime"),
                    formatUptime(s.systemUptimeSec),
                    showDivider = false
                )
            }
        }
    }
}

/** 格式化网络速率 */
private fun formatNetSpeed(kbS: Double): String {
    return if (kbS >= 1024) "${"%.1f".format(kbS / 1024)} MB/s" else "${"%.0f".format(kbS)} KB/s"
}

/** 格式化运行时长 */
private fun formatUptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return if (d > 0) "${d}d ${h}h ${m}m" else if (h > 0) "${h}h ${m}m" else "${m}m"
}

/**
 * Apple 风格性能磁贴：弱标签、大数字、圆角进度条、迷你趋势。
 */
@Composable
private fun ApplePerfMetric(
    label: String,
    valueText: String,
    caption: String,
    detail: String,
    progress: Float,
    history: List<Float>?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "applePerfProgress"
    )
    Surface(
        modifier = modifier,
        shape = AppleTileShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                valueText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = (-0.6).sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (progress >= 0f) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent.copy(alpha = 0.16f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedProgress)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent)
                    )
                }
            }
            if (history != null && history.size > 1) {
                Spacer(Modifier.height(8.dp))
                MiniLineChart(history, accent)
            }
        }
    }
}

/** 内嵌列表中的性能行（磁盘进度 / GPU 信息） */
@Composable
private fun ApplePerfInsetRow(
    label: String,
    value: String,
    caption: String,
    progress: Float,
    accent: Color
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "appleInsetProgress"
    )
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = (-0.2).sp
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (progress >= 0f) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent.copy(alpha = 0.16f))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
            }
        }
    }
}

/** 迷你折线图 */
@Composable
private fun MiniLineChart(data: List<Float>, color: Color) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
    ) {
        val w = size.width
        val h = size.height
        if (data.size < 2) return@Canvas
        val maxVal = data.max().coerceAtLeast(1f)
        val stepX = w / (data.size - 1)
        val points = data.mapIndexed { i, v ->
            val y = h - (h * (v / maxVal)).coerceIn(0f, h)
            Offset(i * stepX, y)
        }
        val fillPath = Path().apply {
            moveTo(points.first().x, h)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, h)
            close()
        }
        drawPath(path = fillPath, color = color.copy(alpha = 0.14f))
        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        drawPath(path = linePath, color = color, style = Stroke(width = 2f))
    }
}


private fun Double.format(digits: Int) = "%.${digits}f".format(this)

// ===== Apple 风格卡片组件 =====

@Composable
private fun AppleStatCard(
    modifier: Modifier = Modifier,
    entranceDelay: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
    Card(
        modifier = modifier.fillMaxWidth().entrance(entranceProgress),
        shape = AppleCardShape,
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            content = content
        )
    }
}

@Composable
private fun AppleEyebrow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.2.sp
    )
}

@Composable
private fun AppleMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = AppleTileShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = (-0.4).sp
            )
        }
    }
}

/** Apple Fitness 风格主时长：大数字 + 单位分行，提升可读性 */
@Composable
private fun AppleHeroDuration(millis: Long) {
    val parts = remember(millis) { heroDurationParts(millis) }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEach { (num, unit) ->
            Row(verticalAlignment = Alignment.Bottom) {
                CountUpText(
                    target = num,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 44.sp,
                        letterSpacing = (-1.2).sp,
                        lineHeight = 48.sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    format = { it.toString() }
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
        if (parts.isEmpty()) {
            Text(
                "0",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, letterSpacing = (-1.2).sp),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 将毫秒拆成 (数值, 单位) 列表，便于大数字展示 */
private fun heroDurationParts(millis: Long): List<Pair<Long, String>> {
    if (millis <= 0) return emptyList()
    val totalSec = millis / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return buildList {
        if (h > 0) add(h to I18n.t("stats.unit_hour"))
        if (m > 0 || h == 0L) add(m to I18n.t("stats.unit_minute"))
    }
}

@Composable
private fun AppleInsetList(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = AppleTileShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(content = content)
    }
}

@Composable
private fun AppleInsetRow(
    label: String,
    value: String,
    showDivider: Boolean = true
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 14.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
            )
        }
    }
}

// ===== 总览卡片（Apple Fitness 英雄卡） =====

@Composable
private fun OverviewCard(
    stats: PlayTimeTracker.OverallStat?,
    records: PlayTimeTracker.RecordsStat?,
    entranceDelay: Int = 0
) {
    val total = stats?.totalDuration ?: 0L
    val sessions = (stats?.totalSessions ?: 0).toLong()
    val avgDaily = if (stats != null && stats.daily.isNotEmpty()) {
        stats.totalDuration / stats.daily.size
    } else 0L
    val streak = records?.currentStreakDays ?: 0

    AppleStatCard(entranceDelay = entranceDelay) {
        AppleEyebrow(I18n.t("stats.overview"))
        Spacer(Modifier.height(10.dp))
        AppleHeroDuration(total)
        Spacer(Modifier.height(4.dp))
        Text(
            I18n.t("stats.total_duration"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppleMetricTile(
                label = I18n.t("stats.total_sessions"),
                value = "$sessions",
                modifier = Modifier.weight(1f)
            )
            AppleMetricTile(
                label = I18n.t("stats.daily_avg"),
                value = PlayTimeTracker.formatDurationShort(avgDaily),
                modifier = Modifier.weight(1f)
            )
            AppleMetricTile(
                label = I18n.t("stats.current_streak"),
                value = I18n.t("stats.days_count", streak),
                modifier = Modifier.weight(1f),
                accent = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

// ===== 游玩记录卡片（2×2 指标方块 + 内嵌列表） =====

@Composable
private fun RecordsCard(records: PlayTimeTracker.RecordsStat, entranceDelay: Int = 0) {
    AppleStatCard(entranceDelay = entranceDelay) {
        AppleEyebrow(I18n.t("stats.play_records"))
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppleMetricTile(
                label = I18n.t("stats.longest_session"),
                value = records.longestSession?.let {
                    PlayTimeTracker.formatDurationShort(it.duration)
                } ?: "—",
                modifier = Modifier.weight(1f)
            )
            AppleMetricTile(
                label = I18n.t("stats.longest_streak"),
                value = I18n.t("stats.days_count", records.longestStreakDays),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppleMetricTile(
                label = I18n.t("stats.total_days"),
                value = I18n.t("stats.days_count", records.totalDays),
                modifier = Modifier.weight(1f)
            )
            AppleMetricTile(
                label = I18n.t("stats.most_played_hour"),
                value = records.mostPlayedHour.ifEmpty { "—" },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))
        AppleInsetList {
            AppleInsetRow(
                I18n.t("stats.first_play"),
                records.firstPlayDate.ifEmpty { "—" }
            )
            AppleInsetRow(
                I18n.t("stats.longest_version"),
                records.longestSession?.version ?: "—"
            )
            AppleInsetRow(
                I18n.t("stats.record_date"),
                records.longestSession?.let {
                    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(it.start))
                } ?: "—",
                showDivider = false
            )
        }
    }
}

// ===== 天数分段选择器（Apple Segmented Control） =====

@Composable
private fun DaysSelector(selected: Int, onSelect: (Int) -> Unit, entranceDelay: Int = 0) {
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 400)
    Surface(
        modifier = Modifier.fillMaxWidth().entrance(entranceProgress, slideDistance = 20f),
        shape = AppleSegmentShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf(7, 14, 30).forEach { days ->
                val isSelected = selected == days
                val bg by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = tween(180),
                    label = "segment"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.15f + 0.85f * bg)
                        )
                        .clickable { onSelect(days) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        I18n.t("stats.recent_days", days),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ===== 每日趋势折线图（支持切换时长/会话数 + 悬浮提示） =====

@Composable
private fun DailyTrendCard(dailyStats: List<PlayTimeTracker.DailyStat>, days: Int, entranceDelay: Int = 0) {
    var showSessions by remember { mutableStateOf(false) }
    val periodTotal = remember(dailyStats, showSessions) {
        if (showSessions) dailyStats.sumOf { it.sessionCount.toLong() }
        else dailyStats.sumOf { it.totalDuration }
    }
    AppleStatCard(entranceDelay = entranceDelay) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AppleEyebrow(I18n.t("stats.daily_trend"))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (showSessions) "$periodTotal ${I18n.t("common.times")}"
                    else PlayTimeTracker.formatDuration(periodTotal),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp
                )
                Text(
                    I18n.t("stats.recent_days", days),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = AppleSegmentShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(false to I18n.t("stats.duration"), true to I18n.t("stats.count")).forEach { (sessions, label) ->
                        val on = showSessions == sessions
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.surface
                                    else Color.Transparent
                                )
                                .clickable { showSessions = sessions }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (on) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DailyTrendChart(dailyStats, showSessions)
    }
}

@Composable
private fun DailyTrendChart(dailyStats: List<PlayTimeTracker.DailyStat>, showSessions: Boolean) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    val maxValue = remember(dailyStats, showSessions) {
        if (showSessions) dailyStats.maxOfOrNull { it.sessionCount }?.toLong() ?: 0L
        else dailyStats.maxOfOrNull { it.totalDuration } ?: 0L
    }

    // 折线绘制动画进度（数据或模式切换时重新触发）
    val drawProgressState = remember(dailyStats, showSessions) { Animatable(0f) }
    LaunchedEffect(dailyStats, showSessions) {
        drawProgressState.snapTo(0f)
        drawProgressState.animateTo(1f, tween(900, easing = LinearOutSlowInEasing))
    }
    val drawProgress by drawProgressState.asState()

    var hoverIndex by remember { mutableStateOf(-1) }

    Box {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(180.dp)
                .pointerInput(dailyStats, showSessions) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Exit) {
                                hoverIndex = -1; continue
                            }
                            val pos = event.changes.firstOrNull()?.position ?: continue
                            val padding = 40f
                            val chartWidth = size.width - padding * 2
                            val stepX = if (dailyStats.size > 1) chartWidth / (dailyStats.size - 1) else 0f
                            if (stepX > 0) {
                                val idx = ((pos.x - padding) / stepX).roundToInt().coerceIn(0, dailyStats.lastIndex)
                                hoverIndex = idx
                            }
                        }
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val padding = 40f
            val chartWidth = canvasWidth - padding * 2
            val chartHeight = canvasHeight - padding * 2

            if (dailyStats.isEmpty() || maxValue == 0L) {
                drawLine(trackColor, Offset(padding, canvasHeight - padding),
                    Offset(canvasWidth - padding, canvasHeight - padding), strokeWidth = 2f)
                return@Canvas
            }

            // 网格线（按进度渐入）
            for (i in 0..4) {
                val y = padding + chartHeight * (1f - i / 4f)
                drawLine(trackColor.copy(alpha = 0.3f * drawProgress), Offset(padding, y),
                    Offset(canvasWidth - padding, y), strokeWidth = 1f)
            }

            val stepX = if (dailyStats.size > 1) chartWidth / (dailyStats.size - 1) else 0f
            val points = dailyStats.mapIndexed { i, stat ->
                val x = padding + i * stepX
                val v = if (showSessions) stat.sessionCount.toLong() else stat.totalDuration
                val yRatio = if (maxValue > 0) v.toFloat() / maxValue.toFloat() else 0f
                Offset(x, padding + chartHeight * (1f - yRatio))
            }

            // 折线 + 填充（用 PathMeasure 实现从左到右绘制动画）
            if (points.size > 1) {
                val fullPath = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                // 用 PathMeasure 截取部分路径实现绘制动画
                val measure = PathMeasure().apply { setPath(fullPath, false) }
                val subPath = Path()
                measure.getSegment(0f, measure.length * drawProgress, subPath, true)
                drawPath(subPath, primaryColor, style = Stroke(width = 3f))

                // 填充区域也跟随进度
                val visibleCount = (points.size * drawProgress).toInt().coerceIn(1, points.size)
                if (visibleCount > 1) {
                    val fillPath = Path().apply {
                        moveTo(points[0].x, canvasHeight - padding)
                        lineTo(points[0].x, points[0].y)
                        for (i in 1 until visibleCount) lineTo(points[i].x, points[i].y)
                        lineTo(points[visibleCount - 1].x, canvasHeight - padding); close()
                    }
                    drawPath(fillPath, primaryColor.copy(alpha = 0.15f * drawProgress))
                }
            }

            // 数据点（按进度依次出现）
            points.forEachIndexed { i, point ->
                val pointProgress = (drawProgress * points.size - i).coerceIn(0f, 1f)
                if (pointProgress > 0f) {
                    drawCircle(
                        color = if (i == hoverIndex) Color(0xFFFF5722) else primaryColor,
                        radius = (if (i == hoverIndex) 6f else 4f) * pointProgress,
                        center = point
                    )
                }
            }

            // 悬浮提示竖线
            if (hoverIndex in points.indices) {
                val p = points[hoverIndex]
                drawLine(
                    Color(0xFFFF5722).copy(alpha = 0.5f),
                    Offset(p.x, padding), Offset(p.x, canvasHeight - padding), strokeWidth = 1f
                )
            }
        }

        // 悬浮提示文字
        if (hoverIndex in dailyStats.indices) {
            val stat = dailyStats[hoverIndex]
            val dateStr = try {
                LocalDate.parse(stat.date).format(DateTimeFormatter.ofPattern("MM/dd"))
            } catch (_: Throwable) { stat.date }
            val valueStr = if (showSessions) "${stat.sessionCount} ${I18n.t("common.times")}"
                else PlayTimeTracker.formatDurationShort(stat.totalDuration)
            Surface(
                modifier = Modifier.padding(top = 4.dp, start = 48.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF424242)
            ) {
                Text("$dateStr  $valueStr", color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }

    // X 轴标签
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, start = 40.dp, end = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val labelInterval = when {
            dailyStats.size <= 7 -> 1
            dailyStats.size <= 14 -> 2
            else -> 5
        }
        dailyStats.forEachIndexed { i, stat ->
            if (i % labelInterval == 0 || i == dailyStats.lastIndex) {
                val date = try {
                    LocalDate.parse(stat.date).format(DateTimeFormatter.ofPattern("MM/dd"))
                } catch (_: Throwable) { stat.date.substring(minOf(5, stat.date.length)) }
                Text(date, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else { Text(" ", fontSize = 9.sp) }
        }
    }
}

// ===== 时段热力图 =====

@Composable
private fun HeatmapCard(heatmap: PlayTimeTracker.HeatmapStat, entranceDelay: Int = 0) {
    // 单元格错峰渐入动画
    val cellProgress = remember(heatmap) { Animatable(0f) }
    LaunchedEffect(heatmap) {
        delay(entranceDelay.toLong())
        cellProgress.snapTo(0f)
        cellProgress.animateTo(1f, tween(800, easing = LinearOutSlowInEasing))
    }
    val cellProgressValue by cellProgress.asState()
    AppleStatCard(entranceDelay = entranceDelay) {
        AppleEyebrow(I18n.t("stats.time_heatmap"))
        Spacer(Modifier.height(4.dp))
        Text(
            I18n.t("stats.heatmap_desc"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        val maxV = heatmap.maxValue.coerceAtLeast(1)
        val primaryColor = MaterialTheme.colorScheme.primary
        val emptyColor = MaterialTheme.colorScheme.surfaceVariant
        val days = listOf(
            I18n.t("stats.weekday_short_mon"),
            I18n.t("stats.weekday_short_tue"),
            I18n.t("stats.weekday_short_wed"),
            I18n.t("stats.weekday_short_thu"),
            I18n.t("stats.weekday_short_fri"),
            I18n.t("stats.weekday_short_sat"),
            I18n.t("stats.weekday_short_sun")
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.width(20.dp))
            (0..23).forEach { h ->
                if (h % 3 == 0) {
                    Text(
                        "$h",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        heatmap.durations.forEachIndexed { dayIdx, hours ->
            val rowAlpha = (cellProgressValue * 8 - dayIdx).coerceIn(0f, 1f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .graphicsLayer { alpha = rowAlpha },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    days[dayIdx],
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp)
                )
                hours.forEach { duration ->
                    val alpha = if (duration > 0) {
                        0.12f + 0.88f * (duration.toFloat() / maxV.toFloat())
                    } else 0f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(12.dp)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (duration > 0) primaryColor.copy(alpha = alpha)
                                else emptyColor.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                I18n.t("stats.heatmap_less"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            (1..5).forEach { i ->
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(primaryColor.copy(alpha = 0.12f + 0.18f * i))
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                I18n.t("stats.heatmap_more"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== 周几分布柱状图 =====

@Composable
private fun WeekdayDistributionCard(weekdayDist: List<PlayTimeTracker.WeekdayStat>, entranceDelay: Int = 0) {
    AppleStatCard(entranceDelay = entranceDelay) {
        AppleEyebrow(I18n.t("stats.weekday_distribution"))
        Spacer(Modifier.height(16.dp))
        val maxDuration = weekdayDist.maxOfOrNull { it.totalDuration } ?: 0L
        val primaryColor = MaterialTheme.colorScheme.primary
        weekdayDist.forEachIndexed { idx, stat ->
            WeekdayBar(stat, maxDuration, primaryColor, entranceDelay + idx * 70)
        }
    }
}

@Composable
private fun WeekdayBar(
    stat: PlayTimeTracker.WeekdayStat,
    maxDuration: Long,
    primaryColor: Color,
    barDelay: Int
) {
    // 柱子增长动画（错峰）
    val barProgress = remember { Animatable(0f) }
    LaunchedEffect(stat) {
        delay(barDelay.toLong())
        barProgress.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }
    val barProgressValue by barProgress.asState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stat.dayName, style = MaterialTheme.typography.labelMedium,
             modifier = Modifier.width(32.dp), fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                val ratio = if (maxDuration > 0) stat.totalDuration.toFloat() / maxDuration.toFloat() else 0f
                val w = size.width * ratio * barProgressValue
                drawRect(color = primaryColor.copy(alpha = 0.15f))
                drawRect(color = primaryColor, size = Size(w, size.height))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            PlayTimeTracker.formatDurationShort(stat.totalDuration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

// ===== 版本分布饼图 =====

@Composable
private fun VersionPieCard(versions: List<PlayTimeTracker.VersionStat>, entranceDelay: Int = 0) {
    // 饼图扫描动画
    val sweepProgress = remember(versions) { Animatable(0f) }
    LaunchedEffect(versions) {
        delay(entranceDelay.toLong())
        sweepProgress.snapTo(0f)
        sweepProgress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val sweepProgressValue by sweepProgress.asState()
    AppleStatCard(entranceDelay = entranceDelay) {
        AppleEyebrow(I18n.t("stats.version_dist"))
        Spacer(Modifier.height(16.dp))
        val topVersions = versions.take(6)
        val otherDuration = versions.drop(6).sumOf { it.totalDuration }
        val pieData = topVersions.map { it to it.totalDuration } +
            (if (otherDuration > 0) listOf(null to otherDuration) else emptyList())
        val total = pieData.sumOf { it.second }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(120.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    if (total <= 0) return@Canvas
                    var startAngle = -90f
                    pieData.forEachIndexed { i, pair ->
                        val sweep = 360f * pair.second.toFloat() / total.toFloat() * sweepProgressValue
                        val color = PIE_COLORS[i % PIE_COLORS.size]
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = true
                        )
                        startAngle += 360f * pair.second.toFloat() / total.toFloat()
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                pieData.forEachIndexed { i, pair ->
                    val version = pair.first?.version ?: I18n.t("stats.other")
                    val pct = if (total > 0) pair.second.toFloat() / total.toFloat() * 100 else 0f
                    val legendAlpha = (sweepProgressValue * pieData.size - i).coerceIn(0f, 1f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(vertical = 3.dp)
                            .graphicsLayer { alpha = legendAlpha }
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(PIE_COLORS[i % PIE_COLORS.size])
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            version,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${"%.0f".format(pct)}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private val PIE_COLORS = listOf(
    Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFFFF9800),
    Color(0xFF9C27B0), Color(0xFF00BCD4), Color(0xFFE91E63),
    Color(0xFF607D8B)
)

// ===== 会话详情列表 =====

@Composable
private fun SessionListCard(vm: LauncherViewModel, entranceDelay: Int = 0) {
    var sessions by remember { mutableStateOf<List<PlayTimeTracker.Session>>(emptyList()) }
    var totalCount by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val pageSize = 10
    var currentPage by remember { mutableIntStateOf(0) }
    val ioScope = rememberCoroutineScope()

    LaunchedEffect(expanded) {
        if (expanded && sessions.isEmpty()) {
            withContext(Dispatchers.IO) {
                val tracker = vm.core.playTimeTracker()
                totalCount = tracker.getSessionCount()
                sessions = tracker.getSessions(0, pageSize)
            }
        }
    }

    AppleStatCard(entranceDelay = entranceDelay) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                AppleEyebrow(I18n.t("stats.session_records"))
                Spacer(Modifier.height(2.dp))
                Text(
                    I18n.t("stats.total_count", totalCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(300)) +
                expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(200)) +
                shrinkVertically(animationSpec = tween(200, easing = FastOutSlowInEasing))
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                AppleInsetList {
                    sessions.forEachIndexed { i, session ->
                        SessionRow(session)
                        if (i < sessions.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 14.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                            )
                        }
                    }
                }
                if ((currentPage + 1) * pageSize < totalCount) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = {
                            currentPage++
                            ioScope.launch {
                                val more = withContext(Dispatchers.IO) {
                                    vm.core.playTimeTracker().getSessions(currentPage * pageSize, pageSize)
                                }
                                sessions = sessions + more
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppleTileShape
                    ) {
                        Text(I18n.t("stats.load_more"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: PlayTimeTracker.Session) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.version,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                timeFmt.format(Date(session.start)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            PlayTimeTracker.formatDurationShort(session.duration),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = (-0.2).sp
        )
    }
}

// ===== 细分统计卡片（按模组/世界/服务器/实例维度） =====

/**
 * 细分统计卡片：通过 Tab 切换查看按模组/世界/服务器/实例维度的时长分布。
 * 数据通过 [PlayTimeTracker] 的 getModBreakdown / getWorldBreakdown /
 * getServerBreakdown / getInstanceBreakdown 获取。
 */
@Composable
private fun BreakdownCard(vm: LauncherViewModel, entranceDelay: Int = 0) {
    // 0=模组 1=世界 2=服务器 3=实例
    var tab by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<PlayTimeTracker.BreakdownStat>>(emptyList()) }

    LaunchedEffect(tab) {
        withContext(Dispatchers.IO) {
            val tracker = vm.core.playTimeTracker()
            items = when (tab) {
                0 -> tracker.getModBreakdown(20)
                1 -> tracker.getWorldBreakdown()
                2 -> tracker.getServerBreakdown()
                else -> tracker.getInstanceBreakdown()
            }
        }
    }

    val tabs = listOf(
        I18n.t("stats.breakdown_mod"),
        I18n.t("stats.breakdown_world"),
        I18n.t("stats.breakdown_server"),
        I18n.t("stats.breakdown_instance")
    )

    AppleStatCard(entranceDelay = entranceDelay) {
        AppleEyebrow(I18n.t("stats.breakdown"))
        Spacer(Modifier.height(4.dp))
        Text(
            I18n.t("stats.breakdown_desc"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        Surface(
            shape = AppleSegmentShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ) {
            Row(
                Modifier.padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                tabs.forEachIndexed { i, label ->
                    val on = tab == i
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (on) MaterialTheme.colorScheme.surface
                                else Color.Transparent
                            )
                            .clickable { tab = i }
                            .padding(vertical = 7.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (on) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(88.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    I18n.t("stats.breakdown_empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            val maxDuration = items.maxOfOrNull { it.totalDuration } ?: 0L
            AppleInsetList {
                items.forEachIndexed { i, stat ->
                    BreakdownRow(stat, maxDuration, entranceDelay + i * 40)
                    if (i < items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 14.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    stat: PlayTimeTracker.BreakdownStat,
    maxDuration: Long,
    barDelay: Int
) {
    val barProgress = remember(stat) { Animatable(0f) }
    LaunchedEffect(stat) {
        delay(barDelay.toLong())
        barProgress.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }
    val barProgressValue by barProgress.asState()
    val primaryColor = MaterialTheme.colorScheme.primary
    val lastPlayedStr = if (stat.lastPlayed > 0) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(stat.lastPlayed))
    } else "—"

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stat.displayName.ifEmpty { stat.key },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                PlayTimeTracker.formatDurationShort(stat.totalDuration),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = (-0.2).sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val ratio = if (maxDuration > 0) stat.totalDuration.toFloat() / maxDuration.toFloat() else 0f
                val w = size.width * ratio * barProgressValue
                drawRect(color = primaryColor.copy(alpha = 0.16f))
                drawRect(color = primaryColor, size = Size(w, size.height))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            I18n.t("stats.breakdown_meta", stat.sessionCount, lastPlayedStr),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
