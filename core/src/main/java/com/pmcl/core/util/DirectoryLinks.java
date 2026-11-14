package com.pmcl.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * {@code Files.walk} 不会进入作为起点的符号链接。
 * 游戏目录被迁到外置磁盘后，起点仍是原来的路径，这里顺着链接列出内容，并保持原来的路径前缀。
 */
public final class DirectoryLinks {

    private DirectoryLinks() {}

    public static Stream<Path> walk(Path start, int maxDepth) throws IOException {
        if (start == null || maxDepth < 0 || !Files.isDirectory(start)) return Stream.empty();
        if (!Files.isSymbolicLink(start)) return Files.walk(start, maxDepth);
        Path real = start.toRealPath();
        Stream<Path> raw = Files.walk(real, maxDepth);
        return raw.map(path -> rebase(start, real, path)).onClose(raw::close);
    }

    private static Path rebase(Path start, Path real, Path path) {
        Path rel = real.relativize(path);
        if (rel.toString().isEmpty()) return start;
        return start.resolve(rel);
    }
}
