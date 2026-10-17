package com.pmcl.core.cursor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorAgentTest {

    private static final String TOKEN = "ab".repeat(32);

    @TempDir
    Path dir;

    @Test
    void tokenStaysOutOfPreferencesAndSurvivesDisable() throws Exception {
        CursorAgentStore.Config off = CursorAgentStore.load(dir);
        assertFalse(off.enabled);
        assertEquals("", off.token);
        CursorAgentStore.Config on = CursorAgentStore.setEnabled(dir, true);
        assertTrue(on.enabled);
        assertEquals(64, on.token.length());
        assertFalse(on.toString().contains(on.token));
        CursorAgentStore.Config again = CursorAgentStore.load(dir);
        assertEquals(on.token, again.token);
        CursorAgentStore.Config disabled = CursorAgentStore.setEnabled(dir, false);
        assertFalse(disabled.enabled);
        assertEquals(on.token, disabled.token);
        CursorAgentStore.Config rotated = CursorAgentStore.rotateToken(dir);
        assertNotEquals(on.token, rotated.token);
        assertFalse(Files.exists(dir.resolve("preferences.json")));
        var perms = Files.getPosixFilePermissions(CursorAgentStore.file(dir));
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
    }

    @Test
    void cursorConfigKeepsOtherServersAndRefusesASymlink() throws Exception {
        Path mcp = dir.resolve("mcp.json");
        Files.writeString(mcp, """
                {"extra":true,"mcpServers":{"other":{"command":"echo"}}}
                """);
        CursorAgentStore.writeMcpConfig(mcp, 47321, TOKEN);
        JsonObject root = JsonParser.parseString(Files.readString(mcp)).getAsJsonObject();
        assertTrue(root.get("extra").getAsBoolean());
        assertEquals("echo", root.getAsJsonObject("mcpServers").getAsJsonObject("other").get("command").getAsString());
        String url = root.getAsJsonObject("mcpServers").getAsJsonObject("pmcl").get("url").getAsString();
        assertEquals("http://127.0.0.1:47321/mcp", url);
        assertTrue(root.getAsJsonObject("mcpServers").getAsJsonObject("pmcl")
                .getAsJsonObject("headers").get("Authorization").getAsString().startsWith("Bearer "));

        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{");
        IOException invalid = assertThrows(IOException.class,
                () -> CursorAgentStore.writeMcpConfig(broken, 47321, TOKEN));
        assertFalse(invalid.getMessage().contains(TOKEN));
        assertEquals("{", Files.readString(broken));

        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "keep");
        Path link = dir.resolve("linked.json");
        Files.createSymbolicLink(link, secret);
        IOException symlink = assertThrows(IOException.class,
                () -> CursorAgentStore.writeMcpConfig(link, 47321, TOKEN));
        assertFalse(symlink.getMessage().contains(TOKEN));
        assertEquals("keep", Files.readString(secret));
    }

    @Test
    void toolsStayInsideLauncherActions() {
        assertEquals(List.of(
                "pmcl_status",
                "pmcl_list_versions",
                "pmcl_list_instances",
                "pmcl_list_accounts",
                "pmcl_list_pages",
                "pmcl_open_page",
                "pmcl_select_version",
                "pmcl_launch_version",
                "pmcl_launch_instance",
                "pmcl_launch_selected",
                "pmcl_refresh"
        ), CursorMcp.toolNames());
        Fake fake = new Fake();
        JsonObject listed = rpc(CursorMcp.dispatch(request("tools/list", null), fake));
        assertTrue(listed.getAsJsonObject("result").getAsJsonArray("tools").size() >= 2);
        JsonObject again = rpc(CursorMcp.dispatch(request("tools/list", null), fake));
        assertTrue(again.toString().contains("inputSchema"));
        assertEquals(0, fake.launches);
    }

    @Test
    void badIdsNeverReachTheLauncher() {
        Fake fake = new Fake();
        JsonObject denied = rpc(CursorMcp.dispatch(call("pmcl_select_version",
                "{\"versionId\":\"../evil\"}"), fake));
        assertTrue(denied.getAsJsonObject("result").get("isError").getAsBoolean());
        assertNull(fake.selected);

        JsonObject opened = rpc(CursorMcp.dispatch(call("pmcl_open_page",
                "{\"route\":\"../settings\"}"), fake));
        assertTrue(opened.getAsJsonObject("result").get("isError").getAsBoolean());
        assertNull(fake.route);

        JsonObject selected = rpc(CursorMcp.dispatch(call("pmcl_select_version",
                "{\"versionId\":\"26.2-ecxp-forbric\"}"), fake));
        assertFalse(selected.getAsJsonObject("result").get("isError").getAsBoolean());
        assertEquals("26.2-ecxp-forbric", fake.selected);

        JsonObject status = rpc(CursorMcp.dispatch(call("pmcl_status", "{}"), fake));
        assertFalse(status.getAsJsonObject("result").get("isError").getAsBoolean());
        assertTrue(status.toString().contains("\\\"ok\\\":true") || status.toString().contains("\"ok\":true"));
        assertEquals(0, fake.launches);
        assertFalse(status.toString().contains("accessToken"));
    }

    @Test
    void endpointRejectsRemoteOriginAndHidesTheBody() {
        Fake fake = new Fake();
        String secret = "body-secret-not-a-token";
        CursorAgentEndpoint.Reply denied = CursorAgentEndpoint.handle(
                "POST", "evil.example", null, "Bearer " + TOKEN,
                secret.getBytes(StandardCharsets.UTF_8), TOKEN, 47321, fake);
        assertEquals(403, denied.status);
        assertFalse(new String(denied.body, StandardCharsets.UTF_8).contains(secret));
        assertFalse(new String(denied.body, StandardCharsets.UTF_8).contains(TOKEN));

        CursorAgentEndpoint.Reply foreign = CursorAgentEndpoint.handle(
                "POST", "127.0.0.1:47321", "https://evil.example", "Bearer " + TOKEN,
                secret.getBytes(StandardCharsets.UTF_8), TOKEN, 47321, fake);
        assertEquals(403, foreign.status);
        assertFalse(new String(foreign.body, StandardCharsets.UTF_8).contains(secret));
        assertFalse(new String(foreign.body, StandardCharsets.UTF_8).contains(TOKEN));

        CursorAgentEndpoint.Reply anonymous = CursorAgentEndpoint.handle(
                "POST", "127.0.0.1:47321", null, "Bearer nope",
                secret.getBytes(StandardCharsets.UTF_8), TOKEN, 47321, fake);
        assertEquals(401, anonymous.status);
        assertFalse(new String(anonymous.body, StandardCharsets.UTF_8).contains(secret));
        assertNull(fake.route);

        assertTrue(CursorAgentEndpoint.bearer("bearer " + TOKEN, TOKEN));
        assertFalse(CursorAgentEndpoint.bearer("Bearer " + TOKEN + " ", TOKEN));
        assertTrue(CursorAgentEndpoint.localHost("localhost:47321", 47321));
        assertFalse(CursorAgentEndpoint.localHost("127.0.0.1.evil.example", 47321));
        assertFalse(CursorAgentEndpoint.localOrigin("http://localhost.evil.example"));
        assertTrue(CursorAgentEndpoint.localOrigin("http://127.0.0.1:47321"));

        byte[] init = CursorMcp.dispatch(request("initialize",
                "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"cursor\",\"version\":\"1\"}}"), fake);
        CursorAgentEndpoint.Reply ok = CursorAgentEndpoint.handle(
                "POST", "127.0.0.1:47321", null, "Bearer " + TOKEN, initRequest(), TOKEN, 47321, fake);
        assertEquals(200, ok.status);
        String body = new String(ok.body, StandardCharsets.UTF_8);
        assertTrue(body.contains("pmcl"));
        assertFalse(body.contains(TOKEN));
        assertFalse(body.contains("accessToken"));
        assertEquals(0, fake.launches);

        CursorAgentEndpoint.Reply note = CursorAgentEndpoint.handle(
                "POST", "127.0.0.1:47321", null, "Bearer " + TOKEN,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}".getBytes(StandardCharsets.UTF_8),
                TOKEN, 47321, fake);
        assertEquals(202, note.status);
        assertEquals(0, note.body.length);
        assertEquals(405, CursorAgentEndpoint.handle(
                "GET", "127.0.0.1:47321", null, "Bearer " + TOKEN, new byte[0], TOKEN, 47321, fake).status);
        assertNotEquals(0, init.length);
    }

    @Test
    void httpServerStaysOnLoopback() throws Exception {
        Fake fake = new Fake();
        CursorAgentHttp http = new CursorAgentHttp(fake, TOKEN);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            int port = http.start(0);
            assertTrue(http.boundHost().isLoopbackAddress());
            assertFalse(http.boundHost().isAnyLocalAddress());
            HttpResponse<String> ok = client.send(post(port, "Bearer " + TOKEN, null), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("\"name\":\"pmcl\""));
            assertFalse(ok.body().contains(TOKEN));
            assertEquals(0, fake.launches);
            HttpResponse<String> blocked = client.send(
                    post(port, "Bearer " + TOKEN, "https://evil.example"), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, blocked.statusCode());
            assertFalse(blocked.body().contains(TOKEN));
            HttpResponse<String> anonymous = client.send(
                    post(port, null, null), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, anonymous.statusCode());
            assertFalse(anonymous.body().contains(TOKEN));
        } finally {
            http.stop();
        }
    }

    private static HttpRequest post(int port, String authorization, String origin) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(initRequest()));
        if (authorization != null) builder.header("Authorization", authorization);
        if (origin != null) builder.header("Origin", origin);
        return builder.build();
    }

    private static byte[] initRequest() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"cursor","version":"1"}}}
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject request(String method, String params) {
        String json = params == null
                ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}"
                : "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject call(String name, String arguments) {
        return request("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}");
    }

    private static JsonObject rpc(byte[] body) {
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final class Fake implements CursorAgentSession {
        String route;
        String selected;
        int launches;

        @Override public String launcherVersion() { return "2.2a"; }

        @Override public JsonObject status() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", true);
            return obj;
        }

        @Override public JsonObject versions() { return status(); }
        @Override public JsonObject instances() { return status(); }
        @Override public JsonObject accounts() { return status(); }
        @Override public JsonObject pages() { return status(); }

        @Override public JsonObject openPage(String route, String section) {
            this.route = route;
            return status();
        }

        @Override public JsonObject selectVersion(String versionId) {
            selected = versionId;
            return status();
        }

        @Override public JsonObject launchVersion(String versionId) {
            launches++;
            return status();
        }

        @Override public JsonObject launchInstance(String instanceId) {
            launches++;
            return status();
        }

        @Override public JsonObject launchSelected() {
            launches++;
            return status();
        }

        @Override public JsonObject refresh() { return status(); }
    }
}
