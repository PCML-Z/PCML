package com.pmcl.core.launch;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 检测系统中已安装的其他 Minecraft 启动器（HMCL、LauncherX 等）。
 * <p>
 * 用于兼容性问题场景：当 PMCL 无法直接启动某版本时（如 Apple Silicon Mac 上旧版本
 * 缺少 arm64 native 库），检测用户是否安装了其他启动器，让用户选择：
 * 1. 借用其他启动器管理的 Java 运行时（特别是 x86_64 Java 8）
 * 2. 直接用其他启动器打开该版本
 */
public final class ExternalLauncherDetector {

    private static final Pattern HMCL_JAR_PATTERN = Pattern.compile("HMCL[-\\d.]*\\.jar");

    /** 检测到的外部启动器信息 */
    public static class ExternalLauncher {
        public enum Type { HMCL, LAUNCHER_X, PCL, OTHER }

        private final Type type;
        private final String name;
        private final String executablePath;   // 启动器可执行文件路径（jar 或 app）
        private final String gameDir;           // 该启动器的游戏目录

        public ExternalLauncher(Type type, String name, String executablePath, String gameDir) {
            this.type = type;
            this.name = name;
            this.executablePath = executablePath;
            this.gameDir = gameDir;
        }

        public Type getType() { return type; }
        public String getName() { return name; }
        public String getExecutablePath() { return executablePath; }
        public String getGameDir() { return gameDir; }
    }

    /** 检测到的 Java 运行时信息 */
    public static class JavaRuntimeInfo {
        private final String javaPath;
        private final int majorVersion;
        private final String arch;
        private final String source;  // 来源描述（如 "HMCL 管理的 Java"）

        public JavaRuntimeInfo(String javaPath, int majorVersion, String arch, String source) {
            this.javaPath = javaPath;
            this.majorVersion = majorVersion;
            this.arch = arch;
            this.source = source;
        }

        public String getJavaPath() { return javaPath; }
        public int getMajorVersion() { return majorVersion; }
        public String getArch() { return arch; }
        public String getSource() { return source; }
    }

    /**
     * 检测系统中已安装的外部启动器。
     * @return 检测到的启动器列表（可能为空）
     */
    public static List<ExternalLauncher> detectLaunchers() {
        List<ExternalLauncher> result = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();

        // === HMCL ===
        detectHMCL(result, os);

        // === LauncherX ===
        detectLauncherX(result, os);

        // === PCL (Plain Craft Launcher) ===
        detectPCL(result, os);

        return result;
    }

