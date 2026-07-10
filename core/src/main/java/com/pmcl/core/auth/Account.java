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

    public Account(String username, String uuid, String accessToken, AccountType type) {
        this(username, uuid, accessToken, type, "", "classic");
    }

    public Account(String username, String uuid, String accessToken, AccountType type,
                   String skinUrl, String skinModel) {
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.type = type;
        this.skinUrl = skinUrl != null ? skinUrl : "";
        this.skinModel = skinModel != null ? skinModel : "classic";
    }

    public String getUsername() { return username; }
    public String getUuid() { return uuid; }
    public String getAccessToken() { return accessToken; }
    public AccountType getType() { return type; }
    public String getSkinUrl() { return skinUrl; }
    public String getSkinModel() { return skinModel; }

    /**
     * 返回 Crafatar 头像 URL（2D 头部图）。
     * 微软账号用 UUID 获取在线皮肤；离线账号若无自定义皮肤则返回空。
     */
    public String getAvatarUrl() {
        if (type == AccountType.MICROSOFT) {
            return "https://crafatar.com/avatars/" + uuid + "?size=64&overlay";
        }
        return skinUrl;
    }

    /**
     * 返回 Crafatar 全身渲染 URL。
     */
    public String getBodyRenderUrl() {
        if (type == AccountType.MICROSOFT) {
            return "https://crafatar.com/renders/body/" + uuid + "?size=128";
        }
        return skinUrl;
    }

    public enum AccountType {
        OFFLINE,
        MICROSOFT
    }
}
