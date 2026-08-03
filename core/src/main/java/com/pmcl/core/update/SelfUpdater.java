package com.pmcl.core.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.DownloadManager;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 启动器自更新：从已签名的远程清单检查最新版本并下载替换。
 * <p>
 * 清单必须通过 HTTPS 获取，并包含 Ed25519 {@code signature}（对版本/URL/哈希等字段的
 * 规范载荷签名；验签公钥随启动器带外分发，见 {@link UpdateSignatureVerifier}）。
 * 仅校验清单内哈希而不验签不足以抵抗清单源被劫持。
 * <p>
 * 清单格式：
 * <pre>
 * {
 *   "version": "1.0.1",
 *   "url": "https://.../pmcl.jar",
 *   "sha256": "...",
 *   "sha1": "...",
 *   "size": 12345,
 *   "notes": "...",
 *   "signature": "&lt;Base64 Ed25519&gt;"
 * }
 * </pre>
 * <p>
 * GitHub Release 通道（{@link TrustedChannel#GITHUB_RELEASE}）除 GitHub HTTPS +
 * asset digest 外，还要求 Release 附带 Ed25519 签名资产（.sig），与自定义清单使用同一公钥验签。
 * <p>
 * 本实现仅完成「下载并验证」，不替换运行中的 jar。
 */
public final class SelfUpdater {

    /** 更新来源信任模型 */
    public enum TrustedChannel {
        /** 自定义清单：必须通过固定公钥 Ed25519 验签 */
        SIGNED_MANIFEST,
        /** GitHub Releases API + asset SHA-256 digest + Ed25519 签名资产（.sig） */
        GITHUB_RELEASE
    }

    /** 下载资产类型，用于选择平台安装方式。 */
    public enum AssetKind {
        JAR, PKG, DMG, MSI, EXE, DEB, RPM, APPIMAGE, TAR_GZ, UNKNOWN
    }

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
        private final String sha256;
        private final long size;
        private final String notes;
        private final String signature;
        private final TrustedChannel channel;

        public UpdateInfo(String version, String url, String sha1, long size, String notes) {
            this(version, url, sha1, null, size, notes, null, TrustedChannel.SIGNED_MANIFEST);
        }

        public UpdateInfo(String version, String url, String sha1, String sha256, long size, String notes) {
            this(version, url, sha1, sha256, size, notes, null, TrustedChannel.SIGNED_MANIFEST);
        }

        public UpdateInfo(String version, String url, String sha1, String sha256, long size,
                          String notes, String signature, TrustedChannel channel) {
            this.version = version;
            this.url = url;
            this.sha1 = sha1;
            this.sha256 = sha256;
            this.size = size;
            this.notes = notes;
            this.signature = signature;
            this.channel = channel == null ? TrustedChannel.SIGNED_MANIFEST : channel;
        }

        public String getVersion() { return version; }
        public String getUrl() { return url; }
        public String getSha1() { return sha1; }
        public String getSha256() { return sha256; }
        public long getSize() { return size; }
        public String getNotes() { return notes; }
        public String getSignature() { return signature; }
        public TrustedChannel getChannel() { return channel; }
        public String getAssetName() {
            try {
                String path = URI.create(url).getPath();
                int slash = path == null ? -1 : path.lastIndexOf('/');
                String raw = slash >= 0 ? path.substring(slash + 1) : path;
                return raw == null ? "" : java.net.URLDecoder.decode(
                        raw, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
        public AssetKind getAssetKind() {
            String name = getAssetName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".tar.gz")) return AssetKind.TAR_GZ;
            if (name.endsWith(".appimage")) return AssetKind.APPIMAGE;
            if (name.endsWith(".jar")) return AssetKind.JAR;
            if (name.endsWith(".pkg")) return AssetKind.PKG;
            if (name.endsWith(".dmg")) return AssetKind.DMG;
            if (name.endsWith(".msi")) return AssetKind.MSI;
            if (name.endsWith(".exe")) return AssetKind.EXE;
            if (name.endsWith(".deb")) return AssetKind.DEB;
            if (name.endsWith(".rpm")) return AssetKind.RPM;
            return AssetKind.UNKNOWN;
        }
    }

    /** 检查更新（若 manifestUrl 为空返回 null） */
    public CompletableFuture<UpdateInfo> checkUpdate() {
        if (manifestUrl == null || manifestUrl.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireHttps(manifestUrl, "更新清单");
                String json = downloadManager.downloadStringSsrfChecked(manifestUrl);
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                String ver = text(o, "version");
                if (ver.isEmpty() || !UpdateVersions.isNewer(ver, currentVersion)) {
                    return null;
                }
                String url = text(o, "url");
                String sha1 = text(o, "sha1");
                String sha256 = text(o, "sha256");
                long size = o.has("size") && !o.get("size").isJsonNull() ? o.get("size").getAsLong() : 0L;
                String notes = text(o, "notes");
                String signature = text(o, "signature");
                requireHttps(url, "更新包");
                UpdateSignatureVerifier.verifyOrThrow(ver, url, sha256, sha1, size, signature);
                if ((sha256 == null || sha256.isEmpty()) && (sha1 == null || sha1.isEmpty())) {
                    throw new IOException("更新清单未提供 SHA-256/SHA-1，拒绝安装未校验的更新包");
                }
                return new UpdateInfo(ver, url, sha1, sha256, size, notes, signature,
                        TrustedChannel.SIGNED_MANIFEST);
            } catch (IOException e) {
                throw new RuntimeException("检查更新失败: " + e.getMessage(), e);
            }
        });
    }

    /** 下载更新到 {@code ~/.pmcl/updates/}（不替换当前 jar） */
    public CompletableFuture<Path> downloadUpdate(UpdateInfo info, Consumer<Long> onProgress) {
        return CompletableFuture.supplyAsync(() -> {
            Path tmp = null;
            try {
                if (info == null) {
                    throw new IOException("更新信息为空");
                }
                requireHttps(info.getUrl(), "更新包");
                assertChannelTrust(info);

                Path updatesDir = Paths.get(System.getProperty("user.home"), ".pmcl", "updates")
                        .toAbsolutePath().normalize();
                Files.createDirectories(updatesDir);
                // 私有目录下的临时文件；不先删再建，避免 /tmp TOCTOU / 符号链接竞态
                tmp = Files.createTempFile(updatesDir, "pmcl-update-", ".tmp");
                trySetOwnerOnly(tmp);

                downloadManager.downloadToSsrfChecked(info.getUrl(), tmp);

                verifyHashes(info, tmp);
                // 先验签载荷已在 checkUpdate / assertChannelTrust；此处对磁盘文件再验哈希即可

                String ver = info.getVersion();
                if (ver == null || !ver.matches("[A-Za-z0-9._+-]+")
                        || ver.contains("..")) {
                    throw new IOException("更新版本号非法（拒绝路径穿越）: " + ver);
                }
                String assetName = info.getAssetName();
                if (assetName.isBlank()) assetName = "pmcl-" + ver + ".jar";
                // Release 资产名不能控制目录，只保留文件名并限制字符集
                assetName = Paths.get(assetName).getFileName().toString()
                        .replaceAll("[^A-Za-z0-9._+-]", "_");
                Path target = updatesDir.resolve(ver + "-" + assetName).normalize();
                if (!target.startsWith(updatesDir)) {
                    throw new IOException("更新目标路径越界: " + target);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                tmp = null;
                // move 后再核一次哈希，防止替换后内容与校验对象不一致
                verifyHashes(info, target);
                if (onProgress != null) onProgress.accept(info.getSize());
                return target;
            } catch (IOException e) {
                throw new RuntimeException("下载更新失败: " + e.getMessage(), e);
            } finally {
                if (tmp != null) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                }
            }
        });
    }

    private static void assertChannelTrust(UpdateInfo info) throws IOException {
        if (info.getChannel() == TrustedChannel.GITHUB_RELEASE) {
            if (!isTrustedGitHubDownloadHost(info.getUrl())) {
                throw new IOException("GitHub 更新通道的下载 URL 主机不受信任: " + info.getUrl());
            }
            String sha256 = info.getSha256();
            if (sha256 == null || sha256.isEmpty()) {
                throw new IOException("GitHub 更新缺少 SHA-256 digest，拒绝安装");
            }
        }
        // 两个通道都验证 Ed25519 签名，防止 HTTPS 被绕过后安装未签名更新
        UpdateSignatureVerifier.verifyOrThrow(
                info.getVersion(), info.getUrl(), info.getSha256(), info.getSha1(),
                info.getSize(), info.getSignature());
    }

    private static boolean isTrustedGitHubDownloadHost(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("github.com")
                    || host.endsWith(".github.com")
                    || host.equals("objects.githubusercontent.com")
                    || host.equals("release-assets.githubusercontent.com")
                    || host.endsWith(".githubusercontent.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static void requireHttps(String url, String what) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException(what + " URL 为空");
        }
        try {
            String scheme = URI.create(url.trim()).getScheme();
            if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
                throw new IOException(what + " 必须使用 HTTPS，拒绝: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException(what + " URL 非法: " + url, e);
        }
    }

    private static void verifyHashes(UpdateInfo info, Path file) throws IOException {
        String sha256 = info.getSha256();
        String sha1 = info.getSha1();
        if (sha256 != null && !sha256.isEmpty()) {
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(sha256)) {
                throw new IOException("更新文件 SHA-256 校验失败：期望 " + sha256 + " 实际 " + actual);
            }
        } else if (sha1 != null && !sha1.isEmpty()) {
            String actual = sha1(file);
            if (!actual.equalsIgnoreCase(sha1)) {
                throw new IOException("更新文件 SHA1 校验失败");
            }
        } else {
            throw new IOException("更新清单未提供 SHA-256/SHA-1，拒绝安装未校验的更新包");
        }
    }

    private static void trySetOwnerOnly(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等非 POSIX：忽略
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String sha1(Path file) throws IOException {
        return hash(file, "SHA-1");
    }

    private static String sha256(Path file) throws IOException {
        return hash(file, "SHA-256");
    }

    private static String hash(Path file, String algorithm) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance(algorithm);
            try (var is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(algorithm + " 计算失败", e);
        }
    }
}
