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
 * 使用前需在 <a href="https://github.com/settings/applications/new">GitHub OAuth Apps</a>
 * 注册应用（Authorization 类型选 OAuth App），将得到的 Client ID 填入 {@link #CLIENT_ID}。
 */
public final class GitHubAuthFlow {

    // TODO: 替换为你自己的 GitHub OAuth App Client ID
    // 注册地址：https://github.com/settings/applications/new
    // Authorization 类型选 "OAuth App"，回调 URL 随意填（设备码流程不用回调）
    public static final String CLIENT_ID = "YOUR_GITHUB_CLIENT_ID";
    public static final String SCOPE = "read:user";

    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_API_URL = "https://api.github.com/user";

    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");

    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "github-auth-scheduler");
        t.setDaemon(true);
        return t;
    });

    public GitHubAuthFlow() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /** 关闭内部调度线程 */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 第一步：请求设备码。用户需在浏览器打开 verificationUri 并输入 userCode。
     */
    public DeviceCode requestDeviceCode() throws IOException {
        String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) +
                "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
        Request req = new Request.Builder()
                .url(DEVICE_CODE_URL)
                .header("Accept", "application/json")
                .post(RequestBody.create(body, FORM))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body() != null ? resp.body().string() : "";
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return new DeviceCode(
                    safeStr(o, "device_code"),
                    safeStr(o, "user_code"),
                    safeStr(o, "verification_uri"),
                    o.has("expires_in") && !o.get("expires_in").isJsonNull() ? o.get("expires_in").getAsInt() : 900,
                    o.has("interval") && !o.get("interval").isJsonNull() ? o.get("interval").getAsInt() : 5,
                    safeStr(o, "message")
            );
        } catch (IOException e) {
            throw new RuntimeException("网络错误", e);
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
        String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) +
                "&device_code=" + URLEncoder.encode(dc.getDeviceCode(), StandardCharsets.UTF_8) +
                "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8);
        Request req = new Request.Builder()
                .url(TOKEN_URL)
                .header("Accept", "application/json")
                .post(RequestBody.create(body, FORM))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body() != null ? resp.body().string() : "";
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String error = o.has("error") && !o.get("error").isJsonNull() ? o.get("error").getAsString() : null;
            if (error == null) {
                future.complete(safeStr(o, "access_token"));
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
            future.completeExceptionally(new RuntimeException("网络错误", e));
            return;
        }
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
        Request req = new Request.Builder()
                .url(USER_API_URL)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body() != null ? resp.body().string() : "";
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String login = safeStr(o, "login");
            long githubId = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsLong() : 0;
            String avatarUrl = safeStr(o, "avatar_url");

            // 基于 GitHub 用户 ID 生成确定性 UUID
            String uuid = UUID.nameUUIDFromBytes(
                    ("GitHub:" + githubId).getBytes(StandardCharsets.UTF_8)).toString();

            return new Account(login, uuid, accessToken, Account.AccountType.GITHUB, avatarUrl, "classic");
        } catch (IOException e) {
            throw new RuntimeException("获取 GitHub 用户信息失败", e);
        }
    }

    private static String safeStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
