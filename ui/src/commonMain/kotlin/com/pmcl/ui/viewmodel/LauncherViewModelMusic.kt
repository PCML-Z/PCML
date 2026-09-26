package com.pmcl.ui.viewmodel

import com.google.gson.reflect.TypeToken
import com.pmcl.core.i18n.I18n
import com.pmcl.music.cache.AudioCache
import com.pmcl.music.lyrics.LyricsLine
import com.pmcl.music.lyrics.LyricsParser
import com.pmcl.music.lyrics.LyricsProvider
import com.pmcl.music.player.PlaybackState
import com.pmcl.music.source.LocalAudioSource
import com.pmcl.ui.page.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.charset.CharacterCodingException
import java.util.UUID

/**
 * 音乐播放器域：解析 / 播放 / 多列表 / 历史 / 缓存 / 歌词。
 */

private const val MUSIC_HISTORY_LIMIT = 50
private const val DEFAULT_PLAYLIST_ID = "default"

private fun LauncherViewModel.syncOptionalMusicSources() {
    val p = preferences
    audioResolver.setOptionalSources(p.isParseKuaishou(), p.isParseDouyin(), p.isParseYoutube())
}

/** 解析 URL / 本地路径并添加到当前播放列表 */
fun LauncherViewModel.resolveAndAddMusicTrack(url: String) {
    if (url.isBlank()) return
    val trimmed = url.trim()
    val existing = _musicPlaylist.value.indexOfFirst { it.sourceUrl == trimmed }
    if (existing >= 0) {
        _status.value = I18n.t("music.already_in_playlist", _musicPlaylist.value[existing].title)
        return
    }
    scope.launch {
        _musicLoadingUrl.value = trimmed
        try {
            val info = withContext(Dispatchers.IO) {
                syncOptionalMusicSources()
                audioResolver.resolve(trimmed)
            }
            val track = MusicTrack(
                sourceUrl = if (info.sourceType == "local") info.audioUrl else trimmed,
                title = info.title.ifBlank { trimmed },
                uploader = info.uploader,
                durationMs = info.durationMs,
                coverUrl = info.coverUrl ?: "",
                sourceType = info.sourceType,
                originalId = info.originalId
            )
            _musicPlaylist.update { it + track }
            persistMusicPlaylist()
            _status.value = I18n.t("music.resolve_success", track.title)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("music.resolve_failed", e.message ?: "?")
        } finally {
            _musicLoadingUrl.value = null
        }
    }
}

/** 批量添加本地文件路径 */
fun LauncherViewModel.addLocalMusicFiles(paths: List<String>) {
    if (paths.isEmpty()) return
    scope.launch {
        var added = 0
        for (p in paths) {
            val abs = File(p).absolutePath
            if (_musicPlaylist.value.any { it.sourceUrl == abs }) continue
            try {
                val info = withContext(Dispatchers.IO) { audioResolver.resolve(abs) }
                val track = MusicTrack(
                    sourceUrl = info.audioUrl,
                    title = info.title,
                    uploader = info.uploader,
                    durationMs = info.durationMs,
                    coverUrl = "",
                    sourceType = "local",
                    originalId = info.audioUrl
                )
                _musicPlaylist.update { it + track }
                added++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                System.err.println("[Music] skip local $p: ${e.message}")
            }
        }
        if (added > 0) {
            persistMusicPlaylist()
            _status.value = I18n.t("music.local_added", added)
        } else {
            _status.value = I18n.t("music.local_none_added")
        }
    }
}

/** 扫描文件夹内音频并添加 */
fun LauncherViewModel.addLocalMusicFolder(folderPath: String) {
    scope.launch {
        val files = withContext(Dispatchers.IO) {
            val dir = File(folderPath)
            if (!dir.isDirectory) emptyList()
            else dir.walkTopDown()
                .maxDepth(3)
                .filter { LocalAudioSource.isSupportedAudioFile(it) }
                .map { it.absolutePath }
                .toList()
        }
        addLocalMusicFiles(files)
    }
}

fun LauncherViewModel.playMusicAt(index: Int) {
    musicPlaybackRetryCount.set(0)
    startMusicAt(index, fresh = false)
}

