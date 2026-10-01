package com.pmcl.core.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseAnnouncementsTest {

    @Test
    void parsesReleaseNotesAndSkipsDrafts() {
        String json = """
                [
                  {
                    "tag_name": "v1.3.0c",
                    "name": "PMCL 1.3.0c",
                    "draft": false,
                    "prerelease": false,
                    "published_at": "2026-09-13T00:46:10Z",
                    "html_url": "https://github.com/PCML-Z/PCML/releases/tag/v1.3.0c",
                    "body": "## 更新\\n\\n- 修复[启动](https://github.com/PCML-Z/PCML/pull/11)"
                  },
                  {
                    "tag_name": "v9.9.9",
                    "name": "draft",
                    "draft": true,
                    "body": "不要显示"
                  }
                ]
                """;
        List<ReleaseAnnouncements.Item> items = ReleaseAnnouncements.parse(json, "PCML-Z/PCML");
        assertEquals(1, items.size());
        ReleaseAnnouncements.Item item = items.get(0);
        assertEquals("1.3.0c", item.getVersion());
        assertEquals("PMCL 1.3.0c", item.getTitle());
        assertEquals("2026-09-13", item.getPublishedAt());
        assertEquals("https://github.com/PCML-Z/PCML/releases/tag/v1.3.0c", item.getUrl());
        assertFalse(item.isPrerelease());
        assertTrue(item.getBody().contains("修复启动"));
        assertFalse(item.getBody().contains("https://"));
    }

    @Test
    void dropsUrlsOutsideTheReleasePage() {
        String json = """
                [{
                  "tag_name": "v1.0.0",
                  "html_url": "https://evil.example/PCML-Z/PCML/releases/tag/v1.0.0",
                  "body": ""
                }]
                """;
        List<ReleaseAnnouncements.Item> items = ReleaseAnnouncements.parse(json, "PCML-Z/PCML");
        assertEquals(1, items.size());
        assertEquals("", items.get(0).getUrl());
    }

    @Test
    void nonArrayPayloadIsEmpty() {
        assertTrue(ReleaseAnnouncements.parse("{\"message\":\"Not Found\"}", "PCML-Z/PCML").isEmpty());
        assertTrue(ReleaseAnnouncements.parse("", "PCML-Z/PCML").isEmpty());
    }
}
