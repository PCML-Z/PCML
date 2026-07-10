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

    public McVersion(String id, String type, String releaseTime, String url) {
        this.id = id;
        this.type = type;
        this.releaseTime = releaseTime;
        this.url = url;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getReleaseTime() { return releaseTime; }
    public String getUrl() { return url; }

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
