@file:OptIn(ExperimentalFoundationApi::class)

package com.pmcl.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Sort
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
import com.pmcl.core.gamecontent.ResourcePackManager
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter

/** 资源包列表页（与 ShaderPacksPage 风格一致） */
@Composable
fun ResourcePacksPage(vm: LauncherViewModel) {
    val packs by vm.resourcePacks.collectAsState()
    val status by vm.status.collectAsState()
    var query by remember { mutableStateOf("") }
    var sortExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(ResourceSort.NAME) }

    // 过滤状态（FilterChip selected 对应）
    var filterDisabled by remember { mutableStateOf(false) }
    var filterSource by remember { mutableStateOf<String?>(null) }
    var filterFormat by remember { mutableStateOf<Int?>(null) }

    // 批量选择
    var selectionMode by remember { mutableStateOf(false) }
    val selectedPacks = remember { mutableStateListOf<ResourcePackManager.Pack>() }

    // 导入 / 详情 对话框
    var showImportDialog by remember { mutableStateOf(false) }
    var detailPack by remember { mutableStateOf<ResourcePackManager.Pack?>(null) }

    LaunchedEffect(Unit) { if (packs.isEmpty()) vm.refreshResourcePacks() }

    val filtered = remember(packs, query, sortBy, filterDisabled, filterSource, filterFormat) {
        var list = if (query.isBlank()) packs
        else packs.filter { it.name.contains(query, ignoreCase = true) }
        if (filterDisabled) list = list.filter { it.isDisabled }
        if (filterSource != null) list = list.filter { it.source == filterSource }
        if (filterFormat != null) list = list.filter { it.packFormat == filterFormat }
        when (sortBy) {
            ResourceSort.NAME -> list.sortedBy { it.name.lowercase() }
            ResourceSort.FORMAT_ASC -> list.sortedBy { it.packFormat }
            ResourceSort.FORMAT_DESC -> list.sortedByDescending { it.packFormat }
            ResourceSort.TYPE -> list.sortedBy { if (it.isZip) 0 else 1 }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // === 标题栏 ===
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("resource.title"), style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = { showImportDialog = true }) {
                Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.import"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.openResourcePacksDir() }) {
                Text(I18n.t("common.open_dir"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = vm::refreshResourcePacks) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.refresh"))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(I18n.t("resource.dir_label", vm.config.workDir.resolve("resourcepacks").toString()),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        // === 搜索框 ===
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(I18n.t("resource.search")) },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // === 统计 + 排序 ===
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("resource.count_status", packs.size) +
                 if (query.isNotBlank() && filtered.size != packs.size) " · ${filtered.size}" else "",
                 style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t(sortBy.labelKey))
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    ResourceSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(I18n.t(s.labelKey)) },
                            onClick = { sortBy = s; sortExpanded = false }
                        )
                    }
                }
            }
        }

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
                    if (selectedPacks.size == filtered.size) selectedPacks.clear()
                    else { selectedPacks.clear(); selectedPacks.addAll(filtered) }
                }) { Text(I18n.t("common.select_all")) }
                TextButton(onClick = { selectedPacks.clear() }) {
                    Text(I18n.t("common.clear_selection"))
                }
                TextButton(onClick = {
                    vm.batchEnableResourcePacks(selectedPacks.toList())
                    selectedPacks.clear()
                }) { Text(I18n.t("common.enable_all")) }
                TextButton(onClick = {
                    vm.batchDisableResourcePacks(selectedPacks.toList())
                    selectedPacks.clear()
                }) { Text(I18n.t("common.disable_all")) }
                TextButton(
                    onClick = {
                        vm.batchDeleteResourcePacks(selectedPacks.toList())
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

        if (filtered.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (packs.isEmpty()) I18n.t("resource.empty")
                         else I18n.t("resource.no_match"),
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(filtered, key = { _, p -> p.path.toString() }) { index, pack ->
                    Box(Modifier.animateItemPlacement()) {
                        StaggeredAppear(index) {
                            ResourcePackRow(
                                pack = pack,
                                vm = vm,
                                selectionMode = selectionMode,
                                isSelected = selectedPacks.any { it.path == pack.path },
                                onToggleSelected = { p ->
                                    if (selectedPacks.any { it.path == p.path }) {
                                        selectedPacks.removeAll { it.path == p.path }
                                    } else {
                                        selectedPacks.add(p)
                                    }
                                },
                                filterDisabled = filterDisabled,
                                filterSource = filterSource,
                                filterFormat = filterFormat,
                                onToggleFilterDisabled = { filterDisabled = !filterDisabled },
                                onToggleFilterSource = { s ->
                                    filterSource = if (filterSource == s) null else s
                                },
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
        Spacer(Modifier.height(8.dp))
        Text(I18n.t("resource.status_format", status),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }

    if (showImportDialog) {
        ImportResourcePackDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { path ->
                showImportDialog = false
                vm.importResourcePack(path)
            }
        )
    }

    detailPack?.let { p ->
        ResourcePackDetailDialog(pack = p, onDismiss = { detailPack = null })
    }
}

enum class ResourceSort(val labelKey: String) {
    NAME("resource.sort.name"), FORMAT_ASC("resource.sort.format_asc"), FORMAT_DESC("resource.sort.format_desc"), TYPE("resource.sort.type")
}

@Composable
private fun ResourcePackRow(
    pack: ResourcePackManager.Pack,
    vm: LauncherViewModel,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelected: (ResourcePackManager.Pack) -> Unit,
    filterDisabled: Boolean,
    filterSource: String?,
    filterFormat: Int?,
    onToggleFilterDisabled: () -> Unit,
    onToggleFilterSource: (String) -> Unit,
    onToggleFilterFormat: (Int) -> Unit,
    onClickName: (ResourcePackManager.Pack) -> Unit
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
                if (!pack.source.isNullOrEmpty()) {
                    FilterChip(
                        selected = filterSource == pack.source,
                        onClick = { onToggleFilterSource(pack.source) },
                        label = { Text(pack.source) }
                    )
                    Spacer(Modifier.width(4.dp))
                }
                FilterChip(
                    selected = filterFormat == pack.packFormat,
                    onClick = { onToggleFilterFormat(pack.packFormat) },
                    label = { Text(I18n.t("resource.format_label", pack.packFormat)) }
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
                Text(I18n.t("resource.compatible", ContentUtils.packFormatHint(pack.packFormat)),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pack.isDisabled) {
                        OutlinedButton(onClick = { vm.enableResourcePack(pack) }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.enable"))
                        }
                    } else {
                        OutlinedButton(onClick = { vm.disableResourcePack(pack) }) {
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
            title = { Text(I18n.t("resource.delete_title")) },
            text = { Text(I18n.t("resource.delete_confirm", pack.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteResourcePack(pack)
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
private fun ImportResourcePackDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("resource.import_title")) },
        text = {
            Column {
                Text(I18n.t("resource.import_hint"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(I18n.t("resource.path_label")) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val fd = FileDialog(null as Frame?, I18n.t("resource.import_title"), FileDialog.LOAD)
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
private fun ResourcePackDetailDialog(pack: ResourcePackManager.Pack, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pack.name, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(I18n.t("resource.detail_path", pack.path.toString()),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("resource.detail_format",
                            pack.packFormat, ContentUtils.packFormatHint(pack.packFormat)),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("resource.detail_type", if (pack.isZip) "zip" else "dir"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("resource.detail_desc", pack.getDescription() ?: "—"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("resource.detail_source", pack.source ?: "—"),
                     style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
        }
    )
}
