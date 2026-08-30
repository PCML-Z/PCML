package com.pmcl.core.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.version.VersionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 推导游戏工作目录（gameDir）以及其下的 mods/config 等。
 * <p>
 * 开启版本隔离时，普通版本改用 {@code instances/<versionId>/}。若直接切过去、
 * 不把原先共享目录里的 mods/config 带上，游戏里就像模组数据丢了。
 * 整合包（版本目录里已有 mods/ 或实例标记）本身就是隔离目录，不能再搬一次。
 */
public final class GameDirResolver {

    private static final String[] GAME_SUBDIRS = {
            "mods", "saves", "config", "resourcepacks", "shaderpacks", "screenshots", "logs"
    };
    /** 跟模组走的目录：jar、配置、材质/光影。存档不自动复制，避免每个版本各拷一份世界。 */
    private static final String[] SEED_SUBDIRS = {
            "mods", "config", "resourcepacks", "shaderpacks"
    };
    private static final String SEEDED_MARKER = ".pmcl-isolation-seeded";

    private final LauncherConfig config;
    private final Preferences preferences;

    public GameDirResolver(LauncherConfig config, Preferences preferences) {
        this.config = config;
        this.preferences = preferences;
    }

    /**
     * 与启动时 {@code gameDir} 对齐。
     *
     * @param versionId 本地版本 ID
     * @param mcRoot    该版本所属 Minecraft 根目录；null 时自行推导
     */
    public Path resolveGameDir(String versionId, Path mcRoot) {
        requireSafeVersionId(versionId);
        Path jsonPath = findVersionJson(versionId);
        Path versionDir = jsonPath != null ? jsonPath.getParent() : null;
        Path root = mcRoot;
        if (root == null && versionDir != null) {
            Path versionsDir = versionDir.getParent();
            if (versionsDir != null) {
                root = versionsDir.getParent();
            }
        }
        if (root == null) {
            root = config.getWorkDir();
        }

        // 整合包 / 已按版本目录隔离：继续用 versions/<id>/，不要搬到空的 instances/
        if (versionDir != null && isSelfContainedVersionDir(versionDir)) {
            return versionDir;
        }

        if (preferences != null && preferences.isVersionIsolation()) {
            Path instanceDir = isolatedInstanceDir(versionId);
            ensureGameSubdirs(instanceDir);
            seedIsolatedDir(instanceDir, versionDir, root, versionId);
            return instanceDir;
        }
        return root;
    }

    public Path resolveGameDir(String versionId) {
        return resolveGameDir(versionId, null);
    }

    /** {@code resolveGameDir(versionId)/mods} */
    public Path resolveModsDir(String versionId) {
        return resolveGameDir(versionId).resolve("mods");
    }

