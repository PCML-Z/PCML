package com.lash.pmcl.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 全局底部音乐迷你条 — 与桌面端 MiniMusicBar 功能一致。
 * 封面、曲目名、进度条、控制按钮。
 */
@Composable
fun MiniMusicBar(
    currentTrack: String,
    isPlaying: Boolean,
    currentMs: Int,
    durationMs: Int,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onOpenMusic: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (currentTrack.isEmpty()) return

    Surface(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(8.dp)) {
            // 进度条
            if (durationMs > 0) {
                val progress = if (durationMs > 0) currentMs.toFloat() / durationMs else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // 封面占位
                Surface(Modifier.size(36.dp), shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Star, null, Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 曲目名
                Column(Modifier.weight(1f).clickable { onOpenMusic() }) {
                    Text(currentTrack.take(30), style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(formatMusicTime(currentMs) + if (durationMs > 0) " / ${formatMusicTime(durationMs)}" else "",
                         style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                // 控制按钮
                IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "上一首", Modifier.size(20.dp))
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(36.dp)) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                         if (isPlaying) "暂停" else "播放", Modifier.size(24.dp),
                         tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.SkipNext, "下一首", Modifier.size(20.dp))
                }
            }
        }
    }
}

private fun formatMusicTime(ms: Int): String {
    val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
