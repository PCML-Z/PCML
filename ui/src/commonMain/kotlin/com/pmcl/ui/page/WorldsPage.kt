package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.gamecontent.WorldManager
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun WorldsPage(vm: LauncherViewModel) {
    val scope = rememberCoroutineScope()
    val worlds by vm.worlds.collectAsState()
    val status by vm.status.collectAsState()
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }

    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(WorldSort.NAME) }

    LaunchedEffect(Unit) { vm.refreshWorlds() }

    val sources = remember(worlds) {
        worlds.map { it.source ?: "未知" }.distinct().sorted()
    }

    val processedWorlds = remember(worlds, query, selectedSource, sortBy) {
        var list = if (query.isBlank()) worlds
        else worlds.filter { it.name.contains(query, ignoreCase = true) }
        if (selectedSource != null) {
            list = list.filter { (it.source ?: "未知") == selectedSource }
        }
        when (sortBy) {
            WorldSort.NAME -> list.sortedBy { it.name.lowercase() }
            WorldSort.SIZE_DESC -> list.sortedByDescending { it.sizeBytes }
            WorldSort.SIZE_ASC -> list.sortedBy { it.sizeBytes }
            WorldSort.MODIFIED -> list.sortedByDescending { it.lastModified }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // === 标题栏 ===
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("世界管理", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                val fd = FileDialog(null as Frame?, "导入世界存档", FileDialog.LOAD)
                fd.file = "*.zip"
                fd.filenameFilter = FilenameFilter { _, name -> name.endsWith(".zip") }
                fd.isVisible = true
                if (fd.file != null) {
                    val zipPath = java.io.File(fd.directory, fd.file).toPath()
                    vm.importWorld(zipPath)
                }
            }) {
                Icon(Icons.Filled.Upload, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.refreshWorlds() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("已合并扫描：PMCL / 外部启动器 / 整合包版本目录下的 saves",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        // === 搜索框 ===
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索世界名…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // === 来源筛选 ===
        if (sources.size > 1 || (sources.size == 1 && sources[0] != "PMCL")) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("来源：", style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                val sourceItems = listOf("全部") + sources
                com.pmcl.ui.animation.AnimatedSegmentedSelector(
                    items = sourceItems,
                    selectedIndex = if (selectedSource == null) 0
                        else sourceItems.indexOf(selectedSource).coerceAtLeast(0),
                    onSelect = { selectedSource = if (it == 0) null else sourceItems[it] },
                    height = 30.dp
                )
            }
        }

        // === 统计 + 排序 ===
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("共 ${worlds.size} 个世界" +
                 if (query.isNotBlank() || selectedSource != null)
                     " · 当前显示 ${processedWorlds.size}" else "",
                 style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sortBy.label)
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    WorldSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            onClick = { sortBy = s; sortExpanded = false }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (processedWorlds.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (worlds.isEmpty()) "暂无世界。开始游戏后会自动在 saves 目录创建。"
                         else "无匹配结果",
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(processedWorlds, key = { _, w -> w.dir.toString() }) { index, world ->
                    StaggeredAppear(index) {
                        WorldRow(world, vm, format)
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

enum class WorldSort(val label: String) {
    NAME("按名称"), SIZE_DESC("大小 ↓"), SIZE_ASC("大小 ↑"), MODIFIED("修改时间")
}

@Composable
private fun WorldRow(
    world: WorldManager.WorldInfo,
    vm: LauncherViewModel,
    format: SimpleDateFormat
) {
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<Path>>(emptyList()) }
    var loadingBackups by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(world.name, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.weight(1f))
                AssistChip(
                    onClick = {},
                    label = { Text(world.source ?: "未知") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(formatFileSize(world.sizeBytes),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(4.dp))
            Text("最后修改: ${format.format(Date(world.lastModified))}",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch { vm.backupWorld(world) }
                }) {
                    Icon(Icons.Filled.Archive, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("备份")
                }
                OutlinedButton(onClick = {
                    loadingBackups = true
                    showRestoreDialog = true
                    scope.launch {
                        backups = vm.listBackups(world.name)
                        loadingBackups = false
                    }
                }) {
                    Icon(Icons.Filled.Restore, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("恢复")
                }
                OutlinedButton(onClick = {
                    scope.launch { vm.refreshDatapacks(world.dir) }
                }) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("数据包")
                }
                OutlinedButton(onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }

    // 恢复对话框：列出该世界的所有备份
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("恢复世界 — ${world.name}") },
            text = {
                Column {
                    Text("选择要恢复的备份（将覆盖当前世界）：",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    when {
                        loadingBackups -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("加载备份列表…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        backups.isEmpty() -> {
                            Text("暂无备份。请先点击「备份」创建一个。",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                        else -> {
                            backups.forEach { zip ->
                                val fileName = zip.fileName.toString()
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.Archive, null,
                                             Modifier.size(16.dp),
                                             tint = MaterialTheme.colorScheme.outline)
                                        Spacer(Modifier.width(8.dp))
                                        Text(fileName, style = MaterialTheme.typography.bodySmall,
                                             maxLines = 1, overflow = TextOverflow.Ellipsis,
                                             modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            vm.restoreWorld(zip, world.name)
                                            showRestoreDialog = false
                                        }) { Text("恢复") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除世界") },
            text = { Text("确定要删除 ${world.name} 吗？\n此操作不可恢复。\n路径：${world.dir}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { vm.deleteWorld(world) }
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}
