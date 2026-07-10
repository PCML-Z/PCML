package com.pmcl.core.news;

import java.util.Collections;
import java.util.List;

/**
 * 新闻文章正文内容（从 minecraft.net 文章页面提取）。
 * <p>
 * 不可变值对象。
 */
public final class ArticleContent {

    private String title;         // 文章标题
    private String bodyHtml;      // 正文 HTML 片段（含 <p>、<h2>、<img> 等）
    private List<String> images;  // 文章内图片 URL 列表
    private String coverImage;    // 封面图 URL（可为空）
    private String url;           // 文章原文链接

    public ArticleContent(String title, String bodyHtml, List<String> images,
                          String coverImage, String url) {
        this.title = title == null ? "" : title;
        this.bodyHtml = bodyHtml == null ? "" : bodyHtml;
        this.images = images == null ? Collections.emptyList() : images;
        this.coverImage = coverImage == null ? "" : coverImage;
        this.url = url == null ? "" : url;
    }

    public String getTitle() { return title; }
    public String getBodyHtml() { return bodyHtml; }
    public List<String> getImages() { return images; }
    public String getCoverImage() { return coverImage; }
    public String getUrl() { return url; }
}
