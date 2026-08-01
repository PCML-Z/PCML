package com.lash.pmcl.core.market

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * Modrinth API 客户端 — Android 版。
 *
 * 端点：https://api.modrinth.com/v2
 *
 * 与桌面版的差异：
 * - 完全移除 CurlFallback（Android 上 OkHttp 足够稳定，且无法依赖系统 curl）
 * - 保留 3 次重试 + 指数退避（1s/2s/4s）
 * - 保留 429 Retry-After 头解析（上限 60s，避免 UI 卡死）
 * - 移除 batchCheckBySha1（整合包导出专用，Android MVP 不需要）
 */
class ModrinthClient(
    /** 复用 DownloadManager 的 OkHttpClient（自动应用代理/镜像配置） */
    private val downloads: DownloadManager
) : ModMarketClient {

    @Volatile
    private var http: OkHttpClient = downloads.httpClient()

    override fun source(): String = "modrinth"

    override fun updateHttpClient(http: OkHttpClient) {
        this.http = http
    }

    override fun search(
        query: String, gameVersion: String, loader: String, limit: Int
    ): CompletableFuture<List<ModProject>> =
        doSearch(query, gameVersion, loader, null, limit, null)

    override fun search(
        query: String, gameVersion: String, loader: String, category: String, limit: Int
    ): CompletableFuture<List<ModProject>> =
        doSearch(query, gameVersion, loader, category, limit, null)

    override fun popular(
        gameVersion: String, loader: String, limit: Int
    ): CompletableFuture<List<ModProject>> =
        doSearch("", gameVersion, loader, null, limit, "downloads")

    /**
     * 通用搜索：可指定排序方式与分类过滤。
     *
     * @param query    关键字，空字符串表示无关键字
     * @param category 可选分类（如 "performance"/"technology"），null 表示不按分类过滤
     * @param sort     排序方式（relevance/downloads/updated/newest/follows），null 表示默认
     */
    private fun doSearch(
        query: String, gameVersion: String, loader: String,
        category: String?, limit: Int, sort: String?
    ): CompletableFuture<List<ModProject>> = CompletableFuture.supplyAsync {
        val parsed = "$BASE/search".toHttpUrlOrNull()
            ?: throw RuntimeException("无效的 URL: $BASE/search")
        val ub = parsed.newBuilder()
            .addQueryParameter("query", query ?: "")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("facets", buildFacets(gameVersion, loader, category))
        if (!sort.isNullOrEmpty()) {
            ub.addQueryParameter("sort", sort)
        }
        val req = Request.Builder().url(ub.build())
            .header("User-Agent", "PMCL/1.0").get().build()

        var last: Exception? = null
        for (attempt in 0..RETRY) {
            val retryAfterMs = longArrayOf(-1L)
            try {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        if (resp.code == 429) {
                            retryAfterMs[0] = parseRetryAfterMs(resp.header("Retry-After"))
                        }
                        throw IOException("HTTP ${resp.code}: $body")
                    }
                    return@supplyAsync parseSearchResult(body)
                }
            } catch (e: Exception) {
                last = e
                if (attempt < RETRY) {
                    val sleepMs = if (retryAfterMs[0] > 0)
                        retryAfterMs[0] else RETRY_BASE_MS * (1L shl attempt)
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        val msg = last?.message ?: "未知错误"
        throw RuntimeException("Modrinth 搜索失败：${friendlyError(msg)}", last)
    }

    /** 解析 Modrinth 搜索响应 JSON 为 ModProject 列表 */
    private fun parseSearchResult(body: String): List<ModProject> {
        val root = JsonParser.parseString(body).asJsonObject
        val hits = if (root.has("hits")) root.getAsJsonArray("hits") else JsonArray()
        val result = ArrayList<ModProject>()
        for (e in hits) {
            val o = e.asJsonObject
            val slug = if (o.has("slug")) o.get("slug").asString else ""
            result.add(
                ModProject(
                    source = "modrinth",
                    id = safeStr(o, "project_id"),
                    slug = slug,
                    name = safeStr(o, "title"),
                    summary = if (o.has("description")) o.get("description").asString else "",
                    author = if (o.has("author")) o.get("author").asString else "",
                    downloadCount = if (o.has("downloads")) o.get("downloads").asLong else 0L,
                    iconUrl = if (o.has("icon_url")) o.get("icon_url").asString else "",
                    websiteUrl = "https://modrinth.com/project/" +
                        (if (slug.isNotEmpty()) slug else safeStr(o, "project_id"))
                )
            )
        }
        return result
    }

    override fun listFiles(projectId: String): CompletableFuture<List<ModFile>> =
        CompletableFuture.supplyAsync {
            val url = "$BASE/project/$projectId/version"
            val req = Request.Builder().url(url)
                .header("User-Agent", "PMCL/1.0").get().build()

            var last: Exception? = null
            for (attempt in 0..RETRY) {
                val retryAfterMs = longArrayOf(-1L)
                try {
                    http.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: "[]"
                        if (!resp.isSuccessful) {
                            if (resp.code == 429) {
                                retryAfterMs[0] = parseRetryAfterMs(resp.header("Retry-After"))
                            }
                            throw IOException("HTTP ${resp.code}: $body")
                        }
                        val versions = JsonParser.parseString(body).asJsonArray
                        val result = ArrayList<ModFile>()
                        for (e in versions) {
                            val v = e.asJsonObject
                            val versionId = safeStr(v, "id")
                            val versionType = if (v.has("version_type"))
                                v.get("version_type").asString else "release"
                            val gameVersions = jsonArrToStrings(v, "game_versions")
                            val loaders = jsonArrToStrings(v, "loaders")
                            val deps = jsonArrToStrings(v, "dependencies")

                            if (v.has("files")) {
                                for (f in v.getAsJsonArray("files")) {
                                    val fo = f.asJsonObject
                                    var sha1 = ""
                                    var sha512 = ""
                                    if (fo.has("hashes") && fo.get("hashes").isJsonObject) {
                                        val h = fo.getAsJsonObject("hashes")
                                        sha1 = safeStr(h, "sha1")
                                        sha512 = safeStr(h, "sha512")
                                    }
                                    result.add(
                                        ModFile(
                                            source = "modrinth",
                                            projectId = projectId,
                                            fileId = versionId,
                                            fileName = safeStr(fo, "filename"),
                                            fileSize = if (fo.has("size")) fo.get("size").asLong else 0L,
                                            downloadUrl = safeStr(fo, "url"),
                                            gameVersions = gameVersions,
                                            loaders = loaders,
                                            releaseType = versionType,
                                            dependencies = deps
                                        ).hashes(sha1, sha512)
                                    )
                                }
                            }
                        }
                        return@supplyAsync result
                    }
                } catch (e: Exception) {
                    last = e
                    if (attempt < RETRY) {
                        val sleepMs = if (retryAfterMs[0] > 0)
                            retryAfterMs[0] else RETRY_BASE_MS * (1L shl attempt)
                        try {
                            Thread.sleep(sleepMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }
            val msg = last?.message ?: "未知错误"
            throw RuntimeException("Modrinth 拉取版本失败：${friendlyError(msg)}", last)
        }

    /**
     * 获取项目元数据（含 game_versions / loaders 等）。
     *
     * @param projectId 项目 slug 或 id
     * @return 项目 JSON；不存在时返回 null
     */
    fun getProject(projectId: String): JsonObject? {
        if (projectId.isBlank()) return null
        val req = Request.Builder()
            .url("$BASE/project/$projectId")
            .header("User-Agent", "PMCL/1.0")
            .get().build()
        var last: Exception? = null
        for (attempt in 0..RETRY) {
            val retryAfterMs = longArrayOf(-1L)
            try {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (resp.code == 404) return null
                    if (!resp.isSuccessful) {
                        if (resp.code == 429) {
                            retryAfterMs[0] = parseRetryAfterMs(resp.header("Retry-After"))
                        }
                        throw IOException("HTTP ${resp.code}: $body")
                    }
                    return JsonParser.parseString(body).asJsonObject
                }
            } catch (e: Exception) {
                last = e
                if (attempt < RETRY) {
                    val sleepMs = if (retryAfterMs[0] > 0)
                        retryAfterMs[0] else RETRY_BASE_MS * (1L shl attempt)
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        val msg = last?.message ?: "未知错误"
        throw RuntimeException("Modrinth 获取项目失败：${friendlyError(msg)}", last)
    }

    /**
     * 批量通过 SHA1 反查 Modrinth 版本文件信息。
     * 调用 POST /v2/version_files 接口，返回 hash → 版本 JSON 的映射。
     *
     * @param sha1s SHA1 哈希列表
     * @return Map<sha1, 版本 JsonObject>；网络失败时抛异常
     */
    fun batchCheckBySha1(sha1s: List<String>): Map<String, JsonObject> {
        if (sha1s.isEmpty()) return emptyMap()
        val hashArray = JsonArray()
        for (s in sha1s) hashArray.add(s)
        val requestBody = JsonObject().apply {
            add("hashes", hashArray)
            addProperty("algorithm", "sha1")
        }
        val mediaType = "application/json".toMediaType()
        val req = Request.Builder()
            .url("$BASE/version_files")
            .header("User-Agent", "PMCL/1.0")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()
        var last: Exception? = null
        for (attempt in 0..RETRY) {
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string() ?: "{}"
                    val root = JsonParser.parseString(body).asJsonObject
                    val result = HashMap<String, JsonObject>()
                    for ((hash, value) in root.entrySet()) {
                        if (value.isJsonObject) {
                            result[hash] = value.asJsonObject
                        }
                    }
                    return result
                }
            } catch (e: Exception) {
                last = e
                if (attempt < RETRY) {
                    try {
                        Thread.sleep(RETRY_BASE_MS * (1L shl attempt))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        throw RuntimeException("Modrinth batchCheckBySha1 失败: ${last?.message}", last)
    }

    /**
     * 生成友好的中文错误信息，提示用户可能的解决方案。
     */
    private fun friendlyError(rawMsg: String?): String {
        val msg = rawMsg ?: ""
        return when {
            msg.contains("handshake") || msg.contains("SSL") || msg.contains("TLS") ||
            msg.contains("reset") || msg.contains("broken pipe") ->
                "无法连接 api.modrinth.com（SSL 握手失败），请检查网络或在设置中配置代理。原始错误：$msg"
            msg.contains("timeout") || msg.contains("timed out") ->
                "连接 api.modrinth.com 超时，请检查网络或配置代理。原始错误：$msg"
            msg.contains("UnknownHost") || msg.contains("Unable to resolve") ->
                "无法解析 api.modrinth.com 域名，请检查网络或 DNS 设置。原始错误：$msg"
            else -> msg
        }
    }

    /**
     * 解析 HTTP Retry-After 头为休眠毫秒数。
     * 支持数字（秒）与 HTTP-date 格式，返回值上限 60 秒。
     * 解析失败或 header 缺失时返回 -1。
     */
    private fun parseRetryAfterMs(header: String?): Long {
        if (header.isNullOrEmpty()) return -1L
        return try {
            val seconds = header.trim().toLong()
            minOf(seconds * 1000, 60_000L)
        } catch (_: NumberFormatException) {
            try {
                val date = java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH
                ).parse(header.trim()) ?: return -1L
                val diff = date.time - System.currentTimeMillis()
                maxOf(1000L, minOf(diff, 60_000L))
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Modrinth facets 数组字符串：
     * [["project_type:mod"],["versions:1.20.4"],["categories:fabric"],["categories:performance"]]
     * 每个条件是一个独立的子数组，子数组之间是 AND 关系。
     */
    private fun buildFacets(gameVersion: String, loader: String, category: String?): String {
        val facets = JsonArray()
        val typeGroup = JsonArray().apply { add("project_type:mod") }
        facets.add(typeGroup)
        if (gameVersion.isNotEmpty()) {
            facets.add(JsonArray().apply { add("versions:$gameVersion") })
        }
        if (loader.isNotEmpty()) {
            facets.add(JsonArray().apply { add("categories:$loader") })
        }
        if (!category.isNullOrEmpty()) {
            facets.add(JsonArray().apply { add("categories:$category") })
        }
        return facets.toString()
    }

    private fun jsonArrToStrings(o: JsonObject, key: String): List<String> {
        if (!o.has(key)) return emptyList()
        val list = ArrayList<String>()
        for (e in o.getAsJsonArray(key)) {
            if (!e.isJsonNull) list.add(e.asString)
        }
        return list
    }

    private fun safeStr(o: JsonObject, key: String): String =
        if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else ""

    companion object {
        private const val BASE = "https://api.modrinth.com/v2"
        private const val RETRY = 3
        private const val RETRY_BASE_MS = 1000L
    }
}
