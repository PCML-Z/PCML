package com.lash.pmcl.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ControlPointDuplicate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lash.pmcl.core.gamecontent.WorldManager
import com.lash.pmcl.core.nbt.NbtReader
import com.lash.pmcl.core.nbt.NbtTag
import com.lash.pmcl.core.nbt.NbtWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * NBT 编辑器界面（Android 版）— 对齐桌面版 NbtEditorPage。
 *
 * 与桌面版差异：
 * - AWT FileDialog → 文本输入框输入路径
 * - AWT 剪贴板 → LocalClipboardManager（仅用于 SNBT 导出复制）
 * - 撤销/重做省略（复杂度过高）
 * - 状态直接由 [NbtEditorState] 持有（无 ViewModel），文件 IO 走 Thread 异步
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NbtEditorScreen(worldManager: WorldManager) {
    val state = remember { NbtEditorState() }
    val clipboardManager = LocalClipboardManager.current

    var searchQuery by remember { mutableStateOf("") }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var expandAll by remember { mutableStateOf<Boolean?>(null) }

    var showOpenDialog by remember { mutableStateOf(false) }
    var showAddChildDialog by remember { mutableStateOf<Pair<NbtTag.CompoundTag, Boolean>?>(null) }
    var showArrayEditor by remember { mutableStateOf<NbtTag?>(null) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var snbtExport by remember { mutableStateOf<String?>(null) }
    var convertTarget by remember { mutableStateOf<ConvertTarget?>(null) }

    val root = state.root
    val filePath = state.filePath
    val dirty = state.dirty
    val error = state.error
    val gzipped = state.gzipped
    val revision = state.revision

    // 首次进入加载世界列表（用于空状态快捷打开 level.dat）
    LaunchedEffect(Unit) { state.loadWorlds(worldManager) }

    fun requestOpen(path: String? = null) {
        val doOpen = {
            if (path != null) state.openFile(path) else showOpenDialog = true
        }
        if (dirty) {
            pendingAction = doOpen
            showUnsavedDialog = true
        } else {
            doOpen()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("NBT 编辑器") }) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(8.dp)) {
            // ===== 顶部工具栏 =====
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { requestOpen() }) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(4.dp))
                    Text("打开")
                }
                OutlinedButton(
                    onClick = { state.saveFile() },
                    enabled = root != null && filePath != null
                ) {
                    Icon(Icons.Default.Save, null)
                    Spacer(Modifier.width(4.dp))
                    Text("保存")
                }
                OutlinedButton(
                    onClick = { showSaveAsDialog = true },
                    enabled = root != null
                ) {
                    Icon(Icons.Default.SaveAs, null)
                    Spacer(Modifier.width(4.dp))
                    Text("另存为")
                }
                OutlinedButton(
                    onClick = {
                        val snbt = state.exportSnbt()
                        snbtExport = snbt
                        clipboardManager.setText(AnnotatedString(snbt))
                    },
                    enabled = root != null
                ) {
                    Icon(Icons.Default.Code, null)
                    Spacer(Modifier.width(4.dp))
                    Text("导出SNBT")
                }

                Spacer(Modifier.width(8.dp))

                if (root != null) {
                    TextButton(onClick = { expandAll = true }) { Text("全部展开") }
                    TextButton(onClick = { expandAll = false }) { Text("全部折叠") }
                    OutlinedButton(onClick = {
                        if (dirty) {
                            pendingAction = { state.closeFile() }
                            showUnsavedDialog = true
                        } else state.closeFile()
                    }) {
                        Icon(Icons.Default.Close, null)
                        Spacer(Modifier.width(4.dp))
                        Text("关闭")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ===== 搜索框 =====
            if (root != null) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索节点名或值...") },
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

            // ===== 文件路径栏 =====
            if (filePath != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Description, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        filePath,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (gzipped) "Gzip" else "Raw",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                    if (dirty) {
                        Text(" *", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // ===== 错误显示区 =====
            error?.let { e ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        e,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // ===== 主体：树 / 空状态 =====
            if (root != null) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NbtTreeNode(
                        tag = root,
                        name = root.name.ifEmpty { "Root" },
                        depth = 0,
                        searchQuery = searchQuery,
                        expandAll = expandAll,
                        isRoot = true,
                        state = state,
                        revision = revision,
                        onAddChild = { parent -> showAddChildDialog = parent to true },
                        onEditArray = { array -> showArrayEditor = array },
                        onConvert = { tag, parent, key -> convertTarget = ConvertTarget(tag, parent, key) }
                    )
                }
            } else {
                EmptyState(
                    state = state,
                    recentFiles = state.recentFiles,
                    worlds = state.worlds,
                    worldsLoading = state.worldsLoading,
                    onOpenPath = { requestOpen(it) }
                )
            }
        }
    }

    // ===== 弹窗 =====
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false; pendingAction = null },
            title = { Text("未保存确认") },
            text = { Text("当前文件未保存，是否保存？") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    state.saveFile()
                    pendingAction?.invoke()
                    pendingAction = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        pendingAction?.invoke()
                        pendingAction = null
                    }) { Text("放弃") }
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        pendingAction = null
                    }) { Text("取消") }
                }
            }
        )
    }

    if (showOpenDialog) {
        OpenPathDialog(
            onDismiss = { showOpenDialog = false },
            onConfirm = { path ->
                showOpenDialog = false
                state.openFile(path)
            }
        )
    }

    showAddChildDialog?.let { (parent, _) ->
        AddChildDialog(
            parent = parent,
            onDismiss = { showAddChildDialog = null },
            onConfirm = { name, type ->
                state.addChild(parent, name, type)
                showAddChildDialog = null
            }
        )
    }

    showArrayEditor?.let { array ->
        ArrayEditorDialog(array = array, onDismiss = { showArrayEditor = null }, state = state)
    }

    if (showSaveAsDialog) {
        SaveAsDialog(
            defaultName = filePath?.let { java.io.File(it).name } ?: "export.dat",
            onDismiss = { showSaveAsDialog = false },
            onConfirm = { path ->
                state.saveFileAs(path)
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
                state.convertTag(target.parent, target.key, target.tag, type)
                convertTarget = null
            }
        )
    }
}

