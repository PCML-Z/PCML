package com.pmcl.core.identity;

import com.pmcl.core.LauncherCore;
import com.pmcl.core.runtime.RuntimeManager;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * HECT-MI（Hardware-Environment-Channel-Type Machine Identifier）唯一产品识别码生成器。
 *
 * <p>格式：19 位数字 {@code XXXXXX-XXXXXX-XXXXXX-X} + 275 位大写字母。
 *
 * <p>采用<strong>可逆编码</strong>：将 8 个因子拆分为 13 个独立值，DEFLATE 压缩后 Base-26
 * 编码到 275 位字母段。数字段存储 CRC32 校验 + 压缩/原始长度 + 编码标志。
 * 解码器可完整逆向还原所有因子数据。
 *
 * <p>当数据过大无法编码到 275 字母时，回退到 SHA-256 哈希模式（flag=1，不可解码）。
 */
public final class HectMiGenerator {

    static final int DIGIT_COUNT = 19;
    static final int LETTER_COUNT = 275;
    /** Base-26 编码最大字节数：floor(275 * log(26) / log(256)) = 161 */
    static final int MAX_COMPRESSED_BYTES = 161;
    /** 因子值数量（8 因子拆分为 13 个独立值） */
    static final int VALUE_COUNT = 13;

    private HectMiGenerator() {}

    /**
     * 根据当前启动器环境生成 HECT-MI 识别码。
     *
     * @param core 启动器内核实例
     * @return 格式为 {@code XXXXXX-XXXXXX-XXXXXX-X<275 letters>} 的识别码
     */
    public static String generate(LauncherCore core) {
        List<String> values = collectValues(core);
        String payload = String.join("\n", values);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        // 尝试 DEFLATE 压缩
        byte[] compressed = deflate(payloadBytes);

        if (compressed.length <= MAX_COMPRESSED_BYTES) {
            // 可编码模式：flag=0
            String letters = base26Encode(compressed, LETTER_COUNT);
            long crc = crc32(payloadBytes);
            String digits = String.format("%06d%06d%06d0",
                    crc % 1000000,
                    compressed.length % 1000000,
                    payloadBytes.length % 1000000);
            return formatCode(digits, letters);
        } else {
            // 回退模式：SHA-256 哈希，flag=1（不可解码）
            byte[] hash = sha256(payload);
            String letters = base26Encode(hash, LETTER_COUNT);
            String digits = String.format("%06d%06d%06d1",
                    crc32(payloadBytes) % 1000000, 0, 0);
            return formatCode(digits, letters);
        }
    }

    // ===== 因子收集 =====

    /**
     * 收集 13 个独立因子值（8 因子拆分），顺序固定，用于编码和解码。
     *
     * @return 有序值列表：[version, cpuName, cpuCores, totalMemory, osName, osArch,
     *         javaVersion, jvmName, jvmVersion, installDate, channel, buildSign, location]
     */
    static List<String> collectValues(LauncherCore core) {
        List<String> values = new ArrayList<>(VALUE_COUNT);

        // 1. 启动器版本
        values.add(safe(core.launcherVersion()));

        // 2-4. 设备：CPU 型号、核心数、总内存
        try {
            RuntimeManager rt = core.runtime();
            values.add(safe(rt.getCpuName()));
            values.add(String.valueOf(rt.getCpuLogicalCores()));
            values.add(String.valueOf(rt.getTotalMemoryMb()));
        } catch (Throwable t) {
            values.add("unknown");
            values.add("0");
            values.add("0");
        }

        // 5-6. 系统：OS 名称、架构
        try {
            values.add(safe(core.runtime().getOsName()));
        } catch (Throwable t) {
            values.add(safe(System.getProperty("os.name", "?")));
        }
        values.add(safe(System.getProperty("os.arch", "?")));

        // 7-9. 内核：Java 版本、JVM 名称、JVM 版本
        values.add(safe(System.getProperty("java.version", "?")));
        values.add(safe(System.getProperty("java.vm.name", "?")));
        values.add(safe(System.getProperty("java.vm.version", "?")));

        // 10. 安装日期：preferences.json 文件创建时间
        String installDate = "0";
        try {
            Path prefs = core.getConfig().getWorkDir().resolve("preferences.json");
            if (Files.exists(prefs)) {
                BasicFileAttributes attrs = Files.readAttributes(prefs, BasicFileAttributes.class);
                long ts = attrs.creationTime().toMillis();
                if (ts <= 0) ts = attrs.lastModifiedTime().toMillis();
                installDate = String.valueOf(ts);
            }
        } catch (Throwable ignored) {}
        values.add(installDate);

        // 11. 安装渠道
        values.add(resolveInstallChannel());

        // 12. 构建签名（缩短为 SHA-256 前 16 hex 字符，节省空间）
        values.add(sha256Hex(resolveBuildSignature()).substring(0, 16));

        // 13. 存放位置
        values.add(safe(core.getConfig().getWorkDir().toString()));

        return values;
    }

