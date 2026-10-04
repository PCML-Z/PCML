package com.pmcl.core.opanel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 连接用户自己服务器上已经在运行的 OPanel。只调用它的 HTTP 接口，不包含 OPanel 的代码。
 * 令牌存在 {@code opanel.json}，不写入 preferences.json。
 */
public final class OPanelClient {
    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");
    private static final int MAX_BODY = 1_000_000;
    private static final int MAX_COMMAND = 500;

    private final OkHttpClient http;
    private final Path home;

    public OPanelClient(OkHttpClient http, Path home) {
        this.http = http.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.home = home;
    }

    public Connection load() {
        return read(store());
    }

    public void save(String baseUrl, String token) throws IOException {
        String url = normalizeBaseUrl(baseUrl);
        String cleaned = checkToken(token);
        JsonObject object = new JsonObject();
        object.addProperty("baseUrl", url);
        object.addProperty("token", cleaned);
        Path file = store();
        Files.createDirectories(file.getParent());
        Files.writeString(file, object.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows 没有 POSIX 权限位
        }
    }

    public Snapshot overview() throws IOException {
        Connection connection = require();
        JsonObject info = get(connection, "/api/info/");
        JsonObject monitor = get(connection, "/api/monitor/");
        JsonObject players = get(connection, "/api/players/list");
        return parseOverview(info, monitor, players);
    }

    public void command(String command) throws IOException {
        postText(require(), "/api/terminal/", checkCommand(command));
    }

    public void control(String action) throws IOException {
        if (!"stop".equals(action) && !"reload".equals(action) && !"restart".equals(action)) {
            throw new OPanelException("rejected");
        }
        postText(require(), "/api/control/" + action, "");
    }

    public void kick(String uuid) throws IOException {
        postText(require(), "/api/players/kick?uuid=" + checkUuid(uuid), "");
    }

    public void ban(String uuid) throws IOException {
        postText(require(), "/api/players/ban?uuid=" + checkUuid(uuid), "");
    }

    public void whitelist(boolean enabled) throws IOException {
        postText(require(), "/api/whitelist/" + (enabled ? "enable" : "disable"), "");
    }

    static Snapshot parseOverview(JsonObject info, JsonObject monitor, JsonObject players) {
        JsonObject time = info.has("ingameTime") && info.get("ingameTime").isJsonObject()
                ? info.getAsJsonObject("ingameTime") : new JsonObject();
        List<Player> list = new ArrayList<>();
        if (players.has("players") && players.get("players").isJsonArray()) {
            for (JsonElement element : players.getAsJsonArray("players")) {
                if (!element.isJsonObject()) continue;
                JsonObject player = element.getAsJsonObject();
                String uuid = text(player, "uuid");
                String name = text(player, "name");
                if (uuid.isEmpty() || name.isEmpty()) continue;
                list.add(new Player(name, uuid, bool(player, "isOnline"), bool(player, "isOp"),
                        bool(player, "isBanned"), text(player, "gamemode"),
                        player.has("ping") && player.get("ping").isJsonPrimitive()
                                ? player.get("ping").getAsInt() : -1));
            }
        }
        return new Snapshot(
                motd(text(info, "motd")),
                (int) number(info, "port"),
                (int) number(info, "maxPlayerCount"),
                bool(info, "whitelist"),
                monitor.has("tps") ? number(monitor, "tps") : -1,
                time.has("mspt") ? number(time, "mspt") : -1,
                List.copyOf(list));
    }

