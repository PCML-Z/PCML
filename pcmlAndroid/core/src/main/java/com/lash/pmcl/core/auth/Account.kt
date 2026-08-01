package com.lash.pmcl.core.auth

/**
 * 账户信息 — Android 版。
 *
 * 桌面版字段全集保留（用户名/UUID/accessToken/类型/皮肤/XUID/皮肤站/authServer/
 * msRefreshToken/expiresAt/clientToken），因为这些字段在 Mojang/Yggdrasil 协议层
 * 是通用的，不依赖桌面平台。
 *
 * 唯一变化：移除桌面 UI 专用的 getAvatarUrl()/getBodyRenderUrl() 中对 crafatar.com
 * 的硬编码（Android UI 应自行决定头像渲染策略，不放在 core 中）。
 */
data class Account(
    val username: String,
    val uuid: String,
    val accessToken: String,
    val type: AccountType,
    val skinUrl: String = "",
    val skinModel: String = "classic",
    /** Xbox Live userHash（uhs），微软账号用于 auth_xuid 启动参数 */
    val xuid: String = "",
    /** 皮肤站 API 地址（YGGDRASIL 账号专用），空表示非皮肤站 */
    val authServerUrl: String = "",
    /** 微软 OAuth refresh_token；空表示旧账号无法自动刷新 */
    val msRefreshToken: String = "",
    /** MC accessToken 过期时间（epoch ms）；0 表示未知 */
    val expiresAt: Long = 0L,
    /** Yggdrasil clientToken；皮肤站会话绑定用 */
    val clientToken: String = ""
) {

    /**
     * 微软账号是否应在启动前刷新：已过期或 5 分钟内过期，且持有 refresh_token。
     * expiresAt<=0 的旧账号也视为可能过期，主动刷新验证有效性。
     */
    fun needsMicrosoftRefresh(): Boolean {
        if (type != AccountType.MICROSOFT) return false
        if (msRefreshToken.isEmpty()) return false
        if (expiresAt <= 0L) return true
        return System.currentTimeMillis() >= expiresAt - 5 * 60_000L
    }

    /** 用新的 MC 会话字段构造副本（保留身份与皮肤元数据）。 */
    fun withMicrosoftSession(
        newAccessToken: String,
        newRefreshToken: String?,
        newExpiresAt: Long
    ): Account = copy(
        accessToken = newAccessToken,
        msRefreshToken = newRefreshToken ?: msRefreshToken,
        expiresAt = newExpiresAt
    )

    /** 用新的 Yggdrasil accessToken 构造副本（保留 clientToken）。 */
    fun withAccessToken(newAccessToken: String): Account =
        copy(accessToken = newAccessToken)

    enum class AccountType {
        OFFLINE,
        MICROSOFT,
        GITHUB,
        YGGDRASIL
    }
}
