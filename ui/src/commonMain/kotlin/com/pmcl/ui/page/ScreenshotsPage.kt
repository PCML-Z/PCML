package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pmcl.core.gamecontent.ScreenshotManager.Screenshot
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun ScreenshotsPage(vm: LauncherViewModel) {
    val scope = rememberCoroutineScope()
    val shots by vm.screenshots.collectAsState()
    val status by vm.status.collectAsState()
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }

    var selectedIndex by remember { mutableStateOf(-1) }
    var previewIndex by remember { mutableStateOf(-1) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (shots.isEmpty()) vm.refreshScreenshots()
        focusRequester.requestFocus()
    }
    // 列表刷新后保证选中索引合法
    LaunchedEffect(shots.size) {
        if (selectedIndex >= shots.size) selectedIndex = -1
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // 预览打开时由 Dialog 自己处理键盘事件，这里只处理列表态
                if (previewIndex >= 0) return@onKeyEvent false
                when (event.key) {
                    Key.Spacebar -> {
                        if (selectedIndex in shots.indices) {
                            previewIndex = selectedIndex
                            true
                        } else false
                    }
                    Key.Escape -> {
                        if (selectedIndex >= 0) { selectedIndex = -1; true } else false
                    }
                    else -> false
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("screenshot.title"), style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // 导出 ZIP
            OutlinedButton(
                onClick = {
                    if (shots.isEmpty()) return@OutlinedButton
                    val fd = FileDialog(null as Frame?, I18n.t("screenshot.export_zip_dialog"), FileDialog.SAVE)
                    fd.file = "screenshots.zip"
                    fd.isVisible = true
                    if (fd.file != null) {
                        val target = File(fd.directory, fd.file).absolutePath
                        vm.exportScreenshotsZip(shots, target)
                    }
                },
                enabled = shots.isNotEmpty()
            ) {
                Icon(Icons.Filled.Download, contentDescription = null,
                     modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("screenshot.export_zip"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.refreshScreenshots() }) { Text(I18n.t("common.refresh")) }
        }
        Spacer(Modifier.height(8.dp))
        Text(I18n.t("screenshot.scan_hint"),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))

        if (shots.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = glassCardColors()) {
                Text(I18n.t("screenshot.empty"),
                     modifier = Modifier.padding(16.dp),
                     color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(shots, key = { _, s -> s.getPath()?.toString() ?: System.identityHashCode(s).toString() }) { index, shot ->
                    val selected = index == selectedIndex
                    val cardShape = RoundedCornerShape(12.dp)
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (selected) Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    cardShape
                                ) else Modifier
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                selectedIndex = index
                                focusRequester.requestFocus()
                            },
                        shape = cardShape,
                        colors = glassCardColors()
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(shot.name, fontWeight = FontWeight.SemiBold,
                                 maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            Text(I18n.t("screenshot.source", shot.source),
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.tertiary)
                            Text("${shot.size / 1024} KB",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                            Text(format.format(Date(shot.modified)),
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // 复制到剪贴板
                                OutlinedButton(
                                    onClick = { vm.copyScreenshotToClipboard(shot) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null,
                                         modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(I18n.t("screenshot.copy"), style = MaterialTheme.typography.labelSmall)
                                }
                                // 预览
                                OutlinedButton(
                                    onClick = { previewIndex = index },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.ZoomIn, contentDescription = null,
                                         modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(I18n.t("screenshot.preview"), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            // 删除
                            OutlinedButton(onClick = {
                                scope.launch { vm.deleteScreenshot(shot) }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Delete, contentDescription = null,
                                     modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(I18n.t("common.delete"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(I18n.t("screenshot.status", status),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }

    // 全屏预览
    if (previewIndex in shots.indices) {
        ScreenshotPreviewDialog(
            shot = shots[previewIndex],
            index = previewIndex + 1,
            total = shots.size,
            onDismiss = { previewIndex = -1 },
            onPrev = { if (previewIndex > 0) previewIndex-- },
            onNext = { if (previewIndex < shots.size - 1) previewIndex++ }
        )
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
                // 顶栏：文件名 + 计数 + 关闭提示
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        shot.name,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
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
                // 图片区
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
                // 底栏：上一张 / 下一张
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    OutlinedButton(onClick = onPrev, enabled = index > 1) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("screenshot.prev"))
                    }
                    OutlinedButton(onClick = onNext, enabled = index < total) {
                        Text(I18n.t("screenshot.next"))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
