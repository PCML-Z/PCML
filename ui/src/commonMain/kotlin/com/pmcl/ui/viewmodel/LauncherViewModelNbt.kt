package com.pmcl.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pmcl.core.i18n.I18n
import com.pmcl.core.nbt.NbtReader
import com.pmcl.core.nbt.NbtWriter
import com.pmcl.core.nbt.NbtTag

/**
 * M29 拆分：NBT 编辑器域。
 *
 * 从 LauncherViewModel.kt 抽取的 NBT 相关状态操作扩展函数。
 * 状态字段保留在 LauncherViewModel 中（@PublishedApi internal），
 * 以便 UI 调用方（vm.openNbtFile / vm.saveNbtFile 等）签名不变。
 */


@PublishedApi
internal fun LauncherViewModel.clearNbtHistory() {
    nbtUndoStack.clear()
    nbtRedoStack.clear()
    _nbtCanUndo.value = false
    _nbtCanRedo.value = false
}

@PublishedApi
internal fun LauncherViewModel.pushNbtUndo() {
    val snap = _nbtRoot.value?.copy() ?: return
    nbtUndoStack.addLast(snap)
    while (nbtUndoStack.size > nbtMaxUndo) nbtUndoStack.removeFirst()
    nbtRedoStack.clear()
    _nbtCanUndo.value = true
    _nbtCanRedo.value = false
}

@PublishedApi
internal fun LauncherViewModel.refreshNbtHistoryFlags() {
    _nbtCanUndo.value = nbtUndoStack.isNotEmpty()
    _nbtCanRedo.value = nbtRedoStack.isNotEmpty()
}

fun LauncherViewModel.undoNbt() {
    val current = _nbtRoot.value ?: return
    val prev = nbtUndoStack.removeLastOrNull() ?: return
    nbtRedoStack.addLast(current.copy())
    _nbtRoot.value = prev
    _nbtDirty.value = true
    _nbtRevision.value++
    refreshNbtHistoryFlags()
}

fun LauncherViewModel.redoNbt() {
    val current = _nbtRoot.value ?: return
    val next = nbtRedoStack.removeLastOrNull() ?: return
    nbtUndoStack.addLast(current.copy())
    _nbtRoot.value = next
    _nbtDirty.value = true
    _nbtRevision.value++
    refreshNbtHistoryFlags()
}

