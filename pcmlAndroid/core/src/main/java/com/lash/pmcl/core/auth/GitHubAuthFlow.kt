package com.lash.pmcl.core.auth

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * GitHub OAuth2 设备码登录流程 — Android 版。
 *
 * 流程：
 * 1. 请求设备码 → 用户在浏览器输入 userCode 授权
 * 2. 轮询 access_token 端点直到授权完成
 * 3. 调用 /user 接口获取用户名、ID、头像
 *
 * 与桌面版的差异：
 * - 完全移除 CurlFallback（Android 上 OkHttp 走系统网络栈，无 GFW TLS 指纹问题）
 *   参考 MicrosoftAuthFlow 的处理方式，网络错误直接抛出 / 异常完成
 */
class GitHubAuthFlow @JvmOverloads constructor(clientId: String? = null) {

    /** 解析后的 Client ID：为空时使用默认值 */
    val clientId: String = clientId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLIENT_ID

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "github-auth-scheduler").apply { isDaemon = true }
    }

    /** 关闭内部调度线程与 HTTP 连接池，释放资源。关闭后不可再用。 */
    fun shutdown() {
        scheduler.shutdownNow()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    /**
     * 第一步：请求设备码。用户需在浏览器打开 verificationUri 并输入 userCode。
     */
    @Throws(IOException::class)
    fun requestDeviceCode(): DeviceCode {
        val body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8.name()) +
            "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8.name())
        val req = Request.Builder()
            .url(DEVICE_CODE_URL)
            .header("Accept", "application/json")
            .post(body.toRequestBody(FORM))
            .build()
        val json = http.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful && bodyStr.isEmpty()) {
                throw IOException("请求 GitHub 设备码失败 code=${resp.code}")
            }
            bodyStr
        }
        try {
            val o = JsonParser.parseString(json).asJsonObject
            val error = safeStr(o, "error")
            if (error.isNotEmpty()) {
                throw IOException("请求 GitHub 设备码失败: $error ${safeStr(o, "error_description")}")
            }
            val deviceCode = safeStr(o, "device_code")
            val userCode = safeStr(o, "user_code")
            val verificationUri = safeStr(o, "verification_uri")
            if (deviceCode.isEmpty() || userCode.isEmpty() || verificationUri.isEmpty()) {
                throw IOException("GitHub 设备码响应缺少必填字段")
            }
            return DeviceCode(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                expiresIn = if (o.has("expires_in") && !o.get("expires_in").isJsonNull)
                    o.get("expires_in").asInt else 900,
                interval = if (o.has("interval") && !o.get("interval").isJsonNull)
                    o.get("interval").asInt else 5,
                message = safeStr(o, "message")
            )
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException("解析 GitHub 设备码失败: ${t.message}", t)
        }
    }

    /**
     * 第二步：轮询 token 端点直到用户完成授权。
     *
     * @param dc        第一步获取的设备码
     * @param onPending 每次轮询返回 pending 时回调（可用于 UI 提示）
     * @return CompletableFuture，成功时返回 access_token，失败时异常完成
     */
    fun pollForAccessToken(dc: DeviceCode, onPending: Consumer<String>?): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        pollOnce(dc, onPending, future)
        return future
    }

    private fun pollOnce(dc: DeviceCode, onPending: Consumer<String>?, future: CompletableFuture<String>) {
        // 取消/完成/异常后停止调度，避免无效请求触发限流
        if (future.isDone) return
        val body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8.name()) +
            "&device_code=" + URLEncoder.encode(dc.deviceCode, StandardCharsets.UTF_8.name()) +
            "&grant_type=" + URLEncoder.encode(
                "urn:ietf:params:oauth:grant-type:device_code",
                StandardCharsets.UTF_8.name()
            )
        val json: String
        try {
            val req = Request.Builder()
                .url(TOKEN_URL)
                .header("Accept", "application/json")
                .post(body.toRequestBody(FORM))
                .build()
            http.newCall(req).execute().use { resp ->
                json = resp.body?.string() ?: ""
            }
        } catch (e: Throwable) {
            future.completeExceptionally(RuntimeException("网络错误: ${e.message}", e))
            return
        }
        try {
            val o = JsonParser.parseString(json).asJsonObject
            val error = if (o.has("error") && !o.get("error").isJsonNull)
                o.get("error").asString else null
            if (error == null) {
                val token = safeStr(o, "access_token")
                if (token.isEmpty()) {
                    future.completeExceptionally(RuntimeException("GitHub access_token 为空"))
                    return
                }
                future.complete(token)
                return
            }
            when (error) {
                "authorization_pending" -> {
                    onPending?.accept("等待用户授权…")
                }
                "slow_down" -> {
                    scheduler.schedule(
                        { pollOnce(dc, onPending, future) },
                        (dc.interval + 5).toLong(), TimeUnit.SECONDS
                    )
                    return
                }
                "expired_token" -> {
                    future.completeExceptionally(RuntimeException("设备码已过期"))
                    return
                }
                "access_denied" -> {
                    future.completeExceptionally(RuntimeException("用户拒绝授权"))
                    return
                }
                else -> {
                    future.completeExceptionally(RuntimeException("登录失败: $error"))
                    return
                }
            }
        } catch (t: Throwable) {
            future.completeExceptionally(RuntimeException("解析 GitHub token 失败", t))
            return
        }
        // 调度前再次检查，避免取消后仍发请求
        if (future.isDone) return
        scheduler.schedule(
            { pollOnce(dc, onPending, future) },
            dc.interval.toLong(), TimeUnit.SECONDS
        )
    }

    /**
     * 第三步：用 access_token 获取 GitHub 用户信息并构造 Account。
     *
     * - username = GitHub login
     * - uuid = 基于 GitHub 用户 ID 生成的离线 UUID
     * - skinUrl = GitHub 头像 URL（用于卡片头像显示）
     */
    @Throws(IOException::class)
    fun completeLogin(accessToken: String): Account {
        if (accessToken.isBlank()) {
            throw IOException("GitHub access_token 为空，拒绝完成登录")
        }
        val req = Request.Builder()
            .url(USER_API_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        val json = http.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IOException("获取 GitHub 用户信息失败 HTTP ${resp.code}")
            }
            bodyStr
        }
        val o = JsonParser.parseString(json).asJsonObject
        val login = safeStr(o, "login")
        if (login.isEmpty()) {
            throw IOException("GitHub 用户信息缺少 login")
        }
        val githubId = if (o.has("id") && !o.get("id").isJsonNull) o.get("id").asLong else 0L
        val avatarUrl = safeStr(o, "avatar_url")
        val uuid = UUID.nameUUIDFromBytes(
            ("GitHub:$githubId").toByteArray(StandardCharsets.UTF_8)
        ).toString()
        return Account(
            username = login,
            uuid = uuid,
            accessToken = accessToken,
            type = Account.AccountType.GITHUB,
            skinUrl = avatarUrl,
            skinModel = "classic"
        )
    }

    private fun safeStr(o: com.google.gson.JsonObject, key: String): String =
        if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else ""

    companion object {
        /** 内置默认 Client ID（设备码流程）；生产部署建议用自定义 Client ID 覆盖 */
        const val DEFAULT_CLIENT_ID = "Ov23liql9Lz1BxIbL1xX"
        const val SCOPE = "read:user"

        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val USER_API_URL = "https://api.github.com/user"

        private val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}
