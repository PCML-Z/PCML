package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.nbt.NbtTag
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NbtEditorPage(vm: LauncherViewModel) {
    val root by vm.nbtRoot.collectAsState()
    val filePath by vm.nbtFilePath.collectAsState()
    val dirty by vm.nbtDirty.collectAsState()
    val error by vm.nbtError.collectAsState()
    val revision by vm.nbtRevision.collectAsState()
    val worlds by vm.worlds.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 对话框状态
    var showAddChildDialog by remember { mutableStateOf<Pair<NbtTag.CompoundTag, Boolean>?>(null) }
    var showArrayEditor by remember { mutableStateOf<NbtTag?>(null) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var snbtExport by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // ===== 工具栏 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 打开文件
            OutlinedButton(onClick = {
                val doOpen = {
                    val fd = FileDialog(Frame(), I18n.t("nbt.open"), FileDialog.LOAD)
                    fd.setFile("*.dat;*.nbt")
                    fd.isVisible = true
                    val f = fd.file
                    val d = fd.directory
                    if (f != null && d != null) vm.openNbtFile(java.io.File(d, f).absolutePath)
                }
                if (dirty) { pendingAction = doOpen; showUnsavedDialog = true } else doOpen()
            }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.open")) }

            // 保存
            OutlinedButton(
                onClick = { vm.saveNbtFile() },
                enabled = root != null && filePath != null
            ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.save")) }

            // 另存为
            OutlinedButton(
                onClick = { showSaveAsDialog = true },
                enabled = root != null
            ) { Icon(Icons.Default.SaveAs, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.save_as")) }

            // 导出 SNBT
            OutlinedButton(
                onClick = {
                    val snbt = vm.exportNbtSnbt()
                    snbtExport = snbt
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(snbt), null)
                },
                enabled = root != null
            ) { Icon(Icons.Default.Code, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.export_snbt")) }

            Spacer(Modifier.weight(1f))

            // 全部展开/折叠（仅在有根节点时显示）
            if (root != null) {
                TextButton(onClick = {
                    // 通过 revision 触发重组，展开状态由 expandAll 控制
                    expandAllState.value = true
                    vm.updateNbtValue()
                }) { Text(I18n.t("nbt.expand_all")) }
                TextButton(onClick = {
                    expandAllState.value = false
                    vm.updateNbtValue()
                }) { Text(I18n.t("nbt.collapse_all")) }
            }

            // 关闭
            if (root != null) {
                OutlinedButton(onClick = {
                    if (dirty) { pendingAction = { vm.closeNbtFile() }; showUnsavedDialog = true }
                    else vm.closeNbtFile()
                }) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.close")) }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ===== 搜索框 =====
        if (root != null) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(I18n.t("nbt.search_hint")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) }
                    }
                },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
        }

        // ===== 文件路径 + dirty 标记 =====
        if (filePath != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    filePath!!,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (dirty) Text(" *", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
        }

        // ===== 错误显示 =====
        error?.let { e ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Text(e, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }

        // ===== 主内容区 =====
        if (root != null) {
            // 引用 revision 强制重组
            @Suppress("UNUSED_EXPRESSION") revision
            val expandAll = expandAllState.value
            Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                NbtTreeNode(
                    tag = root!!,
                    name = root!!.getName().ifEmpty { "Root" },
                    depth = 0,
                    searchQuery = searchQuery,
                    expandAll = expandAll,
                    isRoot = true,
                    vm = vm,
                    onAddChild = { parent -> showAddChildDialog = parent to true },
                    onEditArray = { array -> showArrayEditor = array }
                )
            }
        } else if (worlds.isNotEmpty()) {
            // 快速打开世界 level.dat
            Text(I18n.t("nbt.quick_open"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            worlds.forEach { w ->
                val levelDat = java.io.File(w.dir.toString(), "level.dat")
                if (levelDat.exists()) {
                    OutlinedButton(
                        onClick = { vm.openNbtFile(levelDat.absolutePath) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Public, null)
                        Spacer(Modifier.width(8.dp))
                        Text(w.name, modifier = Modifier.weight(1f))
                        Text("${levelDat.length() / 1024}KB", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            // 空状态
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text(I18n.t("nbt.empty"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    // ===== 未保存确认对话框 =====
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false; pendingAction = null },
            title = { Text(I18n.t("nbt.unsaved_confirm")) },
            text = { Text(I18n.t("nbt.unsaved_confirm_msg")) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    vm.saveNbtFile()
                    pendingAction?.invoke()
                    pendingAction = null
                }) { Text(I18n.t("nbt.save")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        pendingAction?.invoke()
                        pendingAction = null
                    }) { Text(I18n.t("nbt.discard")) }
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        pendingAction = null
                    }) { Text(I18n.t("nbt.cancel")) }
                }
            }
        )
    }

    // ===== 添加子标签对话框 =====
    showAddChildDialog?.let { (parent, _) ->
        AddChildDialog(
            parent = parent,
            onDismiss = { showAddChildDialog = null },
            onConfirm = { name, type ->
                vm.addNbtChild(parent, name, type)
                showAddChildDialog = null
            }
        )
    }

    // ===== 数组编辑器对话框 =====
    showArrayEditor?.let { array ->
        ArrayEditorDialog(
            array = array,
            onDismiss = { showArrayEditor = null },
            vm = vm
        )
    }

    // ===== 另存为对话框 =====
    if (showSaveAsDialog) {
        SaveAsDialog(
            defaultName = filePath?.let { java.io.File(it).name } ?: "export.dat",
            onDismiss = { showSaveAsDialog = false },
            onConfirm = { path ->
                vm.saveNbtFileAs(path)
                showSaveAsDialog = false
            }
        )
    }

    // ===== SNBT 导出预览 =====
    snbtExport?.let { snbt ->
        SnbtPreviewDialog(
            snbt = snbt,
            onDismiss = { snbtExport = null }
        )
    }
}

/** 全局展开/折叠状态 */
val expandAllState = mutableStateOf(false)

// ===== 递归树节点 =====

@Composable
private fun NbtTreeNode(
    tag: NbtTag,
    name: String,
    depth: Int,
    searchQuery: String,
    expandAll: Boolean,
    isRoot: Boolean,
    vm: LauncherViewModel,
    onAddChild: (NbtTag.CompoundTag) -> Unit,
    onEditArray: (NbtTag) -> Unit
) {
    // 搜索匹配判断
    val matchesSearch = searchQuery.isEmpty() || name.contains(searchQuery, ignoreCase = true)
    var expanded by remember(name, depth) { mutableStateOf(depth < 2) }
    if (expandAll) expanded = true

    val isContainer = tag is NbtTag.CompoundTag || tag is NbtTag.ListTag
    val isArray = tag is NbtTag.ByteArrayTag || tag is NbtTag.IntArrayTag || tag is NbtTag.LongArrayTag

    // 内联编辑状态
    var editing by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf("") }
    var renameMode by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(name) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 父引用（用于删除/重命名）
    val parentRef = remember(tag, name) { findParent(vm.nbtRoot.value, tag, name) }

    Column(modifier = Modifier.padding(start = (depth * 20).dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 展开/折叠按钮
            if (isContainer) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Spacer(Modifier.size(24.dp))
            }

            // 标签名（可重命名）
            if (renameMode && !isRoot) {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    modifier = Modifier.width(150.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = {
                    if (renameValue.isNotBlank() && renameValue != name) {
                        parentRef?.let { (parent, oldName) ->
                            if (parent is NbtTag.CompoundTag) {
                                vm.renameNbtChild(parent, oldName, renameValue)
                            }
                        }
                    }
                    renameMode = false
                }) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { renameMode = false; renameValue = name }) {
                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (matchesSearch) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (searchQuery.isNotEmpty() && matchesSearch) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 2.dp) else Modifier
                )
                if (!isRoot) {
                    IconButton(onClick = { renameMode = true; renameValue = name }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            // 类型标签
            AssistChip(
                onClick = {},
                label = { Text(tag.getTypeName(), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(24.dp)
            )

            Spacer(Modifier.weight(1f))

            // 值显示 / 编辑（叶节点）
            if (!isContainer && !isArray) {
                if (editing) {
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { editValue = it },
                        modifier = Modifier.width(120.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = {
                        try {
                            when (tag) {
                                is NbtTag.ByteTag -> tag.setValue(editValue.toByte())
                                is NbtTag.ShortTag -> tag.setValue(editValue.toShort())
                                is NbtTag.IntTag -> tag.setValue(editValue.toInt())
                                is NbtTag.LongTag -> tag.setValue(editValue.toLong().also {} as Long)
                                is NbtTag.FloatTag -> tag.setValue(editValue.toFloat())
                                is NbtTag.DoubleTag -> tag.setValue(editValue.toDouble())
                                is NbtTag.StringTag -> tag.setValue(editValue)
                            }
                            vm.updateNbtValue()
                        } catch (_: NumberFormatException) {}
                        editing = false
                    }) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                    IconButton(onClick = { editing = false }) {
                        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Text(
                        tag.getValueString(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp).clickable { editing = true; editValue = stripQuotes(tag) }
                    )
                    IconButton(onClick = { editing = true; editValue = stripQuotes(tag) }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                    }
                }
            } else if (isArray) {
                // 数组类型：显示摘要 + 点击编辑
                Text(
                    tag.getValueString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.clickable { onEditArray(tag) }
                )
                IconButton(onClick = { onEditArray(tag) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                }
            }

            // 删除按钮（非根节点）
            if (!isRoot) {
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                }
            }

            // Compound: 添加子标签
            if (tag is NbtTag.CompoundTag) {
                IconButton(onClick = { onAddChild(tag) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }

            // List: 添加元素
            if (tag is NbtTag.ListTag) {
                IconButton(onClick = { vm.addNbtListItem(tag) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }

        // 子节点
        if (isContainer && expanded) {
            when (tag) {
                is NbtTag.CompoundTag -> {
                    tag.getChildren().forEach { (key, child) ->
                        // 搜索过滤：子节点匹配或自身匹配时显示
                        if (searchQuery.isEmpty() || key.contains(searchQuery, ignoreCase = true) || hasMatchingDescendant(child, searchQuery)) {
                            NbtTreeNode(
                                tag = child,
                                name = key,
                                depth = depth + 1,
                                searchQuery = searchQuery,
                                expandAll = expandAll,
                                isRoot = false,
                                vm = vm,
                                onAddChild = onAddChild,
                                onEditArray = onEditArray
                            )
                        }
                    }
                }
                is NbtTag.ListTag -> {
                    tag.getItems().forEachIndexed { index, item ->
                        // 搜索过滤
                        if (searchQuery.isEmpty() || hasMatchingDescendant(item, searchQuery)) {
                            Row(
                                modifier = Modifier.padding(start = ((depth + 1) * 20).dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // List 元素的移动/删除按钮
                                IconButton(
                                    onClick = { vm.moveNbtListItem(tag, index, up = true) },
                                    modifier = Modifier.size(20.dp),
                                    enabled = index > 0
                                ) { Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(14.dp)) }
                                IconButton(
                                    onClick = { vm.moveNbtListItem(tag, index, up = false) },
                                    modifier = Modifier.size(20.dp),
                                    enabled = index < tag.size() - 1
                                ) { Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(14.dp)) }
                                Text("[$index]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.width(4.dp))
                                NbtTreeNode(
                                    tag = item,
                                    name = "[$index]",
                                    depth = depth + 1,
                                    searchQuery = searchQuery,
                                    expandAll = expandAll,
                                    isRoot = false,
                                    vm = vm,
                                    onAddChild = onAddChild,
                                    onEditArray = onEditArray
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(I18n.t("nbt.confirm_delete")) },
            text = { Text(I18n.t("nbt.confirm_delete_msg").format(name)) },
            confirmButton = {
                TextButton(onClick = {
                    parentRef?.let { (parent, oldName) ->
                        if (parent is NbtTag.CompoundTag) vm.removeNbtChild(parent, oldName)
                        else if (parent is NbtTag.ListTag) {
                            val idx = oldName.removePrefix("[").removeSuffix("]").toIntOrNull()
                            if (idx != null) vm.removeNbtListItem(parent, idx)
                        }
                    }
                    showDeleteConfirm = false
                }) { Text(I18n.t("nbt.delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(I18n.t("nbt.cancel")) }
            }
        )
    }
}

// ===== 添加子标签对话框 =====

@Composable
private fun AddChildDialog(
    parent: NbtTag.CompoundTag,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(NbtTag.TYPE_STRING) }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("nbt.add_child")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = parent.contains(it) },
                    label = { Text(I18n.t("nbt.tag_name")) },
                    isError = nameError || name.isBlank(),
                    supportingText = if (nameError) { { Text(I18n.t("nbt.name_exists")) } } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(I18n.t("nbt.tag_type"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                // 类型选择器
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    NbtTag.CREATABLE_TYPES.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(NbtTag.getTypeName(type), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && !nameError) onConfirm(name, selectedType) },
                enabled = name.isNotBlank() && !nameError
            ) { Text(I18n.t("nbt.add")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("nbt.cancel")) }
        }
    )
}

// ===== 数组编辑器对话框 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrayEditorDialog(
    array: NbtTag,
    onDismiss: () -> Unit,
    vm: LauncherViewModel
) {
    val elements: List<String> = when (array) {
        is NbtTag.ByteArrayTag -> array.getValue().map { it.toString() }
        is NbtTag.IntArrayTag -> array.getValue().map { it.toString() }
        is NbtTag.LongArrayTag -> array.getValue().map { it.toString() + "L" }
        else -> emptyList()
    }
    val elementTypeName = when (array) {
        is NbtTag.ByteArrayTag -> "Byte"
        is NbtTag.IntArrayTag -> "Int"
        is NbtTag.LongArrayTag -> "Long"
        else -> ""
    }
    var newElementValue by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf(-1) }
    var editingValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${I18n.t("nbt.edit_array")} ($elementTypeName[${elements.size}])") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 元素列表
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                ) {
                    elements.forEachIndexed { index, value ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "[$index]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.width(40.dp)
                            )
                            if (editingIndex == index) {
                                OutlinedTextField(
                                    value = editingValue,
                                    onValueChange = { editingValue = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                IconButton(onClick = {
                                    val v = if (array is NbtTag.LongArrayTag)
                                        editingValue.removeSuffix("L")
                                    else editingValue
                                    if (vm.setNbtArrayElement(array, index, v)) {
                                        editingIndex = -1
                                    }
                                }) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                                IconButton(onClick = { editingIndex = -1 }) {
                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            } else {
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    editingIndex = index
                                    editingValue = if (array is NbtTag.LongArrayTag) value.removeSuffix("L") else value
                                }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                                }
                                IconButton(onClick = { vm.removeNbtArrayElement(array, index) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                    if (elements.isEmpty()) {
                        Text(I18n.t("nbt.empty_array"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 添加新元素
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newElementValue,
                        onValueChange = { newElementValue = it },
                        label = { Text(I18n.t("nbt.add_element")) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val v = if (array is NbtTag.LongArrayTag) newElementValue.removeSuffix("L") else newElementValue
                        if (vm.addNbtArrayElement(array, v)) newElementValue = ""
                    }) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("nbt.done")) }
        }
    )
}

// ===== 另存为对话框 =====

@Composable
private fun SaveAsDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var path by remember { mutableStateOf(System.getProperty("user.home") + "/" + defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("nbt.save_as")) },
        text = {
            Column {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(I18n.t("nbt.file_path")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val fd = FileDialog(Frame(), I18n.t("nbt.save_as"), FileDialog.SAVE)
                        fd.setFile(defaultName)
                        fd.isVisible = true
                        val f = fd.file
                        val d = fd.directory
                        if (f != null && d != null) path = java.io.File(d, f).absolutePath
                    }
                ) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.browse")) }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (path.isNotBlank()) onConfirm(path) }) { Text(I18n.t("nbt.save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("nbt.cancel")) }
        }
    )
}

// ===== SNBT 预览对话框 =====

@Composable
private fun SnbtPreviewDialog(
    snbt: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("nbt.export_snbt")) },
        text = {
            Column {
                Text(I18n.t("nbt.snbt_exported"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    Text(
                        snbt,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("nbt.done")) }
        }
    )
}

// ===== 辅助函数 =====

/** 去除 StringTag 的引号以显示原始值 */
private fun stripQuotes(tag: NbtTag): String {
    return when (tag) {
        is NbtTag.StringTag -> tag.getValue()
        is NbtTag.LongTag -> tag.getValue().toString()
        is NbtTag.FloatTag -> tag.getValue().toString()
        is NbtTag.DoubleTag -> tag.getValue().toString()
        else -> tag.getValueString()
    }
}

/** 递归检查子树是否有匹配搜索的节点 */
private fun hasMatchingDescendant(tag: NbtTag, query: String): Boolean {
    if (query.isEmpty()) return true
    return when (tag) {
        is NbtTag.CompoundTag -> {
            tag.getChildren().keys.any { it.contains(query, ignoreCase = true) } ||
                    tag.getChildren().values.any { hasMatchingDescendant(it, query) }
        }
        is NbtTag.ListTag -> tag.getItems().any { hasMatchingDescendant(it, query) }
        else -> false
    }
}

/** 在 NBT 树中查找标签的父节点和键名/索引 */
private fun findParent(root: NbtTag?, target: NbtTag, name: String): Pair<NbtTag, String>? {
    if (root == null) return null
    return findParentRecursive(root, target, name)
}

private fun findParentRecursive(current: NbtTag, target: NbtTag, name: String): Pair<NbtTag, String>? {
    when (current) {
        is NbtTag.CompoundTag -> {
            for ((key, child) in current.getChildren()) {
                if (child === target) return current to key
                val found = findParentRecursive(child, target, child.getName().ifEmpty { key })
                if (found != null) return found
            }
        }
        is NbtTag.ListTag -> {
            for ((index, item) in current.getItems().withIndex()) {
                if (item === target) return current to "[$index]"
                val found = findParentRecursive(item, target, item.getName().ifEmpty { "[$index]" })
                if (found != null) return found
            }
        }
    }
    return null
}
