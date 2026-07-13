@file:OptIn(ExperimentalFoundationApi::class)

package com.pmcl.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.mods.ModConflictChecker
import com.pmcl.core.mods.ModMeta
import com.pmcl.core.mods.ModUpdateChecker
import com.pmcl.ui.animation.AnimatedSegmentedSelector
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel

@Composable
fun ModsPage(vm: LauncherViewModel) {
    val installedMods by vm.installedMods.collectAsState()
    val conflicts by vm.modConflicts.collectAsState()
    val status by vm.status.collectAsState()
    val translationCache by vm.translationCache.collectAsState()
    val translating by vm.translating.collectAsState()
    val modUpdates by vm.modUpdates.collectAsState()
    val checkingUpdates by vm.checkingUpdates.collectAsState()
    val updateProgress by vm.updateCheckProgress.collectAsState()
    val updatingMod by vm.updatingMod.collectAsState()
    var translateEnabled by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedLoader by remember { mutableStateOf<String?>(null) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(ModSort.NAME) }

    // 更新信息映射：modId → UpdateInfo
    val updateInfoMap = remember(modUpdates) {
        modUpdates.associateBy { it.installed.modId ?: "" }
    }
    val updateCount = remember(modUpdates) { modUpdates.count { it.hasUpdate() } }

    LaunchedEffect(Unit) { vm.refreshInstalledMods() }

    val loaders = remember(installedMods) {
        installedMods.map { it.getLoader() ?: "unknown" }.distinct().sorted()
    }
    val sources = remember(installedMods) {
        installedMods.map { it.getSource() ?: "未知" }.distinct().sorted()
    }

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
            ModSort.STATUS -> list.sortedByDescending { it.isDisabled() }
        }
    }

    val enabledCount = remember(installedMods) { installedMods.count { !it.isDisabled() } }
    val disabledCount = remember(installedMods) { installedMods.count { it.isDisabled() } }
    val hasFilter = query.isNotBlank() || selectedLoader != null || selectedSource != null

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // === 顶部栏：标题 + 计数 + 操作按钮 ===
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模组管理", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "${installedMods.size}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.weight(1f))
            // 检查更新按钮
            if (updateCount > 0) {
                Button(
                    onClick = { vm.updateAllMods() },
                    enabled = !updatingMod && !checkingUpdates,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Filled.Update, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("一键更新 ($updateCount)")
                }
                Spacer(Modifier.width(6.dp))
            }
            // 翻译开关（图标按钮）
            FilterChip(
                selected = translateEnabled,
                onClick = {
                    translateEnabled = !translateEnabled
                    if (translateEnabled) {
                        val texts = installedMods.flatMap {
                            listOfNotNull(it.getName(), it.getDescription())
                        }.distinct()
                        vm.translateBatch(texts)
                    }
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (translateEnabled) Icons.Filled.Translate else Icons.Outlined.Translate,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (translating) "翻译中…" else "翻译")
                    }
                }
            )
            Spacer(Modifier.width(6.dp))
            // 检查更新（图标按钮）
            IconButton(onClick = { vm.checkModUpdates() }, enabled = !checkingUpdates) {
                if (checkingUpdates) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Filled.Update, contentDescription = "检查更新",
                         modifier = Modifier.size(18.dp))
                }
            }
            // 打开目录（图标按钮）
            IconButton(onClick = { vm.openModsDir() }) {
                Icon(Icons.Filled.Folder, contentDescription = "打开目录", modifier = Modifier.size(18.dp))
            }
            // 刷新（图标按钮）
            IconButton(onClick = vm::refreshInstalledMods) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
            }
        }

        // === 元信息行：目录路径 + 状态 ===
        Text(
            "目录：${vm.config.getWorkDir().resolve("mods")}  ·  状态：$status",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // === 更新检测进度条 ===
        if (checkingUpdates || updatingMod) {
            Spacer(Modifier.height(6.dp))
            val (done, total) = updateProgress
            val label = if (checkingUpdates) "检测更新" else "批量更新"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$label $done/$total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        // === 搜索 + 排序 ===
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索模组名 / ID / 加载器…") },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(8.dp))
            // 排序下拉
            Box {
                OutlinedButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.Filled.Sort, null, Modifier.size(16.dp))
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

        // === 筛选条：来源 + 加载器（单行横向滚动） ===
        val showSourceFilter = sources.size > 1 || (sources.size == 1 && sources[0] != "全局")
        if (showSourceFilter || loaders.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showSourceFilter) {
                    val sourceItems = listOf("来源") + sources
                    AnimatedSegmentedSelector(
                        items = sourceItems,
                        selectedIndex = if (selectedSource == null) 0
                            else sourceItems.indexOf(selectedSource).coerceAtLeast(0),
                        onSelect = { selectedSource = if (it == 0) null else sourceItems[it] },
                        height = 30.dp
                    )
                }
                if (loaders.isNotEmpty()) {
                    val loaderItems = listOf("加载器") + loaders
                    AnimatedSegmentedSelector(
                        items = loaderItems,
                        selectedIndex = if (selectedLoader == null) 0
                            else loaderItems.indexOf(selectedLoader).coerceAtLeast(0),
                        onSelect = { selectedLoader = if (it == 0) null else loaderItems[it] },
                        height = 30.dp
                    )
                }
            }
        }

        // === 冲突报告 ===
        val conflictsData = conflicts
        if (conflictsData != null && conflictsData.hasIssues()) {
            Spacer(Modifier.height(10.dp))
            ConflictCard(conflictsData)
        }

        // === 列表标题 ===
        Spacer(Modifier.height(10.dp))
        Text(
            "启用 $enabledCount · 禁用 $disabledCount" +
            if (hasFilter) " · 当前显示 ${processedMods.size}" else "",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))

        // === 已安装列表 ===
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
                    Box(Modifier.animateItemPlacement()) {
                        StaggeredAppear(index) {
                            val updateInfo = updateInfoMap[m.getModId() ?: ""]
                            ModRow(m, vm, translateEnabled, translationCache, updateInfo, updatingMod)
                        }
                    }
                }
            }
        }
    }
}

