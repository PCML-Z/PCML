package com.lash.pmcl.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lash.pmcl.core.stats.PlayTimeTracker
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
 * 游玩统计页（Android 版）：对齐桌面版 StatisticsPage 的右栏游玩统计部分。
 *
 * 左栏实时设备性能负载依赖桌面版 RuntimeManager，Android 无法获取系统性能，故跳过。
 *
 * @param playTimeTracker 时长追踪器，由 LauncherCore.playTimeTracker 注入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(playTimeTracker: PlayTimeTracker) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    var overall by remember { mutableStateOf<PlayTimeTracker.OverallStat?>(null) }
    var records by remember { mutableStateOf<PlayTimeTracker.RecordsStat?>(null) }
    var days by remember { mutableIntStateOf(7) }
    var dailyStats by remember { mutableStateOf<List<PlayTimeTracker.DailyStat>>(emptyList()) }
    var heatmap by remember { mutableStateOf<PlayTimeTracker.HeatmapStat?>(null) }
    var weekdayDist by remember { mutableStateOf<List<PlayTimeTracker.WeekdayStat>>(emptyList()) }

    // 总览与记录为全量统计（recentDays=0），不随天数选择器变化
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                overall = playTimeTracker.getOverallStats(0)
                records = playTimeTracker.getRecords()
            } catch (_: Throwable) {
                // 静默：不阻断页面渲染
            }
        }
    }

    // 天数选择器驱动的窗口统计
    LaunchedEffect(days) {
        withContext(Dispatchers.IO) {
            try {
                dailyStats = playTimeTracker.getDailyStatsWithZeros(days)
                heatmap = playTimeTracker.getHeatmap(days)
                weekdayDist = playTimeTracker.getWeekdayDistribution(days)
            } catch (_: Throwable) {
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("游玩统计") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { OverviewCard(overall, records, entranceDelay = 80) }
            records?.let { item { RecordsCard(it, entranceDelay = 160) } }
            item { DaysSelector(days, onSelect = { days = it }, entranceDelay = 220) }
            if (dailyStats.isNotEmpty()) {
                item { DailyTrendCard(dailyStats, days, entranceDelay = 280) }
            }
            heatmap?.let { item { HeatmapCard(it, entranceDelay = 360) } }
            if (weekdayDist.isNotEmpty()) {
                item { WeekdayDistributionCard(weekdayDist, entranceDelay = 440) }
            }
            overall?.let { ov ->
                if (ov.versions.isNotEmpty()) {
                    item { VersionPieCard(ov.versions, entranceDelay = 520) }
                }
            }
            item { SessionListCard(playTimeTracker, entranceDelay = 600) }
            item { BreakdownCard(playTimeTracker, entranceDelay = 680) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ===== 通用 Apple 风格卡片组件 =====

private val AppleCardShape = RoundedCornerShape(20.dp)
private val AppleTileShape = RoundedCornerShape(16.dp)
private val AppleSegmentShape = RoundedCornerShape(10.dp)

/** 卡片入场动画进度（0→1），带可选延迟实现错峰效果 */
@Composable
private fun rememberEntranceProgress(delayMs: Int = 0, durationMs: Int = 500): State<Float> {
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

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    entranceDelay: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    val entranceProgress by rememberEntranceProgress(delayMs = entranceDelay, durationMs = 600)
    Card(
        modifier = modifier.fillMaxWidth().entrance(entranceProgress),
        shape = AppleCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            content = content
        )
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.2.sp
    )
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = AppleTileShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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
private fun HeroDuration(millis: Long) {
    val parts = remember(millis) { heroDurationParts(millis) }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEach { (num, unit) ->
            Row(verticalAlignment = Alignment.Bottom) {
                CountUpText(
                    target = num,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 42.sp,
                        letterSpacing = (-1.2).sp,
                        lineHeight = 46.sp
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
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 42.sp, letterSpacing = (-1.2).sp),
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
        if (h > 0) add(h to "h")
        if (m > 0 || h == 0L) add(m to "m")
    }
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
    val animatedValue = animated.value
    if (style != null) {
        Text(format(animatedValue.toLong()), modifier = modifier, style = style, fontWeight = fontWeight, color = color)
    } else {
        Text(format(animatedValue.toLong()), modifier = modifier, fontWeight = fontWeight, color = color)
    }
}

@Composable
private fun InsetList(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = AppleTileShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    ) {
        Column(content = content)
    }
}

@Composable
private fun InsetRow(
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

    StatCard(entranceDelay = entranceDelay) {
        Eyebrow("总览")
        Spacer(Modifier.height(10.dp))
        HeroDuration(total)
        Spacer(Modifier.height(4.dp))
        Text(
            "总时长",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricTile(
                label = "总会话数",
                value = "$sessions",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "日均时长",
                value = PlayTimeTracker.formatDurationShort(avgDaily),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "连续天数",
                value = "$streak 天",
                modifier = Modifier.weight(1f),
                accent = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

// ===== 游玩记录卡片（2×2 指标方块 + 内嵌列表） =====

@Composable
private fun RecordsCard(records: PlayTimeTracker.RecordsStat, entranceDelay: Int = 0) {
    val timeFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    StatCard(entranceDelay = entranceDelay) {
        Eyebrow("游玩记录")
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "最长单次会话",
                value = records.longestSession?.let {
                    PlayTimeTracker.formatDurationShort(it.duration)
                } ?: "—",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "最长连续天数",
                value = "${records.longestStreakDays} 天",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "总游玩天数",
                value = "${records.totalDays} 天",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "最常时段",
                value = records.mostPlayedHour.ifEmpty { "—" },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))
        InsetList {
            InsetRow("首次游玩", records.firstPlayDate.ifEmpty { "—" })
            InsetRow("最长会话版本", records.longestSession?.version ?: "—")
            InsetRow(
                "记录时间",
                records.longestSession?.let { timeFmt.format(Date(it.start)) } ?: "—",
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
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.15f + 0.85f * bg))
                        .clickable { onSelect(days) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "近 $days 天",
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

// ===== 每日趋势折线图（支持切换时长/会话数 + 点击提示） =====

@Composable
private fun DailyTrendCard(
    dailyStats: List<PlayTimeTracker.DailyStat>,
    days: Int,
    entranceDelay: Int = 0
) {
    var showSessions by remember { mutableStateOf(false) }
    val periodTotal = remember(dailyStats, showSessions) {
        if (showSessions) dailyStats.sumOf { it.sessionCount.toLong() }
        else dailyStats.sumOf { it.totalDuration }
    }
    StatCard(entranceDelay = entranceDelay) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("每日趋势")
                Spacer(Modifier.height(6.dp))
                Text(
                    if (showSessions) "$periodTotal 次"
                    else PlayTimeTracker.formatDuration(periodTotal),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp
                )
                Text(
                    "近 $days 天",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = AppleSegmentShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(false to "时长", true to "次数").forEach { (sessions, label) ->
                        val on = showSessions == sessions
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
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
    val drawProgress = drawProgressState.value

    var hoverIndex by remember { mutableIntStateOf(-1) }

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
                                val idx = ((pos.x - padding) / stepX).roundToInt()
                                    .coerceIn(0, dailyStats.lastIndex)
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
                drawLine(
                    trackColor, Offset(padding, canvasHeight - padding),
                    Offset(canvasWidth - padding, canvasHeight - padding), strokeWidth = 2f
                )
                return@Canvas
            }

            // 网格线（按进度渐入）
            for (i in 0..4) {
                val y = padding + chartHeight * (1f - i / 4f)
                drawLine(
                    trackColor.copy(alpha = 0.3f * drawProgress), Offset(padding, y),
                    Offset(canvasWidth - padding, y), strokeWidth = 1f
                )
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
                val measure = PathMeasure().apply { setPath(fullPath, false) }
                val subPath = Path()
                measure.getSegment(0f, measure.length * drawProgress, subPath, true)
                drawPath(subPath, primaryColor, style = Stroke(width = 3f))

                val visibleCount = (points.size * drawProgress).toInt().coerceIn(1, points.size)
                if (visibleCount > 1) {
                    val fillPath = Path().apply {
                        moveTo(points[0].x, canvasHeight - padding)
                        lineTo(points[0].x, points[0].y)
                        for (i in 1 until visibleCount) lineTo(points[i].x, points[i].y)
                        lineTo(points[visibleCount - 1].x, canvasHeight - padding)
                        close()
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

            // 提示竖线
            if (hoverIndex in points.indices) {
                val p = points[hoverIndex]
                drawLine(
                    Color(0xFFFF5722).copy(alpha = 0.5f),
                    Offset(p.x, padding), Offset(p.x, canvasHeight - padding), strokeWidth = 1f
                )
            }
        }

        // 提示文字
        if (hoverIndex in dailyStats.indices) {
            val stat = dailyStats[hoverIndex]
            val dateStr = try {
                LocalDate.parse(stat.date).format(DateTimeFormatter.ofPattern("MM/dd"))
            } catch (_: Throwable) {
                stat.date
            }
            val valueStr = if (showSessions) "${stat.sessionCount} 次"
            else PlayTimeTracker.formatDurationShort(stat.totalDuration)
            Surface(
                modifier = Modifier.padding(top = 4.dp, start = 48.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF424242)
            ) {
                Text(
                    "$dateStr  $valueStr", color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
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
                } catch (_: Throwable) {
                    stat.date.substring(minOf(5, stat.date.length))
                }
                Text(date, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(" ", fontSize = 9.sp)
            }
        }
    }
}

// ===== 时段热力图 =====

@Composable
private fun HeatmapCard(heatmap: PlayTimeTracker.HeatmapStat, entranceDelay: Int = 0) {
    val cellProgress = remember(heatmap) { Animatable(0f) }
    LaunchedEffect(heatmap) {
        delay(entranceDelay.toLong())
        cellProgress.snapTo(0f)
        cellProgress.animateTo(1f, tween(800, easing = LinearOutSlowInEasing))
    }
    val cellProgressValue = cellProgress.value

    StatCard(entranceDelay = entranceDelay) {
        Eyebrow("时段热力图")
        Spacer(Modifier.height(4.dp))
        Text(
            "按周一到周日 × 24 小时统计游玩频率",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        val maxV = heatmap.maxValue.coerceAtLeast(1)
        val primaryColor = MaterialTheme.colorScheme.primary
        val emptyColor = MaterialTheme.colorScheme.surfaceVariant
        val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

        // 顶部小时刻度
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.width(20.dp))
            (0..23).forEach { h ->
                if (h % 3 == 0) {
                    Text(
                        "$h",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
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
                modifier = Modifier.fillMaxWidth().height(16.dp)
                    .graphicsLayer { alpha = rowAlpha },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    dayLabels[dayIdx],
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
                "少",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            (1..5).forEach { i ->
                Box(
                    Modifier.size(10.dp).clip(RoundedCornerShape(2.dp))
                        .background(primaryColor.copy(alpha = 0.12f + 0.18f * i))
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "多",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== 周几分布柱状图 =====

@Composable
private fun WeekdayDistributionCard(
    weekdayDist: List<PlayTimeTracker.WeekdayStat>,
    entranceDelay: Int = 0
) {
    StatCard(entranceDelay = entranceDelay) {
        Eyebrow("周几分布")
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
    val barProgress = remember(stat) { Animatable(0f) }
    LaunchedEffect(stat) {
        delay(barDelay.toLong())
        barProgress.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }
    val barProgressValue = barProgress.value
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stat.dayName, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(32.dp), fontWeight = FontWeight.Medium
        )
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
            textAlign = TextAlign.End
        )
    }
}

// ===== 版本分布饼图 =====

private val PIE_COLORS = listOf(
    Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFFFF9800),
    Color(0xFF9C27B0), Color(0xFF00BCD4), Color(0xFFE91E63),
    Color(0xFF607D8B)
)

@Composable
private fun VersionPieCard(
    versions: List<PlayTimeTracker.VersionStat>,
    entranceDelay: Int = 0
) {
    val sweepProgress = remember(versions) { Animatable(0f) }
    LaunchedEffect(versions) {
        delay(entranceDelay.toLong())
        sweepProgress.snapTo(0f)
        sweepProgress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val sweepProgressValue = sweepProgress.value

    StatCard(entranceDelay = entranceDelay) {
        Eyebrow("版本分布")
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
                    val version = pair.first?.version ?: "其他"
                    val pct = if (total > 0) pair.second.toFloat() / total.toFloat() * 100 else 0f
                    val legendAlpha = (sweepProgressValue * pieData.size - i).coerceIn(0f, 1f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 3.dp)
                            .graphicsLayer { alpha = legendAlpha }
                    ) {
                        Box(
                            Modifier.size(10.dp).clip(RoundedCornerShape(3.dp))
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

// ===== 会话详情列表 =====

@Composable
private fun SessionListCard(playTimeTracker: PlayTimeTracker, entranceDelay: Int = 0) {
    var sessions by remember { mutableStateOf<List<PlayTimeTracker.Session>>(emptyList()) }
    var totalCount by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val pageSize = 10
    var currentPage by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(expanded) {
        if (expanded && sessions.isEmpty()) {
            withContext(Dispatchers.IO) {
                totalCount = playTimeTracker.getSessionCount()
                sessions = playTimeTracker.getSessions(0, pageSize)
            }
        }
    }

    StatCard(entranceDelay = entranceDelay) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Eyebrow("会话记录")
                Spacer(Modifier.height(2.dp))
                Text(
                    "共 $totalCount 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
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
                InsetList {
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
                            scope.launch {
                                val more = withContext(Dispatchers.IO) {
                                    playTimeTracker.getSessions(currentPage * pageSize, pageSize)
                                }
                                sessions = sessions + more
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppleTileShape
                    ) {
                        Text("加载更多", style = MaterialTheme.typography.labelLarge)
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

@Composable
private fun BreakdownCard(playTimeTracker: PlayTimeTracker, entranceDelay: Int = 0) {
    // 0=模组 1=世界 2=服务器 3=实例
    var tab by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<PlayTimeTracker.BreakdownStat>>(emptyList()) }

    LaunchedEffect(tab) {
        withContext(Dispatchers.IO) {
            items = when (tab) {
                0 -> playTimeTracker.getModBreakdown(20)
                1 -> playTimeTracker.getWorldBreakdown()
                2 -> playTimeTracker.getServerBreakdown()
                else -> playTimeTracker.getInstanceBreakdown()
            }
        }
    }

    val tabs = listOf("模组", "世界", "服务器", "实例")

    StatCard(entranceDelay = entranceDelay) {
        Eyebrow("细分统计")
        Spacer(Modifier.height(4.dp))
        Text(
            "按维度查看时长分布",
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
                            .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
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
                    "暂无数据",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            val maxDuration = items.maxOfOrNull { it.totalDuration } ?: 0L
            InsetList {
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
    val barProgressValue = barProgress.value
    val primaryColor = MaterialTheme.colorScheme.primary
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val lastPlayedStr = if (stat.lastPlayed > 0) timeFmt.format(Date(stat.lastPlayed)) else "—"

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
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
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
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
            "${stat.sessionCount} 次 · $lastPlayedStr",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
