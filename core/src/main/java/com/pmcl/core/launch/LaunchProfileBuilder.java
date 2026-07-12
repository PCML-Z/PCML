package com.pmcl.core.launch;

import com.google.gson.JsonObject;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.auth.Account;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.Library;
import com.pmcl.core.install.VersionJson;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.version.VersionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从已安装的版本 JSON 构造 {@link LaunchProfile}。
 * <p>
 * 流程：读取 versions/{id}/{id}.json → 解析 → 处理 inheritsFrom
 *      → 收集 classpath（client.jar + libraries 主 artifact）
 *      → 注入 JVM/游戏参数 → 叠加用户偏好（GC/Aikar/自定义参数）。
 * <p>
 * 版本查找范围：.pmcl/versions + 系统默认 Minecraft 目录（Mac/Win/Linux）。
 */
public final class LaunchProfileBuilder {

    private final LauncherConfig config;
    private final Preferences preferences;
    private final DownloadManager downloadManager;

    public LaunchProfileBuilder(LauncherConfig config, Preferences preferences) {
        this(config, preferences, null);
    }

    public LaunchProfileBuilder(LauncherConfig config, Preferences preferences,
                                DownloadManager downloadManager) {
        this.config = config;
        this.preferences = preferences;
        this.downloadManager = downloadManager;
    }

