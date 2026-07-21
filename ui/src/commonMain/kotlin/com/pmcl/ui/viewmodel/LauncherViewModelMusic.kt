package com.pmcl.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.page.MusicTrack
import com.pmcl.music.player.PlaybackState

/**
 * M29 拆分：音乐播放器域。
 *
 * 从 LauncherViewModel.kt 抽取的音乐相关状态操作扩展函数。
 * 状态字段保留在 LauncherViewModel 中（@PublishedApi internal），
 * 以便 UI 调用方（vm.musicPlaylist / vm.playMusicAt 等）签名不变。
 *
 * 后续可按相同模式抽取 NBT / DownloadQueue / ConfigFile / Multiplayer / News 等域。
 */

/** 解析 URL 并添加到播放列表 */
fun LauncherViewModel.resolveAndAddMusicTrack(url: String) {
    if (url.isBlank()) return
    scope.launch {
        _musicLoadingUrl.value = url
        try {
            val info = withContext(Dispatchers.IO) { audioResolver.resolve(url) }
            val track = MusicTrack(
                sourceUrl = url.trim(),
                title = info.title.ifBlank { url },
                uploader = info.uploader,
                durationMs = info.durationMs,
                coverUrl = info.coverUrl ?: "",
                sourceType = info.sourceType,
                originalId = info.originalId
            )
            // M33 修复：使用 MutableStateFlow.update {} 保证原子更新，避免并发追加丢失
            _musicPlaylist.update { it + track }
            persistMusicPlaylist()
            _status.value = I18n.t("music.resolve_success", track.title)
        } catch (e: Throwable) {
            _status.value = I18n.t("music.resolve_failed", e.message ?: "?")
        } finally {
            _musicLoadingUrl.value = null
        }
    }
}

/** 播放指定索引的曲目 */
fun LauncherViewModel.playMusicAt(index: Int) {
    val list = _musicPlaylist.value
    if (index !in list.indices) return
    val track = list[index]
    _musicCurrentIndex.value = index
    scope.launch {
        _musicPlaybackState.value = PlaybackState.LOADING
        try {
            val info = withContext(Dispatchers.IO) { audioResolver.resolve(track.sourceUrl) }
            withContext(Dispatchers.IO) {
                musicPlayer.play(info.audioUrl, info.headers, info.durationMs.coerceAtLeast(track.durationMs))
            }
        } catch (e: Throwable) {
            _musicPlaybackState.value = PlaybackState.ERROR
            _status.value = I18n.t("music.error_load", e.message ?: "?")
        }
    }
}

/** 播放/暂停切换 */
fun LauncherViewModel.toggleMusicPlayPause() {
    when (_musicPlaybackState.value) {
        PlaybackState.PLAYING -> musicPlayer.pause()
        PlaybackState.PAUSED -> musicPlayer.resume()
        PlaybackState.IDLE, PlaybackState.STOPPED, PlaybackState.ENDED, PlaybackState.ERROR -> {
            val idx = _musicCurrentIndex.value
            if (idx >= 0) playMusicAt(idx)
            else if (_musicPlaylist.value.isNotEmpty()) playMusicAt(0)
        }
        else -> {}
    }
}

fun LauncherViewModel.pauseMusic() { musicPlayer.pause() }
fun LauncherViewModel.resumeMusic() { musicPlayer.resume() }

fun LauncherViewModel.stopMusic() {
    musicPlayer.stop()
    _musicCurrentMs.value = 0
}

fun LauncherViewModel.playNextMusic() {
    val list = _musicPlaylist.value
    if (list.isEmpty()) return
    val cur = _musicCurrentIndex.value
    val next = if (_musicShuffle.value) {
        if (list.size == 1) 0 else (0 until list.size).filter { it != cur }.random()
    } else {
        when (_musicRepeatMode.value) {
            2 -> cur  // 单曲循环
            1 -> (cur + 1) % list.size  // 列表循环
            else -> if (cur + 1 < list.size) cur + 1 else -1  // 顺序，末尾停止
        }
    }
    if (next >= 0) playMusicAt(next)
    else stopMusic()
}

fun LauncherViewModel.playPreviousMusic() {
    val list = _musicPlaylist.value
    if (list.isEmpty()) return
    val cur = _musicCurrentIndex.value
    val prev = if (cur - 1 >= 0) cur - 1 else list.size - 1
    playMusicAt(prev)
}

fun LauncherViewModel.seekMusicTo(ms: Long) { musicPlayer.seekTo(ms) }

fun LauncherViewModel.setMusicVolume(v: Int) {
    val normalized = v.coerceIn(0, 100)
    _musicVolume.value = normalized
    _musicMuted.value = normalized == 0
    musicPlayer.setVolume(normalized)
}

fun LauncherViewModel.toggleMusicMute() {
    val muted = !_musicMuted.value
    _musicMuted.value = muted
    musicPlayer.setVolume(if (muted) 0 else _musicVolume.value)
}

fun LauncherViewModel.cycleMusicRepeatMode() {
    _musicRepeatMode.value = (_musicRepeatMode.value + 1) % 3
}

fun LauncherViewModel.toggleMusicShuffle() {
    _musicShuffle.value = !_musicShuffle.value
}

fun LauncherViewModel.removeMusicTrack(index: Int) {
    // M33 修复：使用 update {} 原子移除，避免并发修改丢失
    var removed = false
    _musicPlaylist.update { list ->
        if (index !in list.indices) return@update list
        removed = true
        list.toMutableList().apply { removeAt(index) }
    }
    if (!removed) return
    persistMusicPlaylist()
    // 调整当前索引
    val cur = _musicCurrentIndex.value
    when {
        index < cur -> _musicCurrentIndex.value = cur - 1
        index == cur -> {
            stopMusic()
            _musicCurrentIndex.value = -1
        }
    }
}

fun LauncherViewModel.clearMusicPlaylist() {
    stopMusic()
    _musicPlaylist.value = emptyList()
    _musicCurrentIndex.value = -1
    persistMusicPlaylist()
}

@PublishedApi
internal fun LauncherViewModel.persistMusicPlaylist() {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val file = java.io.File(System.getProperty("user.home"), ".pmcl/music/playlist.json")
                file.parentFile.mkdirs()
                file.writeText(gson.toJson(_musicPlaylist.value))
            }
        } catch (_: Throwable) {}
    }
}

/** 当前曲目（用于 MiniBar 显示） */
val LauncherViewModel.currentMusicTrack: MusicTrack?
    get() {
        val idx = _musicCurrentIndex.value
        val list = _musicPlaylist.value
        return if (idx in list.indices) list[idx] else null
    }
