package com.lash.pmcl.core.install

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.download.DownloadTask
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.Exceptions
import com.lash.pmcl.core.util.FileUtils
import com.lash.pmcl.core.util.SafeZipExtractor
import com.lash.pmcl.core.version.McVersion
import com.lash.pmcl.core.version.VersionManager
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * 版本安装器：拉取版本 JSON → 解析 → 下载 client.jar + libraries + assets。
 *
 * Android 版本：
 * - 路径通过 [PmclPaths] 获取，替代桌面版 LauncherConfig
 * - 移除 Apple Silicon / LoongArch64 / RISC-V 特殊处理（桌面专属，Android 使用 ARM/x86 native）
 * - Files.readString/writeString 替换为 [FileUtils]（Android API 26 无 Java 11 API）
 * - 保留全部安全防护：SHA-1 校验、ZipSlip/ZipBomb 防护、staging 原子提升、继承版本合并
 *
 * 版本私有文件（json / client.jar / natives）写入 `versions/{id}.staging/`，
 * 全部成功后再原子提升为 `versions/{id}/`，避免半成品被扫描为可启动版本。
 * libraries / assets 仍写入共享目录（带 SHA 与 .part 续传）。
 */
class VersionInstaller(
    private val paths: PmclPaths,
    private val versionManager: VersionManager,
    private val downloadManager: DownloadManager
) {

    fun install(
        versionId: String,
        onProgress: Consumer<InstallProgress>?
    ): CompletableFuture<Void> = CompletableFuture.runAsync {
        val stagingDir = paths.versions.resolve(versionId + VersionStaging.STAGING_SUFFIX)
        try {
            VersionStaging.assertSafeVersionId(versionId)
            doInstall(versionId, onProgress)
        } catch (e: Throwable) {
            android.util.Log.e("PMCL", "[VersionInstaller] 安装失败: $versionId — ${Exceptions.rootMessage(e)}", e)
            if (InstallInterruptedException.isInterrupted(e)) {
                // 暂停/取消：保留 staging 与 .part，供断点续传
                throw if (e is RuntimeException) e
                else InstallInterruptedException("安装已中断", e)
            }
            try { FileUtils.deleteRecursively(stagingDir) } catch (_: IOException) {}
            val detail = Exceptions.rootMessage(e)
            onProgress?.accept(
                InstallProgress(InstallProgress.Stage.FAILED, 0, 0, detail)
            )
            throw RuntimeException("安装失败: $versionId — $detail", e)
        }
    }

    private fun doInstall(versionId: String, onProgress: Consumer<InstallProgress>?) {
        // 1. 找到版本元信息
        val target = findVersion(versionId)

        val stagingName = versionId + VersionStaging.STAGING_SUFFIX
        val stagingDir = paths.versions.resolve(stagingName)
        Files.createDirectories(stagingDir)

        // 2. 下载版本 JSON → staging
        onProgress?.accept(
            InstallProgress(InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1, "下载版本清单")
        )
        val versionJsonPath = stagingDir.resolve("$versionId.json")
        val versionSha1 = target.sha1
        if (versionSha1.isBlank()) {
            throw IOException("版本清单缺少 SHA-1，拒绝下载: $versionId")
        }
        val versionJsonStr = downloadManager.downloadStringVerified(target.url, versionSha1)
        FileUtils.writeString(versionJsonPath, versionJsonStr)
        // 持久化版本清单的 SHA-1，供启动时校验本地 JSON 完整性
        val versionSha1Path = stagingDir.resolve("$versionId.json.sha1")
        FileUtils.writeString(versionSha1Path, versionSha1)

        var vj = VersionJson.parse(versionJsonStr)

        // 处理继承：合并父版本 JSON
        if (!vj.inheritsFrom.isNullOrEmpty() && vj.inheritsFrom != versionId) {
            vj = mergeInherited(vj, vj.inheritsFrom)
        }

        val tasks = ArrayList<DownloadTask>()
        // 按相对路径去重：MC 1.12 等旧版本会把同一 jar 列两次
        val seenPaths = HashSet<String>()

        // 3. client.jar → staging
        vj.clientArtifact?.let { c ->
            addTask(tasks, seenPaths, DownloadTask(
                c.url, c.sha1, c.size,
                "versions/$stagingName/$versionId.jar"
            ))
        }

        // 4. libraries（含 native classifier）→ 共享 libraries/
        onProgress?.accept(
            InstallProgress(
                InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, vj.libraries.size.toLong(),
                "扫描依赖库"
            )
        )
        for (lib in vj.libraries) {
            if (!lib.appliesToCurrentOs()) continue
            // 主 artifact
            lib.artifact?.let { a ->
                addTask(tasks, seenPaths, DownloadTask(
                    a.url, a.sha1, a.size,
                    "libraries/${lib.getPath()}"
                ))
            }
            // native classifier（按当前 OS + 架构选择）
            if (lib.isNativeLib) {
                lib.getNativeArtifact()?.let { n ->
                    val classifier = lib.getNativeClassifier()
                    addTask(tasks, seenPaths, DownloadTask(
                        n.url, n.sha1, n.size,
                        "libraries/${lib.getPathForClassifier(classifier)}"
                    ))
                }
            }
        }

        // 5. 资产索引
        if (vj.assets.isNotEmpty()) {
            onProgress?.accept(
                InstallProgress(InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 1, "下载资产索引")
            )
            val assetIndexUrl = resolveAssetIndexUrl(vj)
            val assetIndexSha1 = resolveAssetIndexSha1(vj)
            if (assetIndexUrl.isNullOrBlank()) {
                throw IOException("版本声明了 assets=${vj.assets} 但缺少 assetIndex.url，拒绝安装")
            }
            if (assetIndexSha1.isNullOrBlank()) {
                throw IOException("assetIndex 缺少 sha1，拒绝无完整性校验的索引下载")
            }
            val idxPath = paths.assets.resolve("indexes").resolve("${vj.assets}.json")
            Files.createDirectories(idxPath.parent)
            downloadManager.downloadToVerified(assetIndexUrl, idxPath, assetIndexSha1, null)
            val idxJson = FileUtils.readString(idxPath)

            val idx = AssetIndex.parse(idxJson)
            for (a in idx.getAssets().values) {
                addTask(tasks, seenPaths, DownloadTask(
                    RESOURCE_BASE + a.getPath(),
                    a.hash, a.size,
                    "assets/objects/${a.getPath()}"
                ))
            }
        }

        // 6. 执行批量下载
        val total = tasks.sumOf { it.size }
        checkDiskSpace(paths.minecraftWorkDir, total)
        downloadManager.downloadAll(
            tasks,
            Consumer { },
            Consumer { bytes ->
                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, bytes, total,
                        String.format("下载中 %d / %d bytes", bytes, total)
                    )
                )
            }
        ).join()

        // 7. 解压 native 库到 staging/natives
        extractNatives(vj, stagingDir.resolve("natives"))

        // 8. 原子提升 staging → versions/{id}
        VersionStaging.promote(paths.versions, versionId, stagingDir)

        onProgress?.accept(
            InstallProgress(InstallProgress.Stage.DONE, total, total, "安装完成: $versionId")
        )
    }

    /**
     * 解压所有 native jar 到指定 natives 目录。
     * 排除 META-INF（避免签名文件冲突）。
     */
    private fun extractNatives(vj: VersionJson, nativesDir: Path) {
        Files.createDirectories(nativesDir)
        val nativesDirAbs = nativesDir.toAbsolutePath().normalize()
        // 清空旧 natives
        try {
            Files.list(nativesDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { p ->
                    try { Files.deleteIfExists(p) } catch (_: IOException) {}
                }
            }
        } catch (_: IOException) {}

        for (lib in vj.libraries) {
            if (!lib.appliesToCurrentOs() || !lib.isNativeLib) continue
            val classifier = lib.getNativeClassifier() ?: continue
            val nativeJar = paths.libraries.resolve(lib.getPathForClassifier(classifier))
            if (!Files.exists(nativeJar)) {
                throw IOException("缺少 native 库，无法解压: $nativeJar")
            }
            val maxTotal = SafeZipExtractor.DEFAULT_MAX_TOTAL_SIZE
            val maxEntries = SafeZipExtractor.DEFAULT_MAX_ENTRIES
            var totalSize = 0L
            var entryCount = 0
            var extracted = 0
            try {
                ZipFile(nativeJar.toFile()).use { zip ->
                    val en = zip.entries()
                    while (en.hasMoreElements()) {
                        val entry = en.nextElement()
                        if (++entryCount > maxEntries) {
                            throw IOException("ZipBomb detected: entry count exceeds limit $maxEntries in $nativeJar")
                        }
                        if (entry.isDirectory) continue
                        val name = entry.name
                        // 跳过签名文件与元数据
                        if (name.startsWith("META-INF/")) continue
                        // ZipSlip 防护
                        if (name.isEmpty()) {
                            throw IOException("native zip 含空路径条目: $nativeJar")
                        }
                        if (name.startsWith("/") || name.startsWith("\\") ||
                            name.matches(Regex("^[A-Za-z]:[\\\\/].*"))
                        ) {
                            throw IOException("ZipSlip: native 绝对路径条目 '$name' in $nativeJar")
                        }
                        val hasDotDot = name.replace('\\', '/').split("/").any { it == ".." }
                        if (hasDotDot) {
                            throw IOException("ZipSlip: native 路径含 .. '$name' in $nativeJar")
                        }
                        val target = nativesDir.resolve(name).toAbsolutePath().normalize()
                        if (!target.startsWith(nativesDirAbs)) {
                            throw IOException("ZipSlip: native 路径越界 '$name' in $nativeJar")
                        }
                        val parent = target.parent
                        if (parent == null || !parent.startsWith(nativesDirAbs)) {
                            throw IOException("ZipSlip: native 父目录越界 '$name' in $nativeJar")
                        }
                        Files.createDirectories(parent)
                        zip.getInputStream(entry).use { inp ->
                            Files.newOutputStream(
                                target,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                            ).use { out ->
                                val buf = ByteArray(8192)
                                var n: Int
                                while (inp.read(buf).also { n = it } > 0) {
                                    totalSize += n
                                    if (totalSize > maxTotal) {
                                        throw IOException("ZipBomb detected: total extracted size exceeds $maxTotal bytes in $nativeJar")
                                    }
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        extracted++
                    }
                }
            } catch (e: java.util.zip.ZipException) {
                throw IOException("native 库不是有效 zip，安装中止: $nativeJar", e)
            }
            if (extracted == 0) {
                throw IOException("native 库解压结果为空: $nativeJar")
            }
        }
    }

    /**
     * 磁盘空间预检：预留 10% 余量。
     */
    private fun checkDiskSpace(workDir: Path, requiredBytes: Long) {
        if (requiredBytes <= 0) return
        try {
            val store = Files.getFileStore(workDir)
            val usable = store.usableSpace
            if (usable < 0) return
            val requiredWithMargin = (requiredBytes * 1.1).toLong() + (50L * 1024 * 1024)
            if (usable < requiredWithMargin) {
                val needMb = requiredWithMargin / (1024 * 1024)
                val haveMb = usable / (1024 * 1024)
                val storeName = store.name()
                throw IOException("磁盘空间不足: 需要 $needMb MB（含 10% 余量），可用 $haveMb MB。请清理磁盘后重试。（目标分区: $storeName）")
            }
        } catch (e: java.nio.file.FileSystemException) {
            System.err.println("[VersionInstaller] 磁盘空间预检跳过: ${e.message}")
        } catch (e: SecurityException) {
            // Android 不允许 getFileStore()，跳过磁盘空间检查
            System.err.println("[VersionInstaller] 磁盘空间预检跳过（Android 限制）: ${e.message}")
        }
    }

    /** 远程版本清单缓存，避免每次安装都拉取整个清单 */
    @Volatile
    private var cachedRemoteVersions: List<McVersion>? = null

    private fun findVersion(versionId: String): McVersion {
        var versions = cachedRemoteVersions
        if (versions == null) {
            versions = versionManager.fetchRemoteVersions().join()
            cachedRemoteVersions = versions
        }
        for (v in versions) {
            if (v.id == versionId) return v
        }
        throw IOException("版本不存在: $versionId")
    }

    /** 按相对路径去重后加入下载队列；同路径只保留首次出现的任务。 */
    private fun addTask(tasks: MutableList<DownloadTask>, seenPaths: MutableSet<String>, task: DownloadTask) {
        if (task.relativePath.isEmpty()) return
        if (seenPaths.add(task.relativePath)) {
            tasks.add(task)
        }
    }

    private fun resolveAssetIndexUrl(vj: VersionJson): String? {
        val root = vj.rawJson
        if (root.has("assetIndex")) {
            val ai = root.getAsJsonObject("assetIndex")
            if (ai.has("url")) return ai.get("url").asString
        }
        return null
    }

    private fun resolveAssetIndexSha1(vj: VersionJson): String? {
        val root = vj.rawJson
        if (root.has("assetIndex")) {
            val ai = root.getAsJsonObject("assetIndex")
            if (ai.has("sha1") && !ai.get("sha1").isJsonNull) {
                return ai.get("sha1").asString
            }
        }
        return null
    }

    /**
     * 合并继承版本的 JSON：父版本为主，子版本覆盖 mainClass 等。
     * 递归合并，带循环检测和深度限制。
     */
    private fun mergeInherited(child: VersionJson, parentId: String): VersionJson {
        return mergeInheritedRecursive(child, parentId, HashSet(), 0)
    }

    private fun mergeInheritedRecursive(
        child: VersionJson, parentId: String,
        visiting: MutableSet<String>, depth: Int
    ): VersionJson {
        if (depth > 16) {
            throw IOException("版本继承链过深（>$depth）: $visiting，可能存在异常 inheritsFrom 链")
        }
        if (!visiting.add(parentId)) {
            throw IOException("检测到循环版本继承: $visiting -> $parentId")
        }
        try {
            // 复用 findVersion 已拉取的缓存，避免重复网络调用
            var versions = cachedRemoteVersions
            if (versions == null) {
                versions = versionManager.fetchRemoteVersions().join()
                cachedRemoteVersions = versions
            }
            var parent: McVersion? = null
            for (v in versions) {
                if (v.id == parentId) { parent = v; break }
            }
            if (parent == null) {
                throw IOException("找不到 inheritsFrom 父版本: $parentId")
            }
            if (parent.url.isEmpty()) {
                throw IOException("父版本缺少下载 URL: $parentId")
            }
            val parentSha1 = parent.sha1
            if (parentSha1.isBlank()) {
                throw IOException("父版本清单缺少 SHA-1，拒绝下载: $parentId")
            }

            val parentJson = downloadManager.downloadStringVerified(parent.url, parentSha1)
            var parentObj = JsonParser.parseString(parentJson).asJsonObject

            // 递归处理父版本的 inheritsFrom
            val parentVj = VersionJson.parse(parentJson)
            if (!parentVj.inheritsFrom.isNullOrEmpty() && parentVj.inheritsFrom != parentId) {
                val merged = mergeInheritedRecursive(parentVj, parentVj.inheritsFrom, visiting, depth + 1)
                parentObj = merged.rawJson
            }

            val childObj = child.rawJson

            // 子版本若没有 mainClass/assetIndex，则用父版本
            if (!childObj.has("mainClass") && parentObj.has("mainClass")) {
                childObj.add("mainClass", parentObj.get("mainClass"))
            }
            if (!childObj.has("assets") && parentObj.has("assets")) {
                childObj.add("assets", parentObj.get("assets"))
            }
            if (!childObj.has("assetIndex") && parentObj.has("assetIndex")) {
                childObj.add("assetIndex", parentObj.get("assetIndex"))
            }
            if (!childObj.has("downloads") && parentObj.has("downloads")) {
                childObj.add("downloads", parentObj.get("downloads"))
            }
            // 合并 arguments.game/jvm
            if (parentObj.has("arguments")) {
                val parentArgs = parentObj.getAsJsonObject("arguments")
                if (!childObj.has("arguments")) {
                    childObj.add("arguments", parentArgs)
                } else {
                    val childArgs = childObj.getAsJsonObject("arguments")
                    if (parentArgs.has("game")) {
                        val mergedGame = com.google.gson.JsonArray()
                        if (childArgs.has("game")) {
                            for (e in childArgs.getAsJsonArray("game")) mergedGame.add(e)
                        }
                        for (e in parentArgs.getAsJsonArray("game")) mergedGame.add(e)
                        childArgs.add("game", mergedGame)
                    }
                    if (parentArgs.has("jvm")) {
                        val mergedJvm = com.google.gson.JsonArray()
                        if (childArgs.has("jvm")) {
                            for (e in childArgs.getAsJsonArray("jvm")) mergedJvm.add(e)
                        }
                        for (e in parentArgs.getAsJsonArray("jvm")) mergedJvm.add(e)
                        childArgs.add("jvm", mergedJvm)
                    }
                }
            }
            // 合并旧格式 minecraftArguments
            if (!childObj.has("minecraftArguments") && parentObj.has("minecraftArguments")) {
                childObj.add("minecraftArguments", parentObj.get("minecraftArguments"))
            }
            // 继承 javaVersion
            if (!childObj.has("javaVersion") && parentObj.has("javaVersion")) {
                childObj.add("javaVersion", parentObj.get("javaVersion"))
            }
            // 合并 libraries（子的覆盖父的同名库）
            if (parentObj.has("libraries")) {
                val merged = com.google.gson.JsonArray()
                val childNames = HashSet<String>()
                if (childObj.has("libraries")) {
                    for (e in childObj.getAsJsonArray("libraries")) {
                        merged.add(e)
                        val libObj = e.asJsonObject
                        if (libObj.has("name") && !libObj.get("name").isJsonNull) {
                            childNames.add(libObj.get("name").asString)
                        }
                    }
                }
                for (e in parentObj.getAsJsonArray("libraries")) {
                    val libObj = e.asJsonObject
                    if (!libObj.has("name") || libObj.get("name").isJsonNull) continue
                    val name = libObj.get("name").asString
                    if (name !in childNames) merged.add(e)
                }
                childObj.add("libraries", merged)
            }
            return VersionJson.parse(childObj.toString())
        } finally {
            visiting.remove(parentId)
        }
    }

    companion object {
        private const val RESOURCE_BASE = "https://resources.download.minecraft.net/"
    }
}
