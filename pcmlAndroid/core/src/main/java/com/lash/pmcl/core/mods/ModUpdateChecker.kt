package com.lash.pmcl.core.mods

import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.market.CurseForgeClient
import com.lash.pmcl.core.market.ModFile
import com.lash.pmcl.core.market.ModMarketClient
import com.lash.pmcl.core.market.ModProject
import com.lash.pmcl.core.market.ModrinthClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * 模组更新检测器：扫描已安装模组，在 Modrinth/CurseForge 上检测是否有新版本。
 *
 * 检测策略：
 * 1. 用 modId 作为 slug 直接调用 Modrinth `/project/{slug}/version`（多数 fabric mod 的 modId 即 slug）
 * 2. 失败则用 `search(modId)` 搜索，取 slug 完全匹配或首个结果
 * 3. 按 gameVersion + loader 过滤版本列表，取首个（API 默认按日期倒序）
 * 4. 比较本地 mod 版本号与远程文件名中的版本提示；无可靠版本则不提示更新
 *
 * 一键更新：删除旧 jar 文件 + 下载新 jar（复用 DownloadManager）。
 *
 * Android 版：通过构造函数注入 modsDir / modrinthClient / curseForgeClient / downloadManager / executor，
 * 不再依赖 InstanceManager / ModMarketManager。
 */
