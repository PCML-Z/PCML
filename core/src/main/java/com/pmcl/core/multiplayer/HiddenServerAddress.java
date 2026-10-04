package com.pmcl.core.multiplayer;

import java.nio.charset.StandardCharsets;

/**
 * Minecraft 26.4 快照 1 的隐身服务器地址。
 * <p>
 * 玩家输入的 {@code <id>@<host>} 会被游戏解析成握手主机字段 {@code <host>?_id=<id>}。
 * 键和值按 URI 规则百分号编码。整段主机字段最长 1024 字节。
 */
public final class HiddenServerAddress {

    private HiddenServerAddress() {}

    /** 去掉控制字符并限制长度。空串表示没有令牌。 */
    public static String cleanToken(String token) {
        if (token == null) return "";
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return "";
        int end = Math.min(trimmed.length(), 256);
        StringBuilder sb = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            char c = trimmed.charAt(i);
            if (c < 32 || c == 127) return "";
            sb.append(c);
        }
        return sb.toString();
    }

    /** 真正用来建 TCP 连接的主机名，不含令牌和端口。 */
    public static String connectHost(String address) {
        String text = address == null ? "" : address.trim();
        int at = text.indexOf('@');
        if (at > 0 && !text.startsWith("[")) {
            text = text.substring(at + 1).trim();
        }
        int query = text.indexOf('?');
        if (query >= 0) text = text.substring(0, query).trim();
        if (text.startsWith("[") && text.contains("]")) {
            return text.substring(1, text.indexOf(']'));
        }
        int colon = text.lastIndexOf(':');
        if (colon > 0 && text.indexOf(':') == colon && isPort(text.substring(colon + 1))) {
            return text.substring(0, colon);
        }
        return text;
    }

    /**
     * 写进握手包的主机字段。有令牌时是 {@code host?_id=} 的百分号编码形式。
     * 超过 1024 字节时退回纯主机名，避免发出会被协议截断的令牌。
     */
    public static String packetHost(String address, String token) {
        String host = connectHost(address);
        if (host.isEmpty() && address != null) host = address.trim();
        String id = tokenOf(address, token);
        if (id.isEmpty() || host.isEmpty()) return host;
        String joined = host + "?_id=" + encode(id);
        if (joined.getBytes(StandardCharsets.UTF_8).length > 1024) return host;
        return joined;
    }

    /**
     * 交给游戏 {@code --server} 和服务器列表的地址。
     * 普通令牌用官方简写 {@code <id>@<host>}，含保留字符时直接写 {@code host?_id=}。
     */
    public static String clientArg(String address, String token) {
        String host = connectHost(address);
        if (host.isEmpty() && address != null) host = address.trim();
        String id = tokenOf(address, token);
        if (id.isEmpty() || host.isEmpty()) return host;
        String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        if (plainId(id)) return id + "@" + shown;
        return shown + "?_id=" + encode(id);
    }

    /** {@code servers.dat} 里的地址。非默认端口写在查询串前面。 */
    public static String serversDatAddress(String host, int port, String token) {
        String arg = clientArg(host, token);
        if (port == 25565 || port <= 0 || port > 65535) return arg;
        int query = arg.indexOf('?');
        if (query >= 0) return arg.substring(0, query) + ":" + port + arg.substring(query);
        return arg + ":" + port;
    }

    /** 日志里去掉令牌，保留地址。 */
    public static String redact(String address) {
        if (address == null || address.isEmpty()) return address;
        int at = address.indexOf('@');
        if (at > 0 && !address.startsWith("[")) {
            return "***" + address.substring(at);
        }
        int id = address.indexOf("_id=");
        if (id >= 0) {
            int end = address.indexOf('&', id + 4);
            if (end < 0) end = address.length();
            return address.substring(0, id + 4) + "***" + address.substring(end);
        }
        return address;
    }

    private static String tokenOf(String address, String token) {
        String explicit = cleanToken(token);
        if (!explicit.isEmpty()) return explicit;
        return idFrom(address);
    }

    private static String idFrom(String address) {
        if (address == null) return "";
        String text = address.trim();
        int at = text.indexOf('@');
        if (at > 0 && !text.startsWith("[")) {
            return cleanToken(text.substring(0, at));
        }
        int query = text.indexOf('?');
        if (query < 0) return "";
        String[] parts = text.substring(query + 1).split("&", -1);
        for (String part : parts) {
            if (part.startsWith("_id=")) return cleanToken(decode(part.substring(4)));
        }
        return "";
    }

    private static boolean plainId(String id) {
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (!ok) return false;
        }
        return true;
    }

    static String encode(String value) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte raw : bytes) {
            int c = raw & 0xff;
            boolean plain = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (plain) sb.append((char) c);
            else sb.append(String.format("%02X", c).replaceFirst("^", "%"));
        }
        return sb.toString();
    }

    private static String decode(String value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isPort(String text) {
        if (text == null || text.isEmpty() || text.length() > 5) return false;
        int port = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
            port = port * 10 + (c - '0');
        }
        return port >= 1 && port <= 65535;
    }
}
