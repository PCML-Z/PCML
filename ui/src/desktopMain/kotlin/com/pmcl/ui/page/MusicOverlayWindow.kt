package com.pmcl.ui.page

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.pmcl.ui.MacOverlay
import com.pmcl.ui.theme.LauncherTheme
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.ThemeState
import com.pmcl.core.i18n.I18n
import com.pmcl.music.player.PlaybackState
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.playNextMusic
import com.pmcl.ui.viewmodel.playPreviousMusic
import com.pmcl.ui.viewmodel.toggleMusicPlayPause
import java.awt.MouseInfo
import java.awt.geom.RoundRectangle2D

/**
 * 音乐播放悬浮窗。由播放器页手动打开，关掉即消失。
 */
@Composable
fun MusicOverlayWindow(vm: LauncherViewModel, themeState: ThemeState, onClose: () -> Unit) {
    val videoEnabled by vm.musicOverlayVideoEnabled.collectAsState()
    val videoUrl by vm.musicVideoUrl.collectAsState()
    val showVideo = videoEnabled && videoUrl.isNotBlank()
    val state = rememberWindowState(
        width = if (showVideo) 420.dp else 380.dp,
        height = if (showVideo) 392.dp else 156.dp,
        position = WindowPosition.Aligned(Alignment.BottomCenter)
    )
    LaunchedEffect(showVideo) {
        state.size = if (showVideo) DpSize(420.dp, 392.dp) else DpSize(380.dp, 156.dp)
    }
    Window(
        onCloseRequest = onClose,
        title = "PMCL Music",
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = true,
        resizable = false
    ) {
        DisposableEffect(Unit) {
            val update = {
                window.background = java.awt.Color(0, 0, 0, 0)
                window.shape = RoundRectangle2D.Double(
                    0.0, 0.0,
                    window.width.toDouble(), window.height.toDouble(),
                    32.0, 32.0
                )
                window.isAlwaysOnTop = true
                window.isAutoRequestFocus = false
            }
            update()
            MacOverlay.pin(window)
            val listener = object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    update()
                    MacOverlay.pin(window)
                }
                override fun componentShown(e: java.awt.event.ComponentEvent?) {
                    MacOverlay.pin(window)
                }
            }
            window.addComponentListener(listener)
            onDispose { window.removeComponentListener(listener) }
        }
        LaunchedEffect(Unit) {
            while (true) {
                MacOverlay.pin(window)
                delay(500)
            }
        }
        val scheme = if (themeState.dynamicColor || themeState.customAccentColor != -1) {
            themeState.dynamicColorScheme
        } else {
            null
        }
        LauncherTheme(
            useDarkTheme = themeState.useDark,
            dynamicColorScheme = scheme,
            uiScale = themeState.uiScale,
            launcherFont = themeState.launcherFont,
            themePreset = themeState.themePreset,
            colorMode = themeState.colorMode,
            customThemePack = themeState.customThemePack
        ) {
            CompositionLocalProvider(LocalThemeState provides themeState) {
                MusicOverlayCard(vm, window, onClose, showVideo, videoUrl)
            }
        }
    }
}

@Composable
private fun MusicOverlayCard(
    vm: LauncherViewModel,
    awtWindow: java.awt.Window,
    onClose: () -> Unit,
    showVideo: Boolean,
    videoUrl: String
) {
    val playlist by vm.musicPlaylist.collectAsState()
    val currentIndex by vm.musicCurrentIndex.collectAsState()
    val playback by vm.musicPlaybackState.collectAsState()
    val currentMs by vm.musicCurrentMs.collectAsState()
    val durationMs by vm.musicDurationMs.collectAsState()
    val lyrics by vm.musicLyrics.collectAsState()
    val videoHeaders by vm.musicVideoHeaders.collectAsState()
    val track = playlist.getOrNull(currentIndex)
    val cardColor = Color(0xE6121212)
    val corner = 16.dp
    val ink = Color(0xFFF5F5F7)
    val muted = Color(0xFFAEAEB2)
    if (track == null) {
        Surface(
            color = cardColor,
            shape = RoundedCornerShape(corner),
            modifier = Modifier.fillMaxSize().overlayDraggable(awtWindow)
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    I18n.t("music.overlay_empty"),
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, I18n.t("common.close"), tint = muted, modifier = Modifier.size(16.dp))
                }
            }
        }
        return
    }
    val dur = durationMs.coerceAtLeast(track.durationMs).coerceAtLeast(1L)
    val progress = (currentMs.toFloat() / dur).coerceIn(0f, 1f)

    Surface(
        color = cardColor,
        shape = RoundedCornerShape(corner),
        modifier = Modifier.fillMaxSize().overlayDraggable(awtWindow)
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
        if (showVideo) {
            val cover = rememberMusicUrlImage(track.coverUrl)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(214.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.45f
                    )
                }
                MusicOverlayVideo(
                    url = videoUrl,
                    headers = videoHeaders,
                    playback = playback,
                    currentMs = currentMs,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (showVideo) Modifier.height(74.dp) else Modifier.weight(1f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.clip(RoundedCornerShape(8.dp))) {
                MusicCoverThumbnail(track.coverUrl, 64.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    track.uploader,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = ink,
                    trackColor = Color.White.copy(alpha = 0.16f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatOverlayTime(currentMs, dur),
                    color = muted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Filled.Close, I18n.t("common.close"), tint = muted, modifier = Modifier.size(14.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.playPreviousMusic() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.SkipPrevious, I18n.t("music.previous"), tint = ink, modifier = Modifier.size(18.dp))
                    }
                    val playing = playback == PlaybackState.PLAYING
                    val loading = playback == PlaybackState.LOADING
                    IconButton(onClick = { vm.toggleMusicPlayPause() }, modifier = Modifier.size(36.dp)) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ink)
                        } else {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (playing) I18n.t("music.pause") else I18n.t("music.play"),
                                tint = ink,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    IconButton(onClick = { vm.playNextMusic() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.SkipNext, I18n.t("music.next"), tint = ink, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        LyricsViewport(
            lyrics = lyrics,
            currentMs = currentMs,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            accent = ink,
            idle = muted,
            emptyMessage = I18n.t("music.lyrics_empty"),
            compact = true
        )
        }
    }
}

private fun formatOverlayTime(currentMs: Long, durationMs: Long): String {
    fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
    return "${fmt(currentMs)} / ${fmt(durationMs)}"
}

private fun Modifier.overlayDraggable(awtWindow: java.awt.Window): Modifier = pointerInput(awtWindow) {
    var startMouse: java.awt.Point? = null
    var startWindow: java.awt.Point? = null
    detectDragGestures(
        onDragStart = {
            startMouse = MouseInfo.getPointerInfo()?.location
            startWindow = java.awt.Point(awtWindow.x, awtWindow.y)
        },
        onDragEnd = {
            startMouse = null
            startWindow = null
        },
        onDragCancel = {
            startMouse = null
            startWindow = null
        },
        onDrag = { change, _ ->
            val mouse = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
            val originMouse = startMouse ?: mouse.also { startMouse = it }
            val originWindow = startWindow ?: java.awt.Point(awtWindow.x, awtWindow.y).also { startWindow = it }
            change.consume()
            awtWindow.setLocation(
                originWindow.x + mouse.x - originMouse.x,
                originWindow.y + mouse.y - originMouse.y
            )
        }
    )
}
