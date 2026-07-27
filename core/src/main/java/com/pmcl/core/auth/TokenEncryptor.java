package com.pmcl.core.auth;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Token 加密器：使用基于机器标识派生的 AES-GCM 密钥加密敏感凭据。
 * <p>
 * 加密方案：
 * <ul>
 *   <li>算法：AES-256-GCM（认证加密，防篡改）</li>
 *   <li>密钥派生：PBKDF2-HMAC-SHA256(password=salt, salt=machineFingerprint)</li>
 *   <li>盐：基于用户名 + user.home + OS name 派生（同一机器稳定，跨机器不同）</li>
 *   <li>IV：每次加密随机生成 12 字节，与密文一起存储</li>
 *   <li>迭代次数：100000 次（NIST SP 800-132 推荐）</li>
 *   <li>密钥长度：256 位</li>
 * </ul>
 * <p>
 * 安全说明：
 * <ul>
 *   <li>这不是端到端加密——同机器上的恶意进程仍可能通过内存 dump 或 hook 获取明文。</li>
 *   <li>主要防护目标：防止凭据明文持久化到磁盘，被其他用户或离线取证工具读取。</li>
 *   <li>真正的安全应使用平台密钥库（macOS Keychain / Windows DPAPI / Linux Secret Service），
 *       未来可扩展此类接入平台 API。</li>
 *   <li>加密后的字符串以 {@code "enc:v1:"} 前缀标记，便于向后兼容旧明文格式。</li>
 * </ul>
 */
public final class TokenEncryptor {

    private static final String ENCRYPTED_PREFIX = "enc:v1:";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RNG = new SecureRandom();

    private TokenEncryptor() {}

