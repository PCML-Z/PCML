package com.lash.pmcl.core.auth

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * 微软账号登录完整流程 — Android 版。
 *
 * 流程：
 * 1. OAuth2 设备码流程 → Microsoft access_token
 * 2. Xbox Live 认证 → userToken + userHash
 * 3. XSTS 认证 → XSTS token
 * 4. Minecraft Services 登录 → MC access_token
 * 5. 获取玩家档案 → username + uuid
 *
 * 与桌面版的差异：
 * - 完全移除 CurlFallback（Android 上 OkHttp 走系统网络栈，无 GFW TLS 指纹问题）
 * - 移除 OAuthCallbackServer + 浏览器授权码流程（Android 应使用 Custom Tabs + Intent 回调，
 *   由 UI 层实现，core 只暴露设备码流程）
 * - 保留 refresh_token 刷新逻辑
 * - 保留 license 端点区分「未购买」与「有游戏但无档案」
 */
class MicrosoftAuthFlow(
    /** Azure client_id，默认为 Minecraft 官方启动器的 legacy client_id */
    private val clientId: String = LEGACY_CLIENT_ID
) {

    /** 微软 OAuth token 响应（含 refresh，供启动前刷新） */
    data class MsOAuthToken(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSec: Int
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "msauth-scheduler").apply { isDaemon = true }
    }

    fun hasCustomClientId(): Boolean = LEGACY_CLIENT_ID != clientId

    /** 关闭内部调度线程和 HTTP 连接池，释放资源。关闭后不可再用。 */
    fun shutdown() {
        scheduler.shutdownNow()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    /**
     * 第一步：请求设备码。用户需要在浏览器中打开 verificationUri 并输入 userCode。
     */
    @Throws(IOException::class)
    fun requestDeviceCode(): DeviceCode {
        val body = "client_id=$clientId" +
            "&scope=" + URLEncoder.encode(V2_SCOPE, StandardCharsets.UTF_8.name()) +
            "&response_type=device_code"
        val req = Request.Builder()
            .url(DEVICE_CODE_URL)
            .post(body.toRequestBody(FORM_MEDIA_TYPE))
            .build()
        val json = http.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful && bodyStr.isEmpty()) {
                throw IOException("请求设备码失败 code=${resp.code}")
            }
            bodyStr
        }
        try {
            val o = JsonParser.parseString(json).asJsonObject
            val error = safeStr(o, "error")
            if (error.isNotEmpty()) {
                throw IOException("请求设备码失败: $error ${safeStr(o, "error_description")}")
            }
            // 微软 device code 端点返回字段为 verification_uri（部分旧文档写作 verification_url）
            var verificationUri = safeStr(o, "verification_uri")
            if (verificationUri.isEmpty()) verificationUri = safeStr(o, "verification_url")
            val deviceCode = safeStr(o, "device_code")
            val userCode = safeStr(o, "user_code")
            if (deviceCode.isEmpty() || userCode.isEmpty() || verificationUri.isEmpty()) {
                throw IOException("设备码响应缺少必填字段 (${sanitizeForError(json)})")
            }
            return DeviceCode(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                expiresIn = if (o.has("expires_in") && !o.get("expires_in").isJsonNull)
                    maxOf(1, o.get("expires_in").asInt) else 900,
                interval = if (o.has("interval") && !o.get("interval").isJsonNull)
                    maxOf(5, o.get("interval").asInt) else 5,
                message = safeStr(o, "message")
            )
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException("解析设备码响应失败: ${t.message} (${sanitizeForError(json)})", t)
        }
    }

    /**
     * 第二步：轮询 token 端点直到用户完成登录。
     *
     * @param onPending 每次轮询返回 pending 时回调（可用于 UI 提示）
     */
    fun pollForMsOAuthToken(
        dc: DeviceCode,
        onPending: Consumer<String>?
    ): CompletableFuture<MsOAuthToken> {
        val future = CompletableFuture<MsOAuthToken>()
        pollOnce(dc, onPending, future)
        return future
    }

    private fun pollOnce(
        dc: DeviceCode,
        onPending: Consumer<String>?,
        future: CompletableFuture<MsOAuthToken>
    ) {
        if (future.isDone) return
        val body = "client_id=$clientId" +
            "&grant_type=urn:ietf:params:oauth:grant-type:device_code" +
            "&device_code=" + URLEncoder.encode(dc.deviceCode, StandardCharsets.UTF_8.name())
        val json: String
        try {
            val req = Request.Builder()
                .url(TOKEN_URL)
                .post(body.toRequestBody(FORM_MEDIA_TYPE))
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
                // 成功
                val token = safeStr(o, "access_token")
                if (token.isEmpty()) {
                    future.completeExceptionally(RuntimeException(
                        "token 响应中 access_token 为空 (${sanitizeForError(json)})"))
                    return
                }
                val refresh = safeStr(o, "refresh_token")
                val expiresIn = if (o.has("expires_in") && !o.get("expires_in").isJsonNull)
                    o.get("expires_in").asInt else 0
                future.complete(MsOAuthToken(token, refresh, expiresIn))
                return
            }
            when (error) {
                "authorization_pending" -> {
                    onPending?.accept("等待用户登录…")
                }
                "slow_down" -> {
                    scheduler.schedule({
                        pollOnce(dc, onPending, future)
                    }, (dc.interval + 5).toLong(), TimeUnit.SECONDS)
                    return
                }
                "expired_token" -> {
                    future.completeExceptionally(RuntimeException("设备码已过期"))
                    return
                }
                "authorization_declined" -> {
                    future.completeExceptionally(RuntimeException("用户拒绝授权"))
                    return
                }
                else -> {
                    future.completeExceptionally(RuntimeException("登录失败: $error"))
                    return
                }
            }
        } catch (t: Throwable) {
            future.completeExceptionally(RuntimeException(
                "解析 token 响应失败: ${t.message} (${sanitizeForError(json)})", t))
            return
        }
        if (future.isDone) return
        scheduler.schedule({
            pollOnce(dc, onPending, future)
        }, dc.interval.toLong(), TimeUnit.SECONDS)
    }

    /**
     * 第三步：用 MS access_token 换取 Xbox Live userToken。
     * 返回 [userToken, userHash]。
     */
    @Throws(IOException::class)
    fun authXboxLive(msAccessToken: String): Pair<String, String> {
        if (msAccessToken.isEmpty()) {
            throw IOException("MS access_token 为空，无法认证 Xbox Live")
        }
        val props = JsonObject().apply {
            addProperty("AuthMethod", "RPS")
            addProperty("SiteName", "user.auth.xboxlive.com")
            addProperty("RpsTicket", "d=$msAccessToken")
        }
        val payload = JsonObject().apply {
            add("Properties", props)
            addProperty("RelyingParty", "http://auth.xboxlive.com")
            addProperty("TokenType", "JWT")
        }
        val resp = try {
            postJson(XBL_URL, payload)
        } catch (e: IOException) {
            throw IOException("Xbox Live 认证失败: ${e.message}" +
                "（可能原因：token 已过期 / 网络中断 / scope 不匹配）", e)
        }
        val userToken = safeStr(resp, "Token")
        var userHash = ""
        if (resp.has("DisplayClaims") && resp.getAsJsonObject("DisplayClaims").has("xui")) {
            val xui = resp.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
            if (xui.size() > 0 && xui[0].asJsonObject.has("uhs")) {
                userHash = xui[0].asJsonObject.get("uhs").asString
            }
        }
        return Pair(userToken, userHash)
    }

    /**
     * 第四步：用 userToken 换取 XSTS token。
     */
    @Throws(IOException::class)
    fun authXsts(userToken: String): String {
        val props = JsonObject().apply {
            addProperty("SandboxId", "RETAIL")
            val arr = com.google.gson.JsonArray()
            arr.add(userToken)
            add("UserTokens", arr)
        }
        val payload = JsonObject().apply {
            add("Properties", props)
            addProperty("RelyingParty", "rp://api.minecraftservices.com/")
            addProperty("TokenType", "JWT")
        }
        val resp = postJson(XSTS_URL, payload)
        return safeStr(resp, "Token")
    }

    /**
     * 第五步：用 XSTS token 换取 MC access_token。
     * @return Pair<mcAccessToken, expiresInSeconds>
     */
    @Throws(IOException::class)
    fun loginMinecraft(xstsToken: String, userHash: String): Pair<String, Int> {
        val payload = JsonObject().apply {
            addProperty("identityToken", "XBL3.0 x=$userHash;$xstsToken")
        }
        val resp = postJson(MC_LOGIN_URL, payload)
        val token = safeStr(resp, "access_token")
        val expiresIn = if (resp.has("expires_in") && !resp.get("expires_in").isJsonNull)
            resp.get("expires_in").asInt else 86400
        return Pair(token, maxOf(expiresIn, 60))
    }

    /**
     * 第六步：获取玩家档案（username + uuid + skinUrl + skinModel）。
     * 返回 Quad(name, uuid, skinUrl, skinModel)。
     *
     * 404 时调用 license 端点区分两种情况：
     * - 有 game_minecraft license：账号有游戏但未创建档案（Game Pass 用户需先在
     *   minecraft.net 登录一次以创建玩家档案）
     * - 无 license：账号未购买 Minecraft Java 版
     */
    @Throws(IOException::class)
    fun fetchProfile(mcAccessToken: String): AccountProfile {
        val req = Request.Builder()
            .url(MC_PROFILE_URL)
            .header("Authorization", "Bearer $mcAccessToken")
            .get().build()
        val profileJson: String = http.newCall(req).execute().use { resp ->
            if (resp.code == 404) {
                val hasGame = checkLicense(mcAccessToken)
                if (hasGame) {
                    throw IOException("账号已拥有 Minecraft 但无玩家档案 (404)。" +
                        "请先在 minecraft.net 登录一次以创建档案，再返回启动器登录。")
                } else {
                    throw IOException("此微软账号未购买 Minecraft Java 版 (profile 404)，无法登录。" +
                        "若你是 Xbox Game Pass 用户，需先在 minecraft.net 登录一次激活档案。")
                }
            }
            if (!resp.isSuccessful) {
                throw IOException("获取档案失败 code=${resp.code}")
            }
            resp.body?.string() ?: ""
        }
        val o = JsonParser.parseString(profileJson).asJsonObject
        val name = safeStr(o, "name")
        val uuid = safeStr(o, "id")
        var skinUrl = ""
        var skinModel = "classic"
        if (o.has("skins")) {
            for (skinElem in o.getAsJsonArray("skins")) {
                val skin = skinElem.asJsonObject
                val state = if (skin.has("state")) skin.get("state").asString else ""
                if ("ACTIVE".equals(state, ignoreCase = true)) {
                    skinUrl = if (skin.has("url")) skin.get("url").asString else ""
                    skinModel = if (skin.has("variant")) skin.get("variant").asString else "classic"
                    break
                }
            }
        }
        return AccountProfile(name, uuid, skinUrl, skinModel)
    }

    /** 玩家档案解析结果 */
    data class AccountProfile(
        val name: String,
        val uuid: String,
        val skinUrl: String,
        val skinModel: String
    )

    /**
     * 轻量校验 MC accessToken 是否仍被 minecraftservices 接受。
     * @return true=可用；false=401/403；网络等其它错误抛 IOException
     */
    @Throws(IOException::class)
    fun isMcAccessTokenValid(mcAccessToken: String?): Boolean {
        if (mcAccessToken.isNullOrEmpty()) return false
        val req = Request.Builder()
            .url(MC_PROFILE_URL)
            .header("Authorization", "Bearer $mcAccessToken")
            .get().build()
        http.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code == 401 || code == 403) return false
            if (resp.isSuccessful || code == 404) return true
            throw IOException("校验 MC token 失败 code=$code")
        }
    }

    /**
     * 校验是否拥有 MC。
     */
    @Throws(IOException::class)
    fun checkOwnership(mcAccessToken: String): Boolean {
        val req = Request.Builder()
            .url(MC_ENTITLEMENT_URL)
            .header("Authorization", "Bearer $mcAccessToken")
            .get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("entitlements/mcstore HTTP ${resp.code}")
            }
            val json = resp.body?.string() ?: ""
            return entitlementsContainMinecraft(json)
        }
    }

    /**
     * 检查 license 端点是否包含 Minecraft Java / Game Pass 相关项。
     * 比 mcstore 更全面：mcstore 对 Game Pass 用户可能返回空，
     * 而 license 端点会包含订阅状态。
     */
    @Throws(IOException::class)
    fun checkLicense(mcAccessToken: String): Boolean {
        val requestId = java.util.UUID.randomUUID().toString()
        val licenseUrl = "$MC_LICENSE_URL?requestId=$requestId"
        val req = Request.Builder()
            .url(licenseUrl)
            .header("Authorization", "Bearer $mcAccessToken")
            .get().build()
        val json = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("entitlements/license HTTP ${resp.code}")
            }
            resp.body?.string() ?: ""
        }
        return try {
            entitlementsContainMinecraft(json)
        } catch (t: RuntimeException) {
            throw IOException("解析 license 响应失败: ${t.message}", t)
        }
    }

    /** license / mcstore JSON 中是否含 Minecraft Java 或 Game Pass 产品项 */
    private fun entitlementsContainMinecraft(json: String): Boolean {
        val o = JsonParser.parseString(json).asJsonObject
        if (!o.has("items") || !o.get("items").isJsonArray) return false
        for (item in o.getAsJsonArray("items")) {
            if (!item.isJsonObject) continue
            val name = safeStr(item.asJsonObject, "name")
            if (name.isEmpty()) continue
            if (name == "game_minecraft" ||
                name == "product_minecraft" ||
                name == "product_minecraft_java" ||
                name.startsWith("product_game_pass")) {
                return true
            }
        }
        return false
    }

    /**
     * 端到端登录（保留 refresh_token，供启动前自动刷新）。
     */
    @Throws(IOException::class)
    fun completeLogin(oauth: MsOAuthToken): Account {
        if (oauth.accessToken.isEmpty()) {
            throw IOException("MS access_token 为空")
        }
        val (userToken, userHash) = authXboxLive(oauth.accessToken)
        val xsts = authXsts(userToken)
        val (mcToken, expiresIn) = loginMinecraft(xsts, userHash)
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        val profile = fetchProfile(mcToken)
        return Account(
            username = profile.name,
            uuid = profile.uuid,
            accessToken = mcToken,
            type = Account.AccountType.MICROSOFT,
            skinUrl = profile.skinUrl,
            skinModel = profile.skinModel,
            xuid = userHash,
            msRefreshToken = oauth.refreshToken,
            expiresAt = expiresAt
        )
    }

    /**
     * 用 refresh_token 刷新微软会话并换发新的 MC accessToken。
     * 失败抛 IOException（调用方应提示重新登录）。
     */
    @Throws(IOException::class)
    fun refreshLogin(msRefreshToken: String?): Account {
        if (msRefreshToken.isNullOrEmpty()) {
            throw IOException("无 refresh_token，请重新登录微软账号")
        }
        val oauth = refreshMsOAuthToken(msRefreshToken)
        return completeLogin(oauth)
    }

    /**
     * 用 refresh_token 换取新的 MS access_token。
     * 浏览器登录发自 v2 consumers 端点，设备码发自 login.live.com；
     * 先试 v2 再回退 live，避免端点错配导致 invalid_grant。
     */
    @Throws(IOException::class)
    fun refreshMsOAuthToken(refreshToken: String): MsOAuthToken {
        var first: IOException? = null
        try {
            return refreshMsOAuthTokenAt(V2_TOKEN_URL, refreshToken)
        } catch (e: IOException) {
            first = e
        }
        try {
            return refreshMsOAuthTokenAt(TOKEN_URL, refreshToken)
        } catch (e: IOException) {
            first?.let { e.addSuppressed(it) }
            throw e
        }
    }

    @Throws(IOException::class)
    private fun refreshMsOAuthTokenAt(tokenUrl: String, refreshToken: String): MsOAuthToken {
        val body = "client_id=$clientId" +
            "&grant_type=refresh_token" +
            "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8.name()) +
            "&scope=" + URLEncoder.encode(V2_SCOPE, StandardCharsets.UTF_8.name())
        val req = Request.Builder()
            .url(tokenUrl)
            .post(body.toRequestBody(FORM_MEDIA_TYPE))
            .build()
        val json = http.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful && bodyStr.isEmpty()) {
                throw IOException("刷新 token 失败 code=${resp.code} endpoint=$tokenUrl")
            }
            bodyStr
        }
        try {
            val o = JsonParser.parseString(json).asJsonObject
            val error = safeStr(o, "error")
            if (error.isNotEmpty()) {
                throw IOException("刷新 token 失败: $error ${safeStr(o, "error_description")} endpoint=$tokenUrl")
            }
            val access = safeStr(o, "access_token")
            if (access.isEmpty()) {
                throw IOException("刷新后 access_token 为空 endpoint=$tokenUrl")
            }
            var newRefresh = safeStr(o, "refresh_token")
            if (newRefresh.isEmpty()) newRefresh = refreshToken
            val expiresIn = if (o.has("expires_in") && !o.get("expires_in").isJsonNull)
                o.get("expires_in").asInt else 0
            return MsOAuthToken(access, newRefresh, expiresIn)
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException("解析 refresh 响应失败: ${t.message}", t)
        }
    }

    @Throws(IOException::class)
    private fun postJson(url: String, payload: JsonObject): JsonObject {
        val bodyJson = gson.toJson(payload)
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = http.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IOException("请求失败 $url code=${resp.code} (${sanitizeForError(bodyStr)})")
            }
            bodyStr
        }
        return JsonParser.parseString(body).asJsonObject
    }

    private fun safeStr(o: JsonObject, key: String): String =
        if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else ""

    /**
     * 异常消息不得包含可能含 token 的响应体。
     * 仅保留长度与是否 JSON 的粗粒度诊断信息。
     */
    private fun sanitizeForError(body: String?): String {
        if (body.isNullOrEmpty()) return "(empty)"
        val len = body.length
        val kind = if (body.trim().startsWith("{") || body.trim().startsWith("[")) "json" else "text"
        return "$kind, len=$len"
    }

    companion object {
        /**
         * Legacy 公共客户端ID（来自 Minecraft 官方启动器）。
         * 该 client_id 仅在 login.live.com 端点可用；v2.0 consumers tenant
         * (login.microsoftonline.com) 不识别它（返回 AADSTS700016）。
         */
        const val LEGACY_CLIENT_ID = "00000000402b5328"

        /** 实际使用的 scope：login.live.com 端点支持此 v2.0 scope 且接受 LEGACY_CLIENT_ID。 */
        const val V2_SCOPE = "XboxLive.signin offline_access"

        private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
        private const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"
        private const val V2_TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private const val XBL_URL = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox"
        private const val MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile"
        private const val MC_ENTITLEMENT_URL =
            "https://api.minecraftservices.com/entitlements/mcstore"
        private const val MC_LICENSE_URL =
            "https://api.minecraftservices.com/entitlements/license"

        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
