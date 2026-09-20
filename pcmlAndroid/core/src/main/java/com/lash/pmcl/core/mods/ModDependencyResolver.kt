package com.lash.pmcl.core.mods

import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.launch.GameDirResolver
import com.lash.pmcl.core.market.ModFile
import com.lash.pmcl.core.market.ModrinthClient
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.preferences.Preferences
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.function.Consumer

/**
 * 模组依赖自动解析与安装器。
 *
 * 安装模组时自动解析 jar 内元数据（fabric.mod.json / mods.toml 等）中的 depends 列表，
 * 过滤掉系统依赖（minecraft、java、fabricloader、quilt_loader、forge、neoforge），
 * 对剩余的未安装依赖递归搜索并安装（Modrinth 优先，用 modId 作为 slug 直接查询）。
 *
 * 递归安装带循环检测（`installing` 集合），避免 A→B→A 死循环。
 *
 * Android 版：通过构造函数注入 modsDir / modrinthClient / executor，
 * 不再依赖 InstanceManager / ModMarketManager。
 */
class ModDependencyResolver(
    private val modsDir: Path,
    private val modrinthClient: ModrinthClient,
    private val executor: ExecutorService,
    private val downloadManager: DownloadManager,
    private val paths: PmclPaths? = null,
    private val preferences: Preferences? = null
) {

    /**
     * 依赖安装结果。
     */
    data class DependencyResult(
        val modName: String,
        val installedDependencies: List<String>,
        val skippedInstalled: List<String>,
        val skippedSystem: List<String>,
        val failed: List<String>,
        val notFound: List<String>
    ) {
        /** 是否安装了任何依赖 */
        fun hasInstalled(): Boolean = installedDependencies.isNotEmpty()

        /** 摘要信息 */
        fun summary(): String {
            val sb = StringBuilder()
            if (installedDependencies.isNotEmpty()) {
                sb.append("已安装依赖: ").append(installedDependencies.joinToString(", "))
            }
            if (notFound.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("；")
                sb.append("未找到: ").append(notFound.joinToString(", "))
            }
            if (failed.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("；")
                sb.append("失败: ").append(failed.joinToString(", "))
            }
            return if (sb.isNotEmpty()) sb.toString() else "无额外依赖"
        }
    }

    /** 系统依赖 modId 集合（这些不需要安装） */
    private val SYSTEM_DEPS = setOf(
        "minecraft", "java", "fabricloader", "quilt_loader", "quiltloader",
        "forge", "neoforge", "fmlonly"
    )

    /**
     * 安装模组并自动解析安装其依赖。
     *
     * 流程：
     * 1. 下载安装主模组 jar
     * 2. 优先使用 ModFile.getDependencies()（来自 Modrinth API 的 dependencies 字段）获取依赖列表
     * 3. 若 API 未提供依赖信息，则用 [ModScanner.parseJar] 解析 jar 内元数据获取 depends 列表
     * 4. 过滤系统依赖（minecraft、java、fabricloader 等）
     * 5. 对每个剩余依赖 modId/projectId，检查是否已安装
     * 6. 未安装的，在 Modrinth 上搜索，获取兼容版本文件并安装
     * 7. 递归处理依赖的依赖（带循环检测）
     *
     * @param modFile     要安装的模组文件
     * @param gameVersion 目标 MC 版本
     * @param versionId   版本 ID（有则安装到该版本/实例 mods 目录）
     * @param onStatus    状态回调，可为 null
     * @return 依赖安装结果
     */
    fun installWithDependencies(
        modFile: ModFile, gameVersion: String?, versionId: String?,
        onStatus: Consumer<String>?
    ): CompletableFuture<DependencyResult> {
        return CompletableFuture.supplyAsync({
            val installed = ArrayList<String>()
            val skippedInstalled = ArrayList<String>()
            val skippedSystem = ArrayList<String>()
            val failed = ArrayList<String>()
            val notFound = ArrayList<String>()
            val processing = HashSet<String>()

            val destDir = resolveDestModsDir(versionId)
            try {
                // 1. 安装主模组
                onStatus?.accept("正在下载: ${modFile.fileName}")
                downloadAndInstall(modFile, destDir, onStatus)

                // 2. 优先使用 API 提供的依赖信息（无需解析 jar）
                var deps: List<String>? = modFile.getDependencies()
                var modName = modFile.fileName

                if (deps.isNullOrEmpty()) {
                    // API 未提供依赖信息，回退到解析 jar 内元数据
                    val jarPath = destDir.resolve(modFile.fileName)
                    if (!Files.exists(jarPath)) {
                        return@supplyAsync DependencyResult(
                            modName, installed, skippedInstalled, skippedSystem, failed, notFound
                        )
                    }

                    val meta = ModScanner.parseJar(jarPath)
                    modName = if (meta.name.isNotEmpty()) meta.name else meta.modId
                    deps = meta.depends
                } else {
                    onStatus?.accept("从 API 获取到 ${deps.size} 个依赖")
                }

                if (deps.isNullOrEmpty()) {
                    onStatus?.accept("无额外依赖")
                    return@supplyAsync DependencyResult(
                        modName, installed, skippedInstalled, skippedSystem, failed, notFound
                    )
                }

                onStatus?.accept("检测到 ${deps.size} 个依赖，开始解析...")

                // 3. 递归处理依赖（仅在此处调用一次 getInstalledModIds，递归内增量更新集合）
                val installedModIds = getInstalledModIds(destDir)
                val preferredLoaders = modFile.getLoaders()
                resolveDependencies(
                    deps, gameVersion, preferredLoaders, processing,
                    installed, skippedInstalled, skippedSystem, failed, notFound,
                    onStatus, 0, installedModIds, destDir
                )
            } catch (e: Throwable) {
                failed.add("${modFile.fileName}: ${e.message}")
            }
            DependencyResult(
                modFile.fileName, installed, skippedInstalled, skippedSystem, failed, notFound
            )
        }, executor)
    }

    /**
     * 递归解析并安装依赖。
     *
     * @param deps             依赖 modId 列表
     * @param gameVersion      目标 MC 版本
     * @param preferredLoaders 父模组加载器列表
     * @param processing       当前处理链（循环检测）
     * @param installed        已安装列表（输出）
     * @param skippedInstalled 已安装跳过列表（输出）
     * @param skippedSystem    系统依赖跳过列表（输出）
     * @param failed           失败列表（输出）
     * @param notFound         未找到列表（输出）
     * @param onStatus         状态回调
     * @param depth            递归深度（限制最大深度 10）
     * @param installedModIds  已安装 mod 的 modId 集合（可变，递归过程中增量更新）
     */
    private fun resolveDependencies(
        deps: List<String>, gameVersion: String?, preferredLoaders: List<String>?,
        processing: HashSet<String>,
        installed: ArrayList<String>, skippedInstalled: ArrayList<String>,
        skippedSystem: ArrayList<String>, failed: ArrayList<String>,
        notFound: ArrayList<String>, onStatus: Consumer<String>?,
        depth: Int, installedModIds: HashSet<String>, destDir: Path
    ) {
        if (depth > 10) return  // 防止无限递归

        for (dep in deps) {
            // 解析依赖名：可能是 "modId" 或 "modId@version" 或 {"modId": "versionRange"} 形式
            val depId = extractModId(dep) ?: continue
            if (depId.isEmpty()) continue

            // 循环检测
            if (processing.contains(depId)) continue

            // 系统依赖跳过
            if (isSystemDep(depId)) {
                skippedSystem.add(depId)
                continue
            }

            // 已安装跳过
            if (installedModIds.contains(depId)) {
                skippedInstalled.add(depId)
                continue
            }

            // 防止重复安装
            if (installed.contains(depId)) continue

            processing.add(depId)
            try {
                onStatus?.accept("查找依赖: $depId")

                // 在 Modrinth 上搜索依赖（用 modId 作为 slug），并按父模组 loader 过滤
                val depFile = findCompatibleMod(depId, gameVersion, preferredLoaders)
                if (depFile == null) {
                    notFound.add(depId)
                    processing.remove(depId)
                    continue
                }

                // 下载安装依赖
                onStatus?.accept("安装依赖: ${depFile.fileName}")
                downloadAndInstall(depFile, destDir, onStatus)
                installed.add(depId)
                installedModIds.add(depId)  // 更新已安装集合

                // 递归解析依赖的依赖（沿用父侧 preferredLoaders）
                val depJarPath = destDir.resolve(depFile.fileName)
                if (Files.exists(depJarPath)) {
                    val depMeta = ModScanner.parseJar(depJarPath)
                    if (depMeta.depends.isNotEmpty()) {
                        val nextLoaders = if (depFile.getLoaders().isNotEmpty())
                            depFile.getLoaders() else preferredLoaders
                        resolveDependencies(
                            depMeta.depends, gameVersion, nextLoaders, processing,
                            installed, skippedInstalled, skippedSystem, failed, notFound,
                            onStatus, depth + 1, installedModIds, destDir
                        )
                    }
                }
            } catch (e: Throwable) {
                failed.add("$depId: ${e.message}")
            } finally {
                processing.remove(depId)
            }
        }
    }

    /**
     * 在 Modrinth 上查找兼容的模组文件。
     * 用 modId 作为 slug 直接调用 listFiles，按 gameVersion + loader 过滤取首个。
     *
     * @param modId            依赖 modId
     * @param gameVersion      目标 MC 版本
     * @param preferredLoaders 父模组加载器列表（可空；非空时要求显式匹配）
     * @return 兼容的 ModFile，未找到返回 null
     */
    private fun findCompatibleMod(
        modId: String, gameVersion: String?, preferredLoaders: List<String>?
    ): ModFile? {
        try {
            val requireLoader = !preferredLoaders.isNullOrEmpty()
            val files = modrinthClient.listFiles(modId).join()
            if (files.isNullOrEmpty()) return null

            // 按 gameVersion + loader 过滤，取第一个兼容文件
            for (file in files) {
                val gvOk = gameVersion.isNullOrEmpty() ||
                    file.getGameVersions().contains(gameVersion)
                if (!gvOk) continue
                if (requireLoader) {
                    val fl = file.getLoaders()
                    if (fl.isNullOrEmpty()) continue
                    var loaderOk = false
                    for (want in preferredLoaders!!) {
                        if (want.isBlank()) continue
                        for (have in fl) {
                            if (have.equals(want, ignoreCase = true)) {
                                loaderOk = true
                                break
                            }
                        }
                        if (loaderOk) break
                    }
                    if (!loaderOk) continue
                }
                return file
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * 获取已安装 mod 的 modId 集合。
     */
    private fun getInstalledModIds(dir: Path): HashSet<String> {
        val ids = HashSet<String>()
        if (!Files.isDirectory(dir)) return ids
        try {
            val mods = ModScanner.scanDirectory(dir)
            for (m in mods) {
                if (m.modId.isNotEmpty() && !m.disabled) {
                    ids.add(m.modId)
                }
            }
        } catch (_: Throwable) {
        }
        return ids
    }

    private fun resolveDestModsDir(versionId: String?): Path {
        if (!versionId.isNullOrBlank() && paths != null) {
            return GameDirResolver(paths, preferences).resolveModsDir(versionId)
        }
        return modsDir
    }

    /**
     * 下载并安装模组文件（走 DownloadManager，必须有哈希）。
     */
    private fun downloadAndInstall(modFile: ModFile, destDir: Path, onStatus: Consumer<String>?) {
        val target = destDir.resolve(modFile.fileName).toAbsolutePath().normalize()
        val modsAbs = destDir.toAbsolutePath().normalize()
        if (!target.startsWith(modsAbs)) {
            throw IOException("模组路径越界: ${modFile.fileName}")
        }
        Files.createDirectories(modsAbs)
        val sha1 = modFile.getSha1()
        val sha512 = modFile.getSha512()
        if (sha1.isNullOrEmpty() && sha512.isNullOrEmpty()) {
            throw IOException("模组无完整性哈希，拒绝下载: ${modFile.fileName}")
        }
        downloadManager.downloadToVerified(modFile.downloadUrl, target, sha1, sha512)
    }

    /**
     * 从依赖字符串中提取 modId。
     * Fabric 的 depends 可能是：
     * - 字符串数组 ["modId1", "modId2"]
     * - 对象数组 [{"modId1": ">=1.0"}, {"modId2": "*"}]
     * - 字符串 "modId@version"
     * Forge 的 depends 是 modId 字符串。
     */
    private fun extractModId(dep: String): String? {
        if (dep.isEmpty()) return null
        // 处理 "modId@version" 格式
        val atIdx = dep.indexOf('@')
        if (atIdx > 0) {
            return dep.substring(0, atIdx)
        }
        // 处理 JSON 对象形式（如 {"modId": "version"}），ModScanner 可能解析为 "modId" 或保留原始格式
        // 去除可能的引号和花括号
        val cleaned = dep.replace(Regex("[\"{}]"), "").trim()
        // 如果包含冒号，取冒号前部分
        val colonIdx = cleaned.indexOf(':')
        if (colonIdx > 0) {
            return cleaned.substring(0, colonIdx).trim()
        }
        return cleaned
    }

    /**
     * 判断是否为系统依赖（不需要安装）。
     */
    private fun isSystemDep(modId: String?): Boolean {
        if (modId == null) return false
        return SYSTEM_DEPS.contains(modId.lowercase())
    }
}
