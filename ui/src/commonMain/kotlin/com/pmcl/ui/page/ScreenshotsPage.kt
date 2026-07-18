package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun ScreenshotsPage(vm: LauncherViewModel) {
    val scope = rememberCoroutineScope()
    val shots by vm.screenshots.collectAsState()
    val status by vm.status.collectAsState()
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }

    LaunchedEffect(Unit) { if (shots.isEmpty()) vm.refreshScreenshots() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("截图", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // 导出 ZIP
            OutlinedButton(
                onClick = {
                    if (shots.isEmpty()) return@OutlinedButton
                    val fd = FileDialog(null as Frame?, "导出截图 ZIP", FileDialog.SAVE)
                    fd.file = "screenshots.zip"
                    fd.isVisible = true
                    if (fd.file != null) {
                        val target = java.io.File(fd.directory, fd.file).absolutePath
                        vm.exportScreenshotsZip(shots, target)
                    }
                },
                enabled = shots.isNotEmpty()
            ) {
                Icon(Icons.Filled.Download, contentDescription = null,
                     modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导出 ZIP")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.refreshScreenshots() }) { Text("刷新") }
        }
        Spacer(Modifier.height(8.dp))
        Text("已合并扫描：PMCL / 外部启动器 / 整合包版本目录下的 screenshots",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))

        if (shots.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = glassCardColors()) {
                Text("暂无截图。游戏内按 F2 截图后会自动保存到 screenshots 目录。",
                     modifier = Modifier.padding(16.dp),
                     color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shots, key = { it.getPath()?.toString() ?: System.identityHashCode(it).toString() }) { shot ->
                    Card(Modifier.fillMaxWidth(), colors = glassCardColors()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(shot.name, fontWeight = FontWeight.SemiBold,
                                 maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            Text("来源: ${shot.source}",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.tertiary)
                            Text("${shot.size / 1024} KB",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                            Text(format.format(Date(shot.modified)),
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(4.dp))
                            // 复制到剪贴板
                            OutlinedButton(
                                onClick = { vm.copyScreenshotToClipboard(shot) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null,
                                     modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("复制", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.height(4.dp))
                            // 删除
                            OutlinedButton(onClick = {
                                scope.launch { vm.deleteScreenshot(shot) }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Delete, contentDescription = null,
                                     modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("删除", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("状态: $status",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }
}
