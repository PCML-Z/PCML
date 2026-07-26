package com.pmcl.core.version;

import java.util.Objects;

/**
 * 表示一个 Minecraft 版本元信息。
 */
public final class McVersion {

    private String id;
    private String type;       // release / snapshot / old_beta
    private String releaseTime;
    private String url;        // version manifest url
    /** Mojang version.json 的 SHA-1（来自 version_manifest）；空表示未知 */
    private String sha1;

    public McVersion(String id, String type, String releaseTime, String url) {
        this(id, type, releaseTime, url, "");
    }

    public McVersion(String id, String type, String releaseTime, String url, String sha1) {
        this.id = id;
        this.type = type;
        this.releaseTime = releaseTime;
        this.url = url;
        this.sha1 = sha1 != null ? sha1 : "";
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getReleaseTime() { return releaseTime; }
    public String getUrl() { return url; }
    public String getSha1() { return sha1; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof McVersion)) return false;
        return Objects.equals(id, ((McVersion) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "McVersion{" + id + " (" + type + ")}";
    }
}
