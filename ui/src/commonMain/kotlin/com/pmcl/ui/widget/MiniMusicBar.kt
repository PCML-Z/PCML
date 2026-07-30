package com.pmcl.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.music.player.PlaybackState
import com.pmcl.ui.page.MusicCoverThumbnail
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.playNextMusic
import com.pmcl.ui.viewmodel.playPreviousMusic
import com.pmcl.ui.viewmodel.toggleMusicPlayPause

/**
 * 全局底部音乐迷你条：封面 + 当前曲目 + 进度 + 控制按钮。
 * 点击封面/标题区域打开音乐页；控制按钮不触发导航。
 */
@Composable
fun MiniMusicBar(vm: LauncherViewModel, onOpenMusic: () -> Unit = {}) {
    val playlist by vm.musicPlaylist.collectAsState()
    val currentIndex by vm.musicCurrentIndex.collectAsState()
    val state by vm.musicPlaybackState.collectAsState()
    val currentMs by vm.musicCurrentMs.collectAsState()
    val durationMs by vm.musicDurationMs.collectAsState()

    val track = if (currentIndex in playlist.indices) playlist[currentIndex] else return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = glassSurfaceVariantColor(),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.height(60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenMusic
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MusicCoverThumbnail(track.coverUrl, size = 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            track.uploader.ifBlank { "" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                        )
                        val dur = durationMs.coerceAtLeast(1L)
                        val progress = (currentMs.toFloat() / dur).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.weight(1f).height(3.dp),
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatMiniTime(currentMs, dur),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.playPreviousMusic() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = I18n.t("music.previous"), modifier = Modifier.size(20.dp))
                }
                val isPlaying = state == PlaybackState.PLAYING
                val isLoading = state == PlaybackState.LOADING
                IconButton(onClick = { vm.toggleMusicPlayPause() }, modifier = Modifier.size(36.dp)) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) I18n.t("music.pause") else I18n.t("music.play"),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                IconButton(onClick = { vm.playNextMusic() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = I18n.t("music.next"), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private fun formatMiniTime(currentMs: Long, durationMs: Long): String {
    fun fmt(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
    return "${fmt(currentMs)} / ${fmt(durationMs)}"
}
