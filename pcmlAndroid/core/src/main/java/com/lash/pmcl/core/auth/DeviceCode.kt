package com.lash.pmcl.core.auth

/**
 * 设备码响应：用户在浏览器中打开 verificationUri 并输入 userCode 完成登录。
 */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int,
    val message: String
)
