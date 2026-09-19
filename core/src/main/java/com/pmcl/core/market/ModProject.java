package com.pmcl.core.market;

import java.util.List;

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
    private List<String> categories = java.util.Collections.emptyList();
    private List<String> loaders = java.util.Collections.emptyList();
    private long dateModified;
    private String projectType = "mod";

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
    public java.util.List<String> getCategories() { return categories != null ? categories : java.util.Collections.emptyList(); }
    public java.util.List<String> getLoaders() { return loaders != null ? loaders : java.util.Collections.emptyList(); }
    public long getDateModified() { return dateModified; }
    public String getProjectType() { return projectType != null && !projectType.isBlank() ? projectType : "mod"; }

    public ModProject categories(java.util.List<String> categories) {
        this.categories = categories != null ? new java.util.ArrayList<>(categories) : java.util.Collections.emptyList();
        return this;
    }

    public ModProject loaders(java.util.List<String> loaders) {
        this.loaders = loaders != null ? new java.util.ArrayList<>(loaders) : java.util.Collections.emptyList();
        return this;
    }

    public ModProject dateModified(long dateModified) {
        this.dateModified = dateModified;
        return this;
    }

    public ModProject projectType(String projectType) {
        this.projectType = projectType != null ? projectType : "mod";
        return this;
    }
}
