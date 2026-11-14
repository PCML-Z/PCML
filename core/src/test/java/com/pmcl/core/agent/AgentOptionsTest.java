package com.pmcl.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOptionsTest {

    @Test
    void gameModePhraseTurnsTheOptionOnAndOpensGameSettings() {
        String phrase = "打开设置使用 macOS Game Mode 启动游戏选项";
        AgentOptions.Change change = AgentOptions.match(phrase);
        assertEquals("macGameMode", change.id());
        assertEquals("settings", change.route());
        assertEquals("game", change.section());
        assertEquals(Boolean.TRUE, change.enabled());
        assertTrue(AgentOptions.direct(phrase));
        assertTrue(AgentOptions.direct("打开设置使用 macoS Game Mode 启动游戏选项"));
    }

    @Test
    void otherNamedSwitchesUseTheSameCommand() {
        AgentOptions.Change isolation = AgentOptions.match("打开设置使用版本隔离选项");
        assertEquals("versionIsolation", isolation.id());
        assertEquals("game", isolation.section());
        assertEquals(Boolean.TRUE, isolation.enabled());
        assertTrue(AgentOptions.direct("打开设置使用版本隔离选项"));

        AgentOptions.Change fullscreen = AgentOptions.match("关闭全屏启动");
        assertEquals("fullscreen", fullscreen.id());
        assertEquals(Boolean.FALSE, fullscreen.enabled());

        AgentOptions.Change utf8 = AgentOptions.match("打开设置使用优先使用 UTF-8 编码选项");
        assertEquals("preferUtf8", utf8.id());
        assertTrue(AgentOptions.direct("打开设置使用优先使用 UTF-8 编码选项"));

        AgentOptions.Change dark = AgentOptions.match("打开深色主题");
        assertEquals("dark", dark.id());
        assertEquals("theme", dark.section());
        assertTrue(AgentOptions.direct("打开深色主题"));

        AgentOptions.Change crazy = AgentOptions.match("打开疯狂调度");
        assertEquals("mioCrazy", crazy.id());
        assertTrue(crazy.confirm());
        assertTrue(AgentOptions.direct("打开疯狂调度"));
    }

    @Test
    void closingTheOptionStaysOnTheSameSetting() {
        AgentOptions.Change change = AgentOptions.match("关闭 macOS Game Mode");
        assertEquals("macGameMode", change.id());
        assertEquals(Boolean.FALSE, change.enabled());
        assertTrue(AgentOptions.direct("请关掉 macOS Game Mode 选项"));
    }

    @Test
    void otherPagesAndFollowUpTasksAreLeftAlone() {
        assertNull(AgentOptions.match("打开设置"));
        assertNull(AgentOptions.match("打开光影"));
        assertNull(AgentOptions.match("打开 Game Mode"));
        AgentOptions.Change mixed = AgentOptions.match("打开 macOS Game Mode 然后启动游戏");
        assertEquals(Boolean.TRUE, mixed.enabled());
        assertFalse(AgentOptions.direct("打开 macOS Game Mode 然后启动游戏"));
        assertFalse(AgentOptions.direct("macOS Game Mode 在哪"));
        assertFalse(AgentOptions.available(
                AgentOptions.match("打开 macOS Game Mode"), "Windows 11"));
        assertTrue(AgentOptions.available(
                AgentOptions.match("打开版本隔离"), "Windows 11"));
    }
}
