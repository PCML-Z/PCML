package com.pmcl.core.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Maven 仓库旁路 {@code .sha1} 文件。无完整性哈希时拒绝静默下载。
 */
public final class MavenSidecar {

    private MavenSidecar() {}

    /**
     * 读取 {@code url.sha1}，成功返回 40 位 hex，失败返回 {@code null}。
     */
    public static String fetchSha1(DownloadManager downloads, String url) {
        if (downloads == null || url == null || url.isBlank()) return null;
        try {
            String body = downloads.downloadString(url + ".sha1").trim();
            if (body.isEmpty()) return null;
            String hash = body.split("\\s+")[0].trim();
            if (hash.matches("[0-9a-fA-F]{40}")) return hash;
            System.err.println("[MavenSidecar] .sha1 旁路格式无效: " + url + ".sha1");
            return null;
        } catch (Exception e) {
            System.err.println("[MavenSidecar] 获取 .sha1 旁路失败: " + url
                    + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * 有 SHA-1（参数或旁路）则校验下载；否则拒绝。
     */
    public static void downloadVerified(DownloadManager downloads, String url, Path dest, String sha1)
            throws IOException {
        String effective = sha1;
        if (effective == null || effective.isBlank()) {
            effective = fetchSha1(downloads, url);
        }
        if (effective == null || effective.isBlank()) {
            throw new IOException("无 SHA-1 且旁路 .sha1 不可用，拒绝下载: " + url);
        }
        Files.createDirectories(dest.getParent());
        downloads.downloadToVerified(url, dest, effective, null);
    }
}
