package com.pmcl.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRepairTest {

    @Test
    void nativeEngineFindsOutOfMemoryAndRejectsPaths() {
        long session = LogAgentNative.open();
        try {
            LogAgentNative.addFile(session, "latest.log", "minecraft_log",
                    "start\njava.lang.OutOfMemoryError: Java heap space\nend");
            String context = LogAgentNative.context(session);
            assertTrue(context.contains("out_of_memory"));
            String denied = LogAgentNative.tool(session,
                    "{\"name\":\"read_log_file\",\"file_id\":\"../../secret\",\"start_line\":1}");
            assertTrue(denied.contains("Unknown file_id"));
            CrashRepair.Insight rules = CrashRepair.fromRules(context);
            assertTrue(rules.rootCause.contains("OutOfMemoryError"));
            assertEquals(0.9, rules.confidence, 0.001);
        } finally {
            LogAgentNative.close(session);
        }
    }

    @Test
    void repairStaysOnTheBridgeAllowlist() {
        List<String> calls = new ArrayList<>();
        CrashRepair.Api api = (method, args) -> {
            calls.add(method + " " + args);
            if ("mods.resolveModsDir".equals(method)) return "\"/games/mods\"";
            if ("settings.getMaxMemoryMb".equals(method)) return "2048";
            if ("javaRuntimes.listInstalled".equals(method)) {
                return "[\"" + javaHome() + "\"]";
            }
            return "null";
        };
        assertEquals("不能执行 mods.deleteMod", CrashRepair.repair("1.20.1", "mods.deleteMod", new JsonArray(), api));
        assertEquals("不能执行 settings.setCustomJvmArgs",
                CrashRepair.repair("1.20.1", "settings.setCustomJvmArgs", new JsonArray(), api));
        assertEquals("不能执行 filesystem.readText",
                CrashRepair.repair("1.20.1", "filesystem.readText", new JsonArray(), api));
        assertTrue(calls.isEmpty());

        JsonArray name = new JsonArray();
        name.add("../secrets.jar");
        assertEquals("只接受模组文件名", CrashRepair.repair("1.20.1", "mods.disableMod", name, api));
        assertTrue(calls.isEmpty());

        JsonArray jar = new JsonArray();
        jar.add("sodium.jar");
        String disabled = CrashRepair.repair("1.20.1", "mods.disableMod", jar, api);
        assertEquals("已禁用 sodium.jar", disabled);
        assertTrue(calls.get(0).startsWith("mods.resolveModsDir"));
        assertTrue(calls.get(1).contains("sodium.jar"));
        assertFalse(calls.get(1).contains(".."));

        JsonArray memory = new JsonArray();
        memory.add(9000);
        assertEquals("已把最大内存从 2048 调到 4096",
                CrashRepair.repair("1.20.1", "settings.setMaxMemoryMb", memory, api));
        assertTrue(calls.stream().anyMatch(call -> call.startsWith("settings.setMaxMemoryMb") && call.contains("4096")));

        JsonArray shrink = new JsonArray();
        shrink.add(1024);
        assertEquals("不能把内存调小", CrashRepair.repair("1.20.1", "settings.setMaxMemoryMb", shrink, api));

        JsonArray other = new JsonArray();
        other.add("1.21");
        other.add(javaHome());
        assertEquals("只能改这一局的 Java", CrashRepair.repair("1.20.1", "versionJava.set", other, api));

        String java = javaHome();
        JsonArray javaArgs = new JsonArray();
        javaArgs.add("1.20.1");
        javaArgs.add("/tmp/evil/java");
        assertEquals("只能换成启动器已经装好的 Java",
                CrashRepair.repair("1.20.1", "versionJava.set", javaArgs, api));
        javaArgs.set(1, new com.google.gson.JsonPrimitive(java));
        assertEquals("已把这个版本的 Java 换成 " + java,
                CrashRepair.repair("1.20.1", "versionJava.set", javaArgs, api));

        int before = calls.size();
        CrashRepair.repair("1.20.1", "integrity.check", array("other-version"), api);
        String check = calls.get(calls.size() - 1);
        assertTrue(check.contains("1.20.1"));
        assertFalse(check.contains("other-version"));
        assertTrue(calls.size() > before);
        assertFalse(CrashRepair.TOOLS.contains("deleteMod"));
        assertFalse(CrashRepair.TOOLS.contains("filesystem"));
        assertTrue(CrashRepair.PROMPT.contains("call_api"));
    }

    @Test
    void repeatedLookupDoesNotCallTheBridgeAgain() {
        AtomicInteger calls = new AtomicInteger();
        CrashRepair.Api api = (method, args) -> {
            calls.incrementAndGet();
            return "null";
        };
        AgentModel.ToolCall call = new AgentModel.ToolCall("1", "call_api",
                "{\"method\":\"settings.getJavaPath\",\"args\":[]}");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        CrashRepair.use(0, seen, "1.20.1", call, api, new ArrayList<>());
        String second = CrashRepair.use(0, seen, "1.20.1", call, api, new ArrayList<>());
        assertEquals(1, calls.get());
        assertTrue(second.contains("已经看过"));
    }

    @Test
    void parsesTheInsightBlock() {
        CrashRepair.Insight insight = CrashRepair.present("""
                是 Sodium 和游戏版本不匹配。
                ```json
                {"rootCause":"Sodium 不匹配","confidence":1.4,"evidence":["log-1:12"],"steps":["禁用 sodium.jar"]}
                ```
                """);
        assertEquals("Sodium 不匹配", insight.rootCause);
        assertEquals(1.0, insight.confidence, 0.001);
        assertEquals(List.of("log-1:12"), insight.evidence);
        assertEquals(List.of("禁用 sodium.jar"), insight.steps);
        assertTrue(insight.prose.contains("不匹配"));
        assertFalse(insight.prose.contains("rootCause"));
        assertNull(CrashRepair.present("还不确定").confidence);
    }

    @Test
    void missingLogOrModelDoesNotCallOut() {
        CrashRepair.Hooks hooks = new CrashRepair.Hooks() {
            @Override public boolean cancelled() { return false; }
            @Override public void progress(String text) {}
            @Override public String call(String method, JsonArray args) {
                throw new AssertionError(method);
            }
        };
        assertEquals("empty", CrashRepair.investigate(null, "1.20.1", " ", "", hooks).status);
        assertEquals("need", CrashRepair.investigate(
                new AgentModel.Endpoint("", "", ""), "1.20.1", "crash", "", hooks).status);
        CrashRepair.Outcome rules = CrashRepair.investigate(
                new AgentModel.Endpoint("", "", ""), "1.20.1", "",
                "java.lang.OutOfMemoryError: Java heap space", hooks);
        assertEquals("ready", rules.status);
        assertTrue(rules.insight.rootCause.contains("OutOfMemoryError"));
        assertEquals(0.9, rules.insight.confidence, 0.001);
        CrashRepair.Hooks stop = new CrashRepair.Hooks() {
            @Override public boolean cancelled() { return true; }
            @Override public void progress(String text) {}
            @Override public String call(String method, JsonArray args) {
                throw new AssertionError(method);
            }
        };
        assertEquals("cancelled", CrashRepair.investigate(
                new AgentModel.Endpoint("http://127.0.0.1:9/v1", "", "local"),
                "1.20.1", "crash", "", stop).status);
    }

    private static String javaHome() {
        return Path.of("/opt/pmcl/java/bin/java").toAbsolutePath().normalize().toString();
    }

    private static JsonArray array(String value) {
        JsonArray out = new JsonArray();
        out.add(value);
        return out;
    }

    @Test
    void quotedBridgePathJoinsTheFileName() {
        List<String> calls = new ArrayList<>();
        CrashRepair.Api api = (method, args) -> {
            calls.add(method + " " + args);
            if ("mods.resolveModsDir".equals(method)) return JsonParser.parseString("\"/games/mods\"").toString();
            return "null";
        };
        JsonArray jar = new JsonArray();
        jar.add("fabric.jar");
        assertEquals("已启用 fabric.jar", CrashRepair.repair("1.20.1", "mods.enableMod", jar, api));
        assertTrue(calls.get(1).contains("/games/mods/fabric.jar"));
    }
}