    public static void requireSafeVersionId(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("非法版本 ID: " + versionId);
        }
        if (versionId.contains("..") || versionId.contains("/") || versionId.contains("\\")
                || versionId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法版本 ID: " + versionId);
        }
    }

    static boolean isSelfContainedVersionDir(Path versionDir) {
        return Files.isDirectory(versionDir.resolve("mods"))
                || Files.exists(versionDir.resolve("instance.json"))
                || Files.exists(versionDir.resolve("modpack.json"));
    }

    private Path isolatedInstanceDir(String versionId) {
        Path instancesRoot = config.getWorkDir().resolve("instances").toAbsolutePath().normalize();
        Path instanceDir = instancesRoot.resolve(versionId).normalize();
        if (!instanceDir.startsWith(instancesRoot)) {
            throw new IllegalArgumentException("versionId path escapes instances dir: " + versionId);
        }
        return instanceDir;
    }

    private static void ensureGameSubdirs(Path gameDir) {
        try {
            Files.createDirectories(gameDir);
            for (String sub : GAME_SUBDIRS) {
                Files.createDirectories(gameDir.resolve(sub));
            }
        } catch (IOException e) {
            throw new RuntimeException("无法创建版本隔离目录: " + gameDir, e);
        }
    }

    /**
     * 第一次用隔离目录时，把共享 gameDir 里的模组相关文件拷进去（不覆盖已有文件）。
     * 用标记文件保证只灌一次，避免用户删掉的模组又从全局目录冒回来。
     */
    void seedIsolatedDir(Path instanceDir, Path versionDir, Path mcRoot, String versionId) {
        Path marker = instanceDir.resolve(SEEDED_MARKER);
        if (Files.exists(marker)) {
            return;
        }
        String inheritsFrom = readInheritsFrom(versionDir, versionId);
        try {
            for (String sub : SEED_SUBDIRS) {
                if ("mods".equals(sub)) {
                    seedMods(instanceDir.resolve("mods"), versionDir, mcRoot, versionId, inheritsFrom);
                } else {
                    for (Path srcRoot : seedRoots(versionDir, mcRoot)) {
                        copyTreeIfAbsent(srcRoot.resolve(sub), instanceDir.resolve(sub));
                    }
                }
            }
            Files.writeString(marker, "1", StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[GameDirResolver] 灌入隔离目录失败 " + instanceDir + ": " + e.getMessage());
        }
    }

    private List<Path> seedRoots(Path versionDir, Path mcRoot) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        if (versionDir != null) {
            roots.add(versionDir);
        }
        if (mcRoot != null) {
            roots.add(mcRoot);
        }
        Path work = config.getWorkDir();
        if (work != null) {
            roots.add(work);
        }
        return new ArrayList<>(roots);
    }

    private void seedMods(Path destMods, Path versionDir, Path mcRoot,
                          String versionId, String inheritsFrom) throws IOException {
        Files.createDirectories(destMods);
        Set<String> versionKeys = new LinkedHashSet<>();
        if (versionId != null && !versionId.isBlank()) {
            versionKeys.add(versionId);
        }
        if (inheritsFrom != null && !inheritsFrom.isBlank()) {
            versionKeys.add(inheritsFrom);
        }
        for (Path srcRoot : seedRoots(versionDir, mcRoot)) {
            Path srcMods = srcRoot.resolve("mods");
            if (!Files.isDirectory(srcMods)) {
                continue;
            }
            copyLooseFiles(srcMods, destMods);
            for (String key : versionKeys) {
                Path versioned = srcMods.resolve(key).normalize();
                if (Files.isDirectory(versioned) && versioned.startsWith(srcMods.toAbsolutePath().normalize())) {
                    copyTreeIfAbsent(versioned, destMods);
                }
            }
        }
    }

    private static void copyLooseFiles(Path srcDir, Path destDir) throws IOException {
        if (!Files.isDirectory(srcDir)) {
            return;
        }
        Files.createDirectories(destDir);
        Path destAbs = destDir.toAbsolutePath().normalize();
        try (Stream<Path> stream = Files.list(srcDir)) {
            for (Path src : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(src) || Files.isSymbolicLink(src)) {
                    continue;
                }
                Path dest = destAbs.resolve(src.getFileName().toString()).normalize();
                if (!dest.startsWith(destAbs) || Files.exists(dest)) {
                    continue;
                }
                Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static void copyTreeIfAbsent(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        Files.createDirectories(target);
        Path srcAbs = source.toAbsolutePath().normalize();
        Path dstAbs = target.toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                if (src.equals(source)) {
                    continue;
                }
                Path rel = srcAbs.relativize(src.toAbsolutePath().normalize());
                Path dst = dstAbs.resolve(rel).normalize();
                if (!dst.startsWith(dstAbs)) {
                    continue;
                }
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else if (!Files.exists(dst) && !Files.isSymbolicLink(src)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private Path findVersionJson(String versionId) {
        for (Path dir : versionJsonDirs()) {
            Path jsonPath = dir.resolve(versionId).resolve(versionId + ".json");
            if (Files.exists(jsonPath)) {
                return jsonPath;
            }
        }
        return null;
    }

    private List<Path> versionJsonDirs() {
        List<Path> dirs = new ArrayList<>();
        Path pmcl = config.getVersionsDir();
        if (pmcl != null) {
            dirs.add(pmcl);
        }
        for (Path d : VersionManager.detectAllMinecraftVersionsDirs()) {
            if (!dirs.contains(d)) {
                dirs.add(d);
            }
        }
        if (preferences != null) {
            for (String root : preferences.getExtraMinecraftRoots()) {
                try {
                    Path versionsDir = Path.of(root).resolve("versions");
                    if (Files.isDirectory(versionsDir) && !dirs.contains(versionsDir)) {
                        dirs.add(versionsDir);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return dirs;
    }

    private static String readInheritsFrom(Path versionDir, String versionId) {
        if (versionDir == null || versionId == null) {
            return null;
        }
        Path json = versionDir.resolve(versionId + ".json");
        if (!Files.isRegularFile(json)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.has("inheritsFrom") && !root.get("inheritsFrom").isJsonNull()) {
                String v = root.get("inheritsFrom").getAsString();
                return (v != null && !v.isBlank()) ? v : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
