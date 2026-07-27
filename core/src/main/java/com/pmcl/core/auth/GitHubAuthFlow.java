package com.pmcl.core.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.CurlFallback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * GitHub OAuth2 设备码登录流程。
 * <ol>
 *   <li>请求设备码 → 用户在浏览器输入 userCode 授权</li>
 *   <li>轮询 access_token 端点直到授权完成</li>
 *   <li>调用 /user 接口获取用户名、ID、头像</li>
 * </ol>
 * <p>
 * 默认使用内置 Client ID；可在 {@code ~/.pmcl/github_client_id.txt} 写入自定义
 * <a href="https://github.com/settings/applications/new">GitHub OAuth App</a> Client ID 覆盖。
 */
public final class GitHubAuthFlow {

    /** 内置默认 Client ID（设备码流程）；生产部署建议用 github_client_id.txt 覆盖 */
    public static final String DEFAULT_CLIENT_ID = "Ov23liql9Lz1BxIbL1xX";
    public static final String SCOPE = "read:user";

    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_API_URL = "https://api.github.com/user";

    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");

    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final String clientId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "github-auth-scheduler");
        t.setDaemon(true);
        return t;
    });

    public GitHubAuthFlow() {
        this(null);
    }

    public GitHubAuthFlow(String clientId) {
        this.clientId = (clientId == null || clientId.isBlank()) ? DEFAULT_CLIENT_ID : clientId.trim();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public String getClientId() { return clientId; }

    /** 关闭内部调度线程 */
    public void shutdown() {
        scheduler.shutdownNow();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown();
    }

    /**
     * 第一步：请求设备码。用户需在浏览器打开 verificationUri 并输入 userCode。
     */
    public DeviceCode requestDeviceCode() throws IOException {
        String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
        String json;
        try {
            Request req = new Request.Builder()
                    .url(DEVICE_CODE_URL)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(body, FORM))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                json = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful() && (json == null || json.isEmpty())) {
                    throw new IOException("请求 GitHub 设备码失败 code=" + resp.code());
                }
            }
        } catch (IOException e) {
            if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                json = CurlFallback.postString(DEVICE_CODE_URL, body,
                        "application/x-www-form-urlencoded",
                        java.util.List.of("Accept: application/json"));
            } else {
                throw new IOException("请求 GitHub 设备码失败: " + e.getMessage(), e);
            }
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String error = safeStr(o, "error");
            if (!error.isEmpty()) {
                throw new IOException("请求 GitHub 设备码失败: " + error + " "
                        + safeStr(o, "error_description"));
            }
            String deviceCode = safeStr(o, "device_code");
            String userCode = safeStr(o, "user_code");
            String verificationUri = safeStr(o, "verification_uri");
            if (deviceCode.isEmpty() || userCode.isEmpty() || verificationUri.isEmpty()) {
                throw new IOException("GitHub 设备码响应缺少必填字段");
            }
            return new DeviceCode(
                    deviceCode,
                    userCode,
                    verificationUri,
                    o.has("expires_in") && !o.get("expires_in").isJsonNull() ? o.get("expires_in").getAsInt() : 900,
                    o.has("interval") && !o.get("interval").isJsonNull() ? o.get("interval").getAsInt() : 5,
                    safeStr(o, "message")
            );
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("解析 GitHub 设备码失败: " + t.getMessage(), t);
        }
    }

    /**
     * 第二步：轮询 token 端点直到用户完成授权。
     *
     * @param onPending 每次轮询返回 pending 时回调（可用于 UI 提示）
     */
    public CompletableFuture<String> pollForAccessToken(DeviceCode dc, Consumer<String> onPending) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pollOnce(dc, onPending, future);
        return future;
    }

    private void pollOnce(DeviceCode dc, Consumer<String> onPending,
                          CompletableFuture<String> future) {
        // S6: 取消/完成/异常后停止调度，避免无效请求触发限流
        if (future.isDone()) return;
        String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                "&device_code=" + URLEncoder.encode(dc.getDeviceCode(), StandardCharsets.UTF_8) +
                "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8);
        String json;
        try {
            Request req = new Request.Builder()
                    .url(TOKEN_URL)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(body, FORM))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                json = resp.body() != null ? resp.body().string() : "";
            }
        } catch (IOException e) {
            if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                try {
                    json = CurlFallback.postStringAllowingErrors(TOKEN_URL, body,
                            "application/x-www-form-urlencoded",
                            java.util.List.of("Accept: application/json"));
                } catch (IOException ce) {
                    future.completeExceptionally(new RuntimeException("网络错误", ce));
                    return;
                }
            } else {
                future.completeExceptionally(new RuntimeException("网络错误", e));
                return;
            }
        } catch (Throwable e) {
            future.completeExceptionally(new RuntimeException("网络错误", e));
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String error = o.has("error") && !o.get("error").isJsonNull() ? o.get("error").getAsString() : null;
            if (error == null) {
                String token = safeStr(o, "access_token");
                if (token.isEmpty()) {
                    future.completeExceptionally(new RuntimeException("GitHub access_token 为空"));
                    return;
                }
                future.complete(token);
                return;
            }
            switch (error) {
                case "authorization_pending":
                    if (onPending != null) onPending.accept("等待用户授权…");
                    break;
                case "slow_down":
                    scheduler.schedule(() -> pollOnce(dc, onPending, future),
                            dc.getInterval() + 5, TimeUnit.SECONDS);
                    return;
                case "expired_token":
                    future.completeExceptionally(new RuntimeException("设备码已过期"));
                    return;
                case "access_denied":
                    future.completeExceptionally(new RuntimeException("用户拒绝授权"));
                    return;
                default:
                    future.completeExceptionally(new RuntimeException("登录失败: " + error));
                    return;
            }
        } catch (Throwable e) {
            future.completeExceptionally(new RuntimeException("解析 GitHub token 失败", e));
            return;
        }
        // S6: 调度前再次检查，避免取消后仍发请求
        if (future.isDone()) return;
        scheduler.schedule(() -> pollOnce(dc, onPending, future),
                dc.getInterval(), TimeUnit.SECONDS);
    }

    /**
     * 第三步：用 access_token 获取 GitHub 用户信息并构造 Account。
     * <ul>
     *   <li>username = GitHub login</li>
     *   <li>uuid = 基于 GitHub 用户 ID 生成的离线 UUID</li>
     *   <li>skinUrl = GitHub 头像 URL（用于卡片头像显示）</li>
     * </ul>
     */
    public Account completeLogin(String accessToken) throws IOException {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IOException("GitHub access_token 为空，拒绝完成登录");
        }
        String json;
        try {
            Request req = new Request.Builder()
                    .url(USER_API_URL)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .get()
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                json = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new IOException("获取 GitHub 用户信息失败 HTTP " + resp.code());
                }
            }
        } catch (IOException e) {
            if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                json = new String(CurlFallback.getBytes(USER_API_URL, "GET",
                        java.util.List.of(
                                "Authorization: Bearer " + accessToken,
                                "Accept: application/vnd.github+json")),
                        StandardCharsets.UTF_8);
            } else {
                throw new IOException("获取 GitHub 用户信息失败: " + e.getMessage(), e);
            }
        }
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String login = safeStr(o, "login");
        if (login.isEmpty()) {
            throw new IOException("GitHub 用户信息缺少 login");
        }
        long githubId = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsLong() : 0;
        String avatarUrl = safeStr(o, "avatar_url");
        String uuid = UUID.nameUUIDFromBytes(
                ("GitHub:" + githubId).getBytes(StandardCharsets.UTF_8)).toString();
        return new Account(login, uuid, accessToken, Account.AccountType.GITHUB, avatarUrl, "classic");
    }

    private static String safeStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
