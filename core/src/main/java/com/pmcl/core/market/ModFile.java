package com.pmcl.core.market;

import java.util.List;

/**
 * 模组项目的某个版本文件。
 */
public final class ModFile {

    private String source;          // "curseforge" | "modrinth"
    private String projectId;
    private String fileId;          // 文件 id
    private String fileName;
    private long fileSize;
    private String downloadUrl;
    private List<String> gameVersions;   // 兼容的 MC 版本，如 ["1.20.4", "1.20.3"]
    private List<String> loaders;        // 兼容的加载器，如 ["fabric", "quilt"]
    private String releaseType;          // release / beta / alpha
    private List<String> dependencies;   // 依赖的 project ID 列表（来自 Modrinth API），可为空
    private String sha1 = "";
    private String sha512 = "";

    public ModFile(String source, String projectId, String fileId, String fileName,
                   long fileSize, String downloadUrl, List<String> gameVersions,
                   List<String> loaders, String releaseType) {
        this(source, projectId, fileId, fileName, fileSize, downloadUrl,
                gameVersions, loaders, releaseType, java.util.Collections.emptyList());
    }

    public ModFile(String source, String projectId, String fileId, String fileName,
                   long fileSize, String downloadUrl, List<String> gameVersions,
                   List<String> loaders, String releaseType, List<String> dependencies) {
        this.source = source;
        this.projectId = projectId;
        this.fileId = fileId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.downloadUrl = downloadUrl;
        this.gameVersions = gameVersions != null ? new java.util.ArrayList<>(gameVersions) : new java.util.ArrayList<>();
        this.loaders = loaders;
        this.releaseType = releaseType;
        this.dependencies = dependencies != null ? dependencies : java.util.Collections.emptyList();
    }

    /** 链式设置完整性哈希（API 解析后调用）。 */
    public ModFile hashes(String sha1, String sha512) {
        this.sha1 = sha1 != null ? sha1 : "";
        this.sha512 = sha512 != null ? sha512 : "";
        return this;
    }

    public String getSource() { return source; }
    public String getProjectId() { return projectId; }
    public String getFileId() { return fileId; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public String getDownloadUrl() { return downloadUrl; }
    public java.util.List<String> getGameVersions() { return new java.util.ArrayList<>(gameVersions); }
    public List<String> getLoaders() { return loaders; }
    public String getReleaseType() { return releaseType; }
    /** 返回依赖的 project ID 列表（来自 Modrinth API 的 dependencies 字段），无依赖时返回空列表 */
    public List<String> getDependencies() { return dependencies; }
    public String getSha1() { return sha1; }
    public String getSha512() { return sha512; }
}
