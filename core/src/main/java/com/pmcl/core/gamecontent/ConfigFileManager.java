package com.pmcl.core.gamecontent;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 模组配置文件管理器。
 * <p>
 * 管理 Minecraft 模组的配置文件（config/ 目录下），支持：
 *   - 列出配置文件（递归扫描子目录）
 *   - 读取/写入文件内容
 *   - 备份文件（.bak 后缀）
 *   - 删除文件
 * <p>
 * 支持的配置文件格式（按扩展名识别）：
 *   .cfg / .toml / .json / .properties / .txt / .ini / .conf / .xml / .yml / .yaml
 * <p>
 * 配置文件目录：
 *   - 版本隔离模式：instances/{versionId}/config/
 *   - 非隔离模式：workDir/config/
 */
public final class ConfigFileManager {

    /** 支持的配置文件扩展名 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".cfg", ".toml", ".json", ".properties", ".txt",
            ".ini", ".conf", ".xml", ".yml", ".yaml", ".props"
    );

    /** 最大文件大小（1MB），超过则不读取内容（防止 OOM） */
    private static final long MAX_FILE_SIZE = 1024 * 1024;

    /** 配置文件信息 */
    public static final class ConfigFileEntry {
        private final String relativePath;  // 相对于 config/ 的路径
        private final String fileName;
        private final long size;
        private final long lastModified;
        private final boolean isDirectory;
        private final String format;        // 文件格式（扩展名，如 "toml"）

        public ConfigFileEntry(String relativePath, String fileName, long size,
                               long lastModified, boolean isDirectory, String format) {
            this.relativePath = relativePath;
            this.fileName = fileName;
            this.size = size;
            this.lastModified = lastModified;
            this.isDirectory = isDirectory;
            this.format = format;
        }

        public String getRelativePath() { return relativePath; }
        public String getFileName() { return fileName; }
        public long getSize() { return size; }
        public long getLastModified() { return lastModified; }
        public boolean isDirectory() { return isDirectory; }
        public String getFormat() { return format; }

        @Override
        public String toString() {
            return fileName + (isDirectory ? "/" : "");
        }
    }

    private final Path configDir;

    public ConfigFileManager(Path configDir) {
        this.configDir = configDir;
    }

    public Path getConfigDir() { return configDir; }

    /** 确保配置目录存在 */
    public void ensureConfigDir() throws IOException {
        Files.createDirectories(configDir);
    }

