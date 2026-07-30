package com.pmcl.core.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;

/**
 * 账号认证 + 多账号管理。
 * <p>
 * 多账号存储格式（~/.pmcl/accounts.json）：
 * <pre>
 * {
 *   "selected": "uuid-of-current",
 *   "accounts": [
 *     { "uuid": "...", "username": "...", "accessToken": "...", "type": "OFFLINE|MICROSOFT" }
 *   ]
 * }
 * </pre>
 */
public final class AuthService {

    // H1: flow 字段加 volatile，保证 setAzureClientId 替换后其他线程立即可见
    // 否则旧 flow 的 scheduler/连接池永不 shutdown，造成线程与连接泄漏
    private volatile MicrosoftAuthFlow flow = new MicrosoftAuthFlow();
    private volatile GitHubAuthFlow githubFlow = new GitHubAuthFlow();
    private final YggdrasilAuthFlow yggdrasilFlow = new YggdrasilAuthFlow();
    private final AuthlibInjectorManager authlibInjectorManager = new AuthlibInjectorManager();
    private final Gson gson = new Gson();

    /**
     * 创建离线账号。
     * <p>
     * UUID 使用 Bukkit/Paper 兼容前缀 {@code OfflinePlayer:}（非历史 {@code Offline:}），
     * 以便与主流离线服 / 皮肤站工具对齐。
     */
    public Account offline(String username) {
        String uuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
        // accessToken 置空：离线会话不能用于 Mojang API；用 uuid 充数会触发 401 噪音
        return new Account(username, uuid, "", Account.AccountType.OFFLINE);
    }

    /**
     * 请求设备码（UI 层显示给用户）。
     */
    public DeviceCode requestDeviceCode() throws IOException {
        return flow.requestDeviceCode();
    }

    /**
     * 设置自定义 Azure client_id（用于浏览器授权码流程）。
     * 传入 null 或空字符串则回退到 legacy client_id（仅支持 device code flow）。
     */
    public void setAzureClientId(String clientId) {
        // H1: 关闭旧 flow 的 scheduler/连接池，避免泄漏
        MicrosoftAuthFlow old = this.flow;
        this.flow = new MicrosoftAuthFlow(clientId);
        if (old != null) {
            try { old.shutdown(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 设置自定义 GitHub OAuth Client ID（设备码流程）。
     * 通常从 {@code ~/.pmcl/github_client_id.txt} 加载。
     */
    public void setGitHubClientId(String clientId) {
        GitHubAuthFlow old = this.githubFlow;
        this.githubFlow = new GitHubAuthFlow(clientId);
        if (old != null) {
            try { old.shutdown(); } catch (Throwable ignored) {}
        }
    }

    /** 判断当前是否使用自定义 client_id（即支持浏览器授权码流程）。 */
    public boolean hasCustomClientId() {
        return flow.hasCustomClientId();
    }

    /**
     * 异步等待用户完成登录，并完成剩余流程，最终返回 Account。
     * 安全修复：捕获 flow 引用到局部变量，防止 setAzureClientId 在登录过程中
     * 替换 flow 导致 completeLogin 在新 flow 上执行或 old.shutdown 杀死轮询。
     */
    public CompletableFuture<Account> loginMicrosoftAsync(DeviceCode dc,
                                                          Consumer<String> onPending) {
        final MicrosoftAuthFlow currentFlow = this.flow;
        return currentFlow.pollForMsOAuthToken(dc, onPending)
                .thenApplyAsync(token -> {
                    try {
                        return currentFlow.completeLogin(token);
                    } catch (IOException e) {
                        throw new RuntimeException("微软登录失败: " + e.getMessage(), e);
                    }
                });
    }

    /** 每账号刷新锁，防止并发刷新同一 refresh_token 导致 token 失效 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> refreshLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 启动前刷新微软账号 MC token（需已持久化 refresh_token）。
     * 使用 per-account 锁防止并发刷新同一 refresh_token 导致 token 轮换竞态。
     */
    public Account refreshMicrosoftAccount(Account account) throws IOException {
        if (account == null || account.getType() != Account.AccountType.MICROSOFT) {
            throw new IOException("非微软账号");
        }
        if (account.getMsRefreshToken().isEmpty()) {
            throw new IOException("无 refresh_token，请重新登录微软账号");
        }
        Object lock = refreshLocks.computeIfAbsent(account.getUuid(), k -> new Object());
        synchronized (lock) {
            Account refreshed = flow.refreshLogin(account.getMsRefreshToken());
            // 若 refresh 后 UUID 变化（极少见），仍采用刷新结果；否则保留皮肤站字段等
            if (account.getUuid().equals(refreshed.getUuid())) {
                return account.withMicrosoftSession(
                        refreshed.getAccessToken(),
                        refreshed.getMsRefreshToken(),
                        refreshed.getExpiresAt());
            }
            return refreshed;
        }
    }

    /**
     * 校验微软账号当前 MC accessToken 是否仍有效（GET minecraft/profile）。
     * 网络失败时抛 IOException；401/403 返回 false。
     */
    public boolean isMicrosoftAccessTokenValid(Account account) throws IOException {
        if (account == null || account.getType() != Account.AccountType.MICROSOFT) {
            return false;
        }
        return flow.isMcAccessTokenValid(account.getAccessToken());
    }

    /**
     * 浏览器授权码流程登录（推荐方式）。
     * <p>
     * 打开系统浏览器让用户登录，授权后自动回调本地服务器完成登录。
     * 相比设备码流程，用户体验更佳（无需手动输入代码）。
     *
     * @param onStatus    状态回调（UI 显示进度）
     * @param openBrowser 接收授权 URL 并打开系统浏览器的回调
     * @return CompletableFuture<Account>
     */
    public CompletableFuture<Account> loginMicrosoftViaBrowser(Consumer<String> onStatus,
                                                                Consumer<String> openBrowser) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return flow.loginViaBrowser(onStatus, openBrowser);
            } catch (IOException e) {
                throw new RuntimeException("微软登录失败: " + e.getMessage(), e);
            }
        });
    }

