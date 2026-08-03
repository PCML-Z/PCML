package com.lash.pmcl.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.gamecontent.DatapackManager
import com.lash.pmcl.core.gamecontent.WorldManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

@Composable
fun DatapacksScreen(worldManager: WorldManager, dpManager: DatapackManager) {
    val scope = rememberCoroutineScope()
    var worlds by remember { mutableStateOf<List<WorldManager.WorldInfo>>(emptyList()) }
    var selectedWorld by remember { mutableStateOf<WorldManager.WorldInfo?>(null) }
    var datapacks by remember { mutableStateOf<List<DatapackManager.Datapack>>(emptyList()) }
    var status by remember { mutableStateOf("就绪") }

    var worldQuery by remember { mutableStateOf("") }
    var dpQuery by remember { mutableStateOf("") }
    var filterDisabled by remember { mutableStateOf(false) }
    var filterFormat by remember { mutableStateOf<Int?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedPacks = remember { mutableStateListOf<DatapackManager.Datapack>() }
    var showImportDialog by remember { mutableStateOf(false) }
    var detailPack by remember { mutableStateOf<DatapackManager.Datapack?>(null) }

    fun refreshWorlds() {
        scope.launch {
            try {
                worlds = withContext(Dispatchers.IO) { worldManager.listWorlds() }
                status = if (worlds.isEmpty()) "未找到世界存档" else "已加载 ${worlds.size} 个世界"
            } catch (e: Exception) { status = "加载失败: ${e.message}" }
        }
    }

    fun scanDatapacks(world: WorldManager.WorldInfo) {
        scope.launch {
            try {
                datapacks = withContext(Dispatchers.IO) { dpManager.list(world.dir) }
                status = "已加载 ${datapacks.size} 个数据包"
            } catch (e: Exception) { datapacks = emptyList(); status = "扫描失败: ${e.message}" }
        }
    }

    fun enablePack(pack: DatapackManager.Datapack) {
        val w = selectedWorld ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { dpManager.enable(w.dir, pack.path.fileName.toString()) }
                scanDatapacks(w)
            } catch (e: Exception) { status = "启用失败: ${e.message}" }
        }
    }

    fun disablePack(pack: DatapackManager.Datapack) {
        val w = selectedWorld ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { dpManager.disable(w.dir, pack.path.fileName.toString()) }
                scanDatapacks(w)
            } catch (e: Exception) { status = "禁用失败: ${e.message}" }
        }
    }

    fun deletePack(pack: DatapackManager.Datapack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { dpManager.delete(pack) }
                selectedWorld?.let { scanDatapacks(it) }
            } catch (e: Exception) { status = "删除失败: ${e.message}" }
        }
    }

    fun importDatapack(path: String) {
        val w = selectedWorld ?: return
        scope.launch {
            try {
                val src = java.io.File(path).toPath()
                val dst = w.dir.resolve("datapacks").resolve(src.fileName.toString())
                java.nio.file.Files.createDirectories(dst.parent)
                withContext(Dispatchers.IO) { java.nio.file.Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                scanDatapacks(w); status = "导入成功"
            } catch (e: Exception) { status = "导入失败: ${e.message}" }
        }
    }

    LaunchedEffect(Unit) { refreshWorlds() }

    val sw = selectedWorld
    val filteredWorlds = remember(worlds, worldQuery) {
        if (worldQuery.isBlank()) worlds
        else worlds.filter { it.name.contains(worldQuery, ignoreCase = true) }
    }
    val filteredDatapacks = remember(datapacks, dpQuery, filterDisabled, filterFormat) {
        var list = if (dpQuery.isBlank()) datapacks
        else datapacks.filter { it.name.contains(dpQuery, ignoreCase = true) }
        if (filterDisabled) list = list.filter { it.disabled }
        if (filterFormat != null) list = list.filter { it.packFormat == filterFormat }
        list
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("数据包", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = { showImportDialog = true }, enabled = sw != null) {
                Icon(Icons.Filled.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text("导入")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { refreshWorlds() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text("刷新世界")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("数据包位于存档的 datapacks 目录中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        if (sw != null) {
            Spacer(Modifier.height(8.dp))
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("当前世界: ${sw.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(sw.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { scanDatapacks(sw) }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("重新扫描")
                    }
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = { selectedWorld = null; datapacks = emptyList() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                        Text("返回世界列表")
                    }
                }
            }
        }

        if (sw == null) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = worldQuery, onValueChange = { worldQuery = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索世界...") }, leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            Text("共 ${worlds.size} 个世界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (filteredWorlds.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (worlds.isEmpty()) "未找到世界存档\n请先在游戏中创建一个世界" else "无匹配结果", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(filteredWorlds, key = { it.dir.toString() }) { world ->
                        WorldSelectRow(world) { selectedWorld = world; scanDatapacks(world) }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = dpQuery, onValueChange = { dpQuery = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索数据包...") }, leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(4.dp))
            Text("共 ${datapacks.size} 个数据包", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = selectionMode, onClick = {
                    selectionMode = !selectionMode; if (!selectionMode) selectedPacks.clear()
                }, label = { Text("批量操作") })
                if (selectionMode) {
                    Spacer(Modifier.width(8.dp))
                    Text("已选 ${selectedPacks.size} 项", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (selectedPacks.size == filteredDatapacks.size) selectedPacks.clear()
                        else { selectedPacks.clear(); selectedPacks.addAll(filteredDatapacks) }
                    }) { Text("全选") }
                    TextButton(onClick = { selectedPacks.clear() }) { Text("清除") }
                    TextButton(onClick = { selectedPacks.forEach { enablePack(it) }; selectedPacks.clear() }) { Text("全部启用") }
                    TextButton(onClick = { selectedPacks.forEach { disablePack(it) }; selectedPacks.clear() }) { Text("全部禁用") }
                    TextButton(onClick = { selectedPacks.forEach { deletePack(it) }; selectedPacks.clear() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("全部删除") }
                } else { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))

            if (filteredDatapacks.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (datapacks.isEmpty()) "此世界没有数据包" else "无匹配结果", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(filteredDatapacks, key = { it.path.toString() }) { dp ->
                        DatapackRow(pack = dp, selectionMode = selectionMode,
                            isSelected = selectedPacks.any { it.path == dp.path },
                            onToggleSelected = {
                                if (selectedPacks.any { p -> p.path == dp.path }) selectedPacks.removeAll { p -> p.path == dp.path }
                                else selectedPacks.add(dp)
                            },
                            filterDisabled = filterDisabled, filterFormat = filterFormat,
                            onToggleFilterDisabled = { filterDisabled = !filterDisabled },
                            onToggleFilterFormat = { f -> filterFormat = if (filterFormat == f) null else f },
                            onEnable = { enablePack(dp) }, onDisable = { disablePack(dp) },
                            onDelete = { deletePack(dp) }, onClickName = { detailPack = dp })
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }

    if (showImportDialog) {
        ImportDatapackDialog(onDismiss = { showImportDialog = false },
            onConfirm = { path -> showImportDialog = false; importDatapack(path) })
    }
    detailPack?.let { p -> DatapackDetailDialog(pack = p, onDismiss = { detailPack = null }) }
}

@Composable
private fun WorldSelectRow(world: WorldManager.WorldInfo, onSelect: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(world.name, fontWeight = FontWeight.SemiBold)
                Text("来源: ${world.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Button(onClick = onSelect) { Text("选择") }
        }
    }
}

@Composable
private fun DatapackRow(
    pack: DatapackManager.Datapack, selectionMode: Boolean, isSelected: Boolean,
    onToggleSelected: (DatapackManager.Datapack) -> Unit,
    filterDisabled: Boolean, filterFormat: Int?,
    onToggleFilterDisabled: () -> Unit, onToggleFilterFormat: (Int) -> Unit,
    onEnable: () -> Unit, onDisable: () -> Unit, onDelete: () -> Unit,
    onClickName: (DatapackManager.Datapack) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().alpha(if (pack.disabled) 0.5f else 1f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) { Checkbox(checked = isSelected, onCheckedChange = { onToggleSelected(pack) }); Spacer(Modifier.width(4.dp)) }
                Text(pack.name, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).let { if (selectionMode) it else it.padding(end = 4.dp).clickable { onClickName(pack) } })
                if (pack.disabled) { FilterChip(selected = filterDisabled, onClick = onToggleFilterDisabled, label = { Text("已禁用") }); Spacer(Modifier.width(4.dp)) }
                FilterChip(selected = filterFormat == pack.packFormat, onClick = { onToggleFilterFormat(pack.packFormat) }, label = { Text("格式 ${pack.packFormat}") })
                Spacer(Modifier.width(8.dp))
                Text(if (pack.isZip) "zip" else "dir", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (!selectionMode) {
                if (pack.description.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(pack.description.take(120), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pack.disabled) {
                        OutlinedButton(onClick = onEnable) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("启用") }
                    } else {
                        OutlinedButton(onClick = onDisable) { Icon(Icons.Filled.Pause, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("禁用") }
                    }
                    Spacer(Modifier.width(8.dp)); TextButton(onClick = { onClickName(pack) }) { Text("详情") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("删除")
                    }
                }
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(onDismissRequest = { showDeleteDialog = false }, title = { Text("删除数据包") },
            text = { Text("确定删除 \"${pack.name}\"？") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteDialog = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } })
    }
}

@Composable
private fun ImportDatapackDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("导入数据包") },
        text = { Column { Text("输入 .zip 文件的完整路径", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("文件路径") }, modifier = Modifier.fillMaxWidth()) } },
        confirmButton = { TextButton(onClick = { if (path.isNotEmpty()) onConfirm(path) }, enabled = path.isNotEmpty()) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun DatapackDetailDialog(pack: DatapackManager.Datapack, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(pack.name, fontWeight = FontWeight.Bold) },
        text = { Column {
            Text("路径: ${pack.path}", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(4.dp))
            Text("格式: pack_format ${pack.packFormat}", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(4.dp))
            Text("类型: ${if (pack.isZip) "zip" else "目录"}", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(4.dp))
            Text("描述: ${pack.description.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
