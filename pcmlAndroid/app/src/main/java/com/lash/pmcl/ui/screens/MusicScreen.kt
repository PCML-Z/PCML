package com.lash.pmcl.ui.screens

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MusicScreen() {
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<File>>(emptyList()) }
    var playlistName by remember { mutableStateOf("默认播放列表") }
    var history by remember { mutableStateOf<List<File>>(emptyList()) }
    var status by remember { mutableStateOf("就绪") }
    var loading by remember { mutableStateOf(true) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPos by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var volume by remember { mutableFloatStateOf(0.7f) }

    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showClearHistory by remember { mutableStateOf(false) }
    var tabIdx by remember { mutableIntStateOf(0) } // 0=playlist 1=history

    val playerRef = remember { mutableStateOf<MediaPlayer?>(null) }
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun extractCover(file: File): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val art = retriever.embeddedPicture
        retriever.release()
        if (art != null) android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size) else null
    } catch (_: Exception) { null }

    fun scan() {
        scope.launch {
            loading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val dirs = listOf(File("/sdcard/Music"), File("/sdcard/Download"),
                        File("/storage/emulated/0/Music"))
                    val all = mutableListOf<File>()
                    for (d in dirs) {
                        if (d.isDirectory) d.listFiles()?.filter {
                            it.name.endsWith(".mp3", true) || it.name.endsWith(".wav", true) ||
                            it.name.endsWith(".ogg", true) || it.name.endsWith(".m4a", true) ||
                            it.name.endsWith(".flac", true)
                        }?.let { all.addAll(it) }
                    }
                    all.sortedBy { it.name }
                }
                tracks = result
                status = "已找到 ${tracks.size} 首曲目"
            } catch (e: Exception) { status = "扫描失败: ${e.message}" }
            finally { loading = false }
        }
    }

    fun play(index: Int) {
        val file = tracks.getOrNull(index) ?: return
        playerRef.value?.release()
        scope.launch(Dispatchers.IO) {
            try {
                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.setVolume(volume, volume)
                mp.setOnCompletionListener {
                    isPlaying = false; currentPos = 0
                    if (index + 1 < tracks.size) play(index + 1)
                }
                withContext(Dispatchers.Main) {
                    playerRef.value = mp
                    mp.start()
                    currentIndex = index; isPlaying = true; duration = mp.duration
                    MusicState.currentTrack = file.name; MusicState.isPlaying = true
                    MusicState.durationMs = mp.duration
                    status = file.name
                }
                val art = extractCover(file)
                withContext(Dispatchers.Main) { coverBitmap = art }
                // history add
                history = (listOf(file) + history.filter { it != file }).take(50)
            } catch (e: Exception) { withContext(Dispatchers.Main) { status = "播放失败: ${e.message}"; isPlaying = false } }
        }
    }

    fun togglePlay() {
        val p = playerRef.value ?: return
        if (isPlaying) { p.pause(); isPlaying = false; MusicState.isPlaying = false }
        else { p.start(); isPlaying = true; MusicState.isPlaying = true }
    }

    fun stop() {
        playerRef.value?.apply { stop(); reset(); release() }
        playerRef.value = null; isPlaying = false; currentIndex = -1; currentPos = 0
        MusicState.reset(); coverBitmap = null
    }

    fun next() { if (currentIndex < tracks.size - 1) play(currentIndex + 1) }
    fun prev() { if (currentIndex > 0) play(currentIndex - 1) }

    LaunchedEffect(Unit) { scan() }

    LaunchedEffect(isPlaying, currentIndex) {
        while (isPlaying) {
            playerRef.value?.let { currentPos = it.currentPosition; MusicState.currentMs = it.currentPosition }
            delay(500)
        }
    }

    DisposableEffect(Unit) { onDispose { playerRef.value?.release() } }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        // 标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音乐", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { stop(); scan() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("刷新")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("播放列表、历史记录与播放控制", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(8.dp))

        // 播放列表选择器
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showCreatePlaylist = true }) {
                Icon(Icons.Filled.Add, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp));
                Text(playlistName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = tabIdx == 0, onClick = { tabIdx = 0 }, label = { Text("播放列表 (${tracks.size})") })
            FilterChip(selected = tabIdx == 1, onClick = { tabIdx = 1 }, label = { Text("历史 (${history.size})") })
            if (history.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { showClearHistory = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Delete, "清除历史", Modifier.size(14.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 正在播放卡片
        if (currentIndex >= 0) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                 colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 封面
                    Surface(Modifier.size(64.dp), shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer) {
                        if (coverBitmap != null) {
                            Image(bitmap = coverBitmap!!.asImageBitmap(), "cover",
                                  modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                  contentScale = ContentScale.Crop)
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Star, null, Modifier.size(28.dp),
                                     tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tracks[currentIndex].nameWithoutExtension, fontWeight = FontWeight.Bold,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tracks[currentIndex].name, style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (duration > 0) {
                            Spacer(Modifier.height(4.dp))
                            Slider(value = if (duration > 0) currentPos.toFloat() / duration else 0f,
                                onValueChange = { v ->
                                    val pos = (v * duration).toInt()
                                    playerRef.value?.seekTo(pos); currentPos = pos
                                    MusicState.currentMs = pos
                                })
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(fmt(currentPos), style = MaterialTheme.typography.labelSmall)
                                Text(fmt(duration), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                // 控制 + 音量
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { prev() }, enabled = currentIndex > 0) {
                            Icon(Icons.Filled.SkipPrevious, null, Modifier.size(28.dp))
                        }
                        IconButton(onClick = { togglePlay() }) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                                 Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { next() }, enabled = currentIndex < tracks.size - 1) {
                            Icon(Icons.Filled.SkipNext, null, Modifier.size(28.dp))
                        }
                        IconButton(onClick = { stop() }) {
                            Icon(Icons.Filled.Stop, null, Modifier.size(24.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                        Slider(value = volume, onValueChange = {
                            volume = it; playerRef.value?.setVolume(it, it)
                        }, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        // 列表
        val displayList = if (tabIdx == 0) tracks else history
        if (loading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (displayList.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (tabIdx == 0) "未找到音乐文件" else "暂无播放历史",
                         color = MaterialTheme.colorScheme.outline)
                    if (tabIdx == 0) Text("将音频文件放入 /sdcard/Music",
                         style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                displayList.take(if (tabIdx == 0) 200 else 50).forEachIndexed { _, file ->
                    val isCurrent = tabIdx == 0 && tracks.indexOf(file) == currentIndex
                    Surface(shape = RoundedCornerShape(8.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (tabIdx == 0) play(tracks.indexOf(file))
                                else {
                                    // find in tracks or play directly
                                    val idx = tracks.indexOf(file)
                                    if (idx >= 0) play(idx)
                                }
                            }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isCurrent && isPlaying) Icons.Filled.PlayArrow else Icons.Filled.Star,
                                 null, Modifier.size(20.dp),
                                 tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                     fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                     style = MaterialTheme.typography.bodyMedium)
                                Text(file.name, style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(formatFileSize(file.length()), style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }

    // 创建播放列表
    if (showCreatePlaylist) AlertDialog(
        onDismissRequest = { showCreatePlaylist = false },
        title = { Text("新建播放列表") },
        text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it },
            label = { Text("名称") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = {
            if (newPlaylistName.isNotBlank()) { playlistName = newPlaylistName; newPlaylistName = "" }
            showCreatePlaylist = false
        }) { Text("创建") } },
        dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("取消") } })

    // 清除历史
    if (showClearHistory) AlertDialog(
        onDismissRequest = { showClearHistory = false },
        title = { Text("清除播放历史") }, text = { Text("确定清除所有播放历史记录？") },
        confirmButton = { TextButton(onClick = { history = emptyList(); showClearHistory = false }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { showClearHistory = false }) { Text("取消") } })
}

private fun fmt(ms: Int): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
}
