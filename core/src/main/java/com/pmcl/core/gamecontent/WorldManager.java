package com.pmcl.core.gamecontent;

import java.io.IOException;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 世界 / 存档管理：扫描、备份（zip）、恢复、导入。
 * <p>
 * Minecraft 的 saves 目录下每个子目录即一个世界，含 level.dat。
 * 备份格式：将整个世界目录压缩为 zip 到 backups/ 目录。
 */
public final class WorldManager {

    private final Path savesDir;
    private final Path backupsDir;

    public WorldManager(Path workDir) {
        this.savesDir = workDir.resolve("saves");
        this.backupsDir = workDir.resolve("backups").resolve("worlds");
    }

    public Path getSavesDir() { return savesDir; }
    public Path getBackupsDir() { return backupsDir; }

    /** 单个世界信息 */
    public static final class WorldInfo {
        private String name;
        private Path dir;
        private long lastModified;
        private long sizeBytes;
        private String source;

        public WorldInfo(String name, Path dir, long lastModified, long sizeBytes) {
            this(name, dir, lastModified, sizeBytes, "PMCL");
        }
        public WorldInfo(String name, Path dir, long lastModified, long sizeBytes, String source) {
            this.name = name; this.dir = dir;
            this.lastModified = lastModified; this.sizeBytes = sizeBytes;
            this.source = source;
        }
        public String getName() { return name; }
        public Path getDir() { return dir; }
        public long getLastModified() { return lastModified; }
        public long getSizeBytes() { return sizeBytes; }
        public String getSource() { return source; }
    }

    /** 扫描默认 saves 目录（~/.pmcl/saves） */
    public List<WorldInfo> listWorlds() throws IOException {
        return listWorlds(savesDir, "PMCL");
    }

    /**
     * 扫描指定 saves 目录下的所有世界。
     * @param savesDir 某个 saves 目录（如 ~/.pmcl/saves、整合包 versions/&lt;id&gt;/saves、外部启动器 saves）
     * @param source   来源标签（用于 UI 区分世界归属）
     */
    public List<WorldInfo> listWorlds(Path savesDir, String source) throws IOException {
        List<WorldInfo> result = new ArrayList<>();
        if (!Files.isDirectory(savesDir)) return result;
        try (Stream<Path> stream = Files.list(savesDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Path levelDat = dir.resolve("level.dat");
                if (!Files.exists(levelDat)) return;
                try {
                    long size = dirSize(dir);
                    long mtime = Files.getLastModifiedTime(levelDat).toMillis();
                    result.add(new WorldInfo(dir.getFileName().toString(), dir, mtime, size, source));
                } catch (Throwable ignored) {
                    // 单个世界扫描失败（权限/符号链接/损坏）不应中断其他世界的加载
                }
            });
        }
        return result;
    }

    /** 备份世界为 zip */
    public Path backup(WorldInfo world) throws IOException {
        Files.createDirectories(backupsDir);
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path zip = backupsDir.resolve(world.getName() + "-" + stamp + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            Files.walkFileTree(world.getDir(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = world.getDir().relativize(file).toString().replace(File.separatorChar, '/');
                    zos.putNextEntry(new ZipEntry(rel));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return zip;
    }

    /** 从 zip 恢复世界（覆盖现有世界） */
    public void restore(Path zipFile, String worldName) throws IOException {
        Path target = savesDir.resolve(worldName);
        Files.createDirectories(savesDir);
        if (Files.exists(target)) deleteRecursive(target);
        Files.createDirectories(target);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = target.resolve(e.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("ZIP SLIP detected: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 导入世界 zip（与 restore 相同，但目标名取 zip 文件名） */
    public void importWorld(Path zipFile) throws IOException {
        String name = zipFile.getFileName().toString();
        if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
        restore(zipFile, name);
    }

    /** 删除世界 */
    public void delete(WorldInfo world) throws IOException {
        deleteRecursive(world.getDir());
    }

    private static long dirSize(Path dir) throws IOException {
        long[] size = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                size[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return size[0];
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f); return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d); return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 列出已备份的世界 zip */
    public List<Path> listBackups(String worldName) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(backupsDir)) return result;
        try (Stream<Path> stream = Files.list(backupsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(worldName + "-"))
                    .forEach(result::add);
        }
        return result;
    }
}
