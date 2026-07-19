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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.gamecontent.ShaderPackManager
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter

@Composable
fun ShaderPacksPage(vm: LauncherViewModel) {
    val packs by vm.shaderPacks.collectAsState()
    val status by vm.status.collectAsState()
    var query by remember { mutableStateOf("") }
    var sortExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(ShaderSort.NAME) }

    // 过滤状态（由卡片上的 FilterChip 切换）
    var filterDisabled by remember { mutableStateOf(false) }
    var filterActive by remember { mutableStateOf(false) }
    var filterSource by remember { mutableStateOf<String?>(null) }

    // 批量选择模式
    var selectionMode by remember { mutableStateOf(false) }
    val selectedPacks = remember { mutableStateListOf<ShaderPackManager.ShaderPack>() }

    var showImportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (packs.isEmpty()) vm.refreshShaderPacks() }

    val filtered = remember(packs, query, sortBy, filterDisabled, filterActive, filterSource) {
        var list = if (query.isBlank()) packs
        else packs.filter { it.name.contains(query, ignoreCase = true) }
        if (filterDisabled) list = list.filter { it.isDisabled }
        if (filterActive) list = list.filter { it.isActive }
        if (filterSource != null) list = list.filter { it.source == filterSource }
        when (sortBy) {
            ShaderSort.NAME -> list.sortedBy { it.name.lowercase() }
            ShaderSort.SIZE_DESC -> list.sortedByDescending { it.size }
            ShaderSort.SIZE_ASC -> list.sortedBy { it.size }
            ShaderSort.ACTIVE -> list.sortedByDescending { it.isActive }
        }
    }
    val activeCount = packs.count { it.isActive }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("shader.title"), style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { vm.openShaderPacksDir() }) {
                Text(I18n.t("common.open_dir"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showImportDialog = true }) {
                Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.import"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = vm::refreshShaderPacks) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.refresh"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                selectionMode = !selectionMode
                if (!selectionMode) selectedPacks.clear()
            }) {
                Text(I18n.t("common.batch_actions"))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(I18n.t("shader.dir_label", vm.config.workDir.resolve("shaderpacks")),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(I18n.t("shader.search")) },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // === 排序 ===
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("shader.count_status", packs.size, activeCount),
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
                    ShaderSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            onClick = { sortBy = s; sortExpanded = false }
                        )
                    }
                }
            }
        }

        // === 批量操作栏 ===
        if (selectionMode) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(I18n.t("common.selected_count", selectedPacks.size),
                     style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    filtered.forEach { p ->
                        if (selectedPacks.none { it.path == p.path }) selectedPacks.add(p)
                    }
                }) { Text(I18n.t("common.select_all")) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { selectedPacks.clear() }) {
                    Text(I18n.t("common.clear_selection"))
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    vm.batchEnableShaderPacks(selectedPacks.toList())
                    selectedPacks.clear()
                    selectionMode = false
                }) { Text(I18n.t("common.enable_all")) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    vm.batchDisableShaderPacks(selectedPacks.toList())
                    selectedPacks.clear()
                    selectionMode = false
                }) { Text(I18n.t("common.disable_all")) }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = {
                        vm.batchDeleteShaderPacks(selectedPacks.toList())
                        selectedPacks.clear()
                        selectionMode = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(I18n.t("common.delete_all")) }
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
                    Text(if (packs.isEmpty()) I18n.t("shader.empty")
                         else I18n.t("shader.no_match"),
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
                            ShaderPackRow(
                                pack = pack,
                                vm = vm,
                                isActive = pack.isActive,
                                filterDisabled = filterDisabled,
                                filterActive = filterActive,
                                filterSource = filterSource,
                                onToggleFilterDisabled = { filterDisabled = !filterDisabled },
                                onToggleFilterActive = { filterActive = !filterActive },
                                onToggleFilterSource = { src ->
                                    filterSource = if (filterSource == src) null else src
                                },
                                selectionMode = selectionMode,
                                isSelected = selectedPacks.any { it.path == pack.path },
                                onToggleSelect = {
                                    val idx = selectedPacks.indexOfFirst { it.path == pack.path }
                                    if (idx >= 0) selectedPacks.removeAt(idx)
                                    else selectedPacks.add(pack)
                                }
                            )
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

    if (showImportDialog) {
        ImportShaderPackDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { path ->
                showImportDialog = false
                vm.importShaderPack(path)
            }
        )
    }
}

