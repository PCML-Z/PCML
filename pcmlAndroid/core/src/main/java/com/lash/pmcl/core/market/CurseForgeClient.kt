package com.lash.pmcl.core.market

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * CurseForge API 客户端 — Android 版。
 *
 * 端点：https://api.curseforge.com/v1
 *
 * CurseForge 要求在请求头中携带 X-API-Key。
 *
 * 与桌面版的差异：
 * - 完全移除 CurlFallback
 * - 保留 3 次重试 + 指数退避（1s/2s/4s）
 * - 保留 429 Retry-After 头解析（上限 60s）
 * - 移除 fingerprintLookup（CurseForge 整合包在线导出专用，Android MVP 不需要）
 */
class CurseForgeClient(
    /** CurseForge API Key（X-API-Key 头），为空时所有请求会失败 */
    private val apiKey: String,
    /** 复用 DownloadManager 的 OkHttpClient */
    private val downloads: DownloadManager
) : ModMarketClient {

    @Volatile
    private var http: OkHttpClient = downloads.httpClient()

    override fun source(): String = "curseforge"

    override fun updateHttpClient(http: OkHttpClient) {
        this.http = http
    }

    override fun search(
        query: String, gameVersion: String, loader: String, limit: Int
    ): CompletableFuture<List<ModProject>> = doSearch(query, gameVersion, loader, limit)

    override fun popular(
        gameVersion: String, loader: String, limit: Int
    ): CompletableFuture<List<ModProject>> = CompletableFuture.supplyAsync {
        val ub = "$BASE/mods/search".toHttpUrlOrNull()?.newBuilder()
            ?: throw RuntimeException("无效的 URL: $BASE/mods/search")
        ub.addQueryParameter("gameId", MINECRAFT_GAME_ID.toString())
          .addQueryParameter("pageSize", limit.toString())
          .addQueryParameter("sort", "Popularity")
        if (gameVersion.isNotEmpty()) ub.addQueryParameter("gameVersion", gameVersion)
        if (loader.isNotEmpty()) ub.addQueryParameter("modLoaderType", capitalize(loader))
        supplyAsyncSearch(ub)
    }

    private fun doSearch(
        query: String, gameVersion: String, loader: String, limit: Int
    ): CompletableFuture<List<ModProject>> = CompletableFuture.supplyAsync {
        val ub = "$BASE/mods/search".toHttpUrlOrNull()?.newBuilder()
            ?: throw RuntimeException("无效的 URL: $BASE/mods/search")
        ub.addQueryParameter("gameId", MINECRAFT_GAME_ID.toString())
          .addQueryParameter("searchFilter", query ?: "")
          .addQueryParameter("pageSize", limit.toString())
        if (gameVersion.isNotEmpty()) ub.addQueryParameter("gameVersion", gameVersion)
        if (loader.isNotEmpty()) ub.addQueryParameter("modLoaderType", capitalize(loader))
        supplyAsyncSearch(ub)
    }

    /** 执行搜索请求并解析响应为 ModProject 列表 */
    private fun supplyAsyncSearch(ub: HttpUrl.Builder): List<ModProject> {
        val req = Request.Builder().url(ub.build())
            .header("X-API-Key", apiKey)
            .header("User-Agent", "PMCL/1.0")
            .get().build()
        var last: Throwable? = null
        for (attempt in 0..RETRY) {
            try {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) {
                        if (resp.code == 429) {
                            val retryAfter = resp.header("Retry-After")
                            var waitMs = 5000L
                            if (!retryAfter.isNullOrEmpty()) {
                                try { waitMs = retryAfter.toLong() * 1000L }
                                catch (_: NumberFormatException) {}
                            }
                            try { Thread.sleep(minOf(waitMs, 60_000L)) }
                            catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw IOException("中断")
                            }
                            continue
                        }
                        throw IOException("HTTP ${resp.code}: $body")
                    }
                    val root = JsonParser.parseString(body).asJsonObject
                    val data = if (root.has("data")) root.getAsJsonArray("data") else JsonArray()
                    val result = ArrayList<ModProject>()
                    for (e in data) {
                        val o = e.asJsonObject
                        val downloads = if (o.has("downloadCount"))
                            o.get("downloadCount").asLong else 0L
                        var iconUrl = ""
                        if (o.has("logo") && !o.get("logo").isJsonNull) {
                            val logo = o.getAsJsonObject("logo")
                            iconUrl = if (logo.has("thumbnailUrl"))
                                logo.get("thumbnailUrl").asString else ""
                        }
                        var author = ""
                        if (o.has("authors") && o.getAsJsonArray("authors").size() > 0) {
                            val firstAuthor = o.getAsJsonArray("authors")[0].asJsonObject
                            if (firstAuthor.has("name")) {
                                author = firstAuthor.get("name").asString
                            }
                        }
                        result.add(
                            ModProject(
                                source = "curseforge",
                                id = safeStr(o, "id"),
                                slug = if (o.has("slug")) o.get("slug").asString else "",
                                name = safeStr(o, "name"),
                                summary = if (o.has("summary")) o.get("summary").asString else "",
                                author = author,
                                downloadCount = downloads,
                                iconUrl = iconUrl,
                                websiteUrl = if (o.has("websiteUrl"))
                                    o.get("websiteUrl").asString else ""
                            )
                        )
                    }
                    return result
                }
            } catch (e: Throwable) {
                last = e
                if (attempt < RETRY) {
                    try {
                        Thread.sleep(RETRY_BASE_MS * (1L shl attempt))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return emptyList()
                    }
                }
            }
        }
        val msg = last?.message ?: "未知错误"
        throw RuntimeException("CurseForge 搜索失败：${friendlyError(msg)}", last)
    }

    override fun listFiles(projectId: String): CompletableFuture<List<ModFile>> =
        CompletableFuture.supplyAsync {
            val url = "$BASE/mods/$projectId/files"
            val req = Request.Builder().url(url)
                .header("X-API-Key", apiKey)
                .header("User-Agent", "PMCL/1.0").get().build()
            var last: Exception? = null
            for (attempt in 0..RETRY) {
                try {
                    http.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: "{}"
                        if (!resp.isSuccessful) {
                            if (resp.code == 429) {
                                val retryAfter = resp.header("Retry-After")
                                var waitMs = 5000L
                                if (!retryAfter.isNullOrEmpty()) {
                                    try { waitMs = retryAfter.toLong() * 1000L }
                                    catch (_: NumberFormatException) {}
                                }
                                try { Thread.sleep(minOf(waitMs, 60_000L)) }
                                catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    throw IOException("中断")
                                }
                                continue
                            }
                            throw IOException("HTTP ${resp.code}: $body")
                        }
                        val root = JsonParser.parseString(body).asJsonObject
                        val data = if (root.has("data"))
                            root.getAsJsonArray("data") else JsonArray()
                        val result = ArrayList<ModFile>()
                        for (e in data) {
                            val o = e.asJsonObject
                            val gameVersions = jsonArrToStrings(o, "gameVersions")
                            val loaders = ArrayList<String>()
                            if (o.has("gameVersions")) {
                                for (gv in o.getAsJsonArray("gameVersions")) {
                                    val s = gv.asString
                                    if (s.equals("Fabric", ignoreCase = true) ||
                                        s.equals("Forge", ignoreCase = true) ||
                                        s.equals("Quilt", ignoreCase = true)) {
                                        loaders.add(s.lowercase())
                                    }
                                }
                            }
                            val releaseType = if (o.has("releaseType"))
                                cfReleaseType(o.get("releaseType").asInt) else "release"
                            // CurseForge hashes: algo 1=SHA1, 2=MD5
                            var sha1 = ""
                            if (o.has("hashes") && o.get("hashes").isJsonArray) {
                                for (he in o.getAsJsonArray("hashes")) {
                                    val ho = he.asJsonObject
                                    val algo = if (ho.has("algo")) ho.get("algo").asInt else -1
                                    if (algo == 1) {
                                        sha1 = safeStr(ho, "value")
                                        break
                                    }
                                }
                            }
                            result.add(
                                ModFile(
                                    source = "curseforge",
                                    projectId = projectId,
                                    fileId = safeStr(o, "id"),
                                    fileName = safeStr(o, "fileName"),
                                    fileSize = if (o.has("fileLength"))
                                        o.get("fileLength").asLong else 0L,
                                    downloadUrl = safeStr(o, "downloadUrl"),
                                    gameVersions = gameVersions,
                                    loaders = loaders,
                                    releaseType = releaseType
                                ).hashes(sha1, "")
                            )
                        }
                        return@supplyAsync result
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
            val msg = last?.message ?: "未知错误"
            throw RuntimeException("CurseForge 拉取文件失败：${friendlyError(msg)}", last)
        }

    /**
     * 生成友好的中文错误信息，提示用户可能的解决方案。
     */
    private fun friendlyError(rawMsg: String?): String {
        val msg = rawMsg ?: ""
        return when {
            msg.contains("handshake") || msg.contains("SSL") || msg.contains("TLS") ||
            msg.contains("reset") || msg.contains("broken pipe") ->
                "无法连接 api.curseforge.com（SSL 握手失败），请检查网络或在设置中配置代理。原始错误：$msg"
            msg.contains("timeout") || msg.contains("timed out") ->
                "连接 api.curseforge.com 超时，请检查网络或配置代理。原始错误：$msg"
            msg.contains("UnknownHost") || msg.contains("Unable to resolve") ->
                "无法解析 api.curseforge.com 域名，请检查网络或 DNS 设置。原始错误：$msg"
            else -> msg
        }
    }

    private fun cfReleaseType(code: Int): String = when (code) {
        2 -> "beta"
        3 -> "alpha"
        else -> "release"
    }

    private fun jsonArrToStrings(o: JsonObject, key: String): List<String> {
        if (!o.has(key)) return emptyList()
        val list = ArrayList<String>()
        for (e in o.getAsJsonArray(key)) {
            if (!e.isJsonNull) list.add(e.asString)
        }
        return list
    }

    private fun capitalize(s: String): String {
        if (s.isEmpty()) return s
        return s[0].uppercaseChar() + s.substring(1).lowercase()
    }

    private fun safeStr(o: JsonObject, key: String): String =
        if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else ""

    companion object {
        private const val BASE = "https://api.curseforge.com/v1"
        /** Minecraft 在 CurseForge 的 gameId 固定为 432 */
        private const val MINECRAFT_GAME_ID = 432
        private const val RETRY = 3
        private const val RETRY_BASE_MS = 1000L
    }
}
