package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.market.ModProject
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Desktop
import java.net.URI
import java.net.URL

@Composable
fun ModsMarketPage(vm: LauncherViewModel) {
    val results by vm.marketResults.collectAsState()
    val loading by vm.marketLoading.collectAsState()
    val status by vm.status.collectAsState()
    val installedMods by vm.installedMods.collectAsState()
    val popularMods by vm.popularMods.collectAsState()
    val popularLoading by vm.popularLoading.collectAsState()
    val categoryResults by vm.categoryResults.collectAsState()
    val categoryLoading by vm.categoryLoading.collectAsState()
    val selectedCategory by vm.selectedCategory.collectAsState()
    val detailProject by vm.detailProject.collectAsState()
    val currentModFiles by vm.currentModFiles.collectAsState()
    val translationCache by vm.translationCache.collectAsState()
    val translating by vm.translating.collectAsState()
    val depResult by vm.depInstallResult.collectAsState()
    var translateEnabled by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var gameVersion by remember { mutableStateOf("1.20.4") }
    var loader by remember { mutableStateOf("fabric") }

    // 首次进入自动加载热门推荐
    LaunchedEffect(Unit) {
        if (popularMods.isEmpty() && !popularLoading) {
            vm.loadPopularMods()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模组市场", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = translateEnabled,
                onClick = {
                    translateEnabled = !translateEnabled
                    if (translateEnabled) {
                        val texts = (popularMods + categoryResults + results).flatMap {
                            listOfNotNull(it.getName(), it.getSummary())
                        }.distinct()
                        vm.translateBatch(texts)
                    }
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (translateEnabled) Icons.Filled.Translate else Icons.Outlined.Translate,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (translating) "翻译中…" else "翻译")
                    }
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("聚合 Modrinth + CurseForge。CurseForge 需配置 CURSEFORGE_API_KEY 环境变量。",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(16.dp))

        // 搜索栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("搜索模组（回车搜索）") }, singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (query.isNotBlank() && !loading) {
                            vm.searchMods(query, gameVersion, loader, selectedCategory)
                        }
                    }
                )
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = gameVersion, onValueChange = { gameVersion = it },
                label = { Text("目标版本") }, singleLine = true,
                modifier = Modifier.width(120.dp)
            )
            Spacer(Modifier.width(12.dp))
            LoaderDropdown(loader) { loader = it }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                vm.searchMods(query, gameVersion, loader, selectedCategory)
            }, enabled = !loading && query.isNotBlank()) {
                Text(if (loading) "搜索中…" else "搜索")
            }
        }

        Spacer(Modifier.height(12.dp))

        // 分类推荐标签栏（横向滚动）
        CategoryBar(
            selectedCategory = selectedCategory,
            onSelect = { cat ->
                if (cat.isEmpty()) {
                    vm.clearCategory()
                } else {
                    vm.loadCategoryMods(cat, gameVersion, loader)
                }
            }
        )

        Spacer(Modifier.height(12.dp))

        val installedModIds = remember(installedMods) { installedMods.map { it.getModId() }.toSet() }

        when {
            // 详情视图：点击卡片后进入
            detailProject != null -> {
                val dp = detailProject ?: return@Column
                ModDetailView(
                    project = dp,
                    vm = vm,
                    searchGameVersion = gameVersion,
                    translateEnabled = translateEnabled,
                    translationCache = translationCache,
                    onBack = { vm.closeModDetail() }
                )
            }
            // 搜索结果视图（用户主动搜索后）
            results.isNotEmpty() -> {
                Text("搜索结果（${results.size}）",
                     style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(results, key = { p -> p.getSource() + "/" + p.getId() }) { project ->
                        SearchResultCard(
                            project = project,
                            vm = vm,
                            searchGameVersion = gameVersion,
                            installedModIds = installedModIds,
                            translateEnabled = translateEnabled,
                            translationCache = translationCache
                        )
                    }
                }
            }
            // 分类推荐网格（用户选择分类标签后）
            selectedCategory.isNotEmpty() -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分类推荐：${categoryLabel(selectedCategory)}",
                         style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.weight(1f))
                    if (categoryLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = {
                            vm.loadCategoryMods(selectedCategory, gameVersion, loader)
                        }) { Text("刷新") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (categoryResults.isEmpty() && !categoryLoading) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("该分类下暂无模组，点击刷新重试",
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(220.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(categoryResults,
                                key = { _, p -> p.getSource() + "/" + p.getId() }) { _, project ->
                            PopularCard(
                                project = project,
                                onClick = { vm.openModDetail(project) },
                                translateEnabled = translateEnabled,
                                translationCache = translationCache
                            )
                        }
                    }
                }
            }
            // 热门推荐网格（默认视图）
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔥 热门推荐",
                         style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.weight(1f))
                    if (popularLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { vm.loadPopularMods(gameVersion, loader) }) {
                            Text("刷新")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (popularMods.isEmpty() && !popularLoading) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("加载失败或无数据，点击刷新重试",
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(220.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(popularMods,
                                key = { _, p -> p.getSource() + "/" + p.getId() }) { _, project ->
                            PopularCard(
                                project = project,
                                onClick = { vm.openModDetail(project) },
                                translateEnabled = translateEnabled,
                                translationCache = translationCache
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("状态：$status", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }

    // 依赖安装结果对话框
    if (depResult != null) {
        DependencyResultDialog(
            result = depResult!!,
            onDismiss = { vm.clearDepInstallResult() }
        )
    }
}

/**
 * 依赖安装结果对话框：展示已安装依赖、跳过的系统依赖、未找到的依赖等。
 */
@Composable
private fun DependencyResultDialog(
    result: com.pmcl.core.mods.ModDependencyResolver.DependencyResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("mods.dep_result_title")) },
        text = {
            Column {
                Text(I18n.t("mods.dep_mod_label", result.modName),
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                if (result.installedDependencies.isNotEmpty()) {
                    Text(I18n.t("mods.dep_installed", result.installedDependencies.size),
                         style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.primary,
                         fontWeight = FontWeight.SemiBold)
                    result.installedDependencies.forEach { dep ->
                        Text("  + $dep",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (result.skippedInstalled.isNotEmpty()) {
                    Text(I18n.t("mods.dep_skipped", result.skippedInstalled.size),
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result.skippedInstalled.forEach { dep ->
                        Text("  - $dep (已安装)",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (result.skippedSystem.isNotEmpty()) {
                    Text(I18n.t("mods.dep_system"),
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  ${result.skippedSystem.joinToString(", ")}",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }

                if (result.notFound.isNotEmpty()) {
                    Text(I18n.t("mods.dep_not_found", result.notFound.size),
                         style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.error,
                         fontWeight = FontWeight.SemiBold)
                    result.notFound.forEach { dep ->
                        Text("  ? $dep",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (result.failed.isNotEmpty()) {
                    Text(I18n.t("mods.dep_failed", result.failed.size),
                         style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.error,
                         fontWeight = FontWeight.SemiBold)
                    result.failed.forEach { dep ->
                        Text("  ! $dep",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                }

                if (!result.hasInstalled() && result.notFound.isEmpty() && result.failed.isEmpty()) {
                    Text(I18n.t("mods.dep_no_extra"),
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.ok")) }
        }
    )
}

/**
 * 热门推荐卡片：图标 + 名字 + 简介 + 来源标签 + 下载量。
 * 点击整个卡片进入详情界面。
 */
@Composable
private fun PopularCard(
    project: ModProject,
    onClick: () -> Unit,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap()
) {
    val displayName = if (translateEnabled) translationCache[project.getName()] ?: project.getName() else project.getName()
    val displaySummary = if (translateEnabled) translationCache[project.getSummary()] ?: project.getSummary() else project.getSummary()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(10.dp)) {
            // 图标
            val image = rememberUrlImage(project.getIconUrl())
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (!project.getIconUrl().isNullOrEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Text("🎮", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(displayName,
                 fontWeight = FontWeight.SemiBold,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(displaySummary,
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(project.getSource()) })
                Spacer(Modifier.width(6.dp))
                Text("↓ ${formatCount(project.getDownloadCount())}",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/**
 * Mod 详情界面：顶部信息卡 + 下载到指定游戏版本 + 版本文件列表。
 * 作为 ColumnScope 扩展以使用 weight 修饰符。
 */
@Composable
private fun ColumnScope.ModDetailView(
    project: ModProject,
    vm: LauncherViewModel,
    searchGameVersion: String,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap(),
    onBack: () -> Unit
) {
    var targetGameVersion by remember { mutableStateOf(searchGameVersion) }
    var showAllFiles by remember { mutableStateOf(false) }
    val files by vm.currentModFiles.collectAsState()

    val displayName = if (translateEnabled) translationCache[project.getName()] ?: project.getName() else project.getName()
    val displaySummary = if (translateEnabled) translationCache[project.getSummary()] ?: project.getSummary() else project.getSummary()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().weight(1f)
    ) {
        // 顶部：返回按钮 + 项目信息
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回热门") }
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp)) {
                    // 图标
                    val image = rememberUrlImage(project.getIconUrl())
                    Box(
                        modifier = Modifier.size(80.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        if (image != null) {
                            Image(image, project.getName(),
                                  contentScale = ContentScale.Fit,
                                  modifier = Modifier.fillMaxSize())
                        } else {
                            Text("🎮")
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayName,
                             style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.SemiBold)
                        Text(displaySummary,
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             maxLines = 3)
                        Spacer(Modifier.height(4.dp))
                        Text("${project.getAuthor()}  ·  ↓${formatCount(project.getDownloadCount())}  ·  ${project.getSource()}",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        // 操作按钮：网页 + 刷新版本
        item {
            Row {
                TextButton(onClick = {
                    try {
                        val url = project.getWebsiteUrl()
                        if (!url.isNullOrBlank() && Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().browse(URI(url))
                        }
                    } catch (_: Throwable) {
                        // 浏览器打开失败，忽略
                    }
                }) { Text("🔗 打开网页") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.listProjectFiles(project) }) {
                    Text("🔄 刷新版本列表")
                }
            }
        }
        // 下载目标游戏版本选择
        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("下载到游戏版本",
                         style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = targetGameVersion,
                            onValueChange = { targetGameVersion = it },
                            label = { Text("目标 MC 版本（mods 子目录）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("文件将下载到 mods/$targetGameVersion/ 目录",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        // 版本文件列表
        item {
            Text("版本文件（${files.size}）",
                 style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
        }
        if (files.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("加载中…", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        } else {
            val displayFiles = if (showAllFiles) files else files.take(15)
            items(displayFiles, key = { f -> f.getSource() + "/" + f.getFileId() }) { f ->
                FileRow(f, targetGameVersion, vm)
            }
            if (files.size > 15) {
                item {
                    TextButton(onClick = { showAllFiles = !showAllFiles }) {
                        Text(if (showAllFiles) "收起（共 ${files.size}）"
                             else "查看全部（${files.size}）")
                    }
                }
            }
        }
    }
}

/**
 * 搜索结果卡片（用于主动搜索后的列表展示），点击也可进入详情。
 */
@Composable
private fun SearchResultCard(
    project: ModProject,
    vm: LauncherViewModel,
    searchGameVersion: String,
    installedModIds: Set<String>,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap()
) {
    val isInstalled = installedModIds.contains(project.getSlug())
            || installedModIds.contains(project.getId())

    val displayName = if (translateEnabled) translationCache[project.getName()] ?: project.getName() else project.getName()
    val displaySummary = if (translateEnabled) translationCache[project.getSummary()] ?: project.getSummary() else project.getSummary()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable { vm.openModDetail(project) }
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 小图标
            val image = rememberUrlImage(project.getIconUrl())
            Box(
                modifier = Modifier.size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    Image(image, displayName,
                          contentScale = ContentScale.Fit,
                          modifier = Modifier.fillMaxSize())
                } else {
                    Text("🎮", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (isInstalled) {
                        Spacer(Modifier.width(6.dp))
                        Text("✓ 已安装",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(displaySummary,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${project.getAuthor()}  ·  ↓${formatCount(project.getDownloadCount())}  ·  ${project.getSource()}",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            Text("›", style = MaterialTheme.typography.titleLarge,
                 color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun FileRow(
    f: com.pmcl.core.market.ModFile,
    targetGameVersion: String,
    vm: LauncherViewModel
) {
    val installingDeps by vm.installingDeps.collectAsState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.getFileName() ?: "",
                     style = MaterialTheme.typography.bodySmall,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${(f.getGameVersions() ?: emptyList()).joinToString(",")} · ${f.getLoaders().joinToString(",")} · ${f.getReleaseType()}" +
                    if (f.getFileSize() > 0) " · ${f.getFileSize() / 1024}KB" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Button(onClick = {
                vm.enqueueModDownload(f, targetGameVersion.ifBlank {
                    (f.getGameVersions() ?: emptyList()).firstOrNull() ?: ""
                })
            }) { Text(I18n.t("market.download")) }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(
                onClick = {
                    vm.installModWithDeps(f, targetGameVersion.ifBlank {
                        (f.getGameVersions() ?: emptyList()).firstOrNull() ?: ""
                    })
                },
                enabled = !installingDeps
            ) {
                if (installingDeps) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(I18n.t("mods.with_deps"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoaderDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("fabric", "forge", "quilt", "neoforge", "")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (selected.isEmpty()) "全部" else selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("加载器") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().width(120.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(if (opt.isEmpty()) "全部" else opt) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

/**
 * 模组分类列表：中文标签 → Modrinth 原生 category slug。
 * slug 已通过 https://api.modrinth.com/v2/tag/category 校验为有效分类。
 * 「全部」对应空字符串，表示不按分类过滤（显示热门推荐）。
 */
private val MOD_CATEGORIES: List<Pair<String, String>> = listOf(
    "全部" to "",
    "性能优化" to "optimization",
    "科技" to "technology",
    "魔法" to "magic",
    "冒险" to "adventure",
    "装饰" to "decoration",
    "实用" to "utility",
    "生物" to "mobs",
    "食物" to "food",
    "世界生成" to "worldgen",
    "存储" to "storage",
    "装备" to "equipment",
    "运输" to "transportation",
    "社交" to "social",
    "游戏机制" to "game-mechanics"
)

/** 根据 slug 反查中文标签（用于分类网格标题）。 */
private fun categoryLabel(slug: String): String {
    return MOD_CATEGORIES.firstOrNull { it.second == slug }?.first ?: slug
}

/**
 * 分类推荐标签栏：横向滚动的流体滑动指示器选择器。
 * 点击「全部」回到热门推荐；点击具体分类加载该分类下的热门模组。
 * 分类较多时启用横向滚动，指示器随之滚动保持对齐。
 */
@Composable
private fun CategoryBar(
    selectedCategory: String,
    onSelect: (String) -> Unit
) {
    val labels = MOD_CATEGORIES.map { it.first }
    val selectedIndex = MOD_CATEGORIES.indexOfFirst { it.second == selectedCategory }.coerceAtLeast(0)
    com.pmcl.ui.animation.AnimatedSegmentedSelector(
        items = labels,
        selectedIndex = selectedIndex,
        onSelect = { i -> onSelect(MOD_CATEGORIES[i].second) },
        modifier = Modifier.fillMaxWidth(),
        scrollable = true,
        height = 36.dp
    )
}

/**
 * 图片内存缓存：URL → ImageBitmap。避免滚动时重复下载与解码。
 */
private val modImageCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

/**
 * 异步加载网络图片为 ImageBitmap（基于 Skia）。
 * 失败或空 URL 返回 null。
 */
@Composable
private fun rememberUrlImage(url: String): ImageBitmap? {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(modImageCache[url]) }
    LaunchedEffect(url) {
        if (url.isEmpty()) {
            image = null
            return@LaunchedEffect
        }
        if (modImageCache.containsKey(url)) {
            image = modImageCache[url]
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            try {
                val bytes = URL(url).readBytes()
                val bmp = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                modImageCache[url] = bmp
                while (modImageCache.size > 50) {
                    val iterator = modImageCache.keys.iterator()
                    if (iterator.hasNext()) { iterator.next(); iterator.remove() }
                    else break
                }
                image = bmp
            } catch (_: Throwable) {
                image = null
            }
        }
    }
    return image
}

/** 格式化下载量：1000 → 1k，1000000 → 1M */
private fun formatCount(n: Long): String {
    return when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
        else -> n.toString()
    }
}