private fun LauncherViewModel.startMusicAt(index: Int, fresh: Boolean) {
    val list = _musicPlaylist.value
    if (index !in list.indices) return
    val track = list[index]
    _musicCurrentIndex.value = index
    _musicCurrentMs.value = 0L
    _musicDurationMs.value = track.durationMs
    _musicLyrics.value = emptyList()
    _musicVideoUrl.value = ""
    _musicVideoHeaders.value = emptyMap()
    musicPlayJob?.cancel()
    musicPlayJob = scope.launch {
        _musicPlaybackState.value = PlaybackState.LOADING
        try {
            val info = withContext(Dispatchers.IO) {
                syncOptionalMusicSources()
                if (fresh) audioResolver.resolveFresh(track.sourceUrl)
                else audioResolver.resolve(track.sourceUrl)
            }
            // 缓存命中时播放本地文件；未命中立即播放远程流。
            // 不再等整首歌下载完，慢速 CDN 也不会让界面一直停在 LOADING。
            val playUrl = withContext(Dispatchers.IO) {
                audioCache.findCached(info.sourceType, info.originalId, info.audioUrl)
                    ?: info.audioUrl
            }
            if (!isActive) return@launch
            _musicVideoUrl.value = info.videoUrl
            _musicVideoHeaders.value = info.headers.toMap()
            val headers = if (playUrl.startsWith("http")) info.headers else emptyMap()
            withContext(Dispatchers.IO) {
                musicPlayer.play(playUrl, headers, info.durationMs.coerceAtLeast(track.durationMs))
            }
            prefetchNextMusic(index)
            recordMusicHistory(track)
            loadMusicLyrics(track)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _musicVideoUrl.value = ""
            _musicVideoHeaders.value = emptyMap()
            _musicPlaybackState.value = PlaybackState.ERROR
            _status.value = I18n.t("music.error_load", e.message ?: "?")
        }
    }
}

/** 播放流过期或 CDN 拒绝时，清掉临时地址并自动重新解析一次。 */
internal fun LauncherViewModel.retryCurrentMusicAfterError(): Boolean {
    val index = _musicCurrentIndex.value
    val list = _musicPlaylist.value
    if (index !in list.indices) return false
    if (!musicPlaybackRetryCount.compareAndSet(0, 1)) return false
    audioResolver.invalidate(list[index].sourceUrl)
    _status.value = I18n.t("music.retrying")
    startMusicAt(index, fresh = true)
    return true
}

