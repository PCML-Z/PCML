package com.pmcl.core.cursor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.i18n.I18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/** 本机 Cursor 连接的开关、端口和令牌。写在 {@code cursor-agent.json}，不进 preferences.json。 */
public final class CursorAgentStore {
    public static final int DEFAULT_PORT = 47321;
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private CursorAgentStore() {}

    public static final class Config {
        public final boolean enabled;
        public final int port;
        public final String token;
        public final boolean unreadable;

        Config(boolean enabled, int port, String token, boolean unreadable) {
            this.enabled = enabled;
            this.port = port;
            this.token = token == null ? "" : token;
            this.unreadable = unreadable;
        }

        @Override
        public String toString() {
            String tail = token.length() >= 4 ? token.substring(token.length() - 4) : "";
            return "CursorAgentConfig{enabled=" + enabled + ", port=" + port + ", tokenTail=" + tail + "}";
        }
    }

    public static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    public static int normalizePort(int port) {
        return port >= 1024 && port <= 65535 ? port : DEFAULT_PORT;
    }

    public static Path file(Path pmclHome) {
        return pmclHome.resolve("cursor-agent.json");
    }

    public static Path cursorMcpFile() {
        return Path.of(System.getProperty("user.home"), ".cursor", "mcp.json");
    }

    public static Config load(Path pmclHome) {
        synchronized (LOCK) {
            return loadUnlocked(pmclHome);
        }
    }

    public static Config setEnabled(Path pmclHome, boolean enabled) throws IOException {
        synchronized (LOCK) {
            Config current = loadUnlocked(pmclHome);
            String token = current.token;
            if (enabled && !validToken(token)) token = newToken();
            Config next = new Config(enabled, normalizePort(current.port), token, false);
            write(pmclHome, next);
            return next;
        }
    }

    public static Config rotateToken(Path pmclHome) throws IOException {
        synchronized (LOCK) {
            Config current = loadUnlocked(pmclHome);
            Config next = new Config(current.enabled, normalizePort(current.port), newToken(), false);
            write(pmclHome, next);
            return next;
        }
    }

    public static void savePort(Path pmclHome, int port) throws IOException {
        synchronized (LOCK) {
            Config current = loadUnlocked(pmclHome);
            write(pmclHome, new Config(current.enabled, normalizePort(port), current.token, false));
        }
    }

    public static JsonObject serverEntry(int port, String token) {
        if (!validToken(token) || port < 1024 || port > 65535) {
            throw new IllegalArgumentException("bad cursor entry");
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("url", "http://127.0.0.1:" + port + "/mcp");
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer " + token);
        entry.add("headers", headers);
        return entry;
    }

    public static String snippet(int port, String token) {
        return PRETTY.toJson(serverEntry(port, token));
    }

    /** 只替换 mcpServers.pmcl，其它服务器原样留下。符号链接不写。 */
    public static void writeMcpConfig(Path file, int port, String token) throws IOException {
        final JsonObject entry;
        try {
            entry = serverEntry(port, token);
        } catch (IllegalArgumentException e) {
            throw new IOException(I18n.t("settings.cursor.need_enable"));
        }
        if (file.getParent() == null) throw new IOException(I18n.t("settings.cursor.mcp_invalid"));
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(tmp)) {
            throw new IOException(I18n.t("settings.cursor.symlink"));
        }
        JsonObject root = new JsonObject();
        if (Files.isRegularFile(file)) {
            String text = Files.readString(file);
            if (!text.isBlank()) {
                JsonElement parsed;
                try {
                    parsed = JsonParser.parseString(text);
                } catch (RuntimeException e) {
                    throw new IOException(I18n.t("settings.cursor.mcp_invalid"));
                }
                if (!parsed.isJsonObject()) throw new IOException(I18n.t("settings.cursor.mcp_invalid"));
                root = parsed.getAsJsonObject();
            }
        }
        JsonElement existing = root.get("mcpServers");
        if (existing != null && !existing.isJsonNull() && !existing.isJsonObject()) {
            throw new IOException(I18n.t("settings.cursor.mcp_servers_invalid"));
        }
        JsonObject servers = existing != null && existing.isJsonObject()
                ? existing.getAsJsonObject() : new JsonObject();
        servers.add("pmcl", entry);
        root.add("mcpServers", servers);
        atomicWrite(file, PRETTY.toJson(root) + "\n", false);
    }

    private static Config loadUnlocked(Path pmclHome) {
        Path path = file(pmclHome);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            return new Config(false, DEFAULT_PORT, "", Files.isSymbolicLink(path));
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) return new Config(false, DEFAULT_PORT, "", true);
            JsonObject obj = parsed.getAsJsonObject();
            boolean enabled = obj.has("enabled") && obj.get("enabled").isJsonPrimitive()
                    && obj.get("enabled").getAsJsonPrimitive().isBoolean()
                    && obj.get("enabled").getAsBoolean();
            int port = DEFAULT_PORT;
            if (obj.has("port") && obj.get("port").isJsonPrimitive() && obj.get("port").getAsJsonPrimitive().isNumber()) {
                port = normalizePort(obj.get("port").getAsInt());
            }
            String token = "";
            if (obj.has("token") && obj.get("token").isJsonPrimitive() && obj.get("token").getAsJsonPrimitive().isString()) {
                String raw = obj.get("token").getAsString();
                if (validToken(raw)) token = raw;
            }
            if (enabled && !validToken(token)) enabled = false;
            return new Config(enabled, port, token, false);
        } catch (RuntimeException | IOException e) {
            return new Config(false, DEFAULT_PORT, "", true);
        }
    }

    private static void write(Path pmclHome, Config config) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", config.enabled);
        obj.addProperty("port", normalizePort(config.port));
        if (validToken(config.token)) obj.addProperty("token", config.token);
        atomicWrite(file(pmclHome), PRETTY.toJson(obj) + "\n", true);
    }

    private static void atomicWrite(Path path, String text, boolean ownerOnly) throws IOException {
        if (path.getParent() == null) throw new IOException(I18n.t("settings.cursor.mcp_invalid"));
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(tmp)) {
            throw new IOException(I18n.t("settings.cursor.symlink"));
        }
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (ownerOnly) ownerOnly(tmp);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (ownerOnly) ownerOnly(path);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {}
            throw e;
        }
    }

    private static void ownerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {}
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
