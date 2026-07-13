@file:OptIn(ExperimentalFoundationApi::class)

package com.pmcl.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
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
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel

@Composable
fun ShaderPacksPage(vm: LauncherViewModel) {
    val packs by vm.shaderPacks.collectAsState()
    val status by vm.status.collectAsState()
    var query by remember { mutableStateOf("") }
    var sortExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(ShaderSort.NAME) }

    LaunchedEffect(Unit) { vm.refreshShaderPacks() }

    val filtered = remember(packs, query, sortBy) {
        var list = if (query.isBlank()) packs
        else packs.filter { it.name.contains(query, ignoreCase = true) }
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
            Text("光影包", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { vm.openShaderPacksDir() }) {
                Text("打开目录")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = vm::refreshShaderPacks) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("目录: ${vm.config.workDir.resolve("shaderpacks")}",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索光影包…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // === 排序 ===
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("共 ${packs.size} 个光影包（当前应用 $activeCount）" +
                 if (query.isNotBlank() && filtered.size != packs.size) " · 搜索结果 ${filtered.size}" else "",
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

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (packs.isEmpty()) "暂无光影包。将 .zip 光影包放入 shaderpacks 目录即可。"
                         else "无匹配结果",
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
                            ShaderPackRow(pack, vm, isActive = pack.isActive)
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
}

enum class ShaderSort(val label: String) {
    NAME("按名称"), SIZE_DESC("大小 ↓"), SIZE_ASC("大小 ↑"), ACTIVE("已应用优先")
}

/** 人类可读文件大小 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

@Composable
private fun ShaderPackRow(
    pack: ShaderPackManager.ShaderPack,
    vm: LauncherViewModel,
    isActive: Boolean
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().alpha(if (pack.isDisabled) 0.5f else 1f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pack.name, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.weight(1f))
                if (pack.isDisabled) {
                    AssistChip(onClick = {}, label = { Text("已禁用") })
                    Spacer(Modifier.width(4.dp))
                }
                if (isActive) {
                    AssistChip(
                        onClick = {},
                        label = { Text("当前") },
                        leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(14.dp)) }
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (!pack.source.isNullOrEmpty()) {
                    AssistChip(onClick = {}, label = { Text(pack.source) })
                    Spacer(Modifier.width(4.dp))
                }
                Text(formatFileSize(pack.size),
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
                Text(if (pack.isValid) "含 shaders/ 目录（兼容 Iris/OptiFine）"
                     else "缺少 shaders/ 目录，可能无法生效",
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
                        Text("启用")
                    }
                } else {
                    OutlinedButton(onClick = { vm.disableShaderPack(pack) }) {
                        Icon(Icons.Filled.Pause, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("禁用")
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
                        Text("应用")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (isActive) {
                    OutlinedButton(onClick = { vm.clearActiveShaderPack() }) {
                        Text("关闭光影")
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
                    Text("删除")
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除光影包") },
            text = { Text("确定要删除 ${pack.name} 吗？\n文件：${pack.path}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteShaderPack(pack)
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}
