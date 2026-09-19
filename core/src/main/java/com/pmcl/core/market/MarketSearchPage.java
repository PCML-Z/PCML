package com.pmcl.core.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MarketSearchPage {
    private final List<ModProject> items;
    private final int total;
    private final int offset;
    private final int limit;

    public MarketSearchPage(List<ModProject> items, int total, int offset, int limit) {
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        this.total = Math.max(0, total);
        this.offset = Math.max(0, offset);
        this.limit = limit > 0 ? limit : 20;
    }

    public List<ModProject> getItems() { return Collections.unmodifiableList(items); }
    public int getTotal() { return total; }
    public int getOffset() { return offset; }
    public int getLimit() { return limit; }

    public int getPageCount() {
        if (limit <= 0) return 1;
        return Math.max(1, (int) Math.ceil(total / (double) limit));
    }

    public int getPageIndex() {
        if (limit <= 0) return 0;
        return offset / limit;
    }
}
