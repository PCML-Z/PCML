package com.pmcl.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentRoutesTest {

    @Test
    void phrasesLandOnTheMatchingSection() {
        assertEquals("content/shaders", place("光影", ""));
        assertEquals("content/shaders", place("打开光影页面", ""));
        assertEquals("content/mods", place("模组", ""));
        assertEquals("content/resourcepacks", place("资源包", ""));
        assertEquals("content/modpacks", place("整合包", ""));
        assertEquals("saves/worlds", place("存档", ""));
        assertEquals("saves/timemachine", place("备份", ""));
        assertEquals("saves/screenshots", place("截图", ""));
        assertEquals("settings/java", place("Java", ""));
        assertEquals("settings/java", place("设置", "java"));
        assertEquals("settings/game", place("游戏设置", ""));
        assertEquals("settings/launcher", place("设置", ""));
        assertEquals("download/queue", place("下载队列", ""));
        assertEquals("download/market", place("模组市场", ""));
        assertEquals("download/versions", place("版本", ""));
        assertEquals("accounts/list", place("账号", ""));
        assertEquals("accounts/skin", place("账号皮肤", ""));
        assertEquals("content/skins", place("皮肤", ""));
        assertEquals("multiplayer/room", place("联机", ""));
        assertEquals("multiplayer/settings", place("联机设置", ""));
        assertEquals("friends/mail", place("邮件", ""));
        assertEquals("music/playlist", place("歌单", ""));
        assertEquals("instances", place("实例", ""));
        assertEquals("content/shaders", place("content/shaders", ""));
        assertEquals("content/shaders", place("内容", "光影"));
    }

    @Test
    void unknownSectionDoesNotFallBackToTheFirstPage() {
        assertNull(AgentRoutes.resolve("设置", "光影"));
        assertNull(AgentRoutes.resolve("不存在的页面", ""));
    }

    @Test
    void apiCallsPointAtThePageThatShowsThem() {
        assertEquals("content/shaders", AgentRoutes.homeFor("gameContent.listShaderPacks").route()
                + "/" + AgentRoutes.homeFor("gameContent.listShaderPacks").section());
        assertEquals("content/mods", AgentRoutes.homeFor("mods.scanMods").route()
                + "/" + AgentRoutes.homeFor("mods.scanMods").section());
        assertEquals("download/market", AgentRoutes.homeFor("modMarket.install").route()
                + "/" + AgentRoutes.homeFor("modMarket.install").section());
        assertEquals("settings/java", AgentRoutes.homeFor("javaRuntimes.listInstalled").route()
                + "/" + AgentRoutes.homeFor("javaRuntimes.listInstalled").section());
        assertEquals("saves/timemachine", AgentRoutes.homeFor("backups.listSnapshots").route()
                + "/" + AgentRoutes.homeFor("backups.listSnapshots").section());
        assertNull(AgentRoutes.homeFor("ui.navigate"));
    }

    private static String place(String page, String section) {
        AgentRoutes.Destination dest = AgentRoutes.resolve(page, section);
        if (dest == null) return "null";
        return dest.section().isEmpty() ? dest.route() : dest.route() + "/" + dest.section();
    }
}
