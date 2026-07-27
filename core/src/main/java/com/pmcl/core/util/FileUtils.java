package com.pmcl.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** 文件清理等小工具。 */
public final class FileUtils {

    private FileUtils() {}

    /** 递归删除目录（或单文件）；不存在则忽略。 */
    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            System.err.println("[FileUtils] 删除失败 " + path + ": " + e.getMessage());
        }
    }
}
