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
import com.pmcl.ui.viewmodel.addNbtArrayElement
import com.pmcl.ui.viewmodel.addNbtChild
import com.pmcl.ui.viewmodel.addNbtListItem
import com.pmcl.ui.viewmodel.closeNbtFile
import com.pmcl.ui.viewmodel.convertNbtTag
import com.pmcl.ui.viewmodel.copyNbtNode
import com.pmcl.ui.viewmodel.cutNbtNode
import com.pmcl.ui.viewmodel.duplicateNbtChild
import com.pmcl.ui.viewmodel.exportNbtSnbt
import com.pmcl.ui.viewmodel.moveNbtListItem
import com.pmcl.ui.viewmodel.openNbtFile
import com.pmcl.ui.viewmodel.pasteNbtNode
import com.pmcl.ui.viewmodel.redoNbt
import com.pmcl.ui.viewmodel.removeNbtArrayElement
import com.pmcl.ui.viewmodel.removeNbtChild
import com.pmcl.ui.viewmodel.removeNbtListItem
import com.pmcl.ui.viewmodel.renameNbtChild
import com.pmcl.ui.viewmodel.saveNbtFile
import com.pmcl.ui.viewmodel.saveNbtFileAs
import com.pmcl.ui.viewmodel.setNbtArrayElement
import com.pmcl.ui.viewmodel.setNbtLeafValue
import com.pmcl.ui.viewmodel.undoNbt
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private data class ConvertTarget(
    val tag: NbtTag,
    val parent: NbtTag?,
    val key: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NbtEditorPage(vm: LauncherViewModel) {
    val root by vm.nbtRoot.collectAsState()
    val filePath by vm.nbtFilePath.collectAsState()
    val dirty by vm.nbtDirty.collectAsState()
    val error by vm.nbtError.collectAsState()
    val revision by vm.nbtRevision.collectAsState()
    val worlds by vm.worlds.collectAsState()
    val canUndo by vm.nbtCanUndo.collectAsState()
    val canRedo by vm.nbtCanRedo.collectAsState()
    val hasClipboard by vm.nbtHasClipboard.collectAsState()
    val recentFiles by vm.recentNbtFiles.collectAsState()
    val gzipped by vm.nbtGzipped.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var expandAll by remember { mutableStateOf<Boolean?>(null) }

    var showAddChildDialog by remember { mutableStateOf<Pair<NbtTag.CompoundTag, Boolean>?>(null) }
    var showArrayEditor by remember { mutableStateOf<NbtTag?>(null) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var snbtExport by remember { mutableStateOf<String?>(null) }
    var convertTarget by remember { mutableStateOf<ConvertTarget?>(null) }

    val scrollState = rememberScrollState()
    // 强制树在修订后重组
    @Suppress("UNUSED_VARIABLE")
    val _rev = revision

    fun requestOpen(path: String? = null) {
        val doOpen = {
            if (path != null) {
                vm.openNbtFile(path)
            } else {
                val fd = FileDialog(Frame(), I18n.t("nbt.open"), FileDialog.LOAD)
                fd.setFile("*.dat;*.nbt")
                fd.isVisible = true
                val f = fd.file
                val d = fd.directory
                if (f != null && d != null) vm.openNbtFile(java.io.File(d, f).absolutePath)
            }
        }
        if (dirty) {
            pendingAction = doOpen
            showUnsavedDialog = true
        } else {
            doOpen()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { requestOpen() }) {
                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.open"))
            }
            OutlinedButton(
                onClick = { vm.saveNbtFile() },
                enabled = root != null && filePath != null
            ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.save")) }
            OutlinedButton(
                onClick = { showSaveAsDialog = true },
                enabled = root != null
            ) { Icon(Icons.Default.SaveAs, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.save_as")) }
            OutlinedButton(
                onClick = {
                    val snbt = vm.exportNbtSnbt()
                    snbtExport = snbt
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(snbt), null)
                },
                enabled = root != null
            ) { Icon(Icons.Default.Code, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.export_snbt")) }

            IconButton(onClick = { vm.undoNbt() }, enabled = canUndo) {
                Icon(Icons.Default.Undo, I18n.t("nbt.undo"))
            }
            IconButton(onClick = { vm.redoNbt() }, enabled = canRedo) {
                Icon(Icons.Default.Redo, I18n.t("nbt.redo"))
            }

            Spacer(Modifier.weight(1f))

            if (root != null) {
                TextButton(onClick = { expandAll = true }) { Text(I18n.t("nbt.expand_all")) }
                TextButton(onClick = { expandAll = false }) { Text(I18n.t("nbt.collapse_all")) }
                OutlinedButton(onClick = {
                    if (dirty) {
                        pendingAction = { vm.closeNbtFile() }
                        showUnsavedDialog = true
                    } else vm.closeNbtFile()
                }) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(4.dp)); Text(I18n.t("nbt.close")) }
            }
        }

        Spacer(Modifier.height(8.dp))

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
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (gzipped) I18n.t("nbt.gzip") else I18n.t("nbt.raw"),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.height(24.dp)
                )
                if (dirty) Text(" *", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
        }

        error?.let { e ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Text(e, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (root != null) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                NbtTreeNode(
                    tag = root!!,
                    name = root!!.getName().ifEmpty { "Root" },
                    depth = 0,
                    searchQuery = searchQuery,
                    expandAll = expandAll,
                    isRoot = true,
                    vm = vm,
                    hasClipboard = hasClipboard,
                    onAddChild = { parent -> showAddChildDialog = parent to true },
                    onEditArray = { array -> showArrayEditor = array },
                    onConvert = { tag, parent, key -> convertTarget = ConvertTarget(tag, parent, key) }
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (recentFiles.isNotEmpty()) {
                    Text(I18n.t("nbt.recent"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    recentFiles.forEach { path ->
                        val f = java.io.File(path)
                        if (f.exists()) {
                            OutlinedButton(
                                onClick = { requestOpen(path) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.History, null)
                                Spacer(Modifier.width(8.dp))
                                Text(f.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(f.parent ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (worlds.isNotEmpty()) {
                    Text(I18n.t("nbt.quick_open"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    worlds.forEach { w ->
                        val levelDat = java.io.File(w.dir.toString(), "level.dat")
                        if (levelDat.exists()) {
                            OutlinedButton(
                                onClick = { requestOpen(levelDat.absolutePath) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Public, null)
                                Spacer(Modifier.width(8.dp))
                                Text(w.name, modifier = Modifier.weight(1f))
                                Text("${levelDat.length() / 1024}KB", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else if (recentFiles.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(16.dp))
                            Text(I18n.t("nbt.empty"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Text(I18n.t("nbt.empty_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }

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

    showArrayEditor?.let { array ->
        ArrayEditorDialog(array = array, onDismiss = { showArrayEditor = null }, vm = vm)
    }

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

    snbtExport?.let { snbt ->
        SnbtPreviewDialog(snbt = snbt, onDismiss = { snbtExport = null })
    }

    convertTarget?.let { target ->
        ConvertTypeDialog(
            tag = target.tag,
            onDismiss = { convertTarget = null },
            onConfirm = { type ->
                if (!vm.convertNbtTag(target.parent, target.key, target.tag, type)) {
                    // 失败时保持对话框，错误由 vm 状态/静默处理；用 snackbar 替代：写 status
                }
                convertTarget = null
            }
        )
    }
}

@Composable
private fun NbtTreeNode(
    tag: NbtTag,
    name: String,
    depth: Int,
    searchQuery: String,
    expandAll: Boolean?,
    isRoot: Boolean,
    vm: LauncherViewModel,
    hasClipboard: Boolean,
    onAddChild: (NbtTag.CompoundTag) -> Unit,
    onEditArray: (NbtTag) -> Unit,
    onConvert: (NbtTag, NbtTag?, String?) -> Unit,
    parent: NbtTag? = null,
    parentKey: String? = null
) {
    val valueMatch = searchQuery.isNotEmpty() && tagMatchesValue(tag, searchQuery)
    val matchesSearch = searchQuery.isEmpty() ||
            name.contains(searchQuery, ignoreCase = true) ||
            valueMatch
    var expanded by remember(name, depth) { mutableStateOf(depth < 2) }
    LaunchedEffect(expandAll) {
        if (expandAll != null) expanded = expandAll
    }

    val isContainer = tag is NbtTag.CompoundTag || tag is NbtTag.ListTag
    val isArray = tag is NbtTag.ByteArrayTag || tag is NbtTag.IntArrayTag || tag is NbtTag.LongArrayTag

    var editing by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf("") }
    var renameMode by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(name) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(start = (depth * 20).dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isContainer) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Spacer(Modifier.size(24.dp))
            }

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
                        if (parent is NbtTag.CompoundTag && parentKey != null) {
                            vm.renameNbtChild(parent, parentKey, renameValue)
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
                    modifier = if (searchQuery.isNotEmpty() && matchesSearch) {
                        Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 2.dp)
                    } else Modifier
                )
                if (!isRoot) {
                    IconButton(onClick = { renameMode = true; renameValue = name }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            AssistChip(
                onClick = { onConvert(tag, parent, parentKey) },
                label = { Text(tag.getTypeName(), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(24.dp)
            )

            Spacer(Modifier.weight(1f))

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
                        vm.setNbtLeafValue(tag, editValue)
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

            if (!isRoot) {
                IconButton(onClick = { vm.copyNbtNode(name, tag) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.ContentCopy, I18n.t("nbt.copy"), modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = { vm.cutNbtNode(parent, parentKey, tag) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.ContentCut, I18n.t("nbt.cut"), modifier = Modifier.size(14.dp))
                }
                if (parent is NbtTag.CompoundTag && parentKey != null) {
                    IconButton(onClick = { vm.duplicateNbtChild(parent, parentKey) }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.ControlPointDuplicate, I18n.t("nbt.duplicate"), modifier = Modifier.size(14.dp))
                    }
                }
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                }
            }

            if (tag is NbtTag.CompoundTag) {
                IconButton(
                    onClick = { vm.pasteNbtNode(tag) },
                    enabled = hasClipboard,
                    modifier = Modifier.size(20.dp)
                ) { Icon(Icons.Default.ContentPaste, I18n.t("nbt.paste"), modifier = Modifier.size(14.dp)) }
                IconButton(onClick = { onAddChild(tag) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }

            if (tag is NbtTag.ListTag) {
                IconButton(
                    onClick = { vm.pasteNbtNode(tag) },
                    enabled = hasClipboard,
                    modifier = Modifier.size(20.dp)
                ) { Icon(Icons.Default.ContentPaste, I18n.t("nbt.paste"), modifier = Modifier.size(14.dp)) }
                IconButton(onClick = { vm.addNbtListItem(tag) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (isContainer && expanded) {
            when (tag) {
                is NbtTag.CompoundTag -> {
                    tag.getChildren().forEach { (key, child) ->
                        if (searchQuery.isEmpty() ||
                            key.contains(searchQuery, ignoreCase = true) ||
                            tagMatchesValue(child, searchQuery) ||
                            hasMatchingDescendant(child, searchQuery)
                        ) {
                            NbtTreeNode(
                                tag = child,
                                name = key,
                                depth = depth + 1,
                                searchQuery = searchQuery,
                                expandAll = expandAll,
                                isRoot = false,
                                vm = vm,
                                hasClipboard = hasClipboard,
                                onAddChild = onAddChild,
                                onEditArray = onEditArray,
                                onConvert = onConvert,
                                parent = tag,
                                parentKey = key
                            )
                        }
                    }
                }
                is NbtTag.ListTag -> {
                    tag.getItems().forEachIndexed { index, item ->
                        if (searchQuery.isEmpty() ||
                            tagMatchesValue(item, searchQuery) ||
                            hasMatchingDescendant(item, searchQuery)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = ((depth + 1) * 20).dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
                                    hasClipboard = hasClipboard,
                                    onAddChild = onAddChild,
                                    onEditArray = onEditArray,
                                    onConvert = onConvert,
                                    parent = tag,
                                    parentKey = "[$index]"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(I18n.t("nbt.confirm_delete")) },
            text = { Text(I18n.t("nbt.confirm_delete_msg").format(name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (parent is NbtTag.CompoundTag && parentKey != null) {
                        vm.removeNbtChild(parent, parentKey)
                    } else if (parent is NbtTag.ListTag) {
                        val idx = parentKey?.removePrefix("[")?.removeSuffix("]")?.toIntOrNull()
                        if (idx != null) vm.removeNbtListItem(parent, idx)
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

@Composable
private fun ConvertTypeDialog(
    tag: NbtTag,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val candidates = convertibleTypes(tag)
    var selected by remember { mutableStateOf(candidates.firstOrNull() ?: tag.getType()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("nbt.convert_type")) },
        text = {
            if (candidates.isEmpty()) {
                Text(I18n.t("nbt.convert_fail"))
            } else {
                Column {
                    Text("${tag.getTypeName()} →", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        candidates.forEach { type ->
                            FilterChip(
                                selected = selected == type,
                                onClick = { selected = type },
                                label = { Text(NbtTag.getTypeName(type), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = candidates.isNotEmpty()
            ) { Text(I18n.t("nbt.done")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("nbt.cancel")) }
        }
    )
}

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
                    supportingText = if (nameError) {{ Text(I18n.t("nbt.name_exists")) }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(I18n.t("nbt.tag_type"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrayEditorDialog(
    array: NbtTag,
    onDismiss: () -> Unit,
    vm: LauncherViewModel
) {
    val revision by vm.nbtRevision.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val _rev = revision
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
                                    val v = if (array is NbtTag.LongArrayTag) editingValue.removeSuffix("L") else editingValue
                                    if (vm.setNbtArrayElement(array, index, v)) editingIndex = -1
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

@Composable
private fun SaveAsDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var path by remember { mutableStateOf(java.nio.file.Paths.get(System.getProperty("user.home"), defaultName).toString()) }

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

private fun stripQuotes(tag: NbtTag): String {
    return when (tag) {
        is NbtTag.StringTag -> tag.getValue()
        is NbtTag.LongTag -> tag.getValue().toString()
        is NbtTag.FloatTag -> tag.getValue().toString()
        is NbtTag.DoubleTag -> tag.getValue().toString()
        else -> tag.getValueString()
    }
}

private fun tagMatchesValue(tag: NbtTag, query: String): Boolean {
    if (query.isEmpty()) return false
    return when (tag) {
        is NbtTag.CompoundTag, is NbtTag.ListTag -> false
        else -> tag.getValueString().contains(query, ignoreCase = true) ||
                stripQuotes(tag).contains(query, ignoreCase = true)
    }
}

private fun hasMatchingDescendant(tag: NbtTag, query: String): Boolean {
    if (query.isEmpty()) return true
    return when (tag) {
        is NbtTag.CompoundTag -> {
            tag.getChildren().any { (key, child) ->
                key.contains(query, ignoreCase = true) ||
                        tagMatchesValue(child, query) ||
                        hasMatchingDescendant(child, query)
            }
        }
        is NbtTag.ListTag -> tag.getItems().any {
            tagMatchesValue(it, query) || hasMatchingDescendant(it, query)
        }
        else -> tagMatchesValue(tag, query)
    }
}

/** 可转换的目标类型（不含自身；Compound/List 不可转） */
private fun convertibleTypes(tag: NbtTag): List<Int> {
    return when (tag) {
        is NbtTag.ByteTag, is NbtTag.ShortTag, is NbtTag.IntTag,
        is NbtTag.LongTag, is NbtTag.FloatTag, is NbtTag.DoubleTag -> listOf(
            NbtTag.TYPE_BYTE, NbtTag.TYPE_SHORT, NbtTag.TYPE_INT,
            NbtTag.TYPE_LONG, NbtTag.TYPE_FLOAT, NbtTag.TYPE_DOUBLE, NbtTag.TYPE_STRING
        ).filter { it != tag.getType() }
        is NbtTag.StringTag -> listOf(
            NbtTag.TYPE_BYTE, NbtTag.TYPE_SHORT, NbtTag.TYPE_INT,
            NbtTag.TYPE_LONG, NbtTag.TYPE_FLOAT, NbtTag.TYPE_DOUBLE
        )
        is NbtTag.ByteArrayTag -> listOf(NbtTag.TYPE_INT_ARRAY)
        is NbtTag.IntArrayTag -> listOf(NbtTag.TYPE_BYTE_ARRAY, NbtTag.TYPE_LONG_ARRAY)
        is NbtTag.LongArrayTag -> listOf(NbtTag.TYPE_INT_ARRAY)
        else -> emptyList()
    }
}
