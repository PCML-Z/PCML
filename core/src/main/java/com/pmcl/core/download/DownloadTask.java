package com.pmcl.core.download;

/**
 * 单个下载任务描述。
 */
public final class DownloadTask {

    private final String url;
    private final String sha1;
    private final long size;
    private final String relativePath;

    public DownloadTask(String url, String sha1, long size, String relativePath) {
        this.url = url;
        this.sha1 = sha1;
        this.size = size;
        this.relativePath = relativePath;
    }

    public String getUrl() { return url; }
    public String getSha1() { return sha1; }
    public long getSize() { return size; }
    public String getRelativePath() { return relativePath; }
}
