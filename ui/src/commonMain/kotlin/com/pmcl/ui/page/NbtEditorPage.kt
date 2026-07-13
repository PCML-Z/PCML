package com.pmcl.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.nbt.NbtTag
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter

/**
 * NBT 编辑器页面。
 * <p>
 * 支持打开 gzip 压缩的 NBT 文件（如 level.dat、playerdata 下的 .dat），
 * 以树形结构展示并可编辑叶节点的值。
 */
@Composable
fun NbtEditorPage(vm: LauncherViewModel) {
    val root by vm.nbtRoot.collectAsState()
    val filePath by vm.nbtFilePath.collectAsState()
    val dirty by vm.nbtDirty.collectAsState()
    val error by vm.nbtError.collectAsState()
    val worlds by vm.worlds.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶部工具栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                I18n.t("nbt.title"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // 打开文件按钮
            OutlinedButton(onClick = {
                val fd = FileDialog(null as Frame?, I18n.t("nbt.open_file"), FileDialog.LOAD)
                fd.file = "*.dat"
                fd.filenameFilter = FilenameFilter { _, name -> name.endsWith(".dat") || name.endsWith(".nbt") }
                fd.isVisible = true
                if (fd.file != null) {
                    vm.openNbtFile(java.io.File(fd.directory, fd.file).absolutePath)
                }
            }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("nbt.open"))
            }
            // 保存按钮
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.saveNbtFile() },
                enabled = root != null && filePath != null
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("nbt.save"))
                if (dirty) {
                    Spacer(Modifier.width(4.dp))
                    Text("*", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // 文件路径和状态
        Spacer(Modifier.height(4.dp))
        if (filePath != null) {
            Text(
                filePath!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (error != null) {
            Text(
                error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // 快捷打开世界 level.dat
        if (root == null && worlds.isNotEmpty()) {
            Text(
                I18n.t("nbt.quick_open"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(worlds, key = { it.getDir().toString() }) { world ->
                    val levelDat = java.io.File(world.getDir().toString(), "level.dat")
                    if (levelDat.exists()) {
                        Surface(
                            tonalElevation = 1.dp,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Public, contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(world.getName(), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium)
                                    Text(levelDat.absolutePath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = {
                                    vm.openNbtFile(levelDat.absolutePath)
                                }) {
                                    Text(I18n.t("nbt.open"))
                                }
                            }
                        }
                    }
                }
            }
        }

        // NBT 树形展示
        if (root != null) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    NbtTreeNode(
                        tag = root!!,
                        path = "",
                        vm = vm
                    )
                }
            }
        }

        // 空状态
        if (root == null && worlds.isEmpty() && error == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text(I18n.t("nbt.empty"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("nbt.empty_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/**
 * 递归渲染 NBT 树节点。
 * Compound 和 List 可展开/折叠，叶节点显示值并支持内联编辑。
 */
@Composable
private fun NbtTreeNode(
    tag: NbtTag,
    path: String,
    vm: LauncherViewModel,
    depth: Int = 0
) {
    var expanded by remember { mutableStateOf(depth < 2) } // 默认展开前两层

    Column(modifier = Modifier.padding(start = (depth * 16).dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
        ) {
            // 展开/折叠按钮（仅 Compound/List）
            if (tag is NbtTag.CompoundTag || tag is NbtTag.ListTag) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Spacer(Modifier.width(20.dp))
            }

            // 节点名称
            val displayName = if (tag.getName().isEmpty()) "(root)" else tag.getName()
            Text(
                displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.widthIn(min = 100.dp, max = 200.dp)
            )
            // 类型标签
            AssistChip(
                onClick = {},
                label = { Text(tag.getTypeName(), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(20.dp).padding(start = 4.dp)
            )

            Spacer(Modifier.width(8.dp))

            // 叶节点值（可编辑）
            if (tag !is NbtTag.CompoundTag && tag !is NbtTag.ListTag) {
                EditableNbtValue(tag = tag, onValueChange = { vm.updateNbtValue() })
            } else {
                Text(
                    tag.getValueString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 子节点
        if (expanded) {
            when (tag) {
                is NbtTag.CompoundTag -> {
                    tag.getChildren().forEach { (key, child) ->
                        NbtTreeNode(
                            tag = child,
                            path = "$path.$key",
                            vm = vm,
                            depth = depth + 1
                        )
                    }
                }
                is NbtTag.ListTag -> {
                    tag.getItems().forEachIndexed { idx, child ->
                        NbtTreeNode(
                            tag = child,
                            path = "$path[$idx]",
                            vm = vm,
                            depth = depth + 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * 可编辑的 NBT 叶节点值。
 * 支持内联编辑 Byte/Short/Int/Long/Float/Double/String 类型。
 */
@Composable
private fun EditableNbtValue(tag: NbtTag, onValueChange: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf("") }

    if (!editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tag.getValueString(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 300.dp).clickable {
                    editValue = when (tag) {
                        is NbtTag.StringTag -> tag.getValue()
                        is NbtTag.ByteTag -> tag.getValue().toString()
                        is NbtTag.ShortTag -> tag.getValue().toString()
                        is NbtTag.IntTag -> tag.getValue().toString()
                        is NbtTag.LongTag -> tag.getValue().toString()
                        is NbtTag.FloatTag -> tag.getValue().toString()
                        is NbtTag.DoubleTag -> tag.getValue().toString()
                        else -> tag.getValueString()
                    }
                    editing = true
                }
            )
            // 数组类型不可编辑，只显示
            if (tag !is NbtTag.ByteArrayTag && tag !is NbtTag.IntArrayTag && tag !is NbtTag.LongArrayTag) {
                IconButton(onClick = {
                    editValue = when (tag) {
                        is NbtTag.StringTag -> tag.getValue()
                        is NbtTag.ByteTag -> tag.getValue().toString()
                        is NbtTag.ShortTag -> tag.getValue().toString()
                        is NbtTag.IntTag -> tag.getValue().toString()
                        is NbtTag.LongTag -> tag.getValue().toString()
                        is NbtTag.FloatTag -> tag.getValue().toString()
                        is NbtTag.DoubleTag -> tag.getValue().toString()
                        else -> ""
                    }
                    editing = true
                }, modifier = Modifier.size(16.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = I18n.t("nbt.edit"),
                        modifier = Modifier.size(12.dp))
                }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = editValue,
                onValueChange = { editValue = it },
                singleLine = true,
                modifier = Modifier.width(220.dp).height(36.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            IconButton(onClick = {
                try {
                    when (tag) {
                        is NbtTag.StringTag -> tag.setValue(editValue)
                        is NbtTag.ByteTag -> tag.setValue(editValue.toByte())
                        is NbtTag.ShortTag -> tag.setValue(editValue.toShort())
                        is NbtTag.IntTag -> tag.setValue(editValue.toInt())
                        is NbtTag.LongTag -> tag.setValue(editValue.toLong())
                        is NbtTag.FloatTag -> tag.setValue(editValue.toFloat())
                        is NbtTag.DoubleTag -> tag.setValue(editValue.toDouble())
                    }
                    onValueChange()
                } catch (_: NumberFormatException) {
                    // 忽略无效输入
                }
                editing = false
            }, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Filled.Check, contentDescription = I18n.t("common.confirm"),
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { editing = false }, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Filled.Close, contentDescription = I18n.t("common.cancel"),
                    modifier = Modifier.size(14.dp))
            }
        }
    }
}
