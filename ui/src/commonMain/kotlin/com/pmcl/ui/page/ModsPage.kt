package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.mods.ModConflictChecker
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel

@Composable
fun ModsPage(vm: LauncherViewModel) {
    val installedMods by vm.installedMods.collectAsState()
    val conflicts by vm.modConflicts.collectAsState()
    val status by vm.status.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshInstalledMods() }

    val filteredMods = remember(installedMods, query) {
        if (query.isBlank()) installedMods
        else installedMods.filter {
            it.getName().contains(query, ignoreCase = true) ||
            it.getModId().contains(query, ignoreCase = true) ||
            it.getLoader().contains(query, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模组管理", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // 打开 mods 目录
            OutlinedButton(onClick = { vm.openModsDir() }) {
                Text("打开目录")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = vm::refreshInstalledMods) {
                Icon(Icons.Filled.Refresh, contentDescription = null,
                     modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("目录：${vm.config.getWorkDir().resolve("mods")}",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        // === 搜索框 ===
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索模组名 / ID / 加载器…") },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(16.dp))

        // 冲突报告
        if (conflicts != null && conflicts!!.hasIssues()) {
            ConflictCard(conflicts!!)
            Spacer(Modifier.height(12.dp))
        }

        // 已安装列表
        val enabledCount = installedMods.count { !it.isDisabled() }
        val disabledCount = installedMods.size - enabledCount
        Text("已安装（${installedMods.size}：启用 $enabledCount / 禁用 $disabledCount）" +
             if (query.isNotBlank() && filteredMods.size != installedMods.size)
                 " · 搜索结果 ${filteredMods.size}" else "",
             style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (filteredMods.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (installedMods.isEmpty()) "mods 目录为空，前往「市场」下载模组"
                         else "无匹配结果",
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(filteredMods, key = { _, m -> m.getJarFile() }) { index, m ->
                    StaggeredAppear(index) {
                        ModRow(m, vm)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("状态：$status", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ModRow(m: com.pmcl.core.mods.ModMeta, vm: LauncherViewModel) {
    // 删除确认对话框
    var showDeleteDialog by remember { mutableStateOf(false) }

    Surface(
        color = if (m.isDisabled()) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.getName() + if (m.isDisabled()) "（已禁用）" else "",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    color = if (m.isDisabled()) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface
                )
                // loader 标签
                AssistChip(onClick = {}, label = { Text(m.getLoader()) })
                Spacer(Modifier.width(8.dp))
                Text("v${m.getVersion()}", style = MaterialTheme.typography.labelSmall)
            }
            if (m.getDescription().isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(m.getDescription(),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 2)
            }
            Spacer(Modifier.height(4.dp))
            Text("${m.getJarFile()}  ·  ${m.getModId()}",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            if (m.getAuthors().isNotEmpty()) {
                Text("作者：${m.getAuthors()}",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }

            // 操作按钮
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.isDisabled()) {
                    // 启用按钮
                    TextButton(onClick = { vm.enableMod(m.getJarFile()) }) {
                        Text("▶")
                        Spacer(Modifier.width(4.dp))
                        Text("启用")
                    }
                } else {
                    // 禁用按钮
                    TextButton(onClick = { vm.disableMod(m.getJarFile()) }) {
                        Text("⏸")
                        Spacer(Modifier.width(4.dp))
                        Text("禁用")
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 删除按钮
                TextButton(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null,
                         modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除模组") },
            text = { Text("确定要删除 ${m.getName()} 吗？\n文件：${m.getJarFile()}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteMod(m.getJarFile())
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
private fun ConflictCard(result: ModConflictChecker.Result) {
    val hasErrors = result.getErrors().isNotEmpty()
    val colors = if (hasErrors) MaterialTheme.colorScheme.errorContainer
                 else MaterialTheme.colorScheme.tertiaryContainer
    Surface(color = colors, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (hasErrors) "存在 ${result.getErrors().size} 个潜在问题（仅供参考，不阻断启动）"
                else "存在 ${result.getWarnings().size} 个警告",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            for (e in result.getErrors()) {
                Text("• $e", style = MaterialTheme.typography.bodySmall)
            }
            for (w in result.getWarnings()) {
                Text("• $w", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
