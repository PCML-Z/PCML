package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.gamecontent.RecordingManager
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.TypewriterTitle
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassContainerColor
import com.pmcl.ui.util.LruImageCache
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.deleteRecording
import com.pmcl.ui.viewmodel.deleteRecordings
import com.pmcl.ui.viewmodel.importRecordings
import com.pmcl.ui.viewmodel.openRecording
import com.pmcl.ui.viewmodel.openRecordingFolder
import com.pmcl.ui.viewmodel.openRecordingsDir
import com.pmcl.ui.viewmodel.refreshRecordings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter
import java.text.SimpleDateFormat
import java.util.Date

private const val RECORDING_THUMB_WIDTH = 384

@Composable
fun RecordingsPage(vm: LauncherViewModel) {
    val recordings by vm.recordings.collectAsState()
    val status by vm.status.collectAsState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    val thumbnailCache = remember { LruImageCache(64L * 1024L * 1024L) }
    var query by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (recordings.isEmpty()) vm.refreshRecordings()
    }

    fun key(recording: RecordingManager.Recording): String =
        recording.path.toAbsolutePath().normalize().toString()

    val sources = remember(recordings) { recordings.map { it.source }.distinct().sorted() }
    val filtered = remember(recordings, query, sourceFilter) {
        recordings.filter {
            (sourceFilter.isBlank() || it.source == sourceFilter) &&
                    (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
        }
    }
    LaunchedEffect(recordings) {
        val alive = recordings.map(::key).toSet()
        selected = selected.intersect(alive)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypewriterTitle(
                I18n.t("recording.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                I18n.t("recording.filtered_count", filtered.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                val dialog = FileDialog(null as Frame?, I18n.t("recording.import"), FileDialog.LOAD)
                dialog.isMultipleMode = true
                dialog.filenameFilter = FilenameFilter { _, name -> RecordingManager.isVideo(name) }
                dialog.isVisible = true
                val paths = dialog.files?.map { it.absolutePath }.orEmpty()
                if (paths.isNotEmpty()) vm.importRecordings(paths)
            }) {
                Icon(Icons.Filled.Upload, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("recording.import"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.openRecordingsDir() }) {
                Icon(Icons.Filled.Folder, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("recording.open_folder"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.refreshRecordings() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.refresh"))
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                placeholder = { Text(I18n.t("recording.search_hint")) },
                modifier = Modifier.weight(1f)
            )
            if (filtered.isNotEmpty()) {
                TextButton(onClick = { selected = filtered.map(::key).toSet() }) {
                    Text(I18n.t("recording.select_all"))
                }
            }
            if (selected.isNotEmpty()) {
                TextButton(onClick = { selected = emptySet() }) {
                    Text(I18n.t("recording.clear_selection"))
                }
            }
        }

        if (sources.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = sourceFilter.isBlank(),
                        onClick = { sourceFilter = "" },
                        label = { Text(I18n.t("recording.source_all")) }
                    )
                }
                items(sources, key = { it }) { source ->
                    FilterChip(
                        selected = sourceFilter == source,
                        onClick = { sourceFilter = if (sourceFilter == source) "" else source },
                        label = { Text(source) }
                    )
                }
            }
        }

        if (selected.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = glassContainerColor(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        I18n.t("recording.selected_count", selected.size),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("recording.batch_delete"))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) {
            Card(
                Modifier.fillMaxWidth().glassCardBorder(),
                colors = glassCardColors(),
                elevation = glassCardElevation()
            ) {
                Text(
                    if (recordings.isEmpty()) I18n.t("recording.empty") else I18n.t("search.no_results"),
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filtered, key = { key(it) }) { recording ->
                    val itemKey = key(recording)
                    RecordingCard(
                        recording = recording,
                        thumbnailCache = thumbnailCache,
                        selected = itemKey in selected,
                        dateText = dateFormat.format(Date(recording.modified)),
                        onToggleSelect = {
                            selected = if (itemKey in selected) selected - itemKey else selected + itemKey
                        },
                        onPlay = { vm.openRecording(recording) },
                        onOpenFolder = { vm.openRecordingFolder(recording) },
                        onDelete = {
                            selected = selected - itemKey
                            vm.deleteRecording(recording)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            I18n.t("recording.status", status),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(I18n.t("recording.batch_delete")) },
            text = { Text(I18n.t("recording.batch_delete_confirm", selected.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        val targets = recordings.filter { key(it) in selected }
                        confirmDelete = false
                        selected = emptySet()
                        vm.deleteRecordings(targets)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}

@Composable
private fun RecordingCard(
    recording: RecordingManager.Recording,
    thumbnailCache: LruImageCache,
    selected: Boolean,
    dateText: String,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var thumbnail by remember(recording.path) { mutableStateOf<ImageBitmap?>(null) }
    var thumbnailFailed by remember(recording.path) { mutableStateOf(false) }
    val path = recording.path.toAbsolutePath().toString()

    LaunchedEffect(path) {
        thumbnail = thumbnailCache.get(path)
        if (thumbnail != null) return@LaunchedEffect
        thumbnailFailed = false
        val decoded = withContext(Dispatchers.IO) {
            decodeVideoThumbnailFromPath(path, RECORDING_THUMB_WIDTH)
        }
        if (decoded == null) thumbnailFailed = true
        else {
            thumbnailCache.put(path, decoded)
            thumbnail = decoded
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .glassCardBorder(12.dp),
        shape = shape,
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .clickable(onClick = onPlay)
            ) {
                if (thumbnail != null) {
                    Image(
                        thumbnail!!,
                        contentDescription = recording.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Filled.Videocam,
                        null,
                        Modifier.size(40.dp).align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    if (!thumbnailFailed) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp).align(Alignment.BottomCenter).padding(bottom = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                Surface(
                    modifier = Modifier.size(44.dp).align(Alignment.Center),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        I18n.t("recording.play"),
                        Modifier.padding(10.dp),
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = onToggleSelect,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        null,
                        Modifier.size(20.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
                if (hovered || selected) {
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    ) {
                        IconButton(onClick = onOpenFolder, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.FolderOpen, I18n.t("recording.open_containing"),
                                Modifier.size(16.dp), tint = Color.White)
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, I18n.t("common.delete"),
                                Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    recording.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    recording.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$dateText · ${ContentUtils.formatFileSize(recording.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
