package com.lash.pmcl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.instance.InstanceInfo
import com.lash.pmcl.core.instance.InstanceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(instanceManager: InstanceManager) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var loading by remember { mutableStateOf(true) }
    var instances by remember { mutableStateOf<List<InstanceInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<InstanceInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<InstanceInfo?>(null) }

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
                            Text("点击右上角 + 创建新实例",
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
                        items(instances, key = { it.instanceId }) { instance ->
                            InstanceCard(
                                instance = instance,
                                onRename = { renameTarget = instance },
                                onCopy = {
                                    Thread {
                                        try {
                                            instanceManager.copyInstance(
                                                instance.instanceId,
                                                instance.name + " (副本)")
                                            refresh()
                                        } catch (_: Exception) {}
                                    }.start()
                                },
                                onDelete = { deleteTarget = instance },
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
                    } catch (_: Exception) {}
                }.start()
                showCreateDialog = false
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
                        } catch (_: Exception) {}
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
                        } catch (_: Exception) {}
                    }.start()
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InstanceCard(
    instance: InstanceInfo,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (instance.type == InstanceInfo.Type.MODPACK)
                    Icons.Outlined.Folder
                else Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
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
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp))
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
