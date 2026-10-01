package com.pmcl.music.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pmcl.core.util.SsrfChecker;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/** 链接解析共用的主机判断、页面 JSON 截取和地址清洗。 */
final class SourceText {

    private SourceText() {}

    static boolean hostIs(String url, String... domains) {
        String host = host(url);
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        for (String domain : domains) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    static String host(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.trim();
        if (!u.regionMatches(true, 0, "http://", 0, 7)
                && !u.regionMatches(true, 0, "https://", 0, 8)) {
            u = "https://" + u;
        }
        try {
            return new URL(u).getHost();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /** 从 HTML 里截出 marker 之后第一段花括号配平的 JSON。 */
    static String balancedJson(String html, String marker) {
        if (html == null || marker == null) return null;
        int mark = html.indexOf(marker);
        if (mark < 0) return null;
        int start = html.indexOf('{', mark);
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return html.substring(start, i + 1);
            }
        }
        return null;
    }

    static String text(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive()) return "";
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String cleanUrl(String url) {
        if (url == null) return "";
        String u = url.trim()
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&");
        if (u.startsWith("//")) u = "https:" + u;
        return u;
    }

    static void requirePublicHttp(String url) throws IOException {
        String err = SsrfChecker.validate(url);
        if (err != null) throw new IOException(err);
    }
}