/** 当前曲目稳定播放后，低优先级预解析下一首，自动切歌时可直接打开流。 */
private fun LauncherViewModel.prefetchNextMusic(index: Int) {
    val snapshot = _musicPlaylist.value
    if (snapshot.size < 2) return
    val next = (index + 1) % snapshot.size
    val nextUrl = snapshot[next].sourceUrl
    scope.launch {
        kotlinx.coroutines.delay(2_000)
        if (_musicCurrentIndex.value != index || _musicPlaybackState.value != PlaybackState.PLAYING) {
            return@launch
        }
        try {
            withContext(Dispatchers.IO) {
                syncOptionalMusicSources()
                audioResolver.resolve(nextUrl)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 预解析只是优化，不影响当前播放，也不向用户报错。
        }
    }
}

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

fun LauncherViewModel.playOrToggleMusicAt(index: Int) {
    if (index == _musicCurrentIndex.value && _musicPlaybackState.value == PlaybackState.PLAYING) {
        toggleMusicPlayPause()
    } else {
        playMusicAt(index)
    }
}

fun LauncherViewModel.pauseMusic() { musicPlayer.pause() }
fun LauncherViewModel.resumeMusic() { musicPlayer.resume() }

fun LauncherViewModel.stopMusic() {
    musicPlayJob?.cancel()
    musicPlayJob = null
    musicPlayer.stop()
    _musicCurrentMs.value = 0
    _musicVideoUrl.value = ""
    _musicVideoHeaders.value = emptyMap()
}

fun LauncherViewModel.playNextMusic() {
    val list = _musicPlaylist.value
    if (list.isEmpty()) return
    val cur = _musicCurrentIndex.value
    val next = if (_musicShuffle.value) {
        if (list.size == 1) 0 else (0 until list.size).filter { it != cur }.random()
    } else {
        when (_musicRepeatMode.value) {
            2 -> cur
            1 -> (cur + 1) % list.size
            else -> if (cur + 1 < list.size) cur + 1 else -1
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
    if (normalized > 0) {
        _musicVolume.value = normalized
        _musicVolumeBeforeMute.value = normalized
        _musicMuted.value = false
        musicPlayer.setVolume(normalized)
    } else {
        if (_musicVolume.value > 0) _musicVolumeBeforeMute.value = _musicVolume.value
        _musicVolume.value = 0
        _musicMuted.value = true
        musicPlayer.setVolume(0)
    }
    persistMusicPrefs()
}

fun LauncherViewModel.toggleMusicMute() {
    if (_musicMuted.value) {
        val restore = _musicVolumeBeforeMute.value.coerceIn(1, 100)
        _musicMuted.value = false
        _musicVolume.value = restore
        musicPlayer.setVolume(restore)
    } else {
        val current = _musicVolume.value.coerceIn(1, 100)
        if (current > 0) _musicVolumeBeforeMute.value = current
        _musicMuted.value = true
        musicPlayer.setVolume(0)
    }
    persistMusicPrefs()
}

fun LauncherViewModel.cycleMusicRepeatMode() {
    _musicRepeatMode.value = (_musicRepeatMode.value + 1) % 3
    persistMusicPrefs()
}

fun LauncherViewModel.toggleMusicShuffle() {
    _musicShuffle.value = !_musicShuffle.value
    persistMusicPrefs()
}

fun LauncherViewModel.removeMusicTrack(index: Int) {
    var removed = false
    _musicPlaylist.update { list ->
        if (index !in list.indices) return@update list
        removed = true
        list.toMutableList().apply { removeAt(index) }
    }
    if (!removed) return
    persistMusicPlaylist()
    val cur = _musicCurrentIndex.value
    when {
        index < cur -> _musicCurrentIndex.value = cur - 1
        index == cur -> {
            stopMusic()
            _musicCurrentIndex.value = -1
            _musicLyrics.value = emptyList()
        }
    }
}

fun LauncherViewModel.clearMusicPlaylist() {
    stopMusic()
    _musicPlaylist.value = emptyList()
    _musicCurrentIndex.value = -1
    _musicLyrics.value = emptyList()
    persistMusicPlaylist()
}

fun LauncherViewModel.openMusicSourceUrl(url: String) {
    if (url.isBlank() || url.startsWith("/") || url.contains(":\\") || url.startsWith("file:")) return
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI(url))
                }
            }
        } catch (t: Throwable) {
            _status.value = I18n.t("music.open_url_failed", t.message ?: "?")
        }
    }
}

fun LauncherViewModel.playMusicFromHistory(track: MusicTrack) {
    val existing = _musicPlaylist.value.indexOfFirst { it.sourceUrl == track.sourceUrl }
    if (existing >= 0) {
        playMusicAt(existing)
        return
    }
    _musicPlaylist.update { it + track }
    persistMusicPlaylist()
    playMusicAt(_musicPlaylist.value.lastIndex)
}

fun LauncherViewModel.removeMusicHistoryAt(index: Int) {
    _musicHistory.update { list ->
        if (index !in list.indices) list
        else list.toMutableList().apply { removeAt(index) }
    }
    persistMusicHistory()
}

fun LauncherViewModel.clearMusicHistory() {
    _musicHistory.value = emptyList()
    persistMusicHistory()
}

fun LauncherViewModel.clearMusicCache() {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { audioCache.clear() }
            _status.value = I18n.t("music.cache_cleared")
        } catch (t: Throwable) {
            _status.value = I18n.t("music.cache_clear_failed", t.message ?: "?")
        }
    }
}

// ===== 多播放列表 =====

fun LauncherViewModel.createMusicPlaylist(name: String) {
    val n = name.trim().ifBlank { I18n.t("music.playlist_untitled") }
    val id = UUID.randomUUID().toString().take(8)
    val meta = MusicPlaylistMeta(id = id, name = n)
    _musicPlaylistIndex.update { it + meta }
    persistMusicPlaylistIndex()
    // 空文件
    scope.launch {
        withContext(Dispatchers.IO) {
            playlistFile(id).also { it.parentFile.mkdirs(); it.writeText("[]") }
        }
    }
    switchMusicPlaylist(id)
}

