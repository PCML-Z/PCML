package com.pmcl.core.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * authlib-injector 管理器：下载 authlib-injector.jar、预取 Yggdrasil API 信息。
 * <p>
 * authlib-injector 是一个 Java Agent，通过 Java Instrumentation 在运行时修改
 * Minecraft 的 authlib 请求 URL，将其指向自定义的皮肤站。
 * <p>
 * 启动时注入两种方式（本启动器采用预取方式，更可靠）：
 * <ol>
 *   <li>{@code -javaagent:authlib-injector.jar=服务器URL}</li>
 *   <li>{@code -javaagent:authlib-injector.jar} + {@code -Dauthlibinjector.yggdrasil.prefetched=<base64>}</li>
 * </ol>
 * 预取方式在启动前先 GET 服务器 {@code /api/yggdrasil} 获取元数据，
 * Base64 编码后通过 -D 参数传入，避免运行时网络问题导致注入失败。
 *
 * @see <a href="https://github.com/yushijinhun/authlib-injector">authlib-injector</a>
 */
public final class AuthlibInjectorManager {

    /** authlib-injector 版本信息 JSON 地址（官方） */
    private static final String VERSION_INFO_URL = "https://authlib-injector.yushi.moe/artifact/latest.json";

    private final OkHttpClient http;

    public AuthlibInjectorManager() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 确保 authlib-injector.jar 存在且为最新版本。
     * 若本地不存在则从官方下载；存在则检查版本号，过时则更新。
     *
     * @param jarPath 本地存储路径（如 ~/.pmcl/authlib-injector.jar）
     * @return jar 文件路径
     * @throws IOException 下载或写入失败
     */
    public Path ensureJar(Path jarPath) throws IOException {
        Files.createDirectories(jarPath.getParent());

        // 获取最新版本信息
        VersionInfo info = fetchLatestVersionInfo();
        Path versionFile = jarPath.resolveSibling(jarPath.getFileName() + ".version");
        Path hashFile = jarPath.resolveSibling(jarPath.getFileName() + ".sha256");
        if (info == null) {
            // 无网络版本信息：必须用已存 SHA256 重新哈希比对，不能仅凭文件存在就信任
            if (Files.exists(jarPath) && Files.exists(hashFile)) {
                String expected = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
                if (!expected.matches("[0-9a-fA-F]{64}")) {
                    throw new IOException("本地 authlib-injector SHA256 旁路无效，拒绝离线使用");
                }
                String actual = sha256Hex(jarPath);
                if (!actual.equalsIgnoreCase(expected)) {
                    Files.deleteIfExists(jarPath);
                    Files.deleteIfExists(hashFile);
                    Files.deleteIfExists(versionFile);
                    throw new IOException("本地 authlib-injector.jar 与已存 SHA256 不匹配，拒绝使用");
                }
                System.err.println("[AuthlibInjectorManager] 无法获取最新版本信息，本地 jar SHA256 校验通过，继续使用");
                return jarPath;
            }
            if (Files.exists(jarPath)) {
                throw new IOException("无法获取 authlib-injector 版本信息，且本地 jar 缺少已存 SHA256，拒绝使用未校验文件");
            }
            throw new IOException("无法获取 authlib-injector 版本信息，且本地不存在 jar 文件");
        }

        // 检查本地版本是否最新；命中时仍校验 SHA256
        if (Files.exists(jarPath) && Files.exists(versionFile)) {
            try {
                String localVersion = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
                if (localVersion.equals(info.version)) {
                    if (info.sha256.isEmpty()) {
                        throw new IOException("官方版本信息未提供 sha256，拒绝信任缓存的 authlib-injector.jar");
                    }
                    String actual = sha256Hex(jarPath);
                    if (actual.equalsIgnoreCase(info.sha256)) {
                        Files.writeString(hashFile, info.sha256, StandardCharsets.UTF_8);
                        return jarPath;
                    }
                    System.err.println("[AuthlibInjectorManager] 本地 jar SHA256 不匹配，重新下载");
                    Files.deleteIfExists(jarPath);
                    Files.deleteIfExists(versionFile);
                    Files.deleteIfExists(hashFile);
                }
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("未提供 sha256")) throw e;
                System.err.println("[AuthlibInjectorManager] 读取本地版本/校验失败，将重新下载: "
                        + e.getMessage());
            }
        }

