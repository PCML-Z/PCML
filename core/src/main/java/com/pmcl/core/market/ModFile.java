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

    public ModFile(String source, String projectId, String fileId, String fileName,
                   long fileSize, String downloadUrl, List<String> gameVersions,
                   List<String> loaders, String releaseType) {
        this.source = source;
        this.projectId = projectId;
        this.fileId = fileId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.downloadUrl = downloadUrl;
        this.gameVersions = gameVersions;
        this.loaders = loaders;
        this.releaseType = releaseType;
    }

    public String getSource() { return source; }
    public String getProjectId() { return projectId; }
    public String getFileId() { return fileId; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public String getDownloadUrl() { return downloadUrl; }
    public List<String> getGameVersions() { return gameVersions; }
    public List<String> getLoaders() { return loaders; }
    public String getReleaseType() { return releaseType; }
}
