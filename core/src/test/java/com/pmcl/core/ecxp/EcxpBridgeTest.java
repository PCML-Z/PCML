package com.pmcl.core.ecxp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.LauncherCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcxpBridgeTest {

    @TempDir
    Path tempDir;

    private LauncherCore core;
    private EcxpBridge bridge;

    @BeforeEach
    void setUp() {
        core = new LauncherCore(new LauncherConfig(tempDir));
        bridge = new EcxpBridge(core.plugins(), "test", tempDir);
    }

    @AfterEach
    void tearDown() {
        bridge.close();
        core.shutdown();
    }

    @Test
    void manifestNormalizesFrameworkAndRejectsOldOnes() throws Exception {
        EcxpManifest m = EcxpManifest.parse(manifestJson("tool-box", "net11", "Tool.dll", null));
        assertEquals("net11.0", m.getFramework());
        assertEquals(11, m.frameworkMajor());
        assertTrue(m.needsDotnetHost());
        assertTrue(m.requestsAllPermissions());

        EcxpException old = assertThrows(EcxpException.class,
                () -> EcxpManifest.parse(manifestJson("tool-box", "net8.0", "Tool.dll", null)));
        assertEquals("invalid_framework", old.code());
        EcxpException escape = assertThrows(EcxpException.class,
                () -> EcxpManifest.parse(manifestJson("tool-box", "net10.0", "../Tool.dll", null)));
        assertEquals("invalid_entry", escape.code());
        EcxpException perm = assertThrows(EcxpException.class,
                () -> EcxpManifest.parse(manifestJson("tool-box", "net10.0", "Tool.dll", "[\"ROOT\"]")));
        assertEquals("invalid_permission", perm.code());
    }

    @Test
    void installsFolderAndKeepsEntryInside() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve(EcxpManifest.FILE_NAME), manifestJson("demo.tool", "net10.0", "Demo.dll", null));
        Files.writeString(src.resolve("Demo.dll"), "not really a dll");

        EcxpAppManager apps = core.ecxp();
        EcxpAppManager.Installed installed = apps.install(src);
        assertEquals("demo.tool", installed.manifest().getId());
        assertTrue(Files.isRegularFile(apps.appDir("demo.tool").resolve("Demo.dll")));
        assertEquals(1, apps.list().size());
        assertFalse(apps.isRunning("demo.tool"));

        apps.remove("demo.tool");
        assertTrue(apps.list().isEmpty());
    }

    @Test
    void rejectsWrongToken() throws Exception {
        int port = bridge.ensureStarted();
        bridge.issue(EcxpManifest.parse(manifestJson("demo", "net10.0", "Demo.dll", null)), tempDir);
        try (Client c = new Client(port)) {
            JsonObject reply = c.call("{\"id\":0,\"method\":\"hello\",\"params\":{\"token\":\"nope\"}}");
            assertEquals("unauthorized", reply.getAsJsonObject("error").get("code").getAsString());
        }
    }

    @Test
    void callsPluginApiThroughTheBridge() throws Exception {
        int port = bridge.ensureStarted();
        Path data = Files.createDirectories(tempDir.resolve("data"));
        String token = bridge.issue(EcxpManifest.parse(manifestJson("demo", "net11.0", "Demo.dll", null)), data);
        try (Client c = new Client(port)) {
            JsonObject hello = c.call("{\"id\":0,\"method\":\"hello\",\"params\":{\"token\":\"" + token + "\"}}");
            JsonObject info = hello.getAsJsonObject("result");
            assertEquals("demo", info.getAsJsonObject("app").get("id").getAsString());
            assertTrue(info.get("apiAvailable").getAsBoolean());
            assertTrue(bridge.connected("demo"));

            JsonObject instances = c.call("{\"id\":1,\"method\":\"instances.listInstances\"}");
            assertTrue(instances.get("result").isJsonArray());

            JsonObject accounts = c.call("{\"id\":2,\"method\":\"accounts.listAccounts\",\"params\":[]}");
            assertTrue(accounts.get("result").isJsonArray());

            JsonObject unknown = c.call("{\"id\":3,\"method\":\"instances.nope\"}");
            assertEquals("unknown_method", unknown.getAsJsonObject("error").get("code").getAsString());

            JsonObject methods = c.call("{\"id\":4,\"method\":\"host.methods\"}");
            assertTrue(methods.get("result").toString().contains("launch.requestLaunchInstance"));
        }
    }

    @Test
    void limitedAppGetsPermissionDenied() throws Exception {
        int port = bridge.ensureStarted();
        String token = bridge.issue(
                EcxpManifest.parse(manifestJson("demo", "net10.0", "Demo.dll", "[\"READ_MODS\"]")), tempDir);
        try (Client c = new Client(port)) {
            c.call("{\"id\":0,\"method\":\"hello\",\"params\":{\"token\":\"" + token + "\"}}");
            JsonObject denied = c.call("{\"id\":1,\"method\":\"accounts.listAccounts\"}");
            assertEquals("permission_denied", denied.getAsJsonObject("error").get("code").getAsString());
        }
    }

    @Test
    void revokedTokenDropsTheSession() throws Exception {
        int port = bridge.ensureStarted();
        String token = bridge.issue(EcxpManifest.parse(manifestJson("demo", "net10.0", "Demo.dll", null)), tempDir);
        try (Client c = new Client(port)) {
            c.call("{\"id\":0,\"method\":\"hello\",\"params\":{\"token\":\"" + token + "\"}}");
            bridge.revoke(token);
            assertEquals(null, c.reader.readLine());
        }
        assertFalse(bridge.connected("demo"));
    }

    private static String manifestJson(String id, String framework, String entry, String permissions) {
        return "{\"id\":\"" + id + "\",\"name\":\"Demo\",\"version\":\"1.0.0\",\"framework\":\"" + framework
                + "\",\"entry\":\"" + entry + "\"" + (permissions == null ? "" : ",\"permissions\":" + permissions) + "}";
    }

    private static final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader reader;
        final OutputStream out;

        Client(int port) throws Exception {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(10_000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        JsonObject call(String line) throws Exception {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String reply = reader.readLine();
                if (reply == null) throw new IllegalStateException("closed");
                JsonObject obj = JsonParser.parseString(reply).getAsJsonObject();
                if (obj.has("event")) continue;
                return obj;
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
