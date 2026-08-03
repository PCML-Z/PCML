@file:OptIn(ExperimentalFoundationApi::class)

package com.lash.pmcl.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.gamecontent.WorldManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun WorldsScreen(worldManager: WorldManager) {
    val scope = rememberCoroutineScope()
    var worlds by remember { mutableStateOf<List<WorldManager.WorldInfo>>(emptyList()) }
    var status by remember { mutableStateOf("就绪") }
    var loading by remember { mutableStateOf(true) }
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var query by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("name") }
    var sortExpanded by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                worlds = withContext(Dispatchers.IO) { worldManager.listWorlds() }
                status = "已加载 ${worlds.size} 个世界"
            } catch (e: Exception) {
                status = "加载失败: ${e.message}"
            } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val sorted = remember(worlds, query, sortBy) {
        var list = if (query.isBlank()) worlds
        else worlds.filter { w ->
            w.displayName.contains(query, ignoreCase = true) ||
            w.name.contains(query, ignoreCase = true)
        }
        when (sortBy) {
            "size_desc" -> list.sortedByDescending { it.sizeBytes }
            "size_asc" -> list.sortedBy { it.sizeBytes }
            "modified" -> list.sortedByDescending { it.lastModified }
            else -> list.sortedBy { (it.displayName.ifEmpty { it.name }).lowercase() }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("世界存档", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { showImportDialog = true }) {
                Icon(Icons.Filled.Upload, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("位于 saves/ 目录下的世界", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        // 搜索
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索世界...") },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true, shape = RoundedCornerShape(12.dp))

        // 统计 + 排序
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("共 ${worlds.size} 个世界", style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    val label = when (sortBy) { "size_desc"->"大小↓" "size_asc"->"大小↑" "modified"->"修改时间" else->"名称" }
                    Text(label)
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    listOf("name" to "按名称", "size_desc" to "按大小↓", "size_asc" to "按大小↑",
                           "modified" to "按修改时间").forEach { (k, v) ->
                        DropdownMenuItem(text = { Text(v) },
                            onClick = { sortBy = k; sortExpanded = false })
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Public, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text(if (worlds.isEmpty()) "暂无世界存档" else "无匹配结果",
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(sorted, key = { it.dir.toString() }) { world ->
                    WorldRow(world, worldManager, format, ::refresh)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }

    if (showImportDialog) {
        var path by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showImportDialog = false },
            title = { Text("导入世界存档") },
            text = {
                Column {
                    Text("输入 .zip 世界存档的路径（如 /sdcard/Download/world.zip）",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = path, onValueChange = { path = it },
                        label = { Text("文件路径") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (path.isNotEmpty()) {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { worldManager.importWorld(java.io.File(path).toPath()) }
                                refresh()
                            } catch (e: Exception) { status = "导入失败: ${e.message}" }
                        }
                        showImportDialog = false
                    }
                }, enabled = path.isNotEmpty()) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } })
    }
}

@Composable
private fun WorldRow(
    world: WorldManager.WorldInfo, worldManager: WorldManager,
    format: SimpleDateFormat, onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<java.nio.file.Path>>(emptyList()) }
    var loadingBackups by remember { mutableStateOf(false) }
    var backing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val title = world.displayName.ifEmpty { world.name }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            // 标题 + 信息
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    Modifier.size(40.dp), shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Public, null, Modifier.size(20.dp),
                             tint = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (world.displayName.isNotEmpty() && world.displayName != world.name) {
                        Text("目录: ${world.name}", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (world.gameType >= 0) {
                            SuggestionChip(onClick = {}, label = { Text(gameTypeName(world.gameType)) })
                        }
                        if (world.difficulty >= 0) {
                            SuggestionChip(onClick = {}, label = { Text(difficultyName(world.difficulty)) })
                        }
                        if (world.hardcore) {
                            SuggestionChip(onClick = {}, label = { Text("硬核") },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer))
                        }
                    }
                }
            }

            // 修改时间 + 种子
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${format.format(Date(world.lastModified))} · ${formatFileSize(world.sizeBytes)}",
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                     modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (world.seed != Long.MIN_VALUE) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(world.seed.toString())) }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("种子: ${world.seed}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 操作按钮
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    backing = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { worldManager.backup(world) }
                            status = "备份完成"
                        } catch (e: Exception) { status = "备份失败: ${e.message}" }
                        finally { backing = false }
                    }
                }, enabled = !backing) {
                    if (backing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Archive, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("备份")
                }
                OutlinedButton(onClick = {
                    loadingBackups = true; showRestoreDialog = true
                    scope.launch {
                        try {
                            backups = withContext(Dispatchers.IO) { worldManager.listBackups(world.name) }
                        } finally { loadingBackups = false }
                    }
                }) {
                    Icon(Icons.Filled.Restore, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("恢复")
                }
                OutlinedButton(onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("删除")
                }
            }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    // 恢复对话框
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("恢复 ${world.name}") },
            text = {
                Column {
                    when {
                        loadingBackups -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("加载备份列表中...", style = MaterialTheme.typography.bodySmall)
                        }
                        backups.isEmpty() -> Text("没有找到备份", color = MaterialTheme.colorScheme.outline)
                        else -> backups.forEach { zip ->
                            Surface(shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Archive, null, Modifier.size(16.dp),
                                         tint = MaterialTheme.colorScheme.outline)
                                    Spacer(Modifier.width(8.dp))
                                    Text(zip.fileName.toString(), style = MaterialTheme.typography.bodySmall,
                                         modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { worldManager.restore(zip, world.name) }
                                                onRefresh()
                                            } catch (e: Exception) { status = "恢复失败: ${e.message}" }
                                        }
                                        showRestoreDialog = false
                                    }) { Text("恢复") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRestoreDialog = false }) { Text("关闭") } }
        )
    }

    // 删除对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除存档") },
            text = { Text("确定要删除「$title」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { withContext(Dispatchers.IO) { worldManager.delete(world) }; onRefresh() }
                        catch (e: Exception) { status = "操作失败: ${e.message}" }
                    }
                    showDeleteDialog = false
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}

private fun gameTypeName(type: Int) = when (type) { 0->"生存" 1->"创造" 2->"冒险" 3->"旁观" else->"未知" }
private fun difficultyName(diff: Int) = when (diff) { 0->"和平" 1->"简单" 2->"普通" 3->"困难" else->"未知" }
