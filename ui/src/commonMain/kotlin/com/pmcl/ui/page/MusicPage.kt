package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.music.lyrics.LyricsLine
import com.pmcl.music.lyrics.LyricsParser
import com.pmcl.music.player.PlaybackState
import com.pmcl.music.source.LocalAudioSource
import com.pmcl.ui.theme.glassContainerColor
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.util.decodeSampledBitmap
import com.pmcl.ui.viewmodel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter
import javax.swing.JFileChooser

/**
 * 音乐播放器：由二级侧栏切换 播放器 / 播放列表 / 历史。
 */
@Composable
fun MusicPage(vm: LauncherViewModel, sectionId: String = "player") {
    val playlist by vm.musicPlaylist.collectAsState()
    val history by vm.musicHistory.collectAsState()
    val currentIndex by vm.musicCurrentIndex.collectAsState()
    val state by vm.musicPlaybackState.collectAsState()
    val currentMs by vm.musicCurrentMs.collectAsState()
    val durationMs by vm.musicDurationMs.collectAsState()
    val volume by vm.musicVolume.collectAsState()
    val muted by vm.musicMuted.collectAsState()
    val loadingUrl by vm.musicLoadingUrl.collectAsState()
    val repeatMode by vm.musicRepeatMode.collectAsState()
    val shuffle by vm.musicShuffle.collectAsState()
    val lyrics by vm.musicLyrics.collectAsState()
    val playlistIndex by vm.musicPlaylistIndex.collectAsState()
    val activePlaylistId by vm.musicActivePlaylistId.collectAsState()

    var inputUrl by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var removeIndex by remember { mutableStateOf<Int?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistMenuExpanded by remember { mutableStateOf(false) }

    val sectionTitleKey = when (sectionId) {
        "playlist" -> "music.playlist"
        "history" -> "music.history"
        else -> "music.section.player"
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        when (sectionId) {
            "playlist" -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        I18n.t(sectionTitleKey),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        I18n.t("music.track_count", playlist.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (playlist.isNotEmpty()) {
                        TextButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Filled.DeleteSweep, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("music.clear"))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(I18n.t("music.playlists"), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        OutlinedButton(onClick = { playlistMenuExpanded = true }) {
                            val name = playlistIndex.firstOrNull { it.id == activePlaylistId }?.name
                                ?: I18n.t("music.playlist_default")
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Filled.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = playlistMenuExpanded,
                            onDismissRequest = { playlistMenuExpanded = false }
                        ) {
                            playlistIndex.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = {
                                        vm.switchMusicPlaylist(p.id)
                                        playlistMenuExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(I18n.t("music.playlist_create")) },
                                onClick = {
                                    playlistMenuExpanded = false
                                    newPlaylistName = ""
                                    showCreatePlaylist = true
                                }
                            )
                            if (playlistIndex.size > 1) {
                                DropdownMenuItem(
                                    text = { Text(I18n.t("music.playlist_delete")) },
                                    onClick = {
                                        vm.deleteMusicPlaylist(activePlaylistId)
                                        playlistMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (playlist.isEmpty()) {
                        item(key = "playlist-empty") { EmptyState() }
                    } else {
                        itemsIndexed(playlist, key = { i, t -> "p-$i-${t.sourceUrl}" }) { index, track ->
                            PlaylistRow(
                                index = index,
                                track = track,
                                isCurrent = index == currentIndex,
                                isPlaying = index == currentIndex && state == PlaybackState.PLAYING,
                                onPlay = { vm.playOrToggleMusicAt(index) },
                                onRemove = { removeIndex = index }
                            )
                        }
                    }
                }
            }
            "history" -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        I18n.t(sectionTitleKey),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        I18n.t("music.track_count", history.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    TextButton(
                        onClick = { showClearHistoryDialog = true },
                        enabled = history.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("music.clear_history"))
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (history.isEmpty()) {
                        item(key = "history-empty") { HistoryEmptyState() }
                    } else {
                        itemsIndexed(history, key = { i, t -> "h-$i-${t.sourceUrl}" }) { index, track ->
                            HistoryRow(
                                track = track,
                                onPlay = { vm.playMusicFromHistory(track) },
                                onRemove = { vm.removeMusicHistoryAt(index) }
                            )
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "header") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    I18n.t(sectionTitleKey),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    I18n.t("music.subtitle"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            TextButton(onClick = { vm.clearMusicCache() }) {
                                Icon(Icons.Filled.CleaningServices, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(I18n.t("music.clear_cache"))
                            }
                        }
                    }
                    item(key = "url-input") {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(I18n.t("music.input_placeholder")) },
                            singleLine = true
                        )
                    }
                    item(key = "actions") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    vm.resolveAndAddMusicTrack(inputUrl)
                                    inputUrl = ""
                                },
                                enabled = loadingUrl == null && inputUrl.isNotBlank()
                            ) {
                                if (loadingUrl != null) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(if (loadingUrl != null) I18n.t("music.resolving") else I18n.t("music.resolve"))
                            }
                            OutlinedButton(onClick = {
                                val fd = FileDialog(null as Frame?, I18n.t("music.pick_files"), FileDialog.LOAD)
                                fd.isMultipleMode = true
                                fd.filenameFilter = FilenameFilter { _, name -> LocalAudioSource.hasAudioExt(name) }
                                fd.isVisible = true
                                val files = fd.files?.map { it.absolutePath }.orEmpty()
                                if (files.isNotEmpty()) vm.addLocalMusicFiles(files)
                            }) {
                                Icon(Icons.Filled.AudioFile, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(I18n.t("music.add_files"))
                            }
                            OutlinedButton(onClick = {
                                val chooser = JFileChooser().apply {
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    dialogTitle = I18n.t("music.pick_folder")
                                }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    vm.addLocalMusicFolder(chooser.selectedFile.absolutePath)
                                }
                            }) {
                                Icon(Icons.Filled.FolderOpen, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(I18n.t("music.add_folder"))
                            }
                        }
                    }
                    if (currentIndex in playlist.indices) {
                        item(key = "now-playing") {
                            NowPlayingCard(
                                playlist[currentIndex],
                                state,
                                currentMs,
                                durationMs,
                                lyrics,
                                vm
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        PlayerControls(state, currentMs, durationMs, volume, muted, repeatMode, shuffle, vm)
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text(I18n.t("music.playlist_create")) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    singleLine = true,
                    placeholder = { Text(I18n.t("music.playlist_untitled")) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createMusicPlaylist(newPlaylistName)
                    showCreatePlaylist = false
                }) { Text(I18n.t("common.confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylist = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(I18n.t("music.clear")) },
            text = { Text(I18n.t("music.clear_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearMusicPlaylist()
                    showClearDialog = false
                }) { Text(I18n.t("common.confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(I18n.t("music.clear_history")) },
            text = { Text(I18n.t("music.clear_history_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearMusicHistory()
                    showClearHistoryDialog = false
                }) { Text(I18n.t("common.confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }

    removeIndex?.let { idx ->
        if (idx in playlist.indices) {
            AlertDialog(
                onDismissRequest = { removeIndex = null },
                title = { Text(I18n.t("music.remove")) },
                text = { Text(I18n.t("music.remove_confirm", playlist[idx].title)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.removeMusicTrack(idx)
                        removeIndex = null
                    }) { Text(I18n.t("common.confirm")) }
                },
                dismissButton = {
                    TextButton(onClick = { removeIndex = null }) { Text(I18n.t("common.cancel")) }
                }
            )
        }
    }
}

@Composable
private fun NowPlayingCard(
    track: MusicTrack,
    state: PlaybackState,
    currentMs: Long,
    durationMs: Long,
    lyrics: List<LyricsLine>,
    vm: LauncherViewModel
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = glassSurfaceVariantColor(),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverThumbnail(track.coverUrl, size = 80.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        track.uploader.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceBadge(track.sourceType)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatMusicDuration(track.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (track.sourceType != "local" && track.sourceUrl.startsWith("http")) {
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { vm.openMusicSourceUrl(track.sourceUrl) }) {
                                Icon(Icons.Filled.OpenInNew, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(I18n.t("music.open_url"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                val isPlaying = state == PlaybackState.PLAYING
                val isLoading = state == PlaybackState.LOADING
                FilledIconButton(
                    onClick = { vm.toggleMusicPlayPause() },
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) I18n.t("music.pause") else I18n.t("music.play"),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            val dur = durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(1L)
            val progress = (currentMs.toFloat() / dur).coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatMusicDuration(currentMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = progress,
                    onValueChange = { v -> vm.seekMusicTo((v * dur).toLong()) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatMusicDuration(dur),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(Modifier.height(10.dp))
            LyricsPanel(lyrics, currentMs)
        }
    }
}

@Composable
private fun LyricsPanel(lyrics: List<LyricsLine>, currentMs: Long) {
    Text(
        I18n.t("music.lyrics"),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    if (lyrics.isEmpty()) {
        Text(
            I18n.t("music.lyrics_empty"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        return
    }
    val active = LyricsParser.indexAt(lyrics, currentMs)
    val scroll = rememberScrollState()
    LaunchedEffect(active) {
        if (active >= 0) {
            // 约每行 22dp，滚到当前行附近
            scroll.animateScrollTo((active * 22).coerceAtLeast(0))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        lyrics.forEachIndexed { i, line ->
            val on = i == active
            Text(
                line.text.ifBlank { " " },
                style = if (on) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                I18n.t("music.empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(2.dp))
            Text(
                I18n.t("music.empty_hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun HistoryEmptyState() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                I18n.t("music.history_empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun PlaylistRow(
    index: Int,
    track: MusicTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isCurrent) glassContainerColor(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                else glassContainerColor(MaterialTheme.colorScheme.surface),
        tonalElevation = if (isCurrent) 0.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent) {
                    Icon(
                        if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.uploader.ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    SourceBadge(track.sourceType, compact = true)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatMusicDuration(track.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) I18n.t("music.pause") else I18n.t("music.play"),
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = I18n.t("music.remove"),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    track: MusicTrack,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = glassContainerColor(MaterialTheme.colorScheme.surface),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverThumbnail(track.coverUrl, size = 36.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.uploader.ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    SourceBadge(track.sourceType, compact = true)
                }
            }
            IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = I18n.t("music.play"), Modifier.size(18.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = I18n.t("music.remove"),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlaybackState,
    currentMs: Long,
    durationMs: Long,
    volume: Int,
    muted: Boolean,
    repeatMode: Int,
    shuffle: Boolean,
    vm: LauncherViewModel
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = glassSurfaceVariantColor(glassAlpha = 0.4f),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { vm.playPreviousMusic() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = I18n.t("music.previous"))
                }
                FilledIconButton(
                    onClick = { vm.toggleMusicPlayPause() },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape
                ) {
                    val isLoading = state == PlaybackState.LOADING
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (state == PlaybackState.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = I18n.t("music.play"),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                IconButton(onClick = { vm.stopMusic() }) {
                    Icon(Icons.Filled.Stop, contentDescription = I18n.t("music.stop"))
                }
                IconButton(onClick = { vm.playNextMusic() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = I18n.t("music.next"))
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = { vm.cycleMusicRepeatMode() }) {
                    val tint = if (repeatMode != 0) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.outline
                    when (repeatMode) {
                        2 -> Icon(Icons.Filled.RepeatOne, contentDescription = I18n.t("music.repeat_one"), tint = tint)
                        1 -> Icon(Icons.Filled.Repeat, contentDescription = I18n.t("music.repeat_all"), tint = tint)
                        else -> Icon(Icons.Filled.Repeat, contentDescription = I18n.t("music.repeat_off"), tint = tint)
                    }
                }
                IconButton(onClick = { vm.toggleMusicShuffle() }) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = I18n.t("music.shuffle"),
                        tint = if (shuffle) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.toggleMusicMute() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (muted || volume == 0) Icons.Filled.VolumeOff
                        else if (volume < 50) Icons.Filled.VolumeDown
                        else Icons.Filled.VolumeUp,
                        contentDescription = I18n.t("music.mute"),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Slider(
                    value = if (muted) 0f else volume.toFloat(),
                    onValueChange = { vm.setMusicVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (muted) "0" else "$volume",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(28.dp)
                )
            }
        }
    }
}

@Composable
private fun SourceBadge(sourceType: String, compact: Boolean = false) {
    val (label, color) = when (sourceType) {
        "bilibili" -> I18n.t("music.source_bilibili") to Color(0xFFFB7299)
        "acfun"    -> I18n.t("music.source_acfun")    to Color(0xFFFD4C5D)
        "direct"   -> I18n.t("music.source_direct")   to MaterialTheme.colorScheme.outline
        "local"    -> I18n.t("music.source_local")    to Color(0xFF34C759)
        else       -> sourceType to MaterialTheme.colorScheme.outline
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(if (compact) 4.dp else 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
internal fun MusicCoverThumbnail(coverUrl: String, size: androidx.compose.ui.unit.Dp) {
    CoverThumbnail(coverUrl, size)
}

@Composable
private fun CoverThumbnail(coverUrl: String, size: androidx.compose.ui.unit.Dp) {
    val bmp = rememberMusicUrlImage(coverUrl)
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(8.dp),
        color = glassSurfaceVariantColor()
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(size / 2),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun formatMusicDuration(ms: Long): String {
    if (ms <= 0) return I18n.t("music.duration_unknown")
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m >= 60) {
        val h = m / 60
        val mm = m % 60
        "%d:%02d:%02d".format(h, mm, s)
    } else "%d:%02d".format(m, s)
}

private val musicImageCache = com.pmcl.ui.util.LruImageCache()

@Composable
internal fun rememberMusicUrlImage(url: String): ImageBitmap? {
    if (url.isEmpty()) return null
    val cached = musicImageCache.get(url)
    if (cached != null) return cached

    var image by remember(url) { mutableStateOf<ImageBitmap?>(musicImageCache.get(url)) }
    LaunchedEffect(url) {
        if (url.isEmpty()) { image = null; return@LaunchedEffect }
        if (musicImageCache.isKnownFailed(url)) { image = null; return@LaunchedEffect }
        val existing = musicImageCache.get(url)
        if (existing != null) { image = existing; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                val bytes = com.pmcl.ui.util.SafeUrlFetcher.fetchBytes(url)
                val bmp = decodeSampledBitmap(bytes, 256) ?: throw IllegalStateException("decode failed")
                musicImageCache.put(url, bmp)
                image = bmp
            } catch (_: Throwable) {
                musicImageCache.markFailed(url)
                image = null
            }
        }
    }
    return image
}