    /**
     * 检测 HMCL 的安装位置和游戏目录。
     * HMCL 以 jar 包形式分发，常见位置：~/Downloads/HMCL-*.jar, /Applications/HMCL.app
     * 配置目录：~/.hmcl
     * 游戏目录：~/Library/Application Support/.minecraft (macOS)
     */
    private static void detectHMCL(List<ExternalLauncher> result, String os) {
        List<String> jarCandidates = new ArrayList<>();
        String home = System.getProperty("user.home");

        if (os.contains("mac")) {
            // macOS: 搜索 HMCL jar 文件
            jarCandidates.add(home + "/Downloads");
            jarCandidates.add(home + "/Applications");
            jarCandidates.add("/Applications");

            // 检查 HMCL.app
            String[] appPaths = {
                    "/Applications/HMCL.app",
                    home + "/Applications/HMCL.app"
            };
            for (String appPath : appPaths) {
                if (Files.isDirectory(Paths.get(appPath))) {
                    // HMCL.app 内的 jar
                    Path jarInApp = Paths.get(appPath, "Contents/Java/HMCL.jar");
                    if (Files.exists(jarInApp)) {
                        String gameDir = home + "/Library/Application Support/.minecraft";
                        if (!Files.isDirectory(Paths.get(gameDir))) {
                            gameDir = home + "/Library/Application Support/minecraft";
                        }
                        result.add(new ExternalLauncher(
                                ExternalLauncher.Type.HMCL, "HMCL",
                                jarInApp.toString(), gameDir));
                        return;
                    }
                }
            }
        } else if (os.contains("win")) {
            jarCandidates.add(home + "\\Downloads");
            jarCandidates.add(home + "\\Desktop");
            jarCandidates.add("C:\\Program Files\\HMCL");
            jarCandidates.add("C:\\HMCL");
        } else {
            jarCandidates.add(home + "/Downloads");
            jarCandidates.add(home + "/.local/share/HMCL");
            jarCandidates.add("/opt/HMCL");
        }

        // 搜索目录中的 HMCL-*.jar 文件
        for (String dir : jarCandidates) {
            Path dirPath = Paths.get(dir);
            if (!Files.isDirectory(dirPath)) continue;
            try (Stream<Path> stream = Files.list(dirPath)) {
                var hmclJar = stream
                        .filter(p -> HMCL_JAR_PATTERN.matcher(p.getFileName().toString()).matches())
                        .findFirst();
                if (hmclJar.isPresent()) {
                    String gameDir;
                    if (os.contains("mac")) {
                        gameDir = home + "/Library/Application Support/.minecraft";
                        if (!Files.isDirectory(Paths.get(gameDir))) {
                            gameDir = home + "/Library/Application Support/minecraft";
                        }
                    } else if (os.contains("win")) {
                        gameDir = home + "\\AppData\\Roaming\\.minecraft";
                    } else {
                        gameDir = home + "/.minecraft";
                    }
                    result.add(new ExternalLauncher(
                            ExternalLauncher.Type.HMCL, "HMCL",
                            hmclJar.get().toString(), gameDir));
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * 检测 LauncherX 的安装位置和游戏目录。
     * macOS: /Applications/LauncherX.app
     * 配置目录：~/Library/Application Support/LauncherX/
     */
    private static void detectLauncherX(List<ExternalLauncher> result, String os) {
        String home = System.getProperty("user.home");

        if (os.contains("mac")) {
            // LauncherX 可能的安装路径：LauncherX.app / LauncherX.Avalonia.app
            List<String> appPaths = new ArrayList<>();
            for (String base : new String[]{"/Applications", home + "/Applications"}) {
                Path baseDir = Paths.get(base);
                if (Files.isDirectory(baseDir)) {
                    try (Stream<Path> stream = Files.list(baseDir)) {
                        stream.filter(Files::isDirectory)
                              .filter(p -> {
                                  String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                                  return name.startsWith("launcherx") && name.endsWith(".app");
                              })
                              .forEach(p -> appPaths.add(p.toString()));
                    } catch (Exception ignored) {}
                }
            }
            // 兜底：显式列出常见名称
            appPaths.add("/Applications/LauncherX.app");
            appPaths.add("/Applications/LauncherX.Avalonia.app");
            appPaths.add(home + "/Applications/LauncherX.app");
            appPaths.add(home + "/Applications/LauncherX.Avalonia.app");

            for (String appPath : appPaths) {
                if (!Files.isDirectory(Paths.get(appPath))) continue;
                // 从配置文件读取游戏目录
                String gameDir = home + "/Library/Application Support/.minecraft";
                Path configPath = Paths.get(
                        home, "Library/Application Support/LauncherX/launcherx.json");
                if (Files.exists(configPath)) {
                    try {
                        String content = Files.readString(configPath, java.nio.charset.StandardCharsets.UTF_8);
                        // 解析 GamePathList 段中的 Path 字段（取第一个条目）
                        int gplIdx = content.indexOf("GamePathList");
                        if (gplIdx >= 0) {
                            String segment = content.substring(gplIdx);
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("\"Path\"\\s*:\\s*\"([^\"]+)\"")
                                    .matcher(segment);
                            if (m.find()) {
                                gameDir = m.group(1);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                result.add(new ExternalLauncher(
                        ExternalLauncher.Type.LAUNCHER_X, "LauncherX",
                        appPath, gameDir));
                return;
            }
        } else if (os.contains("win")) {
            String[] appPaths = {
                    "C:\\Program Files\\LauncherX\\LauncherX.exe",
                    "C:\\Program Files (x86)\\LauncherX\\LauncherX.exe",
                    home + "\\AppData\\Local\\LauncherX\\LauncherX.exe"
            };
            for (String appPath : appPaths) {
                if (Files.exists(Paths.get(appPath))) {
                    String gameDir = home + "\\AppData\\Roaming\\.minecraft";
                    result.add(new ExternalLauncher(
                            ExternalLauncher.Type.LAUNCHER_X, "LauncherX",
                            appPath, gameDir));
                    return;
                }
            }
        }
    }

    /**
     * 检测 PCL (Plain Craft Launcher)。
     * PCL 主要是 Windows 启动器。
     */
    private static void detectPCL(List<ExternalLauncher> result, String os) {
        if (!os.contains("win")) return;
        String home = System.getProperty("user.home");
        String[] appPaths = {
                home + "\\AppData\\Local\\PCL\\Plain Craft Launcher.exe",
                "C:\\Program Files\\PCL\\Plain Craft Launcher.exe",
                home + "\\Desktop\\Plain Craft Launcher.exe"
        };
        for (String appPath : appPaths) {
            if (Files.exists(Paths.get(appPath))) {
                String gameDir = home + "\\AppData\\Roaming\\.minecraft";
                result.add(new ExternalLauncher(
                        ExternalLauncher.Type.PCL, "PCL",
                        appPath, gameDir));
                return;
            }
        }
    }

    /**
     * 检测外部启动器管理的 Java 运行时（特别是 x86_64 Java 8）。
     * <p>
     * 扫描路径：
     * - HMCL 游戏目录下的 jre/ 子目录
     * - HMCL 配置目录 ~/.hmcl/jre/
     * - LauncherX 游戏目录下的 jre/ 子目录
     * - 官方启动器 ~/Library/Application Support/minecraft/runtime/
     * - 系统各启动器的 runtime/runtimes 目录
     *
     * @param preferX86 是否优先查找 x86_64 架构的 Java（Apple Silicon Mac 上旧版本需要）
     * @return 检测到的 Java 运行时列表
     */
    public static List<JavaRuntimeInfo> detectExternalJavaRuntimes(boolean preferX86) {
        List<JavaRuntimeInfo> result = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");

        List<String> runtimeDirs = new ArrayList<>();

        if (os.contains("mac")) {
            // HMCL 游戏目录下的 jre/
            runtimeDirs.add(home + "/Library/Application Support/.minecraft/jre");
            runtimeDirs.add(home + "/Library/Application Support/.minecraft/runtime");
            runtimeDirs.add(home + "/Library/Application Support/minecraft/runtime");
            // HMCL 配置目录
            runtimeDirs.add(home + "/.hmcl/jre");
            runtimeDirs.add(home + "/.hmcl/runtime");
            // LauncherX 游戏目录下的 jre/
            runtimeDirs.add(home + "/Library/Application Support/LauncherX/jre");
            runtimeDirs.add(home + "/Library/Application Support/LauncherX/runtime");
            // PMCL 自身的 runtimes 目录（可能之前下载过）
            runtimeDirs.add(home + "/.pmcl/runtimes");
        } else if (os.contains("win")) {
            runtimeDirs.add(home + "\\AppData\\Roaming\\.minecraft\\jre");
            runtimeDirs.add(home + "\\AppData\\Roaming\\.minecraft\\runtime");
            runtimeDirs.add(home + "\\AppData\\Roaming\\.hmcl\\jre");
            runtimeDirs.add(home + "\\AppData\\Local\\LauncherX\\jre");
            runtimeDirs.add(home + "\\AppData\\Local\\LauncherX\\runtime");
            runtimeDirs.add(home + "\\.pmcl\\runtimes");
        } else {
            runtimeDirs.add(home + "/.minecraft/jre");
            runtimeDirs.add(home + "/.minecraft/runtime");
            runtimeDirs.add(home + "/.hmcl/jre");
            runtimeDirs.add(home + "/.pmcl/runtimes");
        }

        for (String dir : runtimeDirs) {
            scanRuntimeDir(dir, result, preferX86);
        }

        return result;
    }

    /**
     * 扫描指定目录下的 Java 运行时。
     * 目录结构可能是：
     *   dir/java-8/bin/java
     *   dir/macos-arm64/JAVA_8-xxx/bin/java
     *   dir/x86_64/jdk-8/bin/java
     */
    private static void scanRuntimeDir(String dirPath, List<JavaRuntimeInfo> result, boolean preferX86) {
        Path dir = Paths.get(dirPath);
        if (!Files.isDirectory(dir)) return;

        String os = System.getProperty("os.name", "").toLowerCase();
        String javaExeName = os.contains("win") ? "java.exe" : "java";

        // M80: 加超时保护（5 秒），避免网络挂载点或慢速磁盘导致扫描线程长期阻塞
        long deadlineMs = System.currentTimeMillis() + 5000;
        try (Stream<Path> stream = Files.walk(dir, 4)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                if (System.currentTimeMillis() > deadlineMs) {
                    System.err.println("[ExternalLauncherDetector] 扫描超时（5s），部分目录跳过: " + dirPath);
                    break;
                }
                Path p = it.next();
                if (!p.getFileName().toString().equals(javaExeName) || !Files.isExecutable(p)) continue;
                try {
                    String absPath = p.toAbsolutePath().toString();
                    Integer ver = JavaRuntimeFinder.getMajorVersion(absPath);
                    String arch = JavaRuntimeFinder.getArchitecture(absPath);
                    if (ver != null) {
                        String source = dirPath.contains("hmcl") ? "HMCL"
                                : dirPath.contains("LauncherX") ? "LauncherX"
                                : dirPath.contains("minecraft") ? "官方启动器"
                                : dirPath.contains("pmcl") ? "PMCL" : "外部启动器";
                        result.add(new JavaRuntimeInfo(absPath, ver, arch, source));
                    }
                } catch (Exception e) {
                    // M80: 记录异常便于排查为何某 Java 未被识别
                    System.err.println("[ExternalLauncherDetector] 解析 Java 失败: " + p
                            + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // M80: 记录扫描异常（如权限拒绝、目录不可读），避免静默吞错
            System.err.println("[ExternalLauncherDetector] 扫描目录失败: " + dirPath
                    + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 查找 x86_64 架构的 Java 8 运行时（Apple Silicon Mac 上旧版本专用）。
     * 先扫描外部启动器管理的 Java，再扫描系统安装的 JDK。
     *
     * @return 找到的 x86_64 Java 8 路径，未找到返回 null
     */
    public static JavaRuntimeInfo findX86Java8() {
        // 1. 优先扫描外部启动器管理的 Java 运行时
        List<JavaRuntimeInfo> externalRuntimes = detectExternalJavaRuntimes(true);
        for (JavaRuntimeInfo rt : externalRuntimes) {
            if (rt.getMajorVersion() == 8 && isX86Arch(rt.getArch())) {
                return rt;
            }
        }
        // 2. 扫描系统安装的 JDK（/Library/Java/JavaVirtualMachines）
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            Path jvmDir = Paths.get("/Library/Java/JavaVirtualMachines");
            if (Files.isDirectory(jvmDir)) {
                List<JavaRuntimeInfo> systemJavas = new ArrayList<>();
                scanRuntimeDir(jvmDir.toString(), systemJavas, true);
                for (JavaRuntimeInfo rt : systemJavas) {
                    if (rt.getMajorVersion() == 8 && isX86Arch(rt.getArch())) {
                        return rt;
                    }
                }
            }
            // 3. Homebrew 安装的 Java
            Path brewDir = Paths.get("/opt/homebrew/opt");
            if (Files.isDirectory(brewDir)) {
                try (Stream<Path> stream = Files.list(brewDir)) {
                    var javaDirs = stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("openjdk") || name.startsWith("java");
                    }).toList();
                    for (Path jd : javaDirs) {
                        Path javaBin = jd.resolve("bin/java");
                        if (Files.exists(javaBin)) {
                            try {
                                Integer ver = JavaRuntimeFinder.getMajorVersion(javaBin.toString());
                                String arch = JavaRuntimeFinder.getArchitecture(javaBin.toString());
                                if (ver != null && ver == 8 && isX86Arch(arch)) {
                                    return new JavaRuntimeInfo(
                                            javaBin.toAbsolutePath().toString(), ver, arch, "Homebrew");
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * 判断架构是否为 x86_64。
     */
    private static boolean isX86Arch(String arch) {
        if (arch == null) return false;
        String a = arch.toLowerCase();
        return a.contains("x86_64") || a.contains("amd64") || a.contains("x64");
    }

    /**
     * 构建用外部启动器启动游戏的命令。
     *
     * @param launcher 外部启动器信息
     * @param versionId 要启动的版本 ID
     * @return 启动命令列表（ProcessBuilder 用）
     */
    public static List<String> buildExternalLaunchCommand(ExternalLauncher launcher, String versionId) {
        List<String> cmd = new ArrayList<>();
        switch (launcher.getType()) {
            case HMCL:
                // HMCL: java -jar HMCL.jar --version <versionId>
                // HMCL 支持命令行指定版本启动
                cmd.add("java");
                cmd.add("-jar");
                cmd.add(launcher.getExecutablePath());
                // HMCL 的命令行参数（具体参数可能因版本而异，这里用常见的格式）
                cmd.add("--launch");
                cmd.add(versionId);
                break;
            case LAUNCHER_X:
                // LauncherX: 直接打开 app，不支持命令行启动指定版本
                // macOS 用 open 命令打开
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("mac")) {
                    cmd.add("open");
                    cmd.add(launcher.getExecutablePath());
                } else {
                    cmd.add(launcher.getExecutablePath());
                }
                break;
            case PCL:
                cmd.add(launcher.getExecutablePath());
                break;
            default:
                cmd.add(launcher.getExecutablePath());
        }
        return cmd;
    }
}
