@file:OptIn(ExperimentalFoundationApi::class)

package com.pmcl.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.gamecontent.DatapackManager
import com.pmcl.core.gamecontent.WorldManager
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter

/**
 * 数据包管理页：先选择世界，再列出该世界的 datapacks。
 * 数据包位于 saves/<world>/datapacks/。
 */
@Composable
fun DatapacksPage(vm: LauncherViewModel) {
    val worlds by vm.worlds.collectAsState()
    val selectedWorld by vm.selectedDatapackWorld.collectAsState()
    val sw = selectedWorld
    val datapacks by vm.datapacks.collectAsState()
    val status by vm.status.collectAsState()
    var worldQuery by remember { mutableStateOf("") }
    var dpQuery by remember { mutableStateOf("") }

    // 过滤状态（FilterChip selected 对应）
    var filterDisabled by remember { mutableStateOf(false) }
    var filterFormat by remember { mutableStateOf<Int?>(null) }

    // 批量选择
    var selectionMode by remember { mutableStateOf(false) }
    val selectedPacks = remember { mutableStateListOf<DatapackManager.Datapack>() }

    // 导入 / 详情 对话框
    var showImportDialog by remember { mutableStateOf(false) }
    var detailPack by remember { mutableStateOf<DatapackManager.Datapack?>(null) }

    LaunchedEffect(Unit) { if (worlds.isEmpty()) vm.refreshWorlds() }

    val filteredWorlds = remember(worlds, worldQuery) {
        if (worldQuery.isBlank()) worlds
        else worlds.filter { it.name.contains(worldQuery, ignoreCase = true) }
    }
    val filteredDatapacks = remember(datapacks, dpQuery, filterDisabled, filterFormat) {
        var list = if (dpQuery.isBlank()) datapacks
        else datapacks.filter { it.name.contains(dpQuery, ignoreCase = true) }
        if (filterDisabled) list = list.filter { it.isDisabled }
        if (filterFormat != null) list = list.filter { it.packFormat == filterFormat }
        list
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // === 标题栏 ===
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("datapack.title"), style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // 导入按钮：未选中世界时禁用
            val canImport = sw != null
            Button(onClick = { showImportDialog = true }, enabled = canImport) {
                Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.import"))
            }
            Spacer(Modifier.width(8.dp))
            if (sw != null) {
                OutlinedButton(onClick = { vm.openDatapacksDir(sw) }) {
                    Text(I18n.t("common.open_dir"))
                }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = { vm.refreshWorlds() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("datapack.refresh_worlds"))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(I18n.t("datapack.location_hint"),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        // === 当前选中的世界 ===
        if (sw != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(I18n.t("datapack.current_world", sw.name),
                         fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(I18n.t("datapack.source", sw.source),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { vm.selectDatapackWorld(sw) }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("datapack.rescan"))
                    }
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = { vm.clearDatapackWorld() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("datapack.back_to_worlds"))
                    }
                }
            }
        }

        // === 世界列表（未选世界时显示） ===
        if (sw == null) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = worldQuery,
                onValueChange = { worldQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(I18n.t("datapack.search_world")) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(I18n.t("datapack.world_count", worlds.size) +
                 if (worldQuery.isNotBlank() && filteredWorlds.size != worlds.size) " · ${filteredWorlds.size}" else "",
                 style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            if (filteredWorlds.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (worlds.isEmpty()) I18n.t("datapack.no_worlds")
                             else I18n.t("datapack.no_match"),
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(filteredWorlds, key = { _, w -> w.dir.toString() }) { index, world ->
                        Box(Modifier.animateItemPlacement()) {
                            StaggeredAppear(index) {
                                WorldSelectRow(world, vm)
                            }
                        }
                    }
                }
            }
        } else {
            // === 数据包列表 ===
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = dpQuery,
                onValueChange = { dpQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(I18n.t("datapack.search_datapack")) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(I18n.t("datapack.count_status", datapacks.size) +
                 if (dpQuery.isNotBlank() && filteredDatapacks.size != datapacks.size) " · ${filteredDatapacks.size}" else "",
                 style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            // === 批量操作栏 ===
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = selectionMode,
                    onClick = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedPacks.clear()
                    },
                    label = { Text(I18n.t("common.batch_actions")) }
                )
                Spacer(Modifier.width(8.dp))
                if (selectionMode) {
                    Text(I18n.t("common.selected_count", selectedPacks.size),
                         style = MaterialTheme.typography.labelLarge,
                         modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (selectedPacks.size == filteredDatapacks.size) selectedPacks.clear()
                        else { selectedPacks.clear(); selectedPacks.addAll(filteredDatapacks) }
                    }) { Text(I18n.t("common.select_all")) }
                    TextButton(onClick = { selectedPacks.clear() }) {
                        Text(I18n.t("common.clear_selection"))
                    }
                    TextButton(onClick = {
                        vm.batchEnableDatapacks(selectedPacks.toList())
                        selectedPacks.clear()
                    }) { Text(I18n.t("common.enable_all")) }
                    TextButton(onClick = {
                        vm.batchDisableDatapacks(selectedPacks.toList())
                        selectedPacks.clear()
                    }) { Text(I18n.t("common.disable_all")) }
                    TextButton(
                        onClick = {
                            vm.batchDeleteDatapacks(selectedPacks.toList())
                            selectedPacks.clear()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(I18n.t("common.delete_all")) }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(12.dp))

            if (filteredDatapacks.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (datapacks.isEmpty()) I18n.t("datapack.empty")
                             else I18n.t("datapack.no_match"),
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(filteredDatapacks, key = { _, d -> d.path.toString() }) { index, dp ->
                        Box(Modifier.animateItemPlacement()) {
                            StaggeredAppear(index) {
                                DatapackRow(
                                    pack = dp,
                                    vm = vm,
                                    selectionMode = selectionMode,
                                    isSelected = selectedPacks.any { it.path == dp.path },
                                    onToggleSelected = { p ->
                                        if (selectedPacks.any { it.path == p.path }) {
                                            selectedPacks.removeAll { it.path == p.path }
                                        } else {
                                            selectedPacks.add(p)
                                        }
                                    },
                                    filterDisabled = filterDisabled,
                                    filterFormat = filterFormat,
                                    onToggleFilterDisabled = { filterDisabled = !filterDisabled },
                                    onToggleFilterFormat = { f ->
                                        filterFormat = if (filterFormat == f) null else f
                                    },
                                    onClickName = { detailPack = it }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(I18n.t("datapack.status_format", status),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }

    if (showImportDialog) {
        ImportDatapackDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { path ->
                showImportDialog = false
                vm.importDatapack(path)
            }
        )
    }

    detailPack?.let { p ->
        DatapackDetailDialog(pack = p, onDismiss = { detailPack = null })
    }
}

@Composable
private fun WorldSelectRow(world: WorldManager.WorldInfo, vm: LauncherViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(world.name, fontWeight = FontWeight.SemiBold)
                Text(I18n.t("datapack.source", world.source),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            Button(onClick = { vm.selectDatapackWorld(world) }) {
                Text(I18n.t("datapack.select_world"))
            }
        }
    }
}

@Composable
private fun DatapackRow(
    pack: DatapackManager.Datapack,
    vm: LauncherViewModel,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelected: (DatapackManager.Datapack) -> Unit,
    filterDisabled: Boolean,
    filterFormat: Int?,
    onToggleFilterDisabled: () -> Unit,
    onToggleFilterFormat: (Int) -> Unit,
    onClickName: (DatapackManager.Datapack) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().alpha(if (pack.isDisabled) 0.5f else 1f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelected(pack) }
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    pack.name,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .let { if (selectionMode) it else it.padding(end = 4.dp) }
                        .let { if (selectionMode) it else it.clickable { onClickName(pack) } }
                )
                if (pack.isDisabled) {
                    FilterChip(
                        selected = filterDisabled,
                        onClick = onToggleFilterDisabled,
                        label = { Text(I18n.t("content.state.disabled")) }
                    )
                    Spacer(Modifier.width(4.dp))
                }
                FilterChip(
                    selected = filterFormat == pack.packFormat,
                    onClick = { onToggleFilterFormat(pack.packFormat) },
                    label = { Text(I18n.t("datapack.format_label", pack.packFormat)) }
                )
                Spacer(Modifier.width(8.dp))
                Text(if (pack.isZip) "zip" else "dir",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            if (!selectionMode) {
                if ((pack.getDescription() ?: "").isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text((pack.getDescription() ?: "").take(120),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         maxLines = 2)
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("datapack.compatible", ContentUtils.packFormatHint(pack.packFormat)),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pack.isDisabled) {
                        OutlinedButton(onClick = { vm.enableDatapack(pack) }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.enable"))
                        }
                    } else {
                        OutlinedButton(onClick = { vm.disableDatapack(pack) }) {
                            Icon(Icons.Filled.Pause, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.disable"))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onClickName(pack) }) {
                        Text(I18n.t("common.detail"))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("common.delete"))
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(I18n.t("datapack.delete_title")) },
            text = { Text(I18n.t("datapack.delete_confirm", pack.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteDatapack(pack)
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(I18n.t("common.cancel"))
                }
            }
        )
    }
}

@Composable
private fun ImportDatapackDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("datapack.import_title")) },
        text = {
            Column {
                Text(I18n.t("datapack.import_hint"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(I18n.t("datapack.path_label")) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val fd = FileDialog(null as Frame?, I18n.t("datapack.import_title"), FileDialog.LOAD)
                            fd.filenameFilter = FilenameFilter { _, name ->
                                name.endsWith(".zip")
                            }
                            fd.isVisible = true
                            if (fd.file != null) {
                                path = java.io.File(fd.directory, fd.file).absolutePath
                            }
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("common.browse"))
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (path.isNotEmpty()) onConfirm(path) },
                enabled = path.isNotEmpty()
            ) { Text(I18n.t("common.import")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
        }
    )
}

@Composable
private fun DatapackDetailDialog(pack: DatapackManager.Datapack, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pack.name, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(I18n.t("datapack.detail_path", pack.path.toString()),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("datapack.detail_format",
                            pack.packFormat, ContentUtils.packFormatHint(pack.packFormat)),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("datapack.detail_type", if (pack.isZip) "zip" else "dir"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("datapack.detail_desc", pack.getDescription() ?: "—"),
                     style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
        }
    )
}
