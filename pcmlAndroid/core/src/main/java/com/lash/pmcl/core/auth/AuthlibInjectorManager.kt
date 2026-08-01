package com.lash.pmcl.core.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.util.SsrfChecker
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * authlib-injector 管理器：下载 authlib-injector.jar、预取 Yggdrasil API 信息 — Android 版。
 *
 * authlib-injector 是一个 Java Agent，通过 Java Instrumentation 在运行时修改
 * Minecraft 的 authlib 请求 URL，将其指向自定义的皮肤站。
 *
 * 启动时注入采用预取方式：先 GET 服务器 /api/yggdrasil 获取元数据，
 * Base64 编码后通过 -Dauthlibinjector.yggdrasil.prefetched 参数传入，
 * 避免运行时网络问题导致注入失败。
 *
 * 纯 OkHttp + NIO，无桌面平台依赖。SHA256 校验（fail-closed）。
 */
class AuthlibInjectorManager {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 确保 authlib-injector.jar 存在且为最新版本。
     * 若本地不存在则从官方下载；存在则检查版本号，过时则更新。
     *
     * @param jarPath 本地存储路径（如 app 缓存目录下的 authlib-injector.jar）
     * @throws IOException 下载或写入失败
     */
    @Throws(IOException::class)
    fun ensureJar(jarPath: Path) {
        Files.createDirectories(jarPath.parent)

        // 获取最新版本信息
        val info = fetchLatestVersionInfo()
        val versionFile = jarPath.resolveSibling(jarPath.fileName.toString() + ".version")
        val hashFile = jarPath.resolveSibling(jarPath.fileName.toString() + ".sha256")
        if (info == null) {
            // 无网络版本信息：必须用已存 SHA256 重新哈希比对，不能仅凭文件存在就信任
            if (Files.exists(jarPath) && Files.exists(hashFile)) {
                val expected = String(Files.readAllBytes(hashFile), StandardCharsets.UTF_8).trim()
                if (!expected.matches(Regex("[0-9a-fA-F]{64}"))) {
                    throw IOException("本地 authlib-injector SHA256 旁路无效，拒绝离线使用")
                }
                val actual = sha256Hex(jarPath)
                if (!actual.equals(expected, ignoreCase = true)) {
                    Files.deleteIfExists(jarPath)
                    Files.deleteIfExists(hashFile)
                    Files.deleteIfExists(versionFile)
                    throw IOException("本地 authlib-injector.jar 与已存 SHA256 不匹配，拒绝使用")
                }
                System.err.println("[AuthlibInjectorManager] 无法获取最新版本信息，本地 jar SHA256 校验通过，继续使用")
                return
            }
            if (Files.exists(jarPath)) {
                throw IOException("无法获取 authlib-injector 版本信息，且本地 jar 缺少已存 SHA256，拒绝使用未校验文件")
            }
            throw IOException("无法获取 authlib-injector 版本信息，且本地不存在 jar 文件")
        }

        // 检查本地版本是否最新；命中时仍校验 SHA256
        if (Files.exists(jarPath) && Files.exists(versionFile)) {
            try {
                val localVersion = String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim()
                if (localVersion == info.version) {
                    if (info.sha256.isEmpty()) {
                        throw IOException("官方版本信息未提供 sha256，拒绝信任缓存的 authlib-injector.jar")
                    }
                    val actual = sha256Hex(jarPath)
                    if (actual.equals(info.sha256, ignoreCase = true)) {
                        Files.write(hashFile, info.sha256.toByteArray(StandardCharsets.UTF_8))
                        return
                    }
                    System.err.println("[AuthlibInjectorManager] 本地 jar SHA256 不匹配，重新下载")
                    Files.deleteIfExists(jarPath)
                    Files.deleteIfExists(versionFile)
                    Files.deleteIfExists(hashFile)
                }
            } catch (e: IOException) {
                if (e.message != null && e.message!!.contains("未提供 sha256")) throw e
                System.err.println("[AuthlibInjectorManager] 读取本地版本/校验失败，将重新下载: ${e.message}")
            }
        }

        // 下载 jar（先落到临时文件，校验通过后再原子提升，避免 javaagent 用上坏文件）
        System.err.println("[AuthlibInjectorManager] 下载 authlib-injector ${info.version} from ${info.downloadUrl}")
        if (info.sha256.isEmpty()) {
            throw IOException("官方版本信息未提供 sha256，拒绝安装未校验的 authlib-injector.jar")
        }
        val tmp = jarPath.resolveSibling(jarPath.fileName.toString() + ".verified-tmp")
        Files.deleteIfExists(tmp)
        try {
            downloadFile(info.downloadUrl, tmp)
            val actual = sha256Hex(tmp)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                throw IOException(
                    "authlib-injector.jar SHA256 校验失败：预期 ${info.sha256}" +
                        "，实际 $actual（文件可能被篡改或下载损坏）"
                )
            }
            try {
                Files.move(tmp, jarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, jarPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            try { Files.deleteIfExists(tmp) } catch (ignored: IOException) {}
            Files.deleteIfExists(jarPath)
            Files.deleteIfExists(versionFile)
            Files.deleteIfExists(hashFile)
            throw e
        }
        System.err.println("[AuthlibInjectorManager] SHA256 校验通过")
        Files.write(versionFile, info.version.toByteArray(StandardCharsets.UTF_8))
        Files.write(hashFile, info.sha256.toByteArray(StandardCharsets.UTF_8))
        System.err.println("[AuthlibInjectorManager] authlib-injector.jar 下载完成: $jarPath")
    }

    /**
     * 预取 Yggdrasil API 元数据，返回 Base64 编码的 prefetched 字符串。
     *
     * GET 皮肤站 /api/yggdrasil 端点，获取包含 skinDomains、signaturePublickey 等
     * 元数据的 JSON，Base64 编码后用于 -Dauthlibinjector.yggdrasil.prefetched 参数。
     *
     * @param apiUrl 皮肤站 API 根地址（如 https://skin.example.com/api/yggdrasil）
     * @return Base64 编码的 prefetched 数据；失败返回 null
     */
    fun prefetchYggdrasilApi(apiUrl: String): String? {
        val normalizedUrl = YggdrasilAuthFlow.normalizeApiUrl(apiUrl)
        val fetchUrl = if (normalizedUrl.endsWith("/"))
            normalizedUrl.substring(0, normalizedUrl.length - 1) else normalizedUrl
        val ssrf = SsrfChecker.validateAllowingPrivateLan(fetchUrl)
        if (ssrf != null) {
            System.err.println("[AuthlibInjectorManager] 预取被 SSRF 防护拒绝: $ssrf")
            return null
        }

        val req = Request.Builder()
            .url(fetchUrl)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    System.err.println("[AuthlibInjectorManager] 预取 Yggdrasil API 失败 (HTTP ${resp.code})")
                    return@use null
                }
                val body = resp.body?.string() ?: ""
                if (body.isEmpty()) {
                    System.err.println("[AuthlibInjectorManager] 预取 Yggdrasil API 返回空响应")
                    return@use null
                }
                // Base64 编码（不换行）
                val base64 = Base64.getEncoder().encodeToString(body.toByteArray(StandardCharsets.UTF_8))
                System.err.println("[AuthlibInjectorManager] Yggdrasil API 预取成功，长度=${base64.length}")
                base64
            }
        } catch (e: IOException) {
            System.err.println("[AuthlibInjectorManager] 预取 Yggdrasil API 网络错误: ${e.message}")
            null
        }
    }

    /**
     * 获取 authlib-injector 最新版本信息。
     * 版本信息 JSON 格式：{ "version": "1.2.3", "downloadUrl": "https://...", "sha256": "..." }
     */
    private fun fetchLatestVersionInfo(): VersionInfo? {
        val req = Request.Builder()
            .url(VERSION_INFO_URL)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    System.err.println("[AuthlibInjectorManager] 获取版本信息失败 (HTTP ${resp.code})")
                    return@use null
                }
                val body = resp.body?.string() ?: ""
                val o = JsonParser.parseString(body).asJsonObject
                val info = VersionInfo(
                    version = safeStr(o, "version"),
                    downloadUrl = safeStr(o, "downloadUrl"),
                    sha256 = safeStr(o, "sha256")
                )
                if (info.version.isEmpty() || info.downloadUrl.isEmpty()) {
                    System.err.println("[AuthlibInjectorManager] 版本信息缺少必要字段")
                    return@use null
                }
                info
            }
        } catch (e: IOException) {
            System.err.println("[AuthlibInjectorManager] 获取版本信息网络错误: ${e.message}")
            null
        }
    }

    /** 下载文件到指定路径 */
    @Throws(IOException::class)
    private fun downloadFile(url: String, target: Path) {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("下载失败 (HTTP ${resp.code}): $url")
            }
            val body = resp.body ?: throw IOException("下载响应体为空: $url")
            body.byteStream().use { ins ->
                Files.copy(ins, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /**
     * 计算文件 SHA-256 摘要，返回小写十六进制字符串。
     * 用于校验下载的 authlib-injector.jar 完整性，防止供应链攻击。
     */
    @Throws(IOException::class)
    private fun sha256Hex(file: Path): String {
        Files.newInputStream(file).use { ins ->
            val md: MessageDigest = try {
                MessageDigest.getInstance("SHA-256")
            } catch (e: NoSuchAlgorithmException) {
                throw IOException("SHA-256 算法不可用", e)
            }
            val buf = ByteArray(8192)
            var n = ins.read(buf)
            while (n != -1) {
                md.update(buf, 0, n)
                n = ins.read(buf)
            }
            val digest = md.digest()
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            return sb.toString()
        }
    }

    private fun safeStr(o: JsonObject, key: String): String =
        if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else ""

    /** authlib-injector 版本信息 */
    private data class VersionInfo(
        val version: String,
        val downloadUrl: String,
        val sha256: String
    )

    companion object {
        /** authlib-injector 版本信息 JSON 地址（官方） */
        private const val VERSION_INFO_URL = "https://authlib-injector.yushi.moe/artifact/latest.json"
    }
}
