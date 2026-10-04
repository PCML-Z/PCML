package com.pmcl.core.update;

import com.pmcl.core.download.DownloadManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** 把清单里变化的文件下载到暂存目录，并在移动前核对大小、哈希和 jar。 */
public final class HotUpdateDownloader {
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

    private HotUpdateDownloader() {}

    public static Path stage(DownloadManager downloads, Path stagingRoot,
                             List<HotUpdateManifest.FileEntry> files,
                             Consumer<Long> onProgress) throws IOException {
        if (downloads == null) throw new IOException("下载组件不可用");
        Files.createDirectories(stagingRoot);
        long done = 0;
        for (HotUpdateManifest.FileEntry file : files) {
            HotUpdateManifest.FileEntry.validatePath(file.path());
            if (file.url().isBlank()) throw new IOException("热更新文件没有下载地址: " + file.path());
            if (file.size() > MAX_FILE_BYTES) {
                throw new IOException("热更新文件超过上限: " + file.path());
            }
            Path dest = stagingRoot.resolve(file.path()).normalize();
            if (!dest.startsWith(stagingRoot.normalize())) {
                throw new IOException("热更新暂存路径越界: " + file.path());
            }
            long start = done;
            downloads.downloadToSsrfChecked(file.url(), dest, read -> {
                if (onProgress != null) onProgress.accept(start + read);
            }, MAX_FILE_BYTES);
            if (Files.size(dest) != file.size()) {
                Files.deleteIfExists(dest);
                throw new IOException("热更新文件大小不符: " + file.path());
            }
            String actual = FileDigest.sha256(dest);
            if (!actual.equals(file.sha256()) || !FileDigest.contentLooksValid(dest)) {
                Files.deleteIfExists(dest);
                throw new IOException("热更新文件校验失败: " + file.path());
            }
            done += Files.size(dest);
            if (onProgress != null) onProgress.accept(done);
        }
        return stagingRoot;
    }
}