enum class ModSort(val label: String) {
    NAME("按名称"), VERSION("按版本"), LOADER("按加载器"), STATUS("按状态")
}

@Composable
private fun ModRow(
    m: ModMeta,
    vm: LauncherViewModel,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap(),
    updateInfo: ModUpdateChecker.UpdateInfo? = null,
    updatingMod: Boolean = false
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val rawName = m.getName() ?: m.getJarFile() ?: "未知"
    val displayName = if (translateEnabled) translationCache[rawName] ?: rawName else rawName
    val rawDesc = m.getDescription()
    val displayDesc = if (translateEnabled) translationCache[rawDesc] ?: rawDesc else rawDesc

    Surface(
        color = if (m.isDisabled()) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 第一行：名称 + 版本 + 标签 + 操作按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态指示点
                Icon(
                    Icons.Filled.Circle,
                    contentDescription = null,
                    modifier = Modifier.size(6.dp),
                    tint = if (m.isDisabled()) MaterialTheme.colorScheme.outline
                           else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    displayName + if (m.isDisabled()) "（已禁用）" else "",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (m.isDisabled()) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface
                )
                Text("v${m.getVersion() ?: "?"}",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                // 更新徽章（有更新时显示）
                if (updateInfo != null && updateInfo.hasUpdate()) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "有更新",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // 加载器标签
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        m.getLoader() ?: "unknown",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(4.dp))
                // 来源标签
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        m.getSource() ?: "未知",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 启用/禁用 + 删除（图标按钮，紧凑内联）
                if (m.isDisabled()) {
                    IconButton(
                        onClick = { vm.enableMod(m.getJarFile()) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "启用",
                             modifier = Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    IconButton(
                        onClick = { vm.disableMod(m.getJarFile()) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = "禁用",
                             modifier = Modifier.size(16.dp))
                    }
                }
                // 更新按钮（有更新时显示）
                if (updateInfo != null && updateInfo.hasUpdate()) {
                    IconButton(
                        onClick = { vm.updateMod(updateInfo) },
                        modifier = Modifier.size(28.dp),
                        enabled = !updatingMod
                    ) {
                        Icon(Icons.Filled.Update, contentDescription = "更新",
                             modifier = Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除",
                         modifier = Modifier.size(16.dp),
                         tint = MaterialTheme.colorScheme.error)
                }
            }

            // 第二行：描述（可选）
            if (!displayDesc.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(displayDesc,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 2,
                     overflow = TextOverflow.Ellipsis)
            }

            // 第三行：元数据（jar · modId · 作者）合并为一行
            val meta = remember(m) {
                val authors = m.getAuthors()
                buildString {
                    append(m.getJarFile() ?: "?")
                    append("  ·  ").append(m.getModId() ?: "?")
                    if (!authors.isNullOrEmpty()) append("  ·  作者：").append(authors)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(meta,
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline,
                 maxLines = 1,
                 overflow = TextOverflow.Ellipsis)

            // 更新信息行（有更新时显示新版本详情）
            if (updateInfo != null && updateInfo.hasUpdate() && updateInfo.latestFile != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "可更新至: ${updateInfo.latestFile.fileName}  ·  来源: ${updateInfo.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
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