/** 打开 NBT 文件（自动检测 gzip 压缩，如 level.dat） */
fun LauncherViewModel.openNbtFile(path: String) {
    scope.launch {
        _nbtError.value = null
        try {
            val result = withContext(Dispatchers.IO) {
                NbtReader.readWithMeta(java.nio.file.Paths.get(path))
            }
            _nbtGzipped.value = result.gzipped
            _nbtRoot.value = result.root
            _nbtFilePath.value = path
            _nbtDirty.value = false
            clearNbtHistory()
            _nbtRevision.value++
            withContext(Dispatchers.IO) {
                core.preferences.recordRecentNbtFile(path)
            }
            _recentNbtFiles.value = core.preferences.recentNbtFiles
            _status.value = I18n.t("status.nbt_loaded", path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _nbtError.value = "读取 NBT 失败: ${e.message}"
            _status.value = I18n.t("status.nbt_read_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 保存 NBT 到当前文件（深拷贝快照 + .bak + 原子写；保持压缩方式） */
fun LauncherViewModel.saveNbtFile() {
    val root = _nbtRoot.value ?: return
    val path = _nbtFilePath.value ?: return
    val gzipped = _nbtGzipped.value
    val snapshot = root.copy()
    scope.launch {
        _nbtError.value = null
        try {
            withContext(Dispatchers.IO) {
                val file = java.nio.file.Paths.get(path)
                if (java.nio.file.Files.exists(file)) {
                    val bak = file.resolveSibling(file.fileName.toString() + ".bak")
                    java.nio.file.Files.copy(file, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                NbtWriter.write(snapshot, file, gzipped)
            }
            _nbtDirty.value = false
            _status.value = I18n.t("status.nbt_saved", path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _nbtError.value = "保存 NBT 失败: ${e.message}"
            _status.value = I18n.t("status.nbt_save_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 另存为指定路径（深拷贝快照 + 原子写） */
fun LauncherViewModel.saveNbtFileAs(targetPath: String) {
    val root = _nbtRoot.value ?: return
    val gzipped = _nbtGzipped.value
    val snapshot = root.copy()
    scope.launch {
        _nbtError.value = null
        try {
            withContext(Dispatchers.IO) {
                NbtWriter.write(snapshot, java.nio.file.Paths.get(targetPath), gzipped)
                core.preferences.recordRecentNbtFile(targetPath)
            }
            _nbtFilePath.value = targetPath
            _nbtDirty.value = false
            _recentNbtFiles.value = core.preferences.recentNbtFiles
            _status.value = I18n.t("status.nbt_saved", targetPath)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _nbtError.value = "保存 NBT 失败: ${e.message}"
            _status.value = I18n.t("status.nbt_save_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 标记 NBT 树已修改，触发 UI 重组（调用前须已 pushNbtUndo） */
fun LauncherViewModel.updateNbtValue() {
    _nbtDirty.value = true
    _nbtRevision.value++
}

/** 关闭当前 NBT 文件 */
fun LauncherViewModel.closeNbtFile() {
    _nbtRoot.value = null
    _nbtFilePath.value = null
    _nbtDirty.value = false
    _nbtError.value = null
    _nbtGzipped.value = true
    clearNbtHistory()
    _nbtRevision.value++
}

// ===== 树结构编辑 =====

/** 向 Compound 添加子标签 */
fun LauncherViewModel.addNbtChild(parent: NbtTag.CompoundTag, name: String, type: Int) {
    if (parent.contains(name)) return
    pushNbtUndo()
    parent.put(name, NbtTag.createDefault(type))
    updateNbtValue()
}

/** 从 Compound 删除子标签 */
fun LauncherViewModel.removeNbtChild(parent: NbtTag.CompoundTag, name: String) {
    if (!parent.contains(name)) return
    pushNbtUndo()
    parent.remove(name)
    updateNbtValue()
}

/** 重命名 Compound 子标签 */
fun LauncherViewModel.renameNbtChild(parent: NbtTag.CompoundTag, oldName: String, newName: String) {
    if (oldName == newName || parent.contains(newName)) return
    val tag = parent.get(oldName) ?: return
    pushNbtUndo()
    parent.remove(oldName)
    parent.put(newName, tag)
    updateNbtValue()
}

/** 向 List 添加元素（使用 listType 创建默认值） */
fun LauncherViewModel.addNbtListItem(list: NbtTag.ListTag) {
    pushNbtUndo()
    val type = if (list.getListType() == NbtTag.TYPE_END) NbtTag.TYPE_COMPOUND else list.getListType()
    list.add(NbtTag.createDefault(type))
    updateNbtValue()
}

/** 删除 List 元素 */
fun LauncherViewModel.removeNbtListItem(list: NbtTag.ListTag, index: Int) {
    if (index < 0 || index >= list.size()) return
    pushNbtUndo()
    list.remove(index)
    updateNbtValue()
}

/** 移动 List 元素（up=true 上移，up=false 下移） */
fun LauncherViewModel.moveNbtListItem(list: NbtTag.ListTag, index: Int, up: Boolean) {
    val target = if (up) index - 1 else index + 1
    if (target < 0 || target >= list.size()) return
    pushNbtUndo()
    val item = list.getItems()[index]
    list.remove(index)
    list.add(target, item)
    updateNbtValue()
}

/** 内联编辑叶节点值（带撤销） */
fun LauncherViewModel.setNbtLeafValue(tag: NbtTag, text: String): Boolean {
    return try {
        pushNbtUndo()
        when (tag) {
            is NbtTag.ByteTag -> tag.setValue(text.toByte())
            is NbtTag.ShortTag -> tag.setValue(text.toShort())
            is NbtTag.IntTag -> tag.setValue(text.toInt())
            is NbtTag.LongTag -> tag.setValue(text.toLong())
            is NbtTag.FloatTag -> tag.setValue(text.toFloat())
            is NbtTag.DoubleTag -> tag.setValue(text.toDouble())
            is NbtTag.StringTag -> tag.setValue(text)
            else -> {
                nbtUndoStack.removeLastOrNull()
                refreshNbtHistoryFlags()
                return false
            }
        }
        updateNbtValue()
        true
    } catch (_: NumberFormatException) {
        nbtUndoStack.removeLastOrNull()
        refreshNbtHistoryFlags()
        false
    }
}

/** 类型转换（数值/字符串/数组族） */
fun LauncherViewModel.convertNbtTag(parent: NbtTag?, key: String?, tag: NbtTag, targetType: Int): Boolean {
    if (tag.getType() == targetType) return true
    val converted = NbtTag.convert(tag, targetType) ?: return false
    pushNbtUndo()
    when {
        parent is NbtTag.CompoundTag && key != null -> parent.put(key, converted)
        parent is NbtTag.ListTag && key != null -> {
            val idx = key.removePrefix("[").removeSuffix("]").toIntOrNull() ?: run {
                nbtUndoStack.removeLastOrNull()
                refreshNbtHistoryFlags()
                return false
            }
            if (idx < 0 || idx >= parent.size()) {
                nbtUndoStack.removeLastOrNull()
                refreshNbtHistoryFlags()
                return false
            }
            // List 要求同类型：若 listType 不匹配则拒绝
            if (parent.getListType() != NbtTag.TYPE_END && parent.getListType() != targetType) {
                nbtUndoStack.removeLastOrNull()
                refreshNbtHistoryFlags()
                return false
            }
            parent.remove(idx)
            parent.add(idx, converted)
        }
        _nbtRoot.value === tag -> {
            converted.setName(tag.getName())
            _nbtRoot.value = converted
        }
        else -> {
            nbtUndoStack.removeLastOrNull()
            refreshNbtHistoryFlags()
            return false
        }
    }
    updateNbtValue()
    return true
}

fun LauncherViewModel.copyNbtNode(name: String, tag: NbtTag) {
    nbtClipboard = name to tag.copy()
    _nbtHasClipboard.value = true
}

fun LauncherViewModel.cutNbtNode(parent: NbtTag?, key: String?, tag: NbtTag) {
    if (parent == null || key == null) return
    val clipName = if (parent is NbtTag.CompoundTag) key else "item"
    nbtClipboard = clipName to tag.copy()
    _nbtHasClipboard.value = true
    when (parent) {
        is NbtTag.CompoundTag -> removeNbtChild(parent, key)
        is NbtTag.ListTag -> {
            val idx = key.removePrefix("[").removeSuffix("]").toIntOrNull() ?: return
            removeNbtListItem(parent, idx)
        }
    }
}

fun LauncherViewModel.pasteNbtNode(parent: NbtTag) {
    val clip = nbtClipboard ?: return
    pushNbtUndo()
    when (parent) {
        is NbtTag.CompoundTag -> {
            var name = clip.first.ifBlank { "tag" }
            var i = 1
            while (parent.contains(name)) {
                name = "${clip.first}_$i"
                i++
            }
            parent.put(name, clip.second.copy())
        }
        is NbtTag.ListTag -> {
            val item = clip.second.copy()
            if (parent.getListType() != NbtTag.TYPE_END && parent.getListType() != item.getType()) {
                nbtUndoStack.removeLastOrNull()
                refreshNbtHistoryFlags()
                _nbtError.value = I18n.t("nbt.paste_type_mismatch")
                return
            }
            parent.add(item)
        }
        else -> {
            nbtUndoStack.removeLastOrNull()
            refreshNbtHistoryFlags()
            return
        }
    }
    updateNbtValue()
}

fun LauncherViewModel.duplicateNbtChild(parent: NbtTag.CompoundTag, key: String) {
    val tag = parent.get(key) ?: return
    var name = "${key}_copy"
    var i = 1
    while (parent.contains(name)) {
        name = "${key}_copy$i"
        i++
    }
    pushNbtUndo()
    parent.put(name, tag.copy())
    updateNbtValue()
}

// ===== 数组编辑 =====

/** 设置数组元素值 */
fun LauncherViewModel.setNbtArrayElement(array: NbtTag, index: Int, value: String): Boolean {
    try {
        when (array) {
            is NbtTag.ByteArrayTag -> {
                val arr = array.getValue()
                if (index < 0 || index >= arr.size) return false
                pushNbtUndo()
                try { arr[index] = value.toByte() } catch (e: NumberFormatException) {
                    nbtUndoStack.removeLastOrNull(); refreshNbtHistoryFlags(); throw e
                }
            }
            is NbtTag.IntArrayTag -> {
                val arr = array.getValue()
                if (index < 0 || index >= arr.size) return false
                pushNbtUndo()
                try { arr[index] = value.toInt() } catch (e: NumberFormatException) {
                    nbtUndoStack.removeLastOrNull(); refreshNbtHistoryFlags(); throw e
                }
            }
            is NbtTag.LongArrayTag -> {
                val arr = array.getValue()
                if (index < 0 || index >= arr.size) return false
                pushNbtUndo()
                try { arr[index] = value.toLong() } catch (e: NumberFormatException) {
                    nbtUndoStack.removeLastOrNull(); refreshNbtHistoryFlags(); throw e
                }
            }
            else -> return false
        }
        updateNbtValue()
        return true
    } catch (_: NumberFormatException) {
        return false
    }
}

/** 添加数组元素 */
fun LauncherViewModel.addNbtArrayElement(array: NbtTag, value: String): Boolean {
    try {
        pushNbtUndo()
        when (array) {
            is NbtTag.ByteArrayTag -> {
                val old = array.getValue()
                val newArr = java.util.Arrays.copyOf(old, old.size + 1)
                newArr[old.size] = value.toByte()
                array.setValue(newArr)
            }
            is NbtTag.IntArrayTag -> {
                val old = array.getValue()
                val newArr = java.util.Arrays.copyOf(old, old.size + 1)
                newArr[old.size] = value.toInt()
                array.setValue(newArr)
            }
            is NbtTag.LongArrayTag -> {
                val old = array.getValue()
                val newArr = java.util.Arrays.copyOf(old, old.size + 1)
                newArr[old.size] = value.toLong()
                array.setValue(newArr)
            }
            else -> {
                nbtUndoStack.removeLastOrNull()
                refreshNbtHistoryFlags()
                return false
            }
        }
        updateNbtValue()
        return true
    } catch (_: NumberFormatException) {
        nbtUndoStack.removeLastOrNull()
        refreshNbtHistoryFlags()
        return false
    }
}

/** 删除数组元素 */
fun LauncherViewModel.removeNbtArrayElement(array: NbtTag, index: Int) {
    when (array) {
        is NbtTag.ByteArrayTag -> {
            val old = array.getValue()
            if (index < 0 || index >= old.size) return
            pushNbtUndo()
            val newArr = java.util.Arrays.copyOf(old, old.size - 1)
            var j = 0
            for (i in old.indices) { if (i != index) newArr[j++] = old[i] }
            array.setValue(newArr)
        }
        is NbtTag.IntArrayTag -> {
            val old = array.getValue()
            if (index < 0 || index >= old.size) return
            pushNbtUndo()
            val newArr = java.util.Arrays.copyOf(old, old.size - 1)
            var j = 0
            for (i in old.indices) { if (i != index) newArr[j++] = old[i] }
            array.setValue(newArr)
        }
        is NbtTag.LongArrayTag -> {
            val old = array.getValue()
            if (index < 0 || index >= old.size) return
            pushNbtUndo()
            val newArr = java.util.Arrays.copyOf(old, old.size - 1)
            var j = 0
            for (i in old.indices) { if (i != index) newArr[j++] = old[i] }
            array.setValue(newArr)
        }
        else -> return
    }
    updateNbtValue()
}

/** 导出 NBT 为 SNBT 字符串 */
fun LauncherViewModel.exportNbtSnbt(): String {
    return _nbtRoot.value?.toSnbt() ?: ""
}

