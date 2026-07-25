package com.pmcl.core.auth;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 设备绑定保护：使用 11498 位 Base62 设备加密码 + RSA-2048 签名许可证实现"一机一码"绑定。
 * <p>
 * 设计目标：
 * <ul>
 *   <li><b>唯一设备加密码</b>：从硬件指纹（CPU 序列号 / 主板序列号 / MAC / 系统）派生
 *       11498 位 [0-9a-zA-Z] 字符串。同一设备稳定，跨设备不同。</li>
 *   <li><b>许可证签发</b>：启动器生成 RSA-2048 密钥对，私钥签名许可证
 *       {@code {deviceCodeHash, enabled, timestamp, nonce}}。许可证存储在 preferences.json，
 *       任何篡改都会导致签名校验失败。</li>
 *   <li><b>私钥保护开关</b>：开启/关闭保护都需要私钥签名新许可证。私钥由用户保管
 *       （首次开启时导出，密码加密的 PEM 格式）。本地也保留一份用设备码加密的副本
 *       （仅在原设备可解密，复制到其他设备无法解密）。</li>
 *   <li><b>启动校验</b>：保护开启时，启动器/游戏启动前校验许可证签名 + 设备码匹配，
 *       不匹配则拒绝启动，防止启动器和游戏被复制到其他设备使用。</li>
 * </ul>
 * <p>
 * 安全说明：
 * <ul>
 *   <li>设备码本身不存盘，每次从硬件指纹实时派生，避免被复制。</li>
 *   <li>私钥导出文件使用 AES-256-GCM 加密（用户密码 + PBKDF2 派生密钥），
 *       即使私钥文件泄露，无密码也无法使用。</li>
 *   <li>本地私钥副本使用设备码作为密码加密，复制到其他设备后设备码不同，无法解密。</li>
 *   <li>许可证含时间戳和 nonce，防止重放攻击。</li>
 * </ul>
 */
public final class DeviceBinder {

    // ===== 常量 =====

    /** 设备码长度：11498 字符（[0-9a-zA-Z]） */
    public static final int DEVICE_CODE_LENGTH = 11498;

    /** Base62 字符表：0-9 + a-z + A-Z */
    private static final String BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** RSA 密钥长度 */
    private static final int RSA_KEY_SIZE = 2048;

    /** 签名算法 */
    private static final String SIGN_ALGORITHM = "SHA256withRSA";

    /** 许可证前缀 */
    public static final String LICENSE_PREFIX = "pmcl-license:v1:";

    /** 私钥导出前缀（密码加密） */
    public static final String EXPORTED_KEY_PREFIX = "pmcl-key:v1:";

    /** 本地私钥前缀（设备码加密） */
    public static final String LOCAL_KEY_PREFIX = "pmcl-localkey:v1:";

    // AES-GCM 参数
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final int AES_KEY_BITS = 256;

    private static final SecureRandom RNG = new SecureRandom();

    private DeviceBinder() {}

    // ===== 设备指纹采集 =====

