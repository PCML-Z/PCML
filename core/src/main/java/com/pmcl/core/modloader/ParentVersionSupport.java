package com.pmcl.core.modloader;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.install.VersionStaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** 安装器共用：确保原版父版本已安装。 */
final class ParentVersionSupport {

    private ParentVersionSupport() {}

    static void ensureParentInstalled(LauncherConfig config, VersionInstaller versionInstaller,
                                      String parentId, Consumer<InstallProgress> onProgress)
            throws IOException {
        VersionStaging.assertSafeVersionId(parentId);
        Path parentDir = config.getVersionsDir().resolve(parentId);
        Path parentJson = parentDir.resolve(parentId + ".json");
        Path parentJar = parentDir.resolve(parentId + ".jar");
        if (Files.isRegularFile(parentJson) && Files.isRegularFile(parentJar)
                && Files.size(parentJar) > 1024) {
            return;
        }
        if (versionInstaller == null) {
            throw new IOException("缺少原版父版本 " + parentId + "，请先安装 Minecraft " + parentId);
        }
        if (onProgress != null) onProgress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                "安装原版父版本 " + parentId));
        try {
            versionInstaller.install(parentId, onProgress).join();
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable c = ce.getCause() != null ? ce.getCause() : ce;
            if (c instanceof IOException) throw (IOException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new IOException("安装原版父版本失败: " + parentId, c);
        }
    }

    static void downloadFirstOk(DownloadManager downloads, Path dest, String... urls)
            throws IOException {
        Files.createDirectories(dest.getParent());
        IOException last = null;
        for (String url : urls) {
            if (url == null || url.isBlank()) continue;
            try {
                downloads.downloadTo(url, dest);
                if (Files.isRegularFile(dest) && Files.size(dest) > 64) return;
            } catch (IOException e) {
                last = e;
                try { Files.deleteIfExists(dest); } catch (IOException ignored) {}
            }
        }
        throw last != null ? last : new IOException("所有下载源均失败");
    }
}
