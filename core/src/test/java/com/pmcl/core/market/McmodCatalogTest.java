package com.pmcl.core.market;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McmodCatalogTest {

    @Test
    void searchHtmlKeepsChineseModTitles() {
        String html = """
                <p>找到约 2 条结果，共约 1 页。</p>
                <div class="search-result-list">
                <div class="result-item"><div class="head">
                <a href="https://www.mcmod.cn/class/332.html"><em>植物</em>魔法 (Botania)</a>
                </div><div class="body">[h1=简介]魔法植物。</div>
                <div class="foot"><a href="https://www.mcmod.cn/class/332.html">www.mcmod.cn/class/332.html</a></div>
                </div>
                <div class="result-item"><div class="head">
                <a href="https://www.mcmod.cn/modpack/9.html">空岛 (Sky)</a>
                </div><div class="body">一个整合包</div></div>
                </div>
                """;
        McmodCatalog.SearchPage page = McmodCatalog.parseSearch(html);
        assertEquals(2, page.getTotalHits());
        assertEquals(1, page.getPageCount());
        assertEquals(2, page.getEntries().size());
        assertEquals("mod", page.getEntries().get(0).getKind());
        assertEquals("332", page.getEntries().get(0).getId());
        assertEquals("植物魔法 (Botania)", page.getEntries().get(0).getName());
        assertEquals("魔法植物。", page.getEntries().get(0).getSummary());
        assertEquals("modpack", page.getEntries().get(1).getKind());
        assertEquals("9", page.getEntries().get(1).getId());
    }

    @Test
    void linksDecodeModrinthAndCurseForgeOnly() {
        String modrinth = token("https://modrinth.com/mod/botania");
        String forge = token("https://www.curseforge.com/minecraft/mc-mods/botania");
        String fabric = token("https://www.curseforge.com/minecraft/mc-mods/botania-fabric/files");
        String github = token("https://github.com/Vazkii/Botania");
        String html = """
                <a data-original-title="Modrinth" href="//link.mcmod.cn/target/%s"></a>
                <a data-original-title="CurseForge: Forge" href="//link.mcmod.cn/target/%s"></a>
                <a data-original-title="CurseForge: Fabric" href="//link.mcmod.cn/target/%s"></a>
                <a data-original-title="GitHub" href="//link.mcmod.cn/target/%s"></a>
                <a href="https://evil.example/modrinth.com/mod/nope"></a>
                """.formatted(modrinth, forge, fabric, github);
        List<McmodCatalog.HostLink> links = McmodCatalog.parseLinks(html);
        assertEquals(4, links.size());
        assertEquals("modrinth", links.get(0).getSource());
        assertEquals("botania", links.get(0).getSlug());
        assertEquals("mod", links.get(0).getProjectType());
        assertEquals("Modrinth", links.get(0).getLabel());
        assertTrue(links.get(0).opensInMarket());
        assertEquals("curseforge", links.get(1).getSource());
        assertEquals("botania", links.get(1).getSlug());
        assertEquals("CurseForge: Forge", links.get(1).getLabel());
        assertEquals("botania-fabric", links.get(2).getSlug());
        assertNull(links.get(1).getCurseforgeId());
        assertEquals("github", links.get(3).getSource());
        assertEquals("https://github.com/Vazkii/Botania", links.get(3).getUrl());
        assertTrue(!links.get(3).opensInMarket());
    }

    @Test
    void relatedLinksKeepGithubMavenAndOfficialSite() {
        String github = token("https://github.com/Vazkii/Botania");
        String maven = token("https://maven.blamejared.com/vazkii/botania/Botania/");
        String site = token("http://botaniamod.net/");
        String wiki = token("https://botaniamod.net/lexicon.php");
        String forum = token("https://forum.violetmoon.org/t/botania");
        String outside = token("https://github.com/other/body");
        String html = """
                <div class="common-link-frame"><ul>
                <a data-original-title="GitHub" href="//link.mcmod.cn/target/%s"></a>
                <a data-original-title="Maven: Forge 快照下载地址" href="//link.mcmod.cn/target/%s"></a>
                <a data-original-title="官方: 官网。" href="//link.mcmod.cn/target/%s"></a>
                <a data-original-title="WIKI: 在线植物魔法辞典" href="//link.mcmod.cn/target/%s"></a>
                <a data-original-title="官方: 官方论坛。" href="//link.mcmod.cn/target/%s"></a>
                </ul></div>
                <a data-original-title="GitHub" href="//link.mcmod.cn/target/%s"></a>
                """.formatted(github, maven, site, wiki, forum, outside);
        List<McmodCatalog.HostLink> links = McmodCatalog.parseLinks(html);
        assertEquals(3, links.size());
        assertEquals("github", links.get(0).getSource());
        java.util.Set<String> urls = new java.util.HashSet<>();
        for (McmodCatalog.HostLink link : links) urls.add(link.getUrl());
        assertTrue(urls.contains("https://github.com/Vazkii/Botania"));
        assertTrue(urls.contains("https://maven.blamejared.com/vazkii/botania/Botania/"));
        assertTrue(urls.contains("https://botaniamod.net/"));
        assertTrue(urls.stream().noneMatch(url -> url != null && (url.contains("lexicon") || url.contains("forum") || url.contains("other/body"))));
        assertNull(McmodCatalog.parseHostUrl("https://github.com/Vazkii/../Botania", "GitHub"));
        assertNull(McmodCatalog.parseHostUrl("https://user@github.com/Vazkii/Botania", "GitHub"));
        assertTrue(!McmodCatalog.isManualBrowseUrl("https://127.0.0.1/file.jar"));
        assertTrue(!McmodCatalog.isManualBrowseUrl("file:///tmp/mod.jar"));
        assertTrue(McmodCatalog.isManualBrowseUrl("https://github.com/Vazkii/Botania"));
    }

    @Test
    void numericCurseForgeProjectAndRejectedSlugs() {
        McmodCatalog.HostLink project = McmodCatalog.parseHostUrl(
                "https://www.curseforge.com/projects/238222", "CurseForge");
        assertEquals("238222", project.getCurseforgeId());
        assertEquals("curseforge", project.getSource());
        assertNull(McmodCatalog.parseHostUrl("https://modrinth.com/mod/../secret", ""));
        assertNull(McmodCatalog.parseHostUrl("https://user@modrinth.com/mod/botania", ""));
        assertNull(McmodCatalog.parseHostUrl("https://modrinth.com.evil.com/mod/botania", ""));
        assertTrue(McmodCatalog.sanitizeQuery("  植物魔法  ").equals("植物魔法"));
        assertEquals("", McmodCatalog.sanitizeQuery("   "));
    }

    @Test
    void coverUrlUsesSmallClassOrModpackImage() {
        String html = """
                <img src="//i.mcmod.cn/post/cover/nope.jpg" />
                <img src="//i.mcmod.cn/class/cover/20211226/1640486226_21294_Qphm.jpg@480x300.jpg" />
                """;
        assertEquals(
                "https://i.mcmod.cn/class/cover/20211226/1640486226_21294_Qphm.jpg@128x128.jpg",
                McmodCatalog.parseCover(html));
        assertEquals(
                "https://i.mcmod.cn/modpack/cover/20240113/1705139595_29797_dSkE.jpg@128x128.jpg",
                McmodCatalog.parseCover("url(//i.mcmod.cn/modpack/cover/20240113/1705139595_29797_dSkE.jpg)"));
        assertEquals("", McmodCatalog.parseCover(
                "//i.mcmod.cn/class/cover/../../secret.jpg"));
    }

    private static String token(String url) {
        return Base64.getEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }
}
