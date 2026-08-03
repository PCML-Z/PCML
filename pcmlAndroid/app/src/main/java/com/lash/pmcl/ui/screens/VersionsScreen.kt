package com.lash.pmcl.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.download.DownloadQueueState
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.modloader.ModLoader
import com.lash.pmcl.core.modloader.ModLoaderManager
import com.lash.pmcl.core.modloader.ModLoaderVersion
import com.lash.pmcl.core.version.McVersion
import com.lash.pmcl.core.version.VersionManager
import java.util.function.Consumer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(
    versionManager: VersionManager,
    versionInstaller: VersionInstaller,
    modLoaderManager: ModLoaderManager,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    var versions by remember { mutableStateOf<List<McVersion>>(emptyList()) }
    var modLoaderVersions by remember { mutableStateOf<List<ModLoaderVersion>>(emptyList()) }
    var localVersionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<InstallProgress?>(null) }
    var status by remember { mutableStateOf("就绪") }
    var currentQueueId by remember { mutableStateOf<String?>(null) }

    var loadingVersions by remember { mutableStateOf(false) }
    var loadingLoaders by remember { mutableStateOf(false) }

    // 0=Vanilla 1=Fabric 2=Quilt 3=Forge 4=NeoForge
    var tab by remember { mutableStateOf(0) }
    var selectedGameVersion by remember { mutableStateOf("1.20.4") }
    var selectedLoaderVersion by remember { mutableStateOf<String?>(null) }
    // 0=全部 1=正式版 2=快照 3=旧版Beta 4=旧版Alpha
    var versionCategory by remember { mutableStateOf(1) }
    var searchQuery by remember { mutableStateOf("") }
    var filterExpanded by remember { mutableStateOf(false) }

    val onProgress = Consumer<InstallProgress> { p ->
        progress = p
        // 同步更新 DownloadQueueState（悬浮下载队列/FloatingDownloadQueue 需要）
        currentQueueId?.let { DownloadQueueState.progress(it, p.completed) }
        when (p.stage) {
            InstallProgress.Stage.DONE -> {
                installing = false
                status = p.message
            }
            InstallProgress.Stage.FAILED -> {
                installing = false
                status = "安装失败: ${p.message}"
            }
            else -> status = p.message
        }
    }

    fun refreshVersions() {
        loadingVersions = true
        status = "正在获取版本清单..."
        // 先扫描本地版本，确保 UI 能显示已有安装
        try { localVersionIds = versionManager.scanLocalVersions().map { it.id }.toSet() } catch (_: Exception) {}
        versionManager.fetchRemoteVersions()
            .thenAccept { list ->
                versions = list
                loadingVersions = false
                status = "已加载 ${list.size} 个版本"
            }
            .exceptionally { e ->
                loadingVersions = false
                status = "获取版本失败: ${e.message ?: e.toString()}"
                null
            }
    }

    fun listModLoaderVersions(loader: ModLoader, gameVersion: String) {
        loadingLoaders = true
        modLoaderVersions = emptyList()
        status = "正在获取 ${loader.displayName} 版本..."
        modLoaderManager.get(loader).listVersions(gameVersion)
            .thenAccept { list ->
                modLoaderVersions = list
                loadingLoaders = false
                status = "已加载 ${list.size} 个 ${loader.displayName} 版本"
            }
            .exceptionally { e ->
                loadingLoaders = false
                status = "获取加载器版本失败: ${e.message ?: e.toString()}"
                null
            }
    }

    fun installVanilla(versionId: String) {
        if (installing) return
        installing = true
        progress = null
        status = "正在安装 $versionId ..."
        val queueId = DownloadQueueState.register("Minecraft $versionId", 300_000_000L)
        currentQueueId = queueId
        versionInstaller.install(versionId, onProgress)
            .whenComplete { _, err ->
                installing = false
                currentQueueId = null
                if (err != null) {
                    status = "安装失败: ${err.cause?.message ?: err.message}"
                    DownloadQueueState.error(queueId, (err.cause?.message ?: err.message) ?: "未知错误")
                } else {
                    status = "安装完成"
                    DownloadQueueState.complete(queueId)
                    // 刷新本地版本集合
                    try {
                        localVersionIds = versionManager.scanLocalVersions().map { it.id }.toSet()
                    } catch (_: Exception) {}
                }
            }
    }

    fun installLoader(loader: ModLoader, gameVersion: String, loaderVersion: String) {
        if (installing) return
        installing = true
        progress = null
        status = "正在安装 ${loader.displayName} $loaderVersion ..."
        val queueId = DownloadQueueState.register("${loader.displayName} $gameVersion-$loaderVersion", 200_000_000L)
        currentQueueId = queueId
        modLoaderManager.get(loader).install(gameVersion, loaderVersion, onProgress)
            .whenComplete { _, err ->
                installing = false
                currentQueueId = null
                if (err != null) {
                    status = "安装失败: ${err.cause?.message ?: err.message}"
                    DownloadQueueState.error(queueId, (err.cause?.message ?: err.message) ?: "未知错误")
                } else {
                    status = "安装完成"
                    DownloadQueueState.complete(queueId)
                    // 刷新本地版本集合
                    try {
                        localVersionIds = versionManager.scanLocalVersions().map { it.id }.toSet()
                    } catch (_: Exception) {}
                }
            }
    }

    LaunchedEffect(Unit) { if (versions.isEmpty()) refreshVersions() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
        ) {
            // 折叠式标题栏（节省横屏空间）
            val loaderNames = listOf("Vanilla", "Fabric", "Quilt", "Forge", "NeoForge", "OptiFine")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${loaderNames[tab]} | $status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                TextButton(onClick = { filterExpanded = !filterExpanded }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (filterExpanded) "收起" else "筛选", style = MaterialTheme.typography.labelMedium)
                    Icon(if (filterExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
                }
            }
            AnimatedVisibility(filterExpanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
            TabRow(selectedTabIndex = tab) {
                loaderNames.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = {
                        tab = i
                        selectedLoaderVersion = null
                        if (i > 0) {
                            listModLoaderVersions(tabToLoader(i), selectedGameVersion)
                        }
                    }) { Text(label, Modifier.padding(12.dp)) }
                }
            }

            // 搜索和筛选（仅 Vanilla 标签）
            if (tab == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("全部", "正式版", "快照", "旧版Beta", "旧版Alpha").forEachIndexed { i, label ->
                        FilterChip(selected = versionCategory == i, onClick = { versionCategory = i }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    label = { Text("搜索版本") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
            }

            // 安装进度卡片
            if (installing && progress != null) {
                val p = progress!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            p.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (p.percent() / 100.0).toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 版本输入 + 获取按钮 + 状态文本
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = selectedGameVersion,
                    onValueChange = { selectedGameVersion = it },
                    label = { Text("游戏版本") },
                    singleLine = true,
                    modifier = Modifier.width(160.dp),
                )
                Spacer(Modifier.width(12.dp))
                if (tab > 0) {
                    Button(onClick = {
                        listModLoaderVersions(tabToLoader(tab), selectedGameVersion)
                    }) { Text("获取版本") }
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

                } // 关闭内部 Column
            } // 关闭 AnimatedVisibility

            Spacer(Modifier.height(8.dp))

            if (tab == 0) {
                VanillaTab(
                    versions = versions,
                    localVersionIds = localVersionIds,
                    loadingVersions = loadingVersions,
                    versionCategory = versionCategory,
                    onCategoryChange = { versionCategory = it },
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    installing = installing,
                    onInstall = { installVanilla(it) },
                )
            } else {
                LoaderTab(
                    modLoaderVersions = modLoaderVersions,
                    loadingLoaders = loadingLoaders,
                    selectedLoaderVersion = selectedLoaderVersion,
                    installing = installing,
                    loader = tabToLoader(tab),
                    onSelect = { selectedLoaderVersion = it },
                    onInstall = { lv ->
                        installLoader(tabToLoader(tab), lv.gameVersion, lv.loaderVersion)
                    },
                )
            }
        }
    }

