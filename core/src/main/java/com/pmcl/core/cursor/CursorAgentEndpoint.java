package com.pmcl.core.cursor;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** 校验本机来源和令牌，再把 JSON-RPC 交给 {@link CursorMcp}。失败响应不回显请求正文。 */
public final class CursorAgentEndpoint {
    public static final int MAX_BODY = 65536;

    private CursorAgentEndpoint() {}

    public static final class Reply {
        public final int status;
        public final byte[] body;

        Reply(int status, byte[] body) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
        }
    }

    public static boolean bearer(String header, String token) {
        if (!CursorAgentStore.validToken(token) || header == null) return false;
        if (header.length() != 7 + token.length()) return false;
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        byte[] got = header.substring(7).getBytes(StandardCharsets.UTF_8);
        byte[] expect = token.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(got, expect);
    }

    public static boolean localHost(String host, int port) {
        if (host == null || host.isBlank()) return true;
        String value = host.trim().toLowerCase(Locale.ROOT);
        String name = value;
        String portText = null;
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            name = value.substring(0, colon);
            portText = value.substring(colon + 1);
        }
        if (!name.equals("127.0.0.1") && !name.equals("localhost")) return false;
        return portText == null || portText.equals(Integer.toString(port));
    }

    public static boolean localOrigin(String origin) {
        if (origin == null || origin.isBlank()) return true;
        String value = origin.trim().toLowerCase(Locale.ROOT);
        String rest;
        if (value.startsWith("http://127.0.0.1")) rest = value.substring("http://127.0.0.1".length());
        else if (value.startsWith("http://localhost")) rest = value.substring("http://localhost".length());
        else return false;
        return rest.isEmpty() || rest.matches(":[0-9]{1,5}");
    }

    public static Reply handle(String method, String host, String origin, String authorization,
                               byte[] body, String token, int port, CursorAgentSession session) {
        if (method == null || !method.equalsIgnoreCase("POST")) return new Reply(405, new byte[0]);
        if (!localHost(host, port) || !localOrigin(origin)) return tiny(403, "local_only");
        if (!bearer(authorization, token)) return tiny(401, "unauthorized");
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > MAX_BODY) return tiny(413, "too_large");
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return new Reply(400, CursorMcp.error(null, -32700, "Parse error"));
        }
        if (!parsed.isJsonObject()) return new Reply(400, CursorMcp.error(null, -32600, "Invalid Request"));
        byte[] out = CursorMcp.dispatch(parsed.getAsJsonObject(), session);
        if (out == null) return new Reply(202, new byte[0]);
        return new Reply(200, out);
    }

    private static Reply tiny(int status, String code) {
        return new Reply(status, ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
