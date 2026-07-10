package com.pmcl.core.news;

/**
 * Minecraft 新闻条目（来自 Minecraft.net RSS）。
 * <p>
 * 不可变值对象，字段全部通过构造器填充。
 */
public final class NewsItem {

    private String title;
    private String link;
    private String description;   // 摘要（含 HTML，UI 层按需清理）
    private String pubDate;       // 原始日期字符串（RFC-822）
    private String category;      // 分类/标签，可为空
    private String imageUrl;      // 封面图，可为空

    public NewsItem(String title, String link, String description,
                    String pubDate, String category, String imageUrl) {
        this.title = title == null ? "" : title;
        this.link = link == null ? "" : link;
        this.description = description == null ? "" : description;
        this.pubDate = pubDate == null ? "" : pubDate;
        this.category = category == null ? "" : category;
        this.imageUrl = imageUrl == null ? "" : imageUrl;
    }

    public String getTitle() { return title; }
    public String getLink() { return link; }
    public String getDescription() { return description; }
    public String getPubDate() { return pubDate; }
    public String getCategory() { return category; }
    public String getImageUrl() { return imageUrl; }
}
