package com.pmcl.core.multiplayer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 红石联机中继客户端。
 * <p>
 * 控制面是中继上的 HTTP（默认 3000）：登记密钥、申请或释放隧道。
 * 数据面是到同一主机 7000 端口的 TCP：先送一行密钥，再把玩家连接转到本机 Minecraft。
 * 房客不走隧道，只使用房主拿到的公网地址。
 */
public final class RedstoneClient {

    public static final String DEFAULT_RELAY = "122.51.108.96";
    public static final int API_PORT = 3000;
    public static final int TUNNEL_PORT = 7000;
    public static final int DEFAULT_MAX_PLAYERS = 8;
    public static final int MAX_PLAYERS = 50;

    private static final int GREETING_TIMEOUT_MS = 400;
    private static final int DIAL_TIMEOUT_MS = 10_000;
    private static final int RETRY_MS = 1_500;
    private static final Pattern HOSTNAME = Pattern.compile(
            "(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");

    private final HttpClient http;
    private final int tunnelPort;
    private final Object stateLock = new Object();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean live;
    private volatile ExecutorService workers;
    private volatile String relayHost = "";
    private volatile String activeKey = "";
    private volatile Relay activeRelay;
    private volatile String localHost = "127.0.0.1";
    private volatile int localPort;
    private volatile int listenPort;

    public RedstoneClient() {
        this(TUNNEL_PORT);
    }

    /** @param tunnelPort 数据面端口。正式中继是 {@link #TUNNEL_PORT}，测试可改到临时端口。 */
    public RedstoneClient(int tunnelPort) {
        if (tunnelPort < 1 || tunnelPort > 65535) {
            throw new IllegalArgumentException("隧道端口无效");
        }
        this.tunnelPort = tunnelPort;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record Relay(String host, int apiPort) {}

    public record Endpoint(String host, int port) {}

    public record Opened(String publicAddress, String localTarget, int listenPort) {}

    public boolean isOpen() {
        return live;
    }

    public int listenPort() {
        return listenPort;
    }

    /**
     * 申请隧道并开始把玩家转到本机端口。
     * 本机目标只允许回环和局域网地址，避免把启动器当成任意公网代理。
     */
    public Opened open(String relayRaw, String apiKey, String targetHost, int targetPort, int maxPlayers)
            throws IOException {
        Relay relay = parseRelay(relayRaw);
        Endpoint local = localTarget(targetHost, targetPort);
        String key = requireKey(apiKey);
        int players = clampPlayers(maxPlayers);
        close();
        live = true;
        try {
            registerKey(relay, key);
            if (!live) throw new IOException("已取消");
            int port = openTunnel(relay, key, players);
            if (port < 1 || port > 65535) throw new IOException("中继没有返回隧道端口");
            synchronized (stateLock) {
                relayHost = relay.host();
                activeRelay = relay;
                activeKey = key;
                localHost = local.host();
                localPort = local.port();
                listenPort = port;
                if (!live) throw new IOException("已取消");
            }
            startWorkers(players);
            if (!live) throw new IOException("已取消");
            return new Opened(relay.host() + ":" + port, local.host() + ":" + local.port(), port);
        } catch (RuntimeException | IOException e) {
            close();
            if (e instanceof IOException io) throw io;
            if (e instanceof IllegalArgumentException invalid) throw invalid;
            throw new IOException(e.getMessage() != null ? e.getMessage() : "申请隧道失败", e);
        }
    }

    /** 停掉转发，并通知中继释放端口。可以重复调用。 */
    public void close() {
        live = false;
        stopWorkers();
        Relay relay;
        String key;
        synchronized (stateLock) {
            relay = activeRelay;
            key = activeKey;
            activeRelay = null;
            activeKey = "";
            relayHost = "";
            listenPort = 0;
        }
        if (relay != null && key != null && !key.isEmpty()) {
            try {
                deleteTunnel(relay, key, 3);
            } catch (IOException ignored) {
                // 释放失败时，下次申请会先查询或在配额已满时再删一次。
            }
        }
    }

    public static String generateApiKey() {
        SecureRandom random = new SecureRandom();
        char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        char[] out = new char[20];
        for (int i = 0; i < out.length; i++) {
            out[i] = alphabet[random.nextInt(alphabet.length)];
        }
        return new String(out);
    }

    public static boolean isApiKey(String key) {
        if (key == null) return false;
        int length = key.length();
        if (length < 8 || length > 64) return false;
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean upper = c >= 'A' && c <= 'Z';
            boolean lower = c >= 'a' && c <= 'z';
            if (!digit && !upper && !lower) return false;
        }
        return true;
    }

