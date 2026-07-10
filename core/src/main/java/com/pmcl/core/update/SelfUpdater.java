package com.pmcl.core.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.DownloadManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 启动器自更新：从远程清单 JSON 检查最新版本并下载替换。
 * <p>
 * 清单格式（由用户在设置中配置 URL）：
 * <pre>
 * { "version": "1.0.1", "url": "https://.../pmcl.jar", "sha1": "..." }
 * </pre>
 * <p>
 * 更新策略：下载到临时文件 → 校验 SHA1 → 在启动器退出时原子替换（写入 .new jar，
 * 由启动脚本在下次启动时完成替换；或直接覆盖当前 jar，需用户拥有写入权限）。
 * <p>
 * 本实现仅完成「下载并验证」，不替换运行中的 jar。用户可在外部脚本中完成替换。
 */
public final class SelfUpdater {

    private final DownloadManager downloadManager;
    private final String manifestUrl;
    private final String currentVersion;

    public SelfUpdater(DownloadManager downloadManager, String manifestUrl, String currentVersion) {
        this.downloadManager = downloadManager;
        this.manifestUrl = manifestUrl;
        this.currentVersion = currentVersion;
    }

    public static final class UpdateInfo {
        private final String version;
        private final String url;
        private final String sha1;
        private final long size;
        private final String notes;

        public UpdateInfo(String version, String url, String sha1, long size, String notes) {
            this.version = version; this.url = url;
            this.sha1 = sha1; this.size = size;
            this.notes = notes;
        }
        public String getVersion() { return version; }
        public String getUrl() { return url; }
        public String getSha1() { return sha1; }
        public long getSize() { return size; }
        public String getNotes() { return notes; }
    }

    /** 检查更新（若 manifestUrl 为空返回 null） */
    public CompletableFuture<UpdateInfo> checkUpdate() {
        if (manifestUrl == null || manifestUrl.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = downloadManager.downloadString(manifestUrl);
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                String ver = o.has("version") && !o.get("version").isJsonNull() ? o.get("version").getAsString() : "";
                if (ver.isEmpty() || ver.equals(currentVersion)) return null;
                return new UpdateInfo(
                        ver,
                        o.has("url") && !o.get("url").isJsonNull() ? o.get("url").getAsString() : "",
                        o.has("sha1") && !o.get("sha1").isJsonNull() ? o.get("sha1").getAsString() : "",
                        o.has("size") && !o.get("size").isJsonNull() ? o.get("size").getAsLong() : 0L,
                        o.has("notes") && !o.get("notes").isJsonNull() ? o.get("notes").getAsString() : "");
            } catch (IOException e) {
                throw new RuntimeException("检查更新失败: " + e.getMessage(), e);
            }
        });
    }

    /** 下载更新到临时文件（不替换当前 jar） */
    public CompletableFuture<Path> downloadUpdate(UpdateInfo info, Consumer<Long> onProgress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path tmp = Files.createTempFile("pmcl-update-", ".jar");
                Files.deleteIfExists(tmp);
                downloadManager.downloadTo(info.getUrl(), tmp);
                // 校验
                if (info.getSha1() != null && !info.getSha1().isEmpty()) {
                    String actual = sha1(tmp);
                    if (!actual.equalsIgnoreCase(info.getSha1())) {
                        Files.deleteIfExists(tmp);
                        throw new IOException("更新文件 SHA1 校验失败");
                    }
                }
                // 复制到 ~/.pmcl/updates/pmcl-{version}.jar
                Path updatesDir = Paths.get(System.getProperty("user.home"), ".pmcl", "updates");
                Files.createDirectories(updatesDir);
                Path target = updatesDir.resolve("pmcl-" + info.getVersion() + ".jar");
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                if (onProgress != null) onProgress.accept(info.getSize());
                return target;
            } catch (IOException e) {
                throw new RuntimeException("下载更新失败: " + e.getMessage(), e);
            }
        });
    }

    private static String sha1(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            try (var is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("SHA1 计算失败", e);
        }
    }
}
