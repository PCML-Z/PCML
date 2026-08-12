package com.pmcl.core.identity;

import com.pmcl.core.LauncherCore;
import com.pmcl.core.runtime.RuntimeManager;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * HECT-MI（Hardware-Environment-Channel-Type Machine Identifier）唯一产品识别码生成器。
 *
 * <p>格式：19 位数字 {@code XXXXXX-XXXXXX-XXXXXX-X} + 275 位大写字母，共 294 个编码字符。
 *
 * <p>由 8 个因子动态确定：
 * <ol>
 *   <li>启动器版本（{@link LauncherCore#launcherVersion()}）</li>
 *   <li>设备（CPU 型号 + 逻辑核心数 + 总内存）</li>
 *   <li>系统（OS 名称 + 架构）</li>
 *   <li>启动器内核（Java 版本 + JVM 名称 + JVM 版本）</li>
 *   <li>安装日期（preferences.json 文件创建时间）</li>
 *   <li>安装渠道（JAR 包 = OFFICIAL，IDE = DEV）</li>
 *   <li>构建签名（JAR Manifest Implementation-Vendor + code source 路径哈希）</li>
 *   <li>存放位置（workDir 路径）</li>
 * </ol>
 *
 * <p>同一台机器、同一安装始终生成相同识别码；任何因子变化都会产生不同识别码。
 */
public final class HectMiGenerator {

    private static final int DIGIT_COUNT = 19;
    private static final int LETTER_COUNT = 275;
    private static final int TOTAL_BYTES = DIGIT_COUNT + LETTER_COUNT; // 294

    private HectMiGenerator() {}

    /**
     * 根据当前启动器环境生成 HECT-MI 识别码。
     *
     * @param core 启动器内核实例
     * @return 格式为 {@code XXXXXX-XXXXXX-XXXXXX-X<275 letters>} 的识别码
     */
    public static String generate(LauncherCore core) {
        String payload = buildCanonicalPayload(core);
        byte[] stream = expandStream(payload, TOTAL_BYTES);
        return formatCode(stream);
    }

    /** 收集 8 个因子，拼接为规范化字符串 */
    private static String buildCanonicalPayload(LauncherCore core) {
        return buildPayloadFromFactors(collectFactors(core));
    }

    /** 从因子列表构建规范化 payload 字符串 */
    static String buildPayloadFromFactors(java.util.List<Factor> factors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < factors.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(factors.get(i).rawValue);
        }
        return sb.toString();
    }

    /**
     * 收集当前环境的 8 个因子详情，供解码器展示用。
     *
     * @return 有序因子列表，顺序与 payload 拼接顺序一致
     */
    public static java.util.List<Factor> collectFactors(LauncherCore core) {
        java.util.List<Factor> factors = new java.util.ArrayList<>();

        // 1. 启动器版本
        String version = safe(core.launcherVersion());
        factors.add(new Factor(1, "启动器版本", "Launcher Version", version, version));

        // 2. 设备：CPU 型号 + 逻辑核心数 + 总内存
        String deviceDisplay;
        String deviceRaw;
        try {
            RuntimeManager rt = core.runtime();
            String cpuName = safe(rt.getCpuName());
            int cores = rt.getCpuLogicalCores();
            long memMb = rt.getTotalMemoryMb();
            deviceRaw = cpuName + "::" + cores + "::" + memMb;
            deviceDisplay = cpuName + " | " + cores + " cores | " + memMb + " MB";
        } catch (Throwable t) {
            deviceRaw = "unknown-device";
            deviceDisplay = "未知";
        }
        factors.add(new Factor(2, "设备", "Device", deviceDisplay, deviceRaw));

        // 3. 系统：OS 名称 + 架构
        String osName;
        String osArch;
        try {
            osName = safe(core.runtime().getOsName());
            osArch = safe(System.getProperty("os.arch", "?"));
        } catch (Throwable t) {
            osName = safe(System.getProperty("os.name", "?"));
            osArch = safe(System.getProperty("os.arch", "?"));
        }
        String systemRaw = osName + "::" + osArch;
        factors.add(new Factor(3, "系统", "System", osName + " | " + osArch, systemRaw));

        // 4. 启动器内核：Java 运行时
        String javaVer = safe(System.getProperty("java.version", "?"));
        String vmName = safe(System.getProperty("java.vm.name", "?"));
        String vmVer = safe(System.getProperty("java.vm.version", "?"));
        String kernelRaw = javaVer + "::" + vmName + "::" + vmVer;
        factors.add(new Factor(4, "启动器内核", "Kernel", javaVer + " | " + vmName + " " + vmVer, kernelRaw));

        // 5. 安装日期：preferences.json 文件创建时间（回退到最后修改时间）
        String installDateRaw = "0";
        String installDateDisplay = "未知";
        try {
            Path prefs = core.getConfig().getWorkDir().resolve("preferences.json");
            if (Files.exists(prefs)) {
                BasicFileAttributes attrs = Files.readAttributes(prefs, BasicFileAttributes.class);
                long ts = attrs.creationTime().toMillis();
                if (ts <= 0) ts = attrs.lastModifiedTime().toMillis();
                installDateRaw = String.valueOf(ts);
                installDateDisplay = java.time.Instant.ofEpochMilli(ts)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        } catch (Throwable ignored) {}
        factors.add(new Factor(5, "安装日期", "Install Date", installDateDisplay, installDateRaw));

        // 6. 安装渠道
        String channel = resolveInstallChannel();
        factors.add(new Factor(6, "安装渠道", "Install Channel", channel, channel));

        // 7. 构建签名
        String buildSign = resolveBuildSignature();
        String buildSignDisplay = buildSign.length() > 80 ? buildSign.substring(0, 80) + "..." : buildSign;
        factors.add(new Factor(7, "构建签名", "Build Signature", buildSignDisplay, buildSign));

        // 8. 存放位置
        String location = safe(core.getConfig().getWorkDir().toString());
        factors.add(new Factor(8, "存放位置", "Storage Location", location, location));

        return factors;
    }

    /** 单个因子信息 */
    public static final class Factor {
        public final int index;
        public final String labelZh;
        public final String labelEn;
        public final String displayValue;
        public final String rawValue;

        public Factor(int index, String labelZh, String labelEn, String displayValue, String rawValue) {
            this.index = index;
            this.labelZh = labelZh;
            this.labelEn = labelEn;
            this.displayValue = displayValue;
            this.rawValue = rawValue;
        }
    }

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

    /** 构建签名：Manifest Implementation-Vendor + code source 路径哈希 */
    private static String resolveBuildSignature() {
        StringBuilder sb = new StringBuilder();
        try {
            URL source = LauncherCore.class.getProtectionDomain().getCodeSource().getLocation();
            if (source != null) {
                sb.append(source.toString());
            }
            // 尝试读取 JAR Manifest
            Manifest manifest = LauncherCore.class.getPackage() != null
                    ? readManifest(source) : null;
            if (manifest != null) {
                Attributes attrs = manifest.getMainAttributes();
                String vendor = attrs.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
                String title = attrs.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
                if (vendor != null) sb.append("::").append(vendor);
                if (title != null) sb.append("::").append(title);
            }
        } catch (Throwable ignored) {}
        if (sb.length() == 0) sb.append("unsigned");
        return sb.toString();
    }

    /** 从 JAR URL 读取 Manifest */
    private static Manifest readManifest(URL codeSource) {
        if (codeSource == null || !codeSource.toString().endsWith(".jar")) return null;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(codeSource.getPath())) {
            return jar.getManifest();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 基于 SHA-256 的流扩展：对 payload + counter 反复哈希，生成足够长度的伪随机字节流。
     * 同一 payload 始终产生相同字节流，保证识别码的可复现性。
     */
    private static byte[] expandStream(String payload, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[length];
            int offset = 0;
            int counter = 0;
            while (offset < length) {
                md.reset();
                md.update(payloadBytes);
                md.update((byte) counter);
                md.update((byte) (counter >>> 8));
                byte[] hash = md.digest();
                int toCopy = Math.min(hash.length, length - offset);
                System.arraycopy(hash, 0, result, offset, toCopy);
                offset += toCopy;
                counter++;
            }
            return result;
        } catch (Throwable t) {
            // 极端情况回退：用 payload 字节循环填充
            byte[] fallback = payload.getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[length];
            for (int i = 0; i < length; i++) {
                result[i] = fallback[i % fallback.length];
            }
            return result;
        }
    }

    /** 将字节流格式化为 XXXXXX-XXXXXX-XXXXXX-X + 275 大写字母 */
    private static String formatCode(byte[] stream) {
        StringBuilder sb = new StringBuilder(DIGIT_COUNT + 3 + LETTER_COUNT);

        // 19 位数字，按 6-6-6-1 分组用连字符分隔
        for (int i = 0; i < DIGIT_COUNT; i++) {
            int digit = (stream[i] & 0xFF) % 10;
            sb.append(digit);
            if (i == 5 || i == 11 || i == 17) {
                sb.append('-');
            }
        }

        // 275 位大写字母
        for (int i = 0; i < LETTER_COUNT; i++) {
            int letter = (stream[DIGIT_COUNT + i] & 0xFF) % 26;
            sb.append((char) ('A' + letter));
        }

        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