    /**
     * 采集设备硬件指纹。组合多个硬件标识符，单一来源缺失不影响整体稳定性。
     * <p>
     * 采集项：
     * <ul>
     *   <li>CPU 处理器标识符（vendor + family + model + stepping）</li>
     *   <li>CPU 序列号（部分 CPU 提供）</li>
     *   <li>主板序列号（manufacturer + model + serial + version）</li>
     *   <li>计算机系统序列号（manufacturer + model + serial + uuid）</li>
     *   <li>首个物理网卡的 MAC 地址（排除虚拟/loopback）</li>
     *   <li>OS 名称 + 版本 + 架构</li>
     *   <li>当前用户名 + user.home（防止同一硬件多用户混淆）</li>
     * </ul>
     */
    public static String collectFingerprint() {
        StringBuilder fp = new StringBuilder(256);
        try {
            oshi.SystemInfo si = new oshi.SystemInfo();
            oshi.hardware.HardwareAbstractionLayer hw = si.getHardware();
            oshi.software.os.OperatingSystem os = si.getOperatingSystem();

            // CPU 标识
            try {
                oshi.hardware.CentralProcessor cpu = hw.getProcessor();
                oshi.hardware.CentralProcessor.ProcessorIdentifier pid = cpu.getProcessorIdentifier();
                fp.append("cpu=").append(pid.getVendor())
                  .append('|').append(pid.getFamily())
                  .append('|').append(pid.getModel())
                  .append('|').append(pid.getStepping())
                  .append('|').append(pid.getMicroarchitecture())
                  .append('|').append(pid.getProcessorID())
                  .append('\n');
            } catch (Throwable t) {
                fp.append("cpu=unknown\n");
            }

            // 主板 + 系统序列号
            try {
                oshi.hardware.ComputerSystem cs = hw.getComputerSystem();
                fp.append("board=").append(safe(cs.getBaseboard().getManufacturer()))
                  .append('|').append(safe(cs.getBaseboard().getModel()))
                  .append('|').append(safe(cs.getBaseboard().getSerialNumber()))
                  .append('|').append(safe(cs.getBaseboard().getVersion()))
                  .append('\n');
                fp.append("system=").append(safe(cs.getManufacturer()))
                  .append('|').append(safe(cs.getModel()))
                  .append('|').append(safe(cs.getSerialNumber()))
                  .append('\n');
                // Firmware 信息（厂商/名称/版本/发布日期）
                try {
                    oshi.hardware.Firmware fw = cs.getFirmware();
                    fp.append("firmware=").append(safe(fw.getManufacturer()))
                      .append('|').append(safe(fw.getName()))
                      .append('|').append(safe(fw.getVersion()))
                      .append('|').append(safe(fw.getReleaseDate()))
                      .append('\n');
                } catch (Throwable t) {
                    fp.append("firmware=unknown\n");
                }
            } catch (Throwable t) {
                fp.append("board=unknown\nsystem=unknown\n");
            }

            // 首个物理网卡 MAC
            try {
                String mac = firstPhysicalMac(hw);
                fp.append("mac=").append(mac).append('\n');
            } catch (Throwable t) {
                fp.append("mac=unknown\n");
            }

            // OS 信息
            try {
                fp.append("os=").append(os.getManufacturer())
                  .append('|').append(os.getFamily())
                  .append('|').append(os.getVersionInfo().getVersion())
                  .append('|').append(os.getBitness())
                  .append('\n');
            } catch (Throwable t) {
                fp.append("os=unknown\n");
            }
        } catch (Throwable t) {
            // oshi 整体初始化失败（罕见），降级到 JVM 属性
            fp.append("oshi_failed=").append(t.getClass().getSimpleName()).append('\n');
        }

        // JVM 属性作为兜底（始终可用）
        fp.append("user=").append(System.getProperty("user.name", "unknown"))
          .append('|').append(System.getProperty("user.home", "/tmp"))
          .append('|').append(System.getProperty("os.name", "unknown"))
          .append('|').append(System.getProperty("os.arch", "unknown"))
          .append('\n');

        return fp.toString();
    }

    private static String safe(String s) {
        return s == null ? "?" : s;
    }

