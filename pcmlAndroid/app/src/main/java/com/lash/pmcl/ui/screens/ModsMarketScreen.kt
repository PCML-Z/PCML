package com.lash.pmcl.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.core.market.ModFile
import com.lash.pmcl.core.market.ModProject
import com.lash.pmcl.core.mods.ModDependencyResolver
import com.lash.pmcl.core.mods.ModScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.function.Consumer

/**
 * 模组市场界面 — Android 版，对齐桌面版 ModsMarketPage。
 *
 * 列表视图：搜索栏 + 分类标签 + 搜索结果/分类推荐/热门推荐三种视图。
 * 详情视图：项目信息 + 筛选 + 文件列表（下载/带依赖安装）。
 *
 * 直接使用 [LauncherCore] 的 manager，不依赖 LauncherViewModel。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsMarketScreen(core: LauncherCore) {
    val modMarketManager = core.modMarketManager
    val modDependencyResolver = core.modDependencyResolver
    val modManager = core.modManager
    val preferences = core.preferences
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var gameVersion by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }
    var searchedQuery by remember { mutableStateOf("") }

    var results by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var popularMods by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var categoryResults by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var popularLoading by remember { mutableStateOf(false) }
    var categoryLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var installedModIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentVersionId by remember { mutableStateOf("") }

    var detailProject by remember { mutableStateOf<ModProject?>(null) }
    var currentModFiles by remember { mutableStateOf<List<ModFile>>(emptyList()) }
    var filesLoading by remember { mutableStateOf(false) }

    var depResult by remember { mutableStateOf<ModDependencyResolver.DependencyResult?>(null) }
    var installingDeps by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    fun openUrl(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun refreshInstalled() {
        scope.launch {
            val ids = withContext(Dispatchers.IO) {
                try {
                    ModScanner.scanDirectory(modManager.modsDir)
                        .map { it.modId }
                        .filter { it.isNotEmpty() }
                        .toSet()
                } catch (_: Exception) {
                    emptySet()
                }
            }
            installedModIds = ids
        }
    }

    fun doSearch(q: String, gv: String, ld: String, cat: String) {
        if (q.isBlank()) return
        loading = true
        status = "正在搜索..."
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    modMarketManager.search(q, gv, ld, cat, 40).join()
                } catch (e: Exception) {
                    status = "搜索失败: ${e.message ?: e}"
                    emptyList()
                }
            }
            results = res
            searchedQuery = q
            loading = false
            status = if (res.isEmpty()) "未找到匹配的模组" else "找到 ${res.size} 个结果"
        }
    }

    fun loadPopular(gv: String, ld: String) {
        popularLoading = true
        status = "正在加载热门推荐..."
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    modMarketManager.popular(gv, ld, 40).join()
                } catch (e: Exception) {
                    status = "加载失败: ${e.message ?: e}"
                    emptyList()
                }
            }
            popularMods = res
            popularLoading = false
            status = if (res.isEmpty()) "暂无热门推荐" else "已加载 ${res.size} 个热门模组"
        }
    }

    fun loadCategory(cat: String, gv: String, ld: String) {
        if (cat.isEmpty()) return
        categoryLoading = true
        status = "正在加载分类..."
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    modMarketManager.searchByCategory(cat, gv, ld, 40).join()
                } catch (e: Exception) {
                    status = "加载失败: ${e.message ?: e}"
                    emptyList()
                }
            }
            categoryResults = res
            categoryLoading = false
            status = if (res.isEmpty()) "该分类暂无模组" else "已加载 ${res.size} 个模组"
        }
    }

    fun openDetail(project: ModProject) {
        detailProject = project
        currentModFiles = emptyList()
        filesLoading = true
        status = "正在加载版本列表..."
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                try {
                    modMarketManager.listFiles(project).join()
                } catch (e: Exception) {
                    status = "加载版本失败: ${e.message ?: e}"
                    emptyList()
                }
            }
            currentModFiles = files
            filesLoading = false
            status = "共 ${files.size} 个版本文件"
        }
    }

    fun refreshFiles(project: ModProject) {
        currentModFiles = emptyList()
        filesLoading = true
        status = "正在刷新版本列表..."
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                try {
                    modMarketManager.listFiles(project).join()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            currentModFiles = files
            filesLoading = false
            status = "共 ${files.size} 个版本文件"
        }
    }

    fun installMod(file: ModFile, gv: String) {
        if (downloading) return
        downloading = true
        status = "正在下载: ${file.fileName}"
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    modMarketManager.installMod(
                        file, gv, Consumer { s -> status = s }
                    ).join()
                    status = "下载完成: ${file.fileName}"
                } catch (e: Exception) {
                    status = "下载失败: ${e.message ?: e}"
                }
            }
            downloading = false
            refreshInstalled()
        }
    }

    fun installWithDeps(file: ModFile, gv: String) {
        if (installingDeps) return
        installingDeps = true
        status = "正在安装(含依赖): ${file.fileName}"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    modDependencyResolver.installWithDependencies(
                        file, gv.ifBlank { null }, null,
                        Consumer { s -> status = s }
                    ).join()
                } catch (e: Exception) {
                    status = "安装失败: ${e.message ?: e}"
                    null
                }
            }
            installingDeps = false
            if (result != null) {
                depResult = result
                status = result.summary()
            }
            refreshInstalled()
        }
    }

    // 初始化：加载当前选中版本 ID + 已安装模组 + 热门推荐
    LaunchedEffect(Unit) {
        currentVersionId = withContext(Dispatchers.IO) {
            try { preferences.getLastSelectedVersion() } catch (_: Exception) { "" }
        }
        refreshInstalled()
        loadPopular("", "")
    }

    // 筛选变化时自动刷新当前视图（防抖，避免版本输入框逐字请求）
    LaunchedEffect(gameVersion, loader, selectedCategory) {
        delay(350)
        when {
            searchedQuery.isNotBlank() && results.isNotEmpty() ->
                doSearch(searchedQuery, gameVersion, loader, selectedCategory)
            selectedCategory.isNotEmpty() ->
                loadCategory(selectedCategory, gameVersion, loader)
            else -> loadPopular(gameVersion, loader)
        }
    }

    val dp = detailProject
    if (dp != null) {
        ModDetailView(
            project = dp,
            files = currentModFiles,
            filesLoading = filesLoading,
            searchGameVersion = gameVersion,
            searchLoader = loader,
            currentVersionId = currentVersionId,
            downloading = downloading,
            installingDeps = installingDeps,
            onBack = { detailProject = null },
            onRefreshFiles = { refreshFiles(dp) },
            onInstall = ::installMod,
            onInstallWithDeps = ::installWithDeps,
            onOpenUrl = ::openUrl,
        )
    } else {
        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("模组市场") },
                    actions = {
                        IconButton(onClick = { refreshInstalled() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新已安装")
                        }
                    },
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
                Text(
                    "聚合 Modrinth / CurseForge，搜索或浏览模组",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))

                // 搜索栏：输入框（回车触发）+ 搜索按钮
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索模组") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (query.isNotBlank() && !loading) {
                                doSearch(query, gameVersion, loader, selectedCategory)
                            }
                        }
                    ),
                    trailingIcon = {
                        IconButton(onClick = {
                            if (query.isNotBlank() && !loading) {
                                doSearch(query, gameVersion, loader, selectedCategory)
                            }
                        }) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索")
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))

                // 游戏版本过滤 + 加载器下拉 + 使用当前实例
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = gameVersion,
                        onValueChange = { gameVersion = it },
                        label = { Text("游戏版本") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("全部") },
                    )
                    LoaderDropdown(
                        selected = loader,
                        onSelect = { loader = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (currentVersionId.isNotEmpty()) {
                                gameVersion = currentVersionId
                            }
                        },
                        enabled = currentVersionId.isNotEmpty(),
                    ) { Text("使用当前实例") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            doSearch(query, gameVersion, loader, selectedCategory)
                        },
                        enabled = !loading && query.isNotBlank(),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (loading) "搜索中" else "搜索")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 分类标签栏（横向滚动）
                CategoryBar(
                    selectedCategory = selectedCategory,
                    onSelect = { cat ->
                        selectedCategory = cat
                        if (cat.isEmpty()) {
                            categoryResults = emptyList()
                            loadPopular(gameVersion, loader)
                        } else {
                            loadCategory(cat, gameVersion, loader)
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))

                // 三种视图
                when {
                    // 搜索结果视图（用户主动搜索后）
                    searchedQuery.isNotBlank() -> {
                        Text(
                            "搜索结果（${results.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        when {
                            loading -> Box(
                                Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }
                            results.isEmpty() -> EmptyHint("未找到匹配的模组")
                            else -> LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                items(
                                    results,
                                    key = { p -> p.source + "/" + p.id },
                                ) { project ->
                                    SearchResultCard(
                                        project = project,
                                        onClick = { openDetail(project) },
                                        installedModIds = installedModIds,
                                    )
                                }
                            }
                        }
                    }
                    // 分类推荐网格
                    selectedCategory.isNotEmpty() -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "「${categoryLabel(selectedCategory)}」推荐",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (categoryLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                )
                            } else {
                                TextButton(
                                    onClick = {
                                        loadCategory(selectedCategory, gameVersion, loader)
                                    },
                                ) { Text("刷新") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        when {
                            categoryResults.isEmpty() && !categoryLoading ->
                                EmptyHint("该分类暂无模组")
                            else -> LazyVerticalGrid(
                                columns = GridCells.Adaptive(160.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                itemsIndexed(
                                    categoryResults,
                                    key = { _, p -> p.source + "/" + p.id },
                                ) { _, project ->
                                    PopularCard(
                                        project = project,
                                        onClick = { openDetail(project) },
                                    )
                                }
                            }
                        }
                    }
                    // 热门推荐网格（默认视图）
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "热门推荐",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (popularLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                )
                            } else {
                                TextButton(
                                    onClick = { loadPopular(gameVersion, loader) },
                                ) { Text("刷新") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        when {
                            popularMods.isEmpty() && !popularLoading ->
                                EmptyHint("加载失败或暂无数据")
                            else -> LazyVerticalGrid(
                                columns = GridCells.Adaptive(160.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                itemsIndexed(
                                    popularMods,
                                    key = { _, p -> p.source + "/" + p.id },
                                ) { _, project ->
                                    PopularCard(
                                        project = project,
                                        onClick = { openDetail(project) },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }

    // 依赖安装结果对话框
    depResult?.let { result ->
        DependencyResultDialog(result = result, onDismiss = { depResult = null })
    }
}

/**
 * Mod 详情界面：左右布局。
 * 左侧（可滚动）：项目信息卡 + 筛选卡 + 下载目标版本卡。
 * 右侧：标题 + 显示全部/仅兼容切换 + 文件列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModDetailView(
    project: ModProject,
    files: List<ModFile>,
    filesLoading: Boolean,
    searchGameVersion: String,
    searchLoader: String,
    currentVersionId: String,
    downloading: Boolean,
    installingDeps: Boolean,
    onBack: () -> Unit,
    onRefreshFiles: () -> Unit,
    onInstall: (ModFile, String) -> Unit,
    onInstallWithDeps: (ModFile, String) -> Unit,
    onOpenUrl: (String?) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var filterGameVersion by remember(project.id) { mutableStateOf(searchGameVersion) }
    var filterLoader by remember(project.id) { mutableStateOf(searchLoader) }
    var targetGameVersion by remember(project.id) { mutableStateOf(searchGameVersion) }
    var showAllFiles by remember(project.id) { mutableStateOf(false) }
    var filterCompatible by remember(project.id) { mutableStateOf(true) }

    LaunchedEffect(searchGameVersion, searchLoader) {
        filterGameVersion = searchGameVersion
        filterLoader = searchLoader
        if (searchGameVersion.isNotBlank()) targetGameVersion = searchGameVersion
    }
    LaunchedEffect(filterGameVersion) {
        if (filterGameVersion.isNotBlank()) targetGameVersion = filterGameVersion
    }

    val compatibleFiles = remember(files, filterGameVersion, filterLoader, filterCompatible) {
        if (!filterCompatible) files
        else files.filter { fileMatchesMarketFilter(it, filterGameVersion, filterLoader) }
    }
    val displayFiles = if (showAllFiles) compatibleFiles else compatibleFiles.take(15)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        project.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ===== 左侧：信息 + 筛选 + 下载目标 =====
            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 项目信息卡
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Extension,
                                contentDescription = project.name,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            project.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            project.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${project.author}  ·  ${formatCount(project.downloadCount)}  ·  ${project.source}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onOpenUrl(project.websiteUrl) }) {
                                Icon(
                                    Icons.Outlined.OpenInBrowser,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("打开网页")
                            }
                            TextButton(onClick = onRefreshFiles) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("刷新版本")
                            }
                        }
                    }
                }

                // 筛选卡
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "筛选版本",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = filterGameVersion,
                            onValueChange = {
                                filterGameVersion = it
                                filterCompatible = true
                                showAllFiles = false
                            },
                            label = { Text("游戏版本") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("全部") },
                        )
                        LoaderDropdown(
                            selected = filterLoader,
                            onSelect = {
                                filterLoader = it
                                filterCompatible = true
                                showAllFiles = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                if (currentVersionId.isNotEmpty()) {
                                    filterGameVersion = currentVersionId
                                    filterCompatible = true
                                    showAllFiles = false
                                }
                            },
                            enabled = currentVersionId.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("使用当前实例") }
                        if (filterGameVersion.isNotBlank() || filterLoader.isNotBlank()) {
                            Text(
                                buildString {
                                    append("仅显示兼容")
                                    if (filterGameVersion.isNotBlank()) append(" · MC $filterGameVersion")
                                    if (filterLoader.isNotBlank()) append(" · $filterLoader")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                // 下载目标版本卡
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "下载到版本",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = targetGameVersion,
                            onValueChange = { targetGameVersion = it },
                            label = { Text("目标 MC 版本") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "将安装到 mods/$targetGameVersion 目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            // ===== 右侧：版本下载列表 =====
            Column(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (filterCompatible && (filterGameVersion.isNotBlank() || filterLoader.isNotBlank()))
                            "兼容文件 ${compatibleFiles.size}/${files.size}"
                        else
                            "版本文件（${files.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (filterGameVersion.isNotBlank() || filterLoader.isNotBlank()) {
                        TextButton(onClick = {
                            filterCompatible = !filterCompatible
                            showAllFiles = false
                        }) {
                            Text(if (filterCompatible) "显示全部" else "仅兼容")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                when {
                    filesLoading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    files.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("加载中...", color = MaterialTheme.colorScheme.outline)
                    }
                    compatibleFiles.isEmpty() -> Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("无兼容文件", color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { filterCompatible = false }) { Text("显示全部") }
                    }
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            displayFiles,
                            key = { f -> f.source + "/" + f.fileId },
                        ) { f ->
                            FileRow(
                                file = f,
                                targetGameVersion = targetGameVersion,
                                downloading = downloading,
                                installingDeps = installingDeps,
                                onInstall = onInstall,
                                onInstallWithDeps = onInstallWithDeps,
                            )
                        }
                        if (compatibleFiles.size > 15) {
                            item {
                                TextButton(onClick = { showAllFiles = !showAllFiles }) {
                                    Text(
                                        if (showAllFiles) "收起（${compatibleFiles.size}）"
                                        else "显示全部（${compatibleFiles.size}）"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索结果卡片：图标占位 + 名 + 简介 + 作者/下载量/来源 + 已安装标签。
 */