    /**
     * 列出配置文件（非递归，仅顶层）。
     * 目录排在前面，按名称排序。
     */
    public List<ConfigFileEntry> listFiles() throws IOException {
        if (!Files.isDirectory(configDir)) return Collections.emptyList();
        List<ConfigFileEntry> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(configDir)) {
            List<Path> paths = stream.sorted().collect(Collectors.toList());
            for (Path p : paths) {
                ConfigFileEntry entry = toEntry(p, configDir);
                if (entry != null) result.add(entry);
            }
        }
        return result;
    }

    /**
     * 递归列出所有配置文件（包括子目录内的文件）。
     */
    public List<ConfigFileEntry> listAllFiles() throws IOException {
        if (!Files.isDirectory(configDir)) return Collections.emptyList();
        List<ConfigFileEntry> result = new ArrayList<>();
        Files.walkFileTree(configDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                ConfigFileEntry entry = toEntry(file, configDir);
                if (entry != null) result.add(entry);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(configDir)) return FileVisitResult.CONTINUE;
                ConfigFileEntry entry = toEntry(dir, configDir);
                if (entry != null) result.add(entry);
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /**
     * 列出指定子目录下的文件。
     * @param subDir 相对于 config/ 的子目录路径（如 "jei" 或 "" 表示顶层）
     */
    public List<ConfigFileEntry> listFiles(String subDir) throws IOException {
        Path dir = subDir.isEmpty() || subDir.equals("/")
                ? configDir : configDir.resolve(subDir).normalize();
        // 安全检查：确保路径在 configDir 内
        if (!dir.startsWith(configDir)) throw new IOException("非法路径: " + subDir);
        if (!Files.isDirectory(dir)) return Collections.emptyList();
        List<ConfigFileEntry> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> paths = stream.sorted().collect(Collectors.toList());
            for (Path p : paths) {
                ConfigFileEntry entry = toEntry(p, configDir);
                if (entry != null) result.add(entry);
            }
        }
        return result;
    }

    /**
     * 读取文件内容。
     * @param relativePath 相对于 config/ 的路径
     * @return 文件内容字符串
     */
    public String readFile(String relativePath) throws IOException {
        Path file = configDir.resolve(relativePath).normalize();
        if (!file.startsWith(configDir)) throw new IOException("非法路径: " + relativePath);
        if (!Files.exists(file)) throw new IOException("文件不存在: " + relativePath);
        long size = Files.size(file);
        if (size > MAX_FILE_SIZE) {
            throw new IOException("文件过大（" + formatSize(size) + "），超过 1MB 限制，请使用外部编辑器");
        }
        return Files.readString(file);
    }

    /**
     * 写入文件内容。
     * 写入前自动备份（如果 .bak 不存在）。
     */
    public void writeFile(String relativePath, String content) throws IOException {
        Path file = configDir.resolve(relativePath).normalize();
        if (!file.startsWith(configDir)) throw new IOException("非法路径: " + relativePath);
        Files.createDirectories(file.getParent());
        // 自动备份
        Path backup = Path.of(file + ".bak");
        if (Files.exists(file) && !Files.exists(backup)) {
            Files.copy(file, backup);
        }
        Files.writeString(file, content);
    }

    /** 删除文件 */
    public void deleteFile(String relativePath) throws IOException {
        Path file = configDir.resolve(relativePath).normalize();
        if (!file.startsWith(configDir)) throw new IOException("非法路径: " + relativePath);
        Files.deleteIfExists(file);
    }

    /** 重命名文件 */
    public void renameFile(String relativePath, String newName) throws IOException {
        Path file = configDir.resolve(relativePath).normalize();
        if (!file.startsWith(configDir)) throw new IOException("非法路径: " + relativePath);
        Path target = file.resolveSibling(newName).normalize();
        if (!target.startsWith(configDir)) throw new IOException("非法目标路径: " + newName);
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 创建新文件 */
    public void createFile(String relativePath) throws IOException {
        Path file = configDir.resolve(relativePath).normalize();
        if (!file.startsWith(configDir)) throw new IOException("非法路径: " + relativePath);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    /** 创建目录 */
    public void createDirectory(String relativePath) throws IOException {
        Path dir = configDir.resolve(relativePath).normalize();
        if (!dir.startsWith(configDir)) throw new IOException("非法路径: " + relativePath);
        Files.createDirectories(dir);
    }

    /** 格式化文件大小 */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** 判断文件是否为支持的配置文件格式 */
    private static boolean isSupportedConfig(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        // 隐藏文件和备份文件不显示
        if (name.startsWith(".")) return false;
        if (name.endsWith(".bak") || name.endsWith(".disabled") || name.endsWith(".old")) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0) return true; // 无扩展名的文本文件也显示
        String ext = name.substring(dotIdx);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /** Path 转 ConfigFileEntry */
    private static ConfigFileEntry toEntry(Path p, Path configDir) {
        try {
            String relative = configDir.relativize(p).toString().replace('\\', '/');
            String name = p.getFileName().toString();
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            boolean isDir = attrs.isDirectory();
            // 目录始终显示，文件需要是支持的格式
            if (!isDir && !isSupportedConfig(p)) return null;
            String format = "";
            if (!isDir) {
                int dotIdx = name.lastIndexOf('.');
                format = dotIdx >= 0 ? name.substring(dotIdx + 1).toLowerCase() : "txt";
            }
            return new ConfigFileEntry(
                    relative, name, attrs.size(),
                    attrs.lastModifiedTime().toMillis(), isDir, format
            );
        } catch (IOException e) {
            return null;
        }
    }
}
