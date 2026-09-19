package com.pmcl.core.market;

/**
 * 市场搜索条件。source 为空表示聚合；projectType 为空表示不限类型。
 */
public final class MarketSearchQuery {
    private String query = "";
    private String gameVersion;
    private String loader;
    private String sort;
    private String projectType;
    private String source;
    private int offset;
    private int limit = 20;

    public String getQuery() { return query != null ? query : ""; }
    public String getGameVersion() { return gameVersion; }
    public String getLoader() { return loader; }
    public String getSort() { return sort; }
    public String getProjectType() { return projectType; }
    public String getSource() { return source; }
    public int getOffset() { return Math.max(0, offset); }
    public int getLimit() { return limit > 0 ? Math.min(limit, 50) : 20; }

    public MarketSearchQuery query(String query) {
        this.query = query != null ? query : "";
        return this;
    }

    public MarketSearchQuery gameVersion(String gameVersion) {
        this.gameVersion = gameVersion;
        return this;
    }

    public MarketSearchQuery loader(String loader) {
        this.loader = loader;
        return this;
    }

    public MarketSearchQuery sort(String sort) {
        this.sort = sort;
        return this;
    }

    public MarketSearchQuery projectType(String projectType) {
        this.projectType = projectType;
        return this;
    }

    public MarketSearchQuery source(String source) {
        this.source = source;
        return this;
    }

    public MarketSearchQuery offset(int offset) {
        this.offset = offset;
        return this;
    }

    public MarketSearchQuery limit(int limit) {
        this.limit = limit;
        return this;
    }
}
