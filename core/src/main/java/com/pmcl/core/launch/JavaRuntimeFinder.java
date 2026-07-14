package com.pmcl.core.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 查找系统中可用的 Java 运行时。
 * <p>
 * 优先顺序：
 *   1) 启动器自带的 runtimes 目录（mojang Java runtime）
 *   2) 系统环境变量 JAVA_HOME
 *   3) 常见安装路径
 */
public final class JavaRuntimeFinder {

    /** 匹配 java -version 输出中的版本号，如 version "21.0.1" */
    private static final Pattern VERSION_PATTERN = Pattern.compile("version\\s+\"(\\d+)");

    /** 匹配 -XshowSettings:properties 输出中的 os.arch 属性 */
    private static final Pattern ARCH_PATTERN = Pattern.compile("os\\.arch\\s*=\\s*(\\S+)");

    /** getMajorVersion 结果缓存（key = javaExe 路径），避免重复 fork java -version 进程 */
    private static final ConcurrentHashMap<String, Integer> MAJOR_VERSION_CACHE = new ConcurrentHashMap<>();

    /** getArchitecture 结果缓存（key = javaExe 路径），避免重复 fork java -XshowSettings 进程 */
    private static final ConcurrentHashMap<String, String> ARCH_CACHE = new ConcurrentHashMap<>();

    private JavaRuntimeFinder() {}

    /**
     * 查找 java 可执行文件路径。
     * 优先选择 MC 兼容的 Java 版本（21/17），避免使用过新的 JDK 导致兼容性问题。
     */
    public static String findJavaExecutable() {
        return findJavaExecutable(null);
    }

    /**
     * 查找 java 可执行文件路径。
     * @param runtimesDir 启动器下载的 Java 运行时目录（可为 null）
     *                    优先扫描此目录，其次系统路径。
     */
    public static String findJavaExecutable(Path runtimesDir) {
        return findJavaExecutable(runtimesDir, 0);
    }

    /**
     * 查找 java 可执行文件路径，优先匹配目标 Java 版本。
     * @param runtimesDir 启动器下载的 Java 运行时目录（可为 null）
     * @param requiredMajorVersion 版本 JSON 要求的 Java 主版本号（0=未指定，按通用策略选 21/17）
     *                             alpha/beta/1.7- 传 0 或 8，会优先选 Java 8（避免 LWJGL 2.x 兼容性问题）
     *                             旧版本（< 11）找不到 Java 8 时回退到 Java 9+（由 PMCL 兼容层处理
     *                             LaunchWrapper 的 URLClassLoader 兼容问题）
     */
    public static String findJavaExecutable(Path runtimesDir, int requiredMajorVersion) {

        // 1. 优先扫描启动器下载的 runtimes 目录
        if (runtimesDir != null) {
            List<String> runtimeJavas = scanRuntimes(runtimesDir);
            String best = pickBestJavaForVersion(runtimeJavas, requiredMajorVersion);
            if (best != null) return best;
        }

        // 2. 常见安装路径
        List<String> candidates = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            Path jvmDir = Paths.get("/Library/Java/JavaVirtualMachines");
            if (Files.isDirectory(jvmDir)) {
                try (Stream<Path> stream = Files.list(jvmDir)) {
                    stream.filter(Files::isDirectory).forEach(p -> {
                        String exe = resolveJava(p.toString());
                        if (exe != null) candidates.add(exe);
                    });
                } catch (IOException ignored) {}
            }
            // 兜底常见路径（含 Java 8，alpha/beta 必需）
            candidates.add("/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home");
            candidates.add("/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home");
            candidates.add("/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home");
            candidates.add("/Library/Java/JavaVirtualMachines/jdk8.jdk/Contents/Home");
            candidates.add("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home");
            candidates.add("/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home");
            candidates.add("/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home");
        } else if (os.contains("win")) {
            candidates.add("C:\\Program Files\\Java\\jdk-21");
            candidates.add("C:\\Program Files\\Java\\jdk-17");
            candidates.add("C:\\Program Files\\Java\\jre-8");
            candidates.add("C:\\Program Files\\Java\\jdk-8");
            candidates.add("C:\\Program Files\\Eclipse Adoptium\\jdk-21");
            candidates.add("C:\\Program Files\\Eclipse Adoptium\\jdk-17");
            candidates.add("C:\\Program Files\\Eclipse Adoptium\\jdk-8");
        } else {
            candidates.add("/usr/lib/jvm/java-21-openjdk");
            candidates.add("/usr/lib/jvm/java-17-openjdk");
            candidates.add("/usr/lib/jvm/java-8-openjdk");
            candidates.add("/usr/lib/jvm/java-8-oracle");
            // 龙芯（LoongArch64）常见 JDK 路径
            candidates.add("/usr/lib/jvm/java-21-openjdk-loongarch64");
            candidates.add("/usr/lib/jvm/java-17-openjdk-loongarch64");
            candidates.add("/usr/lib/jvm/java-8-openjdk-loongarch64");
            candidates.add("/usr/lib/jvm/loongson-jdk-21");
            candidates.add("/usr/lib/jvm/loongson-jdk-17");
            candidates.add("/usr/lib/jvm/loongson-jdk-8");
            candidates.add("/opt/loongarch64-jdk");
            // 龙芯（MIPS64el，旧 3A 系列）常见路径
            candidates.add("/usr/lib/jvm/java-8-openjdk-mips64el");
            candidates.add("/opt/jdk8-mips64el");
            // RISC-V 64 位常见 JDK 路径
            candidates.add("/usr/lib/jvm/java-21-openjdk-riscv64");
            candidates.add("/usr/lib/jvm/java-17-openjdk-riscv64");
            candidates.add("/usr/lib/jvm/java-11-openjdk-riscv64");
            candidates.add("/usr/lib/jvm/java-8-openjdk-riscv64");
            candidates.add("/opt/jdk-riscv64");
            candidates.add("/opt/riscv64-jdk");
        }

