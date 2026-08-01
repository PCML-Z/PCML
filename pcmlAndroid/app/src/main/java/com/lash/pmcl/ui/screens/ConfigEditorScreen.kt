package com.lash.pmcl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.gamecontent.ConfigFileManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(configFileManager: ConfigFileManager) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    // 列表状态
    var listLoading by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf<List<ConfigFileManager.ConfigFileEntry>>(emptyList()) }
    var currentSubDir by remember { mutableStateOf("") }
    var listError by remember { mutableStateOf<String?>(null) }

    // 编辑器状态
    var selectedEntry by remember { mutableStateOf<ConfigFileManager.ConfigFileEntry?>(null) }
    var contentLoading by remember { mutableStateOf(false) }
    var contentError by remember { mutableStateOf<String?>(null) }
    var editedContent by remember { mutableStateOf("") }
    var originalContent by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    // 用于跨线程传递 snackbar 消息
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    fun refreshList() {
        listLoading = true
        listError = null
        val sub = currentSubDir
        Thread {
            try {
                files = configFileManager.listFiles(sub)
            } catch (e: Exception) {
                listError = e.message ?: e.toString()
            } finally {
                listLoading = false
            }
        }.start()
    }

    fun openFile(entry: ConfigFileManager.ConfigFileEntry) {
        selectedEntry = entry
        contentLoading = true
        contentError = null
        editedContent = ""
        originalContent = ""
        val rel = entry.relativePath
        Thread {
            try {
                val text = configFileManager.readFile(rel)
                originalContent = text
                editedContent = text
            } catch (e: Exception) {
                contentError = e.message ?: e.toString()
            } finally {
                contentLoading = false
            }
        }.start()
    }

    fun saveFile() {
        val entry = selectedEntry ?: return
        saving = true
        val rel = entry.relativePath
        val content = editedContent
        Thread {
            try {
                configFileManager.writeFile(rel, content)
                originalContent = content
                saving = false
                // snackbar 必须在主线程，但此处简化：直接设置状态由 LaunchedEffect 触发
                savedMessage = "已保存（已备份 .bak）"
            } catch (e: Exception) {
                saving = false
                savedMessage = "保存失败：${e.message ?: e.toString()}"
            }
        }.start()
    }

    // 用于跨线程传递 snackbar 消息（已在前文声明）
    LaunchedEffect(savedMessage) {
        savedMessage?.let {
            snackbarHostState.showSnackbar(it)
            savedMessage = null
        }
    }

    LaunchedEffect(Unit) { refreshList() }

    val inEditor = selectedEntry != null

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (inEditor) {
                TopAppBar(
                    title = {
                        Text(
                            text = selectedEntry?.fileName ?: "配置文件",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectedEntry = null
                            editedContent = ""
                            originalContent = ""
                            contentError = null
                            refreshList()
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { saveFile() },
                            enabled = !saving && !contentLoading &&
                                editedContent != originalContent,
                        ) {
                            if (saving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Save, contentDescription = "保存")
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            } else {
                TopAppBar(
                    title = {
                        Text(if (currentSubDir.isEmpty()) "配置文件"
                             else "配置文件 / $currentSubDir")
                    },
                    actions = {
                        IconButton(onClick = { refreshList() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            if (inEditor) {
                // 编辑器视图
                when {
                    contentLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    contentError != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("读取失败", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(contentError!!, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    else -> {
                        val entry = selectedEntry!!
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                        ) {
                            // 文件元信息
                            Text("${entry.format.uppercase(Locale.ROOT)}  ·  ${ConfigFileManager.formatSize(entry.size)}  ·  ${dateFmt.format(Date(entry.lastModified))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = editedContent,
                                onValueChange = { editedContent = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                label = { Text("文件内容") },
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                isError = false,
                            )
                            Spacer(Modifier.height(8.dp))
                            if (editedContent != originalContent) {
                                Text("有未保存的修改",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary)
                            } else {
                                Text("无修改",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            } else {
                // 文件列表视图
                when {
                    listLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    listError != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("加载失败", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(listError!!, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    files.isEmpty() && currentSubDir.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.Description, contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(12.dp))
                                Text("暂无配置文件", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.outline)
                                Text("游戏运行后会在此生成配置",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 返回上级目录项
                            if (currentSubDir.isNotEmpty()) {
                                item(key = "__parent__") {
                                    ParentDirCard(onClick = {
                                        val parent = currentSubDir.substringBeforeLast('/', "")
                                        currentSubDir = parent
                                        refreshList()
                                    })
                                }
                            }
                            if (files.isEmpty()) {
                                item(key = "__empty__") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("此目录为空",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                            items(files, key = { it.relativePath }) { entry ->
                                ConfigFileCard(
                                    entry = entry,
                                    dateFmt = dateFmt,
                                    onClick = {
                                        if (entry.isDirectory) {
                                            currentSubDir = entry.relativePath
                                            refreshList()
                                        } else {
                                            openFile(entry)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigFileCard(
    entry: ConfigFileManager.ConfigFileEntry,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.fileName + if (entry.isDirectory) "/" else "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val info = buildString {
                    if (entry.isDirectory) {
                        append("目录")
                    } else {
                        append(entry.format.uppercase(Locale.ROOT))
                        append("  ${ConfigFileManager.formatSize(entry.size)}")
                    }
                    append("  ${dateFmt.format(Date(entry.lastModified))}")
                }
                Text(info, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentDirCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.size(12.dp))
            Text("..", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }
}
