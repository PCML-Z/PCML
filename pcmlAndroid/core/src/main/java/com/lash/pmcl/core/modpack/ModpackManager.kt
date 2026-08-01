package com.lash.pmcl.core.modpack

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallInterruptedException
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.market.ModFile
import com.lash.pmcl.core.market.ModrinthClient
import com.lash.pmcl.core.modloader.ModLoader
import com.lash.pmcl.core.modloader.ModLoaderManager
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.preferences.Preferences
import com.lash.pmcl.core.util.Exceptions
import com.lash.pmcl.core.util.FileUtils
import com.lash.pmcl.core.util.SafeZipExtractor
import com.lash.pmcl.core.util.SsrfChecker
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 整合包管理器（Android 简化版）：支持导入 Modrinth (.mrpack) / CurseForge (.zip) / 纯服务器包格式。
 *
 * 与桌面版的差异：
 * - 路径通过 [PmclPaths] 获取，替代桌面版 LauncherConfig
 * - 仅实现导入和列举功能，不实现导出（Android 端需求较低）
 * - [listInstalledModpacks] 只扫描 PMCL 自身 instances 目录，不扫描外部启动器（Android 无桌面启动器）
 * - [checkForUpdates] 仅对 Modrinth 格式有效，对比 source.json 中的 SHA1
 * - 文件读写使用 [FileUtils]（Android API 26 无 Files.readString/writeString）
 *
 * 导入流程：
 * 1. 解析 manifest（modrinth.index.json 或 manifest.json）
 * 2. 安装原版 Minecraft（调用 [VersionInstaller]）
 * 3. 安装模组加载器（如有，调用 [ModLoaderManager]）
 * 4. 下载所有 mods 文件到 instances/<name>/mods/
 * 5. 解压 overrides/ 到 instances/<name>/（config、resourcepacks 等）
 *
 * 保留全部安全防护：ZipSlip / ZipBomb / SHA-1 校验 / SSRF 校验 / sanitizeName（保留 Unicode）。
 */