/**
 * NBT 编辑器状态持有者。文件 IO 走 [Thread] 异步，UI 状态由 Compose snapshot 驱动重组。
 */
private class NbtEditorState {
    var root by mutableStateOf<NbtTag?>(null)
    var filePath by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var gzipped by mutableStateOf(true)
    var revision by mutableStateOf(0)
    var worlds by mutableStateOf<List<WorldManager.WorldInfo>>(emptyList())
    var worldsLoading by mutableStateOf(true)
    var clipboard by mutableStateOf<Pair<String, NbtTag>?>(null)
    val recentFiles = mutableStateListOf<String>()

    val hasClipboard: Boolean get() = clipboard != null

    private fun markDirty() {
        dirty = true
        revision++
    }

    fun openFile(path: String) {
        Thread {
            try {
                val result = NbtReader.readWithMeta(Paths.get(path))
                root = result.root
                gzipped = result.gzipped
                filePath = path
                dirty = false
                error = null
                revision++
                if (path !in recentFiles) {
                    recentFiles.add(0, path)
                    if (recentFiles.size > 20) recentFiles.removeAt(recentFiles.size - 1)
                }
            } catch (e: Throwable) {
                error = "读取 NBT 失败: ${e.message}"
            }
        }.start()
    }

    fun saveFile() {
        val r = root ?: return
        val p = filePath ?: return
        val gz = gzipped
        val snapshot = r.copy()
        Thread {
            try {
                val file = Paths.get(p)
                if (Files.exists(file)) {
                    val bak = file.resolveSibling(file.fileName.toString() + ".bak")
                    Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING)
                }
                NbtWriter.write(snapshot, file, gz)
                dirty = false
                error = null
            } catch (e: Throwable) {
                error = "保存 NBT 失败: ${e.message}"
            }
        }.start()
    }

    fun saveFileAs(targetPath: String) {
        val r = root ?: return
        val gz = gzipped
        val snapshot = r.copy()
        Thread {
            try {
                NbtWriter.write(snapshot, Paths.get(targetPath), gz)
                filePath = targetPath
                dirty = false
                error = null
                if (targetPath !in recentFiles) {
                    recentFiles.add(0, targetPath)
                    if (recentFiles.size > 20) recentFiles.removeAt(recentFiles.size - 1)
                }
            } catch (e: Throwable) {
                error = "保存 NBT 失败: ${e.message}"
            }
        }.start()
    }

    fun closeFile() {
        root = null
        filePath = null
        dirty = false
        error = null
        gzipped = true
        revision++
    }

    fun exportSnbt(): String = root?.toSnbt() ?: ""

    // ===== Compound 操作 =====
    fun addChild(parent: NbtTag.CompoundTag, name: String, type: Int) {
        if (parent.contains(name)) return
        parent.put(name, NbtTag.createDefault(type))
        markDirty()
    }

    fun removeChild(parent: NbtTag.CompoundTag, name: String) {
        if (!parent.contains(name)) return
        parent.remove(name)
        markDirty()
    }

    fun renameChild(parent: NbtTag.CompoundTag, oldName: String, newName: String) {
        if (oldName == newName || parent.contains(newName)) return
        val tag = parent.get(oldName) ?: return
        parent.remove(oldName)
        parent.put(newName, tag)
        markDirty()
    }

    fun duplicateChild(parent: NbtTag.CompoundTag, key: String) {
        val tag = parent.get(key) ?: return
        var name = "${key}_copy"
        var i = 1
        while (parent.contains(name)) {
            name = "${key}_copy$i"
            i++
        }
        parent.put(name, tag.copy())
        markDirty()
    }

    // ===== List 操作 =====
    fun addListItem(list: NbtTag.ListTag) {
        val type = if (list.listType == NbtTag.TYPE_END) NbtTag.TYPE_COMPOUND else list.listType
        list.add(NbtTag.createDefault(type))
        markDirty()
    }

    fun removeListItem(list: NbtTag.ListTag, index: Int) {
        if (index < 0 || index >= list.size) return
        list.remove(index)
        markDirty()
    }

    fun moveListItem(list: NbtTag.ListTag, index: Int, up: Boolean) {
        val target = if (up) index - 1 else index + 1
        if (target < 0 || target >= list.size) return
        val item = list.items[index]
        list.remove(index)
        list.add(target, item)
        markDirty()
    }

    // ===== 叶节点值编辑 =====
    fun setLeafValue(tag: NbtTag, text: String): Boolean {
        return try {
            when (tag) {
                is NbtTag.ByteTag -> tag.value = text.toByte()
                is NbtTag.ShortTag -> tag.value = text.toShort()
                is NbtTag.IntTag -> tag.value = text.toInt()
                is NbtTag.LongTag -> tag.value = text.toLong()
                is NbtTag.FloatTag -> tag.value = text.toFloat()
                is NbtTag.DoubleTag -> tag.value = text.toDouble()
                is NbtTag.StringTag -> tag.value = text
                else -> return false
            }
            markDirty()
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    // ===== 类型转换 =====
    fun convertTag(parent: NbtTag?, key: String?, tag: NbtTag, targetType: Int): Boolean {
        if (tag.type == targetType) return true
        val converted = NbtTag.convert(tag, targetType) ?: return false
        when {
            parent is NbtTag.CompoundTag && key != null -> parent.put(key, converted)
            parent is NbtTag.ListTag && key != null -> {
                val idx = key.removePrefix("[").removeSuffix("]").toIntOrNull() ?: return false
                if (idx < 0 || idx >= parent.size) return false
                // List 要求同类型：listType 不匹配则拒绝
                if (parent.listType != NbtTag.TYPE_END && parent.listType != targetType) return false
                parent.remove(idx)
                parent.add(idx, converted)
            }
            root === tag -> {
                converted.name = tag.name
                root = converted
            }
            else -> return false
        }
        markDirty()
        return true
    }

    // ===== 剪贴板（应用内） =====
    fun copyNode(name: String, tag: NbtTag) {
        clipboard = name to tag.copy()
    }

    fun cutNode(parent: NbtTag?, key: String?, tag: NbtTag) {
        if (parent == null || key == null) return
        val clipName = if (parent is NbtTag.CompoundTag) key else "item"
        clipboard = clipName to tag.copy()
        when (parent) {
            is NbtTag.CompoundTag -> removeChild(parent, key)
            is NbtTag.ListTag -> {
                val idx = key.removePrefix("[").removeSuffix("]").toIntOrNull() ?: return
                removeListItem(parent, idx)
            }
            else -> { }
        }
    }

    fun pasteNode(parent: NbtTag) {
        val clip = clipboard ?: return
        when (parent) {
            is NbtTag.CompoundTag -> {
                var name = clip.first.ifBlank { "tag" }
                var i = 1
                while (parent.contains(name)) {
                    name = "${clip.first}_$i"
                    i++
                }
                parent.put(name, clip.second.copy())
                markDirty()
            }
            is NbtTag.ListTag -> {
                val item = clip.second.copy()
                if (parent.listType != NbtTag.TYPE_END && parent.listType != item.type) {
                    error = "粘贴失败：List 类型不匹配"
                    return
                }
                parent.add(item)
                markDirty()
            }
            else -> { }
        }
    }

    // ===== 数组编辑 =====
    fun setArrayElement(array: NbtTag, index: Int, value: String): Boolean {
        return try {
            when (array) {
                is NbtTag.ByteArrayTag -> {
                    val arr = array.value
                    if (index < 0 || index >= arr.size) return false
                    arr[index] = value.toByte()
                }
                is NbtTag.IntArrayTag -> {
                    val arr = array.value
                    if (index < 0 || index >= arr.size) return false
                    arr[index] = value.toInt()
                }
                is NbtTag.LongArrayTag -> {
                    val arr = array.value
                    if (index < 0 || index >= arr.size) return false
                    arr[index] = value.toLong()
                }
                else -> return false
            }
            markDirty()
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    fun addArrayElement(array: NbtTag, value: String): Boolean {
        return try {
            when (array) {
                is NbtTag.ByteArrayTag -> {
                    val old = array.value
                    val newArr = java.util.Arrays.copyOf(old, old.size + 1)
                    newArr[old.size] = value.toByte()
                    array.value = newArr
                }
                is NbtTag.IntArrayTag -> {
                    val old = array.value
                    val newArr = java.util.Arrays.copyOf(old, old.size + 1)
                    newArr[old.size] = value.toInt()
                    array.value = newArr
                }
                is NbtTag.LongArrayTag -> {
                    val old = array.value
                    val newArr = java.util.Arrays.copyOf(old, old.size + 1)
                    newArr[old.size] = value.toLong()
                    array.value = newArr
                }
                else -> return false
            }
            markDirty()
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    fun removeArrayElement(array: NbtTag, index: Int) {
        when (array) {
            is NbtTag.ByteArrayTag -> {
                val old = array.value
                if (index < 0 || index >= old.size) return
                val newArr = java.util.Arrays.copyOf(old, old.size - 1)
                var j = 0
                for (i in old.indices) if (i != index) newArr[j++] = old[i]
                array.value = newArr
                markDirty()
            }
            is NbtTag.IntArrayTag -> {
                val old = array.value
                if (index < 0 || index >= old.size) return
                val newArr = java.util.Arrays.copyOf(old, old.size - 1)
                var j = 0
                for (i in old.indices) if (i != index) newArr[j++] = old[i]
                array.value = newArr
                markDirty()
            }
            is NbtTag.LongArrayTag -> {
                val old = array.value
                if (index < 0 || index >= old.size) return
                val newArr = java.util.Arrays.copyOf(old, old.size - 1)
                var j = 0
                for (i in old.indices) if (i != index) newArr[j++] = old[i]
                array.value = newArr
                markDirty()
            }
            else -> { }
        }
    }

    fun loadWorlds(worldManager: WorldManager) {
        worldsLoading = true
        Thread {
            try {
                worlds = worldManager.listWorlds()
            } catch (_: Throwable) {
                worlds = emptyList()
            } finally {
                worldsLoading = false
            }
        }.start()
    }
}

private data class ConvertTarget(
    val tag: NbtTag,
    val parent: NbtTag?,
    val key: String?
)

@Composable
private fun EmptyState(
    state: NbtEditorState,
    recentFiles: List<String>,
    worlds: List<WorldManager.WorldInfo>,
    worldsLoading: Boolean,
    onOpenPath: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (recentFiles.isNotEmpty()) {
            Text("最近文件", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            recentFiles.forEach { path ->
                val f = java.io.File(path)
                if (f.exists()) {
                    OutlinedButton(
                        onClick = { onOpenPath(path) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.History, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            f.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            f.parent ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (worldsLoading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (worlds.isNotEmpty()) {
            Text("快捷打开 level.dat", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            worlds.forEach { w ->
                val levelDat = w.dir.resolve("level.dat")
                if (Files.exists(levelDat)) {
                    OutlinedButton(
                        onClick = { onOpenPath(levelDat.toAbsolutePath().toString()) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Public, null)
                        Spacer(Modifier.width(8.dp))
                        Text(w.displayName.ifEmpty { w.name }, modifier = Modifier.weight(1f))
                        runCatching { Files.size(levelDat) / 1024 }
                            .getOrNull()?.let { Text("${it}KB", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else if (recentFiles.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AccountTree, null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "暂无打开的 NBT 文件",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "打开一个 NBT 文件（.dat/.nbt）开始编辑",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
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
    state: NbtEditorState,
    @Suppress("UNUSED_PARAMETER") revision: Int,
    onAddChild: (NbtTag.CompoundTag) -> Unit,
    onEditArray: (NbtTag) -> Unit,
    onConvert: (NbtTag, NbtTag?, String?) -> Unit,
    parent: NbtTag? = null,
    parentKey: String? = null
) {
    // 引用 revision 以确保编辑后整树重组（实际订阅在顶层 NbtEditorScreen 读取 state.revision）
    @Suppress("UNUSED_VARIABLE")
    val _rev = revision

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
                            state.renameChild(parent, parentKey, renameValue)
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
                    color = if (matchesSearch) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (searchQuery.isNotEmpty() && matchesSearch) {
                        Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 2.dp)
                    } else Modifier
                )
                if (!isRoot) {
                    IconButton(
                        onClick = { renameMode = true; renameValue = name },
                        modifier = Modifier.size(20.dp)
                    ) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp)) }
                }
            }

            Spacer(Modifier.width(4.dp))

            AssistChip(
                onClick = { onConvert(tag, parent, parentKey) },
                label = { Text(tag.typeName, style = MaterialTheme.typography.labelSmall) },
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
                        if (state.setLeafValue(tag, editValue)) editing = false
                    }) {
                        Icon(
                            Icons.Default.Check, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = { editing = false }) {
                        Icon(
                            Icons.Default.Close, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Text(
                        tag.getValueString(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp).clickable {
                            editing = true; editValue = stripQuotes(tag)
                        }
                    )
                    IconButton(
                        onClick = { editing = true; editValue = stripQuotes(tag) },
                        modifier = Modifier.size(20.dp)
                    ) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp)) }
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
                IconButton(
                    onClick = { state.copyNode(name, tag) },
                    modifier = Modifier.size(20.dp)
                ) { Icon(Icons.Default.ContentCopy, "复制", modifier = Modifier.size(14.dp)) }
                IconButton(
                    onClick = { state.cutNode(parent, parentKey, tag) },
                    modifier = Modifier.size(20.dp)
                ) { Icon(Icons.Default.ContentCut, "剪切", modifier = Modifier.size(14.dp)) }
                if (parent is NbtTag.CompoundTag && parentKey != null) {
                    IconButton(
                        onClick = { state.duplicateChild(parent, parentKey) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.ControlPointDuplicate, "复制副本",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Default.Delete, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (tag is NbtTag.CompoundTag) {
                IconButton(
                    onClick = { state.pasteNode(tag) },
                    enabled = state.hasClipboard,
                    modifier = Modifier.size(20.dp)
                ) { Icon(Icons.Default.ContentPaste, "粘贴", modifier = Modifier.size(14.dp)) }
                IconButton(onClick = { onAddChild(tag) }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Default.Add, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (tag is NbtTag.ListTag) {
                IconButton(
                    onClick = { state.pasteNode(tag) },
                    enabled = state.hasClipboard,
                    modifier = Modifier.size(20.dp)
                ) { Icon(Icons.Default.ContentPaste, "粘贴", modifier = Modifier.size(14.dp)) }
                IconButton(onClick = { state.addListItem(tag) }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Default.Add, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        if (isContainer && expanded) {
            when (tag) {
                is NbtTag.CompoundTag -> {
                    tag.children.forEach { (key, child) ->
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
                                state = state,
                                revision = revision,
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
                    tag.items.forEachIndexed { index, item ->
                        if (searchQuery.isEmpty() ||
                            tagMatchesValue(item, searchQuery) ||
                            hasMatchingDescendant(item, searchQuery)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = ((depth + 1) * 20).dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { state.moveListItem(tag, index, up = true) },
                                    modifier = Modifier.size(20.dp),
                                    enabled = index > 0
                                ) { Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(14.dp)) }
                                IconButton(
                                    onClick = { state.moveListItem(tag, index, up = false) },
                                    modifier = Modifier.size(20.dp),
                                    enabled = index < tag.size - 1
                                ) { Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(14.dp)) }
                                Text(
                                    "[$index]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.width(4.dp))
                                NbtTreeNode(
                                    tag = item,
                                    name = "[$index]",
                                    depth = depth + 1,
                                    searchQuery = searchQuery,
                                    expandAll = expandAll,
                                    isRoot = false,
                                    state = state,
                                    revision = revision,
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
                else -> { }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除「$name」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    if (parent is NbtTag.CompoundTag && parentKey != null) {
                        state.removeChild(parent, parentKey)
                    } else if (parent is NbtTag.ListTag) {
                        val idx = parentKey?.removePrefix("[")?.removeSuffix("]")?.toIntOrNull()
                        if (idx != null) state.removeListItem(parent, idx)
                    }
                    showDeleteConfirm = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
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
    var selected by remember { mutableStateOf(candidates.firstOrNull() ?: tag.type) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转换类型") },
        text = {
            if (candidates.isEmpty()) {
                Text("无可用转换")
            } else {
                Column {
                    Text("${tag.typeName} →", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        candidates.forEach { type ->
                            FilterChip(
                                selected = selected == type,
                                onClick = { selected = type },
                                label = {
                                    Text(
                                        NbtTag.getTypeName(type),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
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
            ) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
        title = { Text("添加子节点") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = parent.contains(it) },
                    label = { Text("标签名") },
                    isError = nameError || name.isBlank(),
                    supportingText = if (nameError) {
                        { Text("名称已存在") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("标签类型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    NbtTag.CREATABLE_TYPES.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = {
                                Text(
                                    NbtTag.getTypeName(type),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && !nameError) onConfirm(name, selectedType) },
                enabled = name.isNotBlank() && !nameError
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrayEditorDialog(
    array: NbtTag,
    onDismiss: () -> Unit,
    state: NbtEditorState
) {
    // 编辑后重组
    @Suppress("UNUSED_VARIABLE")
    val _rev = state.revision
    val elements: List<String> = when (array) {
        is NbtTag.ByteArrayTag -> array.value.map { it.toString() }
        is NbtTag.IntArrayTag -> array.value.map { it.toString() }
        is NbtTag.LongArrayTag -> array.value.map { "${it}L" }
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
        title = { Text("编辑数组 ($elementTypeName[${elements.size}])") },
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
                                    val v = if (array is NbtTag.LongArrayTag)
                                        editingValue.removeSuffix("L") else editingValue
                                    if (state.setArrayElement(array, index, v)) editingIndex = -1
                                }) {
                                    Icon(
                                        Icons.Default.Check, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(onClick = { editingIndex = -1 }) {
                                    Icon(
                                        Icons.Default.Close, null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        editingIndex = index
                                        editingValue = if (array is NbtTag.LongArrayTag)
                                            value.removeSuffix("L") else value
                                    },
                                    modifier = Modifier.size(20.dp)
                                ) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp)) }
                                IconButton(
                                    onClick = { state.removeArrayElement(array, index) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete, null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (elements.isEmpty()) {
                        Text(
                            "空数组",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newElementValue,
                        onValueChange = { newElementValue = it },
                        label = { Text("添加元素") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val v = if (array is NbtTag.LongArrayTag)
                            newElementValue.removeSuffix("L") else newElementValue
                        if (state.addArrayElement(array, v)) newElementValue = ""
                    }) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun OpenPathDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开 NBT 文件") },
        text = {
            Column {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("文件路径") },
                    placeholder = { Text("/storage/.../level.dat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持 .dat / .nbt，自动识别 gzip 压缩",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (path.isNotBlank()) onConfirm(path.trim()) },
                enabled = path.isNotBlank()
            ) { Text("打开") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SaveAsDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var path by remember {
        mutableStateOf(
            java.io.File(System.getProperty("user.home", "/storage/emulated/0"), defaultName).absolutePath
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("另存为") },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("文件路径") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (path.isNotBlank()) onConfirm(path.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
        title = { Text("导出SNBT") },
        text = {
            Column {
                Text(
                    "SNBT 已复制到剪贴板",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    Text(
                        snbt,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier.padding(8.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

// ===== 辅助函数 =====

private fun stripQuotes(tag: NbtTag): String = when (tag) {
    is NbtTag.StringTag -> tag.value
    is NbtTag.LongTag -> tag.value.toString()
    is NbtTag.FloatTag -> tag.value.toString()
    is NbtTag.DoubleTag -> tag.value.toString()
    else -> tag.getValueString()
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
        is NbtTag.CompoundTag -> tag.children.any { (key, child) ->
            key.contains(query, ignoreCase = true) ||
                    tagMatchesValue(child, query) ||
                    hasMatchingDescendant(child, query)
        }
        is NbtTag.ListTag -> tag.items.any {
            tagMatchesValue(it, query) || hasMatchingDescendant(it, query)
        }
        else -> tagMatchesValue(tag, query)
    }
}

/** 可转换的目标类型（不含自身；Compound/List 不可转） */
private fun convertibleTypes(tag: NbtTag): List<Int> = when (tag) {
    is NbtTag.ByteTag, is NbtTag.ShortTag, is NbtTag.IntTag,
    is NbtTag.LongTag, is NbtTag.FloatTag, is NbtTag.DoubleTag -> listOf(
        NbtTag.TYPE_BYTE, NbtTag.TYPE_SHORT, NbtTag.TYPE_INT,
        NbtTag.TYPE_LONG, NbtTag.TYPE_FLOAT, NbtTag.TYPE_DOUBLE, NbtTag.TYPE_STRING
    ).filter { it != tag.type }
    is NbtTag.StringTag -> listOf(
        NbtTag.TYPE_BYTE, NbtTag.TYPE_SHORT, NbtTag.TYPE_INT,
        NbtTag.TYPE_LONG, NbtTag.TYPE_FLOAT, NbtTag.TYPE_DOUBLE
    )
    is NbtTag.ByteArrayTag -> listOf(NbtTag.TYPE_INT_ARRAY)
    is NbtTag.IntArrayTag -> listOf(NbtTag.TYPE_BYTE_ARRAY, NbtTag.TYPE_LONG_ARRAY)
    is NbtTag.LongArrayTag -> listOf(NbtTag.TYPE_INT_ARRAY)
    else -> emptyList()
}
