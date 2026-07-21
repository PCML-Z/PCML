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

    public Account(String username, String uuid, String accessToken, AccountType type) {
        this(username, uuid, accessToken, type, "", "classic", "");
    }

    public Account(String username, String uuid, String accessToken, AccountType type,
                   String skinUrl, String skinModel) {
        this(username, uuid, accessToken, type, skinUrl, skinModel, "");
    }

    public Account(String username, String uuid, String accessToken, AccountType type,
                   String skinUrl, String skinModel, String xuid) {
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.type = type;
        this.skinUrl = skinUrl != null ? skinUrl : "";
        this.skinModel = skinModel != null ? skinModel : "classic";
        this.xuid = xuid != null ? xuid : "";
    }

    public String getUsername() { return username; }
    public String getUuid() { return uuid; }
    public String getAccessToken() { return accessToken; }
    public AccountType getType() { return type; }
    public String getSkinUrl() { return skinUrl; }
    public String getSkinModel() { return skinModel; }
    /** 返回 Xbox Live userHash（uhs），仅微软账号有效，离线/GitHub 账号返回空字符串。 */
    public String getXuid() { return xuid; }

    /**
     * 返回头像 URL。
     * 微软账号用 Crafatar 通过 UUID 获取在线皮肤；
     * GitHub 账号用 skinUrl 字段存储的 GitHub 头像 URL；
     * 离线账号若无自定义皮肤则返回空。
     */
    public String getAvatarUrl() {
        if (type == AccountType.MICROSOFT) {
            return "https://crafatar.com/avatars/" + uuid + "?size=64&overlay";
        }
        return skinUrl;
    }

    /**
     * 返回全身渲染 URL（仅微软账号有效）。
     */
    public String getBodyRenderUrl() {
        if (type == AccountType.MICROSOFT) {
            return "https://crafatar.com/renders/body/" + uuid + "?size=128";
        }
        return skinUrl;
    }

    public enum AccountType {
        OFFLINE,
        MICROSOFT,
        GITHUB
    }
}
