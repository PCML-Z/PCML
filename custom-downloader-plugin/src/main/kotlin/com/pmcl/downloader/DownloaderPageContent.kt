package com.pmcl.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.download.DownloadManager
import com.pmcl.plugin.ComposableContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths

/**
 * GUI 页面内容 —— 由插件注册到 PMCL 侧边栏。
 *
 * 提供：
 * - URL 输入框 + 下载按钮
 * - 下载模式选择（文本预览 / 文件下载）
 * - 实时下载状态显示
 * - 下载历史记录列表
 */
class DownloaderPageContent(
    private val downloadManager: DownloadManager
) : ComposableContent {

    @Composable
    override fun invoke() {
        DownloaderPage(downloadManager)
    }
}

@Composable
private fun DownloaderPage(dl: DownloadManager) {
    val scope = rememberCoroutineScope()

    // 输入状态
    var urlInput by remember { mutableStateOf("") }
    var savePath by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DownloadMode.TEXT) }

    // 下载状态
    var isDownloading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var downloadedContent by remember { mutableStateOf("") }
    var downloadedBytes by remember { mutableStateOf(0L) }

    // 下载历史
    var history by remember { mutableStateOf<List<DownloadHistoryEntry>>(emptyList()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Custom Downloader", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(16.dp))

        // 模式选择
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == DownloadMode.TEXT,
                onClick = { mode = DownloadMode.TEXT },
                label = { Text("Text Preview") },
                leadingIcon = { Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            FilterChip(
                selected = mode == DownloadMode.FILE,
                onClick = { mode = DownloadMode.FILE },
                label = { Text("File Download") },
                leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }

        Spacer(Modifier.height(12.dp))

        // URL 输入
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("URL") },
            placeholder = { Text("https://example.com/file.json") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isDownloading
        )

        Spacer(Modifier.height(8.dp))

        // 文件保存路径（仅 FILE 模式显示）
        if (mode == DownloadMode.FILE) {
            OutlinedTextField(
                value = savePath,
                onValueChange = { savePath = it },
                label = { Text("Save path (optional)") },
                placeholder = { Text("~/.pmcl/downloads/ (auto-named if empty)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isDownloading
            )
            Spacer(Modifier.height(8.dp))
        }

        // 下载按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (urlInput.isBlank()) {
                        statusText = "Please enter a URL"
                        statusError = true
                        return@Button
                    }
                    if (!UrlValidator.isValidUrl(urlInput)) {
                        statusText = "Invalid URL: ${UrlValidator.getValidationError(urlInput)}"
                        statusError = true
                        return@Button
                    }

                    isDownloading = true
                    statusError = false
                    statusText = "Downloading..."
                    downloadedContent = ""
                    downloadedBytes = 0L

                    scope.launch {
                        try {
                            when (mode) {
                                DownloadMode.TEXT -> {
                                    val result = withContext(Dispatchers.IO) {
                                        dl.downloadString(urlInput)
                                    }
                                    downloadedContent = if (result.length > 10000) {
                                        result.substring(0, 10000) + "\n\n... (${result.length} chars total)"
                                    } else {
                                        result
                                    }
                                    statusText = "Downloaded ${result.length} characters"
                                    // M70 修复：历史记录最多保留 50 条，防止无限增长导致内存占用与 UI 卡顿
                                    history = (history + DownloadHistoryEntry(
                                        url = urlInput,
                                        target = "(text preview)",
                                        size = result.length.toLong(),
                                        success = true,
                                        timestamp = System.currentTimeMillis()
                                    )).takeLast(MAX_HISTORY)
                                }
                                DownloadMode.FILE -> {
                                    // S23 安全修复：保存路径必须位于 ~/.pmcl/downloads/ 内
                                    val target = if (savePath.isNotBlank()) {
                                        try {
                                            FileHelper.sanitizeSavePath(savePath)
                                        } catch (e: IllegalArgumentException) {
                                            statusText = "Error: ${e.message}"
                                            statusError = true
                                            return@launch
                                        }
                                    } else {
                                        val filename = FileHelper.extractFilename(urlInput)
                                        Paths.get(System.getProperty("user.home"), ".pmcl", "downloads", filename)
                                    }
                                    withContext(Dispatchers.IO) {
                                        FileHelper.ensureParentDir(target)
                                        dl.downloadTo(urlInput, target) { bytes ->
                                            downloadedBytes = bytes
                                        }
                                    }
                                    val size = Files.size(target)
                                    statusText = "Saved to: $target (${formatSize(size)})"
                                    history = (history + DownloadHistoryEntry(
                                        url = urlInput,
                                        target = target.toString(),
                                        size = size,
                                        success = true,
                                        timestamp = System.currentTimeMillis()
                                    )).takeLast(MAX_HISTORY)
                                }
                            }
                        } catch (e: Exception) {
                            statusText = "Error: ${e.message}"
                            statusError = true
                            history = (history + DownloadHistoryEntry(
                                url = urlInput,
                                target = mode.name,
                                size = 0L,
                                success = false,
                                timestamp = System.currentTimeMillis()
                            )).takeLast(MAX_HISTORY)
                        } finally {
                            isDownloading = false
                        }
                    }
                },
                enabled = !isDownloading
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Downloading...")
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download")
                }
            }

            if (downloadedContent.isNotEmpty() && mode == DownloadMode.TEXT) {
                OutlinedButton(onClick = { downloadedContent = "" }) {
                    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
            }
        }

        // 下载进度（仅 FILE 模式下载中显示）
        if (isDownloading && mode == DownloadMode.FILE && downloadedBytes > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Received: ${formatSize(downloadedBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // 状态消息
        if (statusText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (statusError)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (statusError) Icons.Filled.Error else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (statusError)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 文本预览
        if (downloadedContent.isNotEmpty() && mode == DownloadMode.TEXT) {
            Spacer(Modifier.height(12.dp))
            Text("Preview:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    downloadedContent,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 下载历史
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("History", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { history = emptyList() }) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(history.reversed()) { entry ->
                    HistoryItem(entry)
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(entry: DownloadHistoryEntry) {
    val time = remember(entry.timestamp) {
        java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(entry.timestamp))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.success)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (entry.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (entry.success)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                Text(
                    "$time · ${entry.target}" + if (entry.success) " · ${formatSize(entry.size)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==================== Data ====================

/** M70：历史记录最大保留条数 */
private const val MAX_HISTORY = 50

enum class DownloadMode { TEXT, FILE }

data class DownloadHistoryEntry(
    val url: String,
    val target: String,
    val size: Long,
    val success: Boolean,
    val timestamp: Long
)

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
    return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
