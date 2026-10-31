package com.pmcl.ui.page

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.launch.ProcessMonitor
import com.pmcl.ui.animation.TypewriterTitle
import com.pmcl.ui.theme.PmclCard
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.widget.pmclVerticalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

private const val HISTORY = 60
private const val HEAP_SANE = 1L shl 42

internal data class GameMemory(
    val pid: Long,
    val versionId: String,
    val rssBytes: Long,
    val limitBytes: Long,
)

private data class MemorySample(
    val launcherRss: Long,
    val launcherPresent: Boolean,
    val heapUsed: Long,
    val heapCommitted: Long,
    val heapMax: Long,
    val systemTotal: Long,
    val systemAvailable: Long,
    val games: List<GameMemory>,
)

/** 内容页和 Studio 内容模块共用的实时采样。离开页面后采样停止。 */
internal class MemoryWatch {
    var ready by mutableStateOf(false)
    var unavailable by mutableStateOf(false)
    var launcherRss by mutableLongStateOf(0L)
    var launcherPeak by mutableLongStateOf(0L)
    var heapUsed by mutableLongStateOf(0L)
    var heapCommitted by mutableLongStateOf(0L)
    var heapMax by mutableLongStateOf(0L)
    var gamesRss by mutableLongStateOf(0L)
    var gamesPeak by mutableLongStateOf(0L)
    var systemTotal by mutableLongStateOf(0L)
    var systemAvailable by mutableLongStateOf(0L)
    var games by mutableStateOf<List<GameMemory>>(emptyList())
    val launcherHistory = mutableStateListOf<Float>()
    val gameHistory = mutableStateListOf<Float>()

    val systemUsed: Long get() = (systemTotal - systemAvailable).coerceAtLeast(0L)
}

