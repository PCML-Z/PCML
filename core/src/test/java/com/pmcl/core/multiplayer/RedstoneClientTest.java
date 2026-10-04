package com.pmcl.core.multiplayer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedstoneClientTest {

    private static final String KEY = "ABCDEFGHIJ0123456789";

    @Test
    void parsesRelayPortAndRejectsUnsafeTargets() {
        assertEquals("122.51.108.96", RedstoneClient.canonicalRelay("http://122.51.108.96:3000/"));
        assertEquals("relay.example:3001", RedstoneClient.canonicalRelay("https://relay.example:3001"));
        assertEquals(42, RedstoneClient.parseListenPort("{\"listenPort\":42}"));
        assertEquals(7, RedstoneClient.parseListenPort("{\"tunnels\":[{\"listenPort\":7}]}"));
        assertEquals(0, RedstoneClient.parseListenPort("nope"));
        assertTrue(RedstoneClient.isApiKey(RedstoneClient.generateApiKey()));
        assertEquals(20, RedstoneClient.generateApiKey().length());

        RedstoneClient.Endpoint endpoint = RedstoneClient.publicEndpoint("122.51.108.96:23456");
        assertNotNull(endpoint);
        assertEquals("122.51.108.96", endpoint.host());
        assertEquals(23456, endpoint.port());
        assertNull(RedstoneClient.publicEndpoint("122.51.108.96"));
        assertNull(RedstoneClient.publicEndpoint("user:pass@122.51.108.96:1"));

        assertEquals("127.0.0.1", RedstoneClient.localTarget("localhost", 25565).host());
        assertEquals("192.168.1.20", RedstoneClient.localTarget("192.168.1.20", 80).host());
        assertEquals("10.1.2.3", RedstoneClient.localTarget("10.1.2.3", 1).host());
        assertEquals("172.16.5.1", RedstoneClient.localTarget("172.16.5.1", 1).host());
        assertThrows(IllegalArgumentException.class, () -> RedstoneClient.localTarget("8.8.8.8", 25565));
        assertThrows(IllegalArgumentException.class, () -> RedstoneClient.localTarget("172.32.0.1", 1));
        assertThrows(IllegalArgumentException.class, () -> RedstoneClient.localTarget("169.254.1.1", 80));
        assertThrows(IllegalArgumentException.class, () -> RedstoneClient.canonicalRelay("169.254.169.254"));
        assertThrows(IllegalArgumentException.class, () -> RedstoneClient.canonicalRelay("0.0.0.0"));
        assertThrows(IllegalArgumentException.class, () -> RedstoneClient.canonicalRelay("http://user@122.51.108.96"));
        assertThrows(IllegalArgumentException.class, () -> RedstoneClient.canonicalRelay("122.51.108.96/admin"));

        RedstoneClient client = new RedstoneClient();
        assertThrows(IllegalArgumentException.class,
                () -> client.open("127.0.0.1", KEY, "1.1.1.1", 25565, 1));
        assertFalse(client.isOpen());
    }

    @Test
    void greetingKeepsMinecraftBytesAndRejectsErrorLines() throws Exception {
        assertEquals(0, prefixOf("OK TUNNEL 5\nZ".getBytes(StandardCharsets.UTF_8)).length);
        byte[] binary = prefixOf(new byte[] {0x10, 0x00});
        assertEquals(1, binary.length);
        assertEquals(0x10, binary[0] & 0xff);
        assertThrows(IOException.class, () -> prefixOf("ERR bad key\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void guestRemembersPublicAddressWithoutOpeningATunnel() throws Exception {
        MultiplayerManager manager = new MultiplayerManager();
        manager.setBackend(MultiplayerManager.Backend.REDSTONE);
        manager.joinRoom("122.51.108.96:23456", null).get(3, TimeUnit.SECONDS);
        assertEquals(MultiplayerManager.State.CONNECTED, manager.getState());
        assertEquals("122.51.108.96:23456", manager.generateInvitation());
        assertFalse(manager.getRedstone().isOpen());
        manager.leaveRoom();
        assertEquals(MultiplayerManager.State.DISCONNECTED, manager.getState());

        MultiplayerManager invalid = new MultiplayerManager();
        invalid.setBackend(MultiplayerManager.Backend.REDSTONE);
        assertThrows(Exception.class, () -> invalid.joinRoom("not an address", null).get(3, TimeUnit.SECONDS));
        assertEquals(MultiplayerManager.State.FAILED, invalid.getState());
        assertFalse(invalid.getRedstone().isOpen());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void hostForwardsPlayerBytesAndReleasesTheTunnel() throws Exception {
        AtomicInteger tunnelPosts = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/apikey", exchange -> {
            drain(exchange);
            send(exchange, 409, "");
        });
        api.createContext("/tunnels", exchange -> {
            drain(exchange);
            String method = exchange.getRequestMethod();
            if (!KEY.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "");
                return;
            }
            if ("GET".equals(method)) {
                send(exchange, 200, "{\"tunnels\":[]}");
                return;
            }
            if ("DELETE".equals(method)) {
                deletes.incrementAndGet();
                send(exchange, 200, "");
                return;
            }
            if ("POST".equals(method)) {
                int attempt = tunnelPosts.incrementAndGet();
                if (attempt == 1) {
                    send(exchange, 429, "");
                    return;
                }
                send(exchange, 200, "{\"listenPort\":23456}");
                return;
            }
            send(exchange, 405, "");
        });
        api.start();

        ServerSocket tunnel = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        ServerSocket world = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        CountDownLatch arrived = new CountDownLatch(1);
        Thread tunnelThread = new Thread(() -> acceptPlayer(tunnel, arrived), "redstone-test-tunnel");
        Thread worldThread = new Thread(() -> echoWorld(world), "redstone-test-world");
        tunnelThread.setDaemon(true);
        worldThread.setDaemon(true);
        tunnelThread.start();
        worldThread.start();

        RedstoneClient client = new RedstoneClient(tunnel.getLocalPort());
        MultiplayerManager manager = new MultiplayerManager(client);
        try {
            manager.setBackend(MultiplayerManager.Backend.REDSTONE);
            manager.configureRedstone("127.0.0.1:" + api.getAddress().getPort(),
                    world.getLocalPort(), 1, KEY);
            manager.createRoom(null).get(8, TimeUnit.SECONDS);
            assertEquals(MultiplayerManager.State.CONNECTED, manager.getState(), manager.getLastError());
            assertEquals("127.0.0.1:23456", manager.generateInvitation());
            assertEquals("127.0.0.1:" + world.getLocalPort(), manager.getLocalMcAddr());
            assertTrue(arrived.await(8, TimeUnit.SECONDS), "玩家数据没有转到本机端口");
            assertEquals(2, tunnelPosts.get());
            assertEquals(1, deletes.get());
            assertFalse(manager.generateInvitation().contains(KEY));
            manager.leaveRoom();
            assertEquals(MultiplayerManager.State.DISCONNECTED, manager.getState());
            assertEquals(2, deletes.get());
            assertFalse(client.isOpen());
        } finally {
            try {
                manager.leaveRoom();
            } catch (RuntimeException ignored) {
            }
            client.close();
            api.stop(0);
            tunnel.close();
            world.close();
        }
    }

    private static void acceptPlayer(ServerSocket tunnel, CountDownLatch arrived) {
        try (Socket socket = tunnel.accept()) {
            socket.setSoTimeout(8_000);
            assertEquals(KEY, readLine(socket));
            socket.getOutputStream().write("OK TUNNEL 1\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            byte[] got = readExact(socket, 4);
            assertEquals("ping", new String(got, StandardCharsets.UTF_8));
            arrived.countDown();
            socket.setSoTimeout(8_000);
            try {
                while (socket.getInputStream().read() >= 0) {
                    // 等到房主关闭隧道。
                }
            } catch (IOException ignored) {
            }
        } catch (Exception ignored) {
            // 不放行。外层等待超时后会说明数据没有转到本机端口。
        }
    }

    private static void echoWorld(ServerSocket world) {
        try (Socket socket = world.accept()) {
            socket.setSoTimeout(8_000);
            byte[] got = readExact(socket, 4);
            socket.getOutputStream().write(got);
            socket.getOutputStream().flush();
            try {
                while (socket.getInputStream().read() >= 0) {
                    // 保持连接，让回写的字节先被隧道读走。
                }
            } catch (IOException ignored) {
            }
        } catch (IOException ignored) {
        }
    }

    private static byte[] prefixOf(byte[] payload) throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(payload);
                    socket.getOutputStream().flush();
                    Thread.sleep(1_000);
                } catch (Exception ignored) {
                }
            });
            peer.setDaemon(true);
            peer.start();
            try (Socket client = new Socket()) {
                client.connect(server.getLocalSocketAddress(), 2_000);
                return RedstoneClient.readRelayPrefix(client);
            }
        }
    }

    private static String readLine(Socket socket) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < 80) {
            int value = socket.getInputStream().read();
            if (value < 0 || value == '\n') break;
            line.write(value);
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readExact(Socket socket, int count) throws IOException {
        byte[] data = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = socket.getInputStream().read(data, offset, count - offset);
            if (n < 0) break;
            offset += n;
        }
        if (offset != count) throw new IOException("short read " + offset);
        return data;
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
    }

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (bytes.length > 0) out.write(bytes);
        }
    }
}
