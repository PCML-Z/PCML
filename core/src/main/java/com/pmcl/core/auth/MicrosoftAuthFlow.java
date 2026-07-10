package com.pmcl.core.auth;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 微软账号登录完整流程：
 * <ol>
 *   <li>OAuth2 设备码流程 → Microsoft access_token</li>
 *   <li>Xbox Live 认证 → userToken + userHash</li>
 *   <li>XSTS 认证 → XSTS token</li>
 *   <li>Minecraft Services 登录 → MC access_token</li>
 *   <li>获取玩家档案 → username + uuid</li>
 * </ol>
 */
public final class MicrosoftAuthFlow {

    // 公开客户端ID（来自 HMCL 等开源启动器，可在 Azure 自行注册替换）
    public static final String CLIENT_ID = "00000000402b5328";
    public static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    private static final String DEVICE_CODE_URL =
            "https://login.live.com/oauth20_connect.srf";
    private static final String TOKEN_URL =
            "https://login.live.com/oauth20_token.srf";
    private static final String XBL_URL =
            "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL =
            "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";
    private static final String MC_ENTITLEMENT_URL =
            "https://api.minecraftservices.com/entitlements/mcstore";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MicrosoftAuthFlow() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 第一步：请求设备码。用户需要在浏览器中打开 verificationUri 并输入 userCode。
     */
    public DeviceCode requestDeviceCode() throws IOException {
        String body = "client_id=" + CLIENT_ID +
                "&scope=" + java.net.URLEncoder.encode(SCOPE, "UTF-8");
        Request req = new Request.Builder()
                .url(DEVICE_CODE_URL)
                .post(RequestBody.create(body,
                        MediaType.get("application/x-www-form-urlencoded")))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body().string();
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return new DeviceCode(
                    o.get("device_code").getAsString(),
                    o.get("user_code").getAsString(),
                    o.get("verification_url").getAsString(),
                    o.get("expires_in").getAsInt(),
                    o.get("interval").getAsInt(),
                    o.get("message").getAsString()
            );
        }
    }

    /**
     * 第二步：轮询 token 端点直到用户完成登录。
     *
     * @param onPending 每次轮询返回 pending 时回调（可用于 UI 提示）
     */
    public CompletableFuture<String> pollForMsAccessToken(DeviceCode dc,
                                                          Consumer<String> onPending) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pollOnce(dc, onPending, future);
        return future;
    }

    private void pollOnce(DeviceCode dc, Consumer<String> onPending,
                          CompletableFuture<String> future) {
        String body = "client_id=" + CLIENT_ID +
                "&grant_type=urn:ietf:params:oauth:grant-type:device_code" +
                "&device_code=" + dc.getDeviceCode();
        Request req = new Request.Builder()
                .url(TOKEN_URL)
                .post(RequestBody.create(body,
                        MediaType.get("application/x-www-form-urlencoded")))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String json = resp.body().string();
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String error = o.has("error") ? o.get("error").getAsString() : null;
            if (error == null) {
                // 成功
                future.complete(o.get("access_token").getAsString());
                return;
            }
            switch (error) {
                case "authorization_pending":
                    if (onPending != null) onPending.accept("等待用户登录…");
                    break;
                case "slow_down":
                    scheduler.schedule(() -> pollOnce(dc, onPending, future),
                            dc.getInterval() + 5, TimeUnit.SECONDS);
                    return;
                case "expired_token":
                    future.completeExceptionally(new RuntimeException("设备码已过期"));
                    return;
                case "authorization_declined":
                    future.completeExceptionally(new RuntimeException("用户拒绝授权"));
                    return;
                default:
                    future.completeExceptionally(new RuntimeException("登录失败: " + error));
                    return;
            }
        } catch (IOException e) {
            future.completeExceptionally(new RuntimeException("网络错误", e));
            return;
        }
        // 间隔后重试
        scheduler.schedule(() -> pollOnce(dc, onPending, future),
                dc.getInterval(), TimeUnit.SECONDS);
    }

    /**
     * 第三步：用 MS access_token 换取 Xbox Live userToken。
     * 返回 [userToken, userHash]。
     */
    public String[] authXboxLive(String msAccessToken) throws IOException {
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msAccessToken);
        JsonObject payload = new JsonObject();
        payload.add("Properties", props);
        payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        payload.addProperty("TokenType", "JWT");

        JsonObject resp = postJson(XBL_URL, payload);
        String userToken = resp.get("Token").getAsString();
        String userHash = resp.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();
        return new String[]{userToken, userHash};
    }

    /**
     * 第四步：用 userToken 换取 XSTS token。
     */
    public String authXsts(String userToken) throws IOException {
        JsonObject props = new JsonObject();
        props.addProperty("SandboxId", "RETAIL");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(userToken);
        props.add("UserTokens", arr);
        JsonObject payload = new JsonObject();
        payload.add("Properties", props);
        payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        payload.addProperty("TokenType", "JWT");

        JsonObject resp = postJson(XSTS_URL, payload);
        return resp.get("Token").getAsString();
    }

    /**
     * 第五步：用 XSTS token 换取 MC access_token。
     */
    public String loginMinecraft(String xstsToken, String userHash) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JsonObject resp = postJson(MC_LOGIN_URL, payload);
        return resp.get("access_token").getAsString();
    }

    /**
     * 第六步：获取玩家档案（username + uuid + skinUrl + skinModel）。
     * 返回 [name, uuid, skinUrl, skinModel]
     */
    public String[] fetchProfile(String mcAccessToken) throws IOException {
        Request req = new Request.Builder()
                .url(MC_PROFILE_URL)
                .header("Authorization", "Bearer " + mcAccessToken)
                .get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("获取档案失败 code=" + resp.code());
            }
            JsonObject o = JsonParser.parseString(resp.body().string()).getAsJsonObject();
            String name = o.get("name").getAsString();
            String uuid = o.get("id").getAsString();
            // 提取皮肤数据：skins 数组中 state=ACTIVE 的条目
            String skinUrl = "";
            String skinModel = "classic";
            if (o.has("skins")) {
                for (JsonElement skinElem : o.getAsJsonArray("skins")) {
                    JsonObject skin = skinElem.getAsJsonObject();
                    String state = skin.has("state") ? skin.get("state").getAsString() : "";
                    if ("ACTIVE".equalsIgnoreCase(state)) {
                        skinUrl = skin.has("url") ? skin.get("url").getAsString() : "";
                        skinModel = skin.has("variant") ? skin.get("variant").getAsString() : "classic";
                        break;
                    }
                }
            }
            return new String[]{name, uuid, skinUrl, skinModel};
        }
    }

    /**
     * 校验是否拥有 MC。
     */
    public boolean checkOwnership(String mcAccessToken) throws IOException {
        Request req = new Request.Builder()
                .url(MC_ENTITLEMENT_URL)
                .header("Authorization", "Bearer " + mcAccessToken)
                .get().build();
        try (Response resp = http.newCall(req).execute()) {
            return resp.isSuccessful();
        }
    }

    /**
     * 端到端登录：传入已完成的设备码，完成剩余流程，返回完整 Account。
     */
    public Account completeLogin(String msAccessToken) throws IOException {
        String[] xbl = authXboxLive(msAccessToken);
        String xsts = authXsts(xbl[0]);
        String mcToken = loginMinecraft(xsts, xbl[1]);
        String[] profile = fetchProfile(mcToken);
        return new Account(profile[0], profile[1], mcToken, Account.AccountType.MICROSOFT,
                profile[2], profile[3]);
    }

    private JsonObject postJson(String url, JsonObject payload) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(RequestBody.create(gson.toJson(payload), JSON))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("请求失败 " + url + " code=" + resp.code() + " body=" + body);
            }
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }
}
