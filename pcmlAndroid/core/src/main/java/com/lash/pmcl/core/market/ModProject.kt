package com.lash.pmcl.core.market

/**
 * Mod 项目信息（搜索结果）。
 *
 * Android 版本：从 Java 移植，纯数据类，无平台依赖。
 */
data class ModProject(
    val source: String,
    val id: String,
    val slug: String,
    val name: String,
    val summary: String,
    val author: String,
    val downloadCount: Long,
    val iconUrl: String,
    val websiteUrl: String
)