class ModpackManager(
    private val paths: PmclPaths,
    private val downloads: DownloadManager,
    private val versionInstaller: VersionInstaller,
    private val modLoaderManager: ModLoaderManager,
    @Suppress("unused") private val preferences: Preferences
) {

    // ===== 数据类 =====

    /** 整合包清单信息（从 manifest 解析） */
    data class ModpackInfo(
        val name: String,
        val gameVersion: String,
        val loader: String?,
        val loaderVersion: String?,
        val format: String,
        val author: String?
    )

    /** 整合包中的单个模组文件信息 */
    data class ModpackFile(
        val path: String,
        val hash: String,
        val size: Long,
        val downloadUrl: String,
        val projectId: String?,
        val fileId: String?
    )

    /** 单个 mod 的更新信息 */
    data class ModUpdate(
        val fileName: String,
        val currentVersion: String,
        val latestVersion: String,
        val projectId: String,
        val downloadUrl: String,
        val loader: String
    )

    /** 整合包更新检查结果 */
    class ModpackUpdateResult(
        val instanceName: String,
        updates: List<ModUpdate>?,
        val totalChecked: Int,
        val error: String?
    ) {
        val updates: List<ModUpdate> = updates ?: ArrayList()

        fun isSuccess(): Boolean = error == null
        fun hasUpdates(): Boolean = updates.isNotEmpty()
    }

    /** 已安装的整合包实例 */
    data class InstalledModpack(
        val name: String,
        val gameVersion: String,
        val loader: String,
        val loaderVersion: String,
        val instanceDir: Path,
        val modCount: Long,
        val source: String
    )

    // ===== 导入 =====

    /**
     * 导入整合包文件。自动识别 Modrinth (.mrpack) 或 CurseForge (.zip) 格式。
     *
     * @param file      整合包文件路径
     * @param onProgress 进度回调
     */
    fun importModpack(file: Path, onProgress: Consumer<InstallProgress>?): CompletableFuture<Void> =
        CompletableFuture.runAsync {
            try {
                doImport(file, onProgress)
            } catch (e: Throwable) {
                onProgress?.accept(
                    InstallProgress(InstallProgress.Stage.FAILED, 0, 0,
                        "整合包导入失败: ${e.message}")
                )
                throw RuntimeException("整合包导入失败", e)
            }
        }

    private fun doImport(file: Path, progress: Consumer<InstallProgress>?) {
        if (!Files.exists(file)) {
            throw IOException("整合包文件不存在: $file")
        }

        // 1. 解析 manifest
        progress?.accept(InstallProgress(
            InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0, "正在解析整合包清单..."))

        val manifest = parseManifest(file)
        val instanceName = sanitizeName(manifest.name)
        var instanceDir = paths.instances.resolve(instanceName)

        // 如果实例目录已存在，追加序号
        var suffix = 1
        while (Files.exists(instanceDir)) {
            instanceDir = paths.instances.resolve("$instanceName-$suffix")
            suffix++
        }

        Files.createDirectories(instanceDir)
        for (sub in listOf("mods", "saves", "config", "resourcepacks",
                "shaderpacks", "screenshots", "logs")) {
            Files.createDirectories(instanceDir.resolve(sub))
        }

        // 2. 安装原版 Minecraft
        progress?.accept(InstallProgress(
            InstallProgress.Stage.DOWNLOAD_CLIENT, 0, 0,
            "正在安装 Minecraft ${manifest.gameVersion}..."))

        versionInstaller.install(manifest.gameVersion, Consumer { p -> progress?.accept(p) }).join()

        // 3. 安装模组加载器
        val loader = manifest.loader
        val loaderVersion = manifest.loaderVersion
        if (!loader.isNullOrEmpty() && !loaderVersion.isNullOrEmpty()) {
            progress?.accept(InstallProgress(
                InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 0,
                "正在安装 $loader $loaderVersion..."))

            val ml = parseLoader(loader)
            if (ml != null && modLoaderManager.supports(ml)) {
                modLoaderManager.get(ml).install(
                    manifest.gameVersion, loaderVersion,
                    Consumer { p -> progress?.accept(p) }).join()
            }
        }

        // 4. 下载 mods（任一下载失败则整体失败，避免「导入成功但零模组」）
        downloadModpackFiles(manifest, instanceDir, progress)

        // 5. 解压 overrides
        progress?.accept(InstallProgress(
            InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 0, "正在解压配置文件..."))

        extractOverrides(file, instanceDir, manifest.format)

        // 6. 保存实例信息
        saveInstanceInfo(instanceDir, manifest)

        progress?.accept(InstallProgress(
            InstallProgress.Stage.DONE, 0, 0, "整合包 '${manifest.name}' 导入完成"))
    }

    /** 并发下载整合包中的所有 mod 文件。任一中断会中止整体；普通失败累计后抛出。 */
    private fun downloadModpackFiles(
        manifest: ParsedManifest, instanceDir: Path,
        progress: Consumer<InstallProgress>?
    ) {
        val total = manifest.files.size
        progress?.accept(InstallProgress(
            InstallProgress.Stage.DOWNLOAD_ASSETS, 0, total.toLong(),
            "正在下载模组 (0/$total)..."))

        val completed = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val failSamples = Collections.synchronizedList(ArrayList<String>())
        val pool: ExecutorService = Executors.newFixedThreadPool(minOf(16, maxOf(2, total)))
        val instanceDirFinal = instanceDir.toAbsolutePath().normalize()
        try {
            val futures = ArrayList<CompletableFuture<*>>()
            for (mf in manifest.files) {
                futures.add(CompletableFuture.runAsync({
                    try {
                        downloadModpackFile(mf, instanceDirFinal)
                    } catch (e: Throwable) {
                        if (InstallInterruptedException.isInterrupted(e)) {
                            throw if (e is RuntimeException) e
                            else InstallInterruptedException("整合包模组下载已中断", e)
                        }
                        failCount.incrementAndGet()
                        val detail = mf.path + ": " + Exceptions.rootMessage(e)
                        if (failSamples.size < 5) failSamples.add(detail)
                        System.err.println("[ModpackManager] 模组下载失败: $detail")
                    }
                    val done = completed.incrementAndGet()
                    if (progress != null) {
                        progress.accept(InstallProgress(
                            InstallProgress.Stage.DOWNLOAD_ASSETS,
                            done.toLong(), total.toLong(),
                            "正在下载模组 ($done/$total)..."))
                    }
                }, pool))
            }
            try {
                CompletableFuture.allOf(*futures.toTypedArray()).join()
            } catch (ce: CompletionException) {
                val c = ce.cause ?: ce
                if (InstallInterruptedException.isInterrupted(c)) {
                    throw if (c is RuntimeException) c
                    else InstallInterruptedException("整合包导入已中断", c)
                }
                throw ce
            }
        } finally {
            pool.shutdownNow()
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        if (total > 0 && failCount.get() > 0) {
            val preview = failSamples.joinToString("; ")
            throw IOException("整合包模组下载失败 ${failCount.get()}/$total 个（示例: $preview）")
        }
    }

    /** 下载单个整合包模组文件（含 CF URL/SHA 解析）。 */
    @Throws(IOException::class)
    private fun downloadModpackFile(mf: ModpackFile, instanceDirAbs: Path) {
        var url: String? = mf.downloadUrl
        var sha1: String = mf.hash
        // CF 整合包 manifest 不含 downloadUrl/hash，需通过 API 查询
        // 简化版未注入 market client，无法解析 CF 文件下载信息（返回空）
        if ((url.isNullOrEmpty() || sha1.isBlank())
            && !mf.projectId.isNullOrEmpty() && !mf.fileId.isNullOrEmpty()) {
            val resolved = resolveCurseForgeFile(mf.projectId, mf.fileId)
            if (url.isNullOrEmpty()) url = resolved.url
            if (sha1.isBlank()) sha1 = resolved.sha1
        }
        if (url.isNullOrEmpty()) {
            throw IOException("无下载 URL")
        }
        validateDownloadUrl(url)
        val target = instanceDirAbs.resolve(mf.path).normalize()
        if (!target.startsWith(instanceDirAbs)) {
            throw IOException("非法路径: ${mf.path}")
        }
        target.parent?.let { Files.createDirectories(it) }
        if (sha1.isBlank()) {
            // 最后手段：无哈希则下载后拒绝过小文件
            downloads.downloadTo(url, target)
            if (Files.size(target) < 32) {
                Files.deleteIfExists(target)
                throw IOException("下载文件过小且无 SHA-1")
            }
            return
        }
        downloads.downloadToVerified(url, target, sha1, null)
    }

    @Throws(IOException::class)
    private fun validateDownloadUrl(url: String) {
        if (url.isEmpty()) throw IOException("空下载 URL")
        val uri: java.net.URI
        try {
            uri = URI.create(url)
        } catch (e: Exception) {
            throw IOException("非法下载 URL: $url", e)
        }
        val scheme = uri.scheme
        if (!"https".equals(scheme, ignoreCase = true) && !"http".equals(scheme, ignoreCase = true)) {
            throw IOException("非法下载协议: $scheme")
        }
        val host = uri.host ?: throw IOException("下载 URL 缺少 host: $url")
        // 内网主机校验
        try {
            val addr = InetAddress.getByName(host)
            if (SsrfChecker.isInternalAddress(addr)) {
                throw IOException("非法下载主机（内网地址）: $host")
            }
        } catch (e: UnknownHostException) {
            throw IOException("无法解析下载主机: $host", e)
        }
    }

    /**
     * 通过 CurseForge API 查询模组文件的下载 URL 与 SHA-1。
     * 简化版未注入 CurseForge 客户端，直接返回空结果（CF 整合包需后续接入 API Key 才能完整下载）。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun resolveCurseForgeFile(projectId: String, fileId: String): CfResolved {
        return CfResolved("", "")
    }

    private class CfResolved(val url: String, val sha1: String)

    // ===== manifest 解析 =====

    @Throws(IOException::class)
    private fun parseManifest(file: Path): ParsedManifest {
        ZipFile(file.toFile()).use { zf ->
            // 尝试 Modrinth 格式
            zf.getEntry("modrinth.index.json")?.let { return parseModrinthManifest(zf, it) }
            // 尝试 CurseForge 格式
            zf.getEntry("manifest.json")?.let { return parseCurseForgeManifest(zf, it) }
            // 尝试纯 zip/服务器包（无 manifest，检测 mods/ 目录）
            val entries = Collections.list(zf.entries())
            val hasMods = entries.any { e ->
                e.name.startsWith("mods/") && e.name.endsWith(".jar")
            } || zf.getEntry("mods/") != null
            if (hasMods) {
                return parseServerPackManifest(zf)
            }
            throw IOException("无法识别的整合包格式：缺少 modrinth.index.json、manifest.json 或 mods/ 目录")
        }
    }

    @Throws(IOException::class)
    private fun parseModrinthManifest(zf: ZipFile, entry: ZipEntry): ParsedManifest {
        val json: String
        zf.getInputStream(entry).use { inp ->
            json = String(SafeZipExtractor.readLimited(inp, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8)
        }
        val root = JsonParser.parseString(json).asJsonObject

        val name = safeStr(root, "name", "未命名整合包")
        val deps = if (root.has("dependencies") && !root.get("dependencies").isJsonNull)
            root.getAsJsonObject("dependencies") else JsonObject()

        val gameVersion = safeStr(deps, "minecraft", "")
        var loader: String? = null
        var loaderVersion: String? = null

        if (deps.has("fabric-loader") && !deps.get("fabric-loader").isJsonNull) {
            loader = "fabric"; loaderVersion = deps.get("fabric-loader").asString
        } else if (deps.has("quilt-loader") && !deps.get("quilt-loader").isJsonNull) {
            loader = "quilt"; loaderVersion = deps.get("quilt-loader").asString
        } else if (deps.has("forge") && !deps.get("forge").isJsonNull) {
            loader = "forge"; loaderVersion = deps.get("forge").asString
        } else if (deps.has("neoforge") && !deps.get("neoforge").isJsonNull) {
            loader = "neoforge"; loaderVersion = deps.get("neoforge").asString
        }

        val files = ArrayList<ModpackFile>()
        if (root.has("files") && root.get("files").isJsonArray) {
            for (e in root.getAsJsonArray("files")) {
                val f = e.asJsonObject
                val path = safeStr(f, "path", "")
                if (path.isEmpty()) continue
                var hash = ""
                if (f.has("hashes") && f.get("hashes").isJsonObject) {
                    val h = f.getAsJsonObject("hashes")
                    hash = safeStr(h, "sha1", "")
                }
                val size = if (f.has("size") && !f.get("size").isJsonNull)
                    f.get("size").asLong else 0L
                var downloadUrl = ""
                if (f.has("downloads") && f.get("downloads").isJsonArray
                    && f.getAsJsonArray("downloads").size() > 0) {
                    downloadUrl = f.getAsJsonArray("downloads")[0].asString
                }
                files.add(ModpackFile(path, hash, size, downloadUrl, null, null))
            }
        }

        return ParsedManifest(name, gameVersion, loader, loaderVersion, "modrinth", files, null)
    }

    @Throws(IOException::class)
    private fun parseCurseForgeManifest(zf: ZipFile, entry: ZipEntry): ParsedManifest {
        val json: String
        zf.getInputStream(entry).use { inp ->
            json = String(SafeZipExtractor.readLimited(inp, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8)
        }
        val root = JsonParser.parseString(json).asJsonObject

        val name = safeStr(root, "name", "未命名整合包")
        val author = safeStr(root, "author", "")

        val minecraft = if (root.has("minecraft") && !root.get("minecraft").isJsonNull)
            root.getAsJsonObject("minecraft") else JsonObject()

        val gameVersion = safeStr(minecraft, "version", "")

        var loader: String? = null
        var loaderVersion: String? = null
        if (minecraft.has("modLoaders") && minecraft.get("modLoaders").isJsonArray) {
            for (ml in minecraft.getAsJsonArray("modLoaders")) {
                val mlObj = ml.asJsonObject
                val id = safeStr(mlObj, "id", "")
                if (id.startsWith("fabric-")) {
                    loader = "fabric"; loaderVersion = id.substring("fabric-".length); break
                } else if (id.startsWith("forge-")) {
                    loader = "forge"; loaderVersion = id.substring("forge-".length); break
                } else if (id.startsWith("quilt-")) {
                    loader = "quilt"; loaderVersion = id.substring("quilt-".length); break
                } else if (id.startsWith("neoforge-")) {
                    loader = "neoforge"; loaderVersion = id.substring("neoforge-".length); break
                }
            }
        }

        val files = ArrayList<ModpackFile>()
        if (root.has("files") && root.get("files").isJsonArray) {
            for (f in root.getAsJsonArray("files")) {
                val fObj = f.asJsonObject
                val projectId = if (fObj.has("projectID") && !fObj.get("projectID").isJsonNull)
                    fObj.get("projectID").asString else ""
                val fileId = if (fObj.has("fileID") && !fObj.get("fileID").isJsonNull)
                    fObj.get("fileID").asString else ""
                // CurseForge manifest 不含下载 URL；安装阶段由 resolveCurseForgeFile 补全
                files.add(ModpackFile(
                    "mods/${projectId}_${fileId}.jar",
                    "", 0L, "", projectId, fileId))
            }
        }

        return ParsedManifest(name, gameVersion, loader, loaderVersion, "curseforge", files, author)
    }

    /** 解析纯 zip/服务器包（无 manifest，直接扫描 mods/ 目录） */
    @Throws(IOException::class)
    private fun parseServerPackManifest(zf: ZipFile): ParsedManifest {
        val files = ArrayList<ModpackFile>()
        val entries = Collections.list(zf.entries())
        for (e in entries) {
            val name = e.name
            if (name.startsWith("mods/") && name.endsWith(".jar") && !e.isDirectory) {
                val fileName = name.substring(name.lastIndexOf('/') + 1)
                files.add(ModpackFile("mods/$fileName", "", 0L, "", null, null))
            }
        }
        // 服务器包无版本信息，需要用户手动指定
        return ParsedManifest("服务器包", "", "", "", "serverpack", files, "Server")
    }

    @Throws(IOException::class)
    private fun extractOverrides(file: Path, instanceDir: Path, format: String) {
        // modrinth/curseforge 用 "overrides/" 前缀；serverpack 无前缀直接解压到根目录
        val prefixes: List<String> = if (format == "serverpack") listOf("") else listOf("overrides/")

        val maxTotal = SafeZipExtractor.DEFAULT_MAX_TOTAL_SIZE
        val maxEntry = SafeZipExtractor.DEFAULT_MAX_ENTRY_SIZE
        val maxEntries = SafeZipExtractor.DEFAULT_MAX_ENTRIES
        val maxRatio = SafeZipExtractor.DEFAULT_MAX_RATIO
        val instanceDirAbs = instanceDir.toAbsolutePath().normalize()
        var totalSize = 0L
        var entryCount = 0

        ZipFile(file.toFile()).use { zf ->
            val entries = Collections.list(zf.entries())
            for (entry in entries) {
                if (++entryCount > maxEntries) {
                    throw IOException("ZipBomb detected: entry count exceeds limit $maxEntries")
                }
                if (entry.isDirectory) continue
                val name = entry.name

                var relative: String? = null
                for (prefix in prefixes) {
                    if (name.startsWith(prefix)) {
                        relative = name.substring(prefix.length)
                        break
                    }
                }
                if (relative.isNullOrEmpty()) continue

                // ZipSlip 防护：失败即中止（禁止静默跳过）
                if (relative.contains("..") || relative.startsWith("/") || relative.startsWith("\\")
                    || relative.matches(Regex("^[A-Za-z]:[\\\\/].*"))) {
                    throw IOException("ZipSlip: overrides 包含非法路径条目: $name")
                }
                val target = instanceDirAbs.resolve(relative).normalize()
                if (!target.startsWith(instanceDirAbs)) {
                    throw IOException("ZipSlip: overrides 路径越界: $name")
                }

                target.parent?.let { Files.createDirectories(it) }
                val compressed = entry.compressedSize
                zf.getInputStream(entry).use { inp ->
                    val entrySize = SafeZipExtractor.copyLimited(inp, target, maxEntry)
                    totalSize += entrySize
                    if (totalSize > maxTotal) {
                        throw IOException("ZipBomb detected: total extracted size exceeds $maxTotal bytes in $file")
                    }
                    if (compressed > 0 && entrySize > compressed * maxRatio.toLong()) {
                        try { Files.deleteIfExists(target) } catch (_: IOException) {}
                        throw IOException("ZipBomb detected: compression ratio exceeds $maxRatio:1 for $name")
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun saveInstanceInfo(instanceDir: Path, manifest: ParsedManifest) {
        val info = JsonObject()
        info.addProperty("name", manifest.name)
        info.addProperty("gameVersion", manifest.gameVersion)
        info.addProperty("loader", manifest.loader ?: "")
        info.addProperty("loaderVersion", manifest.loaderVersion ?: "")
        info.addProperty("format", manifest.format)
        manifest.author?.let { info.addProperty("author", it) }
        info.addProperty("installedAt", System.currentTimeMillis())

        FileUtils.writeString(instanceDir.resolve("modpack.json"), info.toString())

        // 保存完整 source manifest（含 files 数组及 SHA1 哈希），用于更新检查
        val source = info.deepCopy()
        val filesArr = JsonArray()
        for (mf in manifest.files) {
            val fo = JsonObject()
            fo.addProperty("path", mf.path)
            fo.addProperty("hash", mf.hash)
            fo.addProperty("size", mf.size)
            fo.addProperty("downloadUrl", mf.downloadUrl)
            mf.projectId?.let { fo.addProperty("projectId", it) }
            mf.fileId?.let { fo.addProperty("fileId", it) }
            filesArr.add(fo)
        }
        source.add("files", filesArr)
        FileUtils.writeString(instanceDir.resolve("source.json"), source.toString())
    }

    private fun parseLoader(loader: String): ModLoader? = when (loader.lowercase()) {
        "fabric" -> ModLoader.FABRIC
        "forge" -> ModLoader.FORGE
        "quilt" -> ModLoader.QUILT
        "neoforge" -> ModLoader.NEOFORGE
        else -> null
    }

    /**
     * 规范化实例名：仅过滤文件系统非法字符，保留中文/日文/韩文等 Unicode 字符。
     * 空白与控制字符替换为下划线，去除尾部点和空格（Windows 不允许文件名以 . 结尾）。
     */
    private fun sanitizeName(name: String?): String {
        if (name == null) return "unnamed"
        val sb = StringBuilder(name.length)
        for (i in name.indices) {
            val c = name[i]
            if (c == '/' || c == '\\' || c == ':' || c == '*'
                || c == '?' || c == '"' || c == '<' || c == '>'
                || c == '|' || c < '\u0020') {
                sb.append('_')
            } else if (c == ' ' || c == '\t') {
                sb.append('_')
            } else {
                sb.append(c)
            }
        }
        var result = sb.toString()
        while (result.endsWith(".") || result.endsWith(" ")) {
            result = result.substring(0, result.length - 1)
        }
        return if (result.isEmpty()) "unnamed" else result
    }

    private fun safeStr(obj: JsonObject, key: String, default: String): String {
        if (obj.has(key) && !obj.get(key).isJsonNull) {
            return try { obj.get(key).asString } catch (_: Exception) { default }
        }
        return default
    }

    /** 内部解析结果容器 */
    private class ParsedManifest(
        val name: String,
        val gameVersion: String,
        val loader: String?,
        val loaderVersion: String?,
        val format: String,
        val files: List<ModpackFile>,
        val author: String?
    )

    // ===== 列出已安装整合包 =====

    /**
     * 列出已安装的整合包实例。
     * Android 版只扫描 PMCL 自身 instances 目录，不扫描外部启动器。
     */
    fun listInstalledModpacks(): List<InstalledModpack> {
        val result = ArrayList<InstalledModpack>()
        scanInstances(paths.instances, "PMCL", result)
        return result
    }

    /** 扫描指定 instances 目录下的所有整合包实例 */
    private fun scanInstances(instancesDir: Path, source: String, result: MutableList<InstalledModpack>) {
        if (!Files.isDirectory(instancesDir)) return
        try {
            Files.list(instancesDir).use { stream ->
                val it = stream.iterator()
                while (it.hasNext()) {
                    val dir = it.next()
                    if (!Files.isDirectory(dir)) continue
                    parseInstance(dir, source)?.let { mp -> result.add(mp) }
                }
            }
        } catch (_: IOException) {
            // 跳过无法扫描的目录
        }
    }

    /** 解析单个实例目录的 modpack.json，失败返回 null */
    private fun parseInstance(dir: Path, source: String): InstalledModpack? {
        val infoFile = dir.resolve("modpack.json")
        if (!Files.exists(infoFile)) return null
        return try {
            val json = FileUtils.readString(infoFile, StandardCharsets.UTF_8)
            val o = JsonParser.parseString(json).asJsonObject
            val name = safeStr(o, "name", dir.fileName?.toString() ?: "unnamed")
            val gameVersion = safeStr(o, "gameVersion", "")
            val loader = safeStr(o, "loader", "")
            val loaderVersion = safeStr(o, "loaderVersion", "")

            var modCount = 0L
            val modsDir = dir.resolve("mods")
            if (Files.isDirectory(modsDir)) {
                try {
                    Files.list(modsDir).use { s ->
                        modCount = s.filter { p ->
                            p.toString().lowercase().endsWith(".jar")
                        }.count()
                    }
                } catch (_: IOException) {
                    // 忽略 mods 目录扫描失败
                }
            }
            InstalledModpack(name, gameVersion, loader, loaderVersion, dir, modCount, source)
        } catch (_: Throwable) {
            // 跳过损坏的实例
            null
        }
    }

    // ===== 删除整合包实例 =====

    @Throws(IOException::class)
    fun deleteModpack(name: String?) {
        if (name == null || name.contains("..") || name.contains("/") || name.contains("\\")
            || name.indexOf('\u0000') >= 0) {
            throw IOException("非法整合包名称: $name")
        }
        val instancesRoot = paths.instances.toAbsolutePath().normalize()
        val dir = instancesRoot.resolve(name).normalize()
        if (!dir.startsWith(instancesRoot)) {
            throw IOException("路径越界: $name")
        }
        if (!Files.isDirectory(dir)) {
            throw IOException("整合包实例不存在: $name")
        }
        deleteRecursive(dir)
    }

    @Throws(IOException::class)
    private fun deleteRecursive(path: Path) {
        // 符号链接循环防护：对符号链接本身只删除链接不递归
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        if (Files.isDirectory(path)) {
            Files.list(path).use { stream ->
                val it = stream.iterator()
                while (it.hasNext()) {
                    deleteRecursive(it.next())
                }
            }
        }
        Files.deleteIfExists(path)
    }

    // ===== 检查更新（仅 Modrinth 格式）=====

    /** 懒加载 Modrinth 客户端（复用 DownloadManager 的 OkHttpClient） */
    private val modrinthClient: ModrinthClient by lazy { ModrinthClient(downloads) }

    /**
     * 检查整合包更新（仅对 Modrinth 格式有效）。
     *
     * 通过 source.json 中保存的 SHA1 批量查询 Modrinth API 获取当前版本信息，
     * 再对比每个项目的最新版本，返回有更新的 mod 列表。
     *
     * @param instanceName 实例名称
     * @return 更新检查结果
     */
    fun checkForUpdates(instanceName: String?): CompletableFuture<ModpackUpdateResult> =
        CompletableFuture.supplyAsync {
            // 路径遍历防护（与 deleteModpack 一致）
            if (instanceName == null || instanceName.isBlank()
                || instanceName.contains("..") || instanceName.contains("/")
                || instanceName.contains("\\") || instanceName.indexOf('\u0000') >= 0) {
                throw IllegalArgumentException("非法实例名称: $instanceName")
            }
            val instancesRoot = paths.instances.toAbsolutePath().normalize()
            val instanceDir = instancesRoot.resolve(instanceName).normalize()
            if (!instanceDir.startsWith(instancesRoot)) {
                throw IllegalArgumentException("实例路径越界: $instanceName")
            }
            val sourceFile = instanceDir.resolve("source.json")
            if (!Files.isRegularFile(sourceFile)) {
                return@supplyAsync ModpackUpdateResult(
                    instanceName, ArrayList(), 0,
                    "缺少 source.json，无法检查更新（仅 Modrinth 格式支持）"
                )
            }
            try {
                val source = JsonParser.parseString(
                    FileUtils.readString(sourceFile, StandardCharsets.UTF_8)
                ).asJsonObject
                val gameVersion = safeStr(source, "gameVersion", "")
                val loader = safeStr(source, "loader", "")

                if (!source.has("files") || !source.get("files").isJsonArray) {
                    return@supplyAsync ModpackUpdateResult(instanceName, ArrayList(), 0, null)
                }
                val filesArr = source.getAsJsonArray("files")
                if (filesArr.size() == 0) {
                    return@supplyAsync ModpackUpdateResult(instanceName, ArrayList(), 0, null)
                }

                // 收集有 SHA1 哈希的 mod 文件
                val hashes = ArrayList<String>()
                val hashToFile = LinkedHashMap<String, String>()
                for (e in filesArr) {
                    val fo = e.asJsonObject
                    val path = safeStr(fo, "path", "")
                    val hash = safeStr(fo, "hash", "")
                    if (hash.isNotEmpty() && path.isNotEmpty()) {
                        hashes.add(hash)
                        hashToFile[hash] = path
                    }
                }
                if (hashes.isEmpty()) {
                    return@supplyAsync ModpackUpdateResult(instanceName, ArrayList(), 0, null)
                }

                // 批量查询当前哈希对应的版本信息
                val currentVersions: Map<String, JsonObject> = try {
                    modrinthClient.batchCheckBySha1(hashes)
                } catch (e: Exception) {
                    return@supplyAsync ModpackUpdateResult(
                        instanceName, ArrayList(), 0,
                        "Modrinth API 查询失败: ${e.message}"
                    )
                }

                // hash -> { projectId, currentVersionId, currentVersionNumber }
                val hashToProjectId = LinkedHashMap<String, String>()
                val hashToCurrentVersionId = HashMap<String, String>()
                val hashToCurrentVersionNumber = HashMap<String, String>()
                for (hash in hashes) {
                    val verInfo = currentVersions[hash] ?: continue
                    val pid = safeStr(verInfo, "project_id", "")
                    val vid = safeStr(verInfo, "id", "")
                    val vnum = safeStr(verInfo, "version_number", "")
                    if (pid.isNotEmpty()) {
                        hashToProjectId[hash] = pid
                        hashToCurrentVersionId[hash] = vid
                        hashToCurrentVersionNumber[hash] = vnum
                    }
                }

                val checkedCount = hashToProjectId.size
                val resultMap = ConcurrentHashMap<String, ModUpdate>()
                val pool: ExecutorService = Executors.newFixedThreadPool(
                    minOf(8, maxOf(2, hashToProjectId.size))
                )
                try {
                    val futures = ArrayList<CompletableFuture<*>>()
                    for (hash in hashToProjectId.keys) {
                        val pid = hashToProjectId[hash]!!
                        val currentVid = hashToCurrentVersionId[hash]!!
                        val currentVnum = hashToCurrentVersionNumber[hash]!!
                        var fileName = hashToFile[hash] ?: ""
                        if (fileName.startsWith("mods/")) {
                            fileName = fileName.substring("mods/".length)
                        }
                        val fn = fileName
                        futures.add(CompletableFuture.runAsync({
                            try {
                                val latest = findLatestVersion(pid, gameVersion, loader)
                                if (latest == null) return@runAsync
                                // 对比 version_id，不同则有更新
                                if (latest.fileId.isNotEmpty() && latest.fileId != currentVid) {
                                    resultMap[hash] = ModUpdate(
                                        fn, currentVnum, latest.fileName,
                                        pid, latest.downloadUrl, loader
                                    )
                                }
                            } catch (e: Exception) {
                                // 单个 mod 查询失败不中断整体检查
                                System.err.println("[ModpackManager] 查询 $pid 最新版本失败: ${e.message}")
                            }
                        }, pool))
                    }
                    CompletableFuture.allOf(*futures.toTypedArray()).join()
                } finally {
                    pool.shutdownNow()
                    try {
                        pool.awaitTermination(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }

                // 按原 hashToProjectId 顺序收集结果，保持顺序稳定
                val updates = ArrayList<ModUpdate>()
                for (hash in hashToProjectId.keys) {
                    resultMap[hash]?.let { updates.add(it) }
                }
                ModpackUpdateResult(instanceName, updates, checkedCount, null)
            } catch (e: Exception) {
                ModpackUpdateResult(instanceName, ArrayList(), 0, "检查更新失败: ${e.message}")
            }
        }

    /**
     * 查询项目在指定游戏版本和加载器下的最新版本文件。
     * Modrinth listFiles 返回版本按新→旧排序，优先返回 release 类型。
     *
     * @param projectId  Modrinth 项目 ID
     * @param gameVersion 目标 Minecraft 版本（空表示不过滤）
     * @param loader      目标加载器（空表示不过滤）
     * @return 最新匹配的 ModFile，无匹配时返回 null
     */
    private fun findLatestVersion(
        projectId: String, gameVersion: String, loader: String
    ): ModFile? {
        val allFiles = modrinthClient.listFiles(projectId).join()
        val matched = ArrayList<ModFile>()
        for (f in allFiles) {
            val gvs = f.getGameVersions()
            val lds = f.getLoaders()
            if (gameVersion.isNotEmpty() && gvs.isNotEmpty() && !gvs.contains(gameVersion)) continue
            if (loader.isNotEmpty() && lds.isNotEmpty() && !lds.contains(loader)) continue
            matched.add(f)
        }
        if (matched.isEmpty()) return null
        // 优先 release 类型
        for (f in matched) {
            if (f.releaseType == "release") return f
        }
        return matched[0]
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
    }
}
