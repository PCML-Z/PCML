package com.pmcl.core.gamecontent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 某个版本游戏目录里的模组、截图、资源包、光影和投影。前四类只看这一层；投影可以在 schematics 的子目录里。 */
public final class VersionGameFiles {

    public static final String MODS = "mods";
    public static final String SCREENSHOTS = "screenshots";
    public static final String RESOURCE_PACKS = "resourcepacks";
    public static final String SHADER_PACKS = "shaderpacks";
    public static final String SCHEMATICS = "schematics";

    private VersionGameFiles() {}

    public static boolean allowed(String folder) {
        return MODS.equals(folder) || SCREENSHOTS.equals(folder) || RESOURCE_PACKS.equals(folder)
                || SHADER_PACKS.equals(folder) || SCHEMATICS.equals(folder);
    }

    public static Path directory(Path gameDir, String folder) throws IOException {
        if (gameDir == null || !allowed(folder)) throw new IOException("bad-folder");
        Path root = gameDir.toAbsolutePath().normalize();
        Path dir = root.resolve(folder).normalize();
        if (!dir.startsWith(root)) throw new IOException("bad-folder");
        Files.createDirectories(dir);
        return dir;
    }

    public static List<String> list(Path gameDir, String folder) throws IOException {
        Path dir = directory(gameDir, folder);
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path child : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                if (names.size() >= 200) break;
                String name = child.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (!child.startsWith(dir)) continue;
                names.add(name);
            }
        }
        return names;
    }

    public static void delete(Path gameDir, String folder, String name) throws IOException {
        Path target = child(gameDir, folder, name);
        if (!Files.exists(target)) return;
        if (Files.isDirectory(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
                for (Path path : paths) {
                    if (!path.startsWith(target)) throw new IOException("bad-name");
                    Files.deleteIfExists(path);
                }
            }
        } else {
            Files.deleteIfExists(target);
        }
    }

    /** 列出 schematics 目录及其子目录里的投影文件。不跟随符号链接，最多 8 层、400 个文件。 */
    public static List<Path> schematicFiles(Path schematicsDir) throws IOException {
        List<Path> files = new ArrayList<>();
        if (schematicsDir == null || !Files.isDirectory(schematicsDir) || Files.isSymbolicLink(schematicsDir)) {
            return files;
        }
        Path root = schematicsDir.toAbsolutePath().normalize();
        walkSchematics(root, root, files, 0);
        return files;
    }

    public static String schematicRelative(Path schematicsDir, Path file) {
        Path root = schematicsDir.toAbsolutePath().normalize();
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** 删除 schematics 子目录里的一个投影。相对路径里不能出现 .. 或跳出目录。 */
    public static void deleteSchematic(Path gameDir, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf('\0') >= 0
                || relative.indexOf('\\') >= 0 || relative.contains("..")
                || relative.startsWith("/") || relative.startsWith(".")) {
            throw new IOException("bad-name");
        }
        Path root = directory(gameDir, SCHEMATICS);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("bad-name");
        String fileName = target.getFileName() == null ? "" : target.getFileName().toString();
        if (!accepts(SCHEMATICS, fileName)) throw new IOException("bad-file");
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target)) throw new IOException("bad-file");
        Files.deleteIfExists(target);
    }

    public static void deleteSchematicFile(Path schematicsDir, Path file) throws IOException {
        if (schematicsDir == null || file == null) throw new IOException("bad-path");
        Path root = schematicsDir.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        String relative = schematicRelative(root, target);
        if (relative.isBlank() || relative.contains("..") || relative.startsWith("/") || relative.startsWith(".")) {
            throw new IOException("bad-path");
        }
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("bad-path");
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target)) throw new IOException("bad-file");
        String fileName = target.getFileName() == null ? "" : target.getFileName().toString();
        if (!accepts(SCHEMATICS, fileName)) throw new IOException("bad-file");
        Files.deleteIfExists(target);
    }

    private static void walkSchematics(Path root, Path dir, List<Path> files, int depth) throws IOException {
        if (depth > 8 || files.size() >= 400) return;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> children = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            for (Path child : children) {
                if (files.size() >= 400) return;
                if (Files.isSymbolicLink(child)) continue;
                String name = child.getFileName() == null ? "" : child.getFileName().toString();
                if (name.isBlank() || name.startsWith(".")) continue;
                Path normalized = child.toAbsolutePath().normalize();
                if (!normalized.startsWith(root)) continue;
                if (Files.isDirectory(normalized)) {
                    walkSchematics(root, normalized, files, depth + 1);
                } else if (Files.isRegularFile(normalized) && accepts(SCHEMATICS, name)) {
                    files.add(normalized);
                }
            }
        }
    }

    public static void importFile(Path gameDir, String folder, Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) throw new IOException("bad-file");
        String name = source.getFileName() == null ? "" : source.getFileName().toString();
        if (!accepts(folder, name)) throw new IOException("bad-file");
        Path target = child(gameDir, folder, name);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static boolean accepts(String folder, String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.toLowerCase();
        if (MODS.equals(folder)) return lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".litemod");
        if (SCREENSHOTS.equals(folder)) {
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        }
        if (RESOURCE_PACKS.equals(folder) || SHADER_PACKS.equals(folder)) return lower.endsWith(".zip");
        return SCHEMATICS.equals(folder) && (lower.endsWith(".litematic") || lower.endsWith(".schem")
                || lower.endsWith(".schematic") || lower.endsWith(".nbt"));
    }

    private static Path child(Path gameDir, String folder, String name) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")
                || ".".equals(name)) {
            throw new IOException("bad-name");
        }
        Path dir = directory(gameDir, folder);
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir)) throw new IOException("bad-name");
        return target;
    }
}
