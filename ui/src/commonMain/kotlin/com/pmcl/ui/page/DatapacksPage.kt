@file:OptIn(ExperimentalFoundationApi::class)

package com.pmcl.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.gamecontent.DatapackManager
import com.pmcl.core.gamecontent.WorldManager
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 数据包管理页：先选择世界，再列出该世界的 datapacks。
 * 数据包位于 saves/<world>/datapacks/。
 */
@Composable
fun DatapacksPage(vm: LauncherViewModel) {
    val worlds by vm.worlds.collectAsState()
    val selectedWorld by vm.selectedDatapackWorld.collectAsState()
    val sw = selectedWorld
    val datapacks by vm.datapacks.collectAsState()
    val status by vm.status.collectAsState()
    var worldQuery by remember { mutableStateOf("") }
    var dpQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (worlds.isEmpty()) vm.refreshWorlds() }

    val filteredWorlds = remember(worlds, worldQuery) {
        if (worldQuery.isBlank()) worlds
        else worlds.filter { it.name.contains(worldQuery, ignoreCase = true) }
    }
    val filteredDatapacks = remember(datapacks, dpQuery) {
        if (dpQuery.isBlank()) datapacks
        else datapacks.filter { it.name.contains(dpQuery, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // === 标题栏 ===
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("数据包", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (sw != null) {
                OutlinedButton(onClick = { vm.openDatapacksDir(sw) }) {
                    Text("打开目录")
                }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = { vm.refreshWorlds() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新世界")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("数据包位于 saves/<世界>/datapacks/，请先选择一个世界",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        // === 当前选中的世界 ===
        if (sw != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("当前世界：${sw.name}",
                         fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("来源：${sw.source}",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { vm.selectDatapackWorld(sw) }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重新扫描")
                    }
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = { vm.clearDatapackWorld() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("返回世界列表")
                    }
                }
            }
        }

        // === 世界列表（未选世界时显示） ===
        if (sw == null) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = worldQuery,
                onValueChange = { worldQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索世界…") },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("共 ${worlds.size} 个世界" +
                 if (worldQuery.isNotBlank() && filteredWorlds.size != worlds.size) " · 搜索结果 ${filteredWorlds.size}" else "",
                 style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            if (filteredWorlds.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (worlds.isEmpty()) "未扫描到存档。请先在游戏中创建世界。"
                             else "无匹配结果",
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(filteredWorlds, key = { _, w -> w.dir.toString() }) { index, world ->
                        Box(Modifier.animateItemPlacement()) {
                            StaggeredAppear(index) {
                                WorldSelectRow(world, vm)
                            }
                        }
                    }
                }
            }
        } else {
            // === 数据包列表 ===
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = dpQuery,
                onValueChange = { dpQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索数据包…") },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("共 ${datapacks.size} 个数据包" +
                 if (dpQuery.isNotBlank() && filteredDatapacks.size != datapacks.size) " · 搜索结果 ${filteredDatapacks.size}" else "",
                 style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            if (filteredDatapacks.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (datapacks.isEmpty()) "该世界暂无数据包。将数据包放入 datapacks 目录即可。"
                             else "无匹配结果",
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(filteredDatapacks, key = { _, d -> d.path.toString() }) { index, dp ->
                        Box(Modifier.animateItemPlacement()) {
                            StaggeredAppear(index) {
                                DatapackRow(dp, vm)
                            }
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

@Composable
private fun WorldSelectRow(world: WorldManager.WorldInfo, vm: LauncherViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(world.name, fontWeight = FontWeight.SemiBold)
                Text("来源：${world.source}",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            Button(onClick = { vm.selectDatapackWorld(world) }) {
                Text("选择")
            }
        }
    }
}

@Composable
private fun DatapackRow(pack: DatapackManager.Datapack, vm: LauncherViewModel) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val formatHint = when (pack.packFormat) {
        in 1..3 -> "旧版（1.13 前）"
        in 4..6 -> "1.13-1.16"
        in 7..9 -> "1.17-1.19"
        in 10..12 -> "1.19-1.20"
        in 13..15 -> "1.20"
        in 16..99 -> "1.20.2+"
        else -> "未知"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().alpha(if (pack.isDisabled) 0.5f else 1f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pack.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (pack.isDisabled) {
                    AssistChip(onClick = {}, label = { Text("已禁用") })
                    Spacer(Modifier.width(4.dp))
                }
                AssistChip(onClick = {}, label = { Text("format ${pack.packFormat}") })
                Spacer(Modifier.width(8.dp))
                Text(if (pack.isZip) "zip" else "dir",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            if ((pack.getDescription() ?: "").isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text((pack.getDescription() ?: "").take(120),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 2)
            }
            Spacer(Modifier.height(4.dp))
            Text("兼容：$formatHint",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pack.isDisabled) {
                    OutlinedButton(onClick = { vm.enableDatapack(pack) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("启用")
                    }
                } else {
                    OutlinedButton(onClick = { vm.disableDatapack(pack) }) {
                        Icon(Icons.Filled.Pause, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("禁用")
                    }
                }
                Spacer(Modifier.width(8.dp))
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
            title = { Text("删除数据包") },
            text = { Text("确定要删除 ${pack.name} 吗？\n路径：${pack.path}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteDatapack(pack)
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
