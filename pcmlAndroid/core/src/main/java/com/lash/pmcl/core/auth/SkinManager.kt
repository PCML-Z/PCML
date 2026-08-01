package com.lash.pmcl.core.auth

import com.lash.pmcl.core.util.SsrfChecker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 皮肤管理器：为微软账号和皮肤站账号上传/重置皮肤 — Android 版。
 *
 * 微软账号使用 Mojang 标准 API：
 * - 上传：POST https://api.minecraftservices.com/minecraft/profile/skins
 * - 重置：DELETE https://api.minecraftservices.com/minecraft/profile/skins/active
 *
 * 皮肤站账号使用 Blessing Skin Server 标准 API（LittleSkin 等兼容）：
 * - 上传：POST <apiUrl>/api/skin/upload（multipart: skin file + model + pid）
 * - 重置：DELETE <apiUrl>/api/skin/delete?pid=<playerId>
 *
 * 皮肤站 API 需要用户的 Web session token（非 yggdrasil accessToken），
 * 因此上传时需要用户名密码重新登录获取 session。
 *
 * 与桌面版的差异：皮肤文件参数由 Path 改为 InputStream（Android 友好），
 * 内部读取为字节数组校验后复用上传，避免二次读取流。
 */
class SkinManager {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 上传皮肤到微软账号。
     *
     * @param mcAccessToken Minecraft access token（非 MS access token）
     * @param skinStream    皮肤 PNG 输入流
     * @param model         "classic" 或 "slim"
     * @throws IOException 上传失败
     */
    @Throws(IOException::class)
    fun uploadMicrosoftSkin(mcAccessToken: String, skinStream: InputStream, model: String) {
        if (mcAccessToken.isEmpty()) {
            throw IOException("Minecraft access token 为空，请重新登录")
        }
        val skinBytes = validateSkinFile(skinStream)

        val png = "image/png".toMediaType()
        val fileBody = skinBytes.toRequestBody(png)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "skin.png", fileBody)
            .build()