class ModUpdateChecker(
    private val modsDir: Path,
    private val modrinthClient: ModrinthClient,
    private val curseForgeClient: CurseForgeClient,
    private val downloadManager: DownloadManager,
    private val executor: ExecutorService
) {

    /**
     * 更新检测结果。
     */
    data class UpdateInfo(
        val installed: ModMeta,
        val project: ModProject?,     // 匹配的市场项目（null 表示未找到）
        val latestFile: ModFile?,     // 最新兼容文件（null 表示无兼容版本）
        val source: String?,          // "modrinth" / "curseforge"
        val hasUpdate: Boolean,       // 是否有更新
        val reason: String            // 状态说明（如 "未找到项目"/"已是最新"/"有新版本"）
    ) {
        /** 显示名（优先用市场项目名，fallback 到 mod 元数据名） */
        fun displayName(): String {
            if (project != null && project.name.isNotEmpty()) {
                return project.name
            }
            val n = installed.name
            return if (n.isNotEmpty()) n else installed.modId
        }
    }

    /**
     * 批量检测模组更新。
     *
     * @param mods        已安装模组列表
     * @param gameVersion 目标 MC 版本（如 "1.20.4"），用于过滤兼容文件
     * @param onProgress  进度回调（已完成数 / 总数），可为 null
     * @return 更新检测结果列表
     */
    fun checkUpdates(
        mods: List<ModMeta>?,
        gameVersion: String?,
        onProgress: Consumer<IntArray>?
    ): CompletableFuture<List<UpdateInfo>> {
        if (mods.isNullOrEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
        }
        val gv = gameVersion
        val snapshot = ArrayList(mods)
        val total = snapshot.size
        val completed = AtomicInteger(0)

        val futures = snapshot.map { mod ->
            CompletableFuture.supplyAsync({
                val info = checkOne(mod, gv)
                val done = completed.incrementAndGet()
                onProgress?.accept(intArrayOf(done, total))
                info
            }, executor)
        }

        return CompletableFuture.allOf(*futures.toTypedArray<CompletableFuture<*>>())
            .thenApply {
                val results = ArrayList<UpdateInfo>()
                for (f in futures) {
                    try {
                        results.add(f.join())
                    } catch (_: Throwable) {
                        // 单个检测失败不影响整体
                    }
                }
                results
            }
    }

    /**
     * 检测单个模组是否有更新。
     */
    private fun checkOne(mod: ModMeta, gameVersion: String?): UpdateInfo {
        if (mod.modId.isEmpty()) {
            return UpdateInfo(mod, null, null, null, false, "无 modId")
        }
        if (mod.disabled) {
            return UpdateInfo(mod, null, null, null, false, "已禁用，跳过")
        }

        // 尝试每个市场客户端
        for (client in getMarketClients()) {
            try {
                val info = checkOnClient(mod, gameVersion, client)
                if (info != null) return info
            } catch (_: Throwable) {
                // 该客户端检测失败，尝试下一个
            }
        }
        return UpdateInfo(mod, null, null, null, false, "未找到项目")
    }

    /** 获取市场客户端列表 */
    private fun getMarketClients(): List<ModMarketClient> = listOf(modrinthClient, curseForgeClient)

    /**
     * 在单个市场客户端上检测更新。
     *
     * @return UpdateInfo，若无法匹配则返回 null
     */
    private fun checkOnClient(mod: ModMeta, gameVersion: String?, client: ModMarketClient): UpdateInfo? {
        val source = client.source()
        val modId = mod.modId

        // 步骤1：尝试用 modId 作为 projectId/slug 直接 listFiles
        var project: ModProject? = null
        var files: List<ModFile>? = null
        try {
            files = client.listFiles(modId).join()
            // 构造一个虚拟的 ModProject（listFiles 成功说明 modId 即 slug/id）
            project = ModProject(
                source, modId, modId,
                mod.name, mod.description, mod.authors,
                0L, "", ""
            )
        } catch (_: Throwable) {
            // 直接查询失败，走 search
        }

        // 步骤2：search 查找项目
        if (project == null) {
            val results = client.search(modId, gameVersion ?: "", normalizeLoader(mod.loader) ?: "", 5).join()
            if (results.isNullOrEmpty()) return null

            // 仅接受 slug/id 精确匹配，禁止「取第一个」误更新为无关模组
            for (p in results) {
                if (modId.equals(p.slug, ignoreCase = true) || modId.equals(p.id, ignoreCase = true)) {
                    project = p
                    break
                }
            }
            if (project == null) {
                return UpdateInfo(mod, null, null, source, false,
                    "未找到与 modId 匹配的项目（已拒绝模糊匹配）")
            }
            files = client.listFiles(project.id).join()
        }

        if (files.isNullOrEmpty()) {
            return UpdateInfo(mod, project, null, source, false, "无可用版本")
        }

        // 步骤3：按 gameVersion + loader 过滤
        val loader = normalizeLoader(mod.loader)
        val compatible = ArrayList<ModFile>()
        for (f in files) {
            val gvMatch = gameVersion.isNullOrEmpty() ||
                f.getGameVersions().contains(gameVersion)
            // 要求显式 loader 匹配；空 loaders 不再视为通配（避免 Forge jar 推给 Fabric）
            val loaderMatch = loader.isNullOrEmpty() ||
                f.getLoaders().contains(loader)
            if (gvMatch && loaderMatch) {
                compatible.add(f)
            }
        }

        if (compatible.isEmpty()) {
            return UpdateInfo(mod, project, null, source, false,
                "无 $gameVersion/$loader 兼容版本")
        }

        // 步骤4：取最新文件（列表通常按日期倒序，取第一个）
        val latest = compatible[0]

        // 步骤5：优先用版本号判断；禁止仅凭文件名不同就判定有更新（重命名误报）
        val localJar = mod.jarFile
        val remoteName = latest.fileName
        val localVer = mod.version
        val hasUpdate: Boolean
        val reason: String
        if (localJar.isNotEmpty() && localJar.equals(remoteName, ignoreCase = true)) {
            hasUpdate = false
            reason = "已是最新"
        } else if (localVer.isNotBlank() && !"unknown".equals(localVer, ignoreCase = true)) {
            val remoteVer = extractVersionHint(remoteName)
            if (remoteVer != null && localVer.equals(remoteVer, ignoreCase = true)) {
                hasUpdate = false
                reason = "已是最新 (v$localVer)"
            } else if (remoteVer != null) {
                hasUpdate = true
                reason = "新版本: $remoteVer ($remoteName)"
            } else {
                // 远程文件名无法解析版本：不因文件名不同而 fail-open
                hasUpdate = false
                reason = "无法从远程文件名解析版本，跳过更新提示"
            }
        } else {
            hasUpdate = false
            reason = "本地无可靠版本号，跳过文件名比对"
        }

        return UpdateInfo(mod, project, latest, source, hasUpdate, reason)
    }

    /** 从 jar 文件名提取版本提示：取最后一个以数字开头的 `-` 分段。 */
    private fun extractVersionHint(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        var base = fileName
        val dot = base.lastIndexOf('.')
        if (dot > 0) base = base.substring(0, dot)
        val parts = base.split("[-_]".toRegex())
        for (i in parts.indices.reversed()) {
            val p = parts[i]
            if (p.isEmpty()) continue
            val c = p[0]
            if (c in '0'..'9') return p
        }
        return null
    }

    /**
     * 规范化加载器名称：fabric→fabric, forge→forge, neoforge→neoforge, quilt→quilt。
     * unknown 返回 null（不过滤 loader）。
     */
    private fun normalizeLoader(loader: String?): String? {
        if (loader.isNullOrEmpty() || loader.equals("unknown", ignoreCase = true)) {
            return null
        }
        return loader.lowercase()
    }

    /**
     * 更新单个模组：备份旧 jar + 下载新 jar + 成功后删除备份（失败回滚）。
     *
     * @param info        更新信息
     * @param gameVersion 目标 MC 版本（Android 版不再用于路径解析，保留以维持 API 兼容）
     * @param versionId   版本 ID（同上），可为 null
     * @param onStatus    状态回调，可为 null
     */
    fun updateMod(
        info: UpdateInfo?, gameVersion: String?, versionId: String?,
        onStatus: Consumer<String>?
    ): CompletableFuture<Void> {
        if (info == null || !info.hasUpdate || info.latestFile == null) {
            return CompletableFuture.failedFuture(IllegalStateException("无可用更新"))
        }
        val safeInfo = info
        return CompletableFuture.runAsync({
            try {
                // S13: 先备份旧 jar，下载成功后才删除，失败时恢复
                val mod = safeInfo.installed
                val oldJar = modsDir.resolve(mod.jarFile)

                var backup: Path? = null
                if (Files.exists(oldJar)) {
                    backup = oldJar.resolveSibling(oldJar.fileName.toString() + ".pmcl-bak")
                    Files.move(oldJar, backup, StandardCopyOption.REPLACE_EXISTING)
                    onStatus?.accept("已备份旧版本: ${mod.jarFile}")
                }

                try {
                    // 下载新 jar
                    onStatus?.accept("正在下载: ${safeInfo.latestFile!!.fileName}")
                    downloadModFile(safeInfo.latestFile!!)
                    // 下载成功，删除备份
                    if (backup != null) {
                        Files.deleteIfExists(backup)
                    }
                    onStatus?.accept("更新完成: ${safeInfo.displayName()}")
                } catch (e: Exception) {
                    // 下载失败，恢复备份
                    if (backup != null && Files.exists(backup)) {
                        try {
                            Files.move(backup, oldJar, StandardCopyOption.REPLACE_EXISTING)
                        } catch (restoreErr: Exception) {
                            System.err.println("[ModUpdateChecker] 恢复备份失败: ${restoreErr.message}")
                        }
                    }
                    throw e
                }
            } catch (e: Exception) {
                throw RuntimeException("更新失败: ${safeInfo.displayName()}", e)
            }
        }, executor)
    }

    /**
     * 下载模组文件到 modsDir（使用 DownloadManager，支持 SHA1/SHA512 校验）。
     */
    private fun downloadModFile(modFile: ModFile) {
        val target = modsDir.resolve(modFile.fileName).toAbsolutePath().normalize()
        val modsAbs = modsDir.toAbsolutePath().normalize()
        if (!target.startsWith(modsAbs)) {
            throw IOException("模组路径越界: ${modFile.fileName}")
        }
        Files.createDirectories(modsAbs)
        val sha1 = modFile.getSha1()
        val sha512 = modFile.getSha512()
        if (!sha1.isNullOrEmpty() || !sha512.isNullOrEmpty()) {
            downloadManager.downloadToVerified(modFile.downloadUrl, target, sha1, sha512)
        } else {
            downloadManager.downloadTo(modFile.downloadUrl, target)
        }
    }

    /**
     * 批量更新所有有更新的模组。
     *
     * @param updates    更新信息列表（仅 hasUpdate=true 的会被更新）
     * @param gameVersion 目标 MC 版本
     * @param versionId  版本 ID
     * @param onProgress 进度回调（已完成数 / 总数），可为 null
     */
    fun updateAll(
        updates: List<UpdateInfo>?, gameVersion: String?, versionId: String?,
        onProgress: Consumer<IntArray>?
    ): CompletableFuture<Void> {
        if (updates.isNullOrEmpty()) {
            return CompletableFuture.completedFuture(null)
        }

        val toUpdate = updates.filter { it.hasUpdate }
        if (toUpdate.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }

        val total = toUpdate.size
        val completed = AtomicInteger(0)

        val futures = toUpdate.map { info ->
            CompletableFuture.runAsync({
                try {
                    updateMod(info, gameVersion, versionId, null).join()
                } catch (_: Throwable) {
                    // 单个更新失败不影响整体
                } finally {
                    val done = completed.incrementAndGet()
                    onProgress?.accept(intArrayOf(done, total))
                }
            }, executor)
        }.toTypedArray<CompletableFuture<*>>()

        return CompletableFuture.allOf(*futures)
    }
}
