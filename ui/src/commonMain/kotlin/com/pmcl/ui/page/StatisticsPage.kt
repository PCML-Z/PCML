package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.shape.CircleShape
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
 * 统计页：左侧实时设备性能负载 + 右侧游玩统计（总览/记录/趋势/热力图/周几/版本/会话）。
 */

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
fun StatisticsPage(vm: LauncherViewModel) {
    val stats by vm.playTimeStats.collectAsState()
    val dailyStats by vm.dailyStats.collectAsState()
    val days by vm.statsDays.collectAsState()
    val heatmap by vm.heatmap.collectAsState()
    val weekdayDist by vm.weekdayDist.collectAsState()
    val records by vm.records.collectAsState()

    // 进入页面时刷新数据
    LaunchedEffect(Unit) { if (stats == null) vm.refreshPlayTimeStats() }

    Row(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ===== 左栏：实时设备性能负载 =====
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RealtimePerformanceCard(entranceDelay = 0)
        }

        // ===== 右栏：游玩统计 =====
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 总览卡片
            OverviewCard(stats, entranceDelay = 80)

            // 游玩记录卡片（极值）
            if (records != null) {
                RecordsCard(records!!, entranceDelay = 160)
            }

            // 天数选择器
            DaysSelector(days, onSelect = { vm.setStatsDays(it) }, entranceDelay = 240)

            // 每日趋势折线图（支持切换时长/会话数）
            if (dailyStats.isNotEmpty()) {
                DailyTrendCard(dailyStats, days, entranceDelay = 320)
            }

            // 时段热力图
            if (heatmap != null) {
                HeatmapCard(heatmap!!, entranceDelay = 400)
            }

            // 周几分布
            if (weekdayDist.isNotEmpty()) {
                WeekdayDistributionCard(weekdayDist, entranceDelay = 480)
            }

            // 版本分布饼图
            if (stats != null && stats!!.versions.isNotEmpty()) {
                VersionPieCard(stats!!.versions, entranceDelay = 560)
            }

            // 会话详情列表
            SessionListCard(vm, entranceDelay = 640)

            // 细分统计（按模组/世界/服务器/实例维度）
            BreakdownCard(vm, entranceDelay = 720)
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
    val runtime = remember { RuntimeManager() }
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)

    val cpuHistory = remember { mutableStateListOf<Float>() }
    val memHistory = remember { mutableStateListOf<Float>() }
    val jvmHistory = remember { mutableStateListOf<Float>() }
    val netHistory = remember { mutableStateListOf<Float>() }

    var sample by remember { mutableStateOf<PerformanceSample?>(null) }
    val peaks = remember { PeakRecords() }

    LaunchedEffect(Unit) {
        while (true) {
            val s = withContext(Dispatchers.IO) {
                val cpu = runtime.getCpuLoad()
                val mem = runtime.getMemoryLoad()
                val jvmHeapLoad = runtime.getJvmHeapLoad()
                val diskUsage = runtime.getPmclDiskUsage()
                val netSpeed = runtime.getNetworkSpeedKbS()
                PerformanceSample(
                    cpuLoad = cpu,
                    memLoad = mem,
                    jvmHeapLoad = jvmHeapLoad,
                    jvmHeapUsedMb = runtime.getJvmHeapUsedMb(),
                    jvmHeapAllocatedMb = runtime.getJvmHeapAllocatedMb(),
                    jvmHeapMaxMb = runtime.getJvmHeapMaxMb(),
                    threadCount = runtime.getJvmThreadCount(),
                    diskLoad = diskUsage?.get(2) ?: 0.0,
                    cpuName = runtime.getCpuName(),
                    cpuPhysicalCores = runtime.getCpuPhysicalCores(),
                    cpuLogicalCores = runtime.getCpuLogicalCores(),
                    totalMemMb = runtime.getTotalMemoryMb(),
                    availableMemMb = runtime.getAvailableMemoryMb(),
                    diskUsedGb = diskUsage?.get(0) ?: 0.0,
                    diskTotalGb = diskUsage?.get(1) ?: 0.0,
                    netUpKbS = netSpeed[0],
                    netDownKbS = netSpeed[1],
                    gpuName = runtime.getPrimaryGpuName(),
                    gpuVramMb = runtime.getPrimaryGpuVramMb(),
                    systemUptimeSec = runtime.getSystemUptimeSeconds()
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

            delay(1500)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().entrance(entranceProgress),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(I18n.t("stats.realtime_performance"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                val infiniteTransition = rememberInfiniteTransition(label = "perfPulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
                    label = "pulse"
                )
                Canvas(Modifier.size(8.dp)) { drawCircle(color = Color(0xFF4CAF50).copy(alpha = pulseAlpha)) }
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("stats.realtime"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(12.dp))

            sample?.let { s ->
                PerformanceRow(
                    icon = Icons.Filled.Speed, label = "CPU",
                    valueText = "${"%.1f".format(s.cpuLoad * 100)}%",
                    subText = "${s.cpuName}  ·  ${I18n.t("stats.cpu_cores", s.cpuPhysicalCores, s.cpuLogicalCores)}  ·  ${I18n.t("stats.peak", "${"%.0f".format(peaks.cpuPeak)}%")}",
                    progress = s.cpuLoad.toFloat(), history = cpuHistory, color = Color(0xFF2196F3)
                )
                Spacer(Modifier.height(14.dp))

                PerformanceRow(
                    icon = Icons.Filled.Memory, label = I18n.t("stats.system_memory"),
                    valueText = "${"%.1f".format(s.memLoad * 100)}%",
                    subText = "${((s.totalMemMb - s.availableMemMb) / 1024.0).format(1)} / ${(s.totalMemMb / 1024.0).format(1)} GB  ·  ${I18n.t("stats.peak", "${"%.0f".format(peaks.memPeak)}%")}",
                    progress = s.memLoad.toFloat(), history = memHistory, color = Color(0xFF4CAF50)
                )
                Spacer(Modifier.height(14.dp))

                PerformanceRow(
                    icon = Icons.Filled.DeveloperBoard, label = I18n.t("stats.jvm_heap"),
                    valueText = "${"%.1f".format(s.jvmHeapLoad * 100)}%",
                    subText = "${s.jvmHeapUsedMb} / ${s.jvmHeapAllocatedMb} MB (${I18n.t("stats.heap_limit", s.jvmHeapMaxMb)})  ·  ${I18n.t("stats.threads", s.threadCount)}  ·  ${I18n.t("stats.peak", "${"%.0f".format(peaks.jvmPeak)}%")}",
                    progress = s.jvmHeapLoad.toFloat(), history = jvmHistory, color = Color(0xFFFF9800)
                )
                Spacer(Modifier.height(14.dp))

                // 网络流量
                PerformanceRow(
                    icon = Icons.Filled.NetworkCheck, label = I18n.t("stats.network"),
                    valueText = "↑ ${formatNetSpeed(s.netUpKbS)}  ↓ ${formatNetSpeed(s.netDownKbS)}",
                    subText = I18n.t("stats.net_peak", formatNetSpeed(peaks.netUpPeak), formatNetSpeed(peaks.netDownPeak)),
                    progress = ((s.netUpKbS + s.netDownKbS) / 1024.0).coerceIn(0.0, 1.0).toFloat(),
                    history = netHistory, color = Color(0xFF00BCD4)
                )
                Spacer(Modifier.height(14.dp))

                // GPU 信息
                PerformanceRow(
                    icon = Icons.Filled.VideogameAsset, label = I18n.t("stats.gpu"),
                    valueText = if (s.gpuVramMb > 0) "${s.gpuVramMb} MB" else "N/A",
                    subText = "${s.gpuName}",
                    progress = -1f, history = null, color = Color(0xFFE91E63)
                )
                Spacer(Modifier.height(14.dp))

                PerformanceRow(
                    icon = Icons.Filled.Storage, label = I18n.t("stats.disk"),
                    valueText = "${"%.1f".format(s.diskLoad * 100)}%",
                    subText = "${"%.1f".format(s.diskUsedGb)} / ${"%.1f".format(s.diskTotalGb)} GB",
                    progress = s.diskLoad.toFloat(), history = null, color = Color(0xFF9C27B0)
                )
                Spacer(Modifier.height(14.dp))

                // 系统运行时长
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Schedule, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.t("stats.system_uptime"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.weight(1f))
                    Text(formatUptime(s.systemUptimeSec), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                }
            } ?: run {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
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
 * 单行性能指标：图标+标签+数值+进度条+迷你折线图。
 * progress < 0 表示不显示进度条（如 GPU）。
 */
@Composable
private fun PerformanceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    valueText: String,
    subText: String,
    progress: Float,
    history: List<Float>?,
    color: Color
) {
    // 进度条平滑过渡动画
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "perfProgress"
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Text(valueText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(Modifier.height(2.dp))
            Text(subText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            if (progress >= 0f) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 0.dp, drawStopIndicator = {}
                )
            }
            if (history != null && history.size > 1) {
                Spacer(Modifier.height(4.dp))
                MiniLineChart(history, color)
            }
        }
    }
}

/** 迷你折线图 */
@Composable
private fun MiniLineChart(data: List<Float>, color: Color) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val entranceProgress by rememberEntranceProgress(durationMs = 400)
    Canvas(modifier = Modifier.fillMaxWidth().height(28.dp).graphicsLayer { alpha = entranceProgress }) {
        val w = size.width; val h = size.height
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
            lineTo(points.last().x, h); close()
        }
        drawPath(path = fillPath, color = color.copy(alpha = 0.15f))
        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        drawPath(path = linePath, color = color, style = Stroke(width = 1.5f))
    }
}

private fun Double.format(digits: Int) = "%.${digits}f".format(this)

// ===== 总览卡片 =====

@Composable
private fun OverviewCard(stats: PlayTimeTracker.OverallStat?, entranceDelay: Int = 0) {
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
    Card(
        modifier = Modifier.fillMaxWidth().entrance(entranceProgress), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("stats.overview"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem(I18n.t("stats.total_duration"), stats?.totalDuration ?: 0L) {
                    PlayTimeTracker.formatDuration(it)
                }
                StatItem(I18n.t("stats.total_sessions"), (stats?.totalSessions ?: 0).toLong()) {
                    "$it ${I18n.t("common.times")}"
                }
                val avgDaily = if (stats != null && stats!!.daily.isNotEmpty()) {
                    stats!!.totalDuration / stats!!.daily.size
                } else 0L
                StatItem(I18n.t("stats.daily_avg"), avgDaily) {
                    PlayTimeTracker.formatDuration(it)
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, target: Long, format: (Long) -> String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CountUpText(
            target = target,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            format = format
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===== 游玩记录卡片（极值） =====

@Composable
private fun RecordsCard(records: PlayTimeTracker.RecordsStat, entranceDelay: Int = 0) {
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
    Card(modifier = Modifier.fillMaxWidth().entrance(entranceProgress), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("stats.play_records"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            // 4 列记录
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RecordItem(I18n.t("stats.longest_session"), records.longestSession?.let {
                    PlayTimeTracker.formatDurationShort(it.duration)
                } ?: "—", entranceDelay + 100)
                RecordItem(I18n.t("stats.longest_streak"), I18n.t("stats.days_count", records.longestStreakDays), entranceDelay + 160)
                RecordItem(I18n.t("stats.current_streak"), I18n.t("stats.days_count", records.currentStreakDays), entranceDelay + 220)
                RecordItem(I18n.t("stats.total_days"), I18n.t("stats.days_count", records.totalDays), entranceDelay + 280)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RecordItem(I18n.t("stats.first_play"), records.firstPlayDate.ifEmpty { "—" }, entranceDelay + 340)
                RecordItem(I18n.t("stats.most_played_hour"), records.mostPlayedHour.ifEmpty { "—"}, entranceDelay + 400)
                RecordItem(I18n.t("stats.longest_version"), records.longestSession?.version ?: "—", entranceDelay + 460)
                RecordItem(I18n.t("stats.record_date"), records.longestSession?.let {
                    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(it.start))
                } ?: "—", entranceDelay + 520)
            }
        }
    }
}

@Composable
private fun RecordItem(label: String, value: String, entranceDelay: Int = 0) {
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 400)
    Column(horizontalAlignment = Alignment.CenterHorizontally,
           modifier = Modifier.width(72.dp).entrance(entranceProgress, slideDistance = 20f)) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
             color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ===== 天数选择器 =====

@Composable
private fun DaysSelector(selected: Int, onSelect: (Int) -> Unit, entranceDelay: Int = 0) {
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 400)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.entrance(entranceProgress, slideDistance = 20f)) {
        listOf(7, 14, 30).forEach { days ->
            FilterChip(
                selected = selected == days,
                onClick = { onSelect(days) },
                label = { Text(I18n.t("stats.recent_days", days)) }
            )
        }
    }
}

// ===== 每日趋势折线图（支持切换时长/会话数 + 悬浮提示） =====

@Composable
private fun DailyTrendCard(dailyStats: List<PlayTimeTracker.DailyStat>, days: Int, entranceDelay: Int = 0) {
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
    var showSessions by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().entrance(entranceProgress), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(I18n.t("stats.daily_trend"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // 切换 时长/会话数
                FilterChip(
                    selected = !showSessions,
                    onClick = { showSessions = false },
                    label = { Text(I18n.t("stats.duration"), fontSize = 11.sp) }
                )
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = showSessions,
                    onClick = { showSessions = true },
                    label = { Text(I18n.t("stats.count"), fontSize = 11.sp) }
                )
            }
            Spacer(Modifier.height(16.dp))
            DailyTrendChart(dailyStats, showSessions)
        }
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
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
    // 单元格错峰渐入动画
    val cellProgress = remember(heatmap) { Animatable(0f) }
    LaunchedEffect(heatmap) {
        delay(entranceDelay.toLong())
        cellProgress.snapTo(0f)
        cellProgress.animateTo(1f, tween(800, easing = LinearOutSlowInEasing))
    }
    val cellProgressValue by cellProgress.asState()
    Card(modifier = Modifier.fillMaxWidth().entrance(entranceProgress), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("stats.time_heatmap"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("stats.heatmap_desc"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

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

            // 表头：小时
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("", modifier = Modifier.width(20.dp))
                (0..23).forEach { h ->
                    if (h % 3 == 0) {
                        Text("${h}", fontSize = 8.sp, color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(2.dp))

            // 热力图主体（每行按错峰渐入）
            heatmap.durations.forEachIndexed { dayIdx, hours ->
                // 每行的 alpha 基于整体进度和行索引，实现从上到下错峰
                val rowAlpha = (cellProgressValue * 8 - dayIdx).coerceIn(0f, 1f)
                Row(modifier = Modifier.fillMaxWidth().height(14.dp)
                    .graphicsLayer { alpha = rowAlpha },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(days[dayIdx], fontSize = 9.sp, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(20.dp))
                    hours.forEachIndexed { hourIdx, duration ->
                        val alpha = if (duration > 0) {
                            0.1f + 0.9f * (duration.toFloat() / maxV.toFloat())
                        } else 0f
                        Box(modifier = Modifier.weight(1f).height(12.dp).padding(1.dp)) {
                            Canvas(Modifier.fillMaxSize()) {
                                if (duration > 0) {
                                    drawRect(color = primaryColor.copy(alpha = alpha))
                                } else {
                                    drawRect(color = emptyColor, alpha = 0.3f)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            // 图例
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(I18n.t("stats.heatmap_less"), fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(4.dp))
                (1..5).forEach { i ->
                    Box(Modifier.size(10.dp).padding(1.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(color = primaryColor.copy(alpha = 0.1f + 0.18f * i))
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("stats.heatmap_more"), fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// ===== 周几分布柱状图 =====

@Composable
private fun WeekdayDistributionCard(weekdayDist: List<PlayTimeTracker.WeekdayStat>, entranceDelay: Int = 0) {
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
    Card(modifier = Modifier.fillMaxWidth().entrance(entranceProgress), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("stats.weekday_distribution"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            val maxDuration = weekdayDist.maxOfOrNull { it.totalDuration } ?: 0L
            val primaryColor = MaterialTheme.colorScheme.primary
            weekdayDist.forEachIndexed { idx, stat ->
                WeekdayBar(stat, maxDuration, primaryColor, entranceDelay + idx * 70)
            }
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
        Box(modifier = Modifier.weight(1f).height(16.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val ratio = if (maxDuration > 0) stat.totalDuration.toFloat() / maxDuration.toFloat() else 0f
                val w = size.width * ratio * barProgressValue
                drawRect(color = primaryColor.copy(alpha = 0.2f))
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
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
    // 饼图扫描动画
    val sweepProgress = remember(versions) { Animatable(0f) }
    LaunchedEffect(versions) {
        delay(entranceDelay.toLong())
        sweepProgress.snapTo(0f)
        sweepProgress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val sweepProgressValue by sweepProgress.asState()
    Card(modifier = Modifier.fillMaxWidth().entrance(entranceProgress), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("stats.version_dist"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            val topVersions = versions.take(6)
            val otherDuration = versions.drop(6).sumOf { it.totalDuration }
            val pieData = topVersions.map { it to it.totalDuration } +
                (if (otherDuration > 0) listOf(null to otherDuration) else emptyList())
            val total = pieData.sumOf { it.second }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 饼图（扫描动画）
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
                // 图例（按进度渐入）
                Column(modifier = Modifier.weight(1f)) {
                    pieData.forEachIndexed { i, pair ->
                        val version = pair.first?.version ?: I18n.t("stats.other")
                        val pct = if (total > 0) pair.second.toFloat() / total.toFloat() * 100 else 0f
                        val legendAlpha = (sweepProgressValue * pieData.size - i).coerceIn(0f, 1f)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                                .graphicsLayer { alpha = legendAlpha }) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp))
                                .background(PIE_COLORS[i % PIE_COLORS.size]))
                            Spacer(Modifier.width(6.dp))
                            Text(version, style = MaterialTheme.typography.labelSmall,
                                 modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${"%.0f".format(pct)}%", style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
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

    Card(modifier = Modifier.fillMaxWidth().entrance(entranceProgress), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(I18n.t("stats.session_records"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(I18n.t("stats.total_count", totalCount), style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(300)) + expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(animationSpec = tween(200, easing = FastOutSlowInEasing))
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    sessions.forEachIndexed { i, session ->
                        SessionRow(session, entranceDelay = i * 30)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    }
                    // 分页加载更多
                    if ((currentPage + 1) * pageSize < totalCount) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                currentPage++
                                ioScope.launch {
                                    val more = withContext(Dispatchers.IO) {
                                        vm.core.playTimeTracker().getSessions(currentPage * pageSize, pageSize)
                                    }
                                    sessions = sessions + more
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(I18n.t("stats.load_more"), style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: PlayTimeTracker.Session, entranceDelay: Int = 0) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 350)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .entrance(entranceProgress, slideDistance = 16f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PlayCircle, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(session.version, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(timeFmt.format(Date(session.start)), style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }
        Text(PlayTimeTracker.formatDurationShort(session.duration),
             style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
             color = MaterialTheme.colorScheme.primary)
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
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
    // 0=模组 1=世界 2=服务器 3=实例
    var tab by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<PlayTimeTracker.BreakdownStat>>(emptyList()) }

    // 切换 Tab 时重新加载数据
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

    Card(modifier = Modifier.fillMaxWidth().entrance(entranceProgress), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("stats.breakdown"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("stats.breakdown_desc"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

            // Tab 切换栏
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val tabs = listOf(
                    I18n.t("stats.breakdown_mod"),
                    I18n.t("stats.breakdown_world"),
                    I18n.t("stats.breakdown_server"),
                    I18n.t("stats.breakdown_instance")
                )
                tabs.forEachIndexed { i, label ->
                    FilterChip(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (items.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text(I18n.t("stats.breakdown_empty"), color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val maxDuration = items.maxOfOrNull { it.totalDuration } ?: 0L
                items.forEachIndexed { i, stat ->
                    BreakdownRow(stat, maxDuration, entranceDelay + i * 40)
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

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stat.displayName.ifEmpty { stat.key },
                     style = MaterialTheme.typography.bodySmall,
                     fontWeight = FontWeight.Medium,
                     modifier = Modifier.weight(1f),
                     maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                Text(PlayTimeTracker.formatDurationShort(stat.totalDuration),
                     style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.Bold,
                     color = primaryColor)
            }
            Spacer(Modifier.height(3.dp))
            Box(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val ratio = if (maxDuration > 0) stat.totalDuration.toFloat() / maxDuration.toFloat() else 0f
                    val w = size.width * ratio * barProgressValue
                    drawRect(color = primaryColor.copy(alpha = 0.2f))
                    drawRect(color = primaryColor, size = Size(w, size.height))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                I18n.t("stats.breakdown_meta", stat.sessionCount, lastPlayedStr),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
