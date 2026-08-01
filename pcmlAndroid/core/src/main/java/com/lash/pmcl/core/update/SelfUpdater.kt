package com.lash.pmcl.core.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.paths.PmclPaths
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * 启动器自更新：从已签名的远程清单检查最新版本并下载替换。
 *
 * Android 版本：
 * - 路径通过 [PmclPaths.updates] 获取，不硬编码 ~/.pmcl
 * - 移除 POSIX 文件权限（Android 沙箱已隔离 app 私有目录）
 * - 移除 Files.createTempFile（用手动命名临时文件）
 * - 验签通过 [UpdateSignatureVerifier] 实例注入（公钥从 Android assets/res 加载）
 *
 * 清单格式：
 * ```
 * {
 *   "version": "1.0.1",
 *   "url": "https://.../pmcl.jar",
 *   "sha256": "...",
 *   "sha1": "...",
 *   "size": 12345,
 *   "notes": "...",
 *   "signature": "<Base64 Ed25519>"
 * }
 * ```
 *
 * 本实现仅完成「下载并验证」，不替换运行中的 APK（Android 需通过系统 PackageInstaller）。
 */
class SelfUpdater(
    private val downloadManager: DownloadManager,
    private val paths: PmclPaths,
    private val signatureVerifier: UpdateSignatureVerifier,
    private val manifestUrl: String,
    private val currentVersion: String
) {
    /** 更新来源信任模型 */
    enum class TrustedChannel {
        /** 自定义清单：必须通过固定公钥 Ed25519 验签 */
        SIGNED_MANIFEST,
        /** GitHub Releases API + asset SHA-256 digest + Ed25519 签名资产（.sig） */
        GITHUB_RELEASE
    }

    data class UpdateInfo(
        val version: String,
        val url: String,
        val sha1: String,
        val sha256: String,
        val size: Long,
        val notes: String,
        val signature: String?,
        val channel: TrustedChannel = TrustedChannel.SIGNED_MANIFEST
    )

    /** 检查更新（若 manifestUrl 为空返回 null） */
    fun checkUpdate(): CompletableFuture<UpdateInfo?> {
        if (manifestUrl.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }
        return CompletableFuture.supplyAsync {
            try {
                requireHttps(manifestUrl, "更新清单")
                val json = downloadManager.downloadStringSsrfChecked(manifestUrl)
                val o = JsonParser.parseString(json).asJsonObject
                val ver = text(o, "version")
                if (ver.isEmpty() || !UpdateVersions.isNewer(ver, currentVersion)) {
                    return@supplyAsync null
                }
                val url = text(o, "url")
                val sha1 = text(o, "sha1")
                val sha256 = text(o, "sha256")
                val size = if (o.has("size") && !o.get("size").isJsonNull) o.get("size").asLong else 0L
                val notes = text(o, "notes")
                val signature = text(o, "signature")
                requireHttps(url, "更新包")
                signatureVerifier.verifyOrThrow(ver, url, sha256, sha1, size, signature)
                if (sha256.isEmpty() && sha1.isEmpty()) {
                    throw IOException("更新清单未提供 SHA-256/SHA-1，拒绝安装未校验的更新包")
                }
                UpdateInfo(ver, url, sha1, sha256, size, notes, signature, TrustedChannel.SIGNED_MANIFEST)
            } catch (e: IOException) {
                throw RuntimeException("检查更新失败: ${e.message}", e)
            }
        }
    }

    /** 下载更新到 PmclPaths.updates（不替换当前 APK） */
    fun downloadUpdate(info: UpdateInfo?, onProgress: Consumer<Long>?): CompletableFuture<Path> {
        return CompletableFuture.supplyAsync {
            var tmp: Path? = null
            try {
                if (info == null) {
                    throw IOException("更新信息为空")
                }
                requireHttps(info.url, "更新包")
                assertChannelTrust(info)

                val updatesDir = paths.updates.toAbsolutePath().normalize()
                Files.createDirectories(updatesDir)
                // 私有目录下的临时文件（Android 沙箱已隔离，无需 POSIX 权限）
                tmp = updatesDir.resolve("pmcl-update-${System.currentTimeMillis()}.jar.tmp")

                downloadManager.downloadToSsrfChecked(info.url, tmp)

                verifyHashes(info, tmp)

                val ver = info.version
                if (!ver.matches(Regex("[A-Za-z0-9._+-]+")) || ver.contains("..")) {
                    throw IOException("更新版本号非法（拒绝路径穿越）: $ver")
                }
                val target = updatesDir.resolve("pmcl-$ver.jar").normalize()
                if (!target.startsWith(updatesDir)) {
                    throw IOException("更新目标路径越界: $target")
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                tmp = null
                // move 后再核一次哈希，防止替换后内容与校验对象不一致
                verifyHashes(info, target)
                onProgress?.accept(info.size)
                target
            } catch (e: IOException) {
                throw RuntimeException("下载更新失败: ${e.message}", e)
            } finally {
                tmp?.let {
                    try { Files.deleteIfExists(it) } catch (_: IOException) {}
                }
            }
        }
    }

    private fun assertChannelTrust(info: UpdateInfo) {
        if (info.channel == TrustedChannel.GITHUB_RELEASE) {
            if (!isTrustedGitHubDownloadHost(info.url)) {
                throw IOException("GitHub 更新通道的下载 URL 主机不受信任: ${info.url}")
            }
            if (info.sha256.isEmpty()) {
                throw IOException("GitHub 更新缺少 SHA-256 digest，拒绝安装")
            }
        }
        // 两个通道都验证 Ed25519 签名，防止 HTTPS 被绕过后安装未签名更新
        signatureVerifier.verifyOrThrow(
            info.version, info.url, info.sha256, info.sha1, info.size, info.signature
        )
    }

    private fun isTrustedGitHubDownloadHost(url: String): Boolean {
        return try {
            val host = URI.create(url).host?.lowercase(Locale.ROOT) ?: return false
            host == "github.com" ||
                host.endsWith(".github.com") ||
                host == "objects.githubusercontent.com" ||
                host == "release-assets.githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
        } catch (e: Exception) {
            false
        }
    }

    private fun requireHttps(url: String?, what: String) {
        if (url.isNullOrBlank()) {
            throw IOException("$what URL 为空")
        }
        try {
            val scheme = URI.create(url.trim()).scheme
            if (scheme == null || !scheme.equals("https", ignoreCase = true)) {
                throw IOException("$what 必须使用 HTTPS，拒绝: $url")
            }
        } catch (e: IllegalArgumentException) {
            throw IOException("$what URL 非法: $url", e)
        }
    }

    private fun verifyHashes(info: UpdateInfo, file: Path) {
        val sha256 = info.sha256
        val sha1 = info.sha1
        if (sha256.isNotEmpty()) {
            val actual = sha256(file)
            if (!actual.equals(sha256, ignoreCase = true)) {
                throw IOException("更新文件 SHA-256 校验失败：期望 $sha256 实际 $actual")
            }
        } else if (sha1.isNotEmpty()) {
            val actual = sha1(file)
            if (!actual.equals(sha1, ignoreCase = true)) {
                throw IOException("更新文件 SHA1 校验失败")
            }
        } else {
            throw IOException("更新清单未提供 SHA-256/SHA-1，拒绝安装未校验的更新包")
        }
    }

    private fun text(o: JsonObject, key: String): String {
        return if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else ""
    }

    private fun sha1(file: Path): String = hash(file, "SHA-1")

    private fun sha256(file: Path): String = hash(file, "SHA-256")

    private fun hash(file: Path, algorithm: String): String {
        return try {
            val md = MessageDigest.getInstance(algorithm)
            Files.newInputStream(file).use { inp ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = inp.read(buf)
                    if (n == -1) break
                    md.update(buf, 0, n)
                }
            }
            val digest = md.digest()
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xff))
            sb.toString()
        } catch (e: Exception) {
            throw IOException("$algorithm 计算失败", e)
        }
    }
}
