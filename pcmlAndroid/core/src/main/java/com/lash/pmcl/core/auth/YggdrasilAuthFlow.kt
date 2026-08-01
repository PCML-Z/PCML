package com.lash.pmcl.core.auth

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.util.SsrfChecker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Yggdrasil API 认证流程（用于皮肤站 / authlib-injector 外置登录）— Android 版。
 *
 * 兼容 authlib-injector 规范的皮肤站（如 LittleSkin、Blessing Skin Server）。
 *
 * 认证端点（相对皮肤站 API 根地址）：
 * - POST /authserver/login — 登录，返回 accessToken + selectedProfile
 * - POST /authserver/refresh — 刷新 accessToken
 * - POST /authserver/validate — 验证 accessToken 是否有效
 * - POST /authserver/invalidate — 登出，使 accessToken 失效
 *
 * 纯 OkHttp，无桌面平台依赖。SSRF 校验允许局域网皮肤站，拒绝链路本地/组播/任意本地。
 */
class YggdrasilAuthFlow {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 登录皮肤站。
     *
     * @param apiUrl   皮肤站 API 根地址（如 https://skin.example.com/api/yggdrasil）
     * @param username 用户名或邮箱
     * @param password 密码
     * @return 登录成功后的 Account
     * @throws IOException 网络错误或认证失败
     */
    @Throws(IOException::class)
    fun login(apiUrl: String, username: String, password: String): Account {
        val normalizedUrl = normalizeApiUrl(preferHttps(apiUrl))
        assertHttpUrl(normalizedUrl)

        // 构建登录请求体（勿将 password 写入日志）
        val agent = JsonObject().apply {
            addProperty("name", "Minecraft")
            addProperty("version", 1)
        }
        val payload = JsonObject().apply {
            add("agent", agent)
            addProperty("username", username)
            addProperty("password", password)
        }
        var clientToken = UUID.randomUUID().toString().replace("-", "")
        payload.addProperty("clientToken", clientToken)

        val req = Request.Builder()
            .url(url(normalizedUrl, "/authserver/login"))
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val errorMsg = parseErrorMessage(body)
                throw IOException(if (errorMsg.isEmpty()) "登录失败 (HTTP ${resp.code})" else errorMsg)
            }

            val o = JsonParser.parseString(body).asJsonObject
            val accessToken = safeStr(o, "accessToken")
            val returnedClient = safeStr(o, "clientToken")
            if (returnedClient.isNotEmpty()) clientToken = returnedClient
            val selectedProfile = if (o.has("selectedProfile") && o.get("selectedProfile").isJsonObject)
                o.getAsJsonObject("selectedProfile") else null

            if (selectedProfile == null) {
                throw IOException("皮肤站返回的登录结果中没有 selectedProfile，可能该账号尚未创建角色")
            }

            val playerName = safeStr(selectedProfile, "name")
            val playerUuid = safeStr(selectedProfile, "id")