    /**
     * 获取所有需要查找的 versions 目录（.pmcl 优先，再系统默认 Minecraft 目录）。
     */
    private List<Path> getVersionsDirs() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(config.getVersionsDir());
        Path mcDir = VersionManager.detectDefaultMinecraftVersionsDir();
        if (mcDir != null && !mcDir.equals(config.getVersionsDir())) {
            dirs.add(mcDir);
        }
        return dirs;
    }

    /**
     * 在所有已知 versions 目录中查找版本 JSON，返回首个找到的路径。
     */
    private Path findVersionJson(String versionId) {
        for (Path dir : getVersionsDirs()) {
            Path jsonPath = dir.resolve(versionId).resolve(versionId + ".json");
            if (Files.exists(jsonPath)) return jsonPath;
        }
        return null;
    }

    /**
     * 在所有已知 versions 目录中查找版本 jar，返回首个找到的路径。
     */
    private Path findVersionJar(String versionId) {
        for (Path dir : getVersionsDirs()) {
            Path jarPath = dir.resolve(versionId).resolve(versionId + ".jar");
            if (Files.exists(jarPath)) return jarPath;
        }
        return null;
    }

    /**
     * 根据版本 JSON 所在目录推导 Minecraft 根目录（versions 的父目录）。
     * 外部安装的版本（如 ~/.minecraft/versions/1.21/1.21.json）→ ~/.minecraft
     * .pmcl 安装的版本 → config.getWorkDir()
     */
    private Path resolveMcRoot(String versionId) {
        Path jsonPath = findVersionJson(versionId);
        if (jsonPath != null) {
            Path versionsDir = jsonPath.getParent().getParent(); // versions/{id}/{id}.json → versions
            Path mcRoot = versionsDir.getParent(); // versions → mc root
            if (mcRoot != null) return mcRoot;
        }
        return config.getWorkDir();
    }

    /**
     * 推导游戏工作目录（gameDir）。
     * 对于整合包（版本目录内含 mods/、config/ 等 game content），gameDir 应为版本目录本身，
     * 这样 modloader 才能找到 versions/<id>/mods/ 下的模组。
     * 对于普通版本，gameDir 为 mcRoot（与 libraries/assets 同级）。
     */
    private Path resolveGameDir(String versionId, Path mcRoot) {
        Path jsonPath = findVersionJson(versionId);
        if (jsonPath != null) {
            Path versionDir = jsonPath.getParent(); // versions/{id}/
            // 整合包判定：版本目录内存在 mods/ 子目录
            if (java.nio.file.Files.isDirectory(versionDir.resolve("mods"))) {
                return versionDir;
            }
        }
        return mcRoot;
    }

    /**
     * 构造启动配置。
     */
    public LaunchProfile build(String versionId, Account account) throws IOException {
        return build(versionId, account, 0, null);
    }

    /**
     * 构造启动配置。
     * @param javaMajorVersion 实际使用的 Java 主版本号（如 8/17/21），0 表示未知。
     */
    public LaunchProfile build(String versionId, Account account, int javaMajorVersion) throws IOException {
        return build(versionId, account, javaMajorVersion, null);
    }

    /**
     * 构造启动配置。
     * @param javaMajorVersion 实际使用的 Java 主版本号（如 8/17/21），0 表示未知。
     *                         用于条件注入 Java 16+ 专属参数，避免在 Java 8 上启动失败。
     * @param javaArch 游戏 Java 的架构（如 "aarch64"、"x86_64"），null 表示未知。
     *                 用于让 native 库选择匹配游戏 Java 架构的版本，而非启动器自身架构。
     *                 在 ARM64 系统上用 x86_64 Java 启动老版本时，此参数确保选择 x86_64 natives。
     */
    public LaunchProfile build(String versionId, Account account, int javaMajorVersion, String javaArch) throws IOException {
        // 设置架构覆盖，让 Library 的 classifier 选择匹配游戏 Java 的架构
        if (javaArch != null && !javaArch.isEmpty()) {
            com.pmcl.core.install.Library.setArchOverride(javaArch);
        }
        try {
            return buildInternal(versionId, account, javaMajorVersion);
        } finally {
            com.pmcl.core.install.Library.clearArchOverride();
        }
    }

    /**
     * 读取版本 JSON 要求的 Java 主版本号（javaVersion.majorVersion）。
     * alpha/beta/1.7- 等旧版本无此字段，但实际需要 Java 8（LWJGL 2.x / 旧反射 API）。
     * 判断依据：无 javaVersion 字段且使用旧格式 minecraftArguments（而非 arguments 对象）→ 返回 8。
     * 用于在启动前选择合适版本的 Java 运行时。
     */
    public int getRequiredJavaVersion(String versionId) throws IOException {
        VersionJson vj = loadVersionJson(versionId);
        int ver = vj.getJavaVersion();
        if (ver > 0) return ver;
        // 无 javaVersion 字段的旧版本（alpha/beta/1.7-）需要 Java 8
        if (!vj.getRawJson().has("arguments")) {
            return 8;
        }
        return ver;
    }

    /**
     * 实际构造启动配置的内部方法（架构覆盖已由 build() 设置）。
     * @param javaMajorVersion 实际使用的 Java 主版本号（如 8/17/21），0 表示未知。
     *                         用于条件注入 Java 16+ 专属参数，避免在 Java 8 上启动失败。
     */
    private LaunchProfile buildInternal(String versionId, Account account, int javaMajorVersion) throws IOException {
        VersionJson vj = loadVersionJson(versionId);

        LaunchProfile profile = new LaunchProfile(config, account, versionId);

        if (vj.getMainClass() != null && !vj.getMainClass().isEmpty()) {
            profile.setMainClass(vj.getMainClass());
        }

        // 推导 Minecraft 根目录（外部安装的版本用外部目录的 libraries/assets）
        Path mcRoot = resolveMcRoot(versionId);
        Path librariesDir = mcRoot.resolve("libraries");
        Path assetsDir = mcRoot.resolve("assets");
        Path versionsDir = mcRoot.resolve("versions");

        // 校验并自动下载缺失的库文件（修复不完整的 MC 安装）
        verifyLibraries(vj, librariesDir);

        // 设置游戏工作目录：整合包用版本目录本身，普通版本用 mcRoot
        Path gameDir = resolveGameDir(versionId, mcRoot);
        profile.setGameDir(gameDir);

        Set<String> seen = new LinkedHashSet<>();
        // client jar
        Path clientJar = findVersionJar(versionId);
        if (clientJar != null) {
            addClasspath(profile, seen, clientJar);
        }

        // 解压 natives 到 versions/{id}/natives/ 目录
        Path nativesDir = versionsDir.resolve(versionId).resolve("natives");
        try {
            java.nio.file.Files.createDirectories(nativesDir);
            // 清空 natives 目录（避免旧库残留）
            try (var stream = java.nio.file.Files.list(nativesDir)) {
                stream.forEach(p -> {
                    try { java.nio.file.Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        } catch (IOException e) {
            throw new IOException("无法创建 natives 目录: " + nativesDir, e);
        }

        for (Library lib : vj.getLibraries()) {
            if (!lib.appliesToCurrentOs()) continue;

            // === MC 1.18+ 新格式：native 库以独立 library 条目存在（name 带 :natives-xxx）===
            if (lib.getNameClassifier() != null && lib.getNameClassifier().startsWith("natives-")) {
                // 只处理匹配当前平台的 native 条目
                if (!lib.matchesCurrentNative()) continue;
                Path nativeJar = librariesDir.resolve(lib.getPath());
                if (java.nio.file.Files.exists(nativeJar)) {
                    extractNatives(nativeJar, nativesDir);
                }
                continue;
            }

            // === 旧格式：natives 字段 + classifiers ===
            // 主 artifact 加入 classpath
            if (lib.getArtifact() != null) {
                Path libPath = librariesDir.resolve(lib.getPath());
                addClasspath(profile, seen, libPath);
            }
            // native 库：解压到 nativesDir
            if (lib.isNativeLib()) {
                VersionJson.Artifact nativeArt = lib.getNativeArtifact();
                if (nativeArt == null) continue;
                String classifier = lib.getNativeClassifier();
                Path nativeJar = librariesDir.resolve(lib.getPathForClassifier(classifier));
                if (java.nio.file.Files.exists(nativeJar)) {
                    extractNatives(nativeJar, nativesDir);
                }
            }
        }

        // 设置 java.library.path 指向 natives 目录
        profile.addJvmArg("-Djava.library.path=" + nativesDir.toString());
        // LWJGL 3 也支持此参数
        profile.addJvmArg("-Dorg.lwjgl.librarypath=" + nativesDir.toString());
        // Java 16+ 需要显式开启 native access，否则 LWJGL 加载本地库会警告/失败
        // 注意：此参数 Java 8 不识别，注入会导致 JVM 直接报错退出（alpha/beta 必需 Java 8）
        if (javaMajorVersion >= 16) {
            profile.addJvmArg("--enable-native-access=ALL-UNNAMED");
        }

        // === 兼容层：让 Java 9+ 能启动使用 LaunchWrapper 的旧版本（MC 1.6-1.12.2） ===
        // LaunchWrapper 将系统类加载器强转为 URLClassLoader，Java 9+ 的 AppClassLoader
        // 不再继承 URLClassLoader，导致 ClassCastException 崩溃。
        // 通过 -Djava.system.class.loader 注入自定义 URLClassLoader 子类解决。
        boolean usesLaunchWrapper = vj.getMainClass() != null
                && vj.getMainClass().contains("launchwrapper");
        if (usesLaunchWrapper && javaMajorVersion >= 9) {
            applyLaunchWrapperCompatLayer(profile, seen);
        }

        // macOS 必须在主线程运行 GLFW，否则报 "GLFW may only be used on the main thread"
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            profile.addJvmArg("-XstartOnFirstThread");
        }

        // Log4j2 配置：MC 1.13+ 的版本 JSON 有 logging.client 字段，
        // 指定 log4j2-xml 配置文件（如 client-1.12.xml），需要下载并通过 -Dlog4j.configurationFile 传入。
        // 不设置的话 Log4j 不初始化，所有日志（含崩溃堆栈）被丢弃。
        Path log4jXml = resolveLog4jConfig(vj, versionsDir, versionId);
        if (log4jXml != null) {
            profile.addJvmArg("-Dlog4j.configurationFile=" + log4jXml.toString());
            // Log4j 配置中 fileName="logs/latest.log" 是相对路径，
            // 需确保 gameDir/logs 目录存在且可写，否则 FileAppender 创建失败导致整个日志系统瘫痪。
            try {
                java.nio.file.Files.createDirectories(mcRoot.resolve("logs"));
            } catch (IOException ignored) {}
        }
        // LWJGL debug 模式（帮助诊断 native 加载问题）
        profile.addJvmArg("-Dorg.lwjgl.util.Debug=true");
        profile.addJvmArg("-Dorg.lwjgl.util.DebugLoader=true");

        // 内存参数（用 preferences 覆盖 config 默认值）
        profile.addJvmArg("-Xms" + preferences.getMinMemoryMb() + "m");
        profile.addJvmArg("-Xmx" + preferences.getMaxMemoryMb() + "m");

        // GC 类型（AikarFlags 已包含 -XX:+UseG1GC，启用 Aikar 时跳过避免重复）
        if (!preferences.isUseAikarFlags() &&
            preferences.getGcType() != null && !preferences.getGcType().isEmpty()) {
            profile.addJvmArg("-XX:+Use" + preferences.getGcType());
        }

        // Aikar's Flags（社区公认的 MC 优化 JVM 参数集）
        if (preferences.isUseAikarFlags()) {
            for (String f : AikarFlags.FLAGS) {
                profile.addJvmArg(f);
            }
        }

        // 版本 JSON 自带的 JVM 参数
        // 过滤掉运行时 Java 不支持的参数：
        //   --sun-misc-unsafe-memory-access=allow 是 Java 23+ (JEP 471) 引入的，
        //   Mojang 新版本 JSON 自带此参数，但 PMCL 使用 Java 21 启动会报
        //   "Unrecognized option" 导致 JVM 无法创建、游戏直接退出。
        for (String arg : vj.getJvmArgs()) {
            if (javaMajorVersion > 0 && javaMajorVersion < 23
                    && arg.startsWith("--sun-misc-unsafe-memory-access")) {
                continue;
            }
            profile.addJvmArg(replacePlaceholders(arg, versionId, mcRoot, librariesDir, assetsDir, versionsDir, gameDir, account, vj.getAssets()));
        }

        // 用户自定义 JVM 参数（最后追加，可覆盖前面）
        String custom = preferences.getCustomJvmArgs();
        if (custom != null && !custom.trim().isEmpty()) {
            for (String arg : custom.trim().split("\\s+")) {
                if (!arg.isEmpty()) profile.addJvmArg(arg);
            }
        }

        // 游戏参数
        for (String arg : vj.getGameArgs()) {
            profile.addGameArg(replacePlaceholders(arg, versionId, mcRoot, librariesDir, assetsDir, versionsDir, gameDir, account, vj.getAssets()));
        }

        // === 游戏通用行为（用户偏好） ===
        // 窗口分辨率
        if (preferences.getGameWindowWidth() > 0 && preferences.getGameWindowHeight() > 0) {
            profile.addGameArg("--width");
            profile.addGameArg(Integer.toString(preferences.getGameWindowWidth()));
            profile.addGameArg("--height");
            profile.addGameArg(Integer.toString(preferences.getGameWindowHeight()));
        }
        // 渲染器（MC 1.21+ 支持；OPENGL/VULKAN 注入 --renderer，AUTO 不注入）
        String renderer = preferences.getGameRenderer();
        if (renderer != null && !renderer.isEmpty() && !renderer.equalsIgnoreCase("AUTO")) {
            profile.addGameArg("--renderer");
            profile.addGameArg(renderer.toLowerCase());
        }
        // 全屏
        if (preferences.isGameFullscreen()) {
            profile.addGameArg("--fullscreen");
        }
        // 演示模式
        if (preferences.isGameDemo()) {
            profile.addGameArg("--demo");
        }
        // 自动连接服务器
        String serverHost = preferences.getGameServerHost();
        if (serverHost != null && !serverHost.isEmpty()) {
            profile.addGameArg("--server");
            profile.addGameArg(serverHost);
            profile.addGameArg("--port");
            profile.addGameArg(Integer.toString(preferences.getGameServerPort()));
        }

        return profile;
    }

    private void addClasspath(LaunchProfile profile, Set<String> seen, Path p) {
        String key = p.toAbsolutePath().toString();
        if (seen.add(key)) {
            profile.addClasspath(p);
        }
    }

    /**
     * 校验所有库文件是否存在，自动下载缺失的 jar。
     * <p>
     * 修复不完整的 MC 安装（如部分库 jar 丢失导致 NoClassDefFoundError）。
     * 包括 classpath 库和 native 库。需要 downloadManager，若为 null 则跳过下载只检查。
     */
    private void verifyLibraries(VersionJson vj, Path librariesDir) throws IOException {
        List<String> missing = new ArrayList<>();
        for (Library lib : vj.getLibraries()) {
            if (!lib.appliesToCurrentOs()) continue;

            // === MC 1.18+ 新格式：native 库以独立 library 条目存在 ===
            if (lib.getNameClassifier() != null && lib.getNameClassifier().startsWith("natives-")) {
                if (!lib.matchesCurrentNative()) continue;
                Path nativeJar = librariesDir.resolve(lib.getPath());
                if (Files.exists(nativeJar)) continue;
                // 尝试下载
                VersionJson.Artifact art = lib.getArtifact();
                if (art != null && art.getUrl() != null && !art.getUrl().isEmpty()
                        && downloadManager != null) {
                    try {
                        Files.createDirectories(nativeJar.getParent());
                        downloadManager.downloadTo(art.getUrl(), nativeJar);
                    } catch (IOException e) {
                        missing.add(lib.getName() + " (native): " + e.getMessage());
                    }
                } else if (art == null || art.getUrl() == null || art.getUrl().isEmpty()) {
                    // 无下载 URL，无法自动修复
                    missing.add(lib.getName() + " (native, 无下载URL)");
                }
                continue;
            }

            // === 主 artifact（classpath 库）===
            if (lib.getArtifact() != null) {
                Path libPath = librariesDir.resolve(lib.getPath());
                if (Files.exists(libPath)) continue;
                VersionJson.Artifact art = lib.getArtifact();
                if (art.getUrl() != null && !art.getUrl().isEmpty() && downloadManager != null) {
                    try {
                        Files.createDirectories(libPath.getParent());
                        downloadManager.downloadTo(art.getUrl(), libPath);
                    } catch (IOException e) {
                        missing.add(lib.getName() + ": " + e.getMessage());
                    }
                } else if (art.getUrl() == null || art.getUrl().isEmpty()) {
                    missing.add(lib.getName() + " (无下载URL)");
                }
            }

            // === 旧格式 native 库（有 natives 字段）===
            if (lib.isNativeLib() && lib.getNativeClassifier() != null) {
                Path nativeJar = librariesDir.resolve(
                        lib.getPathForClassifier(lib.getNativeClassifier()));
                if (Files.exists(nativeJar)) continue;
                VersionJson.Artifact nativeArt = lib.getNativeArtifact();
                if (nativeArt != null && nativeArt.getUrl() != null
                        && !nativeArt.getUrl().isEmpty() && downloadManager != null) {
                    try {
                        Files.createDirectories(nativeJar.getParent());
                        downloadManager.downloadTo(nativeArt.getUrl(), nativeJar);
                    } catch (IOException e) {
                        missing.add(lib.getName() + ":" + lib.getNativeClassifier()
                                + " (native): " + e.getMessage());
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("缺少库文件且无法自动下载:\n  - "
                    + String.join("\n  - ", missing));
        }
    }

    /**
     * 从 native jar 中提取本地库文件（.so/.dylib/.dll/.jnilib）到目标目录。
     * 跳过 META-INF 和目录，只提取本地库。
     * 本地库会被扁平化放到 targetDir 根目录（LWJGL 期望 java.library.path 直接包含 .dylib/.so/.dll）。
     */
    private void extractNatives(Path nativeJar, Path targetDir) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(nativeJar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // 跳过 META-INF
                if (name.startsWith("META-INF/")) continue;
                // 只提取本地库文件
                String lower = name.toLowerCase();
                if (!(lower.endsWith(".so") || lower.endsWith(".dylib") ||
                      lower.endsWith(".dll") || lower.endsWith(".jnilib"))) {
                    continue;
                }
                // 扁平化：只取文件名，去掉目录前缀（如 macos/arm64/org/lwjgl/liblwjgl.dylib → liblwjgl.dylib）
                String fileName = name;
                int lastSlash = name.lastIndexOf('/');
                if (lastSlash >= 0) fileName = name.substring(lastSlash + 1);
                Path target = targetDir.resolve(fileName);
                try (java.io.InputStream is = jar.getInputStream(entry);
                     java.io.OutputStream os = java.nio.file.Files.newOutputStream(target)) {
                    is.transferTo(os);
                }

                // Java 9+ 兼容：LWJGL 2.x 时代的 native 库扩展名是 .jnilib，
                // 但 Java 9+ 在 macOS 上只查找 .dylib（System.load 不识别 .jnilib）。
                // 自动创建 .dylib 副本（硬链接或复制），让 Java 9+ 能加载。
                if (lower.endsWith(".jnilib")) {
                    String dylibName = fileName.substring(0, fileName.length() - ".jnilib".length()) + ".dylib";
                    Path dylibTarget = targetDir.resolve(dylibName);
                    // 仅在 .dylib 不存在时创建（避免覆盖已有的 arm64 版本）
                    if (!java.nio.file.Files.exists(dylibTarget)) {
                        try {
                            // 优先用硬链接（零拷贝）
                            java.nio.file.Files.createLink(dylibTarget, target);
                        } catch (IOException linkEx) {
                            // 硬链接失败（跨文件系统等），退而用复制
                            java.nio.file.Files.copy(target, dylibTarget,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }
    }

    /**
     * LaunchWrapper 兼容层：让 Java 9+ 能启动 MC 1.6-1.12.2 的旧版本。
     * <p>
     * LaunchWrapper 的 Launch.<init> 中执行：
     *   ((URLClassLoader) getClass().getClassLoader()).getURLs()
     * Java 9+ 的 AppClassLoader 不再继承 URLClassLoader，强转失败。
     * -Djava.system.class.loader 方案失败：JDK 9+ 要求系统类加载器是 BuiltinClassLoader 子类。
     * <p>
     * 方案：用 PmclBootstrap 替代原主类作为入口点：
     * 1. 提取 PmclBootstrap.class 到 ~/.pmcl/boot/ 目录
     * 2. 将 boot 目录加入 classpath
     * 3. 将主类从 LaunchWrapper 改为 PmclBootstrap
     * 4. PmclBootstrap 内部创建 URLClassLoader 加载 LaunchWrapper 并调用其 main
     * 5. Launch.class 的 getClass().getClassLoader() 返回 URLClassLoader，强转成功
     */
    private void applyLaunchWrapperCompatLayer(LaunchProfile profile, Set<String> seen) throws IOException {
        // 1. 提取 PmclBootstrap.class 到 boot 目录
        Path bootDir = config.getWorkDir().resolve("boot");
        Path classFile = bootDir.resolve("com/pmcl/core/boot/PmclBootstrap.class");
        java.nio.file.Files.createDirectories(classFile.getParent());

        byte[] classBytes = loadClassBytes("com.pmcl.core.boot.PmclBootstrap");
        if (classBytes == null || classBytes.length < 100) {
            throw new IOException("无法加载 PmclBootstrap.class 字节码"
                    + (classBytes == null ? "(null)" : "(" + classBytes.length + " 字节)"));
        }
        java.nio.file.Files.write(classFile, classBytes,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        System.err.println("[PMCL 兼容层] PmclBootstrap.class 已提取: " + classFile
                + " (" + classBytes.length + " 字节)");

        // 2. 将 boot 目录加入 classpath（JVM 能找到 PmclBootstrap 类）
        addClasspath(profile, seen, bootDir);

        // 3. 记录原主类到系统属性，PmclBootstrap 会读取此属性决定加载哪个类
        String originalMainClass = profile.getMainClass();
        if (originalMainClass != null && !originalMainClass.isEmpty()) {
            profile.addJvmArg("-Dpmcl.launch.mainclass=" + originalMainClass);
        }

        // 4. 将主类改为 PmclBootstrap
        profile.setMainClass("com.pmcl.core.boot.PmclBootstrap");

        // 5. 注入 --add-opens（旧版本通过反射访问 Java 内部 API，Java 9+ 模块系统默认禁止）
        String[] opens = {
                "java.base/java.lang",
                "java.base/java.lang.reflect",
                "java.base/java.lang.invoke",
                "java.base/java.util",
                "java.base/java.io",
                "java.base/java.net",
                "java.base/sun.nio.ch",
                "java.base/sun.security.action",
                "java.base/sun.reflect.annotation",
                "java.desktop/java.awt",
                "java.desktop/sun.awt",
                "java.desktop/sun.java2d",
                "java.desktop/sun.awt.image",
                "java.desktop/sun.font"
        };
        for (String pkg : opens) {
            profile.addJvmArg("--add-opens=" + pkg + "=ALL-UNNAMED");
        }
    }

    /**
     * 加载指定类的字节码。
     * 尝试多种 classloader 和 CodeSource 方式定位 class 文件。
     */
    private byte[] loadClassBytes(String className) throws IOException {
        String resourcePath = className.replace('.', '/') + ".class";

        // 策略1：通过 class.getResourceAsStream 加载
        try {
            Class<?> clazz = Class.forName(className);
            try (java.io.InputStream is = clazz.getResourceAsStream(
                    "/" + resourcePath)) {
                if (is != null) return is.readAllBytes();
            }
            // 策略2：相对路径
            try (java.io.InputStream is = clazz.getResourceAsStream(
                    resourcePath.substring(resourcePath.lastIndexOf('/') + 1))) {
                if (is != null) return is.readAllBytes();
            }
        } catch (ClassNotFoundException ignored) {}

        // 策略3：通过当前类的 ClassLoader 加载
        try (java.io.InputStream is = getClass().getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is != null) return is.readAllBytes();
        } catch (Throwable ignored) {}

        // 策略4：通过线程上下文 ClassLoader 加载
        try (java.io.InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is != null) return is.readAllBytes();
        } catch (Throwable ignored) {}

        // 策略5：通过系统 ClassLoader 加载
        try (java.io.InputStream is = ClassLoader.getSystemResourceAsStream(resourcePath)) {
            if (is != null) return is.readAllBytes();
        } catch (Throwable ignored) {}

        // 策略6：通过 CodeSource 定位 jar/目录，直接读取 class 文件
        try {
            Class<?> clazz = Class.forName(className);
            java.security.CodeSource cs = clazz.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                java.net.URL url = cs.getLocation();
                if ("jar".equals(url.getProtocol())) {
                    String path = url.getPath();
                    int bang = path.indexOf('!');
                    if (bang > 0) {
                        String jarPath = path.substring(0, bang);
                        if (jarPath.startsWith("file:")) jarPath = jarPath.substring(5);
                        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
                            java.util.jar.JarEntry entry = jar.getJarEntry(resourcePath);
                            if (entry != null) {
                                try (java.io.InputStream is = jar.getInputStream(entry)) {
                                    return is.readAllBytes();
                                }
                            }
                        }
                    }
                } else if ("file".equals(url.getProtocol())) {
                    Path classFile = Path.of(url.toURI()).resolve(resourcePath);
                    if (java.nio.file.Files.exists(classFile)) {
                        return java.nio.file.Files.readAllBytes(classFile);
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * 从版本 JSON 的 logging.client 字段解析 log4j2 配置文件，
     * 若本地不存在则下载。返回配置文件路径，无 logging 字段返回 null。
     * 配置文件中的相对路径 logs/latest.log 会被改写为基于 gameDir 的绝对路径，
     * 避免 macOS 权限问题导致 FileAppender 创建失败。
     */
    private Path resolveLog4jConfig(VersionJson vj, Path versionsDir, String versionId) {
        try {
            com.google.gson.JsonObject raw = vj.getRawJson();
            if (!raw.has("logging")) return null;
            JsonObject logging = raw.getAsJsonObject("logging");
            if (!logging.has("client")) return null;
            JsonObject client = logging.getAsJsonObject("client");
            if (!client.has("file")) return null;
            JsonObject file = client.getAsJsonObject("file");
            String fileId = file.has("id") && !file.get("id").isJsonNull() ? file.get("id").getAsString() : "";
            String url = file.has("url") && !file.get("url").isJsonNull() ? file.get("url").getAsString() : "";

            // 存储到 versions/{id}/{fileId}（如 client-1.12.xml）
            Path target = versionsDir.resolve(versionId).resolve(fileId);
            if (!java.nio.file.Files.exists(target) || java.nio.file.Files.size(target) == 0) {
                // 下载
                okhttp3.OkHttpClient http = downloadManager != null ? downloadManager.httpClient()
                    : new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .readTimeout(java.time.Duration.ofSeconds(30))
                        .build();
                okhttp3.Request req = new okhttp3.Request.Builder().url(url).get().build();
                try (okhttp3.Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) return null;
                    if (resp.body() == null) return null;
                    java.nio.file.Files.createDirectories(target.getParent());
                    try (java.io.InputStream is = resp.body().byteStream();
                         java.io.OutputStream os = java.nio.file.Files.newOutputStream(target)) {
                        is.transferTo(os);
                    }
                }
            }
            // 改写配置文件中的相对路径为绝对路径（基于 gameDir）
            // client-1.12.xml 中 fileName="logs/latest.log" 和 filePattern="logs/..."
            // 需要改为绝对路径，否则 macOS 权限问题导致 FileAppender 创建失败
            Path mcRoot = resolveMcRoot(versionId);
            Path absLogs = mcRoot.resolve("logs").toAbsolutePath();
            String content = java.nio.file.Files.readString(target);
            // 简单替换：把 "logs/latest.log" 和 "logs/" 改为绝对路径
            content = content.replace("fileName=\"logs/latest.log\"",
                    "fileName=\"" + absLogs.resolve("latest.log") + "\"");
            content = content.replace("filePattern=\"logs/",
                    "filePattern=\"" + absLogs + "/");
            // 写回
            java.nio.file.Files.writeString(target, content);
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    private VersionJson loadVersionJson(String versionId) throws IOException {
        Path jsonPath = findVersionJson(versionId);
        if (jsonPath == null) {
            throw new IOException("版本未安装: " + versionId +
                "（已查找: " + getVersionsDirs() + "）");
        }
        String json = Files.readString(jsonPath);
        VersionJson vj = VersionJson.parse(json);

        if (vj.getInheritsFrom() != null && !vj.getInheritsFrom().equals(versionId)) {
            VersionJson parent = loadVersionJson(vj.getInheritsFrom());
            com.google.gson.JsonObject childObj = vj.getRawJson();
            if (!childObj.has("mainClass") && parent.getMainClass() != null) {
                childObj.addProperty("mainClass", parent.getMainClass());
            }
            if (!childObj.has("assets") && parent.getAssets() != null) {
                childObj.addProperty("assets", parent.getAssets());
            }
            if (!childObj.has("assetIndex") && parent.getRawJson().has("assetIndex")) {
                childObj.add("assetIndex", parent.getRawJson().get("assetIndex"));
            }
            if (!childObj.has("downloads") && parent.getRawJson().has("downloads")) {
                childObj.add("downloads", parent.getRawJson().get("downloads"));
            }
            // 合并 arguments（game + jvm）：子版本的参数在前，父版本的在后，
            // 缺失的必填参数（如 --version/--accessToken）会从父版本补齐
            if (parent.getRawJson().has("arguments")) {
                com.google.gson.JsonObject parentArgs = parent.getRawJson().getAsJsonObject("arguments");
                if (!childObj.has("arguments")) {
                    // 子版本完全没有 arguments，直接用父版本的整体
                    childObj.add("arguments", parentArgs);
                } else {
                    com.google.gson.JsonObject childArgs = childObj.getAsJsonObject("arguments");
                    // 合并 game 数组
                    if (parentArgs.has("game")) {
                        com.google.gson.JsonArray mergedGame = new com.google.gson.JsonArray();
                        if (childArgs.has("game")) {
                            for (var e : childArgs.getAsJsonArray("game")) mergedGame.add(e);
                        }
                        for (var e : parentArgs.getAsJsonArray("game")) mergedGame.add(e);
                        childArgs.add("game", mergedGame);
                    }
                    // 合并 jvm 数组
                    if (parentArgs.has("jvm")) {
                        com.google.gson.JsonArray mergedJvm = new com.google.gson.JsonArray();
                        if (childArgs.has("jvm")) {
                            for (var e : childArgs.getAsJsonArray("jvm")) mergedJvm.add(e);
                        }
                        for (var e : parentArgs.getAsJsonArray("jvm")) mergedJvm.add(e);
                        childArgs.add("jvm", mergedJvm);
                    }
                }
            }
            // 合并旧格式 minecraftArguments（子版本没有时用父版本）
            if (!childObj.has("minecraftArguments") && parent.getRawJson().has("minecraftArguments")) {
                childObj.add("minecraftArguments", parent.getRawJson().get("minecraftArguments"));
            }
            // 继承 javaVersion（子版本未指定时用父版本的，alpha/beta 整合包依赖此字段选 Java 8）
            if (!childObj.has("javaVersion") && parent.getRawJson().has("javaVersion")) {
                childObj.add("javaVersion", parent.getRawJson().get("javaVersion"));
            }
            com.google.gson.JsonArray merged = new com.google.gson.JsonArray();
            java.util.Set<String> childNames = new java.util.HashSet<>();
            if (childObj.has("libraries")) {
                for (var e : childObj.getAsJsonArray("libraries")) {
                    merged.add(e);
                    JsonObject libObj = e.getAsJsonObject();
                    if (libObj.has("name") && !libObj.get("name").isJsonNull()) {
                        childNames.add(libObj.get("name").getAsString());
                    }
                }
            }
            if (parent.getRawJson().has("libraries")) {
                for (var e : parent.getRawJson().getAsJsonArray("libraries")) {
                    JsonObject libObj = e.getAsJsonObject();
                    if (!libObj.has("name") || libObj.get("name").isJsonNull()) continue;
                    String name = libObj.get("name").getAsString();
                    if (!childNames.contains(name)) merged.add(e);
                }
            }
            childObj.add("libraries", merged);
            vj = VersionJson.parse(childObj.toString());
        }
        return vj;
    }

    /**
     * 替换参数占位符。使用版本实际所在 Minecraft 根目录的资源路径，注入真实账号信息。
     * 注意：${game_directory} 用 gameDir（整合包为版本目录本身），而非 mcRoot。
     */
    private String replacePlaceholders(String arg, String versionId,
                                       Path mcRoot, Path librariesDir,
                                       Path assetsDir, Path versionsDir,
                                       Path gameDir,
                                       Account account,
                                       String assetsIndex) {
        // assetsIndex 为空（旧版本 JSON 无 assets 字段）时回退到 versionId
        String effectiveAssetsIndex = (assetsIndex == null || assetsIndex.isEmpty())
                ? versionId : assetsIndex;
        return arg
                .replace("${natives_directory}",
                        versionsDir.resolve(versionId).resolve("natives").toString())
                .replace("${launcher_name}", "PMCL")
                .replace("${launcher_version}", "1.0.0")
                .replace("${classpath_separator}",
                        System.getProperty("path.separator"))
                .replace("${library_directory}",
                        librariesDir.toString())
                .replace("${game_directory}",
                        gameDir.toString())
                .replace("${version_name}", versionId)
                .replace("${assets_root}",
                        assetsDir.toString())
                .replace("${assets_index_name}", effectiveAssetsIndex)
                .replace("${user_type}", "msa")
                .replace("${auth_player_name}", account.getUsername())
                .replace("${auth_uuid}", account.getUuid())
                .replace("${auth_access_token}", account.getAccessToken())
                .replace("${auth_session}", account.getAccessToken())
                // alpha/beta 的 minecraftArguments 使用 ${session_id}（而非 ${auth_session}）
                // 不替换会传入字面量占位符导致会话参数无效，游戏启动后立即崩溃
                .replace("${session_id}", account.getAccessToken())
                // 1.7.10 及更早版本可能引用 ${user_properties}，传空 JSON 数组
                .replace("${user_properties}", "{}")
                // 极旧版本可能引用 ${game_assets}，指向 assets 目录
                .replace("${game_assets}", assetsDir.toString())
                .replace("${clientid}", "")
                .replace("${auth_xuid}", "")
                .replace("${version_type}", "PMCL");
    }
}
