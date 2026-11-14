package com.pmcl.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModelTest {

    @TempDir
    Path tmp;

    @Test
    void chatUrlAcceptsAVersionRootAndAFullEndpoint() throws Exception {
        assertEquals("https://api.openai.com/v1/chat/completions",
                AgentModel.chatUrl("https://api.openai.com/v1/"));
        assertEquals("http://127.0.0.1:11434/v1/chat/completions",
                AgentModel.chatUrl("http://127.0.0.1:11434/v1/chat/completions"));
        assertThrows(java.io.IOException.class, () -> AgentModel.chatUrl("file:///tmp/model"));
        assertThrows(java.io.IOException.class, () -> AgentModel.chatUrl("https://key@example.com/v1"));
    }

    @Test
    void readStepKeepsToolCalls() throws Exception {
        AgentModel.Step step = AgentModel.readStep("""
                {"choices":[{"message":{"content":"","tool_calls":[{"id":"a","type":"function","function":{"name":"open_page","arguments":"{\\"page\\":\\"settings\\"}"}}]}}]}
                """);
        assertEquals("open_page", step.calls.get(0).name);
        assertEquals("", step.content);
        assertEquals("", step.thought);
        assertEquals("settings", com.google.gson.JsonParser.parseString(step.calls.get(0).arguments)
                .getAsJsonObject().get("page").getAsString());
    }

    @Test
    void readStepKeepsThePlanBesideAToolCall() throws Exception {
        AgentModel.Step step = AgentModel.readStep("""
                {"choices":[{"message":{"content":"先打开光影","reasoning_content":"先核对页面再打开光影页面","tool_calls":[{"id":"a","type":"function","function":{"name":"open_page","arguments":"{\\"page\\":\\"光影\\"}"}}]}}]}
                """);
        assertEquals("", step.content);
        assertEquals("先打开光影", step.thought);
        assertEquals(1, step.calls.size());
    }

    @Test
    void readStepSplitsThinkTagsFromTheAnswer() throws Exception {
        AgentModel.Step step = AgentModel.readStep("""
                {"choices":[{"message":{"content":"<think>先看账号</think>还没登录"}}]}
                """);
        assertEquals("先看账号", step.thought);
        assertEquals("还没登录", step.content);
        assertTrue(step.calls.isEmpty());
        assertFalse(step.content.contains("think"));
    }

    @Test
    void readStepUsesReasoningWithoutMixingItIntoTheAnswer() throws Exception {
        AgentModel.Step step = AgentModel.readStep("""
                {"choices":[{"message":{"content":"已选中","reasoning_content":"核对版本"}}]}
                """);
        assertEquals("已选中", step.content);
        assertEquals("核对版本", step.thought);
        assertFalse(step.content.contains("核对"));
    }

    @Test
    void readStepClipsALongThought() throws Exception {
        String longThought = "想".repeat(90);
        AgentModel.Step step = AgentModel.readStep(
                "{\"choices\":[{\"message\":{\"content\":\"好\",\"reasoning_content\":\"" + longThought + "\"}}]}");
        assertEquals("好", step.content);
        assertEquals(81, step.thought.length());
        assertTrue(step.thought.endsWith("…"));
        assertFalse(step.thought.contains("\n"));
    }

    @Test
    void readStepTreatsAnUnclosedThinkTagAsTheThought() throws Exception {
        AgentModel.Step step = AgentModel.readStep("""
                {"choices":[{"message":{"content":"<think>先看版本"}}]}
                """);
        assertEquals("先看版本", step.thought);
        assertEquals("", step.content);
    }

    @Test
    void readReplyTakesStringOrTextParts() throws Exception {
        assertEquals("你好", AgentModel.readReply(
                "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}"));
        assertEquals("ab", AgentModel.readReply(
                "{\"choices\":[{\"message\":{\"content\":[{\"text\":\"a\"},{\"text\":\"b\"}]}}]}"));
        assertThrows(java.io.IOException.class, () -> AgentModel.readReply("{\"choices\":[]}"));
    }

    @Test
    void loopbackPortOnlyMatchesThisMachine() {
        assertEquals(1234, AgentModel.loopbackPort("http://127.0.0.1:1234/v1"));
        assertEquals(-1, AgentModel.loopbackPort("https://api.openai.com/v1"));
        assertEquals(1024, AgentModel.maxTokens());
        assertEquals("server", AgentModel.serverStartCommand(java.nio.file.Path.of("lms"), 1234).get(1));
        assertTrue(AgentModel.loadCommand(java.nio.file.Path.of("lms"), "qwen").contains("--identifier"));
    }

    @Test
    void saveKeepsTheEndpointAndLeavesPreferencesAlone() throws Exception {
        AgentModel.save(tmp, "http://127.0.0.1:11434/v1", "", "llama3");
        AgentModel.Endpoint endpoint = AgentModel.load(tmp);
        assertTrue(endpoint.ready());
        assertEquals("http://127.0.0.1:11434/v1", endpoint.baseUrl);
        assertEquals("llama3", endpoint.model);
        assertEquals("", endpoint.apiKey);
        assertFalse(Files.exists(tmp.resolve("preferences.json")));
        assertTrue(Files.isRegularFile(tmp.resolve("agent-model.json")));
    }
}
