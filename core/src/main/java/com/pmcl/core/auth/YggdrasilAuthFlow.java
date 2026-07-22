package com.pmcl.core.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Yggdrasil API 认证流程（用于皮肤站 / authlib-injector 外置登录）。
 * <p>
 * 兼容 <a href="https://github.com/yushijinhun/authlib-injector">authlib-injector</a>
 * 规范的皮肤站（如 LittleSkin、Blessing Skin Server）。
 * <p>
 * 认证端点（相对皮肤站 API 根地址）：
 * <ul>
 *   <li>{@code POST /authserver/login} — 登录，返回 accessToken + selectedProfile</li>
 *   <li>{@code POST /authserver/refresh} — 刷新 accessToken</li>
 *   <li>{@code POST /authserver/validate} — 验证 accessToken 是否有效</li>
 *   <li>{@code POST /authserver/invalidate} — 登出，使 accessToken 失效</li>
 * </ul>
 */
public final class YggdrasilAuthFlow {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Gson gson = new Gson();

    public YggdrasilAuthFlow() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 规范化皮肤站 API 地址。
     * 用户可能输入的是首页地址（如 https://skin.example.com），
     * 需补齐为 API 根地址（https://skin.example.com/api/yggdrasil）。
     * 若输入已包含 /api/yggdrasil 则原样返回（去除末尾多余斜杠）。
     */
    public static String normalizeApiUrl(String input) {
        if (input == null || input.trim().isEmpty()) return "";
        String url = input.trim();
        // 去除末尾斜杠
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        // 已包含 api/yggdrasil 路径
        if (url.endsWith("/api/yggdrasil")) return url;
        // 去除可能的 /api 后缀再重新拼接
        if (url.endsWith("/api")) url = url.substring(0, url.length() - 4);
        return url + "/api/yggdrasil";
    }

    /** 构建完整请求 URL：apiUrl + 相对路径 */
    private String url(String apiUrl, String path) {
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        return base + path;
    }

    /**
     * 登录皮肤站。
     *
     * @param apiUrl   皮肤站 API 根地址（如 https://skin.example.com/api/yggdrasil）
     * @param username 用户名或邮箱
     * @param password 密码
     * @return 登录成功后的 Account
     * @throws IOException 网络错误或认证失败
     */
    public Account login(String apiUrl, String username, String password) throws IOException {
        String normalizedUrl = normalizeApiUrl(apiUrl);

        // 构建登录请求体
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        JsonObject payload = new JsonObject();
        payload.add("agent", agent);
        payload.addProperty("username", username);
        payload.addProperty("password", password);
        payload.addProperty("clientToken", UUID.randomUUID().toString().replace("-", ""));

        Request req = new Request.Builder()
                .url(url(normalizedUrl, "/authserver/login"))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                String errorMsg = parseErrorMessage(body);
                throw new IOException(errorMsg.isEmpty() ? ("登录失败 (HTTP " + resp.code() + ")") : errorMsg);
            }

            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            String accessToken = safeStr(o, "accessToken");
            JsonObject selectedProfile = o.has("selectedProfile") && o.get("selectedProfile").isJsonObject()
                    ? o.getAsJsonObject("selectedProfile") : null;

            if (selectedProfile == null) {
                throw new IOException("皮肤站返回的登录结果中没有 selectedProfile，可能该账号尚未创建角色");
            }

            String playerName = safeStr(selectedProfile, "name");
            String playerUuid = safeStr(selectedProfile, "id");

            return new Account(playerName, playerUuid, accessToken,
                    Account.AccountType.YGGDRASIL, "", "classic", "", normalizedUrl);
        }
    }

    /**
     * 验证 accessToken 是否仍然有效。
     *
     * @param apiUrl      皮肤站 API 根地址
     * @param accessToken 待验证的 token
     * @return true 表示 token 有效
     */
    public boolean validate(String apiUrl, String accessToken) {
        String normalizedUrl = normalizeApiUrl(apiUrl);
        JsonObject payload = new JsonObject();
        payload.addProperty("accessToken", accessToken);
        payload.addProperty("clientToken", "");

        Request req = new Request.Builder()
                .url(url(normalizedUrl, "/authserver/validate"))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            // 204 No Content 表示 token 有效
            return resp.code() == 204;
        } catch (IOException e) {
            System.err.println("[YggdrasilAuthFlow] validate 网络错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 刷新 accessToken。
     *
     * @param apiUrl         皮肤站 API 根地址
     * @param accessToken    旧的 token
     * @return 新的 accessToken，失败返回 null
     */
    public String refresh(String apiUrl, String accessToken) {
        String normalizedUrl = normalizeApiUrl(apiUrl);
        JsonObject payload = new JsonObject();
        payload.addProperty("accessToken", accessToken);
        payload.addProperty("clientToken", "");

        Request req = new Request.Builder()
                .url(url(normalizedUrl, "/authserver/refresh"))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                System.err.println("[YggdrasilAuthFlow] refresh 失败 (HTTP " + resp.code() + "): " + body);
                return null;
            }
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            return safeStr(o, "accessToken");
        } catch (IOException e) {
            System.err.println("[YggdrasilAuthFlow] refresh 网络错误: " + e.getMessage());
            return null;
        }
    }

    /**
     * 使 accessToken 失效（登出）。
     *
     * @param apiUrl      皮肤站 API 根地址
     * @param accessToken 待失效的 token
     */
    public void invalidate(String apiUrl, String accessToken) {
        String normalizedUrl = normalizeApiUrl(apiUrl);
        JsonObject payload = new JsonObject();
        payload.addProperty("accessToken", accessToken);
        payload.addProperty("clientToken", "");

        Request req = new Request.Builder()
                .url(url(normalizedUrl, "/authserver/invalidate"))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("[YggdrasilAuthFlow] invalidate 失败 (HTTP " + resp.code() + ")");
            }
        } catch (IOException e) {
            System.err.println("[YggdrasilAuthFlow] invalidate 网络错误: " + e.getMessage());
        }
    }

    /** 从错误响应中提取人类可读的错误消息 */
    private static String parseErrorMessage(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            return safeStr(o, "errorMessage");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safeStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