@Composable
private fun VanillaTab(
    versions: List<McVersion>,
    localVersionIds: Set<String>,
    loadingVersions: Boolean,
    versionCategory: Int,
    onCategoryChange: (Int) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    installing: Boolean,
    onInstall: (String) -> Unit,
) {

    val filtered = remember(versions, versionCategory, searchQuery) {
        var list = versions
        if (versionCategory != 0) {
            val typeFilter = when (versionCategory) {
                1 -> "release"
                2 -> "snapshot"
                3 -> "old_beta"
                else -> "old_alpha"
            }
            list = list.filter { it.type == typeFilter }
        }
        if (searchQuery.isNotEmpty()) {
            list = list.filter { it.id.contains(searchQuery, ignoreCase = true) }
        }
        list
    }

    Text(
        "共 ${versions.size} 条，筛选后 ${filtered.size} 条",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    when {
        loadingVersions -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        filtered.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("暂无版本", color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        else -> {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(filtered, key = { it.id }) { v ->
                    VanillaRow(
                        version = v,
                        installed = v.id in localVersionIds,
                        installing = installing,
                        onInstall = { onInstall(v.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoaderTab(
    modLoaderVersions: List<ModLoaderVersion>,
    loadingLoaders: Boolean,
    selectedLoaderVersion: String?,
    installing: Boolean,
    loader: ModLoader,
    onSelect: (String) -> Unit,
    onInstall: (ModLoaderVersion) -> Unit,
) {
    when {
        loadingLoaders -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        modLoaderVersions.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "暂无 ${loader.displayName} 版本，请点击「获取版本」",
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        else -> {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(modLoaderVersions, key = { it.loaderVersion }) { lv ->
                    LoaderRow(
                        version = lv,
                        selected = lv.loaderVersion == selectedLoaderVersion,
                        installing = installing,
                        onSelect = { onSelect(lv.loaderVersion) },
                        onInstall = { onInstall(lv) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VanillaRow(
    version: McVersion,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (installed) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = version.id,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeBadge(version.type)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = version.releaseTime.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (installed) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "✓ 已安装",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Button(onClick = onInstall, enabled = !installing && !installed) {
                Text(if (installed) "已安装" else "安装")
            }
        }
    }
}

@Composable
private fun LoaderRow(
    version: ModLoaderVersion,
    selected: Boolean,
    installing: Boolean,
    onSelect: () -> Unit,
    onInstall: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = version.loaderVersion,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MC ${version.gameVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(8.dp))
                    StabilityBadge(version.stable)
                }
            }
            Button(onClick = onInstall, enabled = !installing) {
                Text("安装")
            }
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val isRelease = type == "release"
    val container = if (isRelease) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (isRelease) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(4.dp), color = container) {
        Text(
            typeLabel(type),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StabilityBadge(stable: Boolean) {
    val container = if (stable) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (stable) MaterialTheme.colorScheme.onSecondaryContainer
                  else MaterialTheme.colorScheme.onTertiaryContainer
    val text = if (stable) "稳定" else "不稳定"
    Surface(shape = RoundedCornerShape(4.dp), color = container) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun tabToLoader(tab: Int): ModLoader = when (tab) {
    1 -> ModLoader.FABRIC
    2 -> ModLoader.QUILT
    3 -> ModLoader.FORGE
    4 -> ModLoader.NEOFORGE
    5 -> ModLoader.OPTIFINE
    else -> ModLoader.NEOFORGE
}

private fun typeLabel(type: String): String = when (type) {
    "release" -> "正式版"
    "snapshot" -> "快照"
    "old_beta" -> "旧版Beta"
    "old_alpha" -> "旧版Alpha"
    "experiment" -> "实验性"
    else -> type
}
