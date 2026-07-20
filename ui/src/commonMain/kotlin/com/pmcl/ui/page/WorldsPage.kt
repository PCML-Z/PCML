@file:OptIn(ExperimentalFoundationApi::class)

package com.pmcl.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.pmcl.core.i18n.I18n
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

    LaunchedEffect(Unit) { if (worlds.isEmpty()) vm.refreshWorlds() }

    val sources = remember(worlds) {
        worlds.map { it.source ?: I18n.t("world.unknown_source") }.distinct().sorted()
    }

    val processedWorlds = remember(worlds, query, selectedSource, sortBy) {
        var list = if (query.isBlank()) worlds
        else worlds.filter { it.name.contains(query, ignoreCase = true) }
        if (selectedSource != null) {
            list = list.filter { (it.source ?: I18n.t("world.unknown_source")) == selectedSource }
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
            Text(I18n.t("worlds.title"), style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                val fd = FileDialog(null as Frame?, I18n.t("world.import_title"), FileDialog.LOAD)
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
                Text(I18n.t("common.import"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.refreshWorlds() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.refresh"))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(I18n.t("world.scan_summary"),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        // === 搜索框 ===
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(I18n.t("world.search_placeholder")) },
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
                Text(I18n.t("world.source_label"), style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                val sourceItems = listOf(I18n.t("world.source_all")) + sources
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
            Text(if (query.isNotBlank() || selectedSource != null)
                     I18n.t("world.count_filtered", worlds.size, processedWorlds.size)
                 else
                     I18n.t("world.count", worlds.size),
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
                    WorldSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(I18n.t(s.labelKey)) },
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
                    Text(if (worlds.isEmpty()) I18n.t("worlds.empty")
                         else I18n.t("world.no_match"),
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(processedWorlds, key = { _, w -> w.dir.toString() }) { index, world ->
                    Box(Modifier.animateItemPlacement()) {
                        StaggeredAppear(index) {
                            WorldRow(world, vm, format)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("${I18n.t("common.status")}: $status",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }
}

enum class WorldSort(val labelKey: String) {
    NAME("world.sort.name"), SIZE_DESC("world.sort.size_desc"), SIZE_ASC("world.sort.size_asc"), MODIFIED("world.sort.modified")
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
                    label = { Text(world.source ?: I18n.t("world.unknown_source")) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(ContentUtils.formatFileSize(world.sizeBytes),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(4.dp))
            Text("${I18n.t("worlds.modified")}: ${format.format(Date(world.lastModified))}",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch { vm.backupWorld(world) }
                }) {
                    Icon(Icons.Filled.Archive, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("common.backup"))
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
                    Text(I18n.t("world.restore"))
                }
                OutlinedButton(onClick = {
                    scope.launch { vm.refreshDatapacks(world.dir) }
                }) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("datapack.title"))
                }
                OutlinedButton(onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("common.delete"))
                }
            }
        }
    }

    // 恢复对话框：列出该世界的所有备份
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(I18n.t("world.restore_title", world.name)) },
            text = {
                Column {
                    Text(I18n.t("world.restore_select_hint"),
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    when {
                        loadingBackups -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(I18n.t("world.loading_backups"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        backups.isEmpty() -> {
                            Text(I18n.t("world.no_backups"),
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
                                        }) { Text(I18n.t("world.restore")) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text(I18n.t("common.close")) }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(I18n.t("world.delete_title")) },
            text = { Text(I18n.t("world.delete_confirm", world.name, world.dir)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { vm.deleteWorld(world) }
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
}
