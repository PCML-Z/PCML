package com.pmcl.core.migration;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 启动器数据迁移管理器：检测本机其他 Minecraft 启动器的数据目录，
 * 并将 versions / libraries / assets 复制到 PMCL 工作目录。
 * <p>
 * 支持的来源：
 * <ul>
 *   <li><b>HMCL</b>：{@code ~/.hmcl} 配置目录 + {@code ~/.minecraft} 游戏目录（HMCL 默认使用系统标准 .minecraft）</li>
 *   <li><b>PCL / Plain Craft Launcher</b>：{@code ~/PCL} 或 {@code ~/.pcl}，游戏目录在同目录下或 {@code ~/.minecraft}</li>
 *   <li><b>系统默认</b>：{@code ~/.minecraft}（macOS: {@code ~/Library/Application Support/minecraft}）</li>
 * </ul>
 * <p>
 * 迁移策略：使用 {@link StandardCopyOption#REPLACE_EXISTING} 覆盖目标，已存在的同名文件会被覆盖，
 * 目标中已有的其他文件保留。迁移只复制数据，不删除来源。
 */
public final class MigrationManager {

    /** 检测到的启动器来源 */
    public static final class Source {
        private final String name;          // 显示名（如 "HMCL"）
        private final Path configDir;       // 启动器配置目录（用于识别，可能为 null）
        private final Path gameRoot;        // 游戏根目录（含 versions/libraries/assets）
        private final long estimatedSize;   // 预估迁移大小（字节，仅 versions 目录）

        public Source(String name, Path configDir, Path gameRoot, long estimatedSize) {
            this.name = name;
            this.configDir = configDir;
            this.gameRoot = gameRoot;
            this.estimatedSize = estimatedSize;
        }

        public String getName() { return name; }
        public Path getConfigDir() { return configDir; }
        public Path getGameRoot() { return gameRoot; }
        public long getEstimatedSize() { return estimatedSize; }

        /** 是否存在可迁移的 versions 目录 */
        public boolean hasVersions() {
            return gameRoot != null && Files.isDirectory(gameRoot.resolve("versions"));
        }
    }

    private final Path targetRoot;

    public MigrationManager(Path targetRoot) {
        this.targetRoot = targetRoot;
    }

    /**
     * 扫描本机已安装的其他启动器与系统默认 Minecraft 目录。
     * 仅返回实际存在 versions 目录的来源。
     * <p>
     * macOS 上的实际安装位置：
     * <ul>
     *   <li><b>HMCL</b>：配置 {@code ~/.hmcl}，游戏目录 {@code ~/Library/Application Support/.minecraft}（HMCL 在 macOS 上的默认）</li>
     *   <li><b>LauncherX</b>：配置 {@code ~/Library/Application Support/LauncherX/launcherx.json}，
     *       游戏目录从配置文件的 {@code GamePathList} 字段解析</li>
     *   <li><b>官方启动器</b>：{@code ~/Library/Application Support/minecraft}</li>
     * </ul>
     */
    public List<Source> detectSources() {
        List<Source> result = new ArrayList<>();
        Path home = Paths.get(System.getProperty("user.home"));
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        boolean isMac = os.contains("mac");
        boolean isWin = os.contains("win");

        // macOS 候选游戏根目录（HMCL 与 LauncherX 常共用同一个）
        Path macAppSupportDotMc = home.resolve("Library").resolve("Application Support").resolve(".minecraft");
        Path macAppSupportMc   = home.resolve("Library").resolve("Application Support").resolve("minecraft");

        // === HMCL ===
        // macOS: 配置在 ~/.hmcl，游戏目录通常在 ~/Library/Application Support/.minecraft
        // Windows: 配置在 %APPDATA%\.hmcl，游戏目录在 %APPDATA%\.minecraft
        Path hmclConfig = isMac ? home.resolve(".hmcl")
                          : isWin ? Paths.get(System.getenv().getOrDefault("APPDATA", home.toString()), ".hmcl")
                          : home.resolve(".hmcl");
        Path hmclGameRoot = isMac ? macAppSupportDotMc
                           : isWin ? Paths.get(System.getenv().getOrDefault("APPDATA", home.toString()), ".minecraft")
                           : home.resolve(".minecraft");
        // HMCL 可能用系统默认 minecraft 目录
        if (!isMinecraftRoot(hmclGameRoot) && isMac && isMinecraftRoot(macAppSupportMc)) {
            hmclGameRoot = macAppSupportMc;
        }
        if (Files.isDirectory(hmclConfig) || isMinecraftRoot(hmclGameRoot)) {
            addSourceIfValid(result, "HMCL", hmclConfig, hmclGameRoot);
        }

        // === LauncherX ===
        // macOS: 配置在 ~/Library/Application Support/LauncherX/launcherx.json，游戏路径从配置文件解析
        // 其他平台: 配置在 ~/.launcherx 或 ~/LauncherX
        Path lxConfigDir = isMac ? home.resolve("Library").resolve("Application Support").resolve("LauncherX")
                           : home.resolve(".launcherx");
        Path lxGameRoot = null;
        if (Files.isDirectory(lxConfigDir)) {
            lxGameRoot = parseLauncherXGamePath(lxConfigDir.resolve("launcherx.json"));
        }
        // 解析失败则回退到默认 .minecraft
        if (lxGameRoot == null) {
            lxGameRoot = isMac ? macAppSupportDotMc
                        : isWin ? Paths.get(System.getenv().getOrDefault("APPDATA", home.toString()), ".minecraft")
                        : home.resolve(".minecraft");
        }
        if (Files.isDirectory(lxConfigDir) || isMinecraftRoot(lxGameRoot)) {
            addSourceIfValid(result, "Launcher X", lxConfigDir, lxGameRoot);
        }

        // === 系统默认 Minecraft（官方启动器，兜底）===
        Path officialMc = isMac ? macAppSupportMc
                         : isWin ? Paths.get(System.getenv().getOrDefault("APPDATA", home.toString()), ".minecraft")
                         : home.resolve(".minecraft");
        boolean alreadyAdded = result.stream().anyMatch(s -> officialMc.equals(s.getGameRoot()));
        if (!alreadyAdded && isMinecraftRoot(officialMc)) {
            addSourceIfValid(result, "系统 Minecraft", null, officialMc);
        }

        return result;
    }

    /**
     * 解析 LauncherX 的 launcherx.json 配置文件，提取第一个游戏路径。
     * 配置结构：{@code {"GamePathList": {"$type":"...", "Value":[{"Path":"/.../"}]}}}
     * @return 第一个游戏路径，解析失败返回 null
     */
    private Path parseLauncherXGamePath(Path configFile) {
        if (!Files.isRegularFile(configFile)) return null;
        try {
            String json = Files.readString(configFile);
            // 用正则提取 "Path": "..." —— 避免引入完整 JSON 库的依赖
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"Path\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(json);
            if (m.find()) {
                Path p = Paths.get(m.group(1));
                return Files.isDirectory(p) ? p : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void addSourceIfValid(List<Source> result, String name, Path configDir, Path gameRoot) {
        if (gameRoot == null || !Files.isDirectory(gameRoot.resolve("versions"))) return;
        long size = estimateVersionsSize(gameRoot.resolve("versions"));
        result.add(new Source(name, configDir, gameRoot, size));
    }

    /** 判断目录是否为 Minecraft 根目录（含 versions 子目录） */
    private boolean isMinecraftRoot(Path root) {
        return root != null && Files.isDirectory(root.resolve("versions"));
    }

    /** 估算 versions 目录总大小（字节） */
    private long estimateVersionsSize(Path versionsDir) {
        final long[] total = {0};
        try {
            Files.walkFileTree(versionsDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return total[0];
    }

    /**
     * 迁移指定来源到 PMCL 工作目录。
     * 复制 versions / libraries / assets 三个核心目录（存在则复制）。
     * 已存在的目标文件会被覆盖（REPLACE_EXISTING），但目标中独有的文件保留。
     *
     * @param source    来源
     * @param progress  进度回调（接收阶段描述文字）
     */
    public void migrate(Source source, Consumer<String> progress) throws IOException {
        Path src = source.getGameRoot();
        if (src == null) throw new IOException("来源游戏目录为空");

        // 依次复制三个核心目录
        copyDirIfExists(src.resolve("versions"), targetRoot.resolve("versions"), "versions", progress);
        copyDirIfExists(src.resolve("libraries"), targetRoot.resolve("libraries"), "libraries", progress);
        copyDirIfExists(src.resolve("assets"), targetRoot.resolve("assets"), "assets", progress);

        if (progress != null) progress.accept("迁移完成");
    }

    /**
     * 递归复制源目录到目标目录（覆盖已存在文件，保留目标独有文件）。
     */
    private void copyDirIfExists(Path src, Path dst, String label, Consumer<String> progress) throws IOException {
        if (!Files.isDirectory(src)) return;
        Files.createDirectories(dst);
        if (progress != null) progress.accept("正在复制 " + label + " …");

        final Path srcRoot = src;
        final Path dstRoot = dst;
        Files.walkFileTree(srcRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = srcRoot.relativize(dir);
                Path target = dstRoot.resolve(relative);
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = srcRoot.relativize(file);
                Path target = dstRoot.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 将字节数格式化为人类可读字符串（KB/MB/GB） */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
