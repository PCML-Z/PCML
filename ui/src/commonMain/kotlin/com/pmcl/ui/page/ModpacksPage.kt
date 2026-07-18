package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.install.InstallProgress
import com.pmcl.core.modpack.ModpackManager
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter

@Composable
fun ModpacksPage(vm: LauncherViewModel) {
    val modpacks by vm.modpacks.collectAsState()
    val status by vm.status.collectAsState()
    val progress by vm.modpackProgress.collectAsState()
    val busy by vm.modpackBusy.collectAsState()
    val selectedVersion by vm.selectedVersion.collectAsState()
    val updateChecking by vm.modpackUpdateChecking.collectAsState()
    val updateResult by vm.modpackUpdateResult.collectAsState()

    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (modpacks.isEmpty()) vm.refreshModpacks() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶部操作栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("modpack.title"), style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.refreshModpacks() }, enabled = !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = I18n.t("common.refresh"))
            }
            Button(
                onClick = { showImportDialog = true },
                enabled = !busy,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.import"))
            }
            Button(
                onClick = { showExportDialog = true },
                enabled = !busy && selectedVersion != null,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.export"))
            }
        }

        Spacer(Modifier.height(8.dp))

        // 进度条
        if (progress != null) {
            val p = progress!!
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = glassCardColors()) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.getMessage() ?: I18n.t("common.processing"), style = MaterialTheme.typography.bodySmall)
                    if (p.getTotal() > 0) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { if (p.getTotal() > 0) p.getCompleted().toFloat() / p.getTotal() else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 整合包列表
        if (modpacks.isEmpty() && !busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inventory2, contentDescription = null,
                         modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text(I18n.t("modpack.empty"), color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("modpack.empty_hint"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(modpacks, key = { _, m -> m.instanceDir.toString() }) { _, mp ->
                    ModpackCard(vm, mp, busy, updateChecking)
                }
            }
        }
    }

    // 导入对话框
    if (showImportDialog) {
        ImportModpackDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { path ->
                showImportDialog = false
                vm.importModpack(path)
            }
        )
    }

    // 导出对话框
    if (showExportDialog) {
        ExportModpackDialog(
            versionId = selectedVersion ?: "",
            onDismiss = { showExportDialog = false },
            onConfirm = { path, format ->
                showExportDialog = false
                vm.exportModpack(path, format)
            }
        )
    }

    // 更新检查结果对话框
    updateResult?.let { result ->
        ModpackUpdateDialog(
            result = result,
            onDismiss = { vm.clearModpackUpdateResult() }
        )
    }
}

@Composable
private fun ModpackCard(vm: LauncherViewModel, mp: ModpackManager.InstalledModpack,
                        busy: Boolean, updateChecking: Boolean) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = glassCardColors()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Inventory2, contentDescription = null,
                 tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(mp.name, fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row {
                    AssistChip(
                        onClick = {},
                        label = { Text(mp.gameVersion, style = MaterialTheme.typography.labelSmall) }
                    )
                    if (mp.loader.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(mp.loader, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(I18n.t("modpack.mod_count", mp.modCount), style = MaterialTheme.typography.labelSmall) }
                    )
                    if (mp.source.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(I18n.t("modpack.source", mp.source), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
            // 检查更新按钮
            IconButton(
                onClick = { vm.checkModpackUpdates(mp.name) },
                enabled = !busy && !updateChecking
            ) {
                if (updateChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = I18n.t("modpack.check_update"),
                         modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = { showDeleteConfirm = true }, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"),
                     tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(I18n.t("modpack.delete_title")) },
            text = { Text(I18n.t("modpack.delete_confirm", mp.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteModpack(mp.name)
                }) { Text(I18n.t("common.delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}

/**
 * 整合包更新检查结果对话框：显示有更新的 mod 列表。
 */
@Composable
private fun ModpackUpdateDialog(
    result: ModpackManager.ModpackUpdateResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("modpack.update_result_title", result.instanceName)) },
        text = {
            Column {
                if (!result.isSuccess()) {
                    Text(
                        result.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (!result.hasUpdates()) {
                    Text(
                        I18n.t("modpack.update_no_updates", result.totalChecked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        I18n.t("modpack.update_has_updates", result.updates.size, result.totalChecked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    // 有更新的 mod 列表
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(result.updates.size) { idx ->
                            val u = result.updates[idx]
                            Surface(
                                tonalElevation = 1.dp,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(
                                        u.fileName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            u.currentVersion.ifEmpty { I18n.t("modpack.update_unknown") },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Icon(Icons.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp).padding(horizontal = 4.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            u.latestVersion,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.confirm")) }
        }
    )
}

@Composable
private fun ImportModpackDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("modpack.import_title")) },
        text = {
            Column {
                Text(I18n.t("modpack.import_hint"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(I18n.t("modpack.file_path")) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val fd = FileDialog(null as Frame?, I18n.t("modpack.select_file"), FileDialog.LOAD)
                            fd.filenameFilter = FilenameFilter { _, name ->
                                name.endsWith(".mrpack") || name.endsWith(".zip")
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
private fun ExportModpackDialog(
    versionId: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var path by remember { mutableStateOf("") }
    // 0 = Modrinth (.mrpack)，1 = CurseForge (.zip)
    var format by remember { mutableStateOf(0) }

    LaunchedEffect(versionId, format) {
        val home = System.getProperty("user.home")
        val downloads = java.nio.file.Paths.get(home, "Downloads").toString()
        path = if (format == 0) "$downloads/$versionId.mrpack"
               else "$downloads/$versionId.zip"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("modpack.export_title")) },
        text = {
            Column {
                Text(I18n.t("modpack.export_hint", versionId),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                // 格式选择
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = format == 0,
                        onClick = { format = 0 },
                        label = { Text("Modrinth (.mrpack)") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = format == 1,
                        onClick = { format = 1 },
                        label = { Text("CurseForge (.zip)") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(I18n.t("modpack.save_path")) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val fd = FileDialog(null as Frame?, I18n.t("modpack.save_file"), FileDialog.SAVE)
                            fd.file = if (format == 0) "$versionId.mrpack" else "$versionId.zip"
                            fd.filenameFilter = FilenameFilter { _, name ->
                                name.endsWith(".mrpack") || name.endsWith(".zip")
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
                Spacer(Modifier.height(4.dp))
                Text(if (format == 0) I18n.t("modpack.export_content")
                     else I18n.t("modpack.export_content_cf"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (path.isNotEmpty()) {
                        val fmt = if (format == 0) "modrinth" else "curseforge"
                        onConfirm(path, fmt)
                    }
                },
                enabled = path.isNotEmpty()
            ) { Text(I18n.t("common.export")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
        }
    )
}