        // 从候选中按目标版本选最佳
        String best = pickBestJavaForVersion(candidates, requiredMajorVersion);
        if (best != null) return best;

        // 旧版本优先 Java 8，找不到时回退到 Java 9+（由 PMCL 兼容层处理 LaunchWrapper 问题）
        // 不再返回 null，而是继续查找可用的 Java 9+

        // 3. JAVA_HOME（如果用户显式设置）
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            String exe = resolveJava(javaHome);
            if (exe != null) return exe;
        }

        // 4. java 命令在 PATH（兜底）
        try {
            Process p = new ProcessBuilder("java", "-version").redirectErrorStream(true).start();
            try {
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return "java";
            } finally {
                p.destroyForcibly();
            }
        } catch (IOException | InterruptedException ignored) {
        }

        // 5. 未找到任何 Java 运行时（返回 null 让调用方引导用户安装）
        return null;
    }

    /**
     * 从候选 java 路径中选最佳：优先 Java 21，其次 17，避免 24+ 等过新版本。
     */
    private static String pickBestJava(List<String> candidates) {
        return pickBestJavaForVersion(candidates, 0);
    }

    /**
     * 从候选 java 路径中按目标版本选最佳。
     * @param requiredMajorVersion 0=未指定（默认策略：优先 21，其次 17）
     *                             非 0（如 8）：优先精确匹配目标版本，其次按默认策略
     */
    private static String pickBestJavaForVersion(List<String> candidates, int requiredMajorVersion) {
        // alpha/beta 等 Old 版本：精确匹配 Java 8 优先（LWJGL 2.x / 反射在 Java 9+ 上不兼容）
        if (requiredMajorVersion > 0 && requiredMajorVersion < 11) {
            // Apple Silicon Mac 特殊处理：旧版本 native 库只有 x86_64 架构，
            // arm64 Java 无法加载这些 native 库。优先选择 x86_64 Java（通过 Rosetta 2 运行）。
            boolean isAppleSilicon = isAppleSiliconMac();
            if (isAppleSilicon) {
                // 优先：x86_64 架构的 Java 8
                for (String exe : candidates) {
                    Integer ver = getMajorVersion(exe);
                    if (ver != null && ver == 8 && isX86Arch(exe)) {
                        System.err.println("[JavaRuntimeFinder] Apple Silicon: 选中 x86_64 Java 8 for 旧版本");
                        return exe;
                    }
                }
                // 次选：x86_64 架构的 Java 9+（配合 PMCL 兼容层）
                for (String exe : candidates) {
                    Integer ver = getMajorVersion(exe);
                    if (ver != null && ver >= 9 && ver < 24 && isX86Arch(exe)) {
                        System.err.println("[JavaRuntimeFinder] Apple Silicon: 选中 x86_64 Java " + ver + " for 旧版本");
                        return exe;
                    }
                }
                // 系统未找到 x86_64 Java，扫描外部启动器（HMCL/LauncherX/官方启动器）管理的 Java
                ExternalLauncherDetector.JavaRuntimeInfo externalJava =
                        ExternalLauncherDetector.findX86Java8();
                if (externalJava != null) {
                    System.err.println("[JavaRuntimeFinder] 从外部启动器找到 x86_64 Java 8: "
                            + externalJava.getJavaPath() + " (来源: " + externalJava.getSource() + ")");
                    return externalJava.getJavaPath();
                }
            }
            // 非 Apple Silicon 或未找到 x86_64 Java：按常规逻辑选 Java 8
            String exact = null;
            for (String exe : candidates) {
                Integer ver = getMajorVersion(exe);
                if (ver != null && ver == requiredMajorVersion) {
                    exact = exe;
                    break;
                }
            }
            if (exact != null) return exact;
            // 找不到精确匹配，退而求其次找 Java 8（最通用旧版）
            for (String exe : candidates) {
                Integer ver = getMajorVersion(exe);
                if (ver != null && ver == 8) return exe;
            }
        }
        // 新版本（17/21）或未指定：优先 21，其次 17，避免 24+ 等过新版本
        String j21 = null, j17 = null, other = null;
        for (String exe : candidates) {
            Integer ver = getMajorVersion(exe);
            if (ver == null) continue;
            if (ver == 21 && j21 == null) j21 = exe;
            else if (ver == 17 && j17 == null) j17 = exe;
            else if (ver >= 8 && ver < 24 && other == null) other = exe;
        }
        if (j21 != null) return j21;
        if (j17 != null) return j17;
        return other;
    }

    /**
     * 判断当前系统是否为 Apple Silicon Mac（arm64 架构的 macOS）。
     */
    private static boolean isAppleSiliconMac() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        return osName.contains("mac") && (osArch.contains("aarch64") || osArch.contains("arm64"));
    }

    /**
     * 判断指定 Java 可执行文件是否为 x86_64 架构。
     * 用于 Apple Silicon Mac 上选择可通过 Rosetta 2 运行的 Java。
     */
    private static boolean isX86Arch(String javaExe) {
        String arch = getArchitecture(javaExe).toLowerCase();
        return arch.contains("x86_64") || arch.contains("amd64") || arch.contains("x64");
    }

    /**
     * 判断当前系统是否为龙芯（LoongArch64）架构。
     * 龙芯 3A5000/3A6000 及以后使用 LoongArch64 架构。
     */
    public static boolean isLoongArch64() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("loongarch64") || arch.contains("la64") || arch.contains("la464");
    }

    /**
     * 判断当前系统是否为龙芯旧版（MIPS64el）架构。
     * 龙芯 3A3000/3A4000 等使用 MIPS64el 架构。
     */
    public static boolean isMips64el() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("mips64el") || arch.contains("mips64");
    }

    /**
     * 判断当前系统是否为任意龙芯架构（LoongArch64 或 MIPS64el）。
     */
    public static boolean isLoongson() {
        return isLoongArch64() || isMips64el();
    }

    /**
     * 判断当前系统是否为 RISC-V 64 位架构。
     * 常见于 RVV 开发板（如 VisionFive 2）、SiFive、阿里平头哥 C910 等硬件。
     */
    public static boolean isRiscV64() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("riscv64") || arch.contains("risc-v64") || arch.contains("rv64");
    }

    /**
     * 判断当前系统是否为任意 RISC-V 架构（当前仅支持 64 位）。
     */
    public static boolean isRiscV() {
        return isRiscV64();
    }

    /**
     * 获取 java 的主版本号（如 21、17、24），失败返回 null。
     * 结果按 javaExe 路径缓存，避免对同一可执行文件重复 fork 进程。
     */
    public static Integer getMajorVersion(String javaExe) {
        Integer cached = MAJOR_VERSION_CACHE.get(javaExe);
        if (cached != null) return cached;
        Integer result = computeMajorVersion(javaExe);
        if (result != null) MAJOR_VERSION_CACHE.put(javaExe, result);
        return result;
    }

    /** 实际通过 fork java -version 解析版本号 */
    private static Integer computeMajorVersion(String javaExe) {
        try {
            Process p = new ProcessBuilder(javaExe, "-version").redirectErrorStream(true).start();
            try (java.io.InputStream is = p.getInputStream()) {
                String output = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    return null;
                }
                // 输出形如: java version "21.0.1" 2023-10-17 LTS
                //          openjdk version "17.0.9" 2023-10-17
                Matcher m = VERSION_PATTERN.matcher(output);
                if (m.find()) return Integer.parseInt(m.group(1));
            } finally {
                p.destroyForcibly();
            }
        } catch (IOException | InterruptedException ignored) {}
        return null;
    }

    /**
     * 获取指定 Java 可执行文件的 os.arch（如 "aarch64"、"amd64"、"x86_64"）。
     * 通过运行 java -XshowSettings:properties -version 解析 os.arch 属性。
     * 这反映的是游戏 Java 进程的架构，而非启动器自身的架构。
     * 失败时回退到系统 os.arch。
     * 成功解析的结果按 javaExe 路径缓存，避免重复 fork 进程。
     */
    public static String getArchitecture(String javaExe) {
        String cached = ARCH_CACHE.get(javaExe);
        if (cached != null) return cached;
        String result = computeArchitecture(javaExe);
        if (result != null) {
            ARCH_CACHE.put(javaExe, result);
            return result;
        }
        // 回退到启动器自身的 os.arch（不缓存，便于后续重试）
        return System.getProperty("os.arch", "");
    }

    /** 实际通过 fork java -XshowSettings:properties -version 解析 os.arch */
    private static String computeArchitecture(String javaExe) {
        try {
            Process p = new ProcessBuilder(javaExe, "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true).start();
            try (java.io.InputStream is = p.getInputStream()) {
                String output = new String(is.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    return null;
                }
                // 输出包含:    os.arch = aarch64  或  os.arch = x86_64  或  os.arch = amd64
                Matcher m = ARCH_PATTERN.matcher(output);
                if (m.find()) return m.group(1);
            } finally {
                p.destroyForcibly();
            }
        } catch (IOException | InterruptedException ignored) {}
        return null;
    }

    private static String resolveJava(String home) {
        Path p = Paths.get(home);
        if (!Files.isDirectory(p)) return null;
        String os = System.getProperty("os.name").toLowerCase();
        // macOS: JDK 目录结构是 xxx.jdk/Contents/Home/bin/java
        if (os.contains("mac")) {
            Path contentsHome = p.resolve("Contents/Home");
            if (Files.isDirectory(contentsHome)) p = contentsHome;
        }
        Path exe = os.contains("win") ? p.resolve("bin/java.exe") : p.resolve("bin/java");
        return Files.isExecutable(exe) ? exe.toString() : null;
    }

    /**
     * 扫描某目录下的所有 Java 运行时。
     */
    public static List<String> scanRuntimes(Path runtimesDir) {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(runtimesDir)) return result;
        String os = System.getProperty("os.name").toLowerCase();
        try (Stream<Path> stream = Files.list(runtimesDir)) {
            stream.filter(Files::isDirectory).forEach(archDir -> {
                try (Stream<Path> inner = Files.list(archDir)) {
                    inner.filter(Files::isDirectory).forEach(jvmDir -> {
                        String exe = resolveJava(jvmDir.toString());
                        if (exe != null) result.add(exe);
                    });
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        return result;
    }
}
