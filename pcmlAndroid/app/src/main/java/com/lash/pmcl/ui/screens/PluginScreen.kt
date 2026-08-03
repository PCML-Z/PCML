package com.lash.pmcl.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PluginInfo(val name: String, val ext: String, val size: Long)

@Composable
fun PluginScreen() {
    val scope = rememberCoroutineScope()
    var plugins by remember { mutableStateOf<List<PluginInfo>>(emptyList()) }
    var status by remember { mutableStateOf("就绪") }
    var loading by remember { mutableStateOf(true) }

    fun scan() {
        scope.launch {
            loading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val list = mutableListOf<PluginInfo>()
                    val base = java.io.File("/sdcard/PMCL/plugins")
                    if (base.isDirectory) {
                        base.listFiles()?.filter { it.extension in listOf("jar", "zip") }?.forEach { f ->
                            list.add(PluginInfo(f.nameWithoutExtension, f.extension, f.length()))
                        }
                    }
                    val versions = java.io.File("/sdcard/PMCL/versions")
                    if (versions.isDirectory) {
                        versions.listFiles()?.filter { it.isDirectory }?.forEach { v ->
                            val pd = java.io.File(v, "plugins")
                            if (pd.isDirectory) pd.listFiles()?.filter { it.extension == "jar" }
                                ?.forEach { f -> list.add(PluginInfo(f.nameWithoutExtension + " [" + v.name + "]", f.extension, f.length())) }
                        }
                    }
                    list
                }
                plugins = result
                status = if (result.isEmpty()) "未找到插件" else "已找到 ${result.size} 个插件"
            } catch (e: Exception) { status = "扫描失败: ${e.message}" }
            finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { scan() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("插件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { scan() }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("刷新")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("扫描 /sdcard/PMCL/plugins 和版本插件目录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (plugins.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Build, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("未找到插件", color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    Text("将 .jar 文件放入 /sdcard/PMCL/plugins 目录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            Text("${plugins.size} 个插件", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(plugins, key = { it.name + it.ext }) { p ->
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Build, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fmt(p.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(p.ext.uppercase(), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

private fun fmt(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
}
