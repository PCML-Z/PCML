package com.lash.pmcl.core.auth

/**
 * 微软账号登录后的完整令牌集合。
 */
data class AuthTokens(
    val mcAccessToken: String,
    val msRefreshToken: String,
    val xboxUserHash: String,
    val expiresAt: Long
) {
    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt
}
