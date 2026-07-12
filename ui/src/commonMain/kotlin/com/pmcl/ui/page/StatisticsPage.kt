package com.pmcl.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.pmcl.core.stats.PlayTimeTracker
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 游戏时长统计页：总览 + 每日趋势折线图 + 版本分布柱状图。
 */
@Composable
fun StatisticsPage(vm: LauncherViewModel) {
    val stats by vm.playTimeStats.collectAsState()
    val dailyStats by vm.dailyStats.collectAsState()
    val days by vm.statsDays.collectAsState()

    // 进入页面时刷新数据
    LaunchedEffect(Unit) { vm.refreshPlayTimeStats() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ===== 总览卡片 =====
        OverviewCard(stats)

        // ===== 天数选择器 =====
        DaysSelector(days, onSelect = { vm.setStatsDays(it) })

        // ===== 每日趋势折线图 =====
        if (dailyStats.isNotEmpty()) {
            DailyTrendCard(dailyStats, days)
        }

        // ===== 版本分布柱状图 =====
        if (stats != null && stats!!.versions.isNotEmpty()) {
            VersionDistributionCard(stats!!.versions)
        }
    }
}

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
