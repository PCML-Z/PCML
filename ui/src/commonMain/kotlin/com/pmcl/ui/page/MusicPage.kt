package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.music.player.PlaybackState
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URL

/**
 * 音乐播放器页面：URL 解析 → 播放列表 → 当前曲目卡片 → 播放控制。
 */
@Composable
fun MusicPage(vm: LauncherViewModel) {
    val playlist by vm.musicPlaylist.collectAsState()
    val currentIndex by vm.musicCurrentIndex.collectAsState()
    val state by vm.musicPlaybackState.collectAsState()
    val currentMs by vm.musicCurrentMs.collectAsState()
    val durationMs by vm.musicDurationMs.collectAsState()
    val volume by vm.musicVolume.collectAsState()
    val muted by vm.musicMuted.collectAsState()
    val loadingUrl by vm.musicLoadingUrl.collectAsState()
    val repeatMode by vm.musicRepeatMode.collectAsState()
    val shuffle by vm.musicShuffle.collectAsState()

    var inputUrl by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var removeIndex by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 标题
        Text(
            I18n.t("music.title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            I18n.t("music.subtitle"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))

        // 输入栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(I18n.t("music.input_placeholder")) },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
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
        }

        Spacer(Modifier.height(16.dp))

        // 当前播放卡片
        currentIndex.takeIf { it >= 0 && it < playlist.size }?.let { idx ->
            NowPlayingCard(playlist[idx], state, currentMs, durationMs, vm)
            Spacer(Modifier.height(12.dp))
        }

        // 播放列表标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                I18n.t("music.playlist"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                I18n.t("music.track_count", playlist.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            if (playlist.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Filled.DeleteSweep, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("music.clear"))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 列表
        if (playlist.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(playlist, key = { i, t -> "$i-${t.sourceUrl}" }) { index, track ->
                    PlaylistRow(
                        index = index,
                        track = track,
                        isCurrent = index == currentIndex,
                        isPlaying = index == currentIndex && state == PlaybackState.PLAYING,
                        onPlay = { vm.playMusicAt(index) },
                        onRemove = { removeIndex = index }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 播放控制
        PlayerControls(state, currentMs, durationMs, volume, muted, repeatMode, shuffle, vm)
    }

    // 清空确认
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

    // 移除确认
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

// ===== 辅助 Composable =====

/** 当前播放曲目卡片：封面 + 标题 + UP主 + 来源徽章 + 时长 + 进度条 */
@Composable
private fun NowPlayingCard(
    track: MusicTrack,
    state: PlaybackState,
    currentMs: Long,
    durationMs: Long,
    vm: LauncherViewModel
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 封面图（80x80，无封面时用 MusicNote 占位）
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
                    }
                }
                // 播放/暂停按钮
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

            // 进度条 + 时间
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
        }
    }
}

/** 空状态：图标 + 提示 */
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

/** 列表行：序号/播放图标 + 标题 + UP主/时长 + 来源徽章 + 移除按钮 */
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
        color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isCurrent) 0.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号或播放图标
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

            // 播放按钮
            IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = I18n.t("music.play"),
                    modifier = Modifier.size(18.dp)
                )
            }
            // 移除按钮
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

/** 底部播放控制栏 */
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            // 第一行：上一曲 / 播放暂停 / 下一曲  ...  循环模式 / 随机
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
                IconButton(onClick = { vm.playNextMusic() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = I18n.t("music.next"))
                }

                Spacer(Modifier.weight(1f))

                // 循环模式：0=顺序(灰 Repeat), 1=列表循环(亮 Repeat), 2=单曲循环(RepeatOne)
                IconButton(onClick = { vm.cycleMusicRepeatMode() }) {
                    val tint = if (repeatMode != 0) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.outline
                    when (repeatMode) {
                        2 -> Icon(Icons.Filled.RepeatOne, contentDescription = I18n.t("music.repeat_one"), tint = tint)
                        else -> Icon(Icons.Filled.Repeat, contentDescription = I18n.t("music.repeat_all"), tint = tint)
                    }
                }
                // 随机
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

            // 第二行：音量
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.toggleMusicMute() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (muted || volume == 0) Icons.Filled.VolumeOff
                        else if (volume < 50) Icons.Filled.VolumeDown
                        else Icons.Filled.VolumeUp,
                        contentDescription = I18n.t("music.volume"),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { vm.setMusicVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$volume",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(28.dp)
                )
            }
        }
    }
}

/** 来源徽章：B站/A站/直链 */
@Composable
private fun SourceBadge(sourceType: String, compact: Boolean = false) {
    val (label, color) = when (sourceType) {
        "bilibili" -> I18n.t("music.source_bilibili") to Color(0xFFFB7299)
        "acfun"    -> I18n.t("music.source_acfun")    to Color(0xFFFD4C5D)
        "direct"   -> I18n.t("music.source_direct")   to MaterialTheme.colorScheme.outline
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

/** 封面缩略图：异步从 URL 加载，失败/无封面时用 MusicNote Icon 占位 */
@Composable
private fun CoverThumbnail(coverUrl: String, size: androidx.compose.ui.unit.Dp) {
    val bmp = rememberUrlImage(coverUrl)
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
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

// ===== 工具函数 =====

/** 时长格式化：mm:ss 或 h:mm:ss；未知显示 music.duration_unknown */
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

/** 图片内存缓存：URL → ImageBitmap。 */
private val musicImageCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, ImageBitmap?>())

/** 异步从 URL 加载图片，返回 Skia 解码的 ImageBitmap。失败返回 null。 */
@Composable
private fun rememberUrlImage(url: String): ImageBitmap? {
    if (url.isEmpty()) return null
    val cached = musicImageCache[url]
    if (cached != null) return cached

    var image by remember(url) { mutableStateOf<ImageBitmap?>(musicImageCache[url]) }
    LaunchedEffect(url) {
        if (url.isEmpty()) { image = null; return@LaunchedEffect }
        if (musicImageCache.containsKey(url)) {
            image = musicImageCache[url]
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            try {
                val bytes = URL(url).readBytes()
                val bmp = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                musicImageCache[url] = bmp
                synchronized(musicImageCache) {
                    while (musicImageCache.size > 50) {
                        val it = musicImageCache.keys.iterator()
                        if (it.hasNext()) { it.next(); it.remove() } else break
                    }
                }
                image = bmp
            } catch (_: Throwable) {
                musicImageCache[url] = null
                image = null
            }
        }
    }
    return image
}
