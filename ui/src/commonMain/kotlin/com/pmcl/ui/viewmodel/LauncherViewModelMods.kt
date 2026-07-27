package com.pmcl.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.reflect.TypeToken
import com.pmcl.core.cache.DataCache
import com.pmcl.core.i18n.I18n
import com.pmcl.core.market.ModFile
import com.pmcl.core.market.ModProject
import com.pmcl.core.mods.ModMeta
import com.pmcl.core.mods.ModScanner
import com.pmcl.core.mods.ModConflictChecker
import com.pmcl.ui.viewmodel.LauncherViewModel.ModScanCacheEntry
import java.nio.file.Path

/**
 * M29 拆分：模组市场 / 已安装模组域。
 *
 * 状态字段保留在 LauncherViewModel（@PublishedApi internal），
 * UI 调用签名不变（需 import 扩展函数）。
 */

// ============ 模组市场 ============

fun LauncherViewModel.searchMods(query: String, gameVersion: String? = null, loader: String? = null,
               category: String? = null) {
    scope.launch {
        _marketLoading.value = true
        _status.value = I18n.t("status.searching", query)
        try {
            val list = withContext(Dispatchers.IO) {
                if (category != null && category.isNotEmpty()) {
                    core.modMarket().search(query, gameVersion, loader, category, 30).join()
                } else {
                    core.modMarket().search(query, gameVersion, loader, 30).join()
                }
            }
            _marketResults.value = list
            _status.value = I18n.t("status.mods_found", list.size, if (core.modMarket().hasCurseForge()) I18n.t("common.enabled") else I18n.t("common.disabled"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.search_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _marketLoading.value = false
        }
    }
}

/**
 * 加载 Modrinth + CurseForge 热门 mod（按下载量排序）。
 * 进入页面时自动调用一次，作为「热门推荐」展示。
 */
fun LauncherViewModel.loadPopularMods(gameVersion: String? = null, loader: String? = null) {
    scope.launch {
        val gv = gameVersion?.trim().orEmpty()
        val ld = loader?.trim().orEmpty()
        // 缓存按筛选条件分键，避免「全部」缓存污染带版本/加载器的结果
        val cacheKey = "popular_mods_${gv}_${ld}"
        // 先读缓存秒开
        val cached = withContext(Dispatchers.IO) {
            DataCache.loadWithTimestamp(cacheKey, object : TypeToken<List<ModProject>>() {})
        }
        if (cached != null) {
            @Suppress("UNCHECKED_CAST")
            val data = cached[0] as? List<ModProject> ?: return@launch
            val savedAt = cached[1] as? Long ?: return@launch
            if (data.isNotEmpty()) {
                _popularMods.value = data
                _popularLoading.value = false
            }
            // 缓存未过期：后台静默刷新（stale-while-revalidate）
            if (!DataCache.isExpired(savedAt, 12 * 60 * 60 * 1000L)) {
                scope.launch {
                    try {
                        val list = withContext(Dispatchers.IO) {
                            core.modMarket().popular(
                                gv.ifBlank { null },
                                ld.ifBlank { null },
                                24
                            ).join()
                        }
                        _popularMods.value = list
                        DataCache.save(cacheKey, list)
                        _status.value = I18n.t("status.popular_mods_loaded", list.size)
                    } catch (_: Throwable) {
                        // 静默失败，保留缓存数据
                    }
                }
                return@launch
            }
            // 缓存已过期：继续走正常网络请求
        }
        // 缓存不存在/已过期：正常网络请求
        _popularLoading.value = true
        _status.value = I18n.t("status.loading_popular_mods")
        try {
            val list = withContext(Dispatchers.IO) {
                core.modMarket().popular(
                    gv.ifBlank { null },
                    ld.ifBlank { null },
                    24
                ).join()
            }
            _popularMods.value = list
            _status.value = I18n.t("status.popular_mods_loaded", list.size)
            DataCache.save(cacheKey, list)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.popular_mods_load_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _popularLoading.value = false
        }
    }
}

/**
 * 按分类加载推荐模组（用户点击分类标签后调用）。
 * 使用 Modrinth + CurseForge 聚合，按下载量排序。
 * category 为空字符串时等同于 loadPopularMods。
 */
fun LauncherViewModel.loadCategoryMods(category: String, gameVersion: String? = null, loader: String? = null) {
    _selectedCategory.value = category
    if (category.isEmpty()) {
        // 取消分类选择：清空分类结果，回到热门推荐
        _categoryResults.value = emptyList()
        return
    }
    // 切换到分类浏览模式：清除关键字搜索结果，使分类网格立即可见
    _marketResults.value = emptyList()
    scope.launch {
        _categoryLoading.value = true
        _status.value = I18n.t("status.loading_category", category)
        try {
            val list = withContext(Dispatchers.IO) {
                core.modMarket().searchByCategory(category, gameVersion, loader, 24).join()
            }
            _categoryResults.value = list
            _status.value = I18n.t("status.category_mods_loaded", list.size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.category_load_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _categoryLoading.value = false
        }
    }
}

/** 清除分类选择，回到热门推荐视图 */
fun LauncherViewModel.clearCategory() {
    _selectedCategory.value = ""
    _categoryResults.value = emptyList()
}

/**
 * 点击热门卡片进入该 mod 的详情界面（展开版本文件列表）。
 * 在 UI 层会把 _detailProject 设置为该 project，并触发 listProjectFiles。
 */
fun LauncherViewModel.openModDetail(project: ModProject) {
    _detailProject.value = project
    listProjectFiles(project)
}

/** 返回热门推荐网格（关闭详情） */
fun LauncherViewModel.closeModDetail() {
    _detailProject.value = null
    _currentModFiles.value = emptyList()
}

fun LauncherViewModel.listProjectFiles(project: ModProject) {
    scope.launch {
        // 立即清空旧的文件列表，避免切换 project 时残留
        _currentModFiles.value = emptyList()
        _status.value = I18n.t("status.fetching_project_files", project.getName())
        try {
            val files = withContext(Dispatchers.IO) {
                core.modMarket().listFiles(project).join()
            }
            _currentModFiles.value = files
            _status.value = I18n.t("status.project_files_loaded", project.getName(), files.size)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.fetch_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.installMod(file: ModFile, gameVersion: String) {
    scope.launch {
        _status.value = I18n.t("status.downloading_mod", file.getFileName())
        try {
            withContext(Dispatchers.IO) {
                core.modMarket().installMod(file, gameVersion,
                    _selectedVersion.value, preferences) { msg ->
                    _status.value = msg
                }.join()
            }
            _status.value = I18n.t("status.mod_installed", file.getFileName())
            refreshInstalledMods()
            try {
                core.plugins().fireEvent(
                    com.pmcl.plugin.ModInstalledEvent(file.getFileName(), file.getFileId() ?: "")
                )
            } catch (_: Throwable) {
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.mod_install_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/**
 * 安装模组并自动解析安装其依赖。
 * 下载主模组后解析 jar 内 depends 列表，自动搜索并安装未安装的依赖。
 */
fun LauncherViewModel.installModWithDeps(file: ModFile, gameVersion: String) {
    if (_installingDeps.value) return
    _installingDeps.value = true
    _depInstallResult.value = null
    scope.launch {
        _status.value = I18n.t("status.installing_mod_with_deps", file.getFileName())
        try {
            val result = core.modDependencyResolver().installWithDependencies(
                file, gameVersion, _selectedVersion.value
            ) { msg -> _status.value = msg }.join()
            _depInstallResult.value = result
            _status.value = if (result.hasInstalled()) {
                I18n.t("status.mod_install_complete_with_deps", file.getFileName(), result.summary())
            } else {
                I18n.t("status.mod_install_complete_no_deps", file.getFileName())
            }
            refreshInstalledMods()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.install_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _installingDeps.value = false
        }
    }
}

/** 清除依赖安装结果 */
fun LauncherViewModel.clearDepInstallResult() {
    _depInstallResult.value = null
}

// ============ 已安装 Mod 扫描 ============

fun LauncherViewModel.refreshInstalledMods() {
    // 先读缓存秒开
    scope.launch {
        try {
            val cached = withContext(Dispatchers.IO) {
                DataCache.load("installed_mods", object : TypeToken<List<ModMeta>>() {})
            }
            if (cached != null && cached.isNotEmpty() && _installedMods.value.isEmpty()) {
                _installedMods.value = cached
            }
        } catch (e: Throwable) {
            // 缓存读取失败不影响后续扫描，静默处理
        }
    }
    scope.launch {
        try {
            val mods = withContext(Dispatchers.IO) {
                val allMods = mutableListOf<ModMeta>()
                val seenFiles = mutableSetOf<String>()
                // 按目录分组的 mod 列表（用于冲突检查时按目录隔离）
                val modsByDir = mutableMapOf<Path, MutableList<ModMeta>>()
                val modsDirs = mutableListOf<Path>()
                // 1. PMCL 工作目录的 mods
                modsDirs.add(config.getWorkDir().resolve("mods"))
                // 2. 系统所有 Minecraft 根目录的 mods
                for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                    val mcRoot = mcDir.parent
                    if (mcRoot != null) modsDirs.add(mcRoot.resolve("mods"))
                }
                // 3. 每个版本目录下的 mods（整合包结构：versions/<id>/mods/）
                val allVersionsDirs = mutableListOf<Path>()
                allVersionsDirs.add(config.getVersionsDir())
                allVersionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                for (versionsDir in allVersionsDirs) {
                    val versionsFile = versionsDir.toFile()
                    if (!versionsFile.isDirectory) continue
                    val subDirs = versionsFile.listFiles { f -> f.isDirectory } ?: continue
                    for (subDir in subDirs) {
                        val versionModsDir = subDir.toPath().resolve("mods")
                        if (versionModsDir !in modsDirs) modsDirs.add(versionModsDir)
                    }
                }
                // 4. 版本隔离目录下的 mods（instances/<id>/mods/）
                val instancesDir = config.getWorkDir().resolve("instances")
                val instancesFile = instancesDir.toFile()
                if (instancesFile.isDirectory) {
                    val instDirs = instancesFile.listFiles { f -> f.isDirectory } ?: emptyArray()
                    for (instDir in instDirs) {
                        val instModsDir = instDir.toPath().resolve("mods")
                        if (instModsDir !in modsDirs) modsDirs.add(instModsDir)
                    }
                }
                // 扫描所有 mods 目录，按目录分组
                var scanFailCount = 0
                for (modsDir in modsDirs) {
                    try {
                        // 基于目录 mtime 的缓存：未变化则复用上次扫描结果
                        val dirMtime = try { java.nio.file.Files.getLastModifiedTime(modsDir).toMillis() } catch (_: Throwable) { 0L }
                        val cached = modScanCache[modsDir]
                        val part = if (cached != null && cached.dirMtime == dirMtime && dirMtime > 0L) {
                            cached.mods
                        } else {
                            val scanned = ModScanner.scanDirectory(modsDir)
                            modScanCache[modsDir] = ModScanCacheEntry(dirMtime, scanned)
                            scanned
                        }
                        // 为每个 mod 设置来源标签
                        val sourceLabel = sourceLabelFor(modsDir)
                        for (m in part) {
                            // 用「目录路径 + 文件名」去重，避免不同目录的同名文件误去重
                            val dedupKey = "$modsDir/${m.getJarFile()}"
                            if (seenFiles.add(dedupKey)) {
                                m.setSource(sourceLabel)
                                allMods.add(m)
                                modsByDir.getOrPut(modsDir) { mutableListOf() }.add(m)
                            }
                        }
                    } catch (t: Throwable) {
                        scanFailCount++
                        System.err.println("[refreshInstalledMods] 扫描失败 $modsDir: ${t.message}")
                    }
                }
                if (scanFailCount > 0) {
                    System.err.println("[refreshInstalledMods] $scanFailCount 个 mods 目录扫描失败（列表可能不完整）")
                }
                // 按目录分组检查冲突，避免跨版本目录误报依赖缺失
                val allErrors = mutableListOf<String>()
                val allWarnings = mutableListOf<String>()
                for ((_, dirMods) in modsByDir) {
                    if (dirMods.isEmpty()) continue
                    val r = ModConflictChecker.check(dirMods)
                    allErrors.addAll(r.getErrors())
                    allWarnings.addAll(r.getWarnings())
                }
                _modConflicts.value = ModConflictChecker.Result(allErrors, allWarnings)
                // 应用用户自定义标签
                try { core.modTagStore().applyTags(allMods) } catch (_: Throwable) {}
                allMods
            }
            _installedMods.value = mods
            DataCache.save("installed_mods", mods)
            _status.value = I18n.t("status.mods_scanned", mods.size, modsDirsCount(mods))
        } catch (e: Throwable) {
            _status.value = I18n.t("status.scan_mods_failed", e.message ?: I18n.t("common.unknown"))
            System.err.println("[refreshInstalledMods] 顶层异常: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
        }
    }
}

/** 刷新标签列表（从 ModTagStore 加载） */
fun LauncherViewModel.refreshModTags() {
    _allModTags.value = core.modTagStore().getAllTags()
}

/** 设置模组标签（jarFile → tags），并刷新 UI */
fun LauncherViewModel.setModTags(jarFile: String, tags: List<String>) {
    scope.launch {
        withContext(Dispatchers.IO) {
            core.modTagStore().setTags(jarFile, tags)
        }
        // 更新内存中的 ModMeta
        _installedMods.value = _installedMods.value.map { mod ->
            if (mod.getJarFile() == jarFile) {
                mod.setTags(tags)
                mod
            } else {
                mod
            }
        }
        // 刷新标签列表
        _allModTags.value = core.modTagStore().getAllTags()
    }
}

@PublishedApi
internal fun LauncherViewModel.modsDirsCount(mods: List<ModMeta>): String {
    return "${mods.size} mods"
}

/**
 * 根据 mods 目录路径推断来源标签：
 * - PMCL 全局 mods → "全局"
 * - versions/<id>/mods → <id>（版本/整合包名）
 * - 系统 .minecraft/mods → "系统"
 */
@PublishedApi
internal fun LauncherViewModel.sourceLabelFor(modsDir: java.nio.file.Path): String {
    // PMCL 全局 mods 目录
    if (modsDir == config.getWorkDir().resolve("mods")) return "全局"
    // 整合包结构：parent 是 versions/<id> 下的版本目录
    val parent = modsDir.parent
    if (parent != null) {
        val grandParentName = parent.parent?.fileName?.toString()?.lowercase()
        if (grandParentName == "versions") {
            return parent.fileName?.toString() ?: "版本"
        }
    }
    // 系统目录
    return "系统"
}

/** 解析 mod jar 绝对路径：优先 jarPath，否则按文件名在已扫描列表 / 全局 mods 中定位 */
@PublishedApi
internal fun LauncherViewModel.resolveModJarPath(mod: ModMeta): java.nio.file.Path? {
    val abs = mod.jarPath
    if (!abs.isNullOrBlank()) return java.nio.file.Path.of(abs)
    return resolveModJarPath(mod.jarFile)
}

@PublishedApi
internal fun LauncherViewModel.resolveModJarPath(jarFile: String?): java.nio.file.Path? {
    if (jarFile.isNullOrBlank()) return null
    val asPath = java.nio.file.Path.of(jarFile)
    if (asPath.isAbsolute && java.nio.file.Files.exists(asPath)) return asPath
    _installedMods.value.firstOrNull { it.jarFile == jarFile }?.jarPath
        ?.takeIf { it.isNotBlank() }
        ?.let { return java.nio.file.Path.of(it) }
    val global = config.getWorkDir().resolve("mods").resolve(jarFile)
    return if (java.nio.file.Files.exists(global)) global else global
}

/** 删除指定 mod（按 jar 文件名或已解析路径） */
fun LauncherViewModel.deleteMod(jarFile: String) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val path = resolveModJarPath(jarFile)
                if (path != null) core.modManager().deleteModAt(path)
                else core.modManager().deleteMod(jarFile)
            }
            modScanCache.clear()
            _status.value = I18n.t("status.mod_deleted", jarFile)
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.deleteMod(mod: ModMeta) {
    scope.launch {
        try {
            val path = resolveModJarPath(mod)
                ?: throw java.io.IOException("文件不存在: ${mod.jarFile}")
            withContext(Dispatchers.IO) { core.modManager().deleteModAt(path) }
            modScanCache.clear()
            _status.value = I18n.t("status.mod_deleted", mod.jarFile ?: path.fileName.toString())
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 禁用 mod（重命名为 .jar.disabled） */
fun LauncherViewModel.disableMod(jarFile: String) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val path = resolveModJarPath(jarFile)
                    ?: throw java.io.IOException("文件不存在: $jarFile")
                core.modManager().disableModAt(path)
            }
            modScanCache.clear()
            _status.value = I18n.t("status.mod_disabled", jarFile)
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.disable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.disableMod(mod: ModMeta) {
    scope.launch {
        try {
            val path = resolveModJarPath(mod)
                ?: throw java.io.IOException("文件不存在: ${mod.jarFile}")
            withContext(Dispatchers.IO) { core.modManager().disableModAt(path) }
            modScanCache.clear()
            _status.value = I18n.t("status.mod_disabled", mod.jarFile ?: path.fileName.toString())
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.disable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 启用 mod（去掉 .disabled 后缀） */
fun LauncherViewModel.enableMod(jarFile: String) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val path = resolveModJarPath(jarFile)
                    ?: throw java.io.IOException("文件不存在: $jarFile")
                core.modManager().enableModAt(path)
            }
            modScanCache.clear()
            _status.value = I18n.t("status.mod_enabled", jarFile)
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.enable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.enableMod(mod: ModMeta) {
    scope.launch {
        try {
            val path = resolveModJarPath(mod)
                ?: throw java.io.IOException("文件不存在: ${mod.jarFile}")
            withContext(Dispatchers.IO) { core.modManager().enableModAt(path) }
            modScanCache.clear()
            _status.value = I18n.t("status.mod_enabled", mod.jarFile ?: path.fileName.toString())
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.enable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 导入模组文件到 mods 目录 */
fun LauncherViewModel.importMod(filePath: String) {
    scope.launch {
        try {
            val fileName = withContext(Dispatchers.IO) {
                val src = java.nio.file.Paths.get(filePath)
                val targetDir = config.getWorkDir().resolve("mods")
                java.nio.file.Files.createDirectories(targetDir)
                val target = targetDir.resolve(src.fileName)
                java.nio.file.Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                src.fileName.toString()
            }
            _status.value = I18n.t("status.mod_imported", fileName)
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量操作结果文案：全成功用 successKey；有失败则报告成功/失败数 */
@PublishedApi
internal fun LauncherViewModel.batchResultStatus(successKey: String, ok: Int, fail: Int, total: Int): String =
    if (fail == 0) I18n.t(successKey, ok)
    else I18n.t("status.batch_partial", ok, fail, total)

/** 批量启用模组 */
fun LauncherViewModel.batchEnableMods(jarFiles: List<String>) {
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (jarFile in jarFiles) {
                    try {
                        val path = resolveModJarPath(jarFile)
                        if (path == null) { fail++; continue }
                        core.modManager().enableModAt(path)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchEnableMods 失败 $jarFile: ${t.message}")
                    }
                }
            }
            modScanCache.clear()
            _status.value = batchResultStatus("status.batch_enabled_mods", ok, fail, jarFiles.size)
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_enable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量禁用模组 */
fun LauncherViewModel.batchDisableMods(jarFiles: List<String>) {
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (jarFile in jarFiles) {
                    try {
                        val path = resolveModJarPath(jarFile)
                        if (path == null) { fail++; continue }
                        core.modManager().disableModAt(path)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchDisableMods 失败 $jarFile: ${t.message}")
                    }
                }
            }
            modScanCache.clear()
            _status.value = batchResultStatus("status.batch_disabled_mods", ok, fail, jarFiles.size)
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_disable_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 批量删除模组 */
fun LauncherViewModel.batchDeleteMods(jarFiles: List<String>) {
    scope.launch {
        try {
            var ok = 0; var fail = 0
            withContext(Dispatchers.IO) {
                for (jarFile in jarFiles) {
                    try {
                        val path = resolveModJarPath(jarFile)
                        if (path == null) { fail++; continue }
                        core.modManager().deleteModAt(path)
                        ok++
                    } catch (t: Throwable) {
                        fail++
                        System.err.println("[VM] batchDeleteMods 失败 $jarFile: ${t.message}")
                    }
                }
            }
            modScanCache.clear()
            _status.value = batchResultStatus("status.batch_deleted_mods", ok, fail, jarFiles.size)
            refreshInstalledMods()
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_delete_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 在系统文件管理中打开 mods 目录（优先打开第一个存在且有文件的目录） */
fun LauncherViewModel.openModsDir() {
    try {
        // 候选 mods 目录：PMCL 工作目录 + 系统所有 Minecraft 根目录
        val candidates = mutableListOf<java.io.File>()
        candidates.add(config.getWorkDir().resolve("mods").toFile())
        for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
            val mcRoot = mcDir.parent
            if (mcRoot != null) candidates.add(mcRoot.resolve("mods").toFile())
        }
        // 优先选第一个存在且非空的目录，否则用 PMCL 默认目录
        val modsDir = candidates.firstOrNull { it.isDirectory && (it.list()?.isNotEmpty() == true) }
            ?: candidates.firstOrNull { it.isDirectory }
            ?: config.getWorkDir().resolve("mods").toFile().also { it.mkdirs() }
        openDir(modsDir)
    } catch (e: Throwable) {
        _status.value = I18n.t("status.open_dir_failed", e.message ?: I18n.t("common.unknown"))
    }
}

/** 打开某个模组 jar 所在文件夹（macOS 尽量选中该文件） */
fun LauncherViewModel.openModFolder(mod: ModMeta) {
    try {
        val pathStr = mod.jarPath
        if (pathStr.isNullOrBlank()) {
            openModsDir()
            return
        }
        val file = java.io.File(pathStr)
        if (!file.exists()) {
            _status.value = I18n.t("status.open_dir_failed", I18n.t("common.unknown"))
            return
        }
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("mac") -> ProcessBuilder("open", "-R", file.absolutePath).start()
            os.contains("win") -> ProcessBuilder("explorer", "/select,", file.absolutePath).start()
            else -> openDir(file.parentFile ?: file)
        }
    } catch (e: Throwable) {
        _status.value = I18n.t("status.open_dir_failed", e.message ?: I18n.t("common.unknown"))
    }
}

/**
 * 检查市场项目是否已安装（按 modId 匹配）。
 * 用于在市场列表中显示"已安装"标记。
 */
fun LauncherViewModel.isModInstalled(modId: String): Boolean {
    return _installedMods.value.any { it.getModId() == modId && !it.isDisabled() }
}

