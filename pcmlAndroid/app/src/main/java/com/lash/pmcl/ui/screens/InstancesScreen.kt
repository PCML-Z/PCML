package com.lash.pmcl.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.instance.InstanceExporter
import com.lash.pmcl.core.instance.InstanceImporter
import com.lash.pmcl.core.instance.InstanceInfo
import com.lash.pmcl.core.instance.InstanceManager
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(instanceManager: InstanceManager) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var loading by remember { mutableStateOf(true) }
    var instances by remember { mutableStateOf<List<InstanceInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var detailTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var exportTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var setIconTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var editDescTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var importResult by remember { mutableStateOf<InstanceImporter.ImportResult?>(null) }

    fun refresh() {
        loading = true
        error = null
        Thread {
            try {
                instances = instanceManager.listInstances()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }.start()
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("实例管理") },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Outlined.Upload, contentDescription = "导入实例")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "创建实例")
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(error!!, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                instances.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Folder, contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text("暂无实例", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline)
                            Text("点击右上角 + 创建新实例，或 ↑ 导入实例",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (statusMsg != null) {
                            item {
                                Text(statusMsg!!, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                        items(instances, key = { it.instanceId }) { instance ->
                            InstanceCard(
                                instance = instance,
                                onClick = { detailTarget = instance },
                                onRename = { renameTarget = instance },
                                onCopy = {
                                    Thread {
                                        try {
                                            instanceManager.copyInstance(
                                                instance.instanceId,
                                                instance.name + " (副本)")
                                            refresh()
                                        } catch (e: Exception) {
                                            statusMsg = "复制失败：${e.message}"
                                        }
                                    }.start()
                                },
                                onDelete = { deleteTarget = instance },
                                onExport = { exportTarget = instance },
                                onSetIcon = { setIconTarget = instance },
                                onClearIcon = {
                                    Thread {
                                        try {
                                            instanceManager.clearInstanceIcon(instance.instanceId)
                                            refresh()
                                        } catch (e: Exception) {
                                            statusMsg = "清除图标失败：${e.message}"
                                        }
                                    }.start()
                                },
                                onEditDesc = { editDescTarget = instance },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateInstanceDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, version ->
                Thread {
                    try {
                        instanceManager.createInstance(name, version, null, null)
                        refresh()
                    } catch (e: Exception) {
                        statusMsg = "创建失败：${e.message}"
                    }
                }.start()
                showCreateDialog = false
            }
        )
    }

    if (showImportDialog) {
        PathInputDialog(
            title = "导入实例",
            label = "实例包路径 (.pmcl-instance / .zip)",
            initialPath = "",
            hint = "例如 /sdcard/Download/example.pmcl-instance",
            confirmText = "导入",
            onDismiss = { showImportDialog = false },
            onConfirm = { path ->
                showImportDialog = false
                Thread {
                    try {
                        importResult = InstanceImporter.importInstance(
                            Paths.get(path), instanceManager)
                        refresh()
                    } catch (e: Exception) {
                        statusMsg = "导入失败：${e.message}"
                    }
                }.start()
            }
        )
    }

    renameTarget?.let { target ->
        var newName by remember { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名实例") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("实例名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Thread {
                        try {
                            instanceManager.renameInstance(target.instanceId, newName)
                            refresh()
                        } catch (e: Exception) {
                            statusMsg = "重命名失败：${e.message}"
                        }
                    }.start()
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除实例") },
            text = { Text("确定要删除「${target.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    Thread {
                        try {
                            instanceManager.deleteInstance(target.instanceId)
                            refresh()
                        } catch (e: Exception) {
                            statusMsg = "删除失败：${e.message}"
                        }
                    }.start()
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    detailTarget?.let { target ->
        InstanceDetailDialog(
            instance = target,
            onDismiss = { detailTarget = null },
        )
    }

    exportTarget?.let { target ->
        val suggest = target.name.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".pmcl-instance"
        PathInputDialog(
            title = "导出实例",
            label = "输出文件路径",
            initialPath = "/sdcard/Download/$suggest",
            hint = "将导出为 .pmcl-instance 压缩包（不含 mods/saves 本体）",
            confirmText = "导出",
            onDismiss = { exportTarget = null },
            onConfirm = { path ->
                exportTarget = null
                Thread {
                    try {
                        val count = InstanceExporter.export(target, Paths.get(path))
                        statusMsg = "导出成功：${target.name}（$count 个模组清单）"
                    } catch (e: Exception) {
                        statusMsg = "导出失败：${e.message}"
                    }
                }.start()
            }
        )
    }

    setIconTarget?.let { target ->
        PathInputDialog(
            title = "设置实例图标",
            label = "图片文件路径 (.png/.jpg)",
            initialPath = "",
            hint = "将复制该图片作为「${target.name}」的图标",
            confirmText = "设置",
            onDismiss = { setIconTarget = null },
            onConfirm = { path ->
                setIconTarget = null
                Thread {
                    try {
                        instanceManager.setInstanceIcon(target.instanceId, Paths.get(path))
                        refresh()
                    } catch (e: Exception) {
                        statusMsg = "设置图标失败：${e.message}"
                    }
                }.start()
            }
        )
    }

    editDescTarget?.let { target ->
        EditDescriptionDialog(
            initialDesc = target.description ?: "",
            onDismiss = { editDescTarget = null },
            onConfirm = { newDesc ->
                Thread {
                    try {
                        target.description = newDesc
                        instanceManager.saveInstanceInfo(target)
                        refresh()
                    } catch (e: Exception) {
                        statusMsg = "保存描述失败：${e.message}"
                    }
                }.start()
                editDescTarget = null
            }
        )
    }

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text("导入成功") },
            text = {
                Column {
                    Text("已导入实例「${result.info.name}」",
                        style = MaterialTheme.typography.bodyMedium)
                    if (result.mods.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("模组清单（${result.mods.size} 个，需重新下载 jar 本体）：",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                        ) {
                            result.mods.forEach { mod ->
                                Text("• ${mod.name.ifEmpty { mod.modId }} ${mod.version}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importResult = null }) { Text("好的") }
            },
        )
    }
}

@Composable
private fun InstanceCard(
    instance: InstanceInfo,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onSetIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onEditDesc: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    var menuExpanded by remember { mutableStateOf(false) }

    // 异步加载实例图标
    var iconBmp by remember(instance.instanceId, instance.iconPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(instance.instanceId, instance.iconPath) {
        iconBmp = null
        val dir = instance.instanceDir ?: return@LaunchedEffect
        val iconRel = instance.iconPath ?: return@LaunchedEffect
        val safe = InstanceManager.resolveSafeIconPath(dir, iconRel) ?: return@LaunchedEffect
        iconBmp = withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(safe.toString()) } catch (_: Exception) { null }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconBmp != null) {
                Image(
                    bitmap = iconBmp!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = if (instance.type == InstanceInfo.Type.MODPACK)
                        Icons.Outlined.Folder else Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = instance.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val info = buildString {
                    append(instance.baseVersionId.ifEmpty { "未知版本" })
                    if (!instance.loader.isNullOrBlank()) {
                        append("  ${instance.loader}")
                        if (!instance.loaderVersion.isNullOrBlank()) {
                            append(" ${instance.loaderVersion}")
                        }
                    }
                    if (instance.type == InstanceInfo.Type.MODPACK) {
                        append("  [整合包]")
                    }
                }
                Text(info, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                if (instance.createdAt > 0) {
                    Text("创建于 ${dateFmt.format(Date(instance.createdAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Outlined.Edit, contentDescription = "重命名",
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制",
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多",
                    modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("实例详情") },
                    leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    onClick = { menuExpanded = false; onClick() },
                )
                DropdownMenuItem(
                    text = { Text("导出实例") },
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                    onClick = { menuExpanded = false; onExport() },
                )
                DropdownMenuItem(
                    text = { Text("设置图标") },
                    leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                    onClick = { menuExpanded = false; onSetIcon() },
                )
                DropdownMenuItem(
                    text = { Text("清除图标") },
                    leadingIcon = { Icon(Icons.Outlined.Clear, contentDescription = null) },
                    onClick = { menuExpanded = false; onClearIcon() },
                )
                DropdownMenuItem(
                    text = { Text("编辑描述") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuExpanded = false; onEditDesc() },
                )
                DropdownMenuItem(
                    text = { Text("删除实例", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Delete, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun CreateInstanceDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, version: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.20.4") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建新实例") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("实例名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("Minecraft 版本") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.ifBlank { "新实例" }, version) },
                enabled = name.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PathInputDialog(
    title: String,
    label: String,
    initialPath: String,
    hint: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (path: String) -> Unit,
) {
    var path by remember { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(path.trim()) },
                enabled = path.isNotBlank(),
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun EditDescriptionDialog(
    initialDesc: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var desc by remember { mutableStateOf(initialDesc) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑实例描述") },
        text = {
            OutlinedTextField(
                value = desc,
                onValueChange = { desc = it },
                label = { Text("描述") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(desc) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun InstanceDetailDialog(
    instance: InstanceInfo,
    onDismiss: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("实例详情") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DetailRow("实例 ID", instance.instanceId)
                DetailRow("名称", instance.name)
                DetailRow("基础版本", instance.baseVersionId.ifEmpty { "未设置" })
                DetailRow("类型", if (instance.type == InstanceInfo.Type.MODPACK) "整合包" else "自定义")
                DetailRow("加载器", instance.loader ?: "无")
                DetailRow("加载器版本", instance.loaderVersion ?: "无")
                DetailRow("描述", instance.description?.ifEmpty { "无" } ?: "无")
                DetailRow("图标路径", instance.iconPath ?: "未设置")
                DetailRow("创建时间",
                    if (instance.createdAt > 0) dateFmt.format(Date(instance.createdAt)) else "未知")
                if (instance.lastPlayedAt > 0) {
                    DetailRow("最后游玩", dateFmt.format(Date(instance.lastPlayedAt)))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(88.dp))
        Text(value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
