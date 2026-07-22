package com.pmcl.core.auth;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 皮肤管理器：为微软账号和皮肤站账号上传/重置皮肤。
 * <p>
 * 微软账号使用 Mojang 标准 API：
 * <ul>
 *   <li>上传：POST https://api.minecraftservices.com/minecraft/profile/skins</li>
 *   <li>重置：DELETE https://api.minecraftservices.com/minecraft/profile/skins/active</li>
 * </ul>
 * <p>
 * 皮肤站账号使用 Blessing Skin Server 标准 API（LittleSkin 等兼容）：
 * <ul>
 *   <li>上传：POST {@code <apiUrl>/api/skin/upload}（multipart: skin file + model + pid）</li>
 *   <li>重置：DELETE {@code <apiUrl>/api/skin/delete?pid=<playerId>}</li>
 * </ul>
 * 皮肤站 API 需要用户的 Web session token（非 yggdrasil accessToken），
 * 因此上传时需要用户名密码重新登录获取 session。
 */
public final class SkinManager {

    private static final String MS_SKIN_API = "https://api.minecraftservices.com/minecraft/profile/skins";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    public SkinManager() {}

    /**
     * 上传皮肤到微软账号。
     *
     * @param mcAccessToken Minecraft access token（非 MS access token）
     * @param skinFile      皮肤 PNG 文件路径
     * @param model         "classic" 或 "slim"
     * @throws IOException 上传失败
     */
    public void uploadMicrosoftSkin(String mcAccessToken, Path skinFile, String model) throws IOException {
        if (mcAccessToken == null || mcAccessToken.isEmpty()) {
            throw new IOException("Minecraft access token 为空，请重新登录");
        }
        if (!Files.exists(skinFile)) {
            throw new IOException("皮肤文件不存在: " + skinFile);
        }
        validateSkinFile(skinFile);

        MediaType png = MediaType.parse("image/png");
        RequestBody fileBody = RequestBody.create(skinFile.toFile(), png);
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("file", skinFile.getFileName().toString(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(MS_SKIN_API)
                .header("Authorization", "Bearer " + mcAccessToken)
                .post(body)
                .build();

        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String msg = readErrorBody(resp);
                if (resp.code() == 401) {
                    throw new IOException("access token 已过期，请重新登录微软账号");
                }
                throw new IOException("上传皮肤失败 (" + resp.code() + "): " + msg);
            }
        }
    }

