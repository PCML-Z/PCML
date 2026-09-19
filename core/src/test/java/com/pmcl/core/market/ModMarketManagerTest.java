package com.pmcl.core.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModMarketManagerTest {

    @Test
    void mergePagesDoesNotForceDownloadSortForRelevance() {
        ModProject cf = project("curseforge", "a", 10, 100);
        ModProject mr = project("modrinth", "b", 999, 50);
        MarketSearchPage p1 = new MarketSearchPage(List.of(cf), 100, 0, 20);
        MarketSearchPage p2 = new MarketSearchPage(List.of(mr), 80, 0, 20);
        MarketSearchPage merged = ModMarketManager.mergePages(
                List.of(p1, p2), new MarketSearchQuery().sort("relevance"));
        assertEquals(2, merged.getItems().size());
        assertEquals("a", merged.getItems().get(0).getId());
        assertEquals("b", merged.getItems().get(1).getId());
        assertEquals(100, merged.getTotal());
    }

    @Test
    void mergePagesSortsByDownloadsWhenRequested() {
        ModProject low = project("curseforge", "low", 10, 1);
        ModProject high = project("modrinth", "high", 50, 1);
        MarketSearchPage merged = ModMarketManager.mergePages(
                List.of(
                        new MarketSearchPage(List.of(low), 1, 0, 20),
                        new MarketSearchPage(List.of(high), 1, 0, 20)
                ),
                new MarketSearchQuery().sort("downloads"));
        assertEquals("high", merged.getItems().get(0).getId());
    }

    @Test
    void mergePagesSortsByDateWhenUpdated() {
        ModProject old = project("curseforge", "old", 1, 10);
        ModProject neu = project("modrinth", "new", 1, 99);
        MarketSearchPage merged = ModMarketManager.mergePages(
                List.of(
                        new MarketSearchPage(List.of(old), 1, 0, 20),
                        new MarketSearchPage(List.of(neu), 1, 0, 20)
                ),
                new MarketSearchQuery().sort("updated"));
        assertEquals("new", merged.getItems().get(0).getId());
    }

    private static ModProject project(String source, String id, long downloads, long modified) {
        return new ModProject(source, id, id, id, "", "", downloads, "", "")
                .dateModified(modified);
    }
}
