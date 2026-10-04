package com.pmcl.core.market;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModrinthClientTest {

    @Test
    void searchUsesIndexValues() {
        assertEquals("relevance", ModrinthClient.searchIndexParam("default", false));
        assertEquals("downloads", ModrinthClient.searchIndexParam("default", true));
        assertEquals("downloads", ModrinthClient.searchIndexParam("downloads", false));
        assertEquals("updated", ModrinthClient.searchIndexParam("updated", false));
        assertEquals("newest", ModrinthClient.searchIndexParam("newest", false));
        assertEquals("downloads", ModrinthClient.searchIndexParam("relevance", true));
        assertEquals("relevance", ModrinthClient.searchIndexParam("relevance", false));
    }

    @Test
    void galleryCoverPrefersFeaturedImage() {
        String raw = """
                {"featured_gallery":"https://cdn.modrinth.com/featured.png","gallery":["https://cdn.modrinth.com/other.png"]}
                """;
        assertEquals("https://cdn.modrinth.com/featured.png",
                ModrinthClient.galleryCover(JsonParser.parseString(raw).getAsJsonObject()));
        String objects = """
                {"gallery":[{"url":"https://cdn.modrinth.com/a.png","featured":false},{"url":"https://cdn.modrinth.com/b.png","featured":true}]}
                """;
        assertEquals("https://cdn.modrinth.com/b.png",
                ModrinthClient.galleryCover(JsonParser.parseString(objects).getAsJsonObject()));
    }

    @Test
    void forbricSearchesFabricForgeAndNeoforgeTogether() {
        assertEquals(List.of("fabric", "forge", "neoforge"), ModrinthClient.loaderCategories("forbric"));
        assertEquals(List.of("fabric"), ModrinthClient.loaderCategories("fabric"));
    }
}
