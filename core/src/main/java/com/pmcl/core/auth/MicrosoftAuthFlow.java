package com.pmcl.core.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.CurlFallback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    // Legacy 公共客户端ID（来自 Minecraft 官方启动器）。
    // 该 client_id 仅在 login.live.com 端点可用；v2.0 consumers tenant
    // (login.microsoftonline.com) 不识别它（返回 AADSTS700016）。
    // 若要使用浏览器授权码流程，需在 Azure 注册独立应用并将 client_id
    // 写入 ~/.pmcl/azure_client_id.txt。
    public static final String LEGACY_CLIENT_ID = "00000000402b5328";
    // 已废弃：MBI_SSL scope 返回的 compact token (EwDIA+... 1292 字符) 会被
    // Xbox Live /user/authenticate 以 401 空响应拒绝。保留常量仅供历史参考。
    public static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    // 实际使用的 scope：login.live.com 端点支持此 v2.0 scope 且接受 LEGACY_CLIENT_ID。
    // 返回 JWT token (eyJ... 前缀)，Xbox Live 可正确认证（RpsTicket: d=<token>）。
    // offline_access 用于获取 refresh_token（后续可刷新）。
    public static final String V2_SCOPE = "XboxLive.signin offline_access";

    private final String clientId;

    private static final String DEVICE_CODE_URL =
            "https://login.live.com/oauth20_connect.srf";
    private static final String AUTHORIZE_URL =
            "https://login.live.com/oauth20_authorize.srf";
    private static final String TOKEN_URL =
            "https://login.live.com/oauth20_token.srf";
    // v2.0 端点：浏览器授权码流程专用。login.live.com 旧端点对授权码流程返回的
    // token 缺少 Xbox Live 期望的 audience claim，导致 /user/authenticate 返回 401。
    private static final String V2_AUTHORIZE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String V2_TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    // v2.0 device code 端点：wiki.vg 明确要求使用 consumers tenant + XboxLive.signin scope，
    // 否则 Xbox Live /user/authenticate 会返回 401（空 body）。旧 MBI_SSL scope 已被微软废弃。
    private static final String V2_DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
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
    // license 端点比 mcstore 更全面：包含 Xbox Game Pass 订阅状态。
    // profile 404 时用它区分"未购买"和"有游戏但未创建档案"两种情况。
    private static final String MC_LICENSE_URL =
            "https://api.minecraftservices.com/entitlements/license";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "msauth-scheduler");
        t.setDaemon(true);
        return t;
    });

    public MicrosoftAuthFlow() {
        this(LEGACY_CLIENT_ID);
    }

    /**
     * 用自定义 Azure client_id 构造（用于浏览器授权码流程）。
     * 传入 null 或空字符串则回退到 legacy client_id（仅支持 device code flow）。
     */
    public MicrosoftAuthFlow(String clientId) {
        this.clientId = (clientId == null || clientId.isEmpty()) ? LEGACY_CLIENT_ID : clientId;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /** 返回当前使用的 client_id。 */
    public String getClientId() {
        return clientId;
    }

    /** 判断是否使用自定义 client_id（即支持浏览器授权码流程）。 */
    public boolean hasCustomClientId() {
        return !LEGACY_CLIENT_ID.equals(clientId);
    }

    /** 关闭内部调度线程和 HTTP 连接池，释放资源。关闭后不可再用，仅供应用退出时调用。 */
    public void shutdown() {
        scheduler.shutdownNow();
        // 关闭 OkHttpClient 的连接池和 dispatcher，防止线程泄漏阻止 JVM 退出
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown();
    }

    /**
     * 第一步：请求设备码。用户需要在浏览器中打开 verificationUri 并输入 userCode。
     * <p>
     * 直连失败时自动 fallback 到系统 curl（绕过 GFW 对 Java TLS 指纹的 RST 干扰）。
     */
    public DeviceCode requestDeviceCode() throws IOException {
        // login.live.com 端点同时支持 MBI_SSL 和 v2.0 scope，且接受 LEGACY_CLIENT_ID。
        // v2.0 consumers tenant (login.microsoftonline.com) 不识别 LEGACY_CLIENT_ID（AADSTS700016）。
        // 必须使用 V2_SCOPE (XboxLive.signin offline_access)：
        //   - MBI_SSL 返回的 compact token (EwDIA+... 1292 字符) 会被 Xbox Live /user/authenticate 拒绝 (401 空响应)。
        //   - XboxLive.signin 返回 JWT token (eyJ... 前缀)，Xbox Live 可正确认证。
        // response_type=device_code 是 login.live.com 端点的必需参数。
        String body = "client_id=" + clientId +
                "&scope=" + java.net.URLEncoder.encode(V2_SCOPE, "UTF-8") +
                "&response_type=device_code";
        String json;
        try {
            Request req = new Request.Builder()
                    .url(DEVICE_CODE_URL)
                    .post(RequestBody.create(body,
                            MediaType.get("application/x-www-form-urlencoded")))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                json = resp.body() != null ? resp.body().string() : "";
            }
        } catch (IOException e) {
            // SSL 握手失败（GFW 干扰）→ fallback 到 curl
            if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                json = CurlFallback.postString(DEVICE_CODE_URL, body,
                        "application/x-www-form-urlencoded", null);
            } else {
                throw new IOException("请求设备码失败: " + e.getMessage(), e);
            }
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            // 微软 device code 端点返回字段为 verification_uri（部分旧文档写作 verification_url，
            // 实际响应为 verification_uri）。同时兼容两种字段名以防万一。
            String verificationUri = safeStr(o, "verification_uri");
            if (verificationUri.isEmpty()) verificationUri = safeStr(o, "verification_url");
            return new DeviceCode(
                    safeStr(o, "device_code"),
                    safeStr(o, "user_code"),
                    verificationUri,
                    o.has("expires_in") && !o.get("expires_in").isJsonNull() ? o.get("expires_in").getAsInt() : 0,
                    o.has("interval") && !o.get("interval").isJsonNull() ? o.get("interval").getAsInt() : 0,
                    safeStr(o, "message")
            );
        } catch (Throwable t) {
            throw new IOException("解析设备码响应失败: " + t.getMessage() + " body=" + json, t);
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
        // login.live.com 旧端点：与 requestDeviceCode 配套。
        // 不带 scope（scope 已在 devicecode 请求时指定，token 端点会自动继承）。
        String body = "client_id=" + clientId +
                "&grant_type=urn:ietf:params:oauth:grant-type:device_code" +
                "&device_code=" + dc.getDeviceCode();
        String json;
        try {
            Request req = new Request.Builder()
                    .url(TOKEN_URL)
                    .post(RequestBody.create(body,
                            MediaType.get("application/x-www-form-urlencoded")))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                json = resp.body() != null ? resp.body().string() : "";
            }
        } catch (IOException e) {
            // SSL 握手失败（GFW 干扰）→ fallback 到 curl
            if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                try {
                    // token 端点对 pending/slow_down/expired/declined 都返回 HTTP 400，
                    // 必须用 postStringAllowingErrors 拿到 body 才能区分具体状态
                    json = CurlFallback.postStringAllowingErrors(TOKEN_URL, body,
                            "application/x-www-form-urlencoded", null);
                } catch (IOException ce) {
                    future.completeExceptionally(new RuntimeException("网络错误: " + ce.getMessage(), ce));
                    return;
                }
            } else {
                future.completeExceptionally(new RuntimeException("网络错误: " + e.getMessage(), e));
                return;
            }
        } catch (Throwable e) {
            // 保留原始异常消息（如 SSL_ERROR_SYSCALL），便于诊断 GFW 干扰
            future.completeExceptionally(new RuntimeException("网络错误: " + e.getMessage(), e));
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String error = o.has("error") && !o.get("error").isJsonNull() ? o.get("error").getAsString() : null;
            if (error == null) {
                // 成功
                String token = safeStr(o, "access_token");
                if (token.isEmpty()) {
                    future.completeExceptionally(new RuntimeException("token 响应中 access_token 为空: " + json));
                    return;
                }
                // 不输出 token 长度/前缀等元数据，防止凭据信息泄漏到共享日志
                future.complete(token);
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
        } catch (Throwable t) {
            future.completeExceptionally(new RuntimeException("解析 token 响应失败: " + t.getMessage() + " body=" + json, t));
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
        if (msAccessToken.isEmpty()) {
            throw new IOException("MS access_token 为空，无法认证 Xbox Live");
        }

        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msAccessToken);
        JsonObject payload = new JsonObject();
        payload.add("Properties", props);
        payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        payload.addProperty("TokenType", "JWT");

        JsonObject resp;
        try {
            resp = postJson(XBL_URL, payload);
        } catch (IOException e) {
            // 不在异常消息中暴露 token 元数据（长度/前缀/JWT 标记），仅说明失败原因
            throw new IOException("Xbox Live 认证失败: " + e.getMessage()
                    + "（可能原因：token 已过期 / 网络中断 / scope 不匹配）", e);
        }
        String userToken = safeStr(resp, "Token");
        String userHash = "";
        if (resp.has("DisplayClaims") && resp.getAsJsonObject("DisplayClaims").has("xui")) {
            JsonArray xui = resp.getAsJsonObject("DisplayClaims").getAsJsonArray("xui");
            if (xui.size() > 0 && xui.get(0).getAsJsonObject().has("uhs")) {
                userHash = xui.get(0).getAsJsonObject().get("uhs").getAsString();
            }
        }
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
        return safeStr(resp, "Token");
    }

    /**
     * 第五步：用 XSTS token 换取 MC access_token。
     */
    public String loginMinecraft(String xstsToken, String userHash) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JsonObject resp = postJson(MC_LOGIN_URL, payload);
        return safeStr(resp, "access_token");
    }

    /**
     * 第六步：获取玩家档案（username + uuid + skinUrl + skinModel）。
     * 返回 [name, uuid, skinUrl, skinModel]
     * <p>
     * 404 时调用 license 端点区分两种情况：
     * <ul>
     *   <li>有 game_minecraft license：账号有游戏但未创建档案（Game Pass 用户需先在
     *       minecraft.net 登录一次以创建玩家档案）</li>
     *   <li>无 license：账号未购买 Minecraft Java 版</li>
     * </ul>
     */
    public String[] fetchProfile(String mcAccessToken) throws IOException {
        String profileJson;
        try {
            Request req = new Request.Builder()
                    .url(MC_PROFILE_URL)
                    .header("Authorization", "Bearer " + mcAccessToken)
                    .get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.code() == 404) {
                    // profile 不存在。查 license 端点区分原因，给出明确错误。
                    boolean hasGame = checkLicense(mcAccessToken);
                    if (hasGame) {
                        throw new IOException("账号已拥有 Minecraft 但无玩家档案 (404)。" +
                                "请先在 minecraft.net 登录一次以创建档案，再返回启动器登录。");
                    } else {
                        throw new IOException("此微软账号未购买 Minecraft Java 版 (profile 404)，无法登录。" +
                                "若你是 Xbox Game Pass 用户，需先在 minecraft.net 登录一次激活档案。");
                    }
                }
                if (!resp.isSuccessful()) {
                    throw new IOException("获取档案失败 code=" + resp.code());
                }
                profileJson = resp.body() != null ? resp.body().string() : "";
            }
        } catch (IOException e) {
            // SSL 握手失败（GFW 干扰）→ fallback 到 curl
            if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                List<String> headers = new ArrayList<>();
                headers.add("Authorization: Bearer " + mcAccessToken);
                byte[] bytes = CurlFallback.getBytes(MC_PROFILE_URL, "GET", headers);
                profileJson = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                throw e;
            }
        }
        JsonObject o = JsonParser.parseString(profileJson).getAsJsonObject();
        String name = safeStr(o, "name");
        String uuid = safeStr(o, "id");
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
     * 检查 license 端点是否包含 game_minecraft 项。
     * 比 mcstore 更全面：mcstore 对 Game Pass 用户可能返回空，
     * 而 license 端点会包含订阅状态。
     */
    public boolean checkLicense(String mcAccessToken) throws IOException {
        String json;
        try {
            Request req = new Request.Builder()
                    .url(MC_LICENSE_URL)
                    .header("Authorization", "Bearer " + mcAccessToken)
                    .get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) return false;
                json = resp.body() != null ? resp.body().string() : "";
            }
        } catch (IOException e) {
            if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                List<String> headers = new ArrayList<>();
                headers.add("Authorization: Bearer " + mcAccessToken);
                byte[] bytes = CurlFallback.getBytes(MC_LICENSE_URL, "GET", headers);
                json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                throw e;
            }
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("items") && o.get("items").isJsonArray()) {
                for (JsonElement item : o.getAsJsonArray("items")) {
                    JsonObject it = item.getAsJsonObject();
                    String name = safeStr(it, "name");
                    if ("game_minecraft".equals(name)) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 端到端登录：传入已完成的设备码，完成剩余流程，返回完整 Account。
     * <p>
     * 把 Xbox Live userHash（uhs）保存到 Account.xuid，供启动时填充
     * ${auth_xuid} 参数（1.16+ 连接 Realms 或需 Xbox Live 验证的服务器时必需）。
     */
    public Account completeLogin(String msAccessToken) throws IOException {
        String[] xbl = authXboxLive(msAccessToken);
        String xsts = authXsts(xbl[0]);
        String mcToken = loginMinecraft(xsts, xbl[1]);
        String[] profile = fetchProfile(mcToken);
        return new Account(profile[0], profile[1], mcToken, Account.AccountType.MICROSOFT,
                profile[2], profile[3], xbl[1]);
    }

    /**
     * 浏览器授权码流程登录（推荐方式，用户体验最佳）。
     * <p>
     * 流程：
     * <ol>
     *   <li>启动本地 HTTP 服务器监听回调</li>
     *   <li>构造授权 URL，调用 openBrowser 回调打开浏览器</li>
     *   <li>用户在浏览器登录授权</li>
     *   <li>Microsoft 重定向回本地服务器，附带 code</li>
     *   <li>用 code 交换 access_token</li>
     *   <li>继续 XBL → XSTS → MC 登录流程</li>
     * </ol>
     * <p>
     * 浏览器走系统网络栈，不受 Java TLS 指纹被 GFW RST 的影响；
     * token 交换和后续 API 调用仍带 curl fallback。
     *
     * @param onStatus    状态回调（UI 显示进度）
     * @param openBrowser 接收授权 URL 并打开系统浏览器的回调
     * @return 完整 Account
     */
    public Account loginViaBrowser(Consumer<String> onStatus,
                                    Consumer<String> openBrowser) throws IOException {
        onStatus.accept("准备登录…");
        try (OAuthCallbackServer server = new OAuthCallbackServer()) {
            String redirectUri = server.getRedirectUri();

            // 构造授权 URL（v2.0 端点）
            String authUrl = V2_AUTHORIZE_URL + "?" +
                    "client_id=" + clientId +
                    "&response_type=code" +
                    "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, "UTF-8") +
                    "&scope=" + java.net.URLEncoder.encode(V2_SCOPE, "UTF-8") +
                    "&prompt=login";  // 强制重新登录，避免缓存的账号干扰

            onStatus.accept("打开浏览器登录…");
            openBrowser.accept(authUrl);

            // 等待授权码（5 分钟超时）
            String code = server.awaitCode(300);

            onStatus.accept("交换 access_token…");
            String msAccessToken = exchangeCodeForToken(code, redirectUri);

            onStatus.accept("登录 Xbox Live…");
            String[] xbl = authXboxLive(msAccessToken);

            onStatus.accept("获取 XSTS token…");
            String xsts = authXsts(xbl[0]);

            onStatus.accept("登录 Minecraft…");
            String mcToken = loginMinecraft(xsts, xbl[1]);

            onStatus.accept("获取玩家档案…");
            String[] profile = fetchProfile(mcToken);

            return new Account(profile[0], profile[1], mcToken, Account.AccountType.MICROSOFT,
                    profile[2], profile[3], xbl[1]);
        }
    }

    /**
     * 用授权码交换 MS access_token。
     * 带 curl fallback，防止 GFW 拦截 Java TLS。
     */
    private String exchangeCodeForToken(String code, String redirectUri) throws IOException {
        String body = "client_id=" + clientId +
                "&grant_type=authorization_code" +
                "&code=" + java.net.URLEncoder.encode(code, "UTF-8") +
                "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, "UTF-8") +
                "&scope=" + java.net.URLEncoder.encode(V2_SCOPE, "UTF-8");
        String json;
        try {
            Request req = new Request.Builder()
                    .url(V2_TOKEN_URL)
                    .post(RequestBody.create(body,
                            MediaType.get("application/x-www-form-urlencoded")))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                json = resp.body() != null ? resp.body().string() : "";
            }
        } catch (IOException e) {
            if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                json = CurlFallback.postString(V2_TOKEN_URL, body,
                        "application/x-www-form-urlencoded", null);
            } else {
                throw new IOException("交换 access_token 失败: " + e.getMessage(), e);
            }
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String error = safeStr(o, "error");
            if (!error.isEmpty()) {
                throw new IOException("交换 token 失败: " + error + " " + safeStr(o, "error_description"));
            }
            String token = safeStr(o, "access_token");
            if (token.isEmpty()) {
                throw new IOException("access_token 为空: " + json);
            }
            return token;
        } catch (Throwable t) {
            throw new IOException("解析 token 响应失败: " + t.getMessage() + " body=" + json, t);
        }
    }

    private JsonObject postJson(String url, JsonObject payload) throws IOException {
        String bodyJson = gson.toJson(payload);
        String body;
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new IOException("请求失败 " + url + " code=" + resp.code() + " body=" + body);
                }
            }
        } catch (IOException e) {
            // SSL 握手失败（GFW 干扰）→ fallback 到 curl
            if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                List<String> headers = new ArrayList<>();
                headers.add("Accept: application/json");
                body = CurlFallback.postString(url, bodyJson, "application/json", headers);
            } else {
                throw e;
            }
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static String safeStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
