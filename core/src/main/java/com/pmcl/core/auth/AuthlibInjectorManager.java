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
        if (info == null) {
            // 获取版本信息失败，若本地已有 jar 则使用旧的
            if (Files.exists(jarPath)) {
                System.err.println("[AuthlibInjectorManager] 无法获取最新版本信息，使用本地已有的 authlib-injector.jar");
                return jarPath;
            }
            throw new IOException("无法获取 authlib-injector 版本信息，且本地不存在 jar 文件");
        }

        // 检查本地版本是否最新
        Path versionFile = jarPath.resolveSibling(jarPath.getFileName() + ".version");
        if (Files.exists(jarPath) && Files.exists(versionFile)) {
            try {
                String localVersion = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
                if (localVersion.equals(info.version)) {
                    return jarPath; // 已是最新
                }
            } catch (IOException ignored) {}
        }

        // 下载 jar
        System.err.println("[AuthlibInjectorManager] 下载 authlib-injector " + info.version + " from " + info.downloadUrl);
        downloadFile(info.downloadUrl, jarPath);
        Files.writeString(versionFile, info.version, StandardCharsets.UTF_8);
        System.err.println("[AuthlibInjectorManager] authlib-injector.jar 下载完成: " + jarPath);
        return jarPath;
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