@Composable
internal fun rememberMemoryWatch(vm: LauncherViewModel): MemoryWatch {
    val watch = remember { MemoryWatch() }
    LaunchedEffect(vm) {
        val monitor = withContext(Dispatchers.IO) {
            try {
                ProcessMonitor()
            } catch (t: Throwable) {
                System.err.println("[Memory] ProcessMonitor 初始化失败: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }
        if (monitor == null) watch.unavailable = true
        while (isActive) {
            try {
                val sample = withContext(Dispatchers.IO) { readMemory(vm, monitor) }
                watch.launcherRss = sample.launcherRss
                if (sample.launcherRss > watch.launcherPeak) watch.launcherPeak = sample.launcherRss
                watch.heapUsed = sample.heapUsed
                watch.heapCommitted = sample.heapCommitted
                watch.heapMax = sample.heapMax
                watch.systemTotal = sample.systemTotal
                watch.systemAvailable = sample.systemAvailable
                watch.games = sample.games
                val total = sample.games.sumOf { it.rssBytes }
                watch.gamesRss = total
                if (total > watch.gamesPeak) watch.gamesPeak = total
                watch.launcherHistory.push(sample.launcherRss / (1024f * 1024f))
                watch.gameHistory.push(total / (1024f * 1024f))
                if (!sample.launcherPresent && monitor != null && sample.systemTotal <= 0L) watch.unavailable = true
                watch.ready = true
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                System.err.println("[Memory] 采样失败: ${t.javaClass.simpleName}: ${t.message}")
            }
            delay(1000)
        }
    }
    return watch
}

private fun MutableList<Float>.push(value: Float) {
    add(value)
    while (size > HISTORY) removeAt(0)
}

private fun readMemory(vm: LauncherViewModel, monitor: ProcessMonitor?): MemorySample {
    val rt = Runtime.getRuntime()
    val heapUsed = (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)
    val heapCommitted = rt.totalMemory().coerceAtLeast(0L)
    val rawMax = rt.maxMemory()
    val heapMax = if (rawMax in 1 until HEAP_SANE) rawMax else 0L
    if (monitor == null) {
        return MemorySample(0L, false, heapUsed, heapCommitted, heapMax, 0L, 0L, emptyList())
    }
    val tracked = vm.core.launch().trackedGames()
    val ids = ArrayList<Long>(tracked.size + 1)
    val self = monitor.currentPid()
    if (self > 0) ids.add(self)
    for (game in tracked) ids.add(game.pid)
    val usage = monitor.usageOf(ids)
    val selfUsage = if (self > 0) usage[self] else null
    val games = tracked.map { game ->
        val row = usage[game.pid]
        GameMemory(
            pid = game.pid,
            versionId = game.versionId.orEmpty(),
            rssBytes = row?.rssBytes ?: 0L,
            limitBytes = if (row == null) 0L else xmxBytes(row.arguments),
        )
    }
    return MemorySample(
        launcherRss = selfUsage?.rssBytes ?: 0L,
        launcherPresent = selfUsage != null,
        heapUsed = heapUsed,
        heapCommitted = heapCommitted,
        heapMax = heapMax,
        systemTotal = monitor.totalSystemMemoryBytes(),
        systemAvailable = monitor.availableSystemMemoryBytes(),
        games = games,
    )
}

/** JVM 的 -Xmx / -XX:MaxHeapSize，单位是字节。最后一个有效值生效。 */
internal fun xmxBytes(args: List<String>?): Long {
    if (args.isNullOrEmpty()) return 0L
    var found = 0L
    for (arg in args) {
        val raw = when {
            arg.length > 4 && arg.startsWith("-Xmx", ignoreCase = true) -> arg.substring(4)
            arg.startsWith("-XX:MaxHeapSize=", ignoreCase = true) -> arg.substringAfter('=')
            else -> null
        } ?: continue
        val bytes = jvmSizeBytes(raw)
        if (bytes > 0) found = bytes
    }
    return found
}

private fun jvmSizeBytes(raw: String): Long {
    val text = raw.trim()
    if (text.isEmpty()) return 0L
    val unit = text.last()
    if (unit.isDigit()) return text.toLongOrNull() ?: 0L
    val num = text.dropLast(1).toDoubleOrNull() ?: return 0L
    if (num <= 0) return 0L
    val mul = when (unit) {
        'k', 'K' -> 1024.0
        'm', 'M' -> 1024.0 * 1024
        'g', 'G' -> 1024.0 * 1024 * 1024
        't', 'T' -> 1024.0 * 1024 * 1024 * 1024
        else -> return 0L
    }
    val bytes = num * mul
    if (bytes >= Long.MAX_VALUE.toDouble()) return 0L
    return bytes.toLong()
}

internal fun formatMem(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 10240 -> String.format(Locale.US, "%.0f GB", mb / 1024.0)
        mb >= 1024 -> String.format(Locale.US, "%.1f GB", mb / 1024.0)
        mb >= 10 -> String.format(Locale.US, "%.0f MB", mb)
        else -> String.format(Locale.US, "%.1f MB", mb)
    }
}

internal fun memMb(bytes: Long): Int = (bytes / (1024.0 * 1024.0)).toInt().coerceAtLeast(0)

internal fun memFraction(used: Long, total: Long): Float {
    if (used <= 0L || total <= 0L) return 0f
    return (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

@Composable
fun MemoryUsagePage(vm: LauncherViewModel) {
    val watch = rememberMemoryWatch(vm)
    val launcherColor = Color(0xFF2B579A)
    val gameColor = Color(0xFF217346)
    Column(
        Modifier.fillMaxSize().padding(16.dp).pmclVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TypewriterTitle(I18n.t("content.memory.title"))
        Text(
            I18n.t("content.memory.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!watch.ready) {
            PmclCard(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        } else {
            if (watch.unavailable) {
                Text(
                    I18n.t("content.memory.unavailable"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MemoryHeadline(
                    title = I18n.t("content.memory.launcher"),
                    value = formatMem(watch.launcherRss),
                    caption = I18n.t("content.memory.peak", formatMem(watch.launcherPeak)),
                    detail = I18n.t(
                        "content.memory.heap_detail",
                        formatMem(watch.heapUsed),
                        if (watch.heapMax > 0L) formatMem(watch.heapMax) else I18n.t("content.unknown"),
                        formatMem(watch.heapCommitted)
                    ),
                    fraction = memFraction(watch.launcherRss, watch.systemTotal),
                    color = launcherColor,
                    modifier = Modifier.weight(1f)
                )
                MemoryHeadline(
                    title = I18n.t("content.memory.game"),
                    value = formatMem(watch.gamesRss),
                    caption = I18n.t("content.memory.peak", formatMem(watch.gamesPeak)),
                    detail = if (watch.games.isEmpty()) I18n.t("content.memory.no_game") else I18n.t("content.memory.rss"),
                    fraction = memFraction(watch.gamesRss, watch.systemTotal),
                    color = gameColor,
                    modifier = Modifier.weight(1f)
                )
            }
            PmclCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(I18n.t("content.memory.system"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        I18n.t(
                            "content.memory.system_line",
                            formatMem(watch.systemUsed),
                            formatMem(watch.systemTotal),
                            formatMem(watch.systemAvailable)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UsageBar(memFraction(watch.systemUsed, watch.systemTotal), MaterialTheme.colorScheme.tertiary)
                }
            }
            PmclCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(I18n.t("content.memory.live"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    MemoryLegend(I18n.t("content.memory.launcher"), launcherColor, I18n.t("content.memory.game"), gameColor)
                    MemoryTrendChart(watch.launcherHistory, watch.gameHistory, launcherColor, gameColor, Modifier.fillMaxWidth().height(120.dp))
                }
            }
            PmclCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(I18n.t("content.memory.game"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (watch.games.isEmpty()) {
                        Text(
                            I18n.t("content.memory.no_game"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        watch.games.forEach { game ->
                            val name = game.versionId.ifBlank { I18n.t("content.memory.game") }
                            val meta = if (game.limitBytes > 0L) {
                                I18n.t("content.memory.game_meta_limit", game.pid.toString(), formatMem(game.limitBytes))
                            } else {
                                I18n.t("content.memory.game_meta", game.pid.toString())
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text(formatMem(game.rssBytes), fontWeight = FontWeight.SemiBold)
                                }
                                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                UsageBar(
                                    memFraction(game.rssBytes, if (game.limitBytes > 0L) game.limitBytes else watch.systemTotal),
                                    gameColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryHeadline(
    title: String,
    value: String,
    caption: String,
    detail: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    PmclCard(modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            UsageBar(fraction, color)
            Text(caption, style = MaterialTheme.typography.labelMedium, color = color)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun UsageBar(fraction: Float, color: Color) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(450), label = "memBar")
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.16f))
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animated).background(color))
    }
}

@Composable
private fun MemoryLegend(launcher: String, launcherColor: Color, game: String, gameColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(launcherColor)
        Spacer(Modifier.width(6.dp))
        Text(launcher, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(16.dp))
        LegendDot(gameColor)
        Spacer(Modifier.width(6.dp))
        Text(game, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

@Composable
internal fun MemoryTrendChart(
    launcherMb: List<Float>,
    gameMb: List<Float>,
    launcherColor: Color,
    gameColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val count = maxOf(launcherMb.size, gameMb.size)
        if (count < 2) return@Canvas
        val maxV = maxOf(launcherMb.maxOrNull() ?: 0f, gameMb.maxOrNull() ?: 0f, 1f)
        drawLine(
            color = Color.Gray.copy(alpha = 0.25f),
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1f
        )
        fun series(data: List<Float>, color: Color) {
            if (data.size < 2) return
            val path = Path()
            data.forEachIndexed { i, v ->
                val x = i * size.width / (data.size - 1)
                val y = size.height - (v / maxV).coerceIn(0f, 1f) * (size.height - 6f) - 3f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        series(gameMb, gameColor)
        series(launcherMb, launcherColor)
    }
}