@Composable
private fun SearchResultCard(
    project: ModProject,
    onClick: () -> Unit,
    installedModIds: Set<String>,
) {
    val isInstalled = installedModIds.contains(project.slug)
            || installedModIds.contains(project.id)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        project.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isInstalled) {
                        Spacer(Modifier.width(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "已安装",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Text(
                    project.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${project.author}  ·  ${formatCount(project.downloadCount)}  ·  ${project.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 热门推荐卡片：大图标占位 + 名 + 简介 + 来源 + 下载量。
 */
@Composable
private fun PopularCard(
    project: ModProject,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = project.name,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                project.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                project.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(project.source) })
                Spacer(Modifier.width(6.dp))
                Text(
                    formatCount(project.downloadCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 文件行：文件名 + 游戏版本/加载器/发布类型/大小 + 下载按钮 + 带依赖安装按钮。
 */
@Composable
private fun FileRow(
    file: ModFile,
    targetGameVersion: String,
    downloading: Boolean,
    installingDeps: Boolean,
    onInstall: (ModFile, String) -> Unit,
    onInstallWithDeps: (ModFile, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${file.getGameVersions().joinToString(",")}" +
                        " · ${file.getLoaders().joinToString(",")}" +
                        " · ${file.releaseType}" +
                        if (file.fileSize > 0) " · ${file.fileSize / 1024}KB" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Button(
                onClick = {
                    val gv = targetGameVersion.ifBlank {
                        file.getGameVersions().firstOrNull() ?: ""
                    }
                    onInstall(file, gv)
                },
                enabled = !downloading,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                if (downloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("下载")
                }
            }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(
                onClick = {
                    val gv = targetGameVersion.ifBlank {
                        file.getGameVersions().firstOrNull() ?: ""
                    }
                    onInstallWithDeps(file, gv)
                },
                enabled = !installingDeps,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                if (installingDeps) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("带依赖")
            }
        }
    }
}

/**
 * 依赖安装结果对话框：已安装 / 跳过 / 未找到 / 失败 / 无额外依赖。
 */
@Composable
private fun DependencyResultDialog(
    result: ModDependencyResolver.DependencyResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("依赖安装结果") },
        text = {
            Column {
                Text("模组：${result.modName}", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                if (result.installedDependencies.isNotEmpty()) {
                    Text(
                        "已安装依赖（${result.installedDependencies.size}）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    result.installedDependencies.forEach { dep ->
                        Text(
                            "  + $dep",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (result.skippedInstalled.isNotEmpty()) {
                    Text(
                        "已安装跳过（${result.skippedInstalled.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    result.skippedInstalled.forEach { dep ->
                        Text(
                            "  = $dep（已安装）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (result.skippedSystem.isNotEmpty()) {
                    Text(
                        "系统依赖跳过",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "  ${result.skippedSystem.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                if (result.notFound.isNotEmpty()) {
                    Text(
                        "未找到依赖（${result.notFound.size}）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    result.notFound.forEach { dep ->
                        Text(
                            "  ? $dep",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (result.failed.isNotEmpty()) {
                    Text(
                        "失败依赖（${result.failed.size}）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    result.failed.forEach { dep ->
                        Text(
                            "  ! $dep",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (!result.hasInstalled() && result.notFound.isEmpty() && result.failed.isEmpty()) {
                    Text(
                        "无额外依赖需要安装",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        },
    )
}

/**
 * 加载器下拉：fabric / forge / quilt / neoforge / 全部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoaderDropdown(
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("fabric", "forge", "quilt", "neoforge", "")
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = if (selected.isEmpty()) "全部" else selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("加载器") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(if (opt.isEmpty()) "全部" else opt) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

/**
 * 分类标签栏：横向滚动 Row，可点击分类。
 */
@Composable
private fun CategoryBar(
    selectedCategory: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MOD_CATEGORIES.forEach { (slug, label) ->
            FilterChip(
                selected = selectedCategory == slug,
                onClick = { onSelect(slug) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 文件是否匹配市场页的游戏版本 + 加载器筛选 */
private fun fileMatchesMarketFilter(
    file: ModFile,
    gameVersion: String,
    loader: String,
): Boolean {
    val gv = gameVersion.trim()
    val ld = loader.trim()
    if (gv.isNotEmpty()) {
        if (file.getGameVersions().none { it.equals(gv, ignoreCase = true) }) return false
    }
    if (ld.isNotEmpty()) {
        if (file.getLoaders().none { it.equals(ld, ignoreCase = true) }) return false
    }
    return true
}

/** 格式化下载量：1000 → 1k，1000000 → 1M */
private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

/** 根据 slug 反查中文标签 */
private fun categoryLabel(slug: String): String =
    MOD_CATEGORIES.firstOrNull { it.first == slug }?.second ?: slug

/** 模组分类列表：中文标签 → Modrinth 原生 category slug */
private val MOD_CATEGORIES: List<Pair<String, String>> = listOf(
    "" to "全部",
    "optimization" to "性能优化",
    "technology" to "科技",
    "magic" to "魔法",
    "adventure" to "冒险",
    "decoration" to "装饰",
    "utility" to "实用",
    "mobs" to "生物",
    "food" to "食物",
    "worldgen" to "世界生成",
    "storage" to "存储",
    "equipment" to "装备",
    "transportation" to "交通",
    "social" to "社交",
    "game-mechanics" to "游戏机制",
)
