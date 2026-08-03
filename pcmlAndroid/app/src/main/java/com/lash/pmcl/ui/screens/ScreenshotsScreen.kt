package com.lash.pmcl.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.lash.pmcl.core.gamecontent.ConfigFileManager
import com.lash.pmcl.core.gamecontent.ScreenshotManager
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val THUMB_PX = 240

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotsScreen(screenshotManager: ScreenshotManager) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var shots by remember { mutableStateOf<List<ScreenshotManager.Screenshot>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var previewTarget by remember { mutableStateOf<ScreenshotManager.Screenshot?>(null) }
    var deleteTarget by remember { mutableStateOf<ScreenshotManager.Screenshot?>(null) }
    var exportTarget by remember { mutableStateOf<ScreenshotManager.Screenshot?>(null) }

    // 缩略图内存缓存（LruCache），按 KB 计量，上限为可用内存的 1/8
    val thumbCache = remember {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        object : LruCache<String, Bitmap>(maxKb / 8) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    val scope = rememberCoroutineScope()
    
    fun refresh() {
        scope.launch {
            loading = true; error = null
            try {
                shots = withContext(Dispatchers.IO) { screenshotManager.list() }
            } catch (e: Exception) { error = e.message ?: e.toString() }
            finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun shareScreenshot(shot: ScreenshotManager.Screenshot) {
        try {
            val file = shot.path.toFile()
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享截图"))
        } catch (e: Exception) {
            statusMsg = "分享失败：${e.message}"
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("截图") },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(error!!, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                shots.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Image, contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text("暂无截图", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline)
                            Text("游戏中按 F2 截图后会保存在 screenshots 目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (statusMsg != null) {
                            item {
                                Text(statusMsg!!, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(4.dp))
                            }
                        }
                        items(shots, key = { it.path.toString() }) { shot ->
                            ScreenshotCard(
                                shot = shot,
                                thumbCache = thumbCache,
                                onClick = { previewTarget = shot },
                                onDelete = { deleteTarget = shot },
                            )
                        }
                    }
                }
            }
        }
    }

    // 全屏预览
    previewTarget?.let { shot ->
        ScreenshotPreviewDialog(
            shot = shot,
            thumbCache = thumbCache,
            onDismiss = { previewTarget = null },
            onExport = {
                previewTarget = null
                exportTarget = shot
            },
            onShare = { shareScreenshot(shot) },
            onDelete = {
                previewTarget = null
                deleteTarget = shot
            },
        )
    }

    // 导出对话框
    exportTarget?.let { target ->
        val suggest = "/sdcard/Download/${target.name}"
        var path by remember { mutableStateOf(suggest) }
        AlertDialog(
            onDismissRequest = { exportTarget = null },
            title = { Text("导出截图") },
            text = {
                Column {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("目标文件路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("将「${target.name}」复制到指定位置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val src = target
                        exportTarget = null
                        Thread {
                            try {
                                val dst = Paths.get(path.trim())
                                dst.parent?.let { Files.createDirectories(it) }
                                Files.copy(src.path, dst, StandardCopyOption.REPLACE_EXISTING)
                                statusMsg = "导出成功：${dst.fileName}"
                            } catch (e: Exception) {
                                statusMsg = "导出失败：${e.message}"
                            }
                        }.start()
                    },
                    enabled = path.isNotBlank(),
                ) { Text("导出") }
            },
            dismissButton = {
                TextButton(onClick = { exportTarget = null }) { Text("取消") }
            },
        )
    }

    // 删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除截图") },
            text = { Text("确定要删除「${target.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    try { screenshotManager.delete(target) } catch (e: Exception) {
                        statusMsg = "删除失败：${e.message}"
                    }
                    deleteTarget = null
                    refresh()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenshotCard(
    shot: ScreenshotManager.Screenshot,
    thumbCache: LruCache<String, Bitmap>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    var thumb by remember(shot.path) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(shot.path) { mutableStateOf(false) }

    LaunchedEffect(shot.path) {
        thumb = null
        loadFailed = false
        val key = shot.path.toString()
        thumbCache.get(key)?.let { thumb = it; return@LaunchedEffect }
        val bmp = withContext(Dispatchers.IO) {
            try { decodeSampledBitmap(key, THUMB_PX, THUMB_PX) } catch (_: Exception) { null }
        }
        if (bmp != null) {
            thumbCache.put(key, bmp)
            thumb = bmp
        } else {
            loadFailed = true
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(108.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = shot.name,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else if (loadFailed) {
                    Icon(Icons.Outlined.Image, contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.outline)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = shot.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateFmt.format(Date(shot.modified)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ScreenshotPreviewDialog(
    shot: ScreenshotManager.Screenshot,
    thumbCache: LruCache<String, Bitmap>,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    var fullBmp by remember(shot.path) { mutableStateOf<Bitmap?>(null) }
    var dimensions by remember(shot.path) { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(shot.path) {
        fullBmp = null
        val key = shot.path.toString()
        // 先取尺寸（不加载像素）
        dimensions = withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(key, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
            } catch (_: Exception) { null }
        }
        // 加载完整图像（大图采样至 2048 防止 OOM）
        val bmp = withContext(Dispatchers.IO) {
            try { decodeSampledBitmap(key, 2048, 2048) } catch (_: Exception) { null }
        }
        if (bmp != null) {
            thumbCache.put(key, bmp)
            fullBmp = bmp
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 图片区域
                Box(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = fullBmp
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = shot.name,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 截图信息
                Text(shot.name, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val dim = dimensions
                InfoLine("文件大小", ConfigFileManager.formatSize(shot.size))
                InfoLine("修改日期", dateFmt.format(Date(shot.modified)))
                InfoLine("来源", shot.source)
                if (dim != null) InfoLine("尺寸", "${dim.first} × ${dim.second}")

                Spacer(Modifier.height(12.dp))
                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onExport) { Text("导出") }
                    TextButton(onClick = onShare) {
                        Icon(Icons.Outlined.Share, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("分享")
                    }
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(72.dp))
        Text(value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 解码采样后的 Bitmap，避免大图直接加载导致 OOM */
private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    var halfH = bounds.outHeight / 2
    var halfW = bounds.outWidth / 2
    while (halfH / sample >= reqHeight && halfW / sample >= reqWidth) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)
}
