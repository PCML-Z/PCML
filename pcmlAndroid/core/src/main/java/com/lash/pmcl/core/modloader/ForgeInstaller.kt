package com.lash.pmcl.core.modloader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.download.DownloadTask
import com.lash.pmcl.core.install.InstallInterruptedException
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionStaging
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.Exceptions
import com.lash.pmcl.core.util.FileUtils
import com.lash.pmcl.core.util.SafeZipExtractor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Forge 安装器（含 NeoForge）—— Android 简化版。
 *
 * 流程：
 *   1) 拉取 BMCLAPI 版本列表
 *   2) 下载 installer.jar
 *   3) 从 installer.jar 提取 install_profile.json 与 version JSON
 *   4) 写入 versions/{id}.staging/
 *   5) 提取内嵌 maven 库 + 下载远端库（含 downloads.artifact）
 *   6) 跳过 client-side processors（Android 无法 fork JVM），仅显示警告
 *   7) 原子提升为正式版本
 *
 * 注意：因未执行 Forge processors，部分（1.13+）版本可能无法正常工作。
 */
class ForgeInstaller(
    private val paths: PmclPaths,
    private val downloads: DownloadManager,
    private val neoForge: Boolean
) : ModLoaderInstaller {

    override fun listVersions(gameVersion: String): CompletableFuture<List<ModLoaderVersion>> {
        return CompletableFuture.supplyAsync {
            try {
                val url = if (neoForge) "$NEOFORGE_LIST_URL$gameVersion"
                else "$BMCLAPI_BASE$gameVersion"
                val json = downloads.downloadString(url)
                val arr = parseJsonArray(json, "加载器版本列表 $url")
                val result = ArrayList<ModLoaderVersion>()
                for (e in arr) {
                    val o = e.asJsonObject
                    if (neoForge) {
                        val version = if (o.has("version") && !o.get("version").isJsonNull)
                            o.get("version").asString else ""
                        if (version.isEmpty()) continue
                        val encoded = when {
                            o.has("installerPath") && !o.get("installerPath").isJsonNull ->
                                version + "|" + o.get("installerPath").asString
                            o.has("rawVersion") && !o.get("rawVersion").isJsonNull ->
                                version + "|" + o.get("rawVersion").asString
                            else -> version
                        }
                        result.add(
                            ModLoaderVersion(ModLoader.NEOFORGE, gameVersion, encoded, true)
                        )
                    } else {
                        result.add(
                            ModLoaderVersion(
                                ModLoader.FORGE,
                                gameVersion,
                                if (o.has("version") && !o.get("version").isJsonNull)
                                    o.get("version").asString else "",
                                !o.has("branch") || o.get("branch").isJsonNull
                                        || "null" == o.get("branch").asString
                            )
                        )
                    }
                }
                result
            } catch (ex: Throwable) {
                throw RuntimeException(
                    "拉取" + (if (neoForge) " NeoForge" else " Forge") + " 版本失败", ex
                )
            }
        }
    }

    override fun install(
        gameVersion: String,
        loaderVersion: String,
        onProgress: Consumer<InstallProgress>?
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            var installerJar: Path? = null
            val loaderName = if (neoForge) "NeoForge" else "Forge"
            var versionId: String? = null
            var stagingDir: Path? = null
            try {
                // 1. 下载 installer.jar
                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "下载 $loaderName installer.jar"
                    )
                )
                installerJar = Files.createTempFile("forge-installer-", ".jar")
                downloadInstallerJar(gameVersion, loaderVersion, installerJar, loaderName)

                // 2. 提取 install_profile.json / version JSON
                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "解析 install_profile.json"
                    )
                )
                val profile = extractInstallProfile(installerJar)
                val versionJson = resolveVersionJson(installerJar, profile)
                versionId = if (versionJson.has("id") && !versionJson.get("id").isJsonNull)
                    versionJson.get("id").asString else ""
                if (versionId.isEmpty()) {
                    throw IOException("$loaderName installer 未包含有效的版本 id")
                }

                // 3. 写入 staging（依赖与 processors 就绪后再 promote）
                stagingDir = VersionStaging.writeVersionJson(
                    paths.versions, versionId, versionJson.toString()
                )

                // 4. 提取内嵌库 + 收集远端库（profile + version.json，含 downloads.artifact）
                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                        "提取/下载 $loaderName 依赖库"
                    )
                )
                val embeddedPaths = HashSet<String>()
                val embedded = extractEmbeddedMaven(installerJar, paths.libraries, embeddedPaths)
                val remoteLibs = ArrayList<DownloadTask>()
                collectLibraryDownloads(profile, remoteLibs, embeddedPaths)
                collectLibraryDownloads(versionJson, remoteLibs, embeddedPaths)

                if (remoteLibs.isNotEmpty()) {
                    onProgress?.accept(
                        InstallProgress(
                            InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, remoteLibs.size.toLong(),
                            "下载 $loaderName 依赖库 (${remoteLibs.size} 个)"
                        )
                    )
                    downloadRemoteLibraries(remoteLibs)
                }

                // 5. 跳过 client processors（Android 无法 fork JVM）
                if (profile.has("processors") && profile.get("processors").isJsonArray
                    && profile.getAsJsonArray("processors").size() > 0
                ) {
                    onProgress?.accept(
                        InstallProgress(
                            InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 0,
                            "警告: Forge processors 未执行，部分版本可能无法正常工作"
                        )
                    )
                }

                // 6. 原子提升
                VersionStaging.promote(paths.versions, versionId, stagingDir)
                stagingDir = null

                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        "$loaderName 安装完成: $versionId" +
                                "（内嵌库 $embedded，远端库 ${remoteLibs.size}）"
                    )
                )
            } catch (e: Exception) {
                if (!InstallInterruptedException.isInterrupted(e)) {
                    versionId?.takeIf { it.isNotBlank() }?.let {
                        VersionStaging.discard(paths.versions, it)
                    }
                    stagingDir?.let { FileUtils.deleteRecursively(it) }
                }
                val detail = Exceptions.rootMessage(e)
                onProgress?.accept(
                    InstallProgress(InstallProgress.Stage.FAILED, 0, 0, detail)
                )
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw if (e is RuntimeException) e
                    else InstallInterruptedException("$loaderName 安装已中断", e)
                }
                throw RuntimeException("$loaderName 安装失败: $detail", e)
            } finally {
                installerJar?.let {
                    try {
                        Files.deleteIfExists(it)
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }

    /** 从 installer.jar 读取 install_profile.json */
    @Throws(IOException::class)
    private fun extractInstallProfile(installerJar: Path): JsonObject {
        ZipFile(installerJar.toFile()).use { zip ->
            var entry = zip.getEntry("install_profile.json")
            if (entry == null) entry = zip.getEntry("install_profile")
            if (entry == null) {
                throw IOException("installer.jar 中找不到 install_profile.json（可能下载了错误的文件）")
            }
            zip.getInputStream(entry).use { inp ->
                val json = String(inp.readBytes(), StandardCharsets.UTF_8)
                return parseJsonObject(json, "install_profile.json")
            }
        }
    }

    /**
     * 解压 installer.jar 中 maven/ 目录下的库到 libraries 目录。
     *
     * @return 内嵌库数量
     */
    @Throws(IOException::class)
    private fun extractEmbeddedMaven(
        installerJar: Path, librariesDir: Path, embeddedPaths: MutableSet<String>
    ): Int {
        var count = 0
        val maxTotal = SafeZipExtractor.DEFAULT_MAX_TOTAL_SIZE
        val maxEntries = SafeZipExtractor.DEFAULT_MAX_ENTRIES
        var totalSize = 0L
        var entryCount = 0
        val librariesAbs = librariesDir.toAbsolutePath().normalize()
        ZipFile(installerJar.toFile()).use { zip ->
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (++entryCount > maxEntries) {
                    throw IOException("ZipBomb detected: entry count exceeds limit $maxEntries in $installerJar")
                }
                val name = e.name
                if (!e.isDirectory && (name.startsWith("maven/") || name.startsWith("libraries/"))) {
                    val relPath = if (name.startsWith("maven/"))
                        name.substring("maven/".length)
                    else
                        name.substring("libraries/".length)
                    if (relPath.isEmpty()) continue
                    if (relPath.contains("..") || relPath.startsWith("/") || relPath.startsWith("\\")) {
                        throw IOException("ZipSlip: installer maven 路径非法: $name")
                    }
                    val target = librariesDir.resolve(relPath).toAbsolutePath().normalize()
                    if (!target.startsWith(librariesAbs)) {
                        throw IOException("ZipSlip: installer 解压越界: $name")
                    }
                    Files.createDirectories(target.parent)
                    zip.getInputStream(e).use { inp ->
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
                                    throw IOException("ZipBomb detected: total extracted size exceeds $maxTotal bytes in $installerJar")
                                }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    embeddedPaths.add(relPath)
                    count++
                }
            }
        }
        return count
    }

    /**
     * 从 profile / version JSON 的 libraries 数组收集下载任务。
     * 优先使用 `downloads.artifact`（现代 Forge）；否则按 name + url 拼 Maven 路径。
     */
    private fun collectLibraryDownloads(
        root: JsonObject?, remoteLibs: MutableList<DownloadTask>, embeddedPaths: MutableSet<String>
    ) {
        if (root == null || !root.has("libraries") || !root.get("libraries").isJsonArray) return
        for (e in root.getAsJsonArray("libraries")) {
            if (!e.isJsonObject) continue
            val lib = e.asJsonObject

            var path: String? = null
            var url: String? = null
            var sha1 = ""
            var size = 0L

            if (lib.has("downloads") && lib.get("downloads").isJsonObject) {
                val dl = lib.getAsJsonObject("downloads")
                if (dl.has("artifact") && dl.get("artifact").isJsonObject) {
                    val art = dl.getAsJsonObject("artifact")
                    if (art.has("path") && !art.get("path").isJsonNull) {
                        path = art.get("path").asString
                    }
                    if (art.has("url") && !art.get("url").isJsonNull) {
                        url = art.get("url").asString
                    }
                    if (art.has("sha1") && !art.get("sha1").isJsonNull) {
                        sha1 = art.get("sha1").asString
                    }
                    if (art.has("size") && !art.get("size").isJsonNull) {
                        size = art.get("size").asLong
                    }
                }
            }

            if (path.isNullOrBlank()) {
                if (!lib.has("name") || lib.get("name").isJsonNull) continue
                path = ForgeMavenCoords.toPath(lib.get("name").asString)
            }
            val resolvedPath = path ?: continue
            if (resolvedPath.isBlank()) continue

            if (embeddedPaths.contains(resolvedPath)) continue
            // 去重：已在 remoteLibs
            val rel = "libraries/$resolvedPath"
            var exists = false
            for (t in remoteLibs) {
                if (rel == t.relativePath) {
                    exists = true; break
                }
            }
            if (exists) continue

            val resolvedUrl = url
            val finalUrl: String = if (resolvedUrl.isNullOrBlank()) {
                var downloadPath = resolvedPath
                if (lib.has("name") && !lib.get("name").isJsonNull) {
                    downloadPath = mavenDownloadPath(lib.get("name").asString)
                }
                var base = BMCLAPI_MAVEN
                if (lib.has("url") && !lib.get("url").isJsonNull) {
                    base = lib.get("url").asString
                    if (!base.endsWith("/")) base += "/"
                }
                base + downloadPath
            } else if (resolvedUrl.contains("maven.minecraftforge.net/")) {
                resolvedUrl.replace("https://maven.minecraftforge.net/", BMCLAPI_MAVEN)
                    .replace("http://maven.minecraftforge.net/", BMCLAPI_MAVEN)
            } else if (resolvedUrl.contains("maven.neoforged.net/")) {
                resolvedUrl.replace("https://maven.neoforged.net/", BMCLAPI_MAVEN)
                    .replace("http://maven.neoforged.net/", BMCLAPI_MAVEN)
            } else {
                resolvedUrl
            }

            remoteLibs.add(DownloadTask(finalUrl, sha1, size, rel))
            embeddedPaths.add(resolvedPath)
        }
    }

    /**
     * maven 坐标对应的实际下载路径。
     * 旧版 Forge（≤1.12）在 versionInfo 里写 `net.minecraftforge:forge:VER`（无 classifier），
     * 但 Maven 上只有 `forge-VER-universal.jar`；落盘仍用无 classifier 文件名以便启动。
     */
    private fun mavenDownloadPath(coords: String): String {
        val c = ForgeMavenCoords.stripBrackets(coords)
        val parts = c.split(":")
        if (parts.size == 3
            && "net.minecraftforge" == parts[0]
            && "forge" == parts[1]
            && !c.contains("@")
        ) {
            val groupPath = parts[0].replace('.', '/')
            val artifact = parts[1]
            val version = parts[2]
            return "$groupPath/$artifact/$version/$artifact-$version-universal.jar"
        }
        return ForgeMavenCoords.toPath(coords)
    }

    private fun downloadInstallerJar(
        gameVersion: String, loaderVersion: String, target: Path, loaderName: String
    ) {
        val urls = buildInstallerUrls(gameVersion, loaderVersion)
        val expectedSha1 = resolveInstallerSha1(gameVersion, loaderVersion, urls)
        if (expectedSha1 == null || expectedSha1.length < 40) {
            throw IOException("$loaderName installer 缺少可校验的 SHA-1（已尝试版本列表与 Maven .sha1）")
        }
        var last: IOException? = null
        for (url in urls) {
            try {
                downloads.downloadToVerified(url, target, expectedSha1, null)
                return
            } catch (e: IOException) {
                last = e
            }
        }
        throw IOException(
            "$loaderName installer 下载失败，已尝试 ${urls.size} 个源: ${last?.message ?: ""}", last
        )
    }

    /** Forge/NeoForge installer 候选下载地址（按优先级）。 */
    private fun buildInstallerUrls(gameVersion: String, loaderVersion: String): List<String> {
        val urls = ArrayList<String>()
        if (!neoForge) {
            val artifact = if (loaderVersion.startsWith("$gameVersion-"))
                loaderVersion else "$gameVersion-$loaderVersion"
            urls.add(
                BMCLAPI_MAVEN + "net/minecraftforge/forge/$artifact/forge-$artifact-installer.jar"
            )
            urls.add(FORGE_MAVEN + "$artifact/forge-$artifact-installer.jar")
            urls.add("$BMCLAPI_BASE$gameVersion/$loaderVersion/jar")
            return urls
        }
        val parts = loaderVersion.split("|", limit = 2)
        val version = parts[0]
        if (parts.size == 2) {
            val encoded = parts[1]
            if (encoded.startsWith("/")) {
                urls.add("https://bmclapi2.bangbang93.com$encoded")
            } else {
                urls.add(
                    BMCLAPI_MAVEN + "net/neoforged/forge/$encoded/forge-$encoded-installer.jar"
                )
            }
        }
        urls.add(
            BMCLAPI_MAVEN + "net/neoforged/neoforge/$version/neoforge-$version-installer.jar"
        )
        return urls
    }

    /** 解析 installer SHA-1：BMCLAPI 版本列表 files[].hash → 官方 Maven .sha1 → 各候选 URL+.sha1。 */
    private fun resolveInstallerSha1(
        gameVersion: String, loaderVersion: String, installerUrls: List<String>
    ): String? {
        if (!neoForge) {
            val fromList = fetchForgeInstallerHashFromList(gameVersion, loaderVersion)
            if (fromList != null) return fromList
            val artifact = if (loaderVersion.startsWith("$gameVersion-"))
                loaderVersion else "$gameVersion-$loaderVersion"
            val officialSha1 = tryDownloadSha1(
                FORGE_MAVEN + "$artifact/forge-$artifact-installer.jar.sha1"
            )
            if (officialSha1 != null) return officialSha1
        }
        for (url in installerUrls) {
            val s = tryDownloadSha1("$url.sha1")
            if (s != null) return s
        }
        return null
    }

    /** 从 BMCLAPI Forge 版本列表读取 installer 文件的 hash。 */
    private fun fetchForgeInstallerHashFromList(gameVersion: String, loaderVersion: String): String? {
        try {
            val json = downloads.downloadString("$BMCLAPI_BASE$gameVersion")
            val arr = parseJsonArray(json, "Forge 版本列表 $gameVersion")
            val want = if (loaderVersion.startsWith("$gameVersion-"))
                loaderVersion.substring(gameVersion.length + 1) else loaderVersion
            for (e in arr) {
                if (!e.isJsonObject) continue
                val o = e.asJsonObject
                val ver = if (o.has("version") && !o.get("version").isJsonNull)
                    o.get("version").asString else ""
                if (want != ver && loaderVersion != ver) continue
                if (!o.has("files") || !o.get("files").isJsonArray) continue
                for (fe in o.getAsJsonArray("files")) {
                    if (!fe.isJsonObject) continue
                    val f = fe.asJsonObject
                    val cat = if (f.has("category") && !f.get("category").isJsonNull)
                        f.get("category").asString else ""
                    val fmt = if (f.has("format") && !f.get("format").isJsonNull)
                        f.get("format").asString else ""
                    if ("installer" == cat && "jar" == fmt
                        && f.has("hash") && !f.get("hash").isJsonNull
                    ) {
                        val hash = f.get("hash").asString.trim()
                        if (hash.length >= 40) return hash
                    }
                }
            }
        } catch (_: Exception) {
            // 列表拉取失败时由 Maven .sha1 回退
        }
        return null
    }

    private fun tryDownloadSha1(sha1Url: String): String? {
        return try {
            val body = downloads.downloadString(sha1Url).trim()
            val hash = body.split(Regex("\\s+"))[0]
            if (hash.length >= 40) hash else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 install_profile 解析真正的版本 JSON。
     * - Forge ≤1.12：`versionInfo` 对象
     * - Forge 1.13+：`json` 指向 jar 内相对路径
     * - 少数：`versionJson` 内嵌对象
     */
    @Throws(IOException::class)
    private fun resolveVersionJson(installerJar: Path, profile: JsonObject): JsonObject {
        if (profile.has("versionInfo") && profile.get("versionInfo").isJsonObject) {
            return profile.getAsJsonObject("versionInfo")
        }
        if (profile.has("versionJson") && profile.get("versionJson").isJsonObject) {
            return profile.getAsJsonObject("versionJson")
        }
        if (profile.has("json") && !profile.get("json").isJsonNull) {
            val rel = profile.get("json").asString
            ZipFile(installerJar.toFile()).use { zip ->
                val entry = zip.getEntry(if (rel.startsWith("/")) rel.substring(1) else rel)
                if (entry == null) {
                    throw IOException("installer.jar 中找不到版本 JSON: $rel")
                }
                zip.getInputStream(entry).use { inp ->
                    val json = String(inp.readBytes(), StandardCharsets.UTF_8)
                    return parseJsonObject(json, rel)
                }
            }
        }
        throw IOException("install_profile.json 中找不到 versionInfo / json / versionJson")
    }

    /** 下载远端库：多源回退 + 优先 URL+.sha1；已存在文件也做 SHA 校验。 */
    @Throws(IOException::class)
    private fun downloadRemoteLibraries(remoteLibs: List<DownloadTask>) {
        for (t in remoteLibs) {
            if (Thread.currentThread().isInterrupted()) {
                throw InstallInterruptedException(
                    (if (neoForge) "NeoForge" else "Forge") + " 依赖库下载已中断"
                )
            }
            val target = paths.minecraftWorkDir.resolve(t.relativePath)
            var sha1 = t.sha1 ?: ""
            if (Files.isRegularFile(target) && Files.size(target) > 32 && looksLikeZip(target)) {
                if (sha1.isBlank()) {
                    sha1 = tryDownloadSha1(t.url + ".sha1") ?: ""
                }
                if (sha1.isNotBlank()) {
                    try {
                        if (sha1.equals(sha1Hex(target), ignoreCase = true)) {
                            continue
                        }
                    } catch (_: IOException) {
                        // 无法读哈希则重新下载
                    }
                    Files.deleteIfExists(target)
                } else {
                    // 无哈希：不信任已有文件，落入下方下载并强制拿到 SHA
                    Files.deleteIfExists(target)
                }
            }
            Files.createDirectories(target.parent)
            val urls = ArrayList<String>()
            urls.add(t.url)
            // 多源回退：Forge Maven ↔ BMCLAPI；其余库再试 Mojang libraries
            if (t.url.contains("maven.minecraftforge.net/")) {
                val mirrored = t.url.replace("https://maven.minecraftforge.net/", BMCLAPI_MAVEN)
                if (!urls.contains(mirrored)) urls.add(0, mirrored)
            } else if (t.url.startsWith(BMCLAPI_MAVEN)) {
                if (t.relativePath.contains("/net/minecraftforge/forge/")) {
                    val official = t.url.replace(BMCLAPI_MAVEN, "https://maven.minecraftforge.net/")
                    if (!urls.contains(official)) urls.add(official)
                }
                val mojang = t.url.replace(BMCLAPI_MAVEN, MOJANG_MAVEN)
                if (!urls.contains(mojang)) urls.add(mojang)
            }
            var last: Exception? = null
            var ok = false
            for (url in urls) {
                try {
                    var useSha = sha1
                    if (useSha.isBlank()) {
                        useSha = tryDownloadSha1("$url.sha1") ?: ""
                    }
                    if (useSha.isBlank()) {
                        throw IOException("依赖库无 SHA-1（含旁路 .sha1），拒绝下载: ${t.relativePath}")
                    }
                    downloads.downloadToVerified(url, target, useSha, null)
                    sha1 = useSha
                    ok = true
                    break
                } catch (e: Exception) {
                    if (InstallInterruptedException.isInterrupted(e)) {
                        throw if (e is RuntimeException) e
                        else InstallInterruptedException(
                            (if (neoForge) "NeoForge" else "Forge") + " 依赖库下载已中断", e
                        )
                    }
                    last = e
                }
            }
            if (!ok) {
                throw IOException(
                    "依赖库下载失败: ${t.relativePath}" +
                            (last?.let { " — ${Exceptions.rootMessage(it)}" } ?: ""), last
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun sha1Hex(file: Path): String {
        try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            Files.newInputStream(file).use { inp ->
                val buf = ByteArray(8192)
                var n: Int
                while (inp.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { String.format("%02x", it.toInt() and 0xff) }
        } catch (e: java.security.NoSuchAlgorithmException) {
            throw IOException("SHA-1 unavailable", e)
        }
    }

    private fun looksLikeZip(file: Path): Boolean {
        return try {
            Files.newInputStream(file).use { inp ->
                val magic = ByteArray(2)
                inp.read(magic)
                magic.size >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte()
            }
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        private const val BMCLAPI_BASE = "https://bmclapi2.bangbang93.com/forge/minecraft/"
        private const val NEOFORGE_LIST_URL = "https://bmclapi2.bangbang93.com/neoforge/list/"
        private const val BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/"
        private const val MOJANG_MAVEN = "https://libraries.minecraft.net/"
        private const val FORGE_MAVEN =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/"

        /** 解析 JSON 数组，非 JSON 响应给出有意义的错误信息 */
        @Throws(IOException::class)
        private fun parseJsonArray(json: String?, context: String): JsonArray {
            val trimmed = json?.trim() ?: ""
            if (trimmed.isEmpty()) {
                throw IOException("服务器返回空响应: $context")
            }
            val first = trimmed[0]
            if (first != '[' && first != '{') {
                val preview = if (trimmed.length > 200) trimmed.substring(0, 200) + "..." else trimmed
                throw IOException("服务器返回非 JSON 内容（可能为错误页面）: $context\n响应内容: $preview")
            }
            return try {
                JsonParser.parseString(trimmed).asJsonArray
            } catch (e: Exception) {
                val preview = if (trimmed.length > 200) trimmed.substring(0, 200) + "..." else trimmed
                throw IOException("JSON 解析失败: $context\n错误: ${e.message}\n响应内容: $preview")
            }
        }

        /** 解析 JSON 对象，非 JSON 响应给出有意义的错误信息 */
        @Throws(IOException::class)
        private fun parseJsonObject(json: String?, context: String): JsonObject {
            val trimmed = json?.trim() ?: ""
            if (trimmed.isEmpty()) {
                throw IOException("服务器返回空响应: $context")
            }
            val first = trimmed[0]
            if (first != '{' && first != '[') {
                val preview = if (trimmed.length > 200) trimmed.substring(0, 200) + "..." else trimmed
                throw IOException("服务器返回非 JSON 内容（可能为错误页面）: $context\n响应内容: $preview")
            }
            return try {
                JsonParser.parseString(trimmed).asJsonObject
            } catch (e: Exception) {
                val preview = if (trimmed.length > 200) trimmed.substring(0, 200) + "..." else trimmed
                throw IOException("JSON 解析失败: $context\n错误: ${e.message}\n响应内容: $preview")
            }
        }
    }
}
