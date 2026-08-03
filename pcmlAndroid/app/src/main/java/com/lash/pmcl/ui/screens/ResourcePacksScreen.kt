package com.lash.pmcl.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.lash.pmcl.core.gamecontent.ResourcePackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ResourcePacksScreen(resourcePackManager: ResourcePackManager) {
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<ResourcePackManager.Pack>>(emptyList()) }
    var status by remember { mutableStateOf("就绪") }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ResourcePackManager.Pack?>(null) }
    var detailPack by remember { mutableStateOf<ResourcePackManager.Pack?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                packs = withContext(Dispatchers.IO) { resourcePackManager.list() }
                status = "已加载 ${packs.size} 个资源包"
            } catch (e: Exception) { status = "加载失败: ${e.message}" }
            finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val filtered = remember(packs, query) {
        if (query.isBlank()) packs
        else packs.filter { it.name.contains(query, ignoreCase = true) ||
                            (it.description).contains(query, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("资源包", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("位于 resourcepacks/ 目录", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索资源包...") },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true, shape = RoundedCornerShape(12.dp))

        Spacer(Modifier.height(8.dp))
        Text("共 ${packs.size} 个资源包", style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inventory2, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text(if (packs.isEmpty()) "暂无资源包\n将 .zip 或目录放入 resourcepacks 目录"
                         else "无匹配结果", color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.path.toString() }) { pack ->
                    Surface(
                        color = if (pack.disabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (pack.isZip) Icons.Filled.Inventory2 else Icons.Filled.Folder,
                                null, Modifier.size(32.dp),
                                tint = if (pack.disabled) MaterialTheme.colorScheme.outline
                                       else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pack.name, style = MaterialTheme.typography.titleSmall,
                                     fontWeight = FontWeight.SemiBold, maxLines = 1,
                                     overflow = TextOverflow.Ellipsis)
                                if (pack.description.isNotEmpty()) {
                                    Text(pack.description.take(80), style = MaterialTheme.typography.bodySmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (pack.isZip) "zip" else "dir",
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.outline)
                                    if (pack.packFormat > 0) {
                                        Text("format ${pack.packFormat}",
                                             style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.outline)
                                    }
                                    Text(if (pack.disabled) "已禁用" else "已启用",
                                         style = MaterialTheme.typography.labelSmall,
                                         color = if (pack.disabled) MaterialTheme.colorScheme.outline
                                                 else MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        val fn = pack.path.fileName.toString()
                                        if (pack.disabled)
                                            withContext(Dispatchers.IO) { resourcePackManager.enable(fn) }
                                        else
                                            withContext(Dispatchers.IO) { resourcePackManager.disable(fn) }
                                        refresh()
                                    } catch (e: Exception) { status = "操作失败: ${e.message}" }
                                }
                            }) {
                                Icon(if (pack.disabled) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                     if (pack.disabled) "启用" else "禁用",
                                     tint = if (pack.disabled) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { detailPack = pack }) {
                                Icon(Icons.Filled.Info, "详情", tint = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { deleteTarget = pack }) {
                                Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除资源包") },
            text = { Text("确定要删除「${target.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { withContext(Dispatchers.IO) { resourcePackManager.delete(target) }; refresh() }
                        catch (e: Exception) { status = "操作失败: ${e.message}" }
                    }
                    deleteTarget = null
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    detailPack?.let { p ->
        AlertDialog(
            onDismissRequest = { detailPack = null },
            title = { Text(p.name, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("路径: ${p.path}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("格式: pack_format ${p.packFormat}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("类型: ${if (p.isZip) "zip" else "目录"}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("状态: ${if (p.disabled) "已禁用" else "已启用"}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("描述: ${p.description.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { detailPack = null }) { Text("关闭") } }
        )
    }
}