enum class ShaderSort(val label: String) {
    NAME("按名称"), SIZE_DESC("大小 ↓"), SIZE_ASC("大小 ↑"), ACTIVE("已应用优先")
}

@Composable
private fun ShaderPackRow(
    pack: ShaderPackManager.ShaderPack,
    vm: LauncherViewModel,
    isActive: Boolean,
    filterDisabled: Boolean,
    filterActive: Boolean,
    filterSource: String?,
    onToggleFilterDisabled: () -> Unit,
    onToggleFilterActive: () -> Unit,
    onToggleFilterSource: (String) -> Unit,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }

    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
            .alpha(if (pack.isDisabled) 0.5f else 1f)
            .then(if (selectionMode) Modifier.clickable { onToggleSelect() } else Modifier)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() }
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(pack.name, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.weight(1f).clickable {
                         if (selectionMode) onToggleSelect() else showDetail = true
                     })
                if (pack.isDisabled) {
                    FilterChip(
                        selected = filterDisabled,
                        onClick = onToggleFilterDisabled,
                        label = { Text(I18n.t("content.state.disabled")) }
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (isActive) {
                    FilterChip(
                        selected = filterActive,
                        onClick = onToggleFilterActive,
                        label = { Text(I18n.t("content.state.current")) },
                        leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(14.dp)) }
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
                Text(ContentUtils.formatFileSize(pack.size),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (pack.isValid) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    null, Modifier.size(14.dp),
                    tint = if (pack.isValid) MaterialTheme.colorScheme.outline
                           else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(4.dp))
                Text(if (pack.isValid) I18n.t("shader.valid")
                     else I18n.t("shader.invalid"),
                     style = MaterialTheme.typography.bodySmall,
                     color = if (pack.isValid) MaterialTheme.colorScheme.outline
                             else MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 启用/禁用切换
                if (pack.isDisabled) {
                    OutlinedButton(onClick = { vm.enableShaderPack(pack) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("common.enable"))
                    }
                } else {
                    OutlinedButton(onClick = { vm.disableShaderPack(pack) }) {
                        Icon(Icons.Filled.Pause, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("common.disable"))
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (!isActive && !pack.isDisabled) {
                    Button(
                        onClick = { vm.setActiveShaderPack(pack) },
                        enabled = pack.isValid
                    ) {
                        Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("shader.apply"))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (isActive) {
                    OutlinedButton(onClick = { vm.clearActiveShaderPack() }) {
                        Text(I18n.t("shader.clear"))
                    }
                    Spacer(Modifier.width(8.dp))
                }
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(I18n.t("shader.delete_title")) },
            text = { Text(I18n.t("shader.delete_confirm", pack.name, pack.path)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteShaderPack(pack)
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }

    if (showDetail) {
        val stateLabel = when {
            pack.isDisabled -> I18n.t("content.state.disabled")
            isActive -> I18n.t("content.state.current")
            else -> "—"
        }
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text(pack.name, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(I18n.t("shader.detail_path", pack.path),
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("shader.detail_size", ContentUtils.formatFileSize(pack.size)),
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("shader.detail_valid",
                         if (pack.isValid) I18n.t("shader.valid") else I18n.t("shader.invalid")),
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("shader.detail_source", pack.source ?: "—"),
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("${I18n.t("common.status")}: $stateLabel",
                         style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetail = false }) { Text(I18n.t("common.close")) }
            }
        )
    }
}

@Composable
private fun ImportShaderPackDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("shader.import_title")) },
        text = {
            Column {
                Text(I18n.t("shader.import_hint"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val fd = FileDialog(null as Frame?, I18n.t("common.browse"), FileDialog.LOAD)
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