    public static String canonicalRelay(String raw) {
        Relay relay = parseRelay(raw);
        if (relay.apiPort() == API_PORT) return relay.host();
        return relay.host() + ":" + relay.apiPort();
    }

    public static Relay parseRelay(String raw) {
        String value = raw == null || raw.isBlank() ? DEFAULT_RELAY : raw;
        String[] parts = splitAddress(value, false, API_PORT);
        return new Relay(parts[0], Integer.parseInt(parts[1]));
    }

    /** 房客粘贴的公网地址。格式不对时返回 null，不发起连接。 */
    public static Endpoint publicEndpoint(String raw) {
        try {
            String[] parts = splitAddress(raw, true, -1);
            return new Endpoint(parts[0], Integer.parseInt(parts[1]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 只接受本机或局域网目标。会把 localhost 收成 127.0.0.1。 */
    public static Endpoint localTarget(String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("本机端口无效");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("只能把隧道转到本机或局域网地址");
        }
        String trimmed = host.trim();
        if ("localhost".equalsIgnoreCase(trimmed) || "::1".equals(trimmed)) {
            return new Endpoint("127.0.0.1", port);
        }
        byte[] ip = ipv4(trimmed);
        if (ip == null || !isLanOrLoopback(ip)) {
            throw new IllegalArgumentException("只能把隧道转到本机或局域网地址");
        }
        return new Endpoint(trimmed, port);
    }

    /** 从 {@code {"listenPort":N}} 或 {@code {"tunnels":[{"listenPort":N}]}} 取出端口。 */
    public static int parseListenPort(String body) {
        if (body == null || body.isBlank()) return 0;
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) return 0;
            JsonObject object = element.getAsJsonObject();
            int direct = portOf(object);
            if (direct > 0) return direct;
            if (!object.has("tunnels") || !object.get("tunnels").isJsonArray()) return 0;
            for (JsonElement item : object.getAsJsonArray("tunnels")) {
                if (!item.isJsonObject()) continue;
                int port = portOf(item.getAsJsonObject());
                if (port > 0) return port;
            }
        } catch (RuntimeException ignored) {
            return 0;
        }
        return 0;
    }

    private void registerKey(Relay relay, String key) throws IOException {
        HttpResult result = request("POST", relay, "/apikey", null,
                "{\"apikey\":\"" + key + "\"}", null, 12);
        if (result.status == 200 || result.status == 409) return;
        throw new IOException("注册联机密钥失败：" + result.brief(key));
    }

    private int openTunnel(Relay relay, String key, int maxPlayers) throws IOException {
        int existing = existingPort(relay, key);
        if (existing > 0) return existing;
        String query = "maxPlayers=" + maxPlayers;
        HttpResult created = request("POST", relay, "/tunnels", query, null, key, 12);
        if (created.status == 429) {
            deleteTunnel(relay, key, 12);
            created = request("POST", relay, "/tunnels", query, null, key, 12);
        }
        if (created.status < 200 || created.status >= 300) {
            throw new IOException("申请隧道失败：" + created.brief(key));
        }
        int port = parseListenPort(created.body);
        if (port > 0) return port;
        port = existingPort(relay, key);
        if (port > 0) return port;
        throw new IOException("中继没有返回隧道端口");
    }

    private int existingPort(Relay relay, String key) throws IOException {
        HttpResult result = request("GET", relay, "/tunnels", null, null, key, 12);
        if (result.status != 200) return 0;
        return parseListenPort(result.body);
    }

    private void deleteTunnel(Relay relay, String key, int timeoutSeconds) throws IOException {
        HttpResult result = request("DELETE", relay, "/tunnels", null, null, key, timeoutSeconds);
        if (result.status >= 400 && result.status != 404 && result.status != 429) {
            throw new IOException("释放隧道失败：" + result.status);
        }
    }

    private HttpResult request(String method, Relay relay, String path, String query,
                               String body, String key, int timeoutSeconds) throws IOException {
        String url = "http://" + relay.host() + ":" + relay.apiPort() + path;
        if (query != null && !query.isEmpty()) url += "?" + query;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        if (key != null && !key.isEmpty()) builder.header("Authorization", key);
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<InputStream> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes((1 << 20) + 1);
                if (bytes.length > (1 << 20)) throw new IOException("中继响应过大");
                String payload = new String(bytes, StandardCharsets.UTF_8);
                return new HttpResult(response.statusCode(), payload);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("已取消", e);
        } catch (IOException e) {
            if ("已取消".equals(e.getMessage())) throw e;
            throw new IOException("无法连接中继 " + relay.host() + ":" + relay.apiPort(), e);
        }
    }

