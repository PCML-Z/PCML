package com.pmcl.downloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件路径处理工具类（Java 实现）。
 * 提供从 URL 提取文件名、确保父目录存在等功能。
 */
public class FileHelper {

    /** 默认下载目录。 */
    public static final String DEFAULT_DOWNLOAD_DIR =
            Paths.get(System.getProperty("user.home"), ".pmcl", "downloads").toString();

    /**
     * 从 URL 中提取文件名。
     * 例如: https://example.com/path/file.json -> file.json
     * 如果无法提取，返回 "download_<timestamp>"
     *
     * @param url 下载 URL
     * @return 提取的文件名
     */
    public static String extractFilename(String url) {
        if (url == null || url.isBlank()) {
            return "download_" + System.currentTimeMillis();
        }

        // 去掉查询参数和锚点
        String path = url;
        int queryIdx = path.indexOf('?');
        if (queryIdx > 0) path = path.substring(0, queryIdx);
        int hashIdx = path.indexOf('#');
        if (hashIdx > 0) path = path.substring(0, hashIdx);

        // 提取最后一段路径
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            String name = path.substring(lastSlash + 1);
            // 过滤非法文件名字符
            name = name.replaceAll("[^a-zA-Z0-9.\\-_]", "_");
            if (!name.isBlank()) {
                return name;
            }
        }

        return "download_" + System.currentTimeMillis();
    }

    /**
     * 确保文件路径的父目录存在，不存在则创建。
     *
     * @param filePath 文件路径
     * @throws IOException 如果创建目录失败
     */
    public static void ensureParentDir(Path filePath) throws IOException {
        Path parent = filePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    /**
     * 获取默认下载目录路径。
     *
     * @return ~/.pmcl/downloads/ 路径
     */
    public static Path getDefaultDownloadDir() {
        return Paths.get(DEFAULT_DOWNLOAD_DIR);
    }
}
