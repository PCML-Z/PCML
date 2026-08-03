package com.lash.pmcl.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.mods.ModDropInfo
import com.lash.pmcl.core.mods.ModDropInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * 模组导入对话框。
 * Android 版：输入文件路径导入 JAR/ZIP 模组。
 */
@Composable
fun ModDropDialog(
    installer: ModDropInstaller,
    versionId: String?,
    gameVersion: String?,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<ModDropInfo?>(null) }

    fun analyze() {
        if (path.isBlank()) return
        analyzing = true
        status = "分析中..."
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { java.io.File(path).toPath() }
                if (!java.io.File(path).exists()) {
                    status = "文件不存在: $path"; analyzing = false; return@launch
                }
                val results = withContext(Dispatchers.IO) { installer.analyze(listOf(file)) }
                lastResult = results.firstOrNull()
                if (results.isEmpty()) {
                    status = "无法识别该文件"; analyzing = false
                } else {
                    val r = results.first()
                    status = "识别成功: ${r.name} ${r.version ?: ""}"
                    analyzing = false
                }
            } catch (e: Exception) {
                status = "分析失败: ${e.message}"; analyzing = false
            }
        }
    }

    fun install() {
        val r = lastResult ?: return
        installing = true
        status = "安装中..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) { installer.installTo(r, versionId, gameVersion) }
                status = "安装成功"; installing = false
                onInstalled()
            } catch (e: Exception) {
                status = "安装失败: ${e.message}"; installing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入模组") },
        text = {
            Column {
                Text("输入模组 JAR/ZIP 文件的完整路径（如 /sdcard/Download/mymod.jar）",
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path, onValueChange = { path = it; lastResult = null },
                    label = { Text("文件路径") }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    trailingIcon = {
                        IconButton(onClick = { analyze() }) {
                            Icon(Icons.Filled.FolderOpen, "选择文件", Modifier.size(20.dp))
                        }
                    }
                )
                if (lastResult != null) {
                    Spacer(Modifier.height(8.dp))
                    val r = lastResult!!
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(r.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                 style = MaterialTheme.typography.bodyMedium)
                            if (r.version != null) {
                                Text("版本: ${r.version}", style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline)
                            }
                            if (r.gameVersions.isNotEmpty()) {
                                Text("兼容: ${r.gameVersions.joinToString(", ")}",
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(status, style = MaterialTheme.typography.labelSmall,
                         color = if (analyzing || installing) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.outline)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { install() },
                enabled = lastResult != null && !installing
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
