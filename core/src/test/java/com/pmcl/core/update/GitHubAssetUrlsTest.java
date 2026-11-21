package com.pmcl.core.update;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubAssetUrlsTest {

    @Test
    void releaseDownloadUsesApiAssetUrl() {
        var release = JsonParser.parseString("""
                {"assets":[{
                  "name":"pmcl-hotupdate-macos-arm64.json",
                  "browser_download_url":"https://github.com/PCML-Z/PCML/releases/download/v2.10.4/pmcl-hotupdate-macos-arm64.json",
                  "url":"https://api.github.com/repos/PCML-Z/PCML/releases/assets/42"
                },{
                  "name":"hot-macos-arm64-app_pmcl.jar",
                  "browser_download_url":"https://github.com/PCML-Z/PCML/releases/download/v2.10.4/hot-macos-arm64-app_pmcl.jar",
                  "url":"https://api.github.com/repos/PCML-Z/PCML/releases/assets/43"
                }]}
                """).getAsJsonObject();
        Map<String, String> index = GitHubAssetUrls.index(release);
        assertEquals(
                "https://api.github.com/repos/PCML-Z/PCML/releases/assets/42",
                GitHubAssetUrls.resolve(
                        "https://github.com/PCML-Z/PCML/releases/download/v2.10.4/pmcl-hotupdate-macos-arm64.json",
                        index));
        assertEquals(
                "https://api.github.com/repos/PCML-Z/PCML/releases/assets/43",
                GitHubAssetUrls.resolve(
                        "https://github.com/PCML-Z/PCML/releases/download/v2.10.4/hot-macos-arm64-app_pmcl.jar",
                        index));
    }

    @Test
    void unknownUrlStaysPut() {
        assertEquals("https://example.com/a", GitHubAssetUrls.resolve("https://example.com/a", Map.of()));
        assertFalse(GitHubAssetUrls.isApiAsset("https://github.com/PCML-Z/PCML/releases/download/v2.10.4/a.json"));
        assertFalse(GitHubAssetUrls.isApiAsset("http://api.github.com/repos/PCML-Z/PCML/releases/assets/1"));
        assertTrue(GitHubAssetUrls.allowedDownloadHost(
                "https://release-assets.githubusercontent.com/github-production-release-asset/a"));
        assertFalse(GitHubAssetUrls.allowedDownloadHost("https://example.com/a"));
    }
}
