package com.pmcl.core.market;

/**
 * 模组市场中的项目（CurseForge / Modrinth 通用模型）。
 */
public final class ModProject {

    private String source;       // "curseforge" | "modrinth"
    private String id;           // 项目 id（CurseForge 为数字字符串，Modrinth 为 slug/ID）
    private String slug;
    private String name;
    private String summary;
    private String author;
    private long downloadCount;
    private String iconUrl;
    private String websiteUrl;

    public ModProject(String source, String id, String slug, String name, String summary,
                      String author, long downloadCount, String iconUrl, String websiteUrl) {
        this.source = source;
        this.id = id;
        this.slug = slug;
        this.name = name;
        this.summary = summary;
        this.author = author;
        this.downloadCount = downloadCount;
        this.iconUrl = iconUrl;
        this.websiteUrl = websiteUrl;
    }

    public String getSource() { return source; }
    public String getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getSummary() { return summary; }
    public String getAuthor() { return author; }
    public long getDownloadCount() { return downloadCount; }
    public String getIconUrl() { return iconUrl; }
    public String getWebsiteUrl() { return websiteUrl; }
}
