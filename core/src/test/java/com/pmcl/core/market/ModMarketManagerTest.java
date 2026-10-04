package com.pmcl.core.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void contentFoldersAreOnlyShaderAndResourcePacks() {
        assertTrue(ModMarketManager.isContentFolder("shaderpacks"));
        assertTrue(ModMarketManager.isContentFolder("resourcepacks"));
        assertFalse(ModMarketManager.isContentFolder("mods"));
        assertFalse(ModMarketManager.isContentFolder("../shaderpacks"));
        assertFalse(ModMarketManager.isContentFolder("shaderpacks/../mods"));
        assertFalse(ModMarketManager.isContentFolder(null));
    }

    @Test
    void safeContentFileNameRejectsTraversal() {
        assertEquals("pack.zip", ModMarketManager.safeContentFileName("pack.zip"));
        assertEquals("pack.zip", ModMarketManager.safeContentFileName("dir/pack.zip"));
        assertEquals("pack.zip", ModMarketManager.safeContentFileName("../pack.zip"));
        assertEquals("b.zip", ModMarketManager.safeContentFileName("a\\b.zip"));
        assertNull(ModMarketManager.safeContentFileName(".."));
        assertNull(ModMarketManager.safeContentFileName("dir/.."));
        assertNull(ModMarketManager.safeContentFileName("."));
        assertNull(ModMarketManager.safeContentFileName(""));
        assertNull(ModMarketManager.safeContentFileName("foo..zip"));
        assertNull(ModMarketManager.safeContentFileName("foo\u0000.zip"));
    }

    @Test
    void modpackCacheNameAddsSuffixAndDropsDirectories() {
        assertEquals("abc-pack.mrpack", ModMarketManager.safeModpackCacheName("pack", "modrinth", "abc"));
        assertEquals("id-pack.zip", ModMarketManager.safeModpackCacheName("pack.zip", "curseforge", "id"));
        String escaped = ModMarketManager.safeModpackCacheName("../../x", "modrinth", "id");
        assertEquals("id-x.mrpack", escaped);
        assertFalse(escaped.contains("/"));
        assertFalse(escaped.contains(".."));
        assertTrue(ModMarketManager.safeModpackCacheName("no-ext", "curseforge", "").endsWith(".zip"));
    }

    private static ModProject project(String source, String id, long downloads, long modified) {
        return new ModProject(source, id, id, id, "", "", downloads, "", "")
                .dateModified(modified);
    }
}
