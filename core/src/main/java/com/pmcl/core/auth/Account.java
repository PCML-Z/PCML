package com.pmcl.core.auth;

/**
 * 账户信息。
 */
public final class Account {

    private final String username;
    private final String uuid;
    private final String accessToken;
    private final AccountType type;
    private final String skinUrl;   // 皮肤纹理 URL（微软账号来自 Mojang API，离线账号可自定义）
    private final String skinModel; // "classic" 或 "slim"
    private final String xuid;      // Xbox Live userHash（uhs），微软账号用于 auth_xuid 启动参数
    private final String authServerUrl; // 皮肤站 API 地址（YGGDRASIL 账号专用），空表示非皮肤站
    /** 微软 OAuth refresh_token；空表示旧账号无法自动刷新 */
    private final String msRefreshToken;
    /** MC accessToken 过期时间（epoch ms）；0 表示未知 */
    private final long expiresAt;
    /** Yggdrasil clientToken；皮肤站会话绑定用，空表示旧账号需重新登录 */
    private final String clientToken;

    public Account(String username, String uuid, String accessToken, AccountType type) {
        this(username, uuid, accessToken, type, "", "classic", "");
    }

    public Account(String username, String uuid, String accessToken, AccountType type,
                   String skinUrl, String skinModel) {
        this(username, uuid, accessToken, type, skinUrl, skinModel, "");
    }

    public Account(String username, String uuid, String accessToken, AccountType type,
                   String skinUrl, String skinModel, String xuid) {
        this(username, uuid, accessToken, type, skinUrl, skinModel, xuid, "");
    }

    public Account(String username, String uuid, String accessToken, AccountType type,
                   String skinUrl, String skinModel, String xuid, String authServerUrl) {
        this(username, uuid, accessToken, type, skinUrl, skinModel, xuid, authServerUrl, "", 0L);
    }

    public Account(String username, String uuid, String accessToken, AccountType type,
                   String skinUrl, String skinModel, String xuid, String authServerUrl,
                   String msRefreshToken, long expiresAt) {
        this(username, uuid, accessToken, type, skinUrl, skinModel, xuid, authServerUrl,
                msRefreshToken, expiresAt, "");
    }

    public Account(String username, String uuid, String accessToken, AccountType type,
                   String skinUrl, String skinModel, String xuid, String authServerUrl,
                   String msRefreshToken, long expiresAt, String clientToken) {
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.type = type;
        this.skinUrl = skinUrl != null ? skinUrl : "";
        this.skinModel = skinModel != null ? skinModel : "classic";
        this.xuid = xuid != null ? xuid : "";
        this.authServerUrl = authServerUrl != null ? authServerUrl : "";
        this.msRefreshToken = msRefreshToken != null ? msRefreshToken : "";
        this.expiresAt = expiresAt;
        this.clientToken = clientToken != null ? clientToken : "";
    }

    public String getUsername() { return username; }
    public String getUuid() { return uuid; }
    public String getAccessToken() { return accessToken; }
    public AccountType getType() { return type; }
    public String getSkinUrl() { return skinUrl; }
    public String getSkinModel() { return skinModel; }
    /** 返回 Xbox Live userHash（uhs），仅微软账号有效，离线/GitHub 账号返回空字符串。 */
    public String getXuid() { return xuid; }
    /** 返回皮肤站 API 地址（YGGDRASIL 账号专用），非皮肤站账号返回空字符串。 */
    public String getAuthServerUrl() { return authServerUrl; }
    public String getMsRefreshToken() { return msRefreshToken; }
    public long getExpiresAt() { return expiresAt; }
    /** Yggdrasil clientToken（皮肤站会话绑定）。 */
    public String getClientToken() { return clientToken; }

    /**
     * 微软账号是否应在启动前刷新：已过期或 5 分钟内过期，且持有 refresh_token。
     * <p>
     * P1-3: expiresAt<=0 的旧账号（早期版本持久化、completeLogin 时 expiresIn 解析为 0）
     * 也应主动刷新，避免过期 token 传给游戏导致多人游戏被踢、皮肤加载失败。
     * 仅当无 refresh_token 时才真正无法刷新。
     */
    public boolean needsMicrosoftRefresh() {
        if (type != AccountType.MICROSOFT) return false;
        if (msRefreshToken.isEmpty()) return false;
        // P1-3: 旧账号 expiresAt<=0 视为可能过期，主动刷新验证有效性
        if (expiresAt <= 0) return true;
        return System.currentTimeMillis() >= expiresAt - 5 * 60_000L;
    }

    /** 用新的 MC 会话字段构造副本（保留身份与皮肤元数据）。 */
    public Account withMicrosoftSession(String newAccessToken, String newRefreshToken, long newExpiresAt) {
        return new Account(username, uuid, newAccessToken, type, skinUrl, skinModel, xuid, authServerUrl,
                newRefreshToken != null ? newRefreshToken : msRefreshToken, newExpiresAt, clientToken);
    }

    /** 用新的 Yggdrasil accessToken 构造副本（保留 clientToken）。 */
    public Account withAccessToken(String newAccessToken) {
        return new Account(username, uuid, newAccessToken, type, skinUrl, skinModel, xuid, authServerUrl,
                msRefreshToken, expiresAt, clientToken);
    }

    /**
     * 返回头像 URL。
     * 微软账号用 Crafatar 通过 UUID 获取在线皮肤；
     * YGGDRASIL 账号用皮肤站的 API 渲染头像；
     * GitHub 账号用 skinUrl 字段存储的 GitHub 头像 URL；
     * 离线账号若无自定义皮肤则返回空。
     */
    public String getAvatarUrl() {
        if (type == AccountType.MICROSOFT) {
            return "https://crafatar.com/avatars/" + uuid + "?size=64&overlay";
        }
        if (type == AccountType.YGGDRASIL && !authServerUrl.isEmpty()) {
            // 皮肤站头像通过其自定义 API 渲染（去除末尾斜杠后拼接 /avatar/<uuid>）
            String base = authServerUrl.endsWith("/") ? authServerUrl.substring(0, authServerUrl.length() - 1) : authServerUrl;
            return base + "/avatar/" + uuid + "?size=64";
        }
        return skinUrl;
    }

    /**
     * 返回全身渲染 URL（仅微软账号和 YGGDRASIL 账号有效）。
     */
    public String getBodyRenderUrl() {
        if (type == AccountType.MICROSOFT) {
            return "https://crafatar.com/renders/body/" + uuid + "?size=128";
        }
        if (type == AccountType.YGGDRASIL && !authServerUrl.isEmpty()) {
            String base = authServerUrl.endsWith("/") ? authServerUrl.substring(0, authServerUrl.length() - 1) : authServerUrl;
            return base + "/renders/body/" + uuid + "?size=128";
        }
        return skinUrl;
    }

    public enum AccountType {
        OFFLINE,
        MICROSOFT,
        GITHUB,
        YGGDRASIL
    }
}
