package com.lash.pmcl.core.news

/**
 * Minecraft 新闻条目（来自 Minecraft.net RSS）。
 *
 * 不可变值对象，字段全部通过构造器填充。
 * imageUrl 为可变字段，用于 RSS 无图时异步回填封面图。
 */
data class NewsItem(
    val title: String = "",
    val link: String = "",
    val description: String = "",
    val pubDate: String = "",
    val category: String = "",
    var imageUrl: String = ""
)
