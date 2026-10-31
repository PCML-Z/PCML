package com.pmcl.ui.studio

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.page.MemoryTrendChart
import com.pmcl.ui.page.formatMem
import com.pmcl.ui.page.memFraction
import com.pmcl.ui.page.memMb
import com.pmcl.ui.page.rememberMemoryWatch

/** Studio 内容模块里的内存页，数字每秒变一次。 */
@Composable
internal fun MemorySection(ctx: ModuleCtx) {
    val watch = rememberMemoryWatch(ctx.vm)
    val p = LocalStudio.current
    val systemPct = if (watch.systemTotal > 0L) ((watch.systemUsed * 100) / watch.systemTotal).toInt().coerceIn(0, 100) else 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ModuleHero(I18n.t("content.memory.title"), I18n.t("content.memory.live"), ctx.color, Icons.Filled.Memory)
        FlatText(
            I18n.t("content.memory.hint"),
            color = p.muted,
            lines = 3,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
        )
        if (watch.unavailable) {
            FlatText(
                I18n.t("content.memory.unavailable"),
                color = Tiles.Red,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        TileRow(84.dp) {
            ColorTile(
                I18n.t("content.memory.tile_launcher"),
                if (watch.ready) memMb(watch.launcherRss).toString() else "—",
                Tiles.Blue,
                Modifier.weight(1f).fillMaxHeight(),
                Icons.Filled.Memory,
                index = 0
            )
            ColorTile(
                I18n.t("content.memory.tile_game"),
                if (watch.ready) memMb(watch.gamesRss).toString() else "—",
                Tiles.Forest,
                Modifier.weight(1f).fillMaxHeight(),
                Icons.Filled.Memory,
                index = 1
            )
            ColorTile(
                I18n.t("content.memory.tile_system"),
                if (watch.ready && watch.systemTotal > 0L) systemPct.toString() else "—",
                Tiles.Slate,
                Modifier.weight(1f).fillMaxHeight(),
                Icons.Filled.Memory,
                index = 2
            )
        }
        Column(Modifier.fillMaxWidth().background(p.panel)) {
            fun shown(bytes: Long) = if (watch.ready) formatMem(bytes) else "—"
            FactRow(I18n.t("content.memory.rss"), shown(watch.launcherRss), 0)
            FactRow(I18n.t("content.memory.peak_label"), shown(watch.launcherPeak), 1)
            FactRow(
                I18n.t("content.memory.heap"),
                if (!watch.ready) "—" else I18n.t(
                    "content.memory.heap_detail",
                    formatMem(watch.heapUsed),
                    if (watch.heapMax > 0L) formatMem(watch.heapMax) else I18n.t("content.unknown"),
                    formatMem(watch.heapCommitted)
                ),
                2,
                lines = 2
            )
            FactRow(
                I18n.t("content.memory.system"),
                if (!watch.ready) "—" else I18n.t(
                    "content.memory.system_line",
                    formatMem(watch.systemUsed),
                    formatMem(watch.systemTotal),
                    formatMem(watch.systemAvailable)
                ),
                3,
                lines = 2
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            FlatText(I18n.t("content.memory.heap"), color = p.muted, size = 11)
            Spacer(Modifier.height(4.dp))
            ProgressLine(memFraction(watch.heapUsed, watch.heapMax), Tiles.Blue, height = 6.dp)
            Spacer(Modifier.height(10.dp))
            FlatText(I18n.t("content.memory.system"), color = p.muted, size = 11)
            Spacer(Modifier.height(4.dp))
            ProgressLine(memFraction(watch.systemUsed, watch.systemTotal), Tiles.Slate, height = 6.dp)
        }
        Column(Modifier.fillMaxWidth().background(p.editor).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(Tiles.Blue)
                Spacer(Modifier.width(6.dp))
                FlatText(I18n.t("content.memory.launcher"), color = p.muted)
                Spacer(Modifier.width(16.dp))
                Swatch(Tiles.Forest)
                Spacer(Modifier.width(6.dp))
                FlatText(I18n.t("content.memory.game"), color = p.muted)
            }
            Spacer(Modifier.height(8.dp))
            MemoryTrendChart(
                watch.launcherHistory,
                watch.gameHistory,
                Tiles.Blue,
                Tiles.Forest,
                Modifier.fillMaxWidth().height(120.dp).background(p.panel)
            )
        }
        if (!watch.ready) {
            FlatText(I18n.t("studio.loading"), color = p.muted, modifier = Modifier.padding(16.dp))
        } else if (watch.games.isEmpty()) {
            FlatText(I18n.t("content.memory.no_game"), color = p.muted, modifier = Modifier.padding(16.dp))
        } else {
            watch.games.forEachIndexed { index, game ->
                val name = game.versionId.ifBlank { I18n.t("content.memory.game") }
                val meta = if (game.limitBytes > 0L) {
                    I18n.t("content.memory.game_meta_limit", game.pid.toString(), formatMem(game.limitBytes))
                } else {
                    I18n.t("content.memory.game_meta", game.pid.toString())
                }
                Column(
                    Modifier.fillMaxWidth().background(if (index % 2 == 0) p.panel else p.panelAlt).padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlatText(name, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        FlatText(formatMem(game.rssBytes), weight = FontWeight.SemiBold)
                    }
                    FlatText(meta, color = p.muted, size = 11)
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(
                        memFraction(game.rssBytes, if (game.limitBytes > 0L) game.limitBytes else watch.systemTotal),
                        Tiles.Forest,
                        height = 6.dp
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
}
