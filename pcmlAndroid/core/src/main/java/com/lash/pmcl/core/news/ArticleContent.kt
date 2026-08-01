package com.lash.pmcl.core.news

/**
 * 新闻文章正文内容（从 minecraft.net 文章页面提取）。
 *
 * 不可变值对象。
 */
data class ArticleContent(
    val title: String = "",
    val bodyHtml: String = "",
    val images: List<String> = emptyList(),
    val coverImage: String = "",
    val url: String = ""
)
