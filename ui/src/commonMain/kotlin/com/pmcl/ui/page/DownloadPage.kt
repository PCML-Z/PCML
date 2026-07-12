package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.modloader.ModLoader
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.viewmodel.LauncherViewModel

@Composable
fun DownloadPage(vm: LauncherViewModel) {
    val versions by vm.versions.collectAsState()
    val installing by vm.installing.collectAsState()
    val progress by vm.installProgress.collectAsState()
    val status by vm.status.collectAsState()
    val modLoaderVersions by vm.modLoaderVersions.collectAsState()

    var tab by remember { mutableStateOf(0) } // 0=Vanilla 1=Fabric 2=Quilt 3=Forge 4=NeoForge
    var selectedGameVersion by remember { mutableStateOf("1.20.4") }
    var selectedLoaderVersion by remember { mutableStateOf<String?>(null) }
    // 版本分类筛选：0=全部 1=正式版 2=快照 3=旧版Beta 4=旧版Alpha
    var versionCategory by remember { mutableStateOf(1) }
    // 版本搜索关键字
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (versions.isEmpty()) vm.refreshVersions() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("下载与安装", style = MaterialTheme.typography.headlineSmall,
             fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // Tab 选择
        TabRow(selectedTabIndex = tab) {
            listOf("Vanilla", "Fabric", "Quilt", "Forge", "NeoForge").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = {
                    tab = i
                    selectedLoaderVersion = null
                    if (i > 0) {
                        val loader = when (i) {
                            1 -> ModLoader.FABRIC
                            2 -> ModLoader.QUILT
                            3 -> ModLoader.FORGE
                            else -> ModLoader.NEOFORGE
                        }
                        vm.listModLoaderVersions(loader, selectedGameVersion)
                    }
                }) { Text(label, Modifier.padding(12.dp)) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 进度条
        if (installing && progress != null) {
            val p = progress ?: return@Column
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.getMessage() ?: "", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (p.percent() / 100f).toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 版本输入
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = selectedGameVersion,
                onValueChange = { selectedGameVersion = it },
                label = { Text("MC 版本") },
                singleLine = true,
                modifier = Modifier.width(180.dp)
            )
            Spacer(Modifier.width(12.dp))
            if (tab > 0) {
                Button(onClick = {
                    val loader = when (tab) {
                        1 -> ModLoader.FABRIC
                        2 -> ModLoader.QUILT
                        3 -> ModLoader.FORGE
                        4 -> ModLoader.NEOFORGE
                        else -> ModLoader.FORGE
                    }
                    vm.listModLoaderVersions(loader, selectedGameVersion)
                }) { Text("拉取版本") }
            }
            Spacer(Modifier.weight(1f))
            Text("状态：$status", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }

        Spacer(Modifier.height(12.dp))

        // 列表
        if (tab == 0) {
            // Vanilla 列表：分类筛选 + 搜索
            // 分类滑动选择器
            val categories = listOf("全部", "正式版", "快照", "旧版Beta", "旧版Alpha")
            com.pmcl.ui.animation.AnimatedSegmentedSelector(
                items = categories,
                selectedIndex = versionCategory,
                onSelect = { versionCategory = it },
                fillWidth = true,
                height = 32.dp
            )

            Spacer(Modifier.height(8.dp))

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("搜索版本号（如 1.20.4）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // 过滤后的版本列表
            val filtered = remember(versions, versionCategory, searchQuery) {
                var list = versions
                if (versionCategory != 0) {
                    val typeFilter = when (versionCategory) {
                        1 -> "release"
                        2 -> "snapshot"
                        3 -> "old_beta"
                        else -> "old_alpha"
                    }
                    list = list.filter { it.getType() == typeFilter }
                }
                if (searchQuery.isNotEmpty()) {
                    list = list.filter { (it.getId() ?: "").contains(searchQuery, ignoreCase = true) }
                }
                list
            }

            // 分类计数提示
            Text(
                "共 ${filtered.size} 个版本" +
                    if (versionCategory != 0) "（已筛选）" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(filtered, key = { _, v -> v.getId() }) { index, v ->
                    StaggeredAppear(index) {
                    DownloadRow(
                        title = v.getId(),
                        subtitle = "${typeLabel(v.getType())} · ${(v.getReleaseTime() ?: "").take(10)}",
                        buttonText = "安装",
                        installing = installing,
                        onAction = { vm.enqueueVersionInstall(v.getId()) }
                    )
                    }
                }
            }
        } else {
            // 加载器列表
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(modLoaderVersions, key = { _, lv -> lv.getLoaderVersion() }) { index, lv ->
                    StaggeredAppear(index) {
                    DownloadRow(
                        title = lv.getLoaderVersion(),
                        subtitle = "MC ${lv.getGameVersion()} · ${if (lv.isStable()) "稳定" else "不稳定"}",
                        buttonText = "安装",
                        installing = installing,
                        selected = lv.getLoaderVersion() == selectedLoaderVersion,
                        onSelect = { selectedLoaderVersion = lv.getLoaderVersion() },
                        onAction = {
                            val loader = when (tab) {
                                1 -> ModLoader.FABRIC
                                2 -> ModLoader.QUILT
                                3 -> ModLoader.FORGE
                                else -> ModLoader.NEOFORGE
                            }
                            vm.enqueueModLoaderInstall(loader.name, lv.getGameVersion(), lv.getLoaderVersion())
                        }
                    )
                    }
                }
            }
        }
    }
}

/** Mojang 版本 type 字段转中文标签 */
private fun typeLabel(type: String): String = when (type) {
    "release" -> "正式版"
    "snapshot" -> "快照"
    "old_beta" -> "旧版Beta"
    "old_alpha" -> "旧版Alpha"
    "experiment" -> "实验性"
    else -> type
}

@Composable
private fun DownloadRow(
    title: String,
    subtitle: String,
    buttonText: String,
    installing: Boolean,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    onAction: () -> Unit
) {
    val colors = if (selected) MaterialTheme.colorScheme.primaryContainer
                 else MaterialTheme.colorScheme.surfaceVariant
    Surface(onClick = onSelect, color = colors, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onAction, enabled = !installing) {
                Text(buttonText)
            }
        }
    }
}