        val request = Request.Builder()
            .url(MS_SKIN_API)
            .header("Authorization", "Bearer $mcAccessToken")
            .post(body)
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = readErrorBody(resp)
                if (resp.code == 401) {
                    throw IOException("access token 已过期，请重新登录微软账号")
                }
                throw IOException("上传皮肤失败 (${resp.code}): $msg")
            }
        }
    }

    /**
     * 重置微软账号皮肤为默认。
     *
     * @param mcAccessToken Minecraft access token
     * @throws IOException 重置失败
     */
    @Throws(IOException::class)
    fun resetMicrosoftSkin(mcAccessToken: String) {
        if (mcAccessToken.isEmpty()) {
            throw IOException("Minecraft access token 为空，请重新登录")
        }

        val request = Request.Builder()
            .url("$MS_SKIN_API/active")
            .header("Authorization", "Bearer $mcAccessToken")
            .delete()
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = readErrorBody(resp)
                if (resp.code == 401) {
                    throw IOException("access token 已过期，请重新登录微软账号")
                }
                throw IOException("重置皮肤失败 (${resp.code}): $msg")
            }
        }
    }

    /**
     * 上传皮肤到皮肤站账号（Blessing Skin Server API）。
     *
     * 流程：用用户名密码登录获取 session cookie → 上传皮肤 → 释放 session。
     * 密码仅在内存中临时使用，不持久化。
     *
     * @param baseUrl   皮肤站基础地址（如 https://littleskin.cn）
     * @param username  用户名（邮箱）
     * @param password  密码
     * @param playerId  角色 UUID（去掉横线）
     * @param skinStream 皮肤 PNG 输入流
     * @param model     "steve"（经典）或 "slim"（纤细）
     * @throws IOException 上传失败
     */
    @Throws(IOException::class)
    fun uploadYggdrasilSkin(
        baseUrl: String, username: String, password: String,
        playerId: String, skinStream: InputStream, model: String
    ) {
        if (baseUrl.isEmpty()) {
            throw IOException("皮肤站地址为空")
        }
        val skinBytes = validateSkinFile(skinStream)

        // 规范化基础 URL：去掉末尾的 /api/yggdrasil
        var base = baseUrl
        if (base.endsWith("/api/yggdrasil")) {
            base = base.substring(0, base.length - "/api/yggdrasil".length)
        }
        while (base.endsWith("/")) base = base.substring(0, base.length - 1)
        assertYggdrasilBaseSafe(base)

        // 1. 登录获取 session cookie
        val loginJson = "{\"email\":\"${escapeJson(username)}\",\"password\":\"${escapeJson(password)}\"}"
        val loginReq = Request.Builder()
            .url("$base/auth/login")
            .header("Content-Type", "application/json")
            .post(loginJson.toRequestBody("application/json".toMediaType()))
            .build()

        val cookies: String = http.newCall(loginReq).execute().use { loginResp ->
            if (!loginResp.isSuccessful) {
                throw IOException("皮肤站登录失败 (${loginResp.code}): ${readErrorBody(loginResp)}")
            }
            // 提取 Set-Cookie 头中的 session cookie
            val c = extractCookies(loginResp)
            if (c.isEmpty()) {
                throw IOException("皮肤站登录未返回 session，请检查用户名和密码")
            }
            c
        }

        // 2. 上传皮肤
        val png = "image/png".toMediaType()
        val fileBody = skinBytes.toRequestBody(png)
        val uploadBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("pid", playerId)
            .addFormDataPart("model", model)
            .addFormDataPart("skin", "skin.png", fileBody)
            .build()

        val uploadReq = Request.Builder()
            .url("$base/api/skin/upload")
            .header("Cookie", cookies)
            .post(uploadBody)
            .build()

        http.newCall(uploadReq).execute().use { uploadResp ->
            if (!uploadResp.isSuccessful) {
                val msg = readErrorBody(uploadResp)
                throw IOException("上传皮肤到皮肤站失败 (${uploadResp.code}): $msg")
            }
        }
    }

    /**
     * 重置皮肤站账号皮肤。
     *
     * @param baseUrl   皮肤站基础地址
     * @param username  用户名
     * @param password  密码
     * @param playerId  角色 UUID（去掉横线）
     * @throws IOException 重置失败
     */
    @Throws(IOException::class)
    fun resetYggdrasilSkin(
        baseUrl: String, username: String, password: String,
        playerId: String
    ) {
        var base = baseUrl
        if (base.endsWith("/api/yggdrasil")) {
            base = base.substring(0, base.length - "/api/yggdrasil".length)
        }
        while (base.endsWith("/")) base = base.substring(0, base.length - 1)
        assertYggdrasilBaseSafe(base)

        // 登录获取 session
        val loginJson = "{\"email\":\"${escapeJson(username)}\",\"password\":\"${escapeJson(password)}\"}"
        val loginReq = Request.Builder()
            .url("$base/auth/login")
            .header("Content-Type", "application/json")
            .post(loginJson.toRequestBody("application/json".toMediaType()))
            .build()

        val cookies: String = http.newCall(loginReq).execute().use { loginResp ->
            if (!loginResp.isSuccessful) {
                throw IOException("皮肤站登录失败 (${loginResp.code}): ${readErrorBody(loginResp)}")
            }
            val c = extractCookies(loginResp)
            if (c.isEmpty()) {
                throw IOException("皮肤站登录未返回 session")
            }
            c
        }

        // 删除皮肤
        val deleteReq = Request.Builder()
            .url("$base/api/skin/delete?pid=" + URLEncoder.encode(playerId, StandardCharsets.UTF_8.name()))
            .header("Cookie", cookies)
            .delete()
            .build()

        http.newCall(deleteReq).execute().use { delResp ->
            if (!delResp.isSuccessful) {
                throw IOException("重置皮肤站皮肤失败 (${delResp.code}): ${readErrorBody(delResp)}")
            }
        }
    }

    /** 允许局域网皮肤站，拒绝云 metadata 等链路本地目标 */
    @Throws(IOException::class)
    private fun assertYggdrasilBaseSafe(base: String) {
        val ssrf = SsrfChecker.validateAllowingPrivateLan(base)
        if (ssrf != null) {
            throw IOException("皮肤站地址被拒绝（SSRF 防护）: $ssrf")
        }
    }

    /**
     * 校验皮肤流：读取为字节数组，校验 PNG 魔数 + IHDR 尺寸（64×32 / 64×64 / 128×128）+ ≤1MB。
     * 返回读取到的字节数组（供上传复用，避免二次读取 InputStream）。
     */
    @Throws(IOException::class)
    private fun validateSkinFile(skinStream: InputStream): ByteArray {
        // 读取最多 MAX + 1 字节以检测超限，避免超大流导致 OOM
        val maxBytes = 1024 * 1024
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = skinStream.read(chunk)
            if (n == -1) break
            total += n
            if (total > maxBytes) {
                throw IOException("皮肤文件过大（超过 ${maxBytes / 1024}KB），最大支持 1MB")
            }
            buf.write(chunk, 0, n)
        }
        val data = buf.toByteArray()
        if (data.size < 33) {
            throw IOException("皮肤文件过小，不是有效 PNG")
        }
        // PNG signature
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in sig.indices) {
            if (data[i] != sig[i]) {
                throw IOException("皮肤文件不是有效 PNG（魔数不匹配）")
            }
        }
        // IHDR 尺寸位于字节 16-23
        val width = ((data[16].toInt() and 0xFF) shl 24) or ((data[17].toInt() and 0xFF) shl 16) or
            ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val height = ((data[20].toInt() and 0xFF) shl 24) or ((data[21].toInt() and 0xFF) shl 16) or
            ((data[22].toInt() and 0xFF) shl 8) or (data[23].toInt() and 0xFF)
        val okSize = (width == 64 && (height == 32 || height == 64)) ||
            (width == 128 && height == 128) // 部分高清皮肤
        if (!okSize) {
            throw IOException("皮肤尺寸必须为 64x32 / 64x64（或 128x128），当前 ${width}x${height}")
        }
        return data
    }

    /** 从响应头提取 Cookie */
    private fun extractCookies(resp: Response): String {
        val cookieHeaders = resp.headers("Set-Cookie")
        if (cookieHeaders.isEmpty()) return ""
        val sb = StringBuilder()
        for (c in cookieHeaders) {
            val semi = c.indexOf(';')
            if (semi > 0) {
                sb.append(c, 0, semi).append("; ")
            } else {
                sb.append(c).append("; ")
            }
        }
        return sb.toString().trim()
    }

    /** 读取错误响应体 */
    private fun readErrorBody(resp: Response): String {
        return try {
            val body = resp.body?.string() ?: ""
            if (body.length > 300) body.substring(0, 300) else body
        } catch (e: IOException) {
            "(无法读取响应体)"
        }
    }

    /** 简单 JSON 字符串转义 */
    private fun escapeJson(s: String?): String {
        if (s == null) return ""
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    companion object {
        private const val MS_SKIN_API = "https://api.minecraftservices.com/minecraft/profile/skins"
    }
}
