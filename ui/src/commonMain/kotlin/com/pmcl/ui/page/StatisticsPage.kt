package com.pmcl.ui.page

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.runtime.RuntimeManager
import com.pmcl.core.stats.PlayTimeTracker
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 统计页：左侧实时设备性能负载 + 右侧游玩统计（总览/趋势/版本分布）。
 */
@Composable
fun StatisticsPage(vm: LauncherViewModel) {
    val stats by vm.playTimeStats.collectAsState()
    val dailyStats by vm.dailyStats.collectAsState()
    val days by vm.statsDays.collectAsState()

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
            RealtimePerformanceCard()
        }

        // ===== 右栏：游玩统计 =====
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 总览卡片
            OverviewCard(stats)

            // 天数选择器
            DaysSelector(days, onSelect = { vm.setStatsDays(it) })

            // 每日趋势折线图
            if (dailyStats.isNotEmpty()) {
                DailyTrendCard(dailyStats, days)
            }

            // 版本分布柱状图
            if (stats != null && stats!!.versions.isNotEmpty()) {
                VersionDistributionCard(stats!!.versions)
            }
        }
    }
}

// ===== 实时设备性能负载 =====

/** 性能采样数据 */
private data class PerformanceSample(
    val cpuLoad: Double,          // 0.0 ~ 1.0
    val memLoad: Double,          // 系统内存 0.0 ~ 1.0
    val jvmHeapLoad: Double,      // JVM 堆 0.0 ~ 1.0
    val jvmHeapUsedMb: Long,
    val jvmHeapAllocatedMb: Long,
    val jvmHeapMaxMb: Long,
    val threadCount: Int,
    val diskLoad: Double,         // 磁盘 0.0 ~ 1.0
    val cpuName: String,
    val cpuPhysicalCores: Int,
    val cpuLogicalCores: Int,
    val totalMemMb: Long,
    val availableMemMb: Long,
    val diskUsedGb: Double,
    val diskTotalGb: Double
)

/**
 * 实时设备性能负载卡片：CPU、内存、JVM 堆、磁盘、线程的实时监控。
 * 每 1.5 秒采样一次，最多保留 40 个历史点用于绘制迷你折线图。
 */
