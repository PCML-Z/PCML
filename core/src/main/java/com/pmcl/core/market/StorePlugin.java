package com.pmcl.core.market;

/**
 * lash.org.cn 插件商店里的一条插件。
 */
public final class StorePlugin {

    public final String id;
    public final String name;
    public final String version;
    public final String author;
    public final String summary;
    public final String description;
    public final long downloads;
    public final String updatedAt;
    /** 只保留 lash.org.cn 上的图标，其它地址为空。 */
    public final String iconUrl;
    public final String homepage;
    /** jar 或 ppk */
    public final String packageType;
    public final String downloadUrl;
    public final String sha256;
    public final long size;

    public StorePlugin(String id,
                       String name,
                       String version,
                       String author,
                       String summary,
                       String description,
                       long downloads,
                       String updatedAt,
                       String iconUrl,
                       String homepage,
                       String packageType,
                       String downloadUrl,
                       String sha256,
                       long size) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.summary = summary;
        this.description = description;
        this.downloads = downloads;
        this.updatedAt = updatedAt == null ? "" : updatedAt;
        this.iconUrl = iconUrl == null ? "" : iconUrl;
        this.homepage = homepage == null ? "" : homepage;
        this.packageType = packageType;
        this.downloadUrl = downloadUrl;
        this.sha256 = sha256;
        this.size = size;
    }
}
