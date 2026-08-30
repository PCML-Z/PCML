package com.lash.pmcl.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.core.mods.ModConflictChecker
import com.lash.pmcl.core.mods.ModIconExtractor
import com.lash.pmcl.core.mods.ModMeta
import com.lash.pmcl.core.mods.ModScanner
import com.lash.pmcl.core.mods.ModUpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModsScreen(core: LauncherCore) {
    val modManager = core.modManager
    val modUpdateChecker = core.modUpdateChecker
    val modTagStore = core.modTagStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var mods by remember { mutableStateOf<List<ModMeta>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    var query by remember { mutableStateOf("") }
    var selectedLoader by remember { mutableStateOf<String?>(null) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(ModSort.NAME) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showModDropDialog by remember { mutableStateOf(false) }
    var detailMod by remember { mutableStateOf<ModMeta?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedMods by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var editingTagMod by remember { mutableStateOf<ModMeta?>(null) }
    var statusFilter by remember { mutableStateOf(ModStatusFilter.ALL) }
    var toolsExpanded by remember { mutableStateOf(false) }

    var modUpdates by remember { mutableStateOf<List<ModUpdateChecker.UpdateInfo>>(emptyList()) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0 to 0) }
    var updatingMod by remember { mutableStateOf(false) }

    var allTags by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refresh() {
        loading = true
        error = null
        Thread {
            try {
                val scanned = ModScanner.scanDirectory(modManager.modsDir)
                modTagStore.applyTags(scanned)
                mods = scanned
                allTags = modTagStore.getAllTags()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }.start()
    }

    LaunchedEffect(Unit) { refresh() }

    val updateInfoMap = remember(modUpdates) {
        modUpdates.associateBy { it.installed.modId }
    }
    val updateCount = remember(modUpdates) { modUpdates.count { it.hasUpdate } }

    val loaders = remember(mods) {
        mods.map { it.loader.ifEmpty { "unknown" } }.distinct().sorted()
    }
    val sources = remember(mods) {
        mods.map { it.source ?: "未知" }.distinct().sorted()
    }

    val processedMods = remember(
        mods, query, selectedLoader, selectedSource, selectedTag, sortBy, statusFilter, updateInfoMap
    ) {
        var list = if (query.isBlank()) mods
        else mods.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.modId.contains(query, ignoreCase = true) ||
                it.loader.contains(query, ignoreCase = true)
        }
        if (selectedSource != null) {
            list = list.filter { (it.source ?: "未知") == selectedSource }
        }
        if (selectedLoader != null) {
            list = list.filter { (it.loader.ifEmpty { "unknown" }) == selectedLoader }
        }
        if (selectedTag != null) {
            list = list.filter { selectedTag in it.tags }
        }
        list = when (statusFilter) {
            ModStatusFilter.ALL -> list
            ModStatusFilter.ENABLED -> list.filter { !it.disabled }
            ModStatusFilter.DISABLED -> list.filter { it.disabled }
            ModStatusFilter.UPDATES -> list.filter {
                val info = updateInfoMap[it.modId]
                info != null && info.hasUpdate
            }
        }
        when (sortBy) {
            ModSort.NAME -> list.sortedBy { it.name.lowercase() }
            ModSort.VERSION -> list.sortedBy { it.version.lowercase() }
            ModSort.LOADER -> list.sortedBy { it.loader.lowercase() }
            ModSort.STATUS -> list.sortedByDescending { it.disabled }
        }
    }

    val enabledCount = remember(mods) { mods.count { !it.disabled } }
    val disabledCount = remember(mods) { mods.count { it.disabled } }
    val hasFilter = query.isNotBlank() || selectedLoader != null ||
        selectedSource != null || selectedTag != null || statusFilter != ModStatusFilter.ALL

    val conflicts = remember(mods) {
        if (mods.isEmpty()) null else runCatching { ModConflictChecker.check(mods) }.getOrNull()
    }

    // ===== 操作 =====
    fun checkUpdates() {
        if (checkingUpdates || updatingMod) return
        scope.launch {
            checkingUpdates = true
            updateProgress = 0 to mods.size
            val result = withContext(Dispatchers.IO) {
                try {
                    modUpdateChecker.checkUpdates(mods, null) { arr ->
                        scope.launch { updateProgress = arr[0] to arr[1] }
                    }.awaitValue()
                } catch (_: Throwable) {
                    null
                }
            }
            modUpdates = result ?: emptyList()
            checkingUpdates = false
        }
    }

    fun updateAll() {
        if (updatingMod || checkingUpdates) return
        scope.launch {
            updatingMod = true
            updateProgress = 0 to modUpdates.count { it.hasUpdate }
            withContext(Dispatchers.IO) {
                try {
                    modUpdateChecker.updateAll(modUpdates, null, null) { arr ->
                        scope.launch { updateProgress = arr[0] to arr[1] }
                    }.awaitValue()
                } catch (_: Throwable) {
                }
            }
            updatingMod = false
            modUpdates = emptyList()
            refresh()
        }
    }

    fun updateOne(info: ModUpdateChecker.UpdateInfo) {
        if (updatingMod) return
        scope.launch {
            updatingMod = true
            updateProgress = 0 to 1
            withContext(Dispatchers.IO) {
                try {
                    modUpdateChecker.updateMod(info, null, null) { _ -> }.awaitValue()
                } catch (_: Throwable) {
                }
            }
            updatingMod = false
            modUpdates = modUpdates.filterNot { it.installed.modId == info.installed.modId }
            refresh()
        }
    }

    fun importMod(path: String) {
        Thread {
            try {
                val src = Path.of(path)
                val fileName = src.fileName?.toString() ?: ""
                if (fileName.isBlank() || !fileName.lowercase().endsWith(".jar")) {
                    error = "请输入有效的 .jar 文件路径"
                    return@Thread
                }
                Files.createDirectories(modManager.modsDir)
                val modsAbs = modManager.modsDir.toAbsolutePath().normalize()
                val target = modsAbs.resolve(fileName).normalize()
                if (!target.startsWith(modsAbs)) {
                    error = "非法路径: $fileName"
                    return@Thread
                }
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
                refresh()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }.start()
    }

    fun toggleMod(m: ModMeta) {
        Thread {
            try {
                if (m.disabled) modManager.enableMod(m.jarFile)
                else modManager.disableMod(m.jarFile)
                refresh()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }.start()
    }

    fun deleteMod(m: ModMeta) {
        Thread {
            try {
                modManager.deleteMod(m.jarFile)
                refresh()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }.start()
    }

    fun batchEnable() {
        Thread {
            for (jar in selectedMods) {
                try {
                    if (modManager.isDisabled(jar)) modManager.enableMod(jar)
                } catch (_: Exception) {
                }
            }
            refresh()
        }.start()
    }

    fun batchDisable() {
        Thread {
            for (jar in selectedMods) {
                try {
                    if (!modManager.isDisabled(jar)) modManager.disableMod(jar)
                } catch (_: Exception) {
                }
            }
            refresh()
        }.start()
    }

    fun batchDelete() {
        Thread {
            for (jar in selectedMods) {
                try {
                    modManager.deleteMod(jar)
                } catch (_: Exception) {
                }
            }
            refresh()
        }.start()
    }

    fun saveTags(jarFile: String, tags: List<String>) {
        Thread {
            modTagStore.setTags(jarFile, tags)
            allTags = modTagStore.getAllTags()
            mods = mods.map { if (it.jarFile == jarFile) it.copy(tags = tags) else it }
        }.start()
    }

    fun openModsDir() {
        Toast.makeText(context, modManager.modsDir.toAbsolutePath().toString(), Toast.LENGTH_LONG).show()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // === 顶栏 ===
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("模组管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "${mods.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("搜索模组名称 / ID / 加载器", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = toolsExpanded || hasFilter,
                    onClick = { toolsExpanded = !toolsExpanded },
                    leadingIcon = {
                        Icon(
                            if (toolsExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.FilterList,
                            null, Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            if (toolsExpanded) "收起菜单" else "工具菜单" + if (hasFilter) " ·" else "",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
                Spacer(Modifier.width(4.dp))
                if (updateCount > 0) {
                    IconButton(onClick = { updateAll() }, enabled = !updatingMod && !checkingUpdates) {
                        BadgedBox(badge = { Badge { Text("$updateCount") } }) {
                            Icon(Icons.Filled.Update, contentDescription = "全部更新($updateCount)", Modifier.size(20.dp))
                        }
                    }
                }
                IconButton(onClick = { showImportDialog = true }) {
                    Icon(Icons.Filled.Download, contentDescription = "导入模组", Modifier.size(20.dp))
                }
                IconButton(onClick = { checkUpdates() }, enabled = !checkingUpdates && !updatingMod) {
                    if (checkingUpdates) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Update, contentDescription = "检查更新", Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = { openModsDir() }) {
                    Icon(Icons.Filled.Folder, contentDescription = "打开模组目录", Modifier.size(20.dp))
                }
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", Modifier.size(20.dp))
                }
            }

            // === 折叠菜单 ===
            AnimatedVisibility(
                visible = toolsExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (updateCount > 0) {
                            Button(
                                onClick = { updateAll() },
                                enabled = !updatingMod && !checkingUpdates,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Filled.Update, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("全部更新($updateCount)")
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Button(onClick = { showImportDialog = true }) {
                            Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导入模组")
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { showModDropDialog = true }) {
                            Icon(Icons.Filled.Info, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("高级导入")
                        }
                        Spacer(Modifier.width(6.dp))
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
                                    Text(if (selectionMode) "已选 ${selectedMods.size}" else "批量操作")
                                }
                            }
                        )
                        Spacer(Modifier.width(6.dp))
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

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "模组目录: ${modManager.modsDir.toAbsolutePath()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { showImportDialog = true }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "点击此处导入 .jar 模组文件",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModStatusFilter.entries.forEach { f ->
                            FilterChip(
                                selected = statusFilter == f,
                                onClick = { statusFilter = f },
                                label = {
                                    Text(
                                        when (f) {
                                            ModStatusFilter.ALL -> "全部"
                                            ModStatusFilter.ENABLED -> "启用"
                                            ModStatusFilter.DISABLED -> "禁用"
                                            ModStatusFilter.UPDATES -> "有更新" +
                                                if (updateCount > 0) " ($updateCount)" else ""
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }

                    val showSourceFilter = sources.size > 1 ||
                        (sources.size == 1 && sources[0] != "全局")
                    if (showSourceFilter || loaders.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (showSourceFilter) {
                                FilterChip(
                                    selected = selectedSource != null,
                                    onClick = { selectedSource = if (selectedSource != null) null else sources.firstOrNull() },
                                    label = {
                                        Text("来源: ${selectedSource ?: "全部"}", style = MaterialTheme.typography.labelSmall)
                                    }
                                )
                            }
                            if (loaders.isNotEmpty()) {
                                FilterChip(
                                    selected = selectedLoader != null,
                                    onClick = { selectedLoader = if (selectedLoader != null) null else loaders.firstOrNull() },
                                    label = {
                                        Text("加载器: ${selectedLoader ?: "全部"}", style = MaterialTheme.typography.labelSmall)
                                    }
                                )
                            }
                        }
                    }

                    if (allTags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("标签筛选", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                allTags.forEach { tag ->
                                    FilterChip(
                                        selected = selectedTag == tag,
                                        onClick = { selectedTag = if (selectedTag == tag) null else tag },
                                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                                if (selectedTag != null) {
                                    TextButton(
                                        onClick = { selectedTag = null },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) { Text("清除", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
            }

            // === 批量操作行 ===
            if (selectionMode) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selectedMods.size} 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = { batchEnable() },
                        enabled = selectedMods.isNotEmpty(),
                        label = { Text("批量启用") }
                    )
                    Spacer(Modifier.width(6.dp))
                    AssistChip(
                        onClick = { batchDisable() },
                        enabled = selectedMods.isNotEmpty(),
                        label = { Text("批量禁用") }
                    )
                    Spacer(Modifier.width(6.dp))
                    AssistChip(
                        onClick = { showBatchDeleteConfirm = true },
                        enabled = selectedMods.isNotEmpty(),
                        label = { Text("批量删除") }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        selectionMode = false
                        selectedMods = emptySet()
                    }) { Text("取消") }
                }
            }

            // === 进度条 ===
            if (checkingUpdates || updatingMod) {
                Spacer(Modifier.height(6.dp))
                val (done, total) = updateProgress
                val label = if (checkingUpdates) "正在检查更新" else "正在更新"
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

            // === 冲突报告 ===
            val conflictsData = conflicts
            if (conflictsData != null && conflictsData.hasIssues()) {
                Spacer(Modifier.height(8.dp))
                ConflictCard(conflictsData)
            }

            // === 列表标题 ===
            Spacer(Modifier.height(8.dp))
            Text(
                if (hasFilter) "共 ${processedMods.size} 个（启用 $enabledCount · 禁用 $disabledCount）"
                else "启用 $enabledCount · 禁用 $disabledCount",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            // === 列表 ===
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else if (processedMods.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (mods.isEmpty()) "暂无模组，将 .jar 文件放入 mods 目录" else "无匹配结果",
                        color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(processedMods, key = { idx, m ->
                        m.jarFile.ifEmpty { (m.modId.ifEmpty { m.toString() }) + "#" + idx }
                    }) { index, m ->
                        val updateInfo = updateInfoMap[m.modId]
                        val isSelected = selectionMode && (m.jarFile in selectedMods)
                        ModRow(
                            m = m,
                            updateInfo = updateInfo,
                            updatingMod = updatingMod,
                            selectionMode = selectionMode,
                            isSelected = isSelected,
                            onToggleSelect = {
                                val key = m.jarFile
                                if (key.isNotEmpty()) {
                                    selectedMods = if (key in selectedMods) selectedMods - key
                                    else selectedMods + key
                                }
                            },
                            onShowDetail = { detailMod = m },
                            onEditTags = { editingTagMod = m },
                            onToggleEnabled = { toggleMod(m) },
                            onDelete = { deleteMod(m) },
                            onUpdate = { updateInfo?.let { updateOne(it) } }
                        )
                    }
                }
            }
        }
    }

    // 导入对话框
    if (showImportDialog) {
        ImportModDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { path ->
                showImportDialog = false
                importMod(path)
            }
        )
    }

    // 详情对话框
    detailMod?.let { mod ->
        ModDetailDialog(m = mod, onDismiss = { detailMod = null })
    }

    // 高级导入（Modrinth 元数据查询）
    if (showModDropDialog) {
        ModDropDialog(
            installer = core.modDropInstaller,
            versionId = null,
            gameVersion = null,
            onDismiss = { showModDropDialog = false },
            onInstalled = { refresh() }
        )
    }

    // 批量删除确认
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量删除") },
            text = { Text("确定删除选中的 ${selectedMods.size} 个模组吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        batchDelete()
                        selectedMods = emptySet()
                        selectionMode = false
                        showBatchDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // 标签编辑对话框
    editingTagMod?.let { mod ->
        TagEditDialog(
            modName = mod.name.ifEmpty { mod.jarFile },
            initialTags = mod.tags,
            candidateTags = allTags,
            onDismiss = { editingTagMod = null },
            onConfirm = { tags ->
                if (mod.jarFile.isNotEmpty()) saveTags(mod.jarFile, tags)
                editingTagMod = null
            }
        )
    }
}

enum class ModSort { NAME, VERSION, LOADER, STATUS }

private fun ModSort.label(): String = when (this) {
    ModSort.NAME -> "名称"
    ModSort.VERSION -> "版本"
    ModSort.LOADER -> "加载器"
    ModSort.STATUS -> "状态"
}

enum class ModStatusFilter { ALL, ENABLED, DISABLED, UPDATES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModRow(
    m: ModMeta,
    updateInfo: ModUpdateChecker.UpdateInfo?,
    updatingMod: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onShowDetail: () -> Unit,
    onEditTags: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val hasUpdate = updateInfo != null && updateInfo.hasUpdate
    val rawName = m.name.ifEmpty { m.jarFile.ifEmpty { "未知" } }
    val shape = RoundedCornerShape(12.dp)

    Card(
        onClick = { if (selectionMode) onToggleSelect() else onShowDetail() },
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (m.disabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else null
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.padding(end = 4.dp).size(24.dp)
                )
            }

            ModIcon(mod = m, disabled = m.disabled, size = 56.dp)

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rawName + if (m.disabled) "（已禁用）" else "",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (m.disabled) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "v${m.version.ifEmpty { "?" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasUpdate) {
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
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            m.loader.ifEmpty { "unknown" },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            m.source ?: "未知",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                if (m.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        m.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val meta = remember(m) {
                    buildString {
                        append(m.jarFile.ifEmpty { "?" })
                        append("  ·  ").append(m.modId.ifEmpty { "?" })
                        if (m.authors.isNotEmpty()) append("  ·  作者: ").append(m.authors)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (m.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        m.tags.forEach { tag ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    tag,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                if (hasUpdate) {
                    val latestFile = updateInfo?.latestFile
                    if (latestFile != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "新版本: ${latestFile.fileName}" +
                                (updateInfo?.source?.let { "（$it）" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasUpdate) {
                        IconButton(
                            onClick = onUpdate,
                            modifier = Modifier.size(32.dp),
                            enabled = !updatingMod
                        ) {
                            Icon(Icons.Filled.Update, contentDescription = "更新",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = onShowDetail, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = "详情",
                            modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onEditTags, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Label, contentDescription = "编辑标签",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.secondary)
                    }
                    IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(4.dp))
                ModWebSearchChips(
                    keyword = m.name.ifEmpty { m.modId },
                    compact = true
                )
            }

            if (!selectionMode) {
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(
                        checked = !m.disabled,
                        onCheckedChange = { onToggleEnabled() }
                    )
                    Text(
                        if (m.disabled) "禁用" else "启用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除模组") },
            text = { Text("确定删除「${m.name.ifEmpty { m.jarFile }}」吗？\n文件: ${m.jarFile}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
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
private fun ModIcon(mod: ModMeta, disabled: Boolean, size: Dp = 56.dp) {
    var bmp by remember(mod.jarPath, mod.iconEntry) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(mod.jarPath, mod.iconEntry) {
        bmp = null
        val jar = mod.jarPath ?: return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            try {
                val bytes = ModIconExtractor.extract(jar, mod.iconEntry)
                if (bytes != null) {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                } else null
            } catch (_: Throwable) {
                null
            }
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Filled.Extension, null, Modifier.size(26.dp),
                tint = if (disabled) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ModDetailDialog(m: ModMeta, onDismiss: () -> Unit) {
    val displayName = m.name.ifEmpty { m.jarFile.ifEmpty { "未知" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(displayName, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Mod ID: ${m.modId.ifEmpty { "-" }}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("版本: ${m.version.ifEmpty { "-" }}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("作者: ${m.authors.ifEmpty { "-" }}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("加载器: ${m.loader.ifEmpty { "-" }}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("文件: ${m.jarFile.ifEmpty { "-" }}", style = MaterialTheme.typography.bodySmall,
                    overflow = TextOverflow.Ellipsis, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text("来源: ${m.source ?: "-"}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (m.description.isNotEmpty()) {
                    Text("描述", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(m.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Text("依赖: ${m.depends.joinToString(", ").ifEmpty { "-" }}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("冲突: ${m.conflicts.joinToString(", ").ifEmpty { "-" }}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text("网上搜", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                ModWebSearchChips(keyword = m.name.ifEmpty { m.modId })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun ConflictCard(result: ModConflictChecker.Result) {
    val hasErrors = result.errors.isNotEmpty()
    val colors = if (hasErrors) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.tertiaryContainer
    Surface(color = colors, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (hasErrors) "冲突错误: ${result.errors.size} 项"
                else "冲突警告: ${result.warnings.size} 项",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            for (e in result.errors) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Circle, null, Modifier.size(6.dp).padding(top = 6.dp),
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(e, style = MaterialTheme.typography.bodySmall)
                }
            }
            for (w in result.warnings) {
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

@Composable
private fun ImportModDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入模组") },
        text = {
            Column {
                Text("输入要导入的 .jar 文件路径", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("文件路径") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (path.isNotEmpty()) onConfirm(path) },
                enabled = path.isNotEmpty()
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagEditDialog(
    modName: String,
    initialTags: List<String>,
    candidateTags: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val selected = remember { mutableStateListOf<String>().apply { addAll(initialTags) } }
    var newTag by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("为「$modName」添加或移除标签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                if (selected.isNotEmpty()) {
                    Text("已选标签", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        selected.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { selected.remove(tag) },
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, null, Modifier.size(12.dp))
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                val available = candidateTags.filter { it !in selected }
                if (available.isNotEmpty()) {
                    Text("候选标签", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        available.forEach { tag ->
                            AssistChip(
                                onClick = { if (tag !in selected) selected.add(tag) },
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Text("新建标签", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入标签名") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val t = newTag.trim()
                            if (t.isNotEmpty() && t !in selected) {
                                selected.add(t)
                                newTag = ""
                            }
                        },
                        enabled = newTag.isNotBlank()
                    ) { Text("添加") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toList()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private enum class ModWebSearchSite(val label: String) {
    MCMOD("MC百科"),
    MC_CHINA("MC中国站"),
    MCBBS("MCBBS");

    fun url(keyword: String): String {
        val q = keyword.trim()
        val encoded = if (q.isEmpty()) "" else java.net.URLEncoder.encode(q, "UTF-8")
        return when (this) {
            MCMOD ->
                if (encoded.isEmpty()) "https://www.mcmod.cn/"
                else "https://search.mcmod.cn/s?key=$encoded"
            MC_CHINA ->
                if (encoded.isEmpty()) "https://www.minecraft.net/zh-hans"
                else "https://www.minecraft.net/zh-hans/search?term=$encoded"
            MCBBS ->
                if (encoded.isEmpty()) "https://www.mcbbs.co/"
                else "https://www.mcbbs.co/search.php?mod=forum&searchsubmit=yes&srchtxt=$encoded"
        }
    }
}

@Composable
private fun ModWebSearchChips(keyword: String, compact: Boolean = false) {
    val context = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModWebSearchSite.entries.forEach { site ->
            AssistChip(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(site.url(keyword)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {
                        Toast.makeText(context, "打不开浏览器", Toast.LENGTH_SHORT).show()
                    }
                },
                label = { Text(site.label, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (compact) null else {
                    {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                }
            )
        }
    }
}

// 将 CompletableFuture 转为可挂起的结果（异常返回 null）
private suspend fun <T> CompletableFuture<T>.awaitValue(): T? = withContext(Dispatchers.IO) {
    try {
        get()
    } catch (_: Throwable) {
        null
    }
}
