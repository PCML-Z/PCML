package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.gamecontent.ResourcePackManager
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel

/** 资源包列表页（与 ShaderPacksPage 风格一致） */
@Composable
fun ResourcePacksPage(vm: LauncherViewModel) {
    val packs by vm.resourcePacks.collectAsState()
    val status by vm.status.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshResourcePacks() }

    val filtered = remember(packs, query) {
        if (query.isBlank()) packs
        else packs.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // === 标题栏 ===
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("资源包", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { vm.openResourcePacksDir() }) {
                Text("打开目录")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = vm::refreshResourcePacks) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("目录: ${vm.config.workDir.resolve("resourcepacks")}",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        // === 搜索框 ===
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索资源包…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // === 统计 ===
        Spacer(Modifier.height(8.dp))
        Text("共 ${packs.size} 个资源包" +
             if (query.isNotBlank() && filtered.size != packs.size) " · 搜索结果 ${filtered.size}" else "",
             style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (packs.isEmpty()) "暂无资源包。将资源包放入 resourcepacks 目录即可。"
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
                    StaggeredAppear(index) {
                        ResourcePackRow(pack, vm)
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

@Composable
private fun ResourcePackRow(pack: ResourcePackManager.Pack, vm: LauncherViewModel) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    // pack_format 兼容性提示（粗略映射）
    val formatHint = when (pack.packFormat) {
        in 1..3 -> "旧版（1.6-1.16）"
        in 4..6 -> "1.13-1.16"
        in 7..8 -> "1.17-1.18"
        in 9..12 -> "1.19"
        in 13..15 -> "1.20"
        in 16..22 -> "1.20.2+"
        in 23..99 -> "1.21+"
        else -> "未知"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pack.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text("format ${pack.packFormat}") })
                Spacer(Modifier.width(8.dp))
                Text(if (pack.isZip) "zip" else "dir",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            if (pack.getDescription().isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(pack.getDescription().take(120),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 2)
            }
            Spacer(Modifier.height(4.dp))
            Text("兼容：$formatHint",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(8.dp))
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除资源包") },
            text = { Text("确定要删除 ${pack.name} 吗？\n路径：${pack.path}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteResourcePack(pack)
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
