package com.lash.pmcl.core.update

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Ed25519 verification for PMCL self-update manifests.
 *
 * Android 版本：
 * - 公钥通过构造函数传入（Android 端从 res/raw 或 assets 加载，不使用 ClassLoader.getResourceAsStream）
 * - Ed25519 在 Android API 33+ 可用；低于 33 时 [verifyOrThrow] 会抛出 IOException（fail-closed）
 * - InputStream.readAllBytes() 替换为手动读取（Java 11 API，Android 不可用）
 *
 * Canonical signed payload (UTF-8):
 * ```
 * PMCL-UPDATE-V1
 * {version}
 * {url}
 * {sha256}
 * {sha1}
 * {size}
 * ```
 * (trailing newline after size; empty sha fields allowed as empty lines)
 */
class UpdateSignatureVerifier(
    /** Base64-encoded X.509 SubjectPublicKeyInfo (SPKI) for Ed25519; null = 验签禁用 */
    privateKeyB64: String?
) {
    private val publicKey: PublicKey? = try {
        if (privateKeyB64.isNullOrBlank()) {
            null
        } else {
            val der = Base64.getDecoder().decode(privateKeyB64.trim())
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
        }
    } catch (e: Exception) {
        System.err.println("[SelfUpdater] 无法加载更新验签公钥: ${e.message}")
        null
    }

    fun canonicalPayload(
        version: String?, url: String?, sha256: String?, sha1: String?, size: Long
    ): String {
        return PAYLOAD_PREFIX + '\n' +
            nullToEmpty(version) + '\n' +
            nullToEmpty(url) + '\n' +
            nullToEmpty(sha256) + '\n' +
            nullToEmpty(sha1) + '\n' +
            size + '\n'
    }

    /**
     * @param signatureB64 Base64 (standard) Ed25519 signature over [canonicalPayload]
     */
    @Throws(IOException::class)
    fun verifyOrThrow(
        version: String?, url: String?, sha256: String?, sha1: String?, size: Long,
        signatureB64: String?
    ) {
        if (publicKey == null) {
            throw IOException("更新验签公钥未配置，拒绝未验签更新")
        }
        if (signatureB64.isNullOrBlank()) {
            throw IOException("更新缺少 signature，拒绝未签名更新")
        }
        val sig: ByteArray
        try {
            sig = Base64.getDecoder().decode(signatureB64.trim())
        } catch (e: IllegalArgumentException) {
            throw IOException("更新签名 Base64 无效", e)
        }
        val payload = canonicalPayload(version, url, sha256, sha1, size)
            .toByteArray(StandardCharsets.UTF_8)
        try {
            val s = Signature.getInstance("Ed25519")
            s.initVerify(publicKey)
            s.update(payload)
            if (!s.verify(sig)) {
                throw IOException("更新清单 Ed25519 签名校验失败")
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("更新签名校验异常: ${e.message}", e)
        }
    }

    companion object {
        const val PAYLOAD_PREFIX = "PMCL-UPDATE-V1"

        private fun nullToEmpty(s: String?): String = s ?: ""
    }
}