            return Account(
                username = playerName,
                uuid = playerUuid,
                accessToken = accessToken,
                type = Account.AccountType.YGGDRASIL,
                skinUrl = "",
                skinModel = "classic",
                xuid = "",
                authServerUrl = normalizedUrl,
                msRefreshToken = "",
                expiresAt = 0L,
                clientToken = clientToken
            )
        }
    }

    /**
     * 验证 accessToken 是否仍然有效。
     *
     * @param apiUrl      皮肤站 API 根地址
     * @param accessToken 待验证的 token
     * @return true 表示 token 有效
     */
    fun validate(apiUrl: String, accessToken: String): Boolean =
        validate(apiUrl, accessToken, "")

    fun validate(apiUrl: String, accessToken: String, clientToken: String): Boolean {
        val normalizedUrl = normalizeApiUrl(preferHttps(apiUrl))
        try {
            assertHttpUrl(normalizedUrl)
        } catch (e: IOException) {
            System.err.println("[YggdrasilAuthFlow] validate: ${e.message}")
            return false
        }
        val payload = JsonObject().apply {
            addProperty("accessToken", accessToken)
            addProperty("clientToken", clientToken)
        }

        val req = Request.Builder()
            .url(url(normalizedUrl, "/authserver/validate"))
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody(JSON))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                // 204 No Content 表示 token 有效
                resp.code == 204
            }
        } catch (e: IOException) {
            System.err.println("[YggdrasilAuthFlow] validate 网络错误: ${e.message}")
            false
        }
    }

    /**
     * 刷新 accessToken。
     *
     * @param apiUrl      皮肤站 API 根地址
     * @param accessToken 旧的 token
     * @return 新的 accessToken，失败返回 null
     */
    fun refresh(apiUrl: String, accessToken: String): String? =
        refresh(apiUrl, accessToken, "")

    fun refresh(apiUrl: String, accessToken: String, clientToken: String): String? {
        val normalizedUrl = normalizeApiUrl(preferHttps(apiUrl))
        try {
            assertHttpUrl(normalizedUrl)
        } catch (e: IOException) {
            System.err.println("[YggdrasilAuthFlow] refresh: ${e.message}")
            return null
        }
        val payload = JsonObject().apply {
            addProperty("accessToken", accessToken)
            addProperty("clientToken", clientToken)
        }

        val req = Request.Builder()
            .url(url(normalizedUrl, "/authserver/refresh"))
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody(JSON))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    System.err.println("[YggdrasilAuthFlow] refresh 失败 (HTTP ${resp.code})")
                    null
                } else {
                    val o = JsonParser.parseString(body).asJsonObject
                    val token = safeStr(o, "accessToken")
                    token.ifEmpty { null }
                }
            }
        } catch (e: IOException) {
            System.err.println("[YggdrasilAuthFlow] refresh 网络错误: ${e.message}")
            null
        }
    }

    /**
     * 使 accessToken 失效（登出）。
     *
     * @param apiUrl      皮肤站 API 根地址
     * @param accessToken 待失效的 token
     */
    fun invalidate(apiUrl: String, accessToken: String) {
        invalidate(apiUrl, accessToken, "")
    }

    fun invalidate(apiUrl: String, accessToken: String, clientToken: String) {
        val normalizedUrl = normalizeApiUrl(preferHttps(apiUrl))
        try {
            assertHttpUrl(normalizedUrl)
        } catch (e: IOException) {
            System.err.println("[YggdrasilAuthFlow] invalidate: ${e.message}")
            return
        }
        val payload = JsonObject().apply {
            addProperty("accessToken", accessToken)
            addProperty("clientToken", clientToken)
        }

        val req = Request.Builder()
            .url(url(normalizedUrl, "/authserver/invalidate"))
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody(JSON))
            .build()

        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    System.err.println("[YggdrasilAuthFlow] invalidate 失败 (HTTP ${resp.code})")
                }
            }
        } catch (e: IOException) {
            System.err.println("[YggdrasilAuthFlow] invalidate 网络错误: ${e.message}")
        }
    }

    /** 构建完整请求 URL：apiUrl + 相对路径 */
    private fun url(apiUrl: String, path: String): String {
        val base = if (apiUrl.endsWith("/")) apiUrl.substring(0, apiUrl.length - 1) else apiUrl
        return base + path
    }

    /** 仅允许 http/https；允许局域网/回环皮肤站，拒绝云 metadata 等链路本地目标 */
    @Throws(IOException::class)
    private fun assertHttpUrl(url: String?) {
        if (url.isNullOrBlank()) {
            throw IOException("皮肤站地址为空")
        }
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw IOException("皮肤站地址必须是 http:// 或 https://")
        }
        // SSRF 校验：允许局域网，拒绝链路本地/组播/任意本地
        val ssrf = SsrfChecker.validateAllowingPrivateLan(url)
        if (ssrf != null) {
            throw IOException("皮肤站地址被拒绝（SSRF 防护）: $ssrf")
        }
        // DNS 解析后再二次校验，降低 DNS rebinding / 解析绕过风险
        try {
            val parsed = URL(url)
            val host = parsed.host
            val addrs = InetAddress.getAllByName(host)
            for (addr in addrs) {
                if (addr.isLinkLocalAddress || addr.isMulticastAddress || addr.isAnyLocalAddress) {
                    throw IOException("皮肤站地址解析到受限地址: ${addr.hostAddress}")
                }
            }
            // 对非本地 http 拒绝（密码明文）
            if (lower.startsWith("http://")) {
                var isLocal = false
                for (addr in addrs) {
                    if (addr.isLoopbackAddress || addr.isSiteLocalAddress) {
                        isLocal = true
                        break
                    }
                }
                if (!isLocal) {
                    throw IOException(
                        "非本地 HTTP 皮肤站地址不安全：密码将通过明文传输。" +
                            "请使用 HTTPS 或本地地址。"
                    )
                }
            }
        } catch (e: java.net.UnknownHostException) {
            throw IOException("无法解析皮肤站地址: ${e.message}")
        }
    }

    /** 从错误响应中提取人类可读的错误消息 */
    private fun parseErrorMessage(body: String): String {
        return try {
            val o = JsonParser.parseString(body).asJsonObject
            safeStr(o, "errorMessage")
        } catch (t: Throwable) {
            ""
        }
    }

    private fun safeStr(o: JsonObject, key: String): String =
        if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else ""

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * 规范化皮肤站 API 地址。
         * 用户可能输入的是首页地址（如 https://skin.example.com），
         * 需补齐为 API 根地址（https://skin.example.com/api/yggdrasil）。
         * 若输入已包含 /api/yggdrasil 则原样返回（去除末尾多余斜杠）。
         */
        fun normalizeApiUrl(input: String): String {
            if (input.isBlank()) return ""
            var url = input.trim()
            // 去除末尾斜杠
            while (url.endsWith("/")) url = url.substring(0, url.length - 1)
            // 已包含 api/yggdrasil 路径
            if (url.endsWith("/api/yggdrasil")) return url
            // 去除可能的 /api 后缀再重新拼接
            if (url.endsWith("/api")) url = url.substring(0, url.length - 4)
            return "$url/api/yggdrasil"
        }

        /** 若用户输入 http://，尝试改写为 https://（本地回环除外）。 */
        fun preferHttps(input: String): String {
            if (input.isBlank()) return input
            val t = input.trim()
            val lower = t.lowercase(Locale.ROOT)
            if (!lower.startsWith("http://")) return t
            if (lower.startsWith("http://localhost") || lower.startsWith("http://127.")
                || lower.startsWith("http://[::1]")
            ) {
                return t
            }
            return "https://" + t.substring("http://".length)
        }
    }
}