    /**
     * 加密明文 token。返回 {@code "enc:v1:<base64(salt|iv|ciphertext)>"}。
     * 输入为 null 或空字符串时原样返回（避免破坏空字段语义）。
     * <p>
     * 安全保证：加密失败时返回空字符串 {@code ""}，绝不降级为明文存储。
     * 调用方（如 AuthService）应感知返回空串意味着该 token 无法持久化，
     * 用户需在下次启动时重新登录。这比明文落盘泄漏凭据安全得多。
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            byte[] salt = new byte[SALT_BYTES];
            RNG.nextBytes(salt);
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);

            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 打包：salt(16) + iv(12) + ciphertext(N) + tag(16，包含在 ciphertext 末尾)
            ByteBuffer buf = ByteBuffer.allocate(salt.length + iv.length + ciphertext.length);
            buf.put(salt).put(iv).put(ciphertext);
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            // 安全原则：加密失败绝不降级为明文存储，返回空串让上层感知持久化失败。
            // 明文落盘会导致 accounts.json 中 accessToken 持续可读，被其他用户/取证工具读取。
            System.err.println("[TokenEncryptor] 加密失败，拒绝持久化该 token（返回空串）: " + e.getMessage());
            return "";
        }
    }

    /**
     * 解密 token。输入非加密格式（无 {@code "enc:v1:"} 前缀）时原样返回，
     * 用于向后兼容旧明文 accounts.json 文件。
     */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored; // 旧明文兼容
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(ENCRYPTED_PREFIX.length()));
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            buf.get(salt);
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[TokenEncryptor] 解密失败（可能是机器标识变化）: " + e.getMessage());
            return "";
        }
    }

    /** 判断字符串是否为加密格式。 */
    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(ENCRYPTED_PREFIX);
    }

    /**
     * 基于机器标识派生密钥。同一机器（同 username + user.home + os.name）派生出相同密钥，
     * 跨机器不同，提供基础的"按用户隔离"语义。
     */
    private static SecretKey deriveKey(byte[] salt) throws Exception {
        String username = System.getProperty("user.name", "unknown");
        String home = System.getProperty("user.home", "/tmp");
        String os = System.getProperty("os.name", "unknown");
        String machineId = username + "|" + home + "|" + os;

        // 额外混入一个本地随机密钥文件（首次生成），增加跨进程熵
        String secondarySecret = loadOrCreateSecondarySecret();

        String password = machineId + "|" + secondarySecret;
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * H9: 获取辅助密钥（机器绑定），用于好友身份密钥派生。
     * 仅供 FriendIdentityManager.deriveSecret() 调用。
     */
    public static String getSecondarySecret() {
        return loadOrCreateSecondarySecret();
    }

    /**
     * 加载或创建本地辅助密钥文件（~/.pmcl/.keyfile）。
     * <p>
     * 首次运行时生成 32 字节随机数并写入文件（权限 0600），后续读取复用。
     * 该文件提供"按机器实例隔离"的额外熵——即使机器标识相同（克隆镜像），
     * 不同实例的 keyfile 不同，密钥也不同。
     */
    private static final Object KEYFILE_LOCK = new Object();

    private static String loadOrCreateSecondarySecret() {
        synchronized (KEYFILE_LOCK) {
            try {
                Path keyFile = Paths.get(System.getProperty("user.home"), ".pmcl", ".keyfile");
                if (Files.exists(keyFile)) {
                    byte[] data = Files.readAllBytes(keyFile);
                    if (data.length >= 32) {
                        return Base64.getEncoder().encodeToString(data);
                    }
                }
                // 生成新密钥（不 REPLACE 已有合法 keyfile，避免并发双写导致 token 无法解密）
                Files.createDirectories(keyFile.getParent());
                byte[] newKey = new byte[32];
                RNG.nextBytes(newKey);
                Path tmpFile = keyFile.resolveSibling(
                        keyFile.getFileName() + ".tmp." + java.util.UUID.randomUUID());
                Files.write(tmpFile, newKey);
                hardenKeyFilePermissions(tmpFile);
                try {
                    try {
                        Files.move(tmpFile, keyFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(tmpFile, keyFile);
                    }
                } catch (java.nio.file.FileAlreadyExistsException exists) {
                    // 另一线程/进程已创建：读取胜出方密钥
                    try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
                    byte[] data = Files.readAllBytes(keyFile);
                    if (data.length >= 32) {
                        return Base64.getEncoder().encodeToString(data);
                    }
                    throw new java.io.IOException("keyfile 已存在但内容无效");
                }
                hardenKeyFilePermissions(keyFile);
                return Base64.getEncoder().encodeToString(newKey);
            } catch (Exception e) {
                System.err.println("[TokenEncryptor] 辅助密钥文件创建失败: " + e.getMessage());
                return machineFallbackSecret();
            }
        }
    }

    /** POSIX 0600；Windows 用 icacls 去掉继承并仅授予当前用户完全控制 */
    private static void hardenKeyFilePermissions(Path keyFile) {
        try {
            Files.setPosixFilePermissions(keyFile,
                    java.util.Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            return;
        } catch (UnsupportedOperationException ignored) {
            // Windows / 非 POSIX
        } catch (Exception e) {
            System.err.println("[TokenEncryptor] POSIX 权限设置失败: " + e.getMessage());
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("win")) return;
        try {
            String user = System.getProperty("user.name", "");
            if (user.isEmpty()) return;
            ProcessBuilder pb = new ProcessBuilder(
                    "icacls", keyFile.toAbsolutePath().toString(),
                    "/inheritance:r",
                    "/grant:r", user + ":F");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (p.isAlive()) p.destroyForcibly();
        } catch (Exception e) {
            System.err.println("[TokenEncryptor] Windows ACL 设置失败: " + e.getMessage());
        }
    }

    /** 无 keyfile 时的机器绑定降级密钥（非全局常量） */
    private static String machineFallbackSecret() {
        try {
            String material = System.getProperty("user.home", "") + "|"
                    + System.getProperty("os.name", "") + "|"
                    + System.getProperty("user.name", "") + "|pmcl-keyfile-fallback";
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(
                    md.digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Base64.getEncoder().encodeToString(
                    (System.getProperty("user.home", "pmcl") + "-fallback")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