    /** 因子标签（中英文），与 collectValues 顺序对应 */
    static final String[][] FACTOR_LABELS = {
            {"1", "启动器版本", "Launcher Version"},
            {"2", "CPU 型号", "CPU Model"},
            {"3", "CPU 核心数", "CPU Cores"},
            {"4", "总内存 (MB)", "Total Memory (MB)"},
            {"5", "操作系统", "OS Name"},
            {"6", "系统架构", "OS Architecture"},
            {"7", "Java 版本", "Java Version"},
            {"8", "JVM 名称", "JVM Name"},
            {"9", "JVM 版本", "JVM Version"},
            {"10", "安装日期", "Install Date"},
            {"11", "安装渠道", "Install Channel"},
            {"12", "构建签名", "Build Signature"},
            {"13", "存放位置", "Storage Location"},
    };

    // ===== 编码/解码核心 =====

    /** DEFLATE 压缩（raw 模式，无 ZLIB 头） */
    static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[data.length * 2 + 64];
        int len = deflater.deflate(buffer);
        deflater.end();
        byte[] result = new byte[len];
        System.arraycopy(buffer, 0, result, 0, len);
        return result;
    }

    /** DEFLATE 解压（raw 模式） */
    static byte[] inflate(byte[] data, int expectedLength) {
        try {
            Inflater inflater = new Inflater(true);
            inflater.setInput(data);
            byte[] buffer = new byte[expectedLength * 2 + 256];
            int len = inflater.inflate(buffer);
            inflater.end();
            byte[] result = new byte[len];
            System.arraycopy(buffer, 0, result, 0, len);
            return result;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * Base-26 编码：将字节数组编码为指定长度的大写字母字符串。
     * 前置 0x01 字节以保留前导零。
     */
    static String base26Encode(byte[] data, int length) {
        // 前置 0x01 以保留前导零
        byte[] prefixed = new byte[data.length + 1];
        prefixed[0] = 1;
        System.arraycopy(data, 0, prefixed, 1, data.length);

        BigInteger num = new BigInteger(1, prefixed);
        BigInteger base = BigInteger.valueOf(26);
        StringBuilder sb = new StringBuilder();
        while (num.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = num.divideAndRemainder(base);
            sb.insert(0, (char) ('A' + divRem[1].intValue()));
            num = divRem[0];
        }
        // 前面填充 'A'（=0）到指定长度
        while (sb.length() < length) {
            sb.insert(0, 'A');
        }
        return sb.substring(0, length);
    }

    /**
     * Base-26 解码：将大写字母字符串还原为字节数组。
     * 返回原始数据（去除 0x01 前缀），截断/填充到 expectedLength。
     */
    static byte[] base26Decode(String str, int expectedLength) {
        BigInteger num = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(26);
        for (int i = 0; i < str.length(); i++) {
            num = num.multiply(base).add(BigInteger.valueOf(str.charAt(i) - 'A'));
        }
        byte[] bytes = num.toByteArray();
        // 去除 BigInteger 可能添加的符号字节
        if (bytes.length > 0 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        // 去除编码时添加的 0x01 前缀
        if (bytes.length > 0 && bytes[0] == 1) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        // 前面填充零到期望长度
        if (bytes.length < expectedLength) {
            byte[] padded = new byte[expectedLength];
            System.arraycopy(bytes, 0, padded, expectedLength - bytes.length, bytes.length);
            bytes = padded;
        } else if (bytes.length > expectedLength) {
            bytes = java.util.Arrays.copyOfRange(bytes, bytes.length - expectedLength, bytes.length);
        }
        return bytes;
    }

    /** CRC32 校验 */
    static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /** SHA-256 哈希 */
    static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[32];
        }
    }

    /** SHA-256 十六进制字符串 */
    static String sha256Hex(String input) {
        byte[] hash = sha256(input);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ===== 格式化 =====

    /** 将 19 位数字字符串 + 275 字母字符串格式化为最终识别码 */
    static String formatCode(String digits, String letters) {
        StringBuilder sb = new StringBuilder(DIGIT_COUNT + 3 + LETTER_COUNT);
        for (int i = 0; i < DIGIT_COUNT; i++) {
            sb.append(digits.charAt(i));
            if (i == 5 || i == 11 || i == 17) {
                sb.append('-');
            }
        }
        sb.append(letters);
        return sb.toString();
    }

    // ===== 辅助方法 =====

    /** 安装渠道：从 JAR 运行 = OFFICIAL，从 IDE/类目录 = DEV */
    private static String resolveInstallChannel() {
        try {
            URL source = LauncherCore.class.getProtectionDomain().getCodeSource().getLocation();
            if (source != null && source.toString().endsWith(".jar")) {
                return "OFFICIAL";
            }
        } catch (Throwable ignored) {}
        return "DEV";
    }

    /** 构建签名：code source 路径 + Manifest 信息 */
    private static String resolveBuildSignature() {
        StringBuilder sb = new StringBuilder();
        try {
            URL source = LauncherCore.class.getProtectionDomain().getCodeSource().getLocation();
            if (source != null) {
                sb.append(source.toString());
            }
            if (source != null && source.toString().endsWith(".jar")) {
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(source.getPath())) {
                    java.util.jar.Manifest manifest = jar.getManifest();
                    if (manifest != null) {
                        java.util.jar.Attributes attrs = manifest.getMainAttributes();
                        String vendor = attrs.getValue(java.util.jar.Attributes.Name.IMPLEMENTATION_VENDOR);
                        String title = attrs.getValue(java.util.jar.Attributes.Name.IMPLEMENTATION_TITLE);
                        if (vendor != null) sb.append("::").append(vendor);
                        if (title != null) sb.append("::").append(title);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        if (sb.length() == 0) sb.append("unsigned");
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
