package com.lash.pmcl.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.modpack.ModpackManager
import java.nio.file.Paths
import java.util.function.Consumer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModpacksScreen(modpackManager: ModpackManager) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    var modpacks by remember { mutableStateOf<List<ModpackManager.InstalledModpack>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<InstallProgress?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var detailModpack by remember { mutableStateOf<ModpackManager.InstalledModpack?>(null) }
    var deleteTarget by remember { mutableStateOf<ModpackManager.InstalledModpack?>(null) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedModpacks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    var filterGameVersion by remember { mutableStateOf<String?>(null) }
    var filterLoader by remember { mutableStateOf<String?>(null) }
    var filterSource by remember { mutableStateOf<String?>(null) }

    var updateChecking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<ModpackManager.ModpackUpdateResult?>(null) }

    fun refresh() {
        loading = true
        error = null
        Thread {
            try {
                modpacks = modpackManager.listInstalledModpacks()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }.start()
    }

    fun importModpack(path: String) {
        if (busy) {
            status = "正在处理中，请稍候..."
            return
        }
        busy = true
        status = "开始导入整合包..."
        progress = InstallProgress(InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0, "开始导入整合包...")
        Thread {
            try {
                modpackManager.importModpack(Paths.get(path), Consumer<InstallProgress> { p ->
                    progress = p
                }).join()
                status = "整合包导入完成"
                refresh()
            } catch (e: Throwable) {
                status = "整合包导入失败: ${e.message ?: e.toString()}"
            } finally {
                busy = false
                progress = null
            }
        }.start()
    }

    fun exportModpack(path: String, format: String) {
        if (busy) {
            status = "正在处理中，请稍候..."
            return
        }
        // Android 版 ModpackManager 暂未实现导出功能
        status = "导出功能在 Android 版暂未实现"
    }

    fun deleteModpack(name: String) {
        if (busy) return
        busy = true
        status = "正在删除整合包..."
        Thread {
            try {
                modpackManager.deleteModpack(name)
                status = "已删除整合包: $name"
                refresh()
            } catch (e: Exception) {
                status = "删除失败: ${e.message ?: e.toString()}"
            } finally {
                busy = false
            }
        }.start()
    }

    fun checkUpdates(instanceName: String) {
        if (updateChecking) return
        updateChecking = true
        updateResult = null
        status = "正在检查更新: $instanceName..."
        Thread {
            try {
                val result = modpackManager.checkForUpdates(instanceName).get()
                updateResult = result
                status = if (result.isSuccess() && !result.hasUpdates()) {
                    "检查完成，暂无更新（共检查 ${result.totalChecked} 个模组）"
                } else if (result.isSuccess()) {
                    "检查完成，发现 ${result.updates.size} 个可更新模组"
                } else {
                    "检查更新失败: ${result.error ?: "未知错误"}"
                }
            } catch (e: Exception) {
                status = "检查更新失败: ${e.message ?: e.toString()}"
            } finally {
                updateChecking = false
            }
        }.start()
    }

    LaunchedEffect(Unit) { refresh() }

    val filteredModpacks = remember(modpacks, filterGameVersion, filterLoader, filterSource) {
        modpacks.filter { mp ->
            (filterGameVersion == null || mp.gameVersion == filterGameVersion) &&
            (filterLoader == null || mp.loader == filterLoader) &&
            (filterSource == null || mp.source == filterSource)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("整合包") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(16.dp)
        ) {
            // 顶部操作栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "整合包",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (selectionMode) {
                    // 批量模式：已选数量 + 批量删除 + 取消
                    Text(
                        "已选 ${selectedModpacks.size} 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { showBatchDeleteConfirm = true },
                        enabled = selectedModpacks.isNotEmpty() && !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("批量删除")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        selectionMode = false
                        selectedModpacks = emptySet()
                    }) { Text("取消") }
                } else {
                    // 正常模式：批量操作开关 + 刷新 + 导入 + 导出
                    FilterChip(
                        selected = false,
                        onClick = { selectionMode = true },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                                    modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("批量操作")
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { refresh() }, enabled = !busy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                    Button(
                        onClick = { showImportDialog = true },
                        enabled = !busy,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入")
                    }
                    Button(
                        onClick = { showExportDialog = true },
                        enabled = !busy,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 状态消息
            status?.let { msg ->
                if (progress == null) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            // 过滤提示条
            if (filterGameVersion != null || filterLoader != null || filterSource != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "结果: ${filteredModpacks.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        filterGameVersion = null
                        filterLoader = null
                        filterSource = null
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清除选择", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // 进度条
            if (progress != null) {
                val p = progress!!
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.message ?: "处理中...", style = MaterialTheme.typography.bodySmall)
                        if (p.total > 0) {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { if (p.total > 0) p.completed.toFloat() / p.total else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 整合包列表
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else if (modpacks.isEmpty() && !busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null,
                            modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text("暂无整合包", color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(4.dp))
                        Text("通过导入 .mrpack 或 .zip 文件添加整合包",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredModpacks, key = { it.instanceDir.toString() }) { mp ->
                        val isSelected = selectionMode && mp.name in selectedModpacks
                        ModpackCard(
                            mp = mp,
                            busy = busy,
                            updateChecking = updateChecking,
                            selectionMode = selectionMode,
                            isSelected = isSelected,
                            onToggleSelect = {
                                selectedModpacks = if (mp.name in selectedModpacks) selectedModpacks - mp.name
                                                   else selectedModpacks + mp.name
                            },
                            onShowDetail = { detailModpack = mp },
                            onDelete = { deleteTarget = mp },
                            onCheckUpdate = { checkUpdates(mp.name) },
                            filterGameVersion = filterGameVersion,
                            filterLoader = filterLoader,
                            filterSource = filterSource,
                            onToggleFilterGameVersion = { v ->
                                filterGameVersion = if (filterGameVersion == v) null else v
                            },
                            onToggleFilterLoader = { l ->
                                filterLoader = if (filterLoader == l) null else l
                            },
                            onToggleFilterSource = { s ->
                                filterSource = if (filterSource == s) null else s
                            }
                        )
                    }
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
                importModpack(path)
            }
        )
    }

    // 导出对话框
    if (showExportDialog) {
        ExportModpackDialog(
            onDismiss = { showExportDialog = false },
            onConfirm = { path, format ->
                showExportDialog = false
                exportModpack(path, format)
            }
        )
    }

    // 详情对话框
    detailModpack?.let { mp ->
        ModpackDetailDialog(mp, onDismiss = { detailModpack = null })
    }

    // 批量删除确认对话框
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量删除") },
            text = { Text("确定要删除选中的 ${selectedModpacks.size} 个整合包吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = selectedModpacks.toList()
                        selectionMode = false
                        selectedModpacks = emptySet()
                        showBatchDeleteConfirm = false
                        toDelete.forEach { name -> deleteModpack(name) }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // 单个删除确认对话框
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除整合包") },
            text = { Text("确定要删除「${target.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    deleteModpack(target.name)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // 更新检查结果对话框
    updateResult?.let { result ->
        ModpackUpdateDialog(
            result = result,
            onDismiss = { updateResult = null }
        )
    }
}

@Composable
private fun ModpackCard(
    mp: ModpackManager.InstalledModpack,
    busy: Boolean,
    updateChecking: Boolean,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onShowDetail: () -> Unit = {},
    onDelete: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    filterGameVersion: String? = null,
    filterLoader: String? = null,
    filterSource: String? = null,
    onToggleFilterGameVersion: (String) -> Unit = {},
    onToggleFilterLoader: (String) -> Unit = {},
    onToggleFilterSource: (String) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 批量选择框
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                Icons.Outlined.Inventory2, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // 整合包名（点击打开详情）
                Text(
                    mp.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onShowDetail() }
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // gameVersion：FilterChip 切换过滤
                    FilterChip(
                        selected = filterGameVersion == mp.gameVersion,
                        onClick = { onToggleFilterGameVersion(mp.gameVersion) },
                        label = { Text(mp.gameVersion.ifEmpty { "未知版本" }, style = MaterialTheme.typography.labelSmall) }
                    )
                    if (mp.loader.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        // loader：FilterChip 切换过滤
                        FilterChip(
                            selected = filterLoader == mp.loader,
                            onClick = { onToggleFilterLoader(mp.loader) },
                            label = { Text(mp.loader, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    // modCount：不可点击的普通标签
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "${mp.modCount} 个模组",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (mp.source.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        // source：FilterChip 切换过滤
                        FilterChip(
                            selected = filterSource == mp.source,
                            onClick = { onToggleFilterSource(mp.source) },
                            label = { Text(mp.source, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
            // 检查更新 + 删除按钮（批量模式下隐藏）
            if (!selectionMode) {
                IconButton(
                    onClick = { onCheckUpdate() },
                    enabled = !busy && !updateChecking
                ) {
                    if (updateChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.SystemUpdate, contentDescription = "检查更新",
                            modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { onDelete() }, enabled = !busy) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 整合包详情对话框
 */
@Composable
private fun ModpackDetailDialog(
    mp: ModpackManager.InstalledModpack,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整合包详情", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(mp.name, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("游戏版本: ${mp.gameVersion.ifEmpty { "-" }}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("加载器: ${mp.loader.ifEmpty { "-" }}${if (mp.loaderVersion.isNotEmpty()) " ${mp.loaderVersion}" else ""}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("模组数量: ${mp.modCount}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("来源: ${mp.source.ifEmpty { "-" }}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("实例目录: ${mp.instanceDir}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 整合包更新检查结果对话框
 */
@Composable
private fun ModpackUpdateDialog(
    result: ModpackManager.ModpackUpdateResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新检查结果: ${result.instanceName}") },
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
                        "共检查 ${result.totalChecked} 个模组，暂无更新。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "发现 ${result.updates.size} 个可更新模组（共检查 ${result.totalChecked} 个）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(result.updates, key = { it.fileName + it.projectId }) { u ->
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
                                            u.currentVersion.ifEmpty { "未知" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Icon(Icons.Outlined.ArrowForward,
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
            TextButton(onClick = onDismiss) { Text("确认") }
        }
    )
}

/**
 * 导入整合包对话框：输入文件路径（.mrpack/.zip）
 */
@Composable
private fun ImportModpackDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入整合包") },
        text = {
            Column {
                Text("请输入整合包文件路径（支持 .mrpack 或 .zip 格式）",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("文件路径") },
                    placeholder = { Text("/sdcard/Download/modpack.mrpack") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            // Android 版使用文本输入，此按钮仅作占位提示
                        }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "浏览")
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

/**
 * 导出整合包对话框：格式选择 + 保存路径输入 + 内容提示
 */
@Composable
private fun ExportModpackDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var path by remember { mutableStateOf("") }
    // 0 = Modrinth (.mrpack)，1 = CurseForge (.zip)
    var format by remember { mutableStateOf(0) }
    var versionId by remember { mutableStateOf("") }

    LaunchedEffect(format, versionId) {
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )?.absolutePath ?: "/sdcard/Download"
        val baseName = versionId.ifEmpty { "modpack" }
        path = if (format == 0) "$downloads/$baseName.mrpack"
               else "$downloads/$baseName.zip"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出整合包") },
        text = {
            Column {
                OutlinedTextField(
                    value = versionId,
                    onValueChange = { versionId = it },
                    label = { Text("版本 ID") },
                    placeholder = { Text("如 1.20.4") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
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
                    label = { Text("保存路径") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "浏览")
                        }
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (format == 0) "将导出为 Modrinth 格式（.mrpack），包含 mods 列表与 overrides 配置文件。"
                    else "将导出为 CurseForge 格式（.zip），包含 manifest.json 与 overrides 配置文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
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
            ) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