    private void startWorkers(int count) {
        if (!live) return;
        ExecutorService pool = Executors.newFixedThreadPool(count, runnable -> {
            Thread thread = new Thread(runnable, "pmcl-redstone");
            thread.setDaemon(true);
            return thread;
        });
        workers = pool;
        if (!live) {
            pool.shutdownNow();
            return;
        }
        for (int i = 0; i < count; i++) {
            if (!live) break;
            pool.execute(this::tunnelLoop);
        }
    }

    private void stopWorkers() {
        ExecutorService pool = workers;
        workers = null;
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        sockets.clear();
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void tunnelLoop() {
        while (live && !Thread.currentThread().isInterrupted()) {
            boolean served = false;
            try {
                served = serveOne();
            } catch (IOException ignored) {
                served = false;
            }
            if (!live || Thread.currentThread().isInterrupted()) return;
            if (served) continue;
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean serveOne() throws IOException {
        String host = relayHost;
        String key = activeKey;
        String targetHost = localHost;
        int targetPort = localPort;
        if (!live || host == null || host.isEmpty() || key == null || key.isEmpty() || targetPort < 1) {
            return false;
        }
        Socket relay = new Socket();
        sockets.add(relay);
        try {
            relay.connect(new InetSocketAddress(host, tunnelPort), DIAL_TIMEOUT_MS);
            relay.setTcpNoDelay(true);
            relay.getOutputStream().write((key + "\n").getBytes(StandardCharsets.UTF_8));
            relay.getOutputStream().flush();
            byte[] pending = readRelayPrefix(relay);
            if (!live) return false;
            if (pending.length == 0) {
                int first = relay.getInputStream().read();
                if (first < 0 || !live) return false;
                pending = new byte[] {(byte) first};
            }
            Socket local = new Socket();
            sockets.add(local);
            try {
                local.connect(new InetSocketAddress(targetHost, targetPort), DIAL_TIMEOUT_MS);
                local.setTcpNoDelay(true);
                local.getOutputStream().write(pending);
                local.getOutputStream().flush();
                pipe(relay, local);
                return true;
            } finally {
                sockets.remove(local);
                try {
                    local.close();
                } catch (IOException ignored) {
                }
            }
        } finally {
            sockets.remove(relay);
            try {
                relay.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 读中继握手。{@code OK} 开头的状态行会被吃掉；二进制的第一字节要原样交给 Minecraft。
     */
    static byte[] readRelayPrefix(Socket socket) throws IOException {
        socket.setSoTimeout(GREETING_TIMEOUT_MS);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        try {
            InputStream in = socket.getInputStream();
            int first = in.read();
            if (first < 0) throw new EOFException("中继关闭了连接");
            if (first != 'O' && first != 'E' && first != 'W') {
                return new byte[] {(byte) first};
            }
            line.write(first);
            while (line.size() < 128) {
                int value = in.read();
                if (value < 0) break;
                line.write(value);
                if (value == '\n') break;
            }
            return interpretStatus(line.toByteArray());
        } catch (SocketTimeoutException e) {
            if (line.size() == 0) return new byte[0];
            return interpretStatus(line.toByteArray());
        } finally {
            try {
                socket.setSoTimeout(0);
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] interpretStatus(byte[] raw) throws IOException {
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.toUpperCase(Locale.ROOT).startsWith("OK")) return new byte[0];
        boolean newline = false;
        for (byte value : raw) {
            if (value == '\n') newline = true;
        }
        if (newline && isPrintableAscii(raw)) {
            throw new IOException("中继拒绝了隧道连接：" + text);
        }
        return raw;
    }

    private static void pipe(Socket relay, Socket local) {
        Thread backward = new Thread(() -> copy(relay, local), "pmcl-redstone-down");
        backward.setDaemon(true);
        backward.start();
        copy(local, relay);
        try {
            backward.join(1_500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(Socket destination, Socket source) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = source.getInputStream();
            OutputStream out = destination.getOutputStream();
            int count;
            while ((count = in.read(buffer)) >= 0) {
                out.write(buffer, 0, count);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static String requireKey(String apiKey) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (!isApiKey(key)) throw new IllegalArgumentException("缺少联机密钥");
        return key;
    }

    private static int clampPlayers(int maxPlayers) {
        if (maxPlayers <= 0) return DEFAULT_MAX_PLAYERS;
        return Math.min(MAX_PLAYERS, maxPlayers);
    }

    private static int portOf(JsonObject object) {
        if (!object.has("listenPort") || !object.get("listenPort").isJsonPrimitive()) return 0;
        try {
            int port = object.get("listenPort").getAsInt();
            return port > 0 && port <= 65535 ? port : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String[] splitAddress(String raw, boolean portRequired, int defaultPort) {
        String value = raw == null ? "" : raw.trim();
        if (value.regionMatches(true, 0, "http://", 0, 7)) value = value.substring(7);
        else if (value.regionMatches(true, 0, "https://", 0, 8)) value = value.substring(8);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()
                || value.indexOf('/') >= 0
                || value.indexOf('@') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0
                || value.indexOf(' ') >= 0
                || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("中继地址无效。请填写主机名或 IP，例如 122.51.108.96");
        }
        String host;
        int port;
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            host = value.substring(0, colon);
            String portText = value.substring(colon + 1);
            if (portText.isEmpty() || !portText.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("中继地址无效。请填写主机名或 IP，例如 122.51.108.96");
            }
            port = Integer.parseInt(portText);
        } else if (portRequired) {
            throw new IllegalArgumentException("请填写带端口的公网地址，例如 122.51.108.96:12345");
        } else {
            host = value;
            port = defaultPort;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("中继地址无效。请填写主机名或 IP，例如 122.51.108.96");
        }
        validateHost(host);
        return new String[] {host, Integer.toString(port)};
    }

    private static void validateHost(String host) {
        if (host.isEmpty() || host.length() > 253) {
            throw new IllegalArgumentException("中继地址无效。请填写主机名或 IP，例如 122.51.108.96");
        }
        boolean numeric = true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) numeric = false;
        }
        if (numeric) {
            byte[] ip = ipv4(host);
            if (ip == null || isForbiddenRelay(ip)) {
                throw new IllegalArgumentException("中继地址无效。请填写主机名或 IP，例如 122.51.108.96");
            }
            return;
        }
        if (!HOSTNAME.matcher(host).matches()) {
            throw new IllegalArgumentException("中继地址无效。请填写主机名或 IP，例如 122.51.108.96");
        }
    }

    private static byte[] ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] ip = new byte[4];
        for (int i = 0; i < 4; i++) {
            int value;
            try {
                value = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (value < 0 || value > 255 || !Integer.toString(value).equals(parts[i])) return null;
            ip[i] = (byte) value;
        }
        return ip;
    }

    private static boolean isLanOrLoopback(byte[] ip) {
        int a = ip[0] & 0xff;
        int b = ip[1] & 0xff;
        if (a == 127 || a == 10) return true;
        if (a == 192 && b == 168) return true;
        return a == 172 && b >= 16 && b <= 31;
    }

    private static boolean isForbiddenRelay(byte[] ip) {
        int a = ip[0] & 0xff;
        int b = ip[1] & 0xff;
        if (a == 0) return true;
        if (a == 169 && b == 254) return true;
        return a >= 224;
    }

    private static boolean isPrintableAscii(byte[] data) {
        for (byte value : data) {
            int c = value & 0xff;
            if (c == '\r' || c == '\n' || c == '\t') continue;
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    private record HttpResult(int status, String body) {
        String brief(String key) {
            String text = body == null ? "" : body.replace('\n', ' ').trim();
            if (key != null && !key.isEmpty()) text = text.replace(key, "******");
            if (text.length() > 160) text = text.substring(0, 160);
            return text.isEmpty() ? Integer.toString(status) : status + " " + text;
        }
    }
}