@Composable
private fun RealtimePerformanceCard() {
    // 单例 RuntimeManager（避免每次采样重建 oshi）
    val runtime = remember { RuntimeManager() }

    // 采样历史（用于迷你折线）
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val memHistory = remember { mutableStateListOf<Float>() }
    val jvmHistory = remember { mutableStateListOf<Float>() }

    // 最新一次采样
    var sample by remember { mutableStateOf<PerformanceSample?>(null) }

    // 启动后台采样协程
    LaunchedEffect(Unit) {
        while (true) {
            val s = withContext(Dispatchers.IO) {
                val cpu = runtime.getCpuLoad()
                val mem = runtime.getMemoryLoad()
                val jvmHeapLoad = runtime.getJvmHeapLoad()
                val diskUsage = runtime.getPmclDiskUsage()
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
                    diskTotalGb = diskUsage?.get(1) ?: 0.0
                )
            }
            sample = s

            // 更新历史数据
            cpuHistory.add((s.cpuLoad * 100).toFloat())
            memHistory.add((s.memLoad * 100).toFloat())
            jvmHistory.add((s.jvmHeapLoad * 100).toFloat())
            // 保留最近 40 个点
            while (cpuHistory.size > 40) cpuHistory.removeAt(0)
            while (memHistory.size > 40) memHistory.removeAt(0)
            while (jvmHistory.size > 40) jvmHistory.removeAt(0)

            delay(1500)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("实时设备性能负载", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // 实时心跳指示
                val infiniteTransition = rememberInfiniteTransition(label = "perfPulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse"
                )
                Canvas(Modifier.size(8.dp)) {
                    drawCircle(color = Color(0xFF4CAF50).copy(alpha = pulseAlpha))
                }
                Spacer(Modifier.width(4.dp))
                Text("实时", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(12.dp))

            sample?.let { s ->
                // ===== CPU 使用率 =====
                PerformanceRow(
                    icon = Icons.Filled.Speed,
                    label = "CPU",
                    valueText = "${"%.1f".format(s.cpuLoad * 100)}%",
                    subText = "${s.cpuName}  ·  ${s.cpuPhysicalCores}P/${s.cpuLogicalCores}L 核",
                    progress = s.cpuLoad.toFloat(),
                    history = cpuHistory,
                    color = Color(0xFF2196F3)
                )

                Spacer(Modifier.height(14.dp))

                // ===== 系统内存 =====
                PerformanceRow(
                    icon = Icons.Filled.Memory,
                    label = "系统内存",
                    valueText = "${"%.1f".format(s.memLoad * 100)}%",
                    subText = "${((s.totalMemMb - s.availableMemMb) / 1024.0).format(1)} / ${(s.totalMemMb / 1024.0).format(1)} GB",
                    progress = s.memLoad.toFloat(),
                    history = memHistory,
                    color = Color(0xFF4CAF50)
                )

                Spacer(Modifier.height(14.dp))

                // ===== JVM 堆内存 =====
                PerformanceRow(
                    icon = Icons.Filled.DeveloperBoard,
                    label = "JVM 堆",
                    valueText = "${"%.1f".format(s.jvmHeapLoad * 100)}%",
                    subText = "${s.jvmHeapUsedMb} / ${s.jvmHeapAllocatedMb} MB (上限 ${s.jvmHeapMaxMb} MB)  ·  ${s.threadCount} 线程",
                    progress = s.jvmHeapLoad.toFloat(),
                    history = jvmHistory,
                    color = Color(0xFFFF9800)
                )

                Spacer(Modifier.height(14.dp))

                // ===== 磁盘使用率 =====
                PerformanceRow(
                    icon = Icons.Filled.Storage,
                    label = "磁盘",
                    valueText = "${"%.1f".format(s.diskLoad * 100)}%",
                    subText = "${"%.1f".format(s.diskUsedGb)} / ${"%.1f".format(s.diskTotalGb)} GB",
                    progress = s.diskLoad.toFloat(),
                    history = null,
                    color = Color(0xFF9C27B0)
                )
            } ?: run {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * 单行性能指标：图标+标签+数值+进度条+迷你折线图。
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            Spacer(Modifier.height(4.dp))
            // 进度条
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
            // 迷你折线图
            if (history != null && history.size > 1) {
                Spacer(Modifier.height(4.dp))
                MiniLineChart(history, color)
            }
        }
    }
}

/**
 * 迷你折线图：绘制历史趋势（无坐标轴，纯视觉指示）。
 */
@Composable
private fun MiniLineChart(data: List<Float>, color: Color) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = Modifier.fillMaxWidth().height(28.dp)
    ) {
        val w = size.width
        val h = size.height
        if (data.size < 2) return@Canvas
        val stepX = w / (data.size - 1)
        val points = data.mapIndexed { i, v ->
            val y = h - (h * (v / 100f)).coerceIn(0f, h)
            Offset(i * stepX, y)
        }
        // 填充区域
        val fillPath = Path().apply {
            moveTo(points.first().x, h)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, h)
            close()
        }
        drawPath(path = fillPath, color = color.copy(alpha = 0.15f))
        // 折线
        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        drawPath(path = linePath, color = color, style = Stroke(width = 1.5f))
    }
}

/** Double 扩展：格式化保留指定小数位 */
private fun Double.format(digits: Int) = "%.${digits}f".format(this)

/**
 * 总览卡片：总时长 + 总会话数 + 平均每日时长
 */
