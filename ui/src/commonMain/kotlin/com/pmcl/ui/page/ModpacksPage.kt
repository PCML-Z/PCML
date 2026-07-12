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
import com.pmcl.core.install.InstallProgress
import com.pmcl.core.modpack.ModpackManager
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

    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshModpacks() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶部操作栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("整合包管理", style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.refreshModpacks() }, enabled = !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            Button(
                onClick = { showImportDialog = true },
                enabled = !busy,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入")
            }
            Button(
                onClick = { showExportDialog = true },
                enabled = !busy && selectedVersion != null,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导出")
            }
        }

        Spacer(Modifier.height(8.dp))

        // 进度条
        if (progress != null) {
            val p = progress!!
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.getMessage() ?: "处理中...", style = MaterialTheme.typography.bodySmall)
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
                    Text("暂无整合包", color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    Text("点击「导入」安装 .mrpack 或 .zip 整合包",
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
                    ModpackCard(vm, mp, busy)
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
            onConfirm = { path ->
                showExportDialog = false
                vm.exportModpack(path)
            }
        )
    }
}

@Composable
private fun ModpackCard(vm: LauncherViewModel, mp: ModpackManager.InstalledModpack, busy: Boolean) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
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
                        label = { Text("${mp.modCount} 个模组", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            IconButton(onClick = { showDeleteConfirm = true }, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = "删除",
                     tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除整合包") },
            text = { Text("确定要删除整合包「${mp.name}」吗？\n这将删除该实例的所有 mods、saves 和 config。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteModpack(mp.name)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ImportModpackDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入整合包") },
        text = {
            Column {
                Text("选择 .mrpack (Modrinth) 或 .zip (CurseForge) 整合包文件",
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("文件路径") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val fd = FileDialog(null as Frame?, "选择整合包文件", FileDialog.LOAD)
                            fd.filenameFilter = FilenameFilter { _, name ->
                                name.endsWith(".mrpack") || name.endsWith(".zip")
                            }
                            fd.isVisible = true
                            if (fd.file != null) {
                                path = java.io.File(fd.directory, fd.file).absolutePath
                            }
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "浏览")
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (path.isNotEmpty()) onConfirm(path) },
                enabled = path.isNotEmpty()
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ExportModpackDialog(
    versionId: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var path by remember { mutableStateOf("") }

    LaunchedEffect(versionId) {
        val home = System.getProperty("user.home")
        path = "$home/Downloads/$versionId.mrpack"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出整合包") },
        text = {
            Column {
                Text("将版本「$versionId」导出为 Modrinth .mrpack 格式",
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("保存路径") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val fd = FileDialog(null as Frame?, "保存整合包", FileDialog.SAVE)
                            fd.file = "$versionId.mrpack"
                            fd.filenameFilter = FilenameFilter { _, name ->
                                name.endsWith(".mrpack")
                            }
                            fd.isVisible = true
                            if (fd.file != null) {
                                path = java.io.File(fd.directory, fd.file).absolutePath
                            }
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "浏览")
                        }
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text("导出包含：mods/、config/、resourcepacks/、shaderpacks/、options.txt",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (path.isNotEmpty()) onConfirm(path) },
                enabled = path.isNotEmpty()
            ) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
