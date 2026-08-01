package com.lash.pmcl.core.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 轻量级 pastebin 上传客户端，用于分享游戏日志 — Android 版。
 *
 * 默认使用 paste.gg（无需 API Key，支持匿名上传）。
 * 上传后返回可直接访问的 URL，便于求助分享。
 *
 * 纯 OkHttp + Gson，无桌面平台依赖。
 */
class PastebinClient {

    @Volatile
    private var http: OkHttpClient

    /** 默认构造：自建客户端（无代理） */
    constructor() {
        this.http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 复用外部 OkHttpClient（推荐）：自动应用代理配置与共享连接池 */
    constructor(http: OkHttpClient) {
        this.http = http
    }

    fun updateHttpClient(http: OkHttpClient) {
        this.http = http
    }

    /**
     * 异步上传文本到 paste.gg。
     *
     * @param content 日志文本
     * @param name    paste 名称（可为空）
     * @return CompletableFuture，成功时返回 paste URL，失败时异常完成
     */
    fun uploadAsync(content: String, name: String?): CompletableFuture<String> {
        return CompletableFuture.supplyAsync {
            try {
                upload(content, name)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
    }

    /**
     * 同步上传文本到 paste.gg。
     *
     * @param content 日志文本
     * @param name    paste 名称（可为空）
     * @return paste URL（如 https://paste.gg/u/anonymous/...）
     */
    @Throws(IOException::class)
    fun upload(content: String, name: String?): String {
        val safeName = if (name.isNullOrBlank()) "PMCL Log" else name
        var safeContent = content
        if (safeContent.length > MAX_UPLOAD_CHARS) {
            // 保留尾部（崩溃栈通常在文件末尾）
            safeContent = safeContent.substring(safeContent.length - MAX_UPLOAD_CHARS)
            safeContent = "...[truncated by PMCL]...\n$safeContent"
        }
        // 用 Gson 构建 JSON payload，避免手动转义
        val contentObj = JsonObject().apply {
            addProperty("format", "text")
            addProperty("value", safeContent)
        }
        val fileObj = JsonObject().apply {
            addProperty("name", "log.txt")
            add("content", contentObj)
        }
        val payloadObj = JsonObject().apply {
            addProperty("name", safeName)
            addProperty("visibility", "unlisted")
            val filesArray = com.google.gson.JsonArray().apply { add(fileObj) }
            add("files", filesArray)
        }
        val payload = Gson().toJson(payloadObj)

        val body = payload.toRequestBody(JSON)
        val req = Request.Builder()
            .url(PASTE_GG_API)
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", "PMCL-Launcher/1.0")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("paste.gg HTTP ${resp.code}")
            }
            val respBody = resp.body?.string() ?: ""
            val url = extractUrl(respBody)
                ?: throw IOException("paste.gg 响应解析失败: ${truncate(respBody, 200)}")
            return url
        }
    }

    /** 从 paste.gg JSON 响应中提取 result.url 字段 */
    private fun extractUrl(json: String?): String? {
        if (json.isNullOrEmpty()) return null
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            if (!root.has("result") || root.get("result").isJsonNull) return null
            val result = root.getAsJsonObject("result")
            if (!result.has("url") || result.get("url").isJsonNull) return null
            val url = result.get("url").asString
            // paste.gg 返回的是相对路径 /u/anonymous/xxx，补全为完整 URL
            if (url.startsWith("/")) {
                return "https://paste.gg$url"
            }
            // 仅接受 paste.gg 主机，防止响应被篡改时打开钓鱼链接
            try {
                val uri = URI.create(url)
                val host = uri.host
                if (host == null || !host.equals("paste.gg", ignoreCase = true)) {
                    return null
                }
                val scheme = uri.scheme
                if (scheme == null || !scheme.equals("https", ignoreCase = true)) {
                    return null
                }
                url
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun truncate(s: String?, max: Int): String {
        if (s == null) return ""
        return if (s.length <= max) s else s.substring(0, max) + "..."
    }

    companion object {
        private const val PASTE_GG_API = "https://paste.gg/api/accept"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        /** ~1.5MB 文本，防 OOM */
        private const val MAX_UPLOAD_CHARS = 1_500_000
    }
}
