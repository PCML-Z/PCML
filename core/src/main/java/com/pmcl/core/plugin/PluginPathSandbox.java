package com.pmcl.core.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Path sandbox for plugin filesystem / NBT / download targets.
 * <p>
 * Rules:
 * <ul>
 *   <li>Plugin data directory is always allowed</li>
 *   <li>Launcher work directory requires {@code FILESYSTEM} (checked by caller)</li>
 *   <li>Sensitive host files under workDir are always denied</li>
 *   <li>Symlinks are rejected (no follow escapes)</li>
 * </ul>
 */
final class PluginPathSandbox {

    private static final Set<String> DENIED_TOP_LEVEL = Set.of(
            "plugins",
            "accounts.json",
            ".keyfile",
            "preferences.json",
            "preferences",
            "plugins.json"
    );

    private PluginPathSandbox() {}

    /**
     * @param workDirAccessAllowed whether caller already checked FILESYSTEM for workDir paths
     */
    static Path requireAccessible(Path path, Path dataDir, Path workDir, boolean workDirAccessAllowed) {
        Path abs = normalize(path);
        Path data = dataDir.toAbsolutePath().normalize();
        if (abs.startsWith(data)) {
            return enforceNoSymlinkEscape(abs, data, null);
        }
        if (!workDirAccessAllowed) {
            throw new SecurityException("Path outside plugin data requires FILESYSTEM: " + abs);
        }
        Path work = workDir.toAbsolutePath().normalize();
        if (!abs.startsWith(work)) {
            throw new SecurityException("Path outside work dir / plugin data: " + abs);
        }
        denySensitive(work, abs);
        return enforceNoSymlinkEscape(abs, data, work);
    }

    static boolean isUnderPluginData(Path absPath, Path dataDir) {
        if (absPath == null || dataDir == null) return false;
        Path abs = absPath.toAbsolutePath().normalize();
        Path data = dataDir.toAbsolutePath().normalize();
        if (!abs.startsWith(data)) return false;
        try {
            enforceNoSymlinkEscape(abs, data, null);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Resolve a path that must stay under workDir (after sensitive denylist + symlink checks). */
    static Path requireUnderWorkDir(Path path, Path workDir) {
        Path abs = normalize(path);
        Path work = workDir.toAbsolutePath().normalize();
        if (!abs.startsWith(work)) {
            throw new SecurityException("Path outside work dir: " + abs);
        }
        denySensitive(work, abs);
        return enforceNoSymlinkEscape(abs, work, work);
    }

    /** True if path is under workDir mods trees (global / versions / instances). */
    static boolean isModJarPath(Path path, Path workDir) {
        if (path == null || workDir == null) return false;
        Path abs = path.toAbsolutePath().normalize();
        Path work = workDir.toAbsolutePath().normalize();
        if (!abs.startsWith(work)) return false;
        denySensitive(work, abs);
        String name = abs.getFileName() != null ? abs.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (!(name.endsWith(".jar") || name.endsWith(".jar.disabled"))) return false;
        String rel = work.relativize(abs).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return rel.startsWith("mods/")
                || rel.contains("/mods/")
                || rel.equals("mods")
                || rel.endsWith("/mods");
    }

    static void denySensitive(Path workDir, Path abs) {
        Path work = workDir.toAbsolutePath().normalize();
        Path target = abs.toAbsolutePath().normalize();
        if (!target.startsWith(work)) return;
        Path rel;
        try {
            rel = work.relativize(target);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Path not relative to work dir: " + target);
        }
        if (rel.getNameCount() == 0) {
            throw new SecurityException("Access to work dir root denied");
        }
        String top = rel.getName(0).toString().toLowerCase(Locale.ROOT);
        if (DENIED_TOP_LEVEL.contains(top)) {
            throw new SecurityException("Access to sensitive path denied: " + top);
        }
        String fileName = target.getFileName() != null
                ? target.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (fileName.equals("accounts.json") || fileName.equals(".keyfile")
                || fileName.equals("preferences.json") || fileName.equals("plugins.json")
                || fileName.startsWith("preferences.")) {
            throw new SecurityException("Access to sensitive file denied: " + fileName);
        }
    }

    private static Path normalize(Path path) {
        if (path == null) throw new IllegalArgumentException("path is null");
        return path.toAbsolutePath().normalize();
    }

    private static Path enforceNoSymlinkEscape(Path abs, Path dataDir, Path workDirOrNull) {
        try {
            // Reject if any existing ancestor is a symlink
            Path cur = abs;
            while (cur != null) {
                if (Files.exists(cur, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cur)) {
                    throw new SecurityException("Symlink path rejected: " + cur);
                }
                cur = cur.getParent();
            }
            if (Files.exists(abs, LinkOption.NOFOLLOW_LINKS)) {
                Path real = abs.toRealPath(LinkOption.NOFOLLOW_LINKS);
                Path dataReal = Files.exists(dataDir) ? dataDir.toRealPath() : dataDir.toAbsolutePath().normalize();
                boolean underData = real.startsWith(dataReal);
                boolean underWork = false;
                if (workDirOrNull != null) {
                    Path workReal = Files.exists(workDirOrNull)
                            ? workDirOrNull.toRealPath()
                            : workDirOrNull.toAbsolutePath().normalize();
                    underWork = real.startsWith(workReal);
                    if (underWork) denySensitive(workReal, real);
                }
                if (!underData && !underWork) {
                    throw new SecurityException("Resolved path escapes sandbox: " + real);
                }
                return abs;
            }
            // Non-existent path: verify nearest existing parent stays in sandbox
            Path parent = abs.getParent();
            while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                parent = parent.getParent();
            }
            if (parent != null) {
                if (Files.isSymbolicLink(parent)) {
                    throw new SecurityException("Symlink parent rejected: " + parent);
                }
                Path realParent = parent.toRealPath();
                Path dataReal = Files.exists(dataDir) ? dataDir.toRealPath() : dataDir.toAbsolutePath().normalize();
                boolean underData = realParent.startsWith(dataReal);
                boolean underWork = false;
                if (workDirOrNull != null) {
                    Path workReal = Files.exists(workDirOrNull)
                            ? workDirOrNull.toRealPath()
                            : workDirOrNull.toAbsolutePath().normalize();
                    underWork = realParent.startsWith(workReal);
                }
                if (!underData && !underWork) {
                    throw new SecurityException("Parent path escapes sandbox: " + realParent);
                }
            }
            return abs;
        } catch (IOException e) {
            throw new SecurityException("Path resolution failed: " + e.getMessage(), e);
        }
    }
}
