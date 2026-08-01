package com.lash.pmcl.ui.screens

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
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<InstallProgress?>(null) }
    var status by remember { mutableStateOf("就绪") }

    var loadingVersions by remember { mutableStateOf(false) }
    var loadingLoaders by remember { mutableStateOf(false) }

    // 0=Vanilla 1=Fabric 2=Quilt 3=Forge 4=NeoForge
    var tab by remember { mutableStateOf(0) }
    var selectedGameVersion by remember { mutableStateOf("1.20.4") }
    var selectedLoaderVersion by remember { mutableStateOf<String?>(null) }
    // 0=全部 1=正式版 2=快照 3=旧版Beta 4=旧版Alpha
    var versionCategory by remember { mutableStateOf(1) }
    var searchQuery by remember { mutableStateOf("") }

    val onProgress = Consumer<InstallProgress> { p ->
        progress = p
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
        versionInstaller.install(versionId, onProgress)
            .exceptionally { e ->
                installing = false
                status = "安装失败: ${e.message ?: e.toString()}"
                null
            }
    }

    fun installLoader(loader: ModLoader, gameVersion: String, loaderVersion: String) {
        if (installing) return
        installing = true
        progress = null
        status = "正在安装 ${loader.displayName} $loaderVersion ..."
        modLoaderManager.get(loader).install(gameVersion, loaderVersion, onProgress)
            .exceptionally { e ->
                installing = false
                status = "安装失败: ${e.message ?: e.toString()}"
                null
            }
    }

    LaunchedEffect(Unit) { if (versions.isEmpty()) refreshVersions() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("下载版本") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(16.dp)
        ) {
            TabRow(selectedTabIndex = tab) {
                listOf("Vanilla", "Fabric", "Quilt", "Forge", "NeoForge").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = {
                        tab = i
                        selectedLoaderVersion = null
                        if (i > 0) {
                            listModLoaderVersions(tabToLoader(i), selectedGameVersion)
                        }
                    }) { Text(label, Modifier.padding(12.dp)) }
                }
            }

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                VanillaTab(
                    versions = versions,
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
}

@Composable
private fun VanillaTab(
    versions: List<McVersion>,
    loadingVersions: Boolean,
    versionCategory: Int,
    onCategoryChange: (Int) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    installing: Boolean,
    onInstall: (String) -> Unit,
) {
    val categories = listOf("全部", "正式版", "快照", "旧版Beta", "旧版Alpha")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEachIndexed { i, label ->
            FilterChip(
                selected = versionCategory == i,
                onClick = { onCategoryChange(i) },
                label = { Text(label) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        label = { Text("搜索版本") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(8.dp))

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
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                }
            }
            Button(onClick = onInstall, enabled = !installing) {
                Text("安装")
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
