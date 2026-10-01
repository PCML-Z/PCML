package com.pmcl.core.gamecontent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 游戏录屏管理：扫描 recordings / videos 目录中的常见视频文件。
 */
public final class RecordingManager {

    private final Path workDir;
    private final Path recordingsDir;

    public RecordingManager(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.recordingsDir = this.workDir.resolve("recordings");
    }

    public Path getRecordingsDir() {
        return recordingsDir;
    }

    public static final class Recording {
        private final String name;
        private final Path path;
        private final long size;
        private final long modified;
        private final String source;

        public Recording(String name, Path path, long size, long modified, String source) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.modified = modified;
            this.source = source;
        }

        public String getName() { return name; }
        public Path getPath() { return path; }
        public long getSize() { return size; }
        public long getModified() { return modified; }
        public String getSource() { return source; }
    }

    public List<Recording> list() throws IOException {
        return list(recordingsDir, "PMCL");
    }

    public List<Recording> list(Path directory, String source) throws IOException {
        List<Recording> result = new ArrayList<>();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return result;
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> isVideo(p.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(
                                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            result.add(new Recording(
                                    path.getFileName().toString(),
                                    path.toAbsolutePath().normalize(),
                                    attrs.size(),
                                    attrs.lastModifiedTime().toMillis(),
                                    source == null || source.isBlank() ? "PMCL" : source));
                        } catch (IOException ignored) {
                            // 单个文件损坏或瞬间消失时继续扫描其它文件。
                        }
                    });
        }
        result.sort((a, b) -> Long.compare(b.getModified(), a.getModified()));
        return result;
    }

    /** 把用户选择的视频复制进 PMCL recordings，重名时自动追加序号。 */
    public Recording importRecording(Path source) throws IOException {
        if (source == null) throw new IOException("录屏路径为空");
        Path file = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !isVideo(file.getFileName().toString())) {
            throw new IOException("不是支持的录屏文件: " + file);
        }
        Files.createDirectories(recordingsDir);
        Path target = uniqueTarget(recordingsDir, file.getFileName().toString());
        if (!file.equals(target.toAbsolutePath().normalize())) {
            Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        BasicFileAttributes attrs = Files.readAttributes(
                target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new Recording(target.getFileName().toString(), target, attrs.size(),
                attrs.lastModifiedTime().toMillis(), "PMCL");
    }

    public void delete(Recording recording) throws IOException {
        if (recording == null || recording.getPath() == null) throw new IOException("录屏路径为空");
        Path file = recording.getPath().toAbsolutePath().normalize();
        Path parent = file.getParent();
        if (parent == null || parent.getFileName() == null || !isRecordingDirectory(parent)) {
            throw new IOException("拒绝删除：路径不在 recordings/videos 目录下: " + file);
        }
        if (!file.equals(parent.resolve(file.getFileName()).normalize())) {
            throw new IOException("拒绝删除：路径越界: " + file);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !isVideo(file.getFileName().toString())) {
            throw new IOException("拒绝删除：不是普通录屏文件: " + file);
        }
        Files.delete(file);
    }

    public static boolean isVideo(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".m4v") || lower.endsWith(".avi")
                || lower.endsWith(".flv") || lower.endsWith(".wmv");
    }

    private static boolean isRecordingDirectory(Path directory) {
        String name = directory.getFileName().toString();
        return "recordings".equalsIgnoreCase(name) || "videos".equalsIgnoreCase(name);
    }

    private static Path uniqueTarget(Path directory, String fileName) {
        Path direct = directory.resolve(fileName);
        if (!Files.exists(direct)) return direct;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            Path candidate = directory.resolve(base + "_" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return directory.resolve(base + "_" + System.currentTimeMillis() + ext);
    }
}
