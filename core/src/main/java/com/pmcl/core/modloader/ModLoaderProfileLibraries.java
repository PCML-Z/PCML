package com.pmcl.core.modloader;

import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.Library;
import com.pmcl.core.install.VersionJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 安装期下载 Fabric/Quilt profile JSON 声明的 libraries，
 * 避免「安装完成」后首次启动才在 verifyLibraries 里补齐依赖。
 */
final class ModLoaderProfileLibraries {

    private ModLoaderProfileLibraries() {}

    /**
     * 根据 profile JSON 下载缺失库到 {@code librariesDir}。
     *
     * @param label 进度文案前缀（如 {@code Fabric}）
     * @return 实际下载次数
     */
    static int downloadMissing(DownloadManager downloads, Path librariesDir,
                               String profileJson, String label,
                               Consumer<InstallProgress> onProgress) throws IOException {
        if (downloads == null || profileJson == null || profileJson.isBlank()) return 0;
        VersionJson vj = VersionJson.parse(profileJson);
        List<LibDownload> pending = new ArrayList<>();

        for (Library lib : vj.getLibraries()) {
            if (!lib.appliesToCurrentOs()) continue;

            if (lib.getNameClassifier() != null && lib.getNameClassifier().startsWith("natives-")) {
                if (!lib.matchesCurrentNative()) continue;
                VersionJson.Artifact art = lib.getArtifact();
                Path dest = librariesDir.resolve(lib.getPath());
                if (isHealthy(dest, art != null ? art.getSha1() : null)) continue;
                String url = resolveUrl(lib, art);
                if (url != null) {
                    pending.add(new LibDownload(lib.getName() + " (native)", url, dest,
                            art != null ? art.getSha1() : null));
                }
                continue;
            }

            VersionJson.Artifact art = lib.getArtifact();
            if (art != null) {
                Path dest = librariesDir.resolve(lib.getPath());
                if (!isHealthy(dest, art.getSha1())) {
                    String url = resolveUrl(lib, art);
                    if (url != null) {
                        pending.add(new LibDownload(lib.getName(), url, dest, art.getSha1()));
                    }
                }
            } else if (!lib.getUrl().isEmpty()) {
                Path dest = librariesDir.resolve(lib.getPath());
                if (!isHealthy(dest, null)) {
                    String mavenUrl = lib.getUrl();
                    if (!mavenUrl.endsWith("/")) mavenUrl += "/";
                    mavenUrl += lib.getPath();
                    pending.add(new LibDownload(lib.getName(), mavenUrl, dest, null));
                }
            }

            if (lib.isNativeLib() && lib.getNativeClassifier() != null) {
                Path nativeJar = librariesDir.resolve(
                        lib.getPathForClassifier(lib.getNativeClassifier()));
                VersionJson.Artifact nativeArt = lib.getNativeArtifact();
                String sha1 = nativeArt != null ? nativeArt.getSha1() : null;
                if (isHealthy(nativeJar, sha1)) continue;
                if (nativeArt != null && nativeArt.getUrl() != null && !nativeArt.getUrl().isEmpty()) {
                    pending.add(new LibDownload(
                            lib.getName() + ":" + lib.getNativeClassifier(),
                            nativeArt.getUrl(), nativeJar, sha1));
                }
            }
        }

        if (pending.isEmpty()) return 0;

        List<String> failed = new ArrayList<>();
        int done = 0;
        for (int i = 0; i < pending.size(); i++) {
            LibDownload d = pending.get(i);
            if (onProgress != null) {
                onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, i, pending.size(),
                        label + " 依赖库: " + d.name));
            }
            try {
                Files.createDirectories(d.dest.getParent());
                if (d.sha1 != null && !d.sha1.isBlank()) {
                    downloads.downloadToVerified(d.url, d.dest, d.sha1, null);
                } else {
                    downloads.downloadTo(d.url, d.dest);
                }
                done++;
            } catch (IOException e) {
                failed.add(d.name + ": " + e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            throw new IOException(label + " 依赖库下载失败:\n  - "
                    + String.join("\n  - ", failed));
        }
        return done;
    }

    private static String resolveUrl(Library lib, VersionJson.Artifact art) {
        if (art != null && art.getUrl() != null && !art.getUrl().isEmpty()) {
            return art.getUrl();
        }
        if (lib.getUrl() != null && !lib.getUrl().isEmpty()) {
            String mavenUrl = lib.getUrl();
            if (!mavenUrl.endsWith("/")) mavenUrl += "/";
            return mavenUrl + lib.getPath();
        }
        return null;
    }

    private static boolean isHealthy(Path file, String sha1) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) < 32) return false;
            if (sha1 == null || sha1.isBlank()) return true;
            // 有 sha1 时交给 downloadToVerified 在缺失/损坏时重下；此处仅存在性快速路径
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static final class LibDownload {
        final String name;
        final String url;
        final Path dest;
        final String sha1;

        LibDownload(String name, String url, Path dest, String sha1) {
            this.name = name;
            this.url = url;
            this.dest = dest;
            this.sha1 = sha1;
        }
    }
}
