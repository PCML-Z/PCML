package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.mods.ModConflictChecker
import com.pmcl.core.mods.ModMeta
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel

@Composable
fun ModsPage(vm: LauncherViewModel) {
    val installedMods by vm.installedMods.collectAsState()
    val conflicts by vm.modConflicts.collectAsState()
    val status by vm.status.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedLoader by remember { mutableStateOf<String?>(null) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(ModSort.NAME) }

    LaunchedEffect(Unit) { vm.refreshInstalledMods() }

    // 提取所有加载器类型（用于筛选 chips）
    val loaders = remember(installedMods) {
        installedMods.map { it.getLoader() ?: "unknown" }.distinct().sorted()
    }
    // 提取所有来源（游戏版本/整合包）标签
    val sources = remember(installedMods) {
        installedMods.map { it.getSource() ?: "未知" }.distinct().sorted()
    }

    // 搜索 + 来源筛选 + 加载器筛选 + 排序
    val processedMods = remember(installedMods, query, selectedLoader, selectedSource, sortBy) {
        var list = if (query.isBlank()) installedMods
        else installedMods.filter {
            (it.getName() ?: "").contains(query, ignoreCase = true) ||
            (it.getModId() ?: "").contains(query, ignoreCase = true) ||
            (it.getLoader() ?: "").contains(query, ignoreCase = true)
        }
        if (selectedSource != null) {
            list = list.filter { (it.getSource() ?: "未知") == selectedSource }
        }
        if (selectedLoader != null) {
            list = list.filter { (it.getLoader() ?: "unknown") == selectedLoader }
        }
        when (sortBy) {
            ModSort.NAME -> list.sortedBy { (it.getName() ?: "").lowercase() }
            ModSort.VERSION -> list.sortedBy { (it.getVersion() ?: "").lowercase() }
            ModSort.LOADER -> list.sortedBy { (it.getLoader() ?: "").lowercase() }
            ModSort.STATUS -> list.sortedByDescending { it.isDisabled() } // 启用在前
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模组管理", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { vm.openModsDir() }) {
                Text("打开目录")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = vm::refreshInstalledMods) {
                Icon(Icons.Filled.Refresh, contentDescription = null,
                     modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("目录：${vm.config.getWorkDir().resolve("mods")}",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        // === 搜索框 ===
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索模组名 / ID / 加载器…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // === 来源（游戏版本/整合包）筛选 ===
        if (sources.size > 1 || (sources.size == 1 && sources[0] != "全局")) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("来源：", style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                val sourceItems = listOf("全部") + sources
                com.pmcl.ui.animation.AnimatedSegmentedSelector(
                    items = sourceItems,
                    selectedIndex = if (selectedSource == null) 0
                        else sourceItems.indexOf(selectedSource).coerceAtLeast(0),
                    onSelect = { selectedSource = if (it == 0) null else sourceItems[it] },
                    height = 30.dp
                )
            }
        }

        // === 加载器筛选 + 排序 ===
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("加载器：", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.outline)
            val loaderItems = listOf("全部") + loaders
            com.pmcl.ui.animation.AnimatedSegmentedSelector(
                items = loaderItems,
                selectedIndex = if (selectedLoader == null) 0
                    else loaderItems.indexOf(selectedLoader).coerceAtLeast(0),
                onSelect = { selectedLoader = if (it == 0) null else loaderItems[it] },
                height = 30.dp
            )
            Spacer(Modifier.weight(1f))
            // 排序下拉
            Box {
                OutlinedButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sortBy.label)
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    ModSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            onClick = { sortBy = s; sortExpanded = false }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 冲突报告
        if (conflicts != null && conflicts!!.hasIssues()) {
            ConflictCard(conflicts!!)
            Spacer(Modifier.height(12.dp))
        }

        // 已安装列表
        val enabledCount = installedMods.count { !it.isDisabled() }
        val disabledCount = installedMods.size - enabledCount
        Text("已安装（${installedMods.size}：启用 $enabledCount / 禁用 $disabledCount）" +
             if (query.isNotBlank() || selectedLoader != null || selectedSource != null)
                 " · 当前显示 ${processedMods.size}" else "",
             style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (processedMods.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (installedMods.isEmpty()) "mods 目录为空，前往「市场」下载模组"
                         else "无匹配结果",
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(processedMods, key = { _, m -> System.identityHashCode(m) }) { index, m ->
                    StaggeredAppear(index) {
                        ModRow(m, vm)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("状态：$status", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }
}

enum class ModSort(val label: String) {
    NAME("按名称"), VERSION("按版本"), LOADER("按加载器"), STATUS("按状态")
}

@Composable
private fun ModRow(m: ModMeta, vm: LauncherViewModel) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Surface(
        color = if (m.isDisabled()) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (m.getName() ?: m.getJarFile() ?: "未知") + if (m.isDisabled()) "（已禁用）" else "",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    color = if (m.isDisabled()) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface
                )
                AssistChip(onClick = {}, label = { Text(m.getLoader() ?: "unknown") })
                Spacer(Modifier.width(6.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(m.getSource() ?: "未知") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text("v${m.getVersion() ?: "?"}", style = MaterialTheme.typography.labelSmall)
            }
            val desc = m.getDescription()
            if (!desc.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(desc,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 2)
            }
            Spacer(Modifier.height(4.dp))
            Text("${m.getJarFile() ?: "?"}  ·  ${m.getModId() ?: "?"}",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            val authors = m.getAuthors()
            if (!authors.isNullOrEmpty()) {
                Text("作者：$authors",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.isDisabled()) {
                    TextButton(onClick = { vm.enableMod(m.getJarFile()) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("启用")
                    }
                } else {
                    TextButton(onClick = { vm.disableMod(m.getJarFile()) }) {
                        Icon(Icons.Filled.Pause, null, Modifier.size(14.dp))
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
                    Icon(Icons.Filled.Delete, contentDescription = null,
                         modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除模组") },
            text = { Text("确定要删除 ${m.getName()} 吗？\n文件：${m.getJarFile()}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteMod(m.getJarFile())
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

@Composable
private fun ConflictCard(result: ModConflictChecker.Result) {
    val hasErrors = result.getErrors().isNotEmpty()
    val colors = if (hasErrors) MaterialTheme.colorScheme.errorContainer
                 else MaterialTheme.colorScheme.tertiaryContainer
    Surface(color = colors, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (hasErrors) "存在 ${result.getErrors().size} 个潜在问题（仅供参考，不阻断启动）"
                else "存在 ${result.getWarnings().size} 个警告",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            for (e in result.getErrors()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Circle, null, Modifier.size(6.dp).padding(top = 6.dp),
                         tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(e, style = MaterialTheme.typography.bodySmall)
                }
            }
            for (w in result.getWarnings()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Circle, null, Modifier.size(6.dp).padding(top = 6.dp),
                         tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                    Text(w, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
