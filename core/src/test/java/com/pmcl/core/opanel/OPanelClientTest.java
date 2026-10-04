package com.pmcl.core.opanel;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OPanelClientTest {

    @TempDir
    Path dir;

    @Test
    void acceptsALocalPanelAddressAndRejectsSecretsInTheUrl() throws Exception {
        assertEquals("http://127.0.0.1:3000", OPanelClient.normalizeBaseUrl("http://127.0.0.1:3000/"));
        assertEquals("https://panel.example.com", OPanelClient.normalizeBaseUrl("https://panel.example.com"));
        assertEquals("bad_url", assertThrows(OPanelException.class,
                () -> OPanelClient.normalizeBaseUrl("http://user:secret@127.0.0.1:3000")).code);
        assertEquals("bad_url", assertThrows(OPanelException.class,
                () -> OPanelClient.normalizeBaseUrl("file:///tmp/opanel")).code);
        assertEquals("bad_url", assertThrows(OPanelException.class,
                () -> OPanelClient.normalizeBaseUrl("http://127.0.0.1:3000/api")).code);
        assertEquals("bad_token", assertThrows(OPanelException.class,
                () -> OPanelClient.checkToken("password")).code);
        assertEquals(50, OPanelClient.checkToken("o-" + "a".repeat(48)).length());
    }

    @Test
    void readsStatusPlayersAndStripsTheCommandSlash() throws Exception {
        String motd = Base64.getEncoder().encodeToString("§aHello\nworld".getBytes());
        String info = """
                {"code":200,"error":"","motd":"%s","port":25565,"maxPlayerCount":20,"whitelist":true,
                 "ingameTime":{"mspt":12.5}}
                """.formatted(motd);
        String monitor = """
                {"code":200,"error":"","tps":19.8}
                """;
        String players = """
                {"code":200,"error":"","players":[
                  {"name":"Alex","uuid":"11111111-1111-1111-1111-111111111111","isOnline":true,"isOp":true,"isBanned":false,"gamemode":"survival","ping":24},
                  {"name":"","uuid":"22222222-2222-2222-2222-222222222222","isOnline":true}
                ]}
                """;
        OPanelClient.Snapshot snapshot = OPanelClient.parseOverview(
                JsonParser.parseString(info).getAsJsonObject(),
                JsonParser.parseString(monitor).getAsJsonObject(),
                JsonParser.parseString(players).getAsJsonObject());
        assertEquals("Hello world", snapshot.motd);
        assertEquals(25565, snapshot.port);
        assertEquals(20, snapshot.maxPlayers);
        assertTrue(snapshot.whitelist);
        assertEquals(19.8, snapshot.tps);
        assertEquals(12.5, snapshot.mspt);
        assertEquals(1, snapshot.players.size());
        assertEquals("Alex", snapshot.players.get(0).name);
        assertTrue(snapshot.players.get(0).op);
        assertEquals(1, snapshot.onlineCount());
        assertEquals("say hi", OPanelClient.checkCommand("  /say hi  "));
        assertEquals("rejected", assertThrows(OPanelException.class,
                () -> OPanelClient.checkCommand("say\nhi")).code);
        assertEquals("rejected", assertThrows(OPanelException.class,
                () -> OPanelClient.checkUuid("../etc")).code);
    }

    @Test
    void storesTheTokenBesideTheLauncherHome() throws Exception {
        OkHttpPanel panel = new OkHttpPanel(dir);
        panel.client.save("http://127.0.0.1:3000", "o-" + "b".repeat(48));
        assertTrue(Files.readString(dir.resolve("opanel.json")).contains("127.0.0.1:3000"));
        assertEquals("o-" + "b".repeat(48), panel.client.load().token);
        assertEquals("bad_url", assertThrows(OPanelException.class,
                () -> panel.client.save("http://127.0.0.1:3000/panel", "o-" + "b".repeat(48))).code);
    }

    private static final class OkHttpPanel {
        final OPanelClient client;

        OkHttpPanel(Path home) {
            client = new OPanelClient(new okhttp3.OkHttpClient(), home);
        }
    }
}
