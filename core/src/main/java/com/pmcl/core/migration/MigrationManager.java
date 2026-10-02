package com.pmcl.core.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.i18n.I18n;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 启动器数据迁移管理器：检测本机其他 Minecraft 启动器的数据目录，
 * 并将 versions / libraries / assets 复制到 PMCL 工作目录。
 * <p>
 * 支持的来源：
 * <ul>
 *   <li><b>HMCL</b>：只认配置里写下的游戏目录（{@code user-game-directories.json} 或 {@code hmcl.json}）</li>
 *   <li><b>LauncherX</b>：只认 {@code launcherx.json} 里 {@code GamePathList} 的路径</li>
 *   <li><b>PCL</b>：只认 {@code Setup.ini} 的 {@code LaunchFolder}，或游戏目录里的 PCL 标记</li>
 *   <li><b>系统默认</b>：macOS {@code ~/Library/Application Support/minecraft}，其它平台 {@code .minecraft}</li>
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
        private volatile long estimatedSize;   // 预估迁移大小（字节，仅 versions 目录）
        // M50 修复：异步估算的大小，完成后填充到 estimatedSize
        private final CompletableFuture<Long> estimatedSizeFuture;

        public Source(String name, Path configDir, Path gameRoot, long estimatedSize) {
            this(name, configDir, gameRoot, estimatedSize, CompletableFuture.completedFuture(estimatedSize));
        }

        public Source(String name, Path configDir, Path gameRoot,
                      long estimatedSize, CompletableFuture<Long> sizeFuture) {
            this.name = name;
            this.configDir = configDir;
            this.gameRoot = gameRoot;
            this.estimatedSize = estimatedSize;
            this.estimatedSizeFuture = sizeFuture;
            // 异步估算完成后更新 estimatedSize 字段
            sizeFuture.whenComplete((size, err) -> {
                if (err == null) this.estimatedSize = size;
            });
        }

        public String getName() { return name; }
        public Path getConfigDir() { return configDir; }
        public Path getGameRoot() { return gameRoot; }
        public long getEstimatedSize() { return estimatedSize; }
        /** M50: 异步估算的 Future，UI 可监听完成后刷新显示 */
        public CompletableFuture<Long> getEstimatedSizeFuture() { return estimatedSizeFuture; }

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
     * 只返回含 versions 目录、且能对上某个启动器配置或标记的来源。
     * 同一个游戏目录只出现一次。
     */
    public List<Source> detectSources() {
        return detectSources(Paths.get(System.getProperty("user.home")),
                System.getProperty("os.name", ""), System.getenv());
    }

    List<Source> detectSources(Path home, String osName, Map<String, String> env) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        boolean mac = os.contains("mac");
        boolean win = os.contains("win");
        if (env == null) env = Map.of();
        List<Source> result = new ArrayList<>();

        for (Path configDir : launcherXConfigDirs(home, mac, win, env)) {
            for (Path game : launcherXGameDirs(configDir.resolve("launcherx.json"))) {
                addClaim(result, "LauncherX", configDir, game, home);
            }
        }
        for (Path configDir : hmclConfigDirs(home, mac, win, env)) {
            for (Path game : hmclGameDirs(configDir, home)) {
                addClaim(result, "HMCL", configDir, game, home);
            }
        }
        for (Path setup : pclSetupFiles(home, win, env)) {
            Path configDir = setup.getParent();
            for (Path game : pclGameDirs(setup, configDir)) {
                addClaim(result, "PCL", configDir, game, home);
            }
        }

        Path official = officialMinecraft(home, mac, win, env);
        String officialLabel = markerName(official);
        if (officialLabel == null) officialLabel = I18n.t("migration.source.official");
        addClaim(result, officialLabel, null, official, home);

        for (Path candidate : wellKnownRoots(home, mac, win, env)) {
            if (claimed(result, candidate)) continue;
            String marked = markerName(candidate);
            if (marked != null) addClaim(result, marked, null, candidate, home);
        }
        return result;
    }

    private static List<Path> launcherXConfigDirs(Path home, boolean mac, boolean win, Map<String, String> env) {
        List<Path> dirs = new ArrayList<>();
        if (mac) {
            dirs.add(home.resolve("Library/Application Support/LauncherX"));
        } else if (win) {
            dirs.add(appData(home, env).resolve("LauncherX"));
            String local = env.get("LOCALAPPDATA");
            if (local != null && !local.isBlank()) dirs.add(Path.of(local).resolve("LauncherX"));
        } else {
            dirs.add(home.resolve(".config/LauncherX"));
            dirs.add(home.resolve(".launcherx"));
        }
        return dirs;
    }

    /** 只读 GamePathList，不取文件里出现的第一个 Path。 */
    private static List<Path> launcherXGameDirs(Path configFile) {
        List<Path> paths = new ArrayList<>();
        JsonObject root = readJson(configFile);
        if (root == null) return paths;
        collectGamePathList(root, paths);
        if (root.has("VariableConfigurationDic") && root.get("VariableConfigurationDic").isJsonObject()) {
            collectGamePathList(root.getAsJsonObject("VariableConfigurationDic"), paths);
        }
        return paths;
    }

    private static void collectGamePathList(JsonObject owner, List<Path> paths) {
        if (!owner.has("GamePathList")) return;
        JsonElement list = owner.get("GamePathList");
        JsonElement items = list;
        if (list.isJsonObject() && list.getAsJsonObject().has("Value")) {
            items = list.getAsJsonObject().get("Value");
        }
        if (items == null || !items.isJsonArray()) return;
        for (JsonElement item : items.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject obj = item.getAsJsonObject();
            if (!obj.has("Path") || !obj.get("Path").isJsonPrimitive()) continue;
            String value = obj.get("Path").getAsString();
            if (value != null && !value.isBlank()) paths.add(Path.of(value));
        }
    }

    private static List<Path> hmclConfigDirs(Path home, boolean mac, boolean win, Map<String, String> env) {
        List<Path> dirs = new ArrayList<>();
        if (mac) {
            dirs.add(home.resolve("Library/Application Support/hmcl"));
            dirs.add(home.resolve(".hmcl"));
        } else if (win) {
            Path app = appData(home, env);
            dirs.add(app.resolve("hmcl"));
            dirs.add(app.resolve(".hmcl"));
        } else {
            dirs.add(home.resolve(".hmcl"));
            dirs.add(home.resolve(".local/share/hmcl"));
        }
        return dirs;
    }

    private static List<Path> hmclGameDirs(Path configDir, Path home) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        JsonObject directories = readJson(configDir.resolve("config/user-game-directories.json"));
        if (directories != null && directories.has("directories") && directories.get("directories").isJsonArray()) {
            for (JsonElement item : directories.getAsJsonArray("directories")) {
                if (!item.isJsonObject()) continue;
                JsonObject obj = item.getAsJsonObject();
                if (!obj.has("path") || !obj.get("path").isJsonPrimitive()) continue;
                String value = obj.get("path").getAsString();
                if (value != null && !value.isBlank()) paths.add(Path.of(value));
            }
        }
        JsonObject hmcl = readJson(configDir.resolve("hmcl.json"));
        if (hmcl != null && hmcl.has("configurations") && hmcl.get("configurations").isJsonObject()) {
            for (var entry : hmcl.getAsJsonObject("configurations").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject profile = entry.getValue().getAsJsonObject();
                if (!profile.has("gameDir") || !profile.get("gameDir").isJsonPrimitive()) continue;
                String gameDir = profile.get("gameDir").getAsString();
                if (gameDir == null || gameDir.isBlank()) continue;
                boolean relative = profile.has("useRelativePath")
                        && profile.get("useRelativePath").isJsonPrimitive()
                        && profile.get("useRelativePath").getAsBoolean();
                Path path = Path.of(gameDir);
                if (relative || !path.isAbsolute()) path = home.resolve(gameDir);
                paths.add(path);
            }
        }
        return new ArrayList<>(paths);
    }

    private static List<Path> pclSetupFiles(Path home, boolean win, Map<String, String> env) {
        List<Path> files = new ArrayList<>();
        files.add(home.resolve("PCL/Setup.ini"));
        files.add(home.resolve(".pcl/Setup.ini"));
        if (win) {
            files.add(appData(home, env).resolve("PCL/Setup.ini"));
            String local = env.get("LOCALAPPDATA");
            if (local != null && !local.isBlank()) files.add(Path.of(local).resolve("PCL/Setup.ini"));
        }
        return files;
    }

    private static List<Path> pclGameDirs(Path setupFile, Path configDir) {
        List<Path> paths = new ArrayList<>();
        if (!Files.isRegularFile(setupFile)) return paths;
        String text = readText(setupFile);
        if (text == null) return paths;
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (!line.regionMatches(true, 0, "LaunchFolder=", 0, "LaunchFolder=".length())) continue;
            String value = line.substring("LaunchFolder=".length()).trim();
            if (!value.isEmpty()) paths.add(Path.of(value));
        }
        if (paths.isEmpty() && configDir != null) paths.add(configDir.resolve(".minecraft"));
        return paths;
    }

    private static Path officialMinecraft(Path home, boolean mac, boolean win, Map<String, String> env) {
        if (mac) return home.resolve("Library/Application Support/minecraft");
        if (win) return appData(home, env).resolve(".minecraft");
        return home.resolve(".minecraft");
    }

    private static List<Path> wellKnownRoots(Path home, boolean mac, boolean win, Map<String, String> env) {
        List<Path> roots = new ArrayList<>();
        if (mac) {
            roots.add(home.resolve("Library/Application Support/.minecraft"));
            roots.add(home.resolve("Library/Application Support/minecraft"));
        } else if (win) {
            roots.add(appData(home, env).resolve(".minecraft"));
        } else {
            roots.add(home.resolve(".minecraft"));
        }
        return roots;
    }

    /** 游戏目录里的启动器标记。没有标记就返回 null，避免把别的目录安到某个启动器头上。 */
    private static String markerName(Path root) {
        if (root == null || !Files.isDirectory(root)) return null;
        Path lxProfiles = root.resolve("lx_profiles.json");
        if (Files.isRegularFile(lxProfiles)) {
            JsonObject profiles = readJson(lxProfiles);
            if (profiles != null && profiles.has("launcherVersion") && profiles.get("launcherVersion").isJsonObject()) {
                JsonObject version = profiles.getAsJsonObject("launcherVersion");
                String name = version.has("name") && version.get("name").isJsonPrimitive()
                        ? version.get("name").getAsString() : "";
                if (name != null && name.toLowerCase(Locale.ROOT).contains("launcherx")) return "LauncherX";
            }
        }
        if (Files.isRegularFile(root.resolve("hmclversion.cfg"))) return "HMCL";
        if (Files.isDirectory(root.resolve("PCL")) || Files.isRegularFile(root.resolve("PCL.ini"))) return "PCL";
        return null;
    }

    private void addClaim(List<Source> result, String name, Path configDir, Path gameRoot, Path home) {
        Path root = normalize(gameRoot);
        if (root == null || name == null || name.isBlank()) return;
        if (samePath(root, targetRoot) || samePath(root, home)) return;
        for (int i = 0; i < result.size(); i++) {
            Source existing = result.get(i);
            if (!samePath(existing.getGameRoot(), root)) continue;
            if (nameHas(existing.getName(), name)) return;
            result.set(i, new Source(existing.getName() + " / " + name,
                    existing.getConfigDir() != null ? existing.getConfigDir() : configDir,
                    existing.getGameRoot(), existing.getEstimatedSize(), existing.getEstimatedSizeFuture()));
            return;
        }
        addSourceIfValid(result, name, configDir, root);
    }

    private static boolean claimed(List<Source> result, Path gameRoot) {
        Path root = normalize(gameRoot);
        if (root == null) return false;
        for (Source source : result) {
            if (samePath(source.getGameRoot(), root)) return true;
        }
        return false;
    }

    private static boolean nameHas(String existing, String name) {
        if (existing == null) return false;
        for (String part : existing.split(" / ")) {
            if (part.equals(name)) return true;
        }
        return false;
    }

    private static Path appData(Path home, Map<String, String> env) {
        String appdata = env.get("APPDATA");
        if (appdata == null || appdata.isBlank()) appdata = home.resolve("AppData/Roaming").toString();
        return Path.of(appdata);
    }

    private static Path normalize(Path path) {
        if (path == null) return null;
        try {
            return path.toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean samePath(Path left, Path right) {
        Path a = normalize(left);
        Path b = normalize(right);
        return a != null && a.equals(b);
    }

    private static JsonObject readJson(Path file) {
        String text = readText(file);
        if (text == null || text.isBlank()) return null;
        try {
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readText(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        try {
            return Files.readString(file, Charset.forName("GBK"));
        } catch (Exception e) {
            return null;
        }
    }

    private void addSourceIfValid(List<Source> result, String name, Path configDir, Path gameRoot) {
        if (gameRoot == null || !Files.isDirectory(gameRoot.resolve("versions"))) return;
        // M50 修复：异步估算 versions 目录大小，避免阻塞 detectSources 调用线程
        Path versionsDir = gameRoot.resolve("versions");
        CompletableFuture<Long> sizeFuture = CompletableFuture.supplyAsync(() -> estimateVersionsSize(versionsDir));
        result.add(new Source(name, configDir, gameRoot, 0L, sizeFuture));
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
     * M49 修复：校验 resolved path 始终在 dstRoot 内，防止符号链接/相对路径穿越。
     */
    private void copyDirIfExists(Path src, Path dst, String label, Consumer<String> progress) throws IOException {
        if (!Files.isDirectory(src)) return;
        Files.createDirectories(dst);
        if (progress != null) progress.accept("正在复制 " + label + " …");

        final Path srcRoot = src.toAbsolutePath().normalize();
        final Path dstRoot = dst.toAbsolutePath().normalize();
        Files.walkFileTree(srcRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // 跳过符号链接目录：walkFileTree 默认不跟随链接，但防御性检查
                if (Files.isSymbolicLink(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path relative = srcRoot.relativize(dir);
                Path target = dstRoot.resolve(relative).normalize();
                // 校验 target 未逃逸出 dstRoot
                if (!target.startsWith(dstRoot)) {
                    throw new IOException("路径穿越检测: " + dir + " -> " + target);
                }
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // 跳过符号链接文件：防止恶意 modpack 植入指向 ~/.ssh/id_rsa 等敏感文件的符号链接
                if (Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = srcRoot.relativize(file);
                Path target = dstRoot.resolve(relative).normalize();
                // 校验 target 未逃逸出 dstRoot
                if (!target.startsWith(dstRoot)) {
                    throw new IOException("路径穿越检测: " + file + " -> " + target);
                }
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
