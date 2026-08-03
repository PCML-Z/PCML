package com.lash.pmcl.core.auth

import android.util.Base64
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.FileUtils
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Token 加密器 — Android 版。
 *
 * 桌面版用 ~/.pmcl/.keyfile 作为辅助熵源，依赖 user.name/user.home 等系统属性。
 * Android 上这些属性不可用（user.name 恒为 "?"，user.home 指向非应用私有目录），
 * 因此改用 Android Keystore 派生 AES-256-GCM 密钥，并在 Keystore 不可用时
 * 回退到「应用私有目录 keyfile + Android ID」方案。
 *
 * 加密方案：
 * - 算法：AES-256-GCM（认证加密，防篡改）
 * - 主路径：Android Keystore（硬件支持时为 TEE/StrongBox，软件支持时为 KeyStore）
 * - 回退路径：PBKDF2-HMAC-SHA256(password=salt, salt=machineFingerprint)
 * - IV：每次加密随机生成 12 字节，与密文一起存储
 * - 迭代次数：100000 次；密钥长度：256 位
 *
 * 安全说明：
 * - 加密失败返回空字符串 {@code ""}，绝不降级为明文存储。
 * - 加密后字符串以 {@code "enc:v1:"} 前缀标记，便于向后兼容旧明文格式。
 *
 * @param paths 用于回退路径的 keyfile 存储位置（应用私有目录）
 * @param androidId 设备级 Android ID（Settings.Secure.ANDROID_ID），作为机器指纹
 * @param appDataDir 应用私有目录路径，用于 keyfile 存储
 */