    static String normalizeBaseUrl(String raw) throws OPanelException {
        String trimmed = raw == null ? "" : raw.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty() || trimmed.length() > 200) throw new OPanelException("bad_url");
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new OPanelException("bad_url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) throw new OPanelException("bad_url");
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null) {
            throw new OPanelException("bad_url");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new OPanelException("bad_url");
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) throw new OPanelException("bad_url");
        return uri.getPort() < 0
                ? scheme + "://" + uri.getHost()
                : scheme + "://" + uri.getHost() + ":" + uri.getPort();
    }

    static String checkToken(String token) throws OPanelException {
        String cleaned = token == null ? "" : token.trim();
        if (cleaned.length() != 50 || !cleaned.startsWith("o-")) throw new OPanelException("bad_token");
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c <= ' ' || c == 127) throw new OPanelException("bad_token");
        }
        return cleaned;
    }

    static String checkCommand(String command) throws OPanelException {
        String cleaned = command == null ? "" : command.trim();
        if (cleaned.startsWith("/")) cleaned = cleaned.substring(1).trim();
        if (cleaned.isEmpty() || cleaned.length() > MAX_COMMAND
                || cleaned.indexOf('\n') >= 0 || cleaned.indexOf('\r') >= 0 || cleaned.indexOf('\0') >= 0) {
            throw new OPanelException("rejected");
        }
        return cleaned;
    }

    static String checkUuid(String uuid) throws OPanelException {
        String cleaned = uuid == null ? "" : uuid.trim();
        if (cleaned.length() < 32 || cleaned.length() > 36) throw new OPanelException("rejected");
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '-';
            if (!hex) throw new OPanelException("rejected");
        }
        return cleaned;
    }

    private JsonObject get(Connection connection, String path) throws IOException {
        return exchange(connection, path, null, true);
    }

    private void postText(Connection connection, String path, String body) throws IOException {
        exchange(connection, path, body, true);
    }

    private JsonObject exchange(Connection connection, String path, String body, boolean retrySlash) throws IOException {
        String url = connection.baseUrl + path;
        Request.Builder builder = new Request.Builder().url(url)
                .header("Authorization", "Bearer " + connection.token)
                .header("User-Agent", "PMCL");
        if (body != null) builder.post(RequestBody.create(body, TEXT));
        try (Response response = http.newCall(builder.build()).execute()) {
            if (retrySlash && response.code() == 404 && !path.contains("?")) {
                String alternate = path.endsWith("/") ? path.substring(0, path.length() - 1) : path + "/";
                return exchange(connection, alternate, body, false);
            }
            String text = readBody(response.body());
            if (response.code() == 401) throw new OPanelException("bad_token");
            JsonObject object = parseObject(text);
            int code = object.has("code") && object.get("code").isJsonPrimitive()
                    ? object.get("code").getAsInt() : response.code();
            if (response.code() == 503 || code == 503) {
                String error = text(object, "error").toLowerCase(Locale.ROOT);
                throw new OPanelException(error.contains("mcp") ? "mcp_off" : "rejected", clip(text(object, "error")));
            }
            if (response.code() >= 400 || code >= 400) {
                throw new OPanelException("rejected", clip(text(object, "error")));
            }
            return object;
        } catch (OPanelException e) {
            throw e;
        } catch (IOException e) {
            throw new OPanelException("unreachable");
        } catch (IllegalArgumentException e) {
            throw new OPanelException("bad_url");
        }
    }

    private static String readBody(ResponseBody body) throws IOException {
        if (body == null) return "";
        if (body.contentLength() > MAX_BODY) throw new OPanelException("rejected");
        byte[] bytes = body.bytes();
        if (bytes.length > MAX_BODY) throw new OPanelException("rejected");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static JsonObject parseObject(String text) {
        if (text == null || text.isBlank()) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private Connection require() throws OPanelException {
        Connection connection = load();
        if (connection.baseUrl.isEmpty() || connection.token.isEmpty()) throw new OPanelException("not_configured");
        return connection;
    }

    private Path store() {
        return home.resolve("opanel.json");
    }

    private static Connection read(Path file) {
        if (!Files.isRegularFile(file)) return new Connection("", "");
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) return new Connection("", "");
            JsonObject object = parsed.getAsJsonObject();
            return new Connection(text(object, "baseUrl"), text(object, "token"));
        } catch (RuntimeException | IOException e) {
            return new Connection("", "");
        }
    }

    static String motd(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            String text = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            return text.replaceAll("§.", "").replace('\r', ' ').replace('\n', ' ').trim();
        } catch (IllegalArgumentException e) {
            return encoded.replace('\n', ' ').trim();
        }
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }

    private static double number(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return 0;
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String clip(String value) {
        String cleaned = value == null ? "" : value.replace('\n', ' ').trim();
        return cleaned.length() <= 160 ? cleaned : cleaned.substring(0, 160);
    }

    public static final class Connection {
        public final String baseUrl;
        public final String token;

        Connection(String baseUrl, String token) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.token = token == null ? "" : token;
        }
    }

    public static final class Player {
        public final String name;
        public final String uuid;
        public final boolean online;
        public final boolean op;
        public final boolean banned;
        public final String gamemode;
        public final int ping;

        Player(String name, String uuid, boolean online, boolean op, boolean banned, String gamemode, int ping) {
            this.name = name;
            this.uuid = uuid;
            this.online = online;
            this.op = op;
            this.banned = banned;
            this.gamemode = gamemode;
            this.ping = ping;
        }
    }

    public static final class Snapshot {
        public final String motd;
        public final int port;
        public final int maxPlayers;
        public final boolean whitelist;
        public final double tps;
        public final double mspt;
        public final List<Player> players;

        Snapshot(String motd, int port, int maxPlayers, boolean whitelist, double tps, double mspt,
                 List<Player> players) {
            this.motd = motd;
            this.port = port;
            this.maxPlayers = maxPlayers;
            this.whitelist = whitelist;
            this.tps = tps;
            this.mspt = mspt;
            this.players = players;
        }

        public int onlineCount() {
            int count = 0;
            for (Player player : players) if (player.online) count++;
            return count;
        }
    }
}