    // ============ GitHub 登录 ============

    /**
     * 请求 GitHub 设备码（UI 层显示给用户）。
     */
    public DeviceCode requestGitHubDeviceCode() throws IOException {
        return githubFlow.requestDeviceCode();
    }

    /**
     * 异步等待用户完成 GitHub 授权，并获取用户信息，最终返回 Account。
     * 安全修复：捕获 githubFlow 引用到局部变量，防止登录过程中被替换。
     */
    public CompletableFuture<Account> loginGitHubAsync(DeviceCode dc, Consumer<String> onPending) {
        final GitHubAuthFlow currentFlow = this.githubFlow;
        return currentFlow.pollForAccessToken(dc, onPending)
                .thenApplyAsync(token -> {
                    try {
                        return currentFlow.completeLogin(token);
                    } catch (IOException e) {
                        throw new RuntimeException("GitHub登录失败: " + e.getMessage(), e);
                    }
                });
    }

    // ============ 皮肤站（Yggdrasil / authlib-injector）登录 ============

    /**
     * 皮肤站登录（同步调用，UI 层应在 IO 线程调用）。
     *
     * @param apiUrl   皮肤站 API 根地址或首页地址（会自动规范化）
     * @param username 用户名或邮箱
     * @param password 密码
     * @return 登录成功后的 Account
     * @throws IOException 网络错误或认证失败
     */
    public Account yggdrasilLogin(String apiUrl, String username, String password) throws IOException {
        return yggdrasilFlow.login(apiUrl, username, password);
    }

    /**
     * 验证皮肤站 accessToken 是否有效。
     */
    public boolean yggdrasilValidate(String apiUrl, String accessToken) {
        return yggdrasilValidate(apiUrl, accessToken, "");
    }

    public boolean yggdrasilValidate(String apiUrl, String accessToken, String clientToken) {
        return yggdrasilFlow.validate(apiUrl, accessToken, clientToken);
    }

    /**
     * 刷新皮肤站 accessToken。失败返回 null。
     */
    public String yggdrasilRefresh(String apiUrl, String accessToken) {
        return yggdrasilRefresh(apiUrl, accessToken, "");
    }

    public String yggdrasilRefresh(String apiUrl, String accessToken, String clientToken) {
        return yggdrasilFlow.refresh(apiUrl, accessToken, clientToken);
    }

