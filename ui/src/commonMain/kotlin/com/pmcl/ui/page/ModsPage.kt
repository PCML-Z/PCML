@file:OptIn(ExperimentalFoundationApi::class)

package com.pmcl.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
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
import com.pmcl.core.i18n.I18n
import com.pmcl.core.mods.ModConflictChecker
import com.pmcl.core.mods.ModMeta
import com.pmcl.core.mods.ModUpdateChecker
import com.pmcl.ui.animation.AnimatedSegmentedSelector
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter

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
    var showImportDialog by remember { mutableStateOf(false) }
    var detailMod by remember { mutableStateOf<ModMeta?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedMods by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // 更新信息映射：modId → UpdateInfo
    val updateInfoMap = remember(modUpdates) {
        modUpdates.associateBy { it.installed.modId ?: "" }
    }
    val updateCount = remember(modUpdates) { modUpdates.count { it.hasUpdate() } }

    // 时间窗守卫：切回 ModsPage 30 秒内不重复触发扫描
    // refreshInstalledMods 内部虽有按目录 mtime 的 modScanCache，但每次仍要 listFiles 所有 versions 子目录 + stat，
    // 100+ 整合包玩家切页频繁时仍是开销。30s 窗口平衡时效与性能
    var lastModsRefreshMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        if (now - lastModsRefreshMs > 30_000L) {
            lastModsRefreshMs = now
            vm.refreshInstalledMods()
        }
    }

    val loaders = remember(installedMods) {
        installedMods.map { it.getLoader() ?: "unknown" }.distinct().sorted()
    }
    val sources = remember(installedMods) {
        installedMods.map { it.getSource() ?: I18n.t("mods.unknown") }.distinct().sorted()
    }

    val processedMods = remember(installedMods, query, selectedLoader, selectedSource, sortBy) {
        var list = if (query.isBlank()) installedMods
        else installedMods.filter {
            (it.getName() ?: "").contains(query, ignoreCase = true) ||
            (it.getModId() ?: "").contains(query, ignoreCase = true) ||
            (it.getLoader() ?: "").contains(query, ignoreCase = true)
        }
        if (selectedSource != null) {
            list = list.filter { (it.getSource() ?: I18n.t("mods.unknown")) == selectedSource }
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
            Text(I18n.t("mods.title"), style = MaterialTheme.typography.headlineSmall,
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
                    Text(I18n.t("mods.update_all", updateCount))
                }
                Spacer(Modifier.width(6.dp))
            }
            // 导入模组按钮
            Button(onClick = { showImportDialog = true }) {
                Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("mods.import_title"))
            }
            Spacer(Modifier.width(6.dp))
            // 批量操作开关
            FilterChip(
                selected = selectionMode,
                onClick = {
                    selectionMode = !selectionMode
                    if (!selectionMode) selectedMods = emptySet()
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Sort, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (selectionMode) I18n.t("common.selected_count", selectedMods.size)
                             else I18n.t("common.batch_actions"))
                    }
                }
            )
            Spacer(Modifier.width(6.dp))
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
                        Text(if (translating) I18n.t("mods.translating") else I18n.t("mods.translate"))
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
                    Icon(Icons.Filled.Update, contentDescription = I18n.t("mods.check_update"),
                         modifier = Modifier.size(18.dp))
                }
            }
            // 打开目录（图标按钮）
            IconButton(onClick = { vm.openModsDir() }) {
                Icon(Icons.Filled.Folder, contentDescription = I18n.t("common.open_dir"), modifier = Modifier.size(18.dp))
            }
            // 刷新（图标按钮）
            IconButton(onClick = vm::refreshInstalledMods) {
                Icon(Icons.Filled.Refresh, contentDescription = I18n.t("common.refresh"), modifier = Modifier.size(18.dp))
            }
        }

        // === 批量操作行（仅在 selectionMode 时显示） ===
        if (selectionMode) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(I18n.t("common.selected_count", selectedMods.size),
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.primary,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = { vm.batchEnableMods(selectedMods.toList()) },
                    enabled = selectedMods.isNotEmpty(),
                    label = { Text(I18n.t("mods.batch_enable_all")) }
                )
                Spacer(Modifier.width(6.dp))
                AssistChip(
                    onClick = { vm.batchDisableMods(selectedMods.toList()) },
                    enabled = selectedMods.isNotEmpty(),
                    label = { Text(I18n.t("mods.batch_disable_all")) }
                )
                Spacer(Modifier.width(6.dp))
                AssistChip(
                    onClick = { showBatchDeleteConfirm = true },
                    enabled = selectedMods.isNotEmpty(),
                    label = { Text(I18n.t("mods.batch_delete_all")) }
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    selectionMode = false
                    selectedMods = emptySet()
                }) { Text(I18n.t("common.cancel")) }
            }
        }

        // === 元信息行：目录路径 + 状态 ===
        Text(
            I18n.t("mods.dir_status", vm.config.getWorkDir().resolve("mods"), status),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // === 更新检测进度条 ===
        if (checkingUpdates || updatingMod) {
            Spacer(Modifier.height(6.dp))
            val (done, total) = updateProgress
            val label = if (checkingUpdates) I18n.t("mods.checking_updates") else I18n.t("mods.batch_update")
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
                placeholder = { Text(I18n.t("mods.search_placeholder")) },
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
                    Text(sortBy.label())
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    ModSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label()) },
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
                    val sourceItems = listOf(I18n.t("mods.source_filter")) + sources
                    AnimatedSegmentedSelector(
                        items = sourceItems,
                        selectedIndex = if (selectedSource == null) 0
                            else sourceItems.indexOf(selectedSource).coerceAtLeast(0),
                        onSelect = { selectedSource = if (it == 0) null else sourceItems[it] },
                        height = 30.dp
                    )
                }
                if (loaders.isNotEmpty()) {
                    val loaderItems = listOf(I18n.t("mods.loader_filter")) + loaders
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
            if (hasFilter) I18n.t("mods.list_summary_filtered", enabledCount, disabledCount, processedMods.size)
            else I18n.t("mods.list_summary", enabledCount, disabledCount),
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
                    Text(if (installedMods.isEmpty()) I18n.t("mods.empty_hint")
                         else I18n.t("search.no_results"),
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
                            val isSelected = selectionMode && (m.getJarFile() in selectedMods)
                            ModRow(
                                m, vm, translateEnabled, translationCache, updateInfo, updatingMod,
                                selectionMode = selectionMode,
                                isSelected = isSelected,
                                onToggleSelect = {
                                    val key = m.getJarFile()
                                    if (key != null) {
                                        selectedMods = if (key in selectedMods) selectedMods - key
                                                       else selectedMods + key
                                    }
                                },
                                onShowDetail = { detailMod = m }
                            )
                        }
                    }
                }
            }
        }
    }

    // 导入模组对话框
    if (showImportDialog) {
        ImportModDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { path ->
                showImportDialog = false
                vm.importMod(path)
            }
        )
    }

    // 详情对话框
    detailMod?.let { mod ->
        ModDetailDialog(
            m = mod,
            translateEnabled = translateEnabled,
            translationCache = translationCache,
            onDismiss = { detailMod = null }
        )
    }

    // 批量删除确认对话框
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(I18n.t("mods.batch_delete_all")) },
            text = { Text(I18n.t("common.selected_count", selectedMods.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.batchDeleteMods(selectedMods.toList())
                        selectedMods = emptySet()
                        selectionMode = false
                        showBatchDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}

enum class ModSort {
    NAME, VERSION, LOADER, STATUS;
    fun label(): String = when (this) {
        NAME -> I18n.t("mods.sort_name")
        VERSION -> I18n.t("mods.sort_version")
        LOADER -> I18n.t("mods.sort_loader")
        STATUS -> I18n.t("mods.sort_status")
    }
}

@Composable
private fun ModRow(
    m: ModMeta,
    vm: LauncherViewModel,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap(),
    updateInfo: ModUpdateChecker.UpdateInfo? = null,
    updatingMod: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onShowDetail: () -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val rawName = m.getName() ?: m.getJarFile() ?: I18n.t("mods.unknown")
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
                // 批量选择框
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // 状态指示点
                Icon(
                    Icons.Filled.Circle,
                    contentDescription = null,
                    modifier = Modifier.size(6.dp),
                    tint = if (m.isDisabled()) MaterialTheme.colorScheme.outline
                           else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                // 模组名（点击打开详情）
                Text(
                    displayName + if (m.isDisabled()) I18n.t("mods.disabled_suffix") else "",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).clickable { onShowDetail() },
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
                            I18n.t("mods.has_update"),
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
                        m.getSource() ?: I18n.t("mods.unknown"),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 启用/禁用 + 删除（图标按钮，紧凑内联）—— 批量模式下隐藏
                if (!selectionMode) {
                    if (m.isDisabled()) {
                        IconButton(
                            onClick = { vm.enableMod(m.getJarFile()) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = I18n.t("common.enable"),
                                 modifier = Modifier.size(16.dp),
                                 tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(
                            onClick = { vm.disableMod(m.getJarFile()) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.Pause, contentDescription = I18n.t("common.disable"),
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
                            Icon(Icons.Filled.Update, contentDescription = I18n.t("mods.update"),
                                 modifier = Modifier.size(16.dp),
                                 tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"),
                             modifier = Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.error)
                    }
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
                    if (!authors.isNullOrEmpty()) append("  ·  ").append(I18n.t("mods.detail_authors", authors))
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
                    I18n.t("mods.new_version", updateInfo.latestFile.fileName, updateInfo.source),
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
            title = { Text(I18n.t("mods.delete_title")) },
            text = { Text(I18n.t("mods.delete_confirm", m.getName() ?: "", m.getJarFile() ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteMod(m.getJarFile())
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}

/**
 * 模组详情对话框：展示 modId / version / name / description / authors / loader / jar / source / depends / conflicts。
 * description 用 verticalScroll 显示完整内容，不截断。
 */
@Composable
private fun ModDetailDialog(
    m: ModMeta,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit
) {
    val rawName = m.getName() ?: m.getJarFile() ?: I18n.t("mods.unknown")
    val displayName = if (translateEnabled) translationCache[rawName] ?: rawName else rawName
    val rawDesc = m.getDescription()
    val displayDesc = if (translateEnabled) translationCache[rawDesc] ?: rawDesc else rawDesc

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(displayName, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(I18n.t("mods.detail_modid", m.getModId() ?: "-"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("mods.detail_version", m.getVersion() ?: "-"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("mods.detail_authors", m.getAuthors() ?: "-"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("mods.detail_loader", m.getLoader() ?: "-"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("mods.detail_jar", m.getJarFile() ?: "-"),
                     style = MaterialTheme.typography.bodySmall,
                     overflow = TextOverflow.Ellipsis,
                     maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("mods.detail_source", m.getSource() ?: "-"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                // description 完整显示（在可滚动 Column 内不截断）
                if (!displayDesc.isNullOrEmpty()) {
                    Text(I18n.t("mods.detail_description"),
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(displayDesc,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Text(I18n.t("mods.detail_depends",
                     m.getDepends().joinToString(", ").ifEmpty { "-" }),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("mods.detail_conflicts",
                     m.getConflicts().joinToString(", ").ifEmpty { "-" }),
                     style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.close")) }
        }
    )
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
                if (hasErrors) I18n.t("mods.conflict_errors", result.getErrors().size)
                else I18n.t("mods.conflict_warnings", result.getWarnings().size),
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

/**
 * 导入模组对话框：FileDialog 选择 .jar 文件，确认后调用 vm.importMod(path)。
 */
@Composable
private fun ImportModDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("mods.import_title")) },
        text = {
            Column {
                Text(I18n.t("mods.import_hint"),
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(I18n.t("modpack.file_path")) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val fd = FileDialog(null as Frame?, I18n.t("mods.import_title"), FileDialog.LOAD)
                            fd.filenameFilter = FilenameFilter { _, name ->
                                name.endsWith(".jar")
                            }
                            fd.isVisible = true
                            if (fd.file != null) {
                                path = java.io.File(fd.directory, fd.file).absolutePath
                            }
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("common.browse"))
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (path.isNotEmpty()) onConfirm(path) },
                enabled = path.isNotEmpty()
            ) { Text(I18n.t("common.import")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
        }
    )
}
