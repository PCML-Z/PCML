package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.gamecontent.ConfigFileManager
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 模组配置文件编辑器页面。
 * <p>
 * 左侧文件列表（支持目录导航），右侧文本编辑器。
 * 支持读取/编辑/保存 .cfg/.toml/.json/.properties 等配置文件。
 * 保存时自动创建 .bak 备份。
 */
@Composable
fun ConfigEditorPage(vm: LauncherViewModel) {
    val configFiles by vm.configFiles.collectAsState()
    val currentDir by vm.configCurrentDir.collectAsState()
    val fileContent by vm.configFileContent.collectAsState()
    val currentPath by vm.currentConfigPath.collectAsState()
    val isDirty by vm.configFileDirty.collectAsState()
    val status by vm.status.collectAsState()
    val selectedVersion by vm.selectedVersion.collectAsState()

    var searchText by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var editorText by remember(fileContent) { mutableStateOf(fileContent ?: "") }
    // 待切换的目标文件路径：切换前若当前文件有未保存修改，弹窗确认后再切换
    var pendingSwitchPath by remember { mutableStateOf<String?>(null) }

    // 文件内容变化时同步到 editorText
    LaunchedEffect(fileContent) {
        editorText = fileContent ?: ""
    }

    // 首次进入刷新文件列表
    LaunchedEffect(Unit) {
        vm.refreshConfigFiles()
    }

    // 版本切换时刷新
    LaunchedEffect(selectedVersion) {
        vm.refreshConfigFiles()
    }

    val filteredFiles = remember(configFiles, searchText) {
        if (searchText.isBlank()) configFiles
        else configFiles.filter { it.fileName.contains(searchText, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶部标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("config.title"), style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (selectedVersion != null) {
                Text(I18n.t("config.version_label", selectedVersion),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(12.dp))
            }
            OutlinedButton(onClick = { vm.openConfigDir() }) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("config.open_dir"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.refreshConfigFiles(currentDir) }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.refresh"))
            }
        }

        Spacer(Modifier.height(12.dp))

        // 主体：左右分栏
        Row(Modifier.fillMaxSize()) {
            // 左侧：文件列表
            Surface(
                modifier = Modifier.weight(0.35f).fillMaxHeight(),
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    // 面包屑导航
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        if (currentDir.isNotEmpty()) {
                            IconButton(onClick = { vm.navigateConfigUp() }, Modifier.size(28.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, I18n.t("config.return_upper"),
                                     Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = "config/" + if (currentDir.isEmpty()) "" else currentDir,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showCreateDialog = true }, Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Add, I18n.t("config.new_file"), Modifier.size(16.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 搜索框
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = { Text(I18n.t("config.search_placeholder"), style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(14.dp)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(8.dp))

                    // 文件列表
                    if (filteredFiles.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.FolderOff, null,
                                     Modifier.size(32.dp),
                                     tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                Spacer(Modifier.height(8.dp))
                                Text(I18n.t("config.empty"),
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(4.dp))
                                Text(I18n.t("config.empty_hint"),
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(filteredFiles, key = { it.relativePath }) { entry ->
                                ConfigFileRow(
                                    entry = entry,
                                    isSelected = entry.relativePath == currentPath,
                                    onClick = {
                                        if (entry.isDirectory) {
                                            // 进入子目录无需提示（不影响当前编辑的文件）
                                            vm.enterConfigDir(entry.fileName)
                                        } else if (entry.relativePath == currentPath) {
                                            // 点击当前已选中文件，无操作
                                        } else if (isDirty) {
                                            // 当前文件有未保存修改：暂存目标路径，弹窗确认
                                            pendingSwitchPath = entry.relativePath
                                        } else {
                                            vm.readConfigFile(entry.relativePath)
                                        }
                                    },
                                    onDelete = { showDeleteDialog = entry.relativePath }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // 右侧：编辑器
            Surface(
                modifier = Modifier.weight(0.65f).fillMaxHeight(),
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (fileContent == null) {
                    // 未选择文件
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Description, null,
                                 Modifier.size(48.dp),
                                 tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            Spacer(Modifier.height(12.dp))
                            Text(I18n.t("config.select_to_edit"),
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        // 编辑器工具栏
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = currentPath ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (isDirty) {
                                Text(" *",
                                     color = MaterialTheme.colorScheme.error,
                                     style = MaterialTheme.typography.labelMedium,
                                     fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { vm.saveConfigFile(editorText) },
                                enabled = isDirty
                            ) {
                                Icon(Icons.Filled.Save, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(I18n.t("common.save"))
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { vm.closeConfigFile() }, Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, I18n.t("common.close"), Modifier.size(16.dp))
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 文本编辑器
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            OutlinedTextField(
                                value = editorText,
                                onValueChange = {
                                    editorText = it
                                    if (!isDirty) vm.markConfigDirty()
                                },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                ),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 新建文件对话框
    if (showCreateDialog) {
        var newFileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(I18n.t("config.new_title")) },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text(I18n.t("config.filename_label")) },
                    singleLine = true,
                    placeholder = { Text("example.toml") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFileName.isNotBlank()) {
                            vm.createConfigFile(newFileName.trim())
                            showCreateDialog = false
                        }
                    }
                ) { Text(I18n.t("config.create")) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }

    // 删除确认对话框
    showDeleteDialog?.let { path ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(I18n.t("config.delete_title")) },
            text = { Text(I18n.t("config.delete_confirm", path)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteConfigFile(path)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text(I18n.t("common.cancel")) }
            }
        )
    }

    // 未保存切换确认对话框
    pendingSwitchPath?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSwitchPath = null },
            title = { Text(I18n.t("config.unsaved_title")) },
            text = { Text(I18n.t("config.unsaved_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    // 保存当前文件后切换
                    vm.saveConfigFile(editorText)
                    vm.readConfigFile(target)
                    pendingSwitchPath = null
                }) { Text(I18n.t("config.save_and_switch")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // 丢弃修改，直接切换
                        vm.readConfigFile(target)
                        pendingSwitchPath = null
                    }) { Text(I18n.t("config.discard")) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { pendingSwitchPath = null }) { Text(I18n.t("common.cancel")) }
                }
            }
        )
    }
}

/** 配置文件列表项 */
@Composable
private fun ConfigFileRow(
    entry: ConfigFileManager.ConfigFileEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    val icon = if (entry.isDirectory) Icons.Filled.Folder
               else when (entry.format) {
                   "json" -> Icons.Filled.Code
                   "toml" -> Icons.Filled.Settings
                   "cfg" -> Icons.Filled.Build
                   "properties", "props" -> Icons.Filled.List
                   "yml", "yaml" -> Icons.Filled.Dataset
                   "xml" -> Icons.Filled.Code
                   else -> Icons.Filled.Description
               }
    val iconColor = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = iconColor)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.fileName + if (entry.isDirectory) "/" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.isDirectory) {
                    Text(
                        text = ConfigFileManager.formatSize(entry.size) +
                               " · " + dateFormat.format(Date(entry.lastModified)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                }
            }
            if (!entry.isDirectory) {
                IconButton(onClick = onDelete, Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Delete, I18n.t("common.delete"),
                         Modifier.size(14.dp),
                         tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