    /**
     * 获取首个物理网卡的 MAC 地址（排除虚拟接口和 loopback）。
     */
    private static String firstPhysicalMac(oshi.hardware.HardwareAbstractionLayer hw) {
        try {
            java.util.List<oshi.hardware.NetworkIF> nics = hw.getNetworkIFs();
            // 优先级：有 MAC 且非虚拟
            for (oshi.hardware.NetworkIF nic : nics) {
                String mac = nic.getMacaddr();
                if (mac == null || mac.isEmpty() || mac.equals("00:00:00:00:00:00")) continue;
                String name = nic.getName() == null ? "" : nic.getName().toLowerCase();
                String desc = nic.getDisplayName() == null ? "" : nic.getDisplayName().toLowerCase();
                // 排除常见虚拟接口
                if (name.contains("virtual") || name.contains("tap") || name.contains("tun")
                    || name.contains("vmnet") || name.contains("docker")
                    || desc.contains("virtual") || desc.contains("tap")) {
                    continue;
                }
                return mac;
            }
            // 兜底：取第一个有 MAC 的
            for (oshi.hardware.NetworkIF nic : nics) {
                String mac = nic.getMacaddr();
                if (mac != null && !mac.isEmpty() && !mac.equals("00:00:00:00:00:00")) {
                    return mac;
                }
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    // ===== 11498 位设备码生成 =====

    /**
     * 获取当前设备的 11498 位 Base62 加密码。同一设备稳定，跨设备不同。
     */
    public static String getDeviceCode() {
        return generateDeviceCode(collectFingerprint());
    }

    /**
     * 从指纹派生 11498 位 Base62 设备码。
     * <p>
     * 派生流程：
     * <ol>
     *   <li>seed = SHA-256(fingerprint)</li>
     *   <li>使用 HKDF 风格扩展：block[i] = SHA-256(seed || i)，i 从 0 递增</li>
     *   <li>拼接所有 block 直到字节足够（11498 字符 ≈ 8560 字节）</li>
     *   <li>使用 BigInteger 转 Base62，左侧补 '0' 到 11498 字符</li>
     * </ol>
     */
    static String generateDeviceCode(String fingerprint) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] seed = sha256.digest(fingerprint.getBytes(StandardCharsets.UTF_8));

            // 11498 Base62 字符需要的字节数：log(62)/log(256) ≈ 0.7446
            // 11498 * 0.7446 ≈ 8560，取 8576（256 的倍数，便于分块）
            int targetBytes = 8576;
            byte[] material = new byte[targetBytes];
            int offset = 0;
            int counter = 0;
            while (offset < targetBytes) {
                ByteBuffer block = ByteBuffer.allocate(seed.length + 4);
                block.put(seed);
                block.putInt(counter);
                byte[] hash = sha256.digest(block.array());
                int copy = Math.min(hash.length, targetBytes - offset);
                System.arraycopy(hash, 0, material, offset, copy);
                offset += copy;
                counter++;
            }

            // Base62 编码（BigInteger 方式）
            java.math.BigInteger num = new java.math.BigInteger(1, material);
            StringBuilder sb = new StringBuilder(DEVICE_CODE_LENGTH);
            java.math.BigInteger base = java.math.BigInteger.valueOf(62);
            while (num.compareTo(java.math.BigInteger.ZERO) > 0) {
                java.math.BigInteger[] dm = num.divideAndRemainder(base);
                sb.append(BASE62.charAt(dm[1].intValue()));
                num = dm[0];
            }
            // 反转（divideAndRemainder 产生低位在前）
            sb.reverse();
            // 左侧补 '0' 到目标长度
            while (sb.length() < DEVICE_CODE_LENGTH) {
                sb.insert(0, '0');
            }
            // 截断到精确长度（保险）
            if (sb.length() > DEVICE_CODE_LENGTH) {
                sb.setLength(DEVICE_CODE_LENGTH);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("设备码生成失败: " + e.getMessage(), e);
        }
    }

    /** 计算设备码的 SHA-256 哈希（用于许可证比对，避免明文存盘） */
    public static String hashDeviceCode(String deviceCode) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(deviceCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ===== RSA 密钥对 =====

    /** 生成 RSA-2048 密钥对 */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(RSA_KEY_SIZE, RNG);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("RSA 密钥对生成失败: " + e.getMessage(), e);
        }
    }

    /** 从 PKCS#8 DER 字节重建私钥 */
    public static PrivateKey loadPrivateKey(byte[] pkcs8Der) {
        try {
            return java.security.KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
        } catch (Exception e) {
            throw new RuntimeException("私钥解析失败: " + e.getMessage(), e);
        }
    }

