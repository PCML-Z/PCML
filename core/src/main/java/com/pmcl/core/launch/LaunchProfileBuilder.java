package com.pmcl.core.launch;

import com.google.gson.JsonObject;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.auth.Account;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.Library;
import com.pmcl.core.install.VersionJson;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.version.VersionManager;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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

    /** 匹配 ${...} 占位符，用于单次扫描替换防止注入 */
    private static final java.util.regex.Pattern PLACEHOLDER_PATTERN =
            java.util.regex.Pattern.compile("\\$\\{[^}]+\\}");

    private final LauncherConfig config;
    private final Preferences preferences;
    private final DownloadManager downloadManager;

    /** 主菜单背景视频处理器（可选，由 UI 层注入 video 模块实现）。null 时该功能降级不可用 */
    private com.pmcl.core.gamecontent.MenuBackgroundProvider menuBackgroundProvider;

    /** authlib-injector 管理器（皮肤站账号启动时注入 Java Agent） */
    private final com.pmcl.core.auth.AuthlibInjectorManager authlibInjectorManager =
            new com.pmcl.core.auth.AuthlibInjectorManager();

    public LaunchProfileBuilder(LauncherConfig config, Preferences preferences) {
        this(config, preferences, null);
    }

    public LaunchProfileBuilder(LauncherConfig config, Preferences preferences,
                                DownloadManager downloadManager) {
        this.config = config;
        this.preferences = preferences;
        this.downloadManager = downloadManager;
    }

    /** 注入主菜单背景视频处理器。UI 层启动时调用，传入 video 模块的 JavaCV 实现 */
    public void setMenuBackgroundProvider(
            com.pmcl.core.gamecontent.MenuBackgroundProvider provider) {
        this.menuBackgroundProvider = provider;
    }

    /**
     * 获取所有需要查找的 versions 目录。
     * 合并三个来源：.pmcl/versions + 全部系统默认 Minecraft 目录 + 用户自定义根目录。
     * 修复：原代码仅扫描第一个系统默认目录（detectDefaultMinecraftVersionsDir），
     * 导致 macOS 上同时有官方目录和 HMCL 目录时，启动 HMCL 目录中的版本会失败。
     */
    private List<Path> getVersionsDirs() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(config.getVersionsDir());
        // 全部系统默认 Minecraft versions 目录（macOS 可能同时有官方 + HMCL 两个）
        for (Path mcDir : VersionManager.detectAllMinecraftVersionsDirs()) {
            if (!mcDir.equals(config.getVersionsDir()) && !dirs.contains(mcDir)) {
                dirs.add(mcDir);
            }
        }
        // 用户自定义的额外 Minecraft 根目录
        if (preferences != null) {
            for (String root : preferences.getExtraMinecraftRoots()) {
                try {
                    Path versionsDir = java.nio.file.Paths.get(root).resolve("versions");
                    if (java.nio.file.Files.isDirectory(versionsDir) && !dirs.contains(versionsDir)) {
                        dirs.add(versionsDir);
                    }
                } catch (Throwable t) {
                    System.err.println("[LaunchProfileBuilder] 无效的根目录路径: " + root + " - " + t.getMessage());
                }
            }
        }
        return dirs;
    }

    /**
     * 在所有已知 versions 目录中查找版本 JSON，返回首个找到的路径。
     */
    private Path findVersionJson(String versionId) {
        if (versionId == null || versionId.contains("..") || versionId.contains("/") || versionId.contains("\\") || versionId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法版本 ID: " + versionId);
        }
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
        if (versionId == null || versionId.contains("..") || versionId.contains("/") || versionId.contains("\\") || versionId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法版本 ID: " + versionId);
        }
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
     * <p>
     * 优先级：
     * <ol>
     *   <li>版本隔离开启：{@code ~/.pmcl/instances/<versionId>/}，自动创建 mods/saves/config/
     *       resourcepacks/shaderpacks/screenshots/logs 子目录</li>
     *   <li>整合包（版本目录内含 mods/）：版本目录本身</li>
     *   <li>普通版本：mcRoot（与 libraries/assets 同级）</li>
     * </ol>
     */
    private Path resolveGameDir(String versionId, Path mcRoot) {
        if (versionId == null || versionId.contains("..") || versionId.contains("/") || versionId.contains("\\") || versionId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法版本 ID: " + versionId);
        }
        // 版本隔离：每个版本独立的游戏目录
        if (preferences.isVersionIsolation()) {
            Path instanceDir = config.getWorkDir().resolve("instances").resolve(versionId);
            // 自动创建子目录
            try {
                java.nio.file.Files.createDirectories(instanceDir);
                for (String sub : new String[]{"mods", "saves", "config", "resourcepacks",
                        "shaderpacks", "screenshots", "logs"}) {
                    java.nio.file.Files.createDirectories(instanceDir.resolve(sub));
                }
            } catch (IOException e) {
                throw new RuntimeException("无法创建版本隔离目录: " + instanceDir, e);
            }
            return instanceDir;
        }
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
     * 按实例启动：使用 baseVersionId 的 JSON/jar/库文件，但 gameDir 指向实例目录。
     * <p>
     * 实例始终使用独立目录（忽略全局 versionIsolation 开关），因为实例的本质就是隔离。
     *
     * @param baseVersionId  基础 Minecraft 版本 ID（如 "1.20.4"）
     * @param instanceDir    实例目录路径（gameDir）
     * @param account        账号
     * @param javaMajorVersion Java 主版本号
     * @param javaArch       Java 架构
     */
    public LaunchProfile buildInstance(String baseVersionId, java.nio.file.Path instanceDir,
                                       Account account, int javaMajorVersion, String javaArch) throws IOException {
        if (javaArch != null && !javaArch.isEmpty()) {
            com.pmcl.core.install.Library.setArchOverride(javaArch);
        }
        try {
            return buildInternal(baseVersionId, account, javaMajorVersion, instanceDir);
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
        return buildInternal(versionId, account, javaMajorVersion, null);
    }

    /**
     * 实际构造启动配置的内部方法。
     * @param javaMajorVersion 实际使用的 Java 主版本号（如 8/17/21），0 表示未知。
     * @param instanceDir 非空时表示按实例启动，gameDir 固定为此目录（忽略 versionIsolation）。
     */
    private LaunchProfile buildInternal(String versionId, Account account, int javaMajorVersion,
                                        java.nio.file.Path instanceDir) throws IOException {
        // P1-4: 在线账号校验 accessToken 非空，避免空 token 传给游戏导致服务器踢人。
        // 离线/GitHub 账号允许空 token（离线模式，UUID 作为玩家标识）。
        if (account != null) {
            Account.AccountType at = account.getType();
            if (at == Account.AccountType.MICROSOFT || at == Account.AccountType.YGGDRASIL) {
                String tok = account.getAccessToken();
                if (tok == null || tok.isEmpty()) {
                    throw new IOException("账号 " + account.getUsername() + "（" + at
                            + "）的 accessToken 为空，请重新登录");
                }
            } else if (at == Account.AccountType.OFFLINE || at == Account.AccountType.GITHUB) {
                System.err.println("[LaunchProfileBuilder] 账号类型=" + at
                        + "（" + account.getUsername() + "）：无法通过正版/联机会话校验；"
                        + "多人 online-mode / Realms / enforce-secure-profile 将失败，请改用微软账号。");
            }
        }
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

        // 校验并自动下载缺失/损坏的库文件（有 SHA-1 时会复检）
        verifyLibraries(vj, librariesDir);
        verifyClientJar(vj, versionsDir);
        verifyAssets(vj, assetsDir);

        // 设置游戏工作目录：实例启动时固定为实例目录，否则按 versionIsolation/整合包逻辑推导
        Path gameDir;
        if (instanceDir != null) {
            // 实例启动：始终使用实例目录，自动创建子目录
            gameDir = instanceDir;
            try {
                java.nio.file.Files.createDirectories(instanceDir);
                for (String sub : new String[]{"mods", "saves", "config", "resourcepacks",
                        "shaderpacks", "screenshots", "logs"}) {
                    java.nio.file.Files.createDirectories(instanceDir.resolve(sub));
                }
            } catch (IOException e) {
                throw new RuntimeException("无法创建实例目录: " + instanceDir, e);
            }
        } else {
            gameDir = resolveGameDir(versionId, mcRoot);
        }
        profile.setGameDir(gameDir);

        Set<String> seen = new LinkedHashSet<>();
        // client jar: walk inheritsFrom chain
        // - Fabric 子版本目录只有 JSON 没有 jar；真正的游戏 jar 在父版本（原版 MC）目录
        // - 旧版 Forge（LaunchWrapper）：子版本补丁 jar + 父版本原版 jar 都需加入 classpath
        // - 现代 Forge/NeoForge（BootstrapLauncher）：游戏由 libraries 中的 client-*-srg 以
        //   named module "minecraft" 提供；若再把 inheritsFrom 的 1.21.x.jar 放进 -cp，
        //   会变成自动模块 _1._21._1，与 minecraft 导出同一包 → ResolutionException
        // - 原版版本：自身 jar 即可，无 inheritsFrom
        String mainClass = vj.getMainClass() != null ? vj.getMainClass() : "";
        boolean usesBootstrapLauncher = mainClass.toLowerCase(java.util.Locale.ROOT)
                .contains("bootstraplauncher");
        java.util.Set<String> visitedVer = new java.util.HashSet<>();
        String currentVer = versionId;
        VersionJson currentVj = vj;
        while (currentVer != null && !currentVer.isEmpty()
                && visitedVer.add(currentVer)) {
            Path jar = findVersionJar(currentVer);
            if (jar != null) {
                addClasspath(profile, seen, jar);
            }
            // BootstrapLauncher：只加入当前版本 jar，不要继续向上挂原版 client jar
            if (usesBootstrapLauncher) break;
            String parent = currentVj.getInheritsFrom();
            if (parent == null || parent.isEmpty() || parent.equals(currentVer)) break;
            // 加载父版本 JSON 以获取更上一层的 inheritsFrom（处理嵌套继承）
            try {
                currentVj = loadVersionJson(parent);
            } catch (IOException e) {
                throw new IOException("无法加载父版本 JSON（inheritsFrom=" + parent
                        + "），classpath 可能不完整: " + e.getMessage(), e);
            }
            currentVer = parent;
        }

        // 解压 natives 到 versions/{id}/natives/ 目录
        // 若用户配置了自定义 natives 目录，则跳过提取直接使用该目录
        String customNatives = preferences.getCustomNativesPath();
        Path nativesDir;
        boolean useCustomNatives = false;
        if (customNatives != null && !customNatives.isEmpty()) {
            Path customDir = java.nio.file.Paths.get(customNatives);
            if (java.nio.file.Files.isDirectory(customDir)) {
                nativesDir = customDir;
                useCustomNatives = true;
            } else {
                System.err.println("[LaunchProfileBuilder] 自定义 natives 目录不存在，回退到默认提取: " + customNatives);
                nativesDir = versionsDir.resolve(versionId).resolve("natives");
            }
        } else {
            nativesDir = versionsDir.resolve(versionId).resolve("natives");
        }

        // natives 提取指纹缓存：用 .natives_fingerprint 记录上次提取时各 native jar 的 mtime+size，
        // 文件未变更则跳过全量解压。1.21.8 有 ~10 个 LWJGL native jar，每次启动全量解压耗时显著，
        // 而绝大多数情况下 native jar 在版本安装后不会变化。
        // 注意：指纹文件放在 nativesDir 外层（versions/{id}/ 下），避免被清空操作删除。
        Path nativesFpFile = versionsDir.resolve(versionId).resolve(".natives_fingerprint");
        java.util.List<Path> nativeJarsToExtract = new java.util.ArrayList<>();
        java.util.Map<String, String> currFp = new java.util.LinkedHashMap<>();

        for (Library lib : vj.getLibraries()) {
            if (!lib.appliesToCurrentOs()) continue;

            // === MC 1.18+ 新格式：native 库以独立 library 条目存在（name 带 :natives-xxx）===
            if (lib.getNameClassifier() != null && lib.getNameClassifier().startsWith("natives-")) {
                // 只处理匹配当前平台的 native 条目
                if (!lib.matchesCurrentNative()) continue;
                if (!useCustomNatives) {
                    Path nativeJar = librariesDir.resolve(lib.getPath());
                    if (java.nio.file.Files.exists(nativeJar)) {
                        nativeJarsToExtract.add(nativeJar);
                        currFp.put(nativeJar.toString(), nativeFingerprint(nativeJar));
                    }
                }
                continue;
            }

            // === 旧格式：natives 字段 + classifiers ===
            // 主 artifact 加入 classpath
            // - Mojang：downloads.artifact
            // - Fabric/Forge/NeoForge：顶层 url
            // - OptiFine 等本地库：仅有 name（Patcher 已写入 libraries/），文件存在则加入
            Path libPath = librariesDir.resolve(lib.getPath());
            if (lib.getArtifact() != null || !lib.getUrl().isEmpty()) {
                addClasspath(profile, seen, libPath);
            } else if (lib.getName() != null && !lib.getName().isBlank()
                    && java.nio.file.Files.isRegularFile(libPath)) {
                addClasspath(profile, seen, libPath);
            }
            // native 库：解压到 nativesDir（自定义 natives 模式下跳过）
            if (lib.isNativeLib() && !useCustomNatives) {
                VersionJson.Artifact nativeArt = lib.getNativeArtifact();
                if (nativeArt == null) continue;
                String classifier = lib.getNativeClassifier();
                Path nativeJar = librariesDir.resolve(lib.getPathForClassifier(classifier));
                if (java.nio.file.Files.exists(nativeJar)) {
                    nativeJarsToExtract.add(nativeJar);
                    currFp.put(nativeJar.toString(), nativeFingerprint(nativeJar));
                }
            }
        }

        // 比对指纹：与上次提取一致且目录仍有 native 库文件时才跳过；
        // 目录被清空/半删除时强制重解压（杀毒/手动清理后仍可启动）
        boolean nativesChanged = !currFp.equals(readNativesFingerprint(nativesFpFile));
        boolean nativesHealthy = nativesDirLooksHealthy(nativesDir, nativeJarsToExtract);
        if (!useCustomNatives && (nativesChanged || !nativesHealthy)) {
            try {
                java.nio.file.Files.createDirectories(nativesDir);
                // 清空 natives 目录（避免旧库残留）
                try (var stream = java.nio.file.Files.list(nativesDir)) {
                    stream.forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
                for (Path nativeJar : nativeJarsToExtract) {
                    extractNatives(nativeJar, nativesDir);
                }
            } catch (IOException e) {
                throw new IOException("无法提取 native 库到: " + nativesDir, e);
            }
            writeNativesFingerprint(nativesFpFile, currFp);
        }

        // === 自动检测并注入 Kotlin stdlib ===
        // 当 mods 目录中的 mod 使用 Kotlin 编写时，需要 kotlin-stdlib 在游戏 classpath 上，
        // 否则游戏启动后会报 NoClassDefFoundError: kotlin/collections/MapWithDefault 等错误。
        injectKotlinStdlibIfNeeded(profile, seen, gameDir, librariesDir);

        // 设置 java.library.path 指向 natives 目录
        profile.addJvmArg("-Djava.library.path=" + nativesDir.toString());
        // LWJGL 3 也支持此参数
        profile.addJvmArg("-Dorg.lwjgl.librarypath=" + nativesDir.toString());
        // Java 16+ 需要显式开启 native access，否则 LWJGL 加载本地库会警告/失败
        // 注意：此参数 Java 8 不识别，注入会导致 JVM 直接报错退出（alpha/beta 必需 Java 8）
        if (javaMajorVersion >= 16) {
            profile.addJvmArg("--enable-native-access=ALL-UNNAMED");
        }

        // 老版本 + 澪模式兼容性：忽略 JVM 不识别的 -XX 选项，避免启动直接失败
        // MioFlags 的 UseProfiledLoopPredicate 仅 JDK 16+ 支持；Java 8 老版本遇不识别的
        // -XX 选项会报 "Unrecognized VM option" 直接退出。必须在任何可能不识别的 -XX 参数
        // 之前注入此选项。老版本(lwjgl2Era/Java<11)和澪模式均依赖此保护确保稳定启动。
        if ((javaMajorVersion > 0 && javaMajorVersion < 11)
                || (preferences != null && preferences.isMioModeEnabled())) {
            profile.addJvmArg("-XX:+IgnoreUnrecognizedVMOptions");
        }

        // === 转译层：RetroWrapper + FrankenLWJGL，使 Java 21+（含 Apple Silicon arm64）可跑旧版 ===
        String translationMode = preferences != null
                ? preferences.getLegacyTranslationMode() : "AUTO";
        String effectiveArch = com.pmcl.core.install.Library.getArchOverride();
        if (effectiveArch == null || effectiveArch.isBlank()) {
            effectiveArch = System.getProperty("os.arch", "");
        }
        // 让 VersionJson 用游戏 Java 架构匹配 arch-specific 的 JVM 参数 rules，
        // 避免启动器架构（arm64）与游戏 Java 架构（x86_64 Rosetta）不一致时选错参数
        vj.setGameJavaArch(effectiveArch);

        // MC 1.13–1.16：用 LWJGL 3.3.3 的 GLFW 覆盖旧版，修复 Apple Silicon /
        // 新 macOS 上 “Failed to find service port for display”；
        // 并注入 javaagent 跳过 glfwSetWindowIcon（否则会报 65548）。
        if (MacOsGlfwFix.shouldApply(versionId) && !useCustomNatives) {
            try {
                Path glfw = MacOsGlfwFix.ensure(
                        nativesDir, config.getWorkDir(), downloadManager, effectiveArch);
                if (glfw != null) {
                    profile.addJvmArg("-Dorg.lwjgl.glfw.libname=" + glfw.toAbsolutePath());
                }
                Path agent = MacOsGlfwFix.ensureIconFixAgent(config.getWorkDir());
                profile.addJavaAgent(agent.toAbsolutePath().toString(), null);
            } catch (Exception e) {
                System.err.println("[PMCL] 现代 GLFW / icon-fix 注入失败（窗口可能无法创建）: "
                        + e.getMessage());
            }
        }

        int requiredJava = vj.getJavaVersion();
        if (requiredJava <= 0 && !vj.getRawJson().has("arguments")) requiredJava = 8;
        boolean useTranslation = RetroWrapperSupport.shouldApply(
                translationMode, requiredJava, javaMajorVersion,
                vj.getMainClass(), effectiveArch, versionId);
        if (useTranslation) {
            try {
                RetroWrapperSupport.apply(profile, config.getWorkDir(), nativesDir,
                        downloadManager, seen, javaMajorVersion, effectiveArch, versionId);
            } catch (Exception e) {
                System.err.println("[PMCL 转译] RetroWrapper 注入失败，回退兼容层: " + e.getMessage());
                useTranslation = false;
            }
        }

        // === 兼容层：让 Java 9+ 能启动使用 LaunchWrapper 的旧版本（MC 1.6-1.12.2） ===
        // LaunchWrapper 将系统类加载器强转为 URLClassLoader，Java 9+ 的 AppClassLoader
        // 不再继承 URLClassLoader，导致 ClassCastException 崩溃。
        boolean usesLaunchWrapper = (vj.getMainClass() != null
                && vj.getMainClass().contains("launchwrapper"))
                || (profile.getMainClass() != null
                && profile.getMainClass().contains("launchwrapper"))
                || useTranslation;
        if (usesLaunchWrapper && javaMajorVersion >= 9) {
            // OptiFine 等仍捆绑 LaunchWrapper 1.12；先换成 Java 9+ 兼容版，再套 PmclBootstrap
            try {
                RetroWrapperSupport.ensureModernLaunchWrapper(
                        profile, config.getWorkDir(), downloadManager, seen);
            } catch (Exception e) {
                System.err.println("[PMCL 兼容层] 替换 Java 9+ LaunchWrapper 失败: " + e.getMessage());
            }
            applyLaunchWrapperCompatLayer(profile, seen);
        }

        // macOS + LWJGL3/GLFW：必须在主线程创建窗口。
        // LWJGL2（~1.12 / alpha）在独立的 "Minecraft main thread" 上 Display.create；
        // 若加 -XstartOnFirstThread，会在 MacOSXDisplay.createWindow 永久卡住。
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")
                && !RetroWrapperSupport.isLwjgl2Era(versionId)) {
            profile.addJvmArg("-XstartOnFirstThread");
        }

        // Log4j2 配置：MC 1.13+ 的版本 JSON 有 logging.client 字段，
        // 指定 log4j2-xml 配置文件（如 client-1.12.xml），需要下载并通过 -Dlog4j.configurationFile 传入。
        // 不设置的话 Log4j 不初始化，所有日志（含崩溃堆栈）被丢弃。
        Path log4jXml = resolveLog4jConfig(vj, versionsDir, versionId, gameDir);
        // alpha/beta/1.6-1.12.2 无 logging.client 字段，但 LaunchWrapper 的 LogWrapper 引用 log4j
        // 类（ensureLog4jForLaunchWrapper 已注入 jar）；不注入配置则 log4j 不初始化，崩溃堆栈被丢弃。
        if (log4jXml == null && usesLaunchWrapper) {
            log4jXml = ensureDefaultLog4jConfig(gameDir);
        }
        if (log4jXml != null) {
            profile.addJvmArg("-Dlog4j.configurationFile=" + log4jXml.toString());
            // Log4j 配置中 fileName 已改写为 gameDir/logs 绝对路径，目录必须存在且可写。
            try {
                java.nio.file.Files.createDirectories(gameDir.resolve("logs"));
            } catch (IOException e) {
                System.err.println("[LaunchProfile] 无法创建 logs 目录（Log4j FileAppender 可能失效）: "
                        + gameDir.resolve("logs") + " — " + e.getMessage());
            }
        }
        // LWJGL debug 默认关闭（噪音/性能）；需要时加 JVM 属性 -Dpmcl.lwjgl.debug=true
        if (Boolean.getBoolean("pmcl.lwjgl.debug")) {
            profile.addJvmArg("-Dorg.lwjgl.util.Debug=true");
            profile.addJvmArg("-Dorg.lwjgl.util.DebugLoader=true");
        }

        // 内存参数（用 preferences 覆盖 config 默认值）
        profile.addJvmArg("-Xms" + preferences.getMinMemoryMb() + "m");
        profile.addJvmArg("-Xmx" + preferences.getMaxMemoryMb() + "m");

        // GC 类型（仅未启用 Aikar Flags 时注入，避免冲突）
        // 澪模式 ZGC 开启时也跳过（避免 -XX:+UseG1GC 与 ZGC 冲突）
        boolean mioZgc = preferences.isMioModeEnabled() && preferences.isMioModeZgc();
        if (!preferences.isUseAikarFlags() && !mioZgc &&
            preferences.getGcType() != null && !preferences.getGcType().isEmpty()) {
            profile.addJvmArg("-XX:+Use" + preferences.getGcType());
        }

        // Aikar's Flags（社区公认的 MC 优化 JVM 参数集）
        if (preferences.isUseAikarFlags() && !mioZgc) {
            for (String f : AikarFlags.FLAGS) {
                profile.addJvmArg(f);
            }
        }

        // 澪模式 L1：JVM 激进参数（在 Aikar 之后、customJvmArgs 之前，用户仍可覆盖）
        if (preferences.isMioModeEnabled() && preferences.isMioModeJvm()) {
            int cores = Runtime.getRuntime().availableProcessors();
            // ZGC 开启时跳过 G1 相关参数（build 已含 G1 参数，ZGC 模式只取 JIT+CPU+CodeCache）
            if (mioZgc) {
                // ZGC 模式：注入 ZGC 参数集 + JIT/CPU/CodeCache（跳过 build 中的 G1 参数）
                for (String f : MioFlags.buildZgc(preferences.getMaxMemoryMb())) {
                    profile.addJvmArg(f);
                }
                // 仅注入非 G1 的激进参数（JIT 内联 + CPU 指令集 + CodeCache + 分配器）
                for (String f : MioFlags.build(cores, preferences.getMaxMemoryMb())) {
                    if (!f.startsWith("-XX:MaxGCPauseMillis") &&
                        !f.startsWith("-XX:G1") &&
                        !f.startsWith("-XX:TargetSurvivorRatio") &&
                        !f.startsWith("-XX:MaxTenuringThreshold") &&
                        !f.startsWith("-XX:ParallelGCThreads") &&
                        !f.startsWith("-XX:ConcGCThreads")) {
                        profile.addJvmArg(f);
                    }
                }
            } else {
                // G1 模式：注入完整激进参数集
                for (String f : MioFlags.build(cores, preferences.getMaxMemoryMb())) {
                    profile.addJvmArg(f);
                }
                // 堆 >= 4GB 时强制 Xms == Xmx，避免运行时堆扩张停顿
                if (preferences.getMaxMemoryMb() >= 4096) {
                    profile.addJvmArg("-Xms" + preferences.getMaxMemoryMb() + "m");
                }
            }
        }

        // 澪模式 L1+：大页内存 + NUMA（JVM 不支持自动降级，不会启动失败）
        if (preferences.isMioModeEnabled() && preferences.isMioModeLargePages()) {
            for (String f : MioFlags.buildLargePages()) {
                profile.addJvmArg(f);
            }
        }

        // 澪模式 L1+：LWJGL/OpenGL 渲染加速（HighDPI 在 LWJGL2/applet 上常致黑屏，跳过）
        if (preferences.isMioModeEnabled() && preferences.isMioModeRenderOpt()
                && !RetroWrapperSupport.isLwjgl2Era(versionId)) {
            for (String f : MioFlags.buildRenderOpt()) {
                profile.addJvmArg(f);
            }
        }

        // 澪模式 L1+：JIT 编译器激进
        if (preferences.isMioModeEnabled() && preferences.isMioModeJitAggressive()) {
            int coresForJit = Runtime.getRuntime().availableProcessors();
            for (String f : MioFlags.buildJitAggressive(coresForJit)) {
                profile.addJvmArg(f);
            }
        }

        // 澪模式 L1+：网络栈优化（MC 联机场景）
        if (preferences.isMioModeEnabled() && preferences.isMioModeNetworkOpt()) {
            for (String f : MioFlags.buildNetworkOpt()) {
                profile.addJvmArg(f);
            }
        }

        // 澪模式 L1+：元空间管控（防 OOM）
        if (preferences.isMioModeEnabled() && preferences.isMioModeMetaspace()) {
            for (String f : MioFlags.buildMetaspace()) {
                profile.addJvmArg(f);
            }
        }

        // 版本 JSON 自带的 JVM 参数
        // 过滤掉运行时 Java 不支持的参数：
        //   --sun-misc-unsafe-memory-access=allow 是 Java 23+ (JEP 471) 引入的，
        //   Mojang 新版本 JSON 自带此参数，但 PMCL 使用 Java 21 启动会报
        //   "Unrecognized option" 导致 JVM 无法创建、游戏直接退出。
        boolean lwjgl2Era = RetroWrapperSupport.isLwjgl2Era(versionId);
        for (String arg : vj.getJvmArgs()) {
            if (javaMajorVersion > 0 && javaMajorVersion < 23
                    && arg.startsWith("--sun-misc-unsafe-memory-access")) {
                continue;
            }
            // LWJGL2：版本 JSON 若带 -XstartOnFirstThread 会卡死 MacOSXDisplay.createWindow
            if (lwjgl2Era && "-XstartOnFirstThread".equals(arg.trim())) {
                continue;
            }
            profile.addJvmArg(replacePlaceholders(arg, versionId, mcRoot, librariesDir, assetsDir,
                    versionsDir, gameDir, nativesDir, account, vj.getAssets()));
        }

        // 用户自定义 JVM 参数（最后追加，可覆盖前面）
        String custom = preferences.getCustomJvmArgs();
        if (custom != null && !custom.trim().isEmpty()) {
            boolean lwjgl2 = RetroWrapperSupport.isLwjgl2Era(versionId);
            for (String arg : custom.trim().split("\\s+")) {
                if (arg.isEmpty()) continue;
                // LWJGL2 上 -XstartOnFirstThread 会卡死 createWindow
                if (lwjgl2 && "-XstartOnFirstThread".equals(arg)) continue;
                profile.addJvmArg(arg);
            }
        }

        // 游戏参数（含 is_demo_user / has_custom_resolution 条件规则）
        int prefW = preferences.getGameWindowWidth();
        int prefH = preferences.getGameWindowHeight();
        boolean customResolution = prefW > 0 && prefH > 0;
        List<String> gameArgsFromJson = vj.getGameArgs(
                preferences.isGameDemo(), customResolution, prefW, prefH);
        boolean jsonHasWidth = false;
        boolean jsonHasDemo = false;
        for (String arg : gameArgsFromJson) {
            if ("--width".equals(arg)) jsonHasWidth = true;
            if ("--demo".equals(arg)) jsonHasDemo = true;
            profile.addGameArg(replacePlaceholders(arg, versionId, mcRoot, librariesDir, assetsDir,
                    versionsDir, gameDir, nativesDir, account, vj.getAssets()));
        }

        // === 游戏通用行为（用户偏好） ===
        // Applet 时代（<1.6）不认现代 --width/--renderer 等；乱注入可能干扰 RetroWrapper
        boolean appletEra = RetroWrapperSupport.needsRetroTweaker(versionId);
        if (!appletEra) {
            // 窗口分辨率（JSON 条件参数未展开时再注入，避免重复 --width）
            if (customResolution && !jsonHasWidth) {
                profile.addGameArg("--width");
                profile.addGameArg(Integer.toString(prefW));
                profile.addGameArg("--height");
                profile.addGameArg(Integer.toString(prefH));
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
            if (preferences.isGameDemo() && !jsonHasDemo) {
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

            // 自定义窗口图标：复制到 <gameDir>/icons/icon_16x16.png 和 icon_32x32.png
            // Minecraft MainWindow 启动时从 gameDir/icons/ 读取图标
            injectWindowIcon(preferences.getWindowIconPath(), gameDir);

            // 同步启动器语言到游戏 options.txt 的 lang 字段
            // Minecraft 没有 --language 命令行参数，游戏内语言只能通过 options.txt 设置
            syncGameLanguage(gameDir);

            // 自定义主菜单背景：从用户视频中提取 6 帧生成 panorama 资源包，并启用
            // Minecraft 主菜单原生只支持 6 张静态全景图，不支持视频；这里用帧提取近似实现
            installMenuBackground(gameDir, versionId);
        } else {
            // alpha 的 lastServer: 空值会在加载 options 时抛 AIOOBE（非致命但会丢设置）
            com.pmcl.core.gamecontent.OptionsTxtWriter.sanitizeEmptyValues(
                    gameDir.resolve("options.txt"));
        }

        // === authlib-injector 注入（皮肤站账号） ===
        // YGGDRASIL 类型账号需通过 authlib-injector Java Agent 修改 authlib 请求 URL，
        // 使 Minecraft 指向自定义皮肤站而非 Mojang 官方服务器。
        // 采用预取方式：启动前 GET /api/yggdrasil 获取元数据，Base64 编码后通过 -D 参数传入。
        if (account != null && account.getType() == Account.AccountType.YGGDRASIL) {
            injectAuthlibInjector(profile, account);
        }

        return profile;
    }

    /**
     * 安装自定义主菜单背景资源包（若用户设置了视频路径且注入了 provider）。
     * <p>
     * 流程：provider 从视频提取 6 帧 → 生成 panorama 资源包 zip 到 gameDir/resourcepacks/ →
     * 调用 {@link com.pmcl.core.gamecontent.OptionsTxtWriter#enableResourcePack} 启用。
     * <p>
     * 任何失败均静默忽略，不影响启动。视频不存在或 provider 为 null 时直接跳过。
     */
    private void installMenuBackground(Path gameDir, String versionId) {
        if (menuBackgroundProvider == null) return;
        String videoPathStr = preferences.getCustomMenuBackgroundVideo();
        if (videoPathStr == null || videoPathStr.isEmpty()) return;
        Path videoPath;
        try {
            videoPath = Path.of(videoPathStr);
        } catch (Throwable e) {
            System.err.println("[LaunchProfileBuilder] installMenuBackground 路径解析失败: " + e.getMessage());
            return;
        }
        if (!java.nio.file.Files.isRegularFile(videoPath)) return;
        Path cacheDir = config.getWorkDir().resolve("cache");
        try {
            String packFileName = menuBackgroundProvider.installTo(gameDir, videoPath, cacheDir, versionId);
            if (packFileName == null || packFileName.isEmpty()) return;
            // 启用资源包：写入 options.txt 的 resourcePacks 字段
            com.pmcl.core.gamecontent.OptionsTxtWriter.enableResourcePack(
                    gameDir.resolve("options.txt"), "file/" + packFileName);
        } catch (Throwable e) {
            // M76: 任何异常都应记录，避免静默吞错导致用户不知菜单背景未生效
            System.err.println("[LaunchProfileBuilder] installMenuBackground 失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 将启动器选定的语言同步写入 gameDir/options.txt 的 {@code lang} 字段。
     * <p>
     * Minecraft（1.6+）从 options.txt 读取 {@code lang:xx_YY} 决定游戏内语言。
     * PMCL 启动器语言（如 zh_CN）需转为小写（zh_cn）后写入。
     * ud_EN（颠倒英语彩蛋）仅作用于启动器 UI，不写入游戏，避免游戏识别为未知语言回退到 en_us。
     * <p>
     * 实现与 {@link com.pmcl.core.gamecontent.ShaderPackManager#writeOption} 一致：
     * 保留 options.txt 其它行，仅更新或追加 lang 字段。
     * 任何 IO 异常均静默忽略，确保不阻塞启动流程。
     */
    private void syncGameLanguage(Path gameDir) {
        String launcherLang = preferences.getLanguage();
        if (launcherLang == null || launcherLang.isEmpty()) return;
        // ud_EN 彩蛋仅影响启动器 UI，不应写入游戏
        if ("ud_EN".equals(launcherLang)) return;
        // MC 语言代码用小写（zh_cn / en_us / ja_jp），PMCL 用 zh_CN 形式
        String mcLang = launcherLang.toLowerCase(java.util.Locale.ROOT);
        Path optionsFile = gameDir.resolve("options.txt");
        try {
            if (!Files.exists(optionsFile)) {
                if (optionsFile.getParent() != null) Files.createDirectories(optionsFile.getParent());
                Files.writeString(optionsFile, "lang:" + mcLang + "\n",
                        java.nio.charset.StandardCharsets.UTF_8);
                return;
            }
            List<String> lines = new ArrayList<>(
                    Files.readAllLines(optionsFile, java.nio.charset.StandardCharsets.UTF_8));
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("lang:")) {
                    // 已是目标语言则无需重写，避免改动 mtime 触发 MC 重新加载
                    if (lines.get(i).equals("lang:" + mcLang)) return;
                    lines.set(i, "lang:" + mcLang);
                    found = true;
                    break;
                }
            }
            if (!found) lines.add("lang:" + mcLang);
            Files.writeString(optionsFile, String.join("\n", lines) + "\n",
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 语言同步失败不应阻塞启动，但记录原因便于排查
            System.err.println("[LaunchProfileBuilder] syncGameLanguage 失败: " + e.getMessage());
        }
    }

    /**
     * 将用户指定的 PNG 复制并缩放到 {@code <gameDir>/icons/icon_16x16.png} 和 {@code icon_32x32.png}。
     * <p>
     * Minecraft（1.6+）启动时从 {@code gameDir/icons/} 读取窗口图标。
     * 任何异常均静默忽略，确保不阻塞启动流程。
     */
    private void injectWindowIcon(String iconPath, Path gameDir) {
        if (iconPath == null || iconPath.isEmpty()) return;
        Path src = Path.of(iconPath);
        if (!Files.isRegularFile(src)) return;
        try {
            BufferedImage img = ImageIO.read(src.toFile());
            if (img == null) return;
            Path iconsDir = gameDir.resolve("icons");
            Files.createDirectories(iconsDir);
            // 缩放并写入 16x16 和 32x32
            writeResizedPng(img, 16, iconsDir.resolve("icon_16x16.png"));
            writeResizedPng(img, 32, iconsDir.resolve("icon_32x32.png"));
        } catch (IOException e) {
            // 图标注入失败不应阻塞启动，但记录原因便于排查
            System.err.println("[LaunchProfileBuilder] injectWindowIcon 失败: " + e.getMessage());
        }
    }

    private void writeResizedPng(BufferedImage src, int size, Path target) throws IOException {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                               java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                               java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        ImageIO.write(out, "png", target.toFile());
    }

    private void addClasspath(LaunchProfile profile, Set<String> seen, Path p) {
        String key = p.toAbsolutePath().toString();
        if (seen.add(key)) {
            profile.addClasspath(p);
        }
    }

    /**
     * 取 Maven 坐标的 group:artifact:classifier 部分（去掉版本号），用于同库不同版本去重。
     * <p>
     * Maven 坐标格式：
     * <ul>
     *   <li>{@code group:artifact:version}</li>
     *   <li>{@code group:artifact:version:classifier}</li>
     * </ul>
     * 返回 {@code group:artifact:classifier}（无 classifier 时 classifier 为空字符串），
     * 确保 asm 9.6 与 asm 9.8 产生相同 key 从而触发去重，
     * 同时区分 {@code lwjgl}（主 artifact）与 {@code lwjgl:natives-macos}（native 条目）。
     */
    private static String libGaKey(String name) {
        String[] parts = name.split(":");
        if (parts.length < 2) return name;
        String classifier = parts.length >= 4 ? parts[3] : "";
        return parts[0] + ":" + parts[1] + ":" + classifier;
    }

    /**
     * 自动检测 mods 目录中是否有使用 Kotlin 的 mod，若有则下载 kotlin-stdlib 并加入 classpath。
     * <p>
     * 仅在「需要 Kotlin 运行时、但尚未由 KotlinForForge / fabric-language-kotlin /
     * 已 shade 的 kotlin 包提供」时注入。KotlinForForge 的 {@code -all} jar 已 shade
     * {@code kotlin.*}；若再注入独立 {@code kotlin-stdlib}，JPMS 会因重复导出
     * {@code kotlin.ranges} 等包而在 Forge 上直接 {@code ResolutionException}。
     *
     * @param profile      启动配置
     * @param seen         已加入 classpath 的路径集合（去重用）
     * @param gameDir      游戏工作目录（含 mods/ 子目录）
     * @param librariesDir 库文件目录（kotlin-stdlib JAR 存放位置）
     */
    private void injectKotlinStdlibIfNeeded(LaunchProfile profile, Set<String> seen,
                                             Path gameDir, Path librariesDir) throws IOException {
        Path modsDir = gameDir.resolve("mods");
        if (!java.nio.file.Files.isDirectory(modsDir)) return;

        // 1. 递归扫描 mods 目录（深度 4，覆盖 Forge 的 mods/<version>/ 子目录）
        boolean needsStdlib = false;
        boolean runtimeAlreadyProvided = false;
        int scanned = 0;
        try (var stream = java.nio.file.Files.walk(modsDir, 4)) {
            var jars = stream
                    .filter(p -> !java.nio.file.Files.isSymbolicLink(p))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".jar");
                    })
                    .toList();
            for (Path jar : jars) {
                scanned++;
                KotlinJarKind kind = classifyKotlinJar(jar);
                if (kind == KotlinJarKind.PROVIDES_RUNTIME) {
                    runtimeAlreadyProvided = true;
                    break;
                }
                if (kind == KotlinJarKind.NEEDS_STDLIB) {
                    needsStdlib = true;
                }
            }
        } catch (IOException e) {
            // 扫描失败不影响启动
            System.err.println("[LaunchProfileBuilder] 扫描 mods 目录失败: " + e.getMessage());
            return;
        }
        if (runtimeAlreadyProvided) {
            System.err.println("[LaunchProfileBuilder] 已由 KotlinForForge/shade 提供 Kotlin 运行时，"
                    + "跳过 kotlin-stdlib 注入（扫描 " + scanned + " 个 jar）");
            return;
        }
        if (!needsStdlib) {
            System.err.println("[LaunchProfileBuilder] 未检测到需要 kotlin-stdlib 的 mod（扫描 "
                    + scanned + " 个 jar）");
            return;
        }
        System.err.println("[LaunchProfileBuilder] 检测到 Kotlin mod，需要注入 kotlin-stdlib（扫描 "
                + scanned + " 个 jar）");

        // 2. 检查 classpath 是否已包含 kotlin-stdlib（可能是 fabric-language-kotlin 等已自带）
        for (String s : seen) {
            if (s.contains("kotlin-stdlib") || s.contains("fabric-language-kotlin")
                    || s.contains("KotlinLanguageAdapter") || s.contains("kotlinforforge")) {
                System.err.println("[LaunchProfileBuilder] classpath 已含 Kotlin 运行时: " + s);
                return; // 已有 Kotlin 运行时
            }
        }

        // 3. 下载 kotlin-stdlib JAR 到 libraries 目录
        // 使用与启动器一致的 Kotlin 版本（2.0.21），向后兼容 1.9.x mod
        String kotlinVersion = "2.0.21";
        // Maven Central 发布的 kotlin-stdlib-2.0.21.jar SHA-1（固定版本时一并更新）
        String expectedSha1 = "618b539767b4899b4660a83006e052b63f1db551";
        String groupPath = "org/jetbrains/kotlin/kotlin-stdlib";
        String jarName = "kotlin-stdlib-" + kotlinVersion + ".jar";
        Path kotlinJar = librariesDir.resolve(groupPath).resolve(kotlinVersion).resolve(jarName);

        if (!java.nio.file.Files.exists(kotlinJar)
                || !expectedSha1.equalsIgnoreCase(sha1File(kotlinJar))) {
            try {
                java.nio.file.Files.deleteIfExists(kotlinJar);
                java.nio.file.Files.createDirectories(kotlinJar.getParent());
                String groupPathUrl = groupPath.replace('/', '.');
                // 主源 Maven Central + 阿里云镜像 fallback（国内网络下 Maven Central 可能超时）
                String[] mavenUrls = {
                    "https://repo1.maven.org/maven2/" + groupPath + "/" + kotlinVersion + "/" + jarName,
                    "https://maven.aliyun.com/repository/public/"
                            + groupPathUrl + "/" + kotlinVersion + "/" + jarName
                };
                System.err.println("[LaunchProfileBuilder] 正在下载 kotlin-stdlib "
                        + kotlinVersion + " ...");
                IOException lastErr = null;
                boolean downloaded = false;
                for (String mavenUrl : mavenUrls) {
                    try {
                        if (downloadManager != null) {
                            downloadManager.downloadTo(mavenUrl, kotlinJar);
                        } else {
                            try (var in = new java.net.URL(mavenUrl).openStream()) {
                                java.nio.file.Files.copy(in, kotlinJar,
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        String actual = sha1File(kotlinJar);
                        if (!expectedSha1.equalsIgnoreCase(actual)) {
                            java.nio.file.Files.deleteIfExists(kotlinJar);
                            throw new IOException("kotlin-stdlib SHA-1 校验失败：期望 "
                                    + expectedSha1 + " 实际 " + actual);
                        }
                        downloaded = true;
                        break;
                    } catch (IOException e) {
                        lastErr = e;
                        System.err.println("[LaunchProfileBuilder] 从 " + mavenUrl + " 下载失败: "
                                + e.getMessage() + "，尝试下一个镜像");
                    }
                }
                if (!downloaded) throw lastErr != null ? lastErr
                        : new IOException("kotlin-stdlib 下载失败");
                System.err.println("[LaunchProfileBuilder] kotlin-stdlib 下载完成: " + kotlinJar);
            } catch (IOException e) {
                throw new IOException("下载 kotlin-stdlib 失败（检测到 Kotlin mod，无法安全启动）: "
                        + e.getMessage(), e);
            }
        }

        // 4. 加入 classpath
        addClasspath(profile, seen, kotlinJar);
        System.err.println("[LaunchProfileBuilder] kotlin-stdlib 已加入 classpath: " + kotlinJar);
    }

    private static String sha1File(Path file) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** mods 目录中 JAR 与 Kotlin 运行时的关系。 */
    private enum KotlinJarKind {
        /** 非 Kotlin / 与 Kotlin 无关 */
        NONE,
        /** 使用 Kotlin 编译，但自身未提供 stdlib，需要启动器注入 */
        NEEDS_STDLIB,
        /** KotlinForForge 或已 shade {@code kotlin.*}，禁止再注入独立 kotlin-stdlib */
        PROVIDES_RUNTIME
    }

    /**
     * 分类 JAR 与 Kotlin 运行时的关系。
     * <p>
     * KotlinForForge（或任何 shade 了 {@code kotlin/} 的 jar）视为已提供运行时；
     * 仅含 {@code META-INF/kotlin-*.kotlin_module} 的 jar 才需要注入 stdlib。
     */
    private KotlinJarKind classifyKotlinJar(Path jarPath) {
        String fileName = jarPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (fileName.contains("kotlinforforge") || fileName.contains("kotlin-for-forge")) {
            return KotlinJarKind.PROVIDES_RUNTIME;
        }
        try (var zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            var manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry != null) {
                try (var in = zip.getInputStream(manifestEntry)) {
                    String manifest = new String(in.readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (manifest.contains("KotlinForForge") || manifest.contains("kotlinforforge")
                            || manifest.contains("kotlin-for-forge")
                            || manifest.contains("thedarkcolour.kotlinforforge")) {
                        return KotlinJarKind.PROVIDES_RUNTIME;
                    }
                }
            }
            boolean hasKotlinModule = false;
            boolean shadesKotlinStdlib = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/kotlin-") && name.endsWith(".kotlin_module")) {
                    hasKotlinModule = true;
                }
                // shade 了 stdlib 的典型路径；勿把普通 mod 里偶然的 kotlin/ 依赖 jar 误判——
                // 顶层 kotlin/ranges 等包是 KFF -all 的特征
                if (name.startsWith("kotlin/") && name.endsWith(".class")) {
                    shadesKotlinStdlib = true;
                }
                if (hasKotlinModule && shadesKotlinStdlib) {
                    break;
                }
            }
            if (shadesKotlinStdlib) {
                return KotlinJarKind.PROVIDES_RUNTIME;
            }
            if (hasKotlinModule) {
                return KotlinJarKind.NEEDS_STDLIB;
            }
        } catch (IOException e) {
            // 读取失败不算 Kotlin mod
        }
        return KotlinJarKind.NONE;
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
                VersionJson.Artifact art = lib.getArtifact();
                String sha1 = art != null ? art.getSha1() : null;
                if (isLibraryHealthy(nativeJar, sha1)) continue;
                if (art != null && art.getUrl() != null && !art.getUrl().isEmpty()
                        && downloadManager != null) {
                    try {
                        Files.createDirectories(nativeJar.getParent());
                        quarantineCorrupt(nativeJar);
                        downloadLibraryVerified(art.getUrl(), nativeJar, sha1);
                    } catch (IOException e) {
                        missing.add(lib.getName() + " (native): " + e.getMessage());
                    }
                } else if (art == null || art.getUrl() == null || art.getUrl().isEmpty()) {
                    missing.add(lib.getName() + " (native, 无下载URL)");
                }
                continue;
            }

            // === 主 artifact（classpath 库）===
            if (lib.getArtifact() != null) {
                Path libPath = librariesDir.resolve(lib.getPath());
                VersionJson.Artifact art = lib.getArtifact();
                if (isLibraryHealthy(libPath, art.getSha1())) {
                    // fall through to old-format natives check
                } else if (art.getUrl() != null && !art.getUrl().isEmpty() && downloadManager != null) {
                    try {
                        Files.createDirectories(libPath.getParent());
                        quarantineCorrupt(libPath);
                        downloadLibraryVerified(art.getUrl(), libPath, art.getSha1());
                    } catch (IOException e) {
                        missing.add(lib.getName() + ": " + e.getMessage());
                    }
                } else if (art.getUrl() == null || art.getUrl().isEmpty()) {
                    missing.add(lib.getName() + " (无下载URL)");
                }
            } else if (!lib.getUrl().isEmpty()) {
                // Fabric/Forge/NeoForge 第三方库格式：只有顶层 url（maven 仓库根），无 downloads.artifact
                Path libPath = librariesDir.resolve(lib.getPath());
                if (!isLibraryHealthy(libPath, null)) {
                    if (downloadManager != null) {
                        String mavenUrl = lib.getUrl();
                        if (!mavenUrl.endsWith("/")) mavenUrl += "/";
                        mavenUrl += lib.getPath();
                        try {
                            Files.createDirectories(libPath.getParent());
                            quarantineCorrupt(libPath);
                            downloadLibraryVerified(mavenUrl, libPath, null);
                        } catch (IOException e) {
                            missing.add(lib.getName() + ": " + e.getMessage());
                        }
                    } else {
                        missing.add(lib.getName() + " (无下载管理器)");
                    }
                }
            }

            // === 旧格式 native 库（有 natives 字段）===
            if (lib.isNativeLib() && lib.getNativeClassifier() != null) {
                Path nativeJar = librariesDir.resolve(
                        lib.getPathForClassifier(lib.getNativeClassifier()));
                VersionJson.Artifact nativeArt = lib.getNativeArtifact();
                String sha1 = nativeArt != null ? nativeArt.getSha1() : null;
                if (isLibraryHealthy(nativeJar, sha1)) continue;
                if (nativeArt != null && nativeArt.getUrl() != null
                        && !nativeArt.getUrl().isEmpty() && downloadManager != null) {
                    try {
                        Files.createDirectories(nativeJar.getParent());
                        quarantineCorrupt(nativeJar);
                        downloadLibraryVerified(nativeArt.getUrl(), nativeJar, sha1);
                    } catch (IOException e) {
                        missing.add(lib.getName() + ":" + lib.getNativeClassifier()
                                + " (native): " + e.getMessage());
                    }
                } else {
                    missing.add(lib.getName() + ":" + lib.getNativeClassifier()
                            + " (native, 无法自动修复)");
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("缺少库文件且无法自动下载:\n  - "
                    + String.join("\n  - ", missing));
        }
    }

    /**
     * 文件存在且（有 sha1 则匹配；无 sha1 则至少为合法 zip）视为健康。
     * 有 sha1 但不匹配时隔离损坏文件并返回 false，触发重下。
     */
    private static boolean isLibraryHealthy(Path path, String expectedSha1) {
        if (!Files.isRegularFile(path)) return false;
        try {
            if (Files.size(path) < 16) {
                quarantineCorrupt(path);
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        if (expectedSha1 == null || expectedSha1.isBlank()) {
            // 无哈希：拒绝截断/非 zip 半成品被当成健康
            if (!looksLikeZip(path)) {
                quarantineCorrupt(path);
                return false;
            }
            return true;
        }
        String actual = sha1File(path);
        if (expectedSha1.equalsIgnoreCase(actual)) return true;
        System.err.println("[LaunchProfileBuilder] 库 SHA-1 不匹配，将重下: " + path
                + " expected=" + expectedSha1 + " actual=" + actual);
        quarantineCorrupt(path);
        return false;
    }

    private static boolean looksLikeZip(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(2);
            return magic.length >= 2 && magic[0] == 'P' && magic[1] == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    /** 将损坏文件移到 {@code .corrupt} 后缀，避免启动继续使用。 */
    private static void quarantineCorrupt(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Path corrupt = path.resolveSibling(path.getFileName() + ".corrupt");
            Files.deleteIfExists(corrupt);
            Files.move(path, corrupt, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }
    }

    private static final String ASSET_RESOURCE_BASE = "https://resources.download.minecraft.net/";

    /**
     * 启动前校验资产索引与缺失对象（存在性 + 声明 size；缺失则按 SHA 重下）。
     * 不对全部已存在文件重算哈希，避免数千资源拖慢启动。
     */
    private void verifyAssets(VersionJson vj, Path assetsDir) throws IOException {
        JsonObject root = vj.getRawJson();
        if (root == null || !root.has("assetIndex") || root.get("assetIndex").isJsonNull()) {
            return;
        }
        JsonObject ai = root.getAsJsonObject("assetIndex");
        String id = ai.has("id") && !ai.get("id").isJsonNull() ? ai.get("id").getAsString() : null;
        if (id == null || id.isBlank() || id.contains("..") || id.contains("/") || id.contains("\\")) {
            throw new IOException("assetIndex.id 非法: " + id);
        }
        String indexSha1 = ai.has("sha1") && !ai.get("sha1").isJsonNull()
                ? ai.get("sha1").getAsString() : "";
        String indexUrl = ai.has("url") && !ai.get("url").isJsonNull()
                ? ai.get("url").getAsString() : "";
        Path indexPath = assetsDir.resolve("indexes").resolve(id + ".json");
        if (!isLibraryHealthy(indexPath, indexSha1.isBlank() ? null : indexSha1)) {
            if (indexUrl.isBlank() || indexSha1.isBlank() || downloadManager == null) {
                throw new IOException("资产索引缺失或损坏且无法自动修复: " + indexPath);
            }
            Files.createDirectories(indexPath.getParent());
            quarantineCorrupt(indexPath);
            downloadManager.downloadToVerified(indexUrl, indexPath, indexSha1, null);
        }
        String idxJson = Files.readString(indexPath, java.nio.charset.StandardCharsets.UTF_8);
        com.pmcl.core.install.AssetIndex idx = com.pmcl.core.install.AssetIndex.parse(idxJson);
        Path objectsAbs = assetsDir.resolve("objects").toAbsolutePath().normalize();
        int repaired = 0;
        for (com.pmcl.core.install.AssetIndex.Asset a : idx.getAssets().values()) {
            Path file = objectsAbs.resolve(a.getPath()).normalize();
            if (!file.startsWith(objectsAbs)) {
                throw new IOException("资产路径越界: " + a.getHash());
            }
            boolean ok = Files.isRegularFile(file);
            if (ok && a.getSize() > 0) {
                try {
                    ok = Files.size(file) == a.getSize();
                } catch (IOException e) {
                    ok = false;
                }
            }
            if (ok) continue;
            if (downloadManager == null) {
                throw new IOException("资产缺失且无下载管理器: " + file);
            }
            Files.createDirectories(file.getParent());
            quarantineCorrupt(file);
            downloadManager.downloadToVerified(
                    ASSET_RESOURCE_BASE + a.getPath(), file, a.getHash(), null);
            repaired++;
        }
        if (repaired > 0) {
            System.err.println("[LaunchProfileBuilder] 启动前补全资产 " + repaired + " 个");
        }
    }

    /**
     * 校验原版 client.jar（若版本 JSON 声明了 downloads.client.sha1）。
     * 损坏则隔离并尝试按 URL 重下。
     */
    private void verifyClientJar(VersionJson vj, Path versionsDir) throws IOException {
        VersionJson.Artifact client = vj.getClientArtifact();
        if (client == null) return;
        String id = vj.getId();
        if (id == null || id.isEmpty()) return;
        Path jar = versionsDir.resolve(id).resolve(id + ".jar");
        if (isLibraryHealthy(jar, client.getSha1())) return;
        if (client.getUrl() == null || client.getUrl().isEmpty() || downloadManager == null) {
            throw new IOException("client.jar 损坏或缺失且无法自动修复: " + jar);
        }
        Files.createDirectories(jar.getParent());
        quarantineCorrupt(jar);
        downloadLibraryVerified(client.getUrl(), jar, client.getSha1());
    }

    /**
     * 有 SHA-1 则强制校验；无哈希时尝试 Maven 旁路 {@code .sha1}；
     * 仍不可得则拒绝下载（与 ForgeInstaller 一致）。
     */
    private void downloadLibraryVerified(String url, Path target, String sha1) throws IOException {
        String effective = sha1;
        if (effective == null || effective.isBlank()) {
            effective = fetchMavenSha1Sidecar(url);
        }
        if (effective == null || effective.isBlank()) {
            throw new IOException("库无 SHA-1 且旁路 .sha1 不可用，拒绝下载: " + url);
        }
        downloadManager.downloadToVerified(url, target, effective, null);
    }

    /** 读取 {@code url.sha1} 旁路文件（Maven 惯例），失败返回 null。 */
    private String fetchMavenSha1Sidecar(String url) {
        try {
            String body = downloadManager.downloadString(url + ".sha1").trim();
            if (body.isEmpty()) return null;
            // 格式可能是 "deadbeef...  filename.jar" 或纯哈希
            String hash = body.split("\\s+")[0].trim();
            if (hash.matches("[0-9a-fA-F]{40}")) return hash;
            System.err.println("[LaunchProfileBuilder] .sha1 旁路格式无效: " + url + ".sha1");
            return null;
        } catch (Exception e) {
            System.err.println("[LaunchProfileBuilder] 获取 .sha1 旁路失败: " + url
                    + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * native jar 指纹：mtime + size，用于判断 jar 是否变更。
     * 比 hash 快（无读文件开销），对"安装后不变"的 native jar 足够可靠。
     */
    private static String nativeFingerprint(Path jar) {
        try {
            var attrs = java.nio.file.Files.readAttributes(jar, "size,lastModifiedTime");
            return attrs.get("size") + "|" + attrs.get("lastModifiedTime");
        } catch (Exception e) {
            return "0|0";
        }
    }

    /**
     * 需要提取的 jar 非空时，natives 目录须至少含一个本地库文件；
     * 并拒绝 LWJGL2/3 混用（历史转译曾把 FrankenLWJGL2 写进 1.13+ 目录，会导致 JNI SIGSEGV）。
     */
    private static boolean nativesDirLooksHealthy(Path nativesDir, java.util.List<Path> jars) {
        if (jars == null || jars.isEmpty()) return true;
        if (!java.nio.file.Files.isDirectory(nativesDir)) return false;
        boolean hasNativeFile = false;
        boolean hasGlfw = false;
        boolean hasLwjglOpengl = false;
        boolean hasJinput = false;
        long lwjglDylibSize = -1L;
        try (var stream = java.nio.file.Files.list(nativesDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!java.nio.file.Files.isRegularFile(p)) continue;
                String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (n.endsWith(".dll") || n.endsWith(".so")
                        || n.endsWith(".dylib") || n.endsWith(".jnilib")) {
                    hasNativeFile = true;
                }
                if (n.equals("libglfw.dylib") || n.equals("glfw.dll") || n.equals("libglfw.so")) {
                    hasGlfw = true;
                }
                if (n.startsWith("liblwjgl_opengl") || n.startsWith("lwjgl_opengl")) {
                    hasLwjglOpengl = true;
                }
                if (n.startsWith("libjinput") || n.startsWith("jinput")) {
                    hasJinput = true;
                }
                if (n.equals("liblwjgl.dylib") || n.equals("lwjgl.dll") || n.equals("liblwjgl.so")) {
                    try {
                        lwjglDylibSize = java.nio.file.Files.size(p);
                    } catch (IOException ignored) {}
                }
            }
        } catch (IOException e) {
            return false;
        }
        if (!hasNativeFile) return false;

        boolean jarsLookLwjgl3 = jars.stream().anyMatch(LaunchProfileBuilder::jarLooksLikeLwjgl3Native);
        // LWJGL3 特征：glfw / lwjgl_opengl；与 LWJGL2 的 jinput 不应共存
        boolean dirLooksLwjgl3 = jarsLookLwjgl3 || hasGlfw || hasLwjglOpengl;
        if (dirLooksLwjgl3 && hasJinput) {
            System.err.println("[LaunchProfileBuilder] natives 目录混入 LWJGL2 jinput，强制重解压: "
                    + nativesDir);
            return false;
        }
        // FrankenLWJGL2 的 liblwjgl.dylib ≈ 950KB；LWJGL 3.1.x 的约为 150KB
        if (dirLooksLwjgl3 && lwjglDylibSize > 400_000L) {
            System.err.println("[LaunchProfileBuilder] natives 中 liblwjgl 体积异常（疑似 FrankenLWJGL2 污染），"
                    + "强制重解压: " + nativesDir + " size=" + lwjglDylibSize);
            return false;
        }
        return true;
    }

    /** 粗判 native jar 是否属于 LWJGL 3.x（1.13+）。 */
    private static boolean jarLooksLikeLwjgl3Native(Path jar) {
        if (jar == null) return false;
        String s = jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!s.contains("lwjgl")) return false;
        // LWJGL2: lwjgl-platform-2.x.x-natives-osx.jar
        if (s.contains("lwjgl-platform") || s.contains("lwjgl_util")) return false;
        if (s.contains("-2.") || s.contains("_2.")) return false;
        // LWJGL3: lwjgl-3.x.x-natives-macos.jar / natives-macos-arm64
        return s.contains("3.") || s.contains("natives-macos") || s.contains("natives-windows")
                || s.contains("natives-linux");
    }

    /**
     * 读取上次提取的 natives 指纹。格式：每行一个 "path<TAB>fingerprint"。
     * 文件不存在或格式异常时返回空 Map（触发全量提取，安全回退）。
     */
    private static java.util.Map<String, String> readNativesFingerprint(Path fpFile) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (!java.nio.file.Files.exists(fpFile)) return map;
        try (var lines = java.nio.file.Files.lines(fpFile, java.nio.charset.StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                int tab = line.indexOf('\t');
                if (tab > 0) map.put(line.substring(0, tab), line.substring(tab + 1));
            });
        } catch (IOException ignored) {
            // 读取失败：返回空 Map，触发全量提取
        }
        return map;
    }

    /**
     * 写入本次提取的 natives 指纹，供下次启动比对。
     */
    private static void writeNativesFingerprint(Path fpFile, java.util.Map<String, String> fp) {
        try {
            java.nio.file.Files.createDirectories(fpFile.getParent());
            StringBuilder sb = new StringBuilder();
            fp.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
            java.nio.file.Files.writeString(fpFile, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 写入失败不影响启动，下次会全量提取
        }
    }

    private void extractNatives(Path nativeJar, Path targetDir) throws IOException {
        Path absTargetDir = targetDir.toAbsolutePath().normalize();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(nativeJar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // 跳过 META-INF
                if (name.startsWith("META-INF/")) continue;
                // 只提取本地库文件
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!(lower.endsWith(".so") || lower.endsWith(".dylib") ||
                      lower.endsWith(".dll") || lower.endsWith(".jnilib"))) {
                    continue;
                }
                // 扁平化：只取文件名，去掉目录前缀（如 macos/arm64/org/lwjgl/liblwjgl.dylib → liblwjgl.dylib）
                // 同时处理反斜杠分隔符（Windows 上恶意 jar 可用 ..\evil.dll 绕过仅检查正斜杠的 ZipSlip 防护）
                String normalized = name.replace('\\', '/');
                String fileName = normalized;
                int lastSlash = normalized.lastIndexOf('/');
                if (lastSlash >= 0) fileName = normalized.substring(lastSlash + 1);
                // ZipSlip 防护：文件名不得含路径分隔符或 ".."（扁平化后应仅为纯文件名）
                if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                    throw new IOException("非法 native entry 名称（疑似 ZipSlip）: " + name);
                }
                Path target = targetDir.resolve(fileName).toAbsolutePath().normalize();
                // 二次校验：解析后的目标路径必须在 targetDir 内
                if (!target.startsWith(absTargetDir)) {
                    throw new IOException("native 解压目标越界（ZipSlip 防护）: " + name
                            + " -> " + target);
                }
                try (java.io.InputStream is = jar.getInputStream(entry);
                     java.io.OutputStream os = java.nio.file.Files.newOutputStream(target)) {
                    is.transferTo(os);
                }

                // Java 9+ 兼容：LWJGL 2.x 时代的 native 库扩展名是 .jnilib，
                // 但 Java 9+ 在 macOS 上只查找 .dylib（System.load 不识别 .jnilib）。
                // 自动创建 .dylib 副本（硬链接或复制），让 Java 9+ 能加载。
                if (lower.endsWith(".jnilib")) {
                    String dylibName = fileName.substring(0, fileName.length() - ".jnilib".length()) + ".dylib";
                    Path dylibTarget = targetDir.resolve(dylibName).toAbsolutePath().normalize();
                    // 二次校验 dylib 目标
                    if (!dylibTarget.startsWith(absTargetDir)) {
                        throw new IOException("dylib 副本目标越界: " + dylibName);
                    }
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

        // 优先 ClassLoader 资源流（避免 Class.forName 触发类初始化副作用）
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) return is.readAllBytes();
        }
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            try (java.io.InputStream is = ctx.getResourceAsStream(resourcePath)) {
                if (is != null) return is.readAllBytes();
            }
        }
        try (java.io.InputStream is = ClassLoader.getSystemResourceAsStream(resourcePath)) {
            if (is != null) return is.readAllBytes();
        }

        // 回退：已加载类的 CodeSource / 同路径相对资源
        try {
            Class<?> clazz = Class.forName(className, false, getClass().getClassLoader());
            try (java.io.InputStream is = clazz.getResourceAsStream("/" + resourcePath)) {
                if (is != null) return is.readAllBytes();
            }
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
        } catch (ClassNotFoundException e) {
            throw new IOException("找不到类字节码: " + className, e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("加载类字节码失败: " + className + " — " + e.getMessage(), e);
        }

        throw new IOException("无法加载类字节码: " + className);
    }

    /**
     * 为 LaunchWrapper 老版本（alpha/beta/1.6-1.12.2）生成默认 log4j2 配置。
     * <p>
     * 这些版本 JSON 无 logging.client 字段，但 LaunchWrapper 的 LogWrapper 引用 log4j 类；
     * 不注入配置则 log4j 不初始化，游戏崩溃堆栈被丢弃，无法定位启动失败原因。
     * 配置同时输出到控制台和 gameDir/logs/latest.log。
     *
     * @param gameDir 游戏运行目录
     * @return 配置文件路径，生成失败返回 null
     */
    private Path ensureDefaultLog4jConfig(Path gameDir) {
        try {
            Path logsDir = gameDir.resolve("logs");
            java.nio.file.Files.createDirectories(logsDir);
            Path config = logsDir.resolve("pmcl-log4j2.xml");
            String absLatest = logsDir.resolve("latest.log").toAbsolutePath().toString();
            String absLogsDir = logsDir.toAbsolutePath().toString();
            String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<Configuration status=\"WARN\">\n"
                    + "  <Appenders>\n"
                    + "    <Console name=\"Console\" target=\"SYSTEM_OUT\">\n"
                    + "      <PatternLayout pattern=\"[%d{HH:mm:ss}] [%t/%level] [%logger]: %msg%n\"/>\n"
                    + "    </Console>\n"
                    + "    <RollingFile name=\"File\" fileName=\"" + absLatest + "\""
                    + " filePattern=\"" + absLogsDir + "/%d{yyyy-MM-dd}-%i.log.gz\">\n"
                    + "      <PatternLayout pattern=\"[%d{HH:mm:ss}] [%t/%level] [%logger]: %msg%n\"/>\n"
                    + "      <Policies>\n"
                    + "        <TimeBasedTriggeringPolicy/>\n"
                    + "        <SizeBasedTriggeringPolicy size=\"10MB\"/>\n"
                    + "      </Policies>\n"
                    + "      <DefaultRolloverStrategy max=\"5\"/>\n"
                    + "    </RollingFile>\n"
                    + "  </Appenders>\n"
                    + "  <Loggers>\n"
                    + "    <Logger name=\"net.minecraft\" level=\"INFO\"/>\n"
                    + "    <Root level=\"INFO\">\n"
                    + "      <AppenderRef ref=\"Console\"/>\n"
                    + "      <AppenderRef ref=\"File\"/>\n"
                    + "    </Root>\n"
                    + "  </Loggers>\n"
                    + "</Configuration>\n";
            java.nio.file.Files.writeString(config, content, java.nio.charset.StandardCharsets.UTF_8);
            return config;
        } catch (IOException e) {
            System.err.println("[LaunchProfile] 生成默认 log4j2 配置失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从版本 JSON 的 logging.client 字段解析 log4j2 配置文件，
     * 若本地不存在则下载。返回配置文件路径，无 logging 字段返回 null。
     * 配置文件中的相对路径 logs/latest.log 会被改写为基于 gameDir 的绝对路径，
     * 避免 macOS 权限问题导致 FileAppender 创建失败。
     * <p>
     * M75: 原实现把改写后的内容写回原文件，多次启动会累积 stale 绝对路径
     * （gameDir 变化时旧路径残留）。改为保留原始文件（.orig 后缀），
     * 每次基于原始内容生成改写副本，避免污染源文件。
     */
    private Path resolveLog4jConfig(VersionJson vj, Path versionsDir, String versionId, Path gameDir) {
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
            String sha1 = file.has("sha1") && !file.get("sha1").isJsonNull() ? file.get("sha1").getAsString() : "";
            if (url == null || url.isBlank()) return null;
            String ssrf = com.pmcl.core.util.SsrfChecker.validate(url);
            if (ssrf != null) {
                System.err.println("[LaunchProfileBuilder] log4j 配置 URL 被 SSRF 防护拒绝: " + ssrf);
                return null;
            }

            // M75: 原始文件存储为 <fileId>.orig，改写后的副本存储为 <fileId>
            // 这样原始内容永不污染，每次基于 .orig 生成新的改写副本
            Path origPath = versionsDir.resolve(versionId).resolve(fileId + ".orig");
            Path target = versionsDir.resolve(versionId).resolve(fileId);
            if (!java.nio.file.Files.exists(origPath) || java.nio.file.Files.size(origPath) == 0) {
                java.nio.file.Files.createDirectories(origPath.getParent());
                if (downloadManager != null) {
                    downloadManager.downloadToVerified(url, origPath, sha1, null);
                } else {
                    if (sha1 == null || sha1.isBlank()) {
                        System.err.println("[LaunchProfileBuilder] log4j 配置缺少 SHA-1 且无 DownloadManager，跳过");
                        return null;
                    }
                    okhttp3.OkHttpClient http = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .readTimeout(java.time.Duration.ofSeconds(30))
                        .build();
                    okhttp3.Request req = new okhttp3.Request.Builder().url(url).get().build();
                    try (okhttp3.Response resp = http.newCall(req).execute()) {
                        if (!resp.isSuccessful() || resp.body() == null) return null;
                        try (java.io.InputStream is = resp.body().byteStream();
                             java.io.OutputStream os = java.nio.file.Files.newOutputStream(origPath)) {
                            is.transferTo(os);
                        }
                    }
                    com.pmcl.core.download.DownloadManager.verifyHashesOrWarn(origPath, sha1, null);
                }
            } else if (sha1 != null && !sha1.isBlank()) {
                // 已有 .orig：启动前复检，防缓存投毒
                try {
                    com.pmcl.core.download.DownloadManager.verifyHashesOrWarn(origPath, sha1, null);
                } catch (IOException e) {
                    System.err.println("[LaunchProfileBuilder] 已有 log4j .orig 校验失败，重新下载: " + e.getMessage());
                    java.nio.file.Files.deleteIfExists(origPath);
                    if (downloadManager != null) {
                        downloadManager.downloadToVerified(url, origPath, sha1, null);
                    } else {
                        return null;
                    }
                }
            }
            // 改写配置文件中的相对路径为绝对路径（基于 gameDir，适配实例/整合包隔离）
            // client-1.12.xml 中 fileName="logs/latest.log" 和 filePattern="logs/..."
            Path logsBase = (gameDir != null ? gameDir : resolveMcRoot(versionId)).resolve("logs");
            Path absLogs = logsBase.toAbsolutePath();
            String content = java.nio.file.Files.readString(origPath, java.nio.charset.StandardCharsets.UTF_8);
            // 简单替换：把 "logs/latest.log" 和 "logs/" 改为绝对路径
            content = content.replace("fileName=\"logs/latest.log\"",
                    "fileName=\"" + absLogs.resolve("latest.log") + "\"");
            content = content.replace("filePattern=\"logs/",
                    "filePattern=\"" + absLogs + "/");
            // 写入改写后的副本（target），原文件 .orig 保持不变
            java.nio.file.Files.writeString(target, content, java.nio.charset.StandardCharsets.UTF_8);
            return target;
        } catch (Exception e) {
            System.err.println("[LaunchProfileBuilder] resolveLog4jConfig 失败 ("
                    + versionId + "): " + e.getMessage());
            return null;
        }
    }

    private VersionJson loadVersionJson(String versionId) throws IOException {
        return loadVersionJson(versionId, new java.util.HashSet<>(), 0);
    }

    /**
     * @param visiting 已访问的版本 ID 集合，用于检测循环继承
     * @param depth 当前递归深度，防止超长 inheritsFrom 链导致 StackOverflowError
     *              （正常版本继承深度很少超过 2，Fabric/Forge 通常 1 层，限制 16 足够冗余）
     */
    private VersionJson loadVersionJson(String versionId, java.util.Set<String> visiting, int depth) throws IOException {
        if (!visiting.add(versionId)) {
            throw new IOException("检测到循环版本继承: " + visiting + " -> " + versionId);
        }
        if (depth > 16) {
            throw new IOException("版本继承链过深（>" + depth + "）: " + visiting
                    + "，可能存在异常 inheritsFrom 链");
        }
        Path jsonPath = findVersionJson(versionId);
        if (jsonPath == null) {
            throw new IOException("版本未安装: " + versionId +
                "（已查找: " + getVersionsDirs() + "）");
        }
        // P1-5: 校验本地版本 JSON 的 SHA-1（若安装时保存了 sidecar）。
        // 防止本地篡改/磁盘损坏导致恶意 library 注入或解析错误的 libraries 列表。
        // 父版本（depth>0）也校验，但校验失败仅警告不中断（兼容手动安装的父版本无 sha1）。
        verifyVersionJsonSha1(jsonPath, versionId, depth == 0);
        String json = Files.readString(jsonPath, java.nio.charset.StandardCharsets.UTF_8);
        VersionJson vj = VersionJson.parse(json);

        if (vj.getInheritsFrom() != null && !vj.getInheritsFrom().equals(versionId)) {
            VersionJson parent = loadVersionJson(vj.getInheritsFrom(), visiting, depth + 1);
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
            // 去重 key 用 group:artifact:classifier（不含版本号）。
            // 修复：之前用完整 group:artifact:version 作 key，导致同库不同版本同时进入
            // classpath（如 Fabric 的 asm 9.6 与原版 1.21.8 的 asm 9.8），Fabric Loader
            // 的 LoaderUtil.verifyClasspath 检测到重复的 ClassReader.class 抛
            // IllegalStateException，游戏崩溃。同 group:artifact+classifier 只保留 child 版本。
            // 保留 classifier 区分主 artifact 与 native 条目，避免误删 LWJGL natives。
            java.util.Set<String> childKeys = new java.util.HashSet<>();
            if (childObj.has("libraries")) {
                for (var e : childObj.getAsJsonArray("libraries")) {
                    merged.add(e);
                    JsonObject libObj = e.getAsJsonObject();
                    if (libObj.has("name") && !libObj.get("name").isJsonNull()) {
                        childKeys.add(libGaKey(libObj.get("name").getAsString()));
                    }
                }
            }
            if (parent.getRawJson().has("libraries")) {
                for (var e : parent.getRawJson().getAsJsonArray("libraries")) {
                    JsonObject libObj = e.getAsJsonObject();
                    if (!libObj.has("name") || libObj.get("name").isJsonNull()) continue;
                    String name = libObj.get("name").getAsString();
                    if (!childKeys.contains(libGaKey(name))) merged.add(e);
                }
            }
            childObj.add("libraries", merged);
            vj = VersionJson.parse(childObj.toString());
        }
        return vj;
    }

    /**
     * P1-5: 校验本地版本 JSON 的 SHA-1。
     * 安装时由 VersionInstaller 保存 {versionId}.json.sha1 sidecar（来自版本清单）。
     * 启动时读取 sidecar 并重新计算本地 JSON 的 SHA-1 比对，不匹配则：
     * - 顶层版本（strict=true）：抛 IOException 中断启动（防篡改/损坏）
     * - 父版本（strict=false）：仅警告（兼容手动安装的父版本可能无 sidecar）
     * 无 sidecar 文件时跳过校验（兼容旧版本/外部安装）。
     */
    private void verifyVersionJsonSha1(Path jsonPath, String versionId, boolean strict) throws IOException {
        Path sha1Path = jsonPath.resolveSibling(jsonPath.getFileName() + ".sha1");
        if (!Files.exists(sha1Path)) return; // 无 sidecar，跳过（兼容旧版本/外部安装）
        String expected;
        try {
            expected = Files.readString(sha1Path, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (expected.isEmpty()) return;
            // 兼容 sidecar 可能含文件名前缀（如 "abc123  1.21.json"），取第一个 token
            int space = expected.indexOf(' ');
            if (space > 0) expected = expected.substring(0, space);
        } catch (IOException e) {
            System.err.println("[LaunchProfileBuilder] 读取版本 JSON SHA-1 sidecar 失败: " + e.getMessage());
            return;
        }
        String actual = sha1OfFile(jsonPath);
        if (!actual.equalsIgnoreCase(expected)) {
            String msg = "版本 JSON SHA-1 校验失败: " + versionId
                    + " 期望=" + expected + " 实际=" + actual
                    + "（可能被篡改或磁盘损坏，建议重新安装该版本）";
            if (strict) {
                throw new IOException(msg);
            } else {
                System.err.println("[LaunchProfileBuilder] 警告: " + msg);
            }
        }
    }

    /** 计算文件 SHA-1 的十六进制摘要 */
    private static String sha1OfFile(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            try (java.io.InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 不可用", e);
        }
    }

    /**
     * 替换参数占位符。使用版本实际所在 Minecraft 根目录的资源路径，注入真实账号信息。
     * 注意：${game_directory} 用 gameDir（整合包为版本目录本身），而非 mcRoot。
     */
    private String replacePlaceholders(String arg, String versionId,
                                       Path mcRoot, Path librariesDir,
                                       Path assetsDir, Path versionsDir,
                                       Path gameDir, Path nativesDir,
                                       Account account,
                                       String assetsIndex) {
        // assetsIndex 为空（旧版本 JSON 无 assets 字段）时回退到 versionId
        String effectiveAssetsIndex = (assetsIndex == null || assetsIndex.isEmpty())
                ? versionId : assetsIndex;
        // S9: account 或其 getter 返回 null 时 String.replace 抛 NPE，离线/未登录账号启动崩溃
        String username = account != null ? account.getUsername() : "";
        String uuid = account != null ? account.getUuid() : "";
        // GITHUB / OFFLINE 不能用于 Mojang 在线认证：accessToken 置空，避免 401 / Realms Invalid session。
        // 保留 UUID 作为玩家标识（离线服 / 局域网）。
        String accessToken = "";
        if (account != null
                && account.getType() != Account.AccountType.GITHUB
                && account.getType() != Account.AccountType.OFFLINE) {
            accessToken = account.getAccessToken();
        }
        if (username == null) username = "";
        if (uuid == null) uuid = "";
        if (accessToken == null) accessToken = "";
        Path effectiveNatives = nativesDir != null
                ? nativesDir
                : versionsDir.resolve(versionId).resolve("natives");
        // 安全修复：单次扫描替换，防止恶意用户名（如 ${auth_access_token}）被链式
        // .replace 展开导致 access_token 泄露到进程命令行/日志。
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("${natives_directory}", effectiveNatives.toString());
        placeholders.put("${launcher_name}", "PMCL");
        placeholders.put("${launcher_version}", "1.0.0");
        placeholders.put("${classpath_separator}", System.getProperty("path.separator"));
        placeholders.put("${library_directory}", librariesDir.toString());
        placeholders.put("${game_directory}", gameDir.toString());
        placeholders.put("${version_name}", versionId);
        placeholders.put("${assets_root}", assetsDir.toString());
        placeholders.put("${assets_index_name}", effectiveAssetsIndex);
        placeholders.put("${user_type}", userTypeFor(account));
        placeholders.put("${auth_player_name}", username);
        // OptiFine/LiteLoader 安装器的 fallback 模板使用非标准 ${auth_name}（Mojang 标准为 ${auth_player_name}）
        placeholders.put("${auth_name}", username);
        placeholders.put("${auth_uuid}", uuid);
        placeholders.put("${auth_access_token}", accessToken);
        placeholders.put("${auth_session}", accessToken);
        // alpha/beta 的 minecraftArguments 使用 ${session_id}（而非 ${auth_session}）
        placeholders.put("${session_id}", accessToken);
        // 1.7.10 及更早版本可能引用 ${user_properties}，传空 JSON 数组
        placeholders.put("${user_properties}", "{}");
        // 极旧版本可能引用 ${game_assets}，指向 assets 目录
        placeholders.put("${game_assets}", assetsDir.toString());
        placeholders.put("${clientid}", "");
        // auth_xuid：微软账号的 Xbox Live userHash（uhs）
        placeholders.put("${auth_xuid}", account != null && account.getXuid() != null ? account.getXuid() : "");
        placeholders.put("${version_type}", "PMCL");

        java.util.regex.Matcher pm = PLACEHOLDER_PATTERN.matcher(arg);
        StringBuilder sb = new StringBuilder(arg.length() + 64);
        while (pm.find()) {
            String key = pm.group();
            String val = placeholders.get(key);
            pm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val != null ? val : key));
        }
        pm.appendTail(sb);
        return sb.toString();
    }

    /**
     * Mojang 会话 user_type：离线/GitHub → legacy；微软 → msa；皮肤站 → mojang。
     * 恒传 msa 会导致部分服务端/模组按在线会话处理离线玩家。
     */
    private static String userTypeFor(Account account) {
        if (account == null) return "legacy";
        return switch (account.getType()) {
            case MICROSOFT -> "msa";
            case YGGDRASIL -> "mojang";
            case OFFLINE, GITHUB -> "legacy";
        };
    }

    /**
     * 注入 authlib-injector Java Agent（皮肤站账号专用）。
     * <p>
     * 流程：
     * <ol>
     *   <li>确保 authlib-injector.jar 存在（不存在则从官方下载）</li>
     *   <li>预取皮肤站 Yggdrasil API 元数据，Base64 编码</li>
     *   <li>添加 -javaagent 和 -Dauthlibinjector.yggdrasil.prefetched 参数</li>
     * </ol>
     * 预取失败时回退到直接传服务器 URL 的方式（-javaagent:jar=URL）。
     * 任何失败均记录到 stderr 但不中断启动（游戏可能仍能以离线模式运行）。
     */
    private void injectAuthlibInjector(LaunchProfile profile, Account account) throws IOException {
        String apiUrl = account.getAuthServerUrl();
        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IOException("YGGDRASIL 账号缺少 authServerUrl，无法注入 authlib-injector");
        }

        // 1. 确保 authlib-injector.jar 存在
        Path jarPath = config.getWorkDir().resolve("authlib-injector.jar");
        try {
            authlibInjectorManager.ensureJar(jarPath);
        } catch (IOException e) {
            throw new IOException("authlib-injector.jar 准备失败（皮肤站账号无法启动）: " + e.getMessage(), e);
        }
        if (!java.nio.file.Files.exists(jarPath)) {
            throw new IOException("authlib-injector.jar 不存在，无法启动皮肤站账号");
        }

        // 2. 预取 Yggdrasil API 元数据
        String prefetched = authlibInjectorManager.prefetchYggdrasilApi(apiUrl);

        // 3. 注入参数
        if (prefetched != null && !prefetched.isEmpty()) {
            // 预取方式（推荐）：-javaagent:jar + -Dauthlibinjector.yggdrasil.prefetched=<base64>
            profile.addJavaAgent(jarPath.toString(), null);
            profile.addJvmArg("-Dauthlibinjector.yggdrasil.prefetched=" + prefetched);
            System.err.println("[LaunchProfileBuilder] authlib-injector 注入成功（预取方式）");
        } else {
            // P2-5: 预取失败回退方式，写明显警告让用户在日志中看到皮肤加载失败的原因
            String normalizedUrl = com.pmcl.core.auth.YggdrasilAuthFlow.normalizeApiUrl(apiUrl);
            profile.addJavaAgent(jarPath.toString(), normalizedUrl);
            String warn = "[PMCL] 警告: authlib-injector 预取皮肤站 API 失败，已回退到运行时获取模式。"
                    + "若网络不通，皮肤/披风加载将失败，部分严格校验的服务器可能拒绝连接。"
                    + "皮肤站 URL: " + normalizedUrl;
            System.err.println("[LaunchProfileBuilder] " + warn);
            // 通过 addJvmArg 注入提示属性，authlib-injector 启动时会在日志中显示
            // 同时将警告写入游戏启动日志（通过 profile 的 gameArgs 前缀）
            profile.addJvmArg("-Dpmcl.authlibinjector.warning=" + warn);
        }
    }
}
