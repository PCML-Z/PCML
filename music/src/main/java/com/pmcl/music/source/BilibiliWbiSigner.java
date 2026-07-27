package com.pmcl.music.source;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * B站 WBI 签名：从 nav 取 img/sub key，按 mixin 表重排后 MD5 生成 w_rid。
 *
 * <p>算法见 bilibili-API-collect docs/misc/sign/wbi.md。
 */
public final class BilibiliWbiSigner {

    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    private final OkHttpClient client;
    private final String userAgent;
    private final String referer;
    private final AtomicReference<CachedKeys> cached = new AtomicReference<>();

    public BilibiliWbiSigner(OkHttpClient client, String userAgent, String referer) {
        this.client = client;
        this.userAgent = userAgent;
        this.referer = referer;
    }

    /** 为参数表追加 wts / w_rid，返回已签名的 query string（不含前导 ?） */
    public String signQuery(Map<String, String> params) throws IOException {
        CachedKeys keys = ensureKeys();
        String mixinKey = getMixinKey(keys.imgKey, keys.subKey);
        Map<String, String> signed = new TreeMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            signed.put(e.getKey(), filterValue(e.getValue()));
        }
        long wts = System.currentTimeMillis() / 1000L;
        signed.put("wts", Long.toString(wts));

        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> e : signed.entrySet()) {
            joiner.add(e.getKey() + "=" + encodeURIComponent(e.getValue()));
        }
        String query = joiner.toString();
        String wRid = md5Hex(query + mixinKey);
        return query + "&w_rid=" + wRid;
    }

    public Map<String, String> signParams(Map<String, String> params) throws IOException {
        String query = signQuery(params);
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return out;
    }

    /** 强制刷新 nav 密钥（签名失败时可调用） */
    public void invalidate() {
        cached.set(null);
    }

    private CachedKeys ensureKeys() throws IOException {
        CachedKeys hit = cached.get();
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.fetchedAtMs < 3600_000L) {
            return hit;
        }
        Request req = new Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/nav")
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            // nav 未登录也可能返回 code!=0，但仍可能带 data.wbi_img
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : null;
            if (data == null || !data.has("wbi_img")) {
                throw new IOException("B站 WBI: nav 未返回 wbi_img");
            }
            JsonObject wbi = data.getAsJsonObject("wbi_img");
            String imgUrl = wbi.has("img_url") ? wbi.get("img_url").getAsString() : "";
            String subUrl = wbi.has("sub_url") ? wbi.get("sub_url").getAsString() : "";
            String imgKey = extractKey(imgUrl);
            String subKey = extractKey(subUrl);
            if (imgKey.isEmpty() || subKey.isEmpty()) {
                throw new IOException("B站 WBI: 无法解析 img_key/sub_key");
            }
            CachedKeys fresh = new CachedKeys(imgKey, subKey, now);
            cached.set(fresh);
            return fresh;
        }
    }

    static String getMixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder key = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            key.append(s.charAt(MIXIN_KEY_ENC_TAB[i]));
        }
        return key.toString();
    }

    private static String extractKey(String url) {
        if (url == null || url.isBlank()) return "";
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String filterValue(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '!' || c == '\'' || c == '(' || c == ')' || c == '*') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** 等价 encodeURIComponent：空格 %20，十六进制大写 */
    static String encodeURIComponent(String s) {
        String enc = URLEncoder.encode(s, StandardCharsets.UTF_8);
        return enc.replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }

    static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static final class CachedKeys {
        final String imgKey;
        final String subKey;
        final long fetchedAtMs;

        CachedKeys(String imgKey, String subKey, long fetchedAtMs) {
            this.imgKey = imgKey;
            this.subKey = subKey;
            this.fetchedAtMs = fetchedAtMs;
        }
    }
}