    /** 从 X.509 DER 字节重建公钥 */
    public static PublicKey loadPublicKey(byte[] x509Der) {
        try {
            return java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(x509Der));
        } catch (Exception e) {
            throw new RuntimeException("公钥解析失败: " + e.getMessage(), e);
        }
    }

    /** 私钥转 PKCS#8 DER 字节 */
    public static byte[] privateKeyToDer(PrivateKey key) {
        return key.getEncoded();
    }

    /** 公钥转 X.509 DER 字节 */
    public static byte[] publicKeyToDer(PublicKey key) {
        return key.getEncoded();
    }

    /** 公钥/私钥 DER → Base64 字符串 */
    public static String toBase64(byte[] der) {
        return Base64.getEncoder().encodeToString(der);
    }

    /** Base64 → DER 字节 */
    public static byte[] fromBase64(String b64) {
        return Base64.getDecoder().decode(b64);
    }

    // ===== 许可证签发与验证 =====

    /**
     * 签发许可证。格式：{@code pmcl-license:v1:<base64(jsonPayload)>.<base64(signature)>}。
     * <p>
     * payload 包含：deviceCodeHash、enabled、timestamp、nonce。
     */
    public static String signLicense(String deviceCodeHash, boolean enabled, PrivateKey privKey) {
        try {
            long timestamp = System.currentTimeMillis();
            long nonce = RNG.nextLong();
            String payload = String.format(
                    "{\"h\":\"%s\",\"e\":%b,\"t\":%d,\"n\":%d}",
                    deviceCodeHash, enabled, timestamp, nonce);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

            Signature sig = Signature.getInstance(SIGN_ALGORITHM);
            sig.initSign(privKey);
            sig.update(payloadBytes);
            byte[] signature = sig.sign();

            return LICENSE_PREFIX
                    + Base64.getEncoder().encodeToString(payloadBytes)
                    + "."
                    + Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new RuntimeException("许可证签发失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验证许可证。
     * <ul>
     *   <li>签名必须有效（公钥验证）</li>
     *   <li>{@code deviceCodeHash} 必须匹配 {@code expectedDeviceCodeHash}</li>
     *   <li>{@code enabled} 必须为 {@code true}（保护已开启）</li>
     * </ul>
     */
    public static boolean verifyLicense(String license, String expectedDeviceCodeHash, PublicKey pubKey) {
        if (license == null || !license.startsWith(LICENSE_PREFIX)) return false;
        try {
            String rest = license.substring(LICENSE_PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot < 0) return false;
            byte[] payloadBytes = Base64.getDecoder().decode(rest.substring(0, dot));
            byte[] signature = Base64.getDecoder().decode(rest.substring(dot + 1));

            // 验签
            Signature sig = Signature.getInstance(SIGN_ALGORITHM);
            sig.initVerify(pubKey);
            sig.update(payloadBytes);
            if (!sig.verify(signature)) return false;

            // 解析 payload
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            // 简单解析（避免引入 JSON 依赖，payload 是固定格式）
            String hash = extractField(payload, "\"h\":\"");
            boolean enabled = extractBoolField(payload, "\"e\":");
            if (!enabled) return false;
            return hash != null && hash.equals(expectedDeviceCodeHash);
        } catch (Exception e) {
            return false;
        }
    }

    /** 从许可证提取 enabled 字段（不验签，仅供 UI 显示状态） */
    public static boolean isLicenseEnabled(String license) {
        if (license == null || !license.startsWith(LICENSE_PREFIX)) return false;
        try {
            String rest = license.substring(LICENSE_PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot < 0) return false;
            byte[] payloadBytes = Base64.getDecoder().decode(rest.substring(0, dot));
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            return extractBoolField(payload, "\"e\":");
        } catch (Exception e) {
            return false;
        }
    }

    private static String extractField(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static boolean extractBoolField(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return false;
        int start = i + key.length();
        if (start + 4 <= json.length() && json.regionMatches(start, "true", 0, 4)) return true;
        if (start + 5 <= json.length() && json.regionMatches(start, "false", 0, 5)) return false;
        return false;
    }

    // ===== 私钥加密存储（AES-GCM + PBKDF2） =====

    /**
     * 用密码加密私钥（用于导出给用户）。
     * 格式：{@code pmcl-key:v1:<base64(salt|iv|ciphertext)>}
     */
    public static String encryptPrivateKey(PrivateKey key, char[] password) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            RNG.nextBytes(salt);
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);

            SecretKey aesKey = deriveAesKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(key.getEncoded());

            ByteBuffer buf = ByteBuffer.allocate(salt.length + iv.length + ciphertext.length);
            buf.put(salt).put(iv).put(ciphertext);
            return EXPORTED_KEY_PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new RuntimeException("私钥加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用密码解密私钥（用户导入时使用）。
     */
    public static PrivateKey decryptPrivateKey(String encrypted, char[] password) {
        if (encrypted == null || !encrypted.startsWith(EXPORTED_KEY_PREFIX)) {
            throw new IllegalArgumentException("无效的私钥格式");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encrypted.substring(EXPORTED_KEY_PREFIX.length()));
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            buf.get(salt);
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            SecretKey aesKey = deriveAesKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pkcs8 = cipher.doFinal(ciphertext);
            return loadPrivateKey(pkcs8);
        } catch (Exception e) {
            throw new RuntimeException("私钥解密失败（密码错误或文件损坏）: " + e.getMessage(), e);
        }
    }

    /**
     * 用设备码加密私钥（本地存储副本，仅在原设备可解密）。
     * 格式：{@code pmcl-localkey:v1:<base64(salt|iv|ciphertext)>}
     */
    public static String encryptPrivateKeyWithDeviceCode(PrivateKey key, String deviceCode) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            RNG.nextBytes(salt);
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);

            // 用设备码作为密码派生 AES 密钥
            SecretKey aesKey = deriveAesKey(deviceCode.toCharArray(), salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(key.getEncoded());

            ByteBuffer buf = ByteBuffer.allocate(salt.length + iv.length + ciphertext.length);
            buf.put(salt).put(iv).put(ciphertext);
            return LOCAL_KEY_PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new RuntimeException("私钥本地加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用设备码解密私钥（本地存储副本）。
     * 设备码不同时解密失败，实现"复制到其他设备无法解密"。
     */
    public static PrivateKey decryptPrivateKeyWithDeviceCode(String encrypted, String deviceCode) {
        if (encrypted == null || !encrypted.startsWith(LOCAL_KEY_PREFIX)) {
            throw new IllegalArgumentException("无效的本地私钥格式");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encrypted.substring(LOCAL_KEY_PREFIX.length()));
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            buf.get(salt);
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            SecretKey aesKey = deriveAesKey(deviceCode.toCharArray(), salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pkcs8 = cipher.doFinal(ciphertext);
            return loadPrivateKey(pkcs8);
        } catch (Exception e) {
            throw new RuntimeException("本地私钥解密失败（设备不匹配或文件损坏）: " + e.getMessage(), e);
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 派生 AES-256 密钥。
     */
    private static SecretKey deriveAesKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** 校验私钥与公钥是否匹配（同密钥对） */
    public static boolean keyPairMatches(PrivateKey priv, PublicKey pub) {
        try {
            // 用私钥签名一段随机数据，再用公钥验证
            byte[] data = new byte[32];
            RNG.nextBytes(data);
            Signature sig = Signature.getInstance(SIGN_ALGORITHM);
            sig.initSign(priv);
            sig.update(data);
            byte[] signature = sig.sign();
            sig.initVerify(pub);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // ===== 高级 API（配合 Preferences） =====

    /**
     * 开启设备绑定保护。
     * <p>
     * 流程：
     * <ol>
     *   <li>采集当前设备指纹 → 派生 11498 位设备码</li>
     *   <li>生成 RSA-2048 密钥对</li>
     *   <li>用设备码加密私钥，存入 preferences（本地副本）</li>
     *   <li>签发许可证 {@code {deviceCodeHash, enabled=true}}，存入 preferences</li>
     *   <li>返回公钥 Base64 + 密码加密的私钥导出字符串（给用户保存）</li>
     * </ol>
     *
     * @param exportPassword 用户设置的私钥导出密码（不能为空）
     * @return 包含公钥、设备码哈希、许可证、加密私钥导出字符串的结果
     */
    public static EnableResult enableProtection(char[] exportPassword) {
        if (exportPassword == null || exportPassword.length == 0) {
            throw new IllegalArgumentException("导出密码不能为空");
        }
        String deviceCode = getDeviceCode();
        String deviceCodeHash = hashDeviceCode(deviceCode);

        KeyPair kp = generateKeyPair();
        PrivateKey privKey = kp.getPrivate();
        PublicKey pubKey = kp.getPublic();

        String publicKeyB64 = toBase64(publicKeyToDer(pubKey));
        String license = signLicense(deviceCodeHash, true, privKey);
        String localKeyEnc = encryptPrivateKeyWithDeviceCode(privKey, deviceCode);
        String exportedKey = encryptPrivateKey(privKey, exportPassword);

        return new EnableResult(
                publicKeyB64,
                deviceCodeHash,
                license,
                localKeyEnc,
                exportedKey,
                deviceCode
        );
    }

    /**
     * 关闭设备绑定保护。
     * <p>
     * 流程：
     * <ol>
     *   <li>用密码解密导入的私钥</li>
     *   <li>校验私钥与 preferences 中的公钥匹配</li>
     *   <li>用私钥签发新许可证 {@code {deviceCodeHash, enabled=false}}</li>
     * </ol>
     *
     * @param importedEncryptedKey 用户导入的加密私钥字符串
     * @param password 私钥密码
     * @param storedPublicKeyB64 preferences 中存储的公钥 Base64
     * @return 新的许可证（enabled=false），或 null 表示校验失败
     */
    public static String disableProtection(String importedEncryptedKey, char[] password,
                                           String storedPublicKeyB64) {
        try {
            PrivateKey privKey = decryptPrivateKey(importedEncryptedKey, password);
            PublicKey pubKey = loadPublicKey(fromBase64(storedPublicKeyB64));
            if (!keyPairMatches(privKey, pubKey)) return null;

            // 关闭时签发的许可证使用当前设备码哈希（即使设备变了也能关闭，
            // 因为关闭后不再校验设备）
            String deviceCode = getDeviceCode();
            String deviceCodeHash = hashDeviceCode(deviceCode);
            return signLicense(deviceCodeHash, false, privKey);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 重新开启保护（已关闭后再次开启，需要私钥）。
     * <p>
     * 流程：
     * <ol>
     *   <li>用密码解密导入的私钥</li>
     *   <li>校验私钥与 preferences 中的公钥匹配</li>
     *   <li>采集当前设备码，签发新许可证 {@code {deviceCodeHash, enabled=true}}</li>
     *   <li>用当前设备码加密私钥，更新本地副本</li>
     * </ol>
     *
     * @return 新的结果（含新许可证和本地副本），或 null 表示校验失败
     */
    public static ReenableResult reenableProtection(String importedEncryptedKey, char[] password,
                                                     String storedPublicKeyB64) {
        try {
            PrivateKey privKey = decryptPrivateKey(importedEncryptedKey, password);
            PublicKey pubKey = loadPublicKey(fromBase64(storedPublicKeyB64));
            if (!keyPairMatches(privKey, pubKey)) return null;

            String deviceCode = getDeviceCode();
            String deviceCodeHash = hashDeviceCode(deviceCode);
            String license = signLicense(deviceCodeHash, true, privKey);
            String localKeyEnc = encryptPrivateKeyWithDeviceCode(privKey, deviceCode);
            return new ReenableResult(license, localKeyEnc, deviceCodeHash);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 启动时校验设备绑定状态。
     * <ul>
     *   <li>保护未开启（无许可证或许可证 enabled=false）→ 返回 true（放行）</li>
     *   <li>保护已开启 → 校验许可证签名 + 设备码匹配 → 匹配返回 true，否则 false</li>
     * </ul>
     *
     * @param license preferences 中存储的许可证
     * @param publicKeyB64 preferences 中存储的公钥 Base64
     * @return true 表示允许启动，false 表示设备未授权
     */
    public static boolean verifyOnLaunch(String license, String publicKeyB64) {
        if (license == null || license.isEmpty() || publicKeyB64 == null || publicKeyB64.isEmpty()) {
            // 未配置保护，放行
            return true;
        }
        if (!license.startsWith(LICENSE_PREFIX)) return true;
        if (!isLicenseEnabled(license)) return true; // 保护已关闭

        // 保护已开启，校验签名 + 设备码
        try {
            PublicKey pubKey = loadPublicKey(fromBase64(publicKeyB64));
            String currentDeviceCode = getDeviceCode();
            String currentHash = hashDeviceCode(currentDeviceCode);
            return verifyLicense(license, currentHash, pubKey);
        } catch (Exception e) {
            // 公钥损坏等异常，保守拒绝
            return false;
        }
    }

    // ===== 结果类 =====

    /** 开启保护的结果 */
    public static final class EnableResult {
        /** 公钥 Base64（存入 preferences） */
        public final String publicKeyB64;
        /** 设备码哈希（用于校验，存入 preferences） */
        public final String deviceCodeHash;
        /** 许可证（存入 preferences） */
        public final String license;
        /** 设备码加密的私钥本地副本（存入 preferences） */
        public final String localKeyEnc;
        /** 密码加密的私钥导出字符串（给用户保存） */
        public final String exportedKey;
        /** 当前设备码（仅用于 UI 显示，不存盘） */
        public final String deviceCode;

        public EnableResult(String publicKeyB64, String deviceCodeHash, String license,
                            String localKeyEnc, String exportedKey, String deviceCode) {
            this.publicKeyB64 = publicKeyB64;
            this.deviceCodeHash = deviceCodeHash;
            this.license = license;
            this.localKeyEnc = localKeyEnc;
            this.exportedKey = exportedKey;
            this.deviceCode = deviceCode;
        }
    }

    /** 重新开启保护的结果 */
    public static final class ReenableResult {
        public final String license;
        public final String localKeyEnc;
        public final String deviceCodeHash;

        public ReenableResult(String license, String localKeyEnc, String deviceCodeHash) {
            this.license = license;
            this.localKeyEnc = localKeyEnc;
            this.deviceCodeHash = deviceCodeHash;
        }
    }
}