fun LauncherViewModel.renameMusicPlaylist(id: String, name: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    _musicPlaylistIndex.update { list ->
        list.map { if (it.id == id) it.copy(name = n) else it }
    }
    persistMusicPlaylistIndex()
}

fun LauncherViewModel.deleteMusicPlaylist(id: String) {
    if (_musicPlaylistIndex.value.size <= 1) {
        _status.value = I18n.t("music.playlist_keep_one")
        return
    }
    _musicPlaylistIndex.update { it.filterNot { p -> p.id == id } }
    persistMusicPlaylistIndex()
    scope.launch {
        withContext(Dispatchers.IO) { playlistFile(id).delete() }
    }
    if (_musicActivePlaylistId.value == id) {
        val next = _musicPlaylistIndex.value.firstOrNull()?.id ?: DEFAULT_PLAYLIST_ID
        switchMusicPlaylist(next)
    }
}

fun LauncherViewModel.switchMusicPlaylist(id: String) {
    if (id == _musicActivePlaylistId.value) return
    // 先保存当前（异步，避免阻塞 UI）
    scope.launch { persistMusicPlaylistSync() }
    stopMusic()
    _musicCurrentIndex.value = -1
    _musicLyrics.value = emptyList()
    _musicActivePlaylistId.value = id
    persistMusicPrefs()
    val gen = musicPlaylistLoadGen.incrementAndGet()
    scope.launch {
        try {
            val list = withContext(Dispatchers.IO) {
                val f = playlistFile(id)
                if (!f.exists()) emptyList()
                else {
                    val type = object : TypeToken<List<MusicTrack>>() {}.type
                    gson.fromJson<List<MusicTrack>>(f.readText(), type) ?: emptyList()
                }
            }
            // H46: 丢弃过期加载结果
            if (gen != musicPlaylistLoadGen.get() || id != _musicActivePlaylistId.value) return@launch
            _musicPlaylist.value = list
        } catch (t: Throwable) {
            if (gen != musicPlaylistLoadGen.get()) return@launch
            _musicPlaylist.value = emptyList()
            System.err.println("[Music] load playlist $id: ${t.message}")
        }
    }
}

@PublishedApi
internal fun LauncherViewModel.loadMusicLyrics(track: MusicTrack) {
    scope.launch {
        val lines = withContext(Dispatchers.IO) { readLyricsFor(track) }
        if (currentMusicTrack?.sourceUrl == track.sourceUrl) {
            _musicLyrics.value = lines
        }
    }
}

fun LauncherViewModel.hasImportedMusicLyrics(track: MusicTrack): Boolean =
    importedLyricsFile(track).isFile

