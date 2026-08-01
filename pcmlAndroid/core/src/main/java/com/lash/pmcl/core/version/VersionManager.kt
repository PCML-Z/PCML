package com.lash.pmcl.core.version

import com.google.gson.JsonParser
import com.lash.pmcl.core.install.VersionStaging
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.FileUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 版本管理：拉取官方版本清单、本地版本扫描。
 *
 * Android 版本：
 * - 移除 CurlFallback（Android OkHttp 工作正常，无需 curl）
 * - 移除 detectDefaultMinecraftVersionsDir（Android 无 ~/.minecraft）
 * - 移除 Preferences 依赖，外部 Minecraft 根目录通过 extraMinecraftRoots 参数传入
 * - 路径通过 PmclPaths 抽象，由 Android Context 提供
 */
class VersionManager(
    private val paths: PmclPaths,
    /** 用户自定义的外部 Minecraft 根目录（每项含 versions 子目录的父目录） */
    private val extraMinecraftRoots: List<String> = emptyList()
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 远程获取所有可用版本。
     * Android 直接使用 OkHttp，不依赖 curl fallback。
     */
    fun fetchRemoteVersions(): CompletableFuture<List<McVersion>> {
        return CompletableFuture.supplyAsync {
            try {
                val req = Request.Builder().url(VERSION_MANIFEST_URL).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Unexpected code ${resp.code}")
                    }
                    val body = resp.body ?: throw IOException("响应体为空")
                    parseManifest(body.string())
                }
            } catch (e: Throwable) {
                throw RuntimeException("拉取版本清单失败", e)
            }
        }
    }

    private fun parseManifest(json: String): List<McVersion> {
        val root = JsonParser.parseString(json).asJsonObject
        val versions = if (root.has("versions")) root.getAsJsonArray("versions") else return emptyList()
        val result = ArrayList<McVersion>(versions.size())
        for (e in versions) {
            val v = e.asJsonObject
            result.add(
                McVersion(
                    id = if (v.has("id") && !v.get("id").isJsonNull) v.get("id").asString else "",
                    type = if (v.has("type") && !v.get("type").isJsonNull) v.get("type").asString else "",
                    releaseTime = if (v.has("releaseTime") && !v.get("releaseTime").isJsonNull) v.get("releaseTime").asString else "",
                    url = if (v.has("url") && !v.get("url").isJsonNull) v.get("url").asString else "",
                    sha1 = if (v.has("sha1") && !v.get("sha1").isJsonNull) v.get("sha1").asString else ""
                )
            )
        }
        return result
    }

    /**
     * 扫描本地已安装的版本（仅 id 列表）。
     */
    fun listLocalVersions(): List<String> {
        val dir = paths.versions
        if (!Files.isDirectory(dir)) return emptyList()
        val names = ArrayList<String>()
        try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .forEach { p ->
                        val name = p.fileName.toString()
                        if (!VersionStaging.isTransientDirName(name)) {
                            names.add(name)
                        }
                    }
            }
        } catch (e: IOException) {
            throw RuntimeException("扫描本地版本失败", e)
        }
        return names
    }

    /**
     * 扫描本地已安装版本的详细信息（解析 version json 提取 inheritsFrom/mainClass/assets）。
     * 默认扫描配置的 versions 目录。
     */
    fun scanLocalVersions(): List<LocalVersionInfo> {
        return scanVersionsDir(paths.versions, null)
    }

    /**
     * 扫描指定 versions 目录，支持进度回调。
     */
    fun scanVersionsDir(versionsDir: Path, onProgress: ((ScanProgress) -> Unit)?): List<LocalVersionInfo> {
        if (!Files.isDirectory(versionsDir)) return emptyList()
        // 先列出所有子目录，算总数
        val subDirs = ArrayList<Path>()
        try {
            Files.list(versionsDir).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { subDirs.add(it) }
            }
        } catch (e: IOException) {
            throw RuntimeException("扫描本地版本失败: $versionsDir", e)
        }
        val dirName = versionsDir.fileName?.toString() ?: versionsDir.toString()
        val total = subDirs.size
        val result = ArrayList<LocalVersionInfo>(total)
        var scanned = 0
        for (p in subDirs) {
            val id = p.fileName.toString()
            // 跳过安装暂存 / 回滚备份目录
            if (VersionStaging.isTransientDirName(id)) continue
            val json = p.resolve("$id.json")
            val jar = p.resolve("$id.jar")
            val hasJson = Files.exists(json)
            val hasJar = Files.exists(jar)
            var mtime = 0L
            try {
                mtime = Files.getLastModifiedTime(json).toMillis()
            } catch (_: IOException) {
            }
            var inheritsFrom: String? = null
            var mainClass: String? = null
            var assets: String? = null
            if (hasJson) {
                try {
                    val root = JsonParser.parseString(
                        FileUtils.readString(json, StandardCharsets.UTF_8)
                    ).asJsonObject
                    if (root.has("inheritsFrom") && !root.get("inheritsFrom").isJsonNull) {
                        inheritsFrom = root.get("inheritsFrom").asString
                    }
                    if (root.has("mainClass") && !root.get("mainClass").isJsonNull) {
                        mainClass = root.get("mainClass").asString
                    }
                    if (root.has("assets") && !root.get("assets").isJsonNull) {
                        assets = root.get("assets").asString
                    }
                } catch (_: Throwable) {
                }
            }
            result.add(LocalVersionInfo(id, mtime, hasJar, hasJson, inheritsFrom, mainClass, assets))
            scanned++
            onProgress?.invoke(ScanProgress(dirName, scanned, total, id))
        }
        // 按修改时间倒序（最新在前）
        result.sortByDescending { it.lastModified }
        return result
    }

    /**
     * 扫描所有应扫描的 versions 目录：
     * .pmcl/versions + 用户自定义根目录下的 versions。
     * 合并去重（同名版本以 .pmcl 优先）。
     */
    fun scanAllLocalVersions(onProgress: ((ScanProgress) -> Unit)? = null): List<LocalVersionInfo> {
        val dirs = getAllScanDirs()
        // 第一遍：逐目录扫描，收集结果
        val parts = ArrayList<List<LocalVersionInfo>>()
        val dirNames = ArrayList<String>()
        for (d in dirs) {
            if (!Files.isDirectory(d)) {
                parts.add(emptyList())
                continue
            }
            dirNames.add(d.fileName?.toString() ?: d.toString())
            parts.add(scanVersionsDir(d, null))
        }
        // 计算总数
        var grandTotal = 0
        for (part in parts) grandTotal += part.size

        // 第二遍：合并去重 + 回调进度
        val merged = ArrayList<LocalVersionInfo>()
        val existing = HashSet<String>()
        var scanned = 0
        for (i in parts.indices) {
            val dirName = if (i < dirNames.size) dirNames[i] else ""
            for (v in parts[i]) {
                if (existing.add(v.id)) {
                    merged.add(v)
                }
                scanned++
                onProgress?.invoke(ScanProgress(dirName, scanned, grandTotal, v.id))
            }
        }
        return merged
    }

    /**
     * 获取所有应扫描的 versions 目录。
     * 合并 .pmcl/versions + 用户自定义根目录。
     */
    fun getAllScanDirs(): List<Path> {
        val dirs = ArrayList<Path>()
        dirs.add(paths.versions)
        for (root in extraMinecraftRoots) {
            try {
                val versionsDir = Path.of(root).resolve("versions")
                if (Files.isDirectory(versionsDir) && !dirs.contains(versionsDir)) {
                    dirs.add(versionsDir)
                }
            } catch (t: Throwable) {
                System.err.println("[VersionManager] 无效的根目录路径: $root - ${t.message}")
            }
        }
        return dirs
    }

    /**
     * 本地版本详细信息：含修改时间、是否含 jar、是否含 json、inheritsFrom。
     */
    data class LocalVersionInfo(
        val id: String,
        val lastModified: Long,
        val hasJar: Boolean,
        val hasJson: Boolean,
        val inheritsFrom: String?,
        val mainClass: String?,
        val assets: String?
    ) {
        /** 是否可启动：必须含 json（jar 可继承自父版本） */
        val isLaunchable: Boolean get() = hasJson
    }

    /**
     * 扫描进度信息。
     */
    data class ScanProgress(
        val currentDir: String,
        val scanned: Int,
        val total: Int,
        val currentVersion: String
    ) {
        /** 进度比例 0~1 */
        val fraction: Float get() = if (total > 0) scanned.toFloat() / total else 0f
        /** 是否完成 */
        val isDone: Boolean get() = scanned >= total
    }

    companion object {
        private const val VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }
}
