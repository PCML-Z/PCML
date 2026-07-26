package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pmcl.core.gamecontent.ScreenshotManager.Screenshot
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.util.decodeSampledBitmap
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.copyScreenshotToClipboard
import com.pmcl.ui.viewmodel.deleteScreenshot
import com.pmcl.ui.viewmodel.deleteScreenshots
import com.pmcl.ui.viewmodel.exportScreenshotsZip
import com.pmcl.ui.viewmodel.openScreenshotFolder
import com.pmcl.ui.viewmodel.openScreenshotsDir
import com.pmcl.ui.viewmodel.refreshScreenshots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

private const val THUMB_MAX_PX = 320

@Composable
fun ScreenshotsPage(vm: LauncherViewModel) {
    val shots by vm.screenshots.collectAsState()
    val status by vm.status.collectAsState()
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }

    var query by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf("") } // 空 = 全部
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (shots.isEmpty()) vm.refreshScreenshots()
        focusRequester.requestFocus()
    }

    val sources = remember(shots) {
        shots.map { it.source ?: "PMCL" }.distinct().sorted()
    }

    val filtered = remember(shots, query, sourceFilter) {
        val q = query.trim()
        shots.filter { shot ->
            val srcOk = sourceFilter.isEmpty() || (shot.source ?: "PMCL") == sourceFilter
            val nameOk = q.isEmpty() || (shot.name ?: "").contains(q, ignoreCase = true)
            srcOk && nameOk
        }
    }

    // 预览索引基于过滤列表
    val previewIndex = remember(previewPath, filtered) {
        if (previewPath == null) -1
        else filtered.indexOfFirst { it.path?.toString() == previewPath }
    }

    // 列表变化时清理失效选中
    LaunchedEffect(shots) {
        val alive = shots.mapNotNull { it.path?.toString() }.toSet()
        selectedPaths = selectedPaths.intersect(alive)
        if (previewPath != null && previewPath !in alive) previewPath = null
    }

    fun pathKey(shot: Screenshot): String =
        shot.path?.toAbsolutePath()?.toString() ?: shot.name ?: ""

    fun exportTargets(): List<Screenshot> =
        if (selectedPaths.isNotEmpty()) filtered.filter { pathKey(it) in selectedPaths }
        else filtered

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (previewIndex >= 0) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        if (selectedPaths.isNotEmpty()) {
                            selectedPaths = emptySet()
                            true
                        } else false
                    }
                    else -> false
                }
            }
    ) {
        // ===== 顶栏 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                I18n.t("screenshot.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                I18n.t("screenshot.filtered_count", filtered.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = {
                    val targets = exportTargets()
                    if (targets.isEmpty()) return@OutlinedButton
                    val fd = FileDialog(null as Frame?, I18n.t("screenshot.export_zip_dialog"), FileDialog.SAVE)
                    fd.file = "screenshots.zip"
                    fd.isVisible = true
                    if (fd.file != null) {
                        vm.exportScreenshotsZip(targets, File(fd.directory, fd.file).absolutePath)
                    }
                },
                enabled = filtered.isNotEmpty()
            ) {
                Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("screenshot.export_zip"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.openScreenshotsDir() }) {
                Icon(Icons.Filled.Folder, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("screenshot.open_folder"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.refreshScreenshots() }) {
                Text(I18n.t("common.refresh"))
            }
        }

        Spacer(Modifier.height(8.dp))

        // ===== 搜索 + 多选工具 =====
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
                placeholder = { Text(I18n.t("screenshot.search_hint")) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            if (filtered.isNotEmpty()) {
                TextButton(onClick = {
                    selectedPaths = filtered.map { pathKey(it) }.toSet()
                }) {
                    Text(I18n.t("screenshot.select_all"))
                }
            }
            if (selectedPaths.isNotEmpty()) {
                TextButton(onClick = { selectedPaths = emptySet() }) {
                    Text(I18n.t("screenshot.clear_selection"))
                }
            }
        }

        // ===== 来源筛选 =====
        if (sources.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = sourceFilter.isEmpty(),
                        onClick = { sourceFilter = "" },
                        label = { Text(I18n.t("screenshot.source_all")) }
                    )
                }
                items(sources, key = { it }) { src ->
                    FilterChip(
                        selected = sourceFilter == src,
                        onClick = { sourceFilter = if (sourceFilter == src) "" else src },
                        label = { Text(src) }
                    )
                }
            }
        }

        // ===== 批量操作条 =====
        if (selectedPaths.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        I18n.t("screenshot.selected_count", selectedPaths.size),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("screenshot.batch_delete"))
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
                    if (shots.isEmpty()) I18n.t("screenshot.empty")
                    else I18n.t("search.no_results"),
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filtered, key = { pathKey(it) }) { shot ->
                    val key = pathKey(shot)
                    ScreenshotThumbCard(
                        shot = shot,
                        selected = key in selectedPaths,
                        dateText = format.format(Date(shot.modified)),
                        onToggleSelect = {
                            selectedPaths = if (key in selectedPaths) selectedPaths - key
                            else selectedPaths + key
                        },
                        onPreview = { previewPath = key },
                        onCopy = { vm.copyScreenshotToClipboard(shot) },
                        onOpenFolder = { vm.openScreenshotFolder(shot) },
                        onDelete = {
                            selectedPaths = selectedPaths - key
                            vm.deleteScreenshot(shot)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            I18n.t("screenshot.status", status),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }

    // 全屏预览（按过滤列表翻页）
    if (previewIndex in filtered.indices) {
        ScreenshotPreviewDialog(
            shot = filtered[previewIndex],
            index = previewIndex + 1,
            total = filtered.size,
            onDismiss = { previewPath = null },
            onPrev = {
                if (previewIndex > 0) previewPath = pathKey(filtered[previewIndex - 1])
            },
            onNext = {
                if (previewIndex < filtered.lastIndex) previewPath = pathKey(filtered[previewIndex + 1])
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(I18n.t("screenshot.batch_delete")) },
            text = { Text(I18n.t("screenshot.batch_delete_confirm", selectedPaths.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = shots.filter { pathKey(it) in selectedPaths }
                        confirmDelete = false
                        selectedPaths = emptySet()
                        vm.deleteScreenshots(toDelete)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(I18n.t("common.cancel"))
                }
            }
        )
    }
}

@Composable
private fun ScreenshotThumbCard(
    shot: Screenshot,
    selected: Boolean,
    dateText: String,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit,
    onCopy: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val showActions = hovered || selected
    var thumb by remember(shot.path) { mutableStateOf<ImageBitmap?>(null) }
    var thumbFailed by remember(shot.path) { mutableStateOf(false) }

    LaunchedEffect(shot.path) {
        thumb = null
        thumbFailed = false
        val path = shot.path ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            try {
                decodeSampledBitmap(File(path.toString()).readBytes(), THUMB_MAX_PX)
            } catch (_: Throwable) {
                null
            }
        }
        if (loaded == null) thumbFailed = true else thumb = loaded
    }

    val shape = RoundedCornerShape(12.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .glassCardBorder(12.dp),
        shape = shape,
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .clickable(onClick = onPreview)
            ) {
                when {
                    thumb != null -> Image(
                        bitmap = thumb!!,
                        contentDescription = shot.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    thumbFailed -> Icon(
                        Icons.Filled.Image,
                        null,
                        Modifier.size(36.dp).align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    else -> CircularProgressIndicator(
                        Modifier.size(28.dp).align(Alignment.Center),
                        strokeWidth = 2.dp
                    )
                }

                // 左上角勾选
                IconButton(
                    onClick = onToggleSelect,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(32.dp)
                        .background(
                            Color.Black.copy(alpha = if (showActions || selected) 0.35f else 0.15f),
                            CircleShape
                        )
                ) {
                    Icon(
                        if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 悬停操作
                if (showActions) {
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.ContentCopy, I18n.t("screenshot.copy"),
                                tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                        IconButton(onClick = onOpenFolder, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.FolderOpen, I18n.t("screenshot.open_containing"),
                                tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Delete, I18n.t("common.delete"),
                                tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    shot.name ?: "",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    shot.source ?: "PMCL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 全屏截图预览：按空格/ESC 关闭，左右方向键切换。
 */
@Composable
private fun ScreenshotPreviewDialog(
    shot: Screenshot,
    index: Int,
    total: Int,
    onDismiss: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    var bitmap by remember(shot.path) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(shot.path) { mutableStateOf(false) }

    LaunchedEffect(shot.path) {
        bitmap = null
        loadError = false
        val path = shot.path ?: return@LaunchedEffect
        try {
            val loaded = withContext(Dispatchers.IO) {
                loadPathImageBitmap(path.toString())
            }
            bitmap = loaded
        } catch (_: Throwable) {
            loadError = true
        }
    }

    Dialog(
        onCloseRequest = onDismiss,
        undecorated = true,
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@Dialog false
            when (event.key) {
                Key.Spacebar, Key.Escape -> { onDismiss(); true }
                Key.DirectionLeft -> { onPrev(); true }
                Key.DirectionRight -> { onNext(); true }
                else -> false
            }
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xE6000000)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        shot.name ?: "",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        I18n.t("screenshot.count", index, total),
                        color = Color(0xFFB0B0B0),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        I18n.t("screenshot.preview_hint"),
                        color = Color(0xFFB0B0B0),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        bitmap != null -> Image(
                            bitmap = bitmap!!,
                            contentDescription = shot.name,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            contentScale = ContentScale.Fit
                        )
                        loadError -> Text(I18n.t("screenshot.load_error"), color = Color.White)
                        else -> CircularProgressIndicator(color = Color.White)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    OutlinedButton(onClick = onPrev, enabled = index > 1) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("screenshot.prev"))
                    }
                    OutlinedButton(onClick = onNext, enabled = index < total) {
                        Text(I18n.t("screenshot.next"))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
