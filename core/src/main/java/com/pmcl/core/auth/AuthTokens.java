package com.pmcl.core.auth;

/**
 * 微软账号登录后的完整令牌集合，可序列化持久化。
 */
public final class AuthTokens {

    private final String mcAccessToken;
    private final String msRefreshToken;
    private final String xboxUserHash;
    private final long expiresAt;

    public AuthTokens(String mcAccessToken, String msRefreshToken, String xboxUserHash, long expiresAt) {
        this.mcAccessToken = mcAccessToken;
        this.msRefreshToken = msRefreshToken;
        this.xboxUserHash = xboxUserHash;
        this.expiresAt = expiresAt;
    }

    public String getMcAccessToken() { return mcAccessToken; }
    public String getMsRefreshToken() { return msRefreshToken; }
    public String getXboxUserHash() { return xboxUserHash; }
    public long getExpiresAt() { return expiresAt; }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }
}
