package com.pmcl.core.gamecontent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 截图管理：扫描 screenshots 目录下的图片文件。
 */
public final class ScreenshotManager {

    private final Path screenshotsDir;

    public ScreenshotManager(Path workDir) {
        this.screenshotsDir = workDir.resolve("screenshots");
    }

    public Path getScreenshotsDir() { return screenshotsDir; }

    public static final class Screenshot {
        private String name;
        private Path path;
        private long size;
        private long modified;
        private String source;

        public Screenshot(String name, Path path, long size, long modified) {
            this(name, path, size, modified, "PMCL");
        }
        public Screenshot(String name, Path path, long size, long modified, String source) {
            this.name = name; this.path = path;
            this.size = size; this.modified = modified;
            this.source = source;
        }
        public String getName() { return name; }
        public Path getPath() { return path; }
        public long getSize() { return size; }
        public long getModified() { return modified; }
        public String getSource() { return source; }
    }

    /** 扫描默认 screenshots 目录（~/.pmcl/screenshots） */
    public List<Screenshot> list() throws IOException {
        return list(screenshotsDir, "PMCL");
    }

    /**
     * 扫描指定 screenshots 目录下的所有图片。
     * @param screenshotsDir 某个 screenshots 目录
     * @param source         来源标签（用于 UI 区分截图归属）
     */
    public List<Screenshot> list(Path screenshotsDir, String source) throws IOException {
        List<Screenshot> result = new ArrayList<>();
        if (!Files.isDirectory(screenshotsDir)) return result;
        try (Stream<Path> stream = Files.list(screenshotsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isImage(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            result.add(new Screenshot(
                                    p.getFileName().toString(),
                                    p,
                                    attrs.size(),
                                    attrs.lastModifiedTime().toMillis(),
                                    source));
                        } catch (Throwable ignored) {
                            // 单个文件读取失败不应中断其他截图扫描
                        }
                    });
        }
        // 按修改时间倒序
        result.sort((a, b) -> Long.compare(b.getModified(), a.getModified()));
        return result;
    }

    public void delete(Screenshot shot) throws IOException {
        Files.deleteIfExists(shot.getPath());
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }
}