        // 下载 jar（先落到临时文件，校验通过后再提升，避免 javaagent 用上坏文件）
        System.err.println("[AuthlibInjectorManager] 下载 authlib-injector " + info.version + " from " + info.downloadUrl);
        if (info.sha256.isEmpty()) {
            throw new IOException("官方版本信息未提供 sha256，拒绝安装未校验的 authlib-injector.jar");
        }
        Path tmp = jarPath.resolveSibling(jarPath.getFileName() + ".verified-tmp");
        Files.deleteIfExists(tmp);
        try {
            downloadFile(info.downloadUrl, tmp);
            String actual = sha256Hex(tmp);
            if (!actual.equalsIgnoreCase(info.sha256)) {
                throw new IOException("authlib-injector.jar SHA256 校验失败：预期 " + info.sha256
                        + "，实际 " + actual + "（文件可能被篡改或下载损坏）");
            }
            try {
                Files.move(tmp, jarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, jarPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            Files.deleteIfExists(jarPath);
            Files.deleteIfExists(versionFile);
            Files.deleteIfExists(hashFile);
            throw e;
        }
        System.err.println("[AuthlibInjectorManager] SHA256 校验通过");
        Files.writeString(versionFile, info.version, StandardCharsets.UTF_8);
        Files.writeString(hashFile, info.sha256, StandardCharsets.UTF_8);
        System.err.println("[AuthlibInjectorManager] authlib-injector.jar 下载完成: " + jarPath);
        return jarPath;
    }

    /**
     * 计算文件 SHA-256 摘要，返回小写十六进制字符串。
     * 用于校验下载的 authlib-injector.jar 完整性，防止供应链攻击。
     */
    private static String sha256Hex(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 算法不可用", e);
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
    }

    /**
     * 预取 Yggdrasil API 元数据，返回 Base64 编码的 prefetched 字符串。
     * <p>
     * GET 皮肤站 {@code /api/yggdrasil} 端点，获取包含 skinDomains、signaturePublickey 等
     * 元数据的 JSON，Base64 编码后用于 -Dauthlibinjector.yggdrasil.prefetched 参数。
     *
     * @param apiUrl 皮肤站 API 根地址（如 https://skin.example.com/api/yggdrasil）
     * @return Base64 编码的 prefetched 数据；失败返回 null
     */
    public String prefetchYggdrasilApi(String apiUrl) {
        String normalizedUrl = YggdrasilAuthFlow.normalizeApiUrl(apiUrl);
        String fetchUrl = normalizedUrl.endsWith("/")
                ? normalizedUrl.substring(0, normalizedUrl.length() - 1)
                : normalizedUrl;
        String ssrf = com.pmcl.core.util.SsrfChecker.validateAllowingPrivateLan(fetchUrl);
        if (ssrf != null) {
            System.err.println("[AuthlibInjectorManager] 预取被 SSRF 防护拒绝: " + ssrf);
            return null;
        }

        Request req = new Request.Builder()
                .url(fetchUrl)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("[AuthlibInjectorManager] 预取 Yggdrasil API 失败 (HTTP " + resp.code() + ")");
                return null;
            }
            String body = resp.body() != null ? resp.body().string() : "";
            if (body.isEmpty()) {
                System.err.println("[AuthlibInjectorManager] 预取 Yggdrasil API 返回空响应");
                return null;
            }
            // Base64 编码（不换行）
            String base64 = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
            System.err.println("[AuthlibInjectorManager] Yggdrasil API 预取成功，长度=" + base64.length());
            return base64;
        } catch (IOException e) {
            System.err.println("[AuthlibInjectorManager] 预取 Yggdrasil API 网络错误: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取 authlib-injector 最新版本信息。
     * 版本信息 JSON 格式：
     * <pre>{ "version": "1.2.3", "downloadUrl": "https://...", "sha256": "..." }</pre>
     */
    private VersionInfo fetchLatestVersionInfo() {
        Request req = new Request.Builder()
                .url(VERSION_INFO_URL)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("[AuthlibInjectorManager] 获取版本信息失败 (HTTP " + resp.code() + ")");
                return null;
            }
            String body = resp.body() != null ? resp.body().string() : "";
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            VersionInfo info = new VersionInfo();
            info.version = safeStr(o, "version");
            info.downloadUrl = safeStr(o, "downloadUrl");
            info.sha256 = safeStr(o, "sha256");
            if (info.version.isEmpty() || info.downloadUrl.isEmpty()) {
                System.err.println("[AuthlibInjectorManager] 版本信息缺少必要字段");
                return null;
            }
            return info;
        } catch (IOException e) {
            System.err.println("[AuthlibInjectorManager] 获取版本信息网络错误: " + e.getMessage());
            return null;
        }
    }

    /** 下载文件到指定路径 */
    private void downloadFile(String url, Path target) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("下载失败 (HTTP " + resp.code() + "): " + url);
            }
            if (resp.body() == null) {
                throw new IOException("下载响应体为空: " + url);
            }
            try (InputStream is = resp.body().byteStream()) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String safeStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** authlib-injector 版本信息 */
    private static class VersionInfo {
        String version;
        String downloadUrl;
        String sha256;
    }
}
