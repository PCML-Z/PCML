package com.pmcl.core.gamecontent;

import com.pmcl.core.multiplayer.HiddenServerAddress;
import com.pmcl.core.nbt.NbtReader;
import com.pmcl.core.nbt.NbtTag;
import com.pmcl.core.nbt.NbtWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把收藏服务器写进当前实例的 {@code servers.dat}，游戏里的多人列表能直接看到。
 */
public final class GameServerList {

    private GameServerList() {}

    public static final class Address {
        private final String host;
        private final int port;

        Address(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() { return host; }
        public int getPort() { return port; }
    }

    /** 解析 {@code host}、{@code host:port} 或 {@code [ipv6]:port}。非法时返回 null。 */
    public static Address parse(String raw, int fallbackPort) {
        if (raw == null) return null;
        String text = stripHidden(raw.trim());
        if (text.isEmpty() || text.length() > 1024) return null;
        if (fallbackPort < 1 || fallbackPort > 65535) fallbackPort = 25565;
        String host;
        int port = fallbackPort;
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end <= 1) return null;
            host = text.substring(1, end);
            String rest = text.substring(end + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":") || rest.length() < 2) return null;
                Integer parsed = parsePort(rest.substring(1));
                if (parsed == null) return null;
                port = parsed;
            }
        } else {
            int colon = text.lastIndexOf(':');
            if (colon > 0 && text.indexOf(':') == colon) {
                Integer parsed = parsePort(text.substring(colon + 1));
                if (parsed != null) {
                    host = text.substring(0, colon);
                    port = parsed;
                } else {
                    host = text;
                }
            } else {
                host = text;
            }
        }
        if (!isSafeHost(host)) return null;
        return new Address(host, port);
    }

    /**
     * 写入或更新 {@code gameDir/servers.dat}。同一地址已存在时只改名称。
     * 读失败时不覆盖原文件。
     */
    public static void add(Path gameDir, String name, String host, int port) throws IOException {
        add(gameDir, name, host, port, "");
    }

    public static void add(Path gameDir, String name, String host, int port, String token) throws IOException {
        Address address = parse(host, port);
        if (address == null || gameDir == null) throw new IOException("bad-server");
        Path dir = gameDir.toAbsolutePath().normalize();
        Path file = dir.resolve("servers.dat").normalize();
        if (!file.startsWith(dir)) throw new IOException("bad-server");
        String display = cleanName(name, address.host);
        String ip = HiddenServerAddress.cleanToken(token).isEmpty()
                ? datastoreIp(address.host, address.port)
                : HiddenServerAddress.serversDatAddress(address.host, address.port, token);
        boolean gzipped = true;
        NbtTag.CompoundTag root;
        NbtTag.ListTag servers;
        if (Files.isRegularFile(file)) {
            NbtReader.ReadResult read = NbtReader.readWithMeta(file);
            gzipped = read.gzipped;
            if (!(read.root instanceof NbtTag.CompoundTag compound)) throw new IOException("bad-servers");
            root = compound;
            NbtTag existing = root.get("servers");
            if (existing == null) {
                servers = new NbtTag.ListTag();
                servers.setListType(NbtTag.TYPE_COMPOUND);
                root.put("servers", servers);
            } else if (existing instanceof NbtTag.ListTag list) {
                servers = list;
            } else {
                throw new IOException("bad-servers");
            }
        } else {
            root = new NbtTag.CompoundTag();
            root.setName("");
            servers = new NbtTag.ListTag();
            servers.setListType(NbtTag.TYPE_COMPOUND);
            root.put("servers", servers);
        }
        for (NbtTag item : servers.getItems()) {
            if (!(item instanceof NbtTag.CompoundTag entry)) continue;
            if (!sameServer(stringOf(entry, "ip"), address.host, address.port)) continue;
            if (!display.equals(stringOf(entry, "name")) || !ip.equals(stringOf(entry, "ip"))) {
                entry.put("name", new NbtTag.StringTag(display));
                entry.put("ip", new NbtTag.StringTag(ip));
                NbtWriter.write(root, file, gzipped);
            }
            return;
        }
        NbtTag.CompoundTag entry = new NbtTag.CompoundTag();
        entry.put("name", new NbtTag.StringTag(display));
        entry.put("ip", new NbtTag.StringTag(ip));
        entry.put("acceptTextures", new NbtTag.ByteTag((byte) 1));
        servers.add(0, entry);
        NbtWriter.write(root, file, gzipped);
    }

    static String datastoreIp(String host, int port) {
        if (host.indexOf(':') >= 0) return "[" + host + "]:" + port;
        if (port == 25565) return host;
        return host + ":" + port;
    }

    private static boolean sameServer(String stored, String host, int port) {
        Address parsed = parse(stored, 25565);
        return parsed != null && parsed.host.equalsIgnoreCase(host) && parsed.port == port;
    }

    private static String stringOf(NbtTag.CompoundTag tag, String key) {
        NbtTag value = tag.get(key);
        return value instanceof NbtTag.StringTag text ? text.getValue() : "";
    }

    private static String cleanName(String name, String host) {
        String source = name == null ? "" : name.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < source.length() && sb.length() < 64; i++) {
            char c = source.charAt(i);
            if (c >= 32) sb.append(c);
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? host : cleaned;
    }

    private static Integer parsePort(String text) {
        if (text == null || text.isEmpty() || text.length() > 5) return null;
        int port = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return null;
            port = port * 10 + (c - '0');
        }
        return port >= 1 && port <= 65535 ? port : null;
    }

    /** 去掉 {@code id@} 和 {@code ?_id=}，剩下的仍按普通地址解析。 */
    private static String stripHidden(String text) {
        int at = text.indexOf('@');
        if (at > 0 && !text.startsWith("[")) text = text.substring(at + 1);
        int query = text.indexOf('?');
        if (query >= 0) text = text.substring(0, query);
        return text.trim();
    }

    private static boolean isSafeHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) return false;
        if (host.startsWith(".") || host.endsWith(".") || host.contains("..")) return false;
        boolean v6 = host.indexOf(':') >= 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            if (v6) ok = ok || c == ':';
            if (!ok) return false;
        }
        return true;
    }
}