    /**
     * 重置微软账号皮肤为默认。
     *
     * @param mcAccessToken Minecraft access token
     * @throws IOException 重置失败
     */
    public void resetMicrosoftSkin(String mcAccessToken) throws IOException {
        if (mcAccessToken == null || mcAccessToken.isEmpty()) {
            throw new IOException("Minecraft access token 为空，请重新登录");
        }

        Request request = new Request.Builder()
                .url(MS_SKIN_API + "/active")
                .header("Authorization", "Bearer " + mcAccessToken)
                .delete()
                .build();

        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String msg = readErrorBody(resp);
                if (resp.code() == 401) {
                    throw new IOException("access token 已过期，请重新登录微软账号");
                }
                throw new IOException("重置皮肤失败 (" + resp.code() + "): " + msg);
            }
        }
    }

    /**
     * 上传皮肤到皮肤站账号（Blessing Skin Server API）。
     * <p>
     * 流程：用用户名密码登录获取 session cookie → 上传皮肤 → 释放 session。
     * 密码仅在内存中临时使用，不持久化。
     *
     * @param baseUrl  皮肤站基础地址（如 https://littleskin.cn）
     * @param username 用户名（邮箱）
     * @param password 密码
     * @param playerId 角色 UUID（去掉横线）
     * @param skinFile 皮肤 PNG 文件路径
     * @param model    "steve"（经典）或 "slim"（纤细）
     * @throws IOException 上传失败
     */
    public void uploadYggdrasilSkin(String baseUrl, String username, String password,
                                     String playerId, Path skinFile, String model) throws IOException {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IOException("皮肤站地址为空");
        }
        if (!Files.exists(skinFile)) {
            throw new IOException("皮肤文件不存在: " + skinFile);
        }
        validateSkinFile(skinFile);

        // 规范化基础 URL：去掉末尾的 /api/yggdrasil
        String base = baseUrl;
        if (base.endsWith("/api/yggdrasil")) {
            base = base.substring(0, base.length() - "/api/yggdrasil".length());
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        // 1. 登录获取 session cookie
        String loginJson = "{\"email\":\"" + escapeJson(username) + "\",\"password\":\"" + escapeJson(password) + "\"}";
        Request loginReq = new Request.Builder()
                .url(base + "/auth/login")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(loginJson, MediaType.parse("application/json")))
                .build();

        String cookies;
        try (Response loginResp = http.newCall(loginReq).execute()) {
            if (!loginResp.isSuccessful()) {
                throw new IOException("皮肤站登录失败 (" + loginResp.code() + "): " + readErrorBody(loginResp));
            }
            // 提取 Set-Cookie 头中的 session cookie
            cookies = extractCookies(loginResp);
            if (cookies.isEmpty()) {
                throw new IOException("皮肤站登录未返回 session，请检查用户名和密码");
            }
        }

        // 2. 上传皮肤
        MediaType png = MediaType.parse("image/png");
        RequestBody fileBody = RequestBody.create(skinFile.toFile(), png);
        MultipartBody uploadBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("pid", playerId)
                .addFormDataPart("model", model)
                .addFormDataPart("skin", skinFile.getFileName().toString(), fileBody)
                .build();

        Request uploadReq = new Request.Builder()
                .url(base + "/api/skin/upload")
                .header("Cookie", cookies)
                .post(uploadBody)
                .build();

        try (Response uploadResp = http.newCall(uploadReq).execute()) {
            if (!uploadResp.isSuccessful()) {
                String msg = readErrorBody(uploadResp);
                throw new IOException("上传皮肤到皮肤站失败 (" + uploadResp.code() + "): " + msg);
            }
        }
    }

    /**
     * 重置皮肤站账号皮肤。
     *
     * @param baseUrl  皮肤站基础地址
     * @param username 用户名
     * @param password 密码
     * @param playerId 角色 UUID（去掉横线）
     * @throws IOException 重置失败
     */
    public void resetYggdrasilSkin(String baseUrl, String username, String password,
                                    String playerId) throws IOException {
        String base = baseUrl;
        if (base.endsWith("/api/yggdrasil")) {
            base = base.substring(0, base.length() - "/api/yggdrasil".length());
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        // 登录获取 session
        String loginJson = "{\"email\":\"" + escapeJson(username) + "\",\"password\":\"" + escapeJson(password) + "\"}";
        Request loginReq = new Request.Builder()
                .url(base + "/auth/login")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(loginJson, MediaType.parse("application/json")))
                .build();

        String cookies;
        try (Response loginResp = http.newCall(loginReq).execute()) {
            if (!loginResp.isSuccessful()) {
                throw new IOException("皮肤站登录失败 (" + loginResp.code() + "): " + readErrorBody(loginResp));
            }
            cookies = extractCookies(loginResp);
            if (cookies.isEmpty()) {
                throw new IOException("皮肤站登录未返回 session");
            }
        }

        // 删除皮肤
        Request deleteReq = new Request.Builder()
                .url(base + "/api/skin/delete?pid=" + playerId)
                .header("Cookie", cookies)
                .delete()
                .build();

        try (Response delResp = http.newCall(deleteReq).execute()) {
            if (!delResp.isSuccessful()) {
                throw new IOException("重置皮肤站皮肤失败 (" + delResp.code() + "): " + readErrorBody(delResp));
            }
        }
    }

    /** 校验皮肤文件：大小不超过 1MB，扩展名是 png */
    private void validateSkinFile(Path skinFile) throws IOException {
        String name = skinFile.getFileName().toString().toLowerCase();
        if (!name.endsWith(".png")) {
            throw new IOException("皮肤文件必须是 PNG 格式");
        }
        long size = Files.size(skinFile);
        if (size > 1024 * 1024) {
            throw new IOException("皮肤文件过大（" + (size / 1024) + "KB），最大支持 1MB");
        }
    }

    /** 从响应头提取 Cookie */
    private String extractCookies(Response resp) {
        java.util.List<String> cookieHeaders = resp.headers("Set-Cookie");
        if (cookieHeaders.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String c : cookieHeaders) {
            int semi = c.indexOf(';');
            if (semi > 0) sb.append(c, 0, semi).append("; ");
            else sb.append(c).append("; ");
        }
        return sb.toString().trim();
    }

    /** 读取错误响应体 */
    private String readErrorBody(Response resp) {
        try {
            String body = resp.body() != null ? resp.body().string() : "";
            if (body.length() > 300) body = body.substring(0, 300);
            return body;
        } catch (IOException e) {
            return "(无法读取响应体)";
        }
    }

    /** 简单 JSON 字符串转义 */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