class TokenEncryptor(
    private val paths: PmclPaths,
    private val androidId: String,
    private val appDataDir: Path
) {

    /**
     * 加密明文 token。返回 {@code "enc:v1:<base64(salt|iv|ciphertext)>"}。
     * 输入为 null 或空字符串时原样返回。
     * 加密失败返回空字符串，绝不降级为明文。
     */
    fun encrypt(plaintext: String?): String {
        if (plaintext.isNullOrEmpty()) return plaintext ?: ""
        return try {
            val key = loadOrCreateKey()
            val iv = ByteArray(IV_BYTES).also { RNG.nextBytes(it) }
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

            // Keystore 路径无 salt；回退路径 salt 在 deriveFallbackKey 内部处理。
            // 为保持编码格式统一，Keystore 路径填入 16 字节占位 salt。
            val salt = ByteArray(SALT_BYTES)
            val buf = ByteBuffer.allocate(salt.size + iv.size + ciphertext.size)
            buf.put(salt).put(iv).put(ciphertext)
            ENCRYPTED_PREFIX + Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        } catch (e: Exception) {
            System.err.println("[TokenEncryptor] 加密失败，拒绝持久化该 token（返回空串）: ${e.message}")
            ""
        }
    }

    /**
     * 解密 token。输入非加密格式（无前缀）时原样返回，向后兼容旧明文。
     */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return stored
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored
        return try {
            val all = Base64.decode(stored.substring(ENCRYPTED_PREFIX.length), Base64.NO_WRAP)
            val buf = ByteBuffer.wrap(all)
            val salt = ByteArray(SALT_BYTES); buf.get(salt)
            val iv = ByteArray(IV_BYTES); buf.get(iv)
            val ciphertext = ByteArray(buf.remaining()); buf.get(ciphertext)

            val key = loadOrCreateKey()
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[TokenEncryptor] 解密失败（可能是机器标识变化）: ${e.message}")
            null
        }
    }

    fun isEncrypted(s: String?): Boolean =
        s != null && s.startsWith(ENCRYPTED_PREFIX)

    /**
     * 派生用途特定密钥（如好友身份密钥派生）。
     * 返回 Base64 编码的 SHA-256 摘要。
     */
    fun derivePurposeKey(purpose: String): String {
        val material = "$purpose|$androidId"
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            Base64.encodeToString(
                md.digest(material.toByteArray(StandardCharsets.UTF_8)),
                Base64.NO_WRAP
            )
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 加载或创建主密钥。
     *
     * 优先使用 Android Keystore（API 23+），密钥不可导出，硬件支持时存储在 TEE/StrongBox。
     * Keystore 不可用时回退到 keyfile 方案。
     */
    @Synchronized
    private fun loadOrCreateKey(): SecretKey {
        // 路径 1: Android Keystore
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            // 生成新密钥并存入 Keystore
            val gen = KeyGenerator.getInstance(KEY_ALGORITHM, KEYSTORE_PROVIDER)
            gen.init(256)
            val key = gen.generateKey()
            // KeyGenerator 已自动存入 Keystore
            return key
        } catch (e: Exception) {
            System.err.println("[TokenEncryptor] Keystore 不可用，回退到 keyfile: ${e.message}")
        }
        // 路径 2: keyfile 回退
        return loadOrCreateFallbackKey()
    }

    /**
     * 回退方案：基于 Android ID + 应用私有目录 keyfile 派生密钥。
     *
     * 与桌面版策略一致：
     * - keyfile 不存在时生成 32 字节随机数并写入应用私有目录
     * - keyfile 存在时读取复用
     * - 失败时返回进程级随机密钥（每次启动不同，token 无法跨进程解密）
     */
    @Synchronized
    private fun loadOrCreateFallbackKey(): SecretKey {
        synchronized(KEYFILE_LOCK) {
            val keyFile = appDataDir.resolve("pmcl").resolve(".keyfile") // 与 PmclPaths 同目录，统一管理
            try {
                if (Files.exists(keyFile)) {
                    val data = Files.readAllBytes(keyFile)
                    if (data.size >= 32) {
                        return deriveFallbackKey(data)
                    }
                }
                Files.createDirectories(keyFile.parent)
                val newKey = ByteArray(32).also { RNG.nextBytes(it) }
                val tmpFile = keyFile.resolveSibling(
                    keyFile.fileName.toString() + ".tmp." + java.util.UUID.randomUUID()
                )
                FileUtils.writeBytes(tmpFile, newKey)
                try {
                    Files.move(tmpFile, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmpFile, keyFile, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    Files.deleteIfExists(tmpFile)
                    val data = Files.readAllBytes(keyFile)
                    if (data.size >= 32) {
                        return deriveFallbackKey(data)
                    }
                    throw java.io.IOException("keyfile 已存在但内容无效")
                }
                return deriveFallbackKey(newKey)
            } catch (e: Exception) {
                System.err.println("[TokenEncryptor] keyfile 创建失败: ${e.message}")
                val emergency = ByteArray(32).also { RNG.nextBytes(it) }
                return deriveFallbackKey(emergency)
            }
        }
    }

    /**
     * 基于 Android ID 和 keyfile 内容派生 AES-256 密钥。
     * PBKDF2-HMAC-SHA256(password=machineId, salt=keyfileBytes)
     */
    private fun deriveFallbackKey(keyfileBytes: ByteArray): SecretKey {
        // 派生时使用随机 salt（与桌面版一致：每次加密一个新 salt）
        // 但这里需要 deterministic：同一 keyfile 派生同一密钥。
        // 因此 salt 取 keyfile 前 16 字节（若不足则补零）
        val salt = ByteArray(SALT_BYTES)
        for (i in salt.indices) {
            salt[i] = if (i < keyfileBytes.size) keyfileBytes[i] else 0
        }
        val machineId = "$androidId|pmcl-android-keyfile"
        val spec = PBEKeySpec(machineId.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            val factory = javax.crypto.SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            spec.clearPassword()
            SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private const val ENCRYPTED_PREFIX = "enc:v1:"
        private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val SALT_BYTES = 16
        private const val ITERATIONS = 100_000
        private const val KEY_BITS = 256

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pmcl_token_master_key"
        private const val KEY_ALGORITHM = "AES"

        private val RNG = SecureRandom()
        private val KEYFILE_LOCK = Any()
    }
}
