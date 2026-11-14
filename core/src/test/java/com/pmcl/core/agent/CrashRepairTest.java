package com.pmcl.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    void grepKeepsOriginalLinesAndMergesContext() {
        CrashLogTools.LogFile file = new CrashLogTools.LogFile(
                "log-1", "latest.log", "minecraft_log", 200, true,
                List.of("start", "ERROR one", "cause", "error two", "end"));
        JsonObject request = new JsonObject();
        request.addProperty("name", "grep_log_file");
        request.addProperty("file_id", "log-1");
        request.addProperty("query", "error");
        request.addProperty("context", 1);
        JsonObject result = CrashLogTools.execute(List.of(file), request);
        assertEquals(5, result.getAsJsonArray("lines").size());
        assertEquals(200, result.getAsJsonArray("lines").get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals(204, result.getAsJsonArray("lines").get(4).getAsJsonObject().get("line").getAsInt());
    }

    @Test
    void rejectsPathsAndBoundsLongLines() {
        CrashLogTools.LogFile file = new CrashLogTools.LogFile(
                "log-1", "latest.log", "minecraft_log", 200, false, List.of("字".repeat(20000)));
        JsonObject request = new JsonObject();
        request.addProperty("name", "read_log_file");
        request.addProperty("file_id", "../../secret");
        request.addProperty("start_line", 200);
        request.addProperty("end_line", Integer.MAX_VALUE);
        assertTrue(CrashLogTools.execute(List.of(file), request).has("error"));
        request.addProperty("file_id", "log-1");
        JsonObject result = CrashLogTools.execute(List.of(file), request);
        String text = result.getAsJsonArray("lines").get(0).getAsJsonObject().get("text").getAsString();
        assertEquals(2000, text.codePointCount(0, text.length()));
        assertTrue(result.get("truncated").getAsBoolean());
        request.addProperty("start_line", Integer.MAX_VALUE);
        assertEquals(0, CrashLogTools.execute(List.of(file), request).getAsJsonArray("lines").size());
    }

    @Test
    void snapshotHidesHomeAndListOmitsLineText() {
        String home = System.getProperty("user.home");
        List<CrashLogTools.LogFile> files = CrashLogTools.snapshot("boom " + home + "\nCaused by: bad", "");
        JsonObject list = new JsonObject();
        list.addProperty("name", "list_log_files");
        String listed = CrashLogTools.execute(files, list).toString();
        assertFalse(listed.contains("boom"));
        assertFalse(listed.contains(home));
        JsonObject read = new JsonObject();
        read.addProperty("name", "read_log_file");
        read.addProperty("file_id", "log-1");
        read.addProperty("start_line", 1);
        read.addProperty("end_line", 5);
        String body = CrashLogTools.execute(files, read).toString();
        assertTrue(body.contains("{HOME}"));
        assertFalse(body.contains(home));
    }

    @Test
    void initialWindowStartsAtTheCrashReport() {
        CrashLogTools.LogFile crash = new CrashLogTools.LogFile(
                "log-1", "crash-report.txt", "crash_report", 1, false,
                List.of("header", "Caused by: sodium"));
        CrashLogTools.LogFile log = new CrashLogTools.LogFile(
                "log-2", "latest.log", "minecraft_log", 1, false, List.of("hello"));
        String window = CrashLogTools.initialWindow(List.of(log, crash));
        assertTrue(window.indexOf("log-1") < window.indexOf("log-2"));
        assertTrue(window.contains("Caused by: sodium"));
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
        CrashRepair.use(List.of(), seen, "1.20.1", call, api, new ArrayList<>());
        String second = CrashRepair.use(List.of(), seen, "1.20.1", call, api, new ArrayList<>());
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