@Composable
private fun OverviewCard(stats: PlayTimeTracker.OverallStat?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("stats.overview"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem(I18n.t("stats.total_duration"), PlayTimeTracker.formatDuration(stats?.totalDuration ?: 0))
                StatItem(I18n.t("stats.total_sessions"), "${stats?.totalSessions ?: 0} ${I18n.t("common.times")}")
                val avgDaily = if (stats != null && stats!!.daily.isNotEmpty()) {
                    stats!!.totalDuration / stats!!.daily.size
                } else 0L
                StatItem(I18n.t("stats.daily_avg"), PlayTimeTracker.formatDuration(avgDaily))
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
             color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 天数选择器：7天 / 14天 / 30天
 */
@Composable
private fun DaysSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 14, 30).forEach { days ->
            FilterChip(
                selected = selected == days,
                onClick = { onSelect(days) },
                label = { Text(I18n.t("stats.recent_days", days)) }
            )
        }
    }
}

/**
 * 每日趋势折线图（Canvas 绘制）
 */
@Composable
private fun DailyTrendCard(dailyStats: List<PlayTimeTracker.DailyStat>, days: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("stats.daily_trend"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            DailyTrendChart(dailyStats)
        }
    }
}

@Composable
private fun DailyTrendChart(dailyStats: List<PlayTimeTracker.DailyStat>) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    val maxDuration = remember(dailyStats) {
        dailyStats.maxOfOrNull { it.totalDuration } ?: 0L
    }

    Canvas(
        modifier = Modifier.fillMaxWidth().height(180.dp)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val padding = 40f
        val chartWidth = canvasWidth - padding * 2
        val chartHeight = canvasHeight - padding * 2

        if (dailyStats.isEmpty() || maxDuration == 0L) {
            // 空状态：画一条基线
            drawLine(
                color = trackColor,
                start = Offset(padding, canvasHeight - padding),
                end = Offset(canvasWidth - padding, canvasHeight - padding),
                strokeWidth = 2f
            )
            return@Canvas
        }

        // 绘制水平网格线（4 条）
        for (i in 0..4) {
            val y = padding + chartHeight * (1f - i / 4f)
            drawLine(
                color = trackColor.copy(alpha = 0.3f),
                start = Offset(padding, y),
                end = Offset(canvasWidth - padding, y),
                strokeWidth = 1f
            )
        }

        // 计算每个点的位置
        val stepX = if (dailyStats.size > 1) chartWidth / (dailyStats.size - 1) else 0f
        val points = dailyStats.mapIndexed { i, stat ->
            val x = padding + i * stepX
            val yRatio = if (maxDuration > 0) stat.totalDuration.toFloat() / maxDuration.toFloat() else 0f
            val y = padding + chartHeight * (1f - yRatio)
            Offset(x, y)
        }

        // 绘制折线
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
            }
            drawPath(
                path = path,
                color = primaryColor,
                style = Stroke(width = 3f)
            )

            // 绘制填充区域
            val fillPath = Path().apply {
                moveTo(points[0].x, canvasHeight - padding)
                lineTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
                lineTo(points.last().x, canvasHeight - padding)
                close()
            }
            drawPath(
                path = fillPath,
                color = primaryColor.copy(alpha = 0.15f)
            )
        }

        // 绘制数据点
        points.forEach { point ->
            drawCircle(
                color = primaryColor,
                radius = 4f,
                center = point
            )
        }
    }

    // X 轴日期标签（每隔合适间隔显示）
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
                } catch (e: Throwable) {
                    stat.date.substring(minOf(5, stat.date.length))
                }
                Text(date, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(" ", fontSize = 9.sp)
            }
        }
    }
}

/**
 * 版本分布柱状图（Canvas 绘制）
 */
@Composable
private fun VersionDistributionCard(versions: List<PlayTimeTracker.VersionStat>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("stats.version_dist"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            // 只显示前 10 个版本
            versions.take(10).forEach { stat ->
                VersionBar(stat, versions.first().totalDuration)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun VersionBar(stat: PlayTimeTracker.VersionStat, maxDuration: Long) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val ratio = if (maxDuration > 0) stat.totalDuration.toFloat() / maxDuration.toFloat() else 0f

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stat.version, style = MaterialTheme.typography.bodySmall,
                 fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                 maxLines = 1)
            Text("${PlayTimeTracker.formatDurationShort(stat.totalDuration)} (${stat.sessionCount} ${I18n.t("common.times")})",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = primaryColor,
            trackColor = trackColor
        )
    }
}