/** 把用户选的 .lrc / 文本绑到这首曲子上，之后播放优先用这份。 */
fun LauncherViewModel.importMusicLyrics(
    track: MusicTrack,
    path: String,
    onImported: (Boolean) -> Unit = {}
) {
    scope.launch {
        try {
            val lines = withContext(Dispatchers.IO) {
                val text = readLyricsText(File(path))
                val parsed = LyricsParser.parse(text)
                if (parsed.isEmpty()) return@withContext emptyList()
                val dest = importedLyricsFile(track)
                dest.parentFile.mkdirs()
                dest.writeText(text, Charsets.UTF_8)
                parsed
            }
            if (lines.isEmpty()) {
                _status.value = I18n.t("music.lyrics_import_empty")
                onImported(false)
                return@launch
            }
            if (currentMusicTrack?.sourceUrl == track.sourceUrl) {
                _musicLyrics.value = lines
            }
            _status.value = I18n.t("music.lyrics_imported")
            onImported(true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("music.lyrics_import_failed", e.message ?: "?")
            onImported(false)
        }
    }
}

fun LauncherViewModel.clearImportedMusicLyrics(track: MusicTrack) {
    scope.launch {
        withContext(Dispatchers.IO) { importedLyricsFile(track).delete() }
        if (currentMusicTrack?.sourceUrl == track.sourceUrl) {
            _musicLyrics.value = withContext(Dispatchers.IO) { readLyricsFor(track) }
        }
        _status.value = I18n.t("music.lyrics_cleared")
    }
}

private fun LauncherViewModel.readLyricsFor(track: MusicTrack): List<LyricsLine> {
    val imported = importedLyricsFile(track)
    if (imported.isFile) {
        val lines = LyricsParser.parse(readLyricsText(imported))
        if (lines.isNotEmpty()) return lines
    }
    return lyricsProvider.fetch(track.sourceType, track.sourceUrl, track.originalId)
}

@PublishedApi
internal fun LauncherViewModel.recordMusicHistory(track: MusicTrack) {
    _musicHistory.update { list ->
        val without = list.filterNot { it.sourceUrl == track.sourceUrl }
        (listOf(track) + without).take(MUSIC_HISTORY_LIMIT)
    }
    persistMusicHistory()
}

@PublishedApi
internal fun LauncherViewModel.persistMusicPlaylist() {
    scope.launch { persistMusicPlaylistSync() }
}

private fun LauncherViewModel.persistMusicPlaylistSync() {
    try {
        val id = _musicActivePlaylistId.value.ifBlank { DEFAULT_PLAYLIST_ID }
        val file = playlistFile(id)
        file.parentFile.mkdirs()
        file.writeText(gson.toJson(_musicPlaylist.value))
        // 兼容旧路径
        File(musicDir(), "playlist.json").writeText(gson.toJson(_musicPlaylist.value))
    } catch (t: Throwable) {
        System.err.println("[VM] 保存音乐播放列表失败: ${t.message}")
        _status.value = I18n.t(
            "music.playlist_save_failed", t.message ?: I18n.t("common.unknown")
        )
    }
}

@PublishedApi
internal fun LauncherViewModel.persistMusicPlaylistIndex() {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val file = File(playlistsDir(), "index.json")
                file.parentFile.mkdirs()
                val index = MusicPlaylistIndex(
                    activeId = _musicActivePlaylistId.value,
                    playlists = _musicPlaylistIndex.value
                )
                file.writeText(gson.toJson(index))
            }
        } catch (t: Throwable) {
            System.err.println("[VM] 保存播放列表索引失败: ${t.message}")
        }
    }
}

@PublishedApi
internal fun LauncherViewModel.persistMusicPrefs() {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val file = File(musicDir(), "prefs.json")
                file.parentFile.mkdirs()
                val prefs = MusicPrefs(
                    volume = _musicVolume.value,
                    muted = _musicMuted.value,
                    volumeBeforeMute = _musicVolumeBeforeMute.value,
                    repeatMode = _musicRepeatMode.value,
                    shuffle = _musicShuffle.value,
                    activePlaylistId = _musicActivePlaylistId.value
                )
                file.writeText(gson.toJson(prefs))
            }
        } catch (t: Throwable) {
            System.err.println("[VM] 保存音乐偏好失败: ${t.message}")
        }
    }
}

@PublishedApi
internal fun LauncherViewModel.persistMusicHistory() {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val file = File(musicDir(), "history.json")
                file.parentFile.mkdirs()
                file.writeText(gson.toJson(_musicHistory.value))
            }
        } catch (t: Throwable) {
            System.err.println("[VM] 保存音乐历史失败: ${t.message}")
        }
    }
}

