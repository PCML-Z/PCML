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
import com.lash.pmcl.core.gamecontent.ShaderPackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShaderPacksScreen(shaderPackManager: ShaderPackManager) {
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<ShaderPackManager.ShaderPack>>(emptyList()) }
    var status by remember { mutableStateOf("就绪") }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ShaderPackManager.ShaderPack?>(null) }
    var detailPack by remember { mutableStateOf<ShaderPackManager.ShaderPack?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                packs = withContext(Dispatchers.IO) { shaderPackManager.list() }
                status = "已加载 ${packs.size} 个光影包"
            } catch (e: Exception) { status = "加载失败: ${e.message}" }
            finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val filtered = remember(packs, query) {
        if (query.isBlank()) packs else packs.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("光影包", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("位于 shaderpacks/ 目录", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索光影包...") },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true, shape = RoundedCornerShape(12.dp))

        Spacer(Modifier.height(8.dp))
        Text("共 ${packs.size} 个光影包", style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.InvertColors, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text(if (packs.isEmpty()) "暂无光影包\n将 .zip 光影包放入 shaderpacks 目录"
                         else "无匹配结果", color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.path.toString() }) { pack ->
                    Surface(
                        color = if (pack.active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else if (pack.disabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.InvertColors, null, Modifier.size(32.dp),
                                 tint = if (pack.active) MaterialTheme.colorScheme.primary
                                        else if (pack.disabled) MaterialTheme.colorScheme.outline
                                        else MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(pack.name, style = MaterialTheme.typography.titleSmall,
                                         fontWeight = FontWeight.SemiBold, maxLines = 1,
                                         overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (pack.active) {
                                        SuggestionChip(onClick = {}, label = { Text("当前使用") })
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(com.lash.pmcl.core.gamecontent.ConfigFileManager.formatSize(pack.size),
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.outline)
                                    if (pack.valid) {
                                        Text("有效", style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Text("无效", style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.error)
                                    }
                                    if (pack.disabled) {
                                        Text("已禁用", style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                            if (pack.active) {
                                IconButton(onClick = {
                                    scope.launch {
                                        try { withContext(Dispatchers.IO) { shaderPackManager.clearActive() }; refresh() }
                                        catch (e: Exception) { status = "操作失败: ${e.message}" }
                                    }
                                }) { Icon(Icons.Filled.Star, "清除当前", tint = MaterialTheme.colorScheme.primary) }
                            } else {
                                IconButton(onClick = {
                                    scope.launch {
                                        try {
                                            if (pack.valid && !pack.disabled) {
                                                withContext(Dispatchers.IO) { shaderPackManager.setActive(pack) }
                                                refresh()
                                            }
                                        } catch (e: Exception) { status = "操作失败: ${e.message}" }
                                    }
                                }, enabled = pack.valid && !pack.disabled) {
                                    Icon(Icons.Filled.StarBorder, "设为当前",
                                         tint = if (pack.valid && !pack.disabled) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline)
                                }
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        val fn = pack.path.fileName.toString()
                                        if (pack.disabled) withContext(Dispatchers.IO) { shaderPackManager.enable(fn) }
                                        else withContext(Dispatchers.IO) { shaderPackManager.disable(fn) }
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
            title = { Text("删除光影包") },
            text = { Text("确定要删除「${target.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { withContext(Dispatchers.IO) { shaderPackManager.delete(target) }; refresh() }
                        catch (e: Exception) { status = "操作失败: ${e.message}" }
                    }; deleteTarget = null
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
                    Text("大小: ${com.lash.pmcl.core.gamecontent.ConfigFileManager.formatSize(p.size)}",
                         style = MaterialTheme.typography.bodySmall)
                    Text("有效: ${if (p.valid) "是" else "否（缺少 shaders/ 目录）"}",
                         style = MaterialTheme.typography.bodySmall)
                    Text("状态: ${when { p.active -> "当前使用"; p.disabled -> "已禁用"; else -> "已启用" }}",
                         style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { detailPack = null }) { Text("关闭") } }
        )
    }
}