    /**
     * 确保 authlib-injector.jar 存在并返回其路径。
     */
    public java.nio.file.Path ensureAuthlibInjectorJar(java.nio.file.Path jarPath) throws IOException {
        return authlibInjectorManager.ensureJar(jarPath);
    }

    /**
     * 预取 Yggdrasil API 元数据，返回 Base64 编码的 prefetched 字符串。失败返回 null。
     */
    public String prefetchYggdrasilApi(String apiUrl) {
        return authlibInjectorManager.prefetchYggdrasilApi(apiUrl);
    }

    // ============ 多账号持久化 ============

    /** 串行化 accounts.json 读写，避免 load/save 竞态与共享 .tmp 撕文件 */
    private final Object accountStoreLock = new Object();

    /**
     * 加载所有账号 + 当前选中账号。
     * 文件不存在时返回空 AccountStore。
     */
    public AccountStore loadStore(Path file) throws IOException {
        synchronized (accountStoreLock) {
            if (!Files.exists(file)) return new AccountStore(new ArrayList<>(), null);
            String raw;
            try {
                raw = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw e;
            }
            JsonObject root;
            try {
                root = JsonParser.parseString(raw).getAsJsonObject();
            } catch (Throwable t) {
                // 解析失败：保留原文件并复制备份，绝不 move 掉唯一副本（防并发写撕裂误杀）
                System.err.println("[AuthService] 账号文件解析失败（已保留原文件）: " + t.getMessage());
                try {
                    Path backup = file.resolveSibling(
                            file.getFileName() + ".corrupt." + System.currentTimeMillis());
                    Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    System.err.println("[AuthService] 可疑文件已复制至: " + backup);
                } catch (Throwable backupErr) {
                    System.err.println("[AuthService] 备份可疑文件失败: " + backupErr.getMessage());
                }
                return new AccountStore(new ArrayList<>(), null);
            }
            List<Account> accounts = new ArrayList<>();
            List<String> corrupted = new ArrayList<>();
            if (root.has("accounts")) {
                for (JsonElement e : root.getAsJsonArray("accounts")) {
                    JsonObject o = e.getAsJsonObject();
                    Account.AccountType accountType;
                    try {
                        accountType = Account.AccountType.valueOf(
                                o.has("type") && !o.get("type").isJsonNull() ? o.get("type").getAsString() : "OFFLINE");
                    } catch (IllegalArgumentException ex) {
                        accountType = Account.AccountType.OFFLINE;
                    }
                    String encRefresh = o.has("msRefreshToken") && !o.get("msRefreshToken").isJsonNull()
                            ? o.get("msRefreshToken").getAsString() : "";
                    String msRefresh = encRefresh.isEmpty() ? "" : TokenEncryptor.decrypt(encRefresh);
                    long expiresAt = 0L;
                    if (o.has("expiresAt") && !o.get("expiresAt").isJsonNull()) {
                        try { expiresAt = o.get("expiresAt").getAsLong(); } catch (Throwable ignored) {}
                    }
                    String accessToken = TokenEncryptor.decrypt(
                            o.has("accessToken") && !o.get("accessToken").isJsonNull() ? o.get("accessToken").getAsString() : "");
                    if (accessToken == null) {
                        // P2-1: 不再静默跳过，记录到 corruptedAccounts 供 UI 提示用户重新登录
                        String uname = o.has("username") && !o.get("username").isJsonNull()
                                ? o.get("username").getAsString() : "(unknown)";
                        System.err.println("[AuthService] 账号 accessToken 解密失败，已记录: " + uname);
                        corrupted.add(uname);
                        continue;
                    }
                    accounts.add(new Account(
                            o.has("username") && !o.get("username").isJsonNull() ? o.get("username").getAsString() : "",
                            o.has("uuid") && !o.get("uuid").isJsonNull() ? o.get("uuid").getAsString() : "",
                            accessToken,
                            accountType,
                            o.has("skinUrl") && !o.get("skinUrl").isJsonNull() ? o.get("skinUrl").getAsString() : "",
                            o.has("skinModel") && !o.get("skinModel").isJsonNull() ? o.get("skinModel").getAsString() : "classic",
                            o.has("xuid") && !o.get("xuid").isJsonNull() ? o.get("xuid").getAsString() : "",
                            o.has("authServerUrl") && !o.get("authServerUrl").isJsonNull() ? o.get("authServerUrl").getAsString() : "",
                            msRefresh != null ? msRefresh : "",
                            expiresAt,
                            o.has("clientToken") && !o.get("clientToken").isJsonNull()
                                    ? o.get("clientToken").getAsString() : ""
                    ));
                }
            }
            String selected = root.has("selected") && !root.get("selected").isJsonNull()
                    ? root.get("selected").getAsString() : null;
            return new AccountStore(accounts, selected, corrupted);
        }
    }