@PublishedApi
internal fun LauncherViewModel.loadMusicPersistedState() {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                musicDir().mkdirs()
                playlistsDir().mkdirs()

                // 索引
                val indexFile = File(playlistsDir(), "index.json")
                var activeId = DEFAULT_PLAYLIST_ID
                if (indexFile.exists()) {
                    val index = gson.fromJson(indexFile.readText(), MusicPlaylistIndex::class.java)
                    if (index != null && index.playlists.isNotEmpty()) {
                        _musicPlaylistIndex.value = index.playlists
                        activeId = index.activeId.ifBlank { index.playlists.first().id }
                    }
                } else {
                    // 迁移旧 playlist.json
                    val legacy = File(musicDir(), "playlist.json")
                    val defaultMeta = MusicPlaylistMeta(DEFAULT_PLAYLIST_ID, I18n.t("music.playlist_default"))
                    _musicPlaylistIndex.value = listOf(defaultMeta)
                    if (legacy.exists()) {
                        playlistFile(DEFAULT_PLAYLIST_ID).writeText(legacy.readText())
                    } else {
                        playlistFile(DEFAULT_PLAYLIST_ID).writeText("[]")
                    }
                    File(playlistsDir(), "index.json").writeText(
                        gson.toJson(MusicPlaylistIndex(DEFAULT_PLAYLIST_ID, listOf(defaultMeta)))
                    )
                }

                val prefsFile = File(musicDir(), "prefs.json")
                if (prefsFile.exists()) {
                    val prefs = gson.fromJson(prefsFile.readText(), MusicPrefs::class.java)
                    if (prefs != null) {
                        // 旧默认音量 80，比 B 站网页满音量小一截。没改过的升到 100。
                        val saved = prefs.volume.coerceIn(0, 100)
                        val before = prefs.volumeBeforeMute.coerceIn(1, 100)
                        val volume = if (saved == 80 && before == 80) 100 else saved
                        _musicVolume.value = volume
                        _musicVolumeBeforeMute.value = if (volume == 100 && before == 80) 100 else before
                        _musicMuted.value = prefs.muted
                        _musicRepeatMode.value = prefs.repeatMode.coerceIn(0, 2)
                        _musicShuffle.value = prefs.shuffle
                        if (prefs.activePlaylistId.isNotBlank()) activeId = prefs.activePlaylistId
                        musicPlayer.setVolume(if (prefs.muted) 0 else volume)
                    }
                }

                if (_musicPlaylistIndex.value.none { it.id == activeId }) {
                    activeId = _musicPlaylistIndex.value.firstOrNull()?.id ?: DEFAULT_PLAYLIST_ID
                }
                _musicActivePlaylistId.value = activeId

                val pf = playlistFile(activeId)
                if (pf.exists()) {
                    val type = object : TypeToken<List<MusicTrack>>() {}.type
                    _musicPlaylist.value = gson.fromJson(pf.readText(), type) ?: emptyList()
                } else {
                    val legacy = File(musicDir(), "playlist.json")
                    if (legacy.exists()) {
                        val type = object : TypeToken<List<MusicTrack>>() {}.type
                        _musicPlaylist.value = gson.fromJson(legacy.readText(), type) ?: emptyList()
                    }
                }

                val historyFile = File(musicDir(), "history.json")
                if (historyFile.exists()) {
                    val type = object : TypeToken<List<MusicTrack>>() {}.type
                    val list: List<MusicTrack> = gson.fromJson(historyFile.readText(), type) ?: emptyList()
                    _musicHistory.value = list.take(MUSIC_HISTORY_LIMIT)
                }
            }
        } catch (t: Throwable) {
            System.err.println("[VM] 加载音乐持久化数据失败: ${t.message}")
            _status.value = I18n.t("music.playlist_load_failed", t.message ?: I18n.t("common.unknown"))
        }
    }
}

val LauncherViewModel.currentMusicTrack: MusicTrack?
    get() {
        val idx = _musicCurrentIndex.value
        val list = _musicPlaylist.value
        return if (idx in list.indices) list[idx] else null
    }

private fun musicDir(): File =
    File(System.getProperty("user.home"), ".pmcl/music")

private fun playlistsDir(): File = File(musicDir(), "playlists")

private fun playlistFile(id: String): File = File(playlistsDir(), "$id.json")

private fun importedLyricsFile(track: MusicTrack): File {
    val id = track.originalId.ifBlank { track.sourceUrl }
    val raw = track.sourceType + "\n" + id
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return File(musicDir(), "lyrics/$hex.lrc")
}

private fun readLyricsText(file: File): String {
    val bytes = file.readBytes()
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, charset("GB18030"))
    }
}

internal data class MusicPrefs(
    val volume: Int = 100,
    val muted: Boolean = false,
    val volumeBeforeMute: Int = 100,
    val repeatMode: Int = 0,
    val shuffle: Boolean = false,
    val activePlaylistId: String = DEFAULT_PLAYLIST_ID
)

data class MusicPlaylistMeta(
    val id: String,
    val name: String
)

internal data class MusicPlaylistIndex(
    val activeId: String = DEFAULT_PLAYLIST_ID,
    val playlists: List<MusicPlaylistMeta> = emptyList()
)
