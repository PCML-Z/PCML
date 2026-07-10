package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.gamecontent.WorldManager
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun WorldsPage(vm: LauncherViewModel) {
    val scope = rememberCoroutineScope()
    val worlds by vm.worlds.collectAsState()
    val datapacks by vm.datapacks.collectAsState()
    val status by vm.status.collectAsState()
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    var selectedWorld by remember { mutableStateOf<WorldManager.WorldInfo?>(null) }

    LaunchedEffect(Unit) { vm.refreshWorlds() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("世界管理", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.refreshWorlds() }) { Text("刷新") }
        }
        Spacer(Modifier.height(8.dp))
        Text("已合并扫描：PMCL / 外部启动器 / 整合包版本目录下的 saves",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))

        if (worlds.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("暂无世界。开始游戏后会自动在 saves 目录创建。",
                     modifier = Modifier.padding(16.dp),
                     color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(worlds) { index, world ->
                    val isSelected = selectedWorld?.name == world.name
                    StaggeredAppear(index) {
                    Card(
                        Modifier.fillMaxWidth()
                            .then(if (isSelected)
                                Modifier.padding(0.dp) else Modifier)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(world.name, fontWeight = FontWeight.SemiBold,
                                     modifier = Modifier.weight(1f))
                                if (isSelected) {
                                    AssistChip(onClick = {}, label = { Text("选中") },
                                               leadingIcon = {
                                                   Icon(Icons.Filled.Star, null,
                                                        Modifier.size(14.dp))
                                               })
                                }
                            }
                            Text("来源: ${world.source}",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.tertiary)
                            Text("大小: ${world.sizeBytes / 1024 / 1024} MB",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.outline)
                            Text("最后修改: ${format.format(Date(world.lastModified))}",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch { vm.backupWorld(world) }
                                }) {
                                    Icon(Icons.Filled.Star, contentDescription = null,
                                         modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("备份")
                                }
                                OutlinedButton(onClick = {
                                    selectedWorld = world
                                    vm.refreshDatapacks(world.dir)
                                }) {
                                    Text("数据包")
                                }
                                OutlinedButton(onClick = {
                                    scope.launch { vm.deleteWorld(world) }
                                }, colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error)) {
                                    Icon(Icons.Filled.Delete, contentDescription = null,
                                         modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("删除")
                                }
                            }
                        }
                    }
                    }
                }
            }
        }

        // 数据包区域
        selectedWorld?.let { w ->
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("${w.name} 的数据包", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (datapacks.isEmpty()) {
                Text("暂无数据包（或 datapacks 目录为空）",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp),
                           modifier = Modifier.heightIn(max = 240.dp)) {
                    items(datapacks) { dp ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(dp.getName(),
                                         style = MaterialTheme.typography.bodyMedium,
                                         fontWeight = FontWeight.SemiBold)
                                    Text("format=${dp.getPackFormat()}  ${if (dp.isZip()) "zip" else "dir"}",
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.outline)
                                    if (dp.getDescription().isNotEmpty()) {
                                        Text(dp.getDescription().take(80),
                                             style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                OutlinedButton(onClick = {
                                    scope.launch { vm.deleteDatapack(dp); vm.refreshDatapacks(w.dir) }
                                }) {
                                    Icon(Icons.Filled.Delete, null, Modifier.size(14.dp))
                                }
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