    /**
     * 保存账号集合 + 选中状态。
     */
    public void saveStore(AccountStore store, Path file) throws IOException {
        synchronized (accountStoreLock) {
            JsonObject root = new JsonObject();
            if (store.getSelectedUuid() != null) {
                root.addProperty("selected", store.getSelectedUuid());
            }
            JsonArray arr = new JsonArray();
            for (Account a : store.getAccounts()) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", a.getUuid());
                o.addProperty("username", a.getUsername());
                String plainToken = a.getAccessToken();
                String encToken = TokenEncryptor.encrypt(plainToken);
                if (plainToken != null && !plainToken.isEmpty()
                        && (encToken == null || encToken.isEmpty())) {
                    throw new IOException("无法加密账号 accessToken（" + a.getUsername()
                            + "），账号文件未保存。请检查磁盘权限或 ~/.pmcl/.keyfile");
                }
                o.addProperty("accessToken", encToken != null ? encToken : "");
                o.addProperty("type", a.getType().name());
                o.addProperty("skinUrl", a.getSkinUrl());
                o.addProperty("skinModel", a.getSkinModel());
                o.addProperty("xuid", a.getXuid());
                o.addProperty("authServerUrl", a.getAuthServerUrl());
                if (a.getClientToken() != null && !a.getClientToken().isEmpty()) {
                    o.addProperty("clientToken", a.getClientToken());
                }
                String plainRefresh = a.getMsRefreshToken();
                if (plainRefresh != null && !plainRefresh.isEmpty()) {
                    String encRefresh = TokenEncryptor.encrypt(plainRefresh);
                    if (encRefresh == null || encRefresh.isEmpty()) {
                        throw new IOException("无法加密账号 msRefreshToken（" + a.getUsername()
                                + "），账号文件未保存。请检查磁盘权限或 ~/.pmcl/.keyfile");
                    }
                    o.addProperty("msRefreshToken", encRefresh);
                }
                if (a.getExpiresAt() > 0) {
                    o.addProperty("expiresAt", a.getExpiresAt());
                }
                arr.add(o);
            }
            root.add("accounts", arr);
            Files.createDirectories(file.getParent());
            // 唯一 tmp，避免并发 save 争用 accounts.json.tmp
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + java.util.UUID.randomUUID());
            try {
                Files.writeString(tmp, gson.toJson(root), java.nio.charset.StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                tmp = null;
            } finally {
                if (tmp != null) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                }
            }
        }
    }

    // ============ 兼容旧 API（单账号文件） ============

    public void saveAccount(Account account, Path file) throws IOException {
        List<Account> list = new ArrayList<>();
        list.add(account);
        AccountStore store = new AccountStore(list, account.getUuid());
        saveStore(store, file);
    }

    public Account loadAccount(Path file) throws IOException {
        AccountStore store = loadStore(file);
        if (store.getSelectedUuid() == null) {
            return store.getAccounts().isEmpty() ? null : store.getAccounts().get(0);
        }
        Optional<Account> sel = store.getAccounts().stream()
                .filter(a -> a.getUuid().equals(store.getSelectedUuid()))
                .findFirst();
        return sel.orElse(null);
    }

    /** 关闭微软 / GitHub 登录流的调度器与连接池。 */
    public void shutdown() {
        try {
            if (flow != null) flow.shutdown();
        } catch (Throwable ignored) {}
        try {
            if (githubFlow != null) githubFlow.shutdown();
        } catch (Throwable ignored) {}
    }
}
