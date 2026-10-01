package com.pmcl.core.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 GitHub Releases 读取更新公告，供关于页展示。
 * 只请求 {@code api.github.com} 上已校验的 owner/repo，不跟随重定向。
 */
public final class ReleaseAnnouncements {

    public static final int MAX_ITEMS = 8;
    static final int MAX_BODY_CHARS = 4_000;
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    private static final int HTTP_TIMEOUT_SECONDS = 15;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private ReleaseAnnouncements() {}

    public static final class Item {
        private final String version;
        private final String title;
        private final String body;
        private final String publishedAt;
        private final String url;
        private final boolean prerelease;

        Item(String version, String title, String body, String publishedAt, String url, boolean prerelease) {
            this.version = version;
            this.title = title;
            this.body = body;
            this.publishedAt = publishedAt;
            this.url = url;
            this.prerelease = prerelease;
        }

        public String getVersion() { return version; }
        public String getTitle() { return title; }
        public String getBody() { return body; }
        public String getPublishedAt() { return publishedAt; }
        public String getUrl() { return url; }
        public boolean isPrerelease() { return prerelease; }
    }

    public static List<Item> fetch(String repo) throws IOException {
        if (!GitHubReleaseSyncChecker.isValidGithubRepo(repo)) {
            throw new IOException("invalid-repo");
        }
        String api = "https://api.github.com/repos/" + repo.trim() + "/releases?per_page=" + MAX_ITEMS;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(api))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PMCL")
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        int status = resp.statusCode();
        if (status == 404) throw new IOException("not-found");
        if (status == 403 || status == 429) throw new IOException("rate-limit");
        if (status != 200) throw new IOException("http-" + status);
        String body = resp.body();
        if (body != null && body.length() > MAX_RESPONSE_CHARS) {
            throw new IOException("too-large");
        }
        return parse(body, repo.trim());
    }

    public static List<Item> parse(String json, String repo) {
        if (json == null || json.isBlank()) return List.of();
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!root.isJsonArray()) return List.of();
        JsonArray array = root.getAsJsonArray();
        List<Item> items = new ArrayList<>();
        for (JsonElement element : array) {
            if (items.size() >= MAX_ITEMS) break;
            if (!element.isJsonObject()) continue;
            JsonObject release = element.getAsJsonObject();
            if (bool(release, "draft")) continue;
            String version = versionOf(text(release, "tag_name"));
            if (version.isEmpty()) continue;
            String title = text(release, "name");
            if (title.isEmpty()) title = version;
            items.add(new Item(
                    version,
                    title,
                    plainBody(text(release, "body")),
                    formatDate(text(release, "published_at")),
                    safeReleaseUrl(text(release, "html_url"), repo),
                    bool(release, "prerelease")));
        }
        return List.copyOf(items);
    }

    static String versionOf(String tag) {
        if (tag == null) return "";
        String trimmed = tag.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    static String formatDate(String published) {
        if (published == null || published.isBlank()) return "";
        try {
            return OffsetDateTime.parse(published.trim()).toLocalDate().toString();
        } catch (RuntimeException e) {
            String raw = published.trim();
            return raw.length() >= 10 ? raw.substring(0, 10) : raw;
        }
    }

    static String safeReleaseUrl(String url, String repo) {
        if (url == null || repo == null || repo.isBlank()) return "";
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return "";
            if (uri.getUserInfo() != null) return "";
            int port = uri.getPort();
            if (port != -1 && port != 443) return "";
            String host = uri.getHost();
            if (host == null || !host.equalsIgnoreCase("github.com")) return "";
            String path = uri.getRawPath() == null ? "" : uri.getPath();
            String prefix = "/" + repo.trim() + "/releases/";
            if (!path.startsWith(prefix) || path.contains("..")) return "";
            if (uri.getQuery() != null || uri.getFragment() != null) return "";
            return "https://github.com" + path;
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String plainBody(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = raw.length() > 20_000 ? raw.substring(0, 20_000) : raw;
        text = stripMarkdownLinks(text.replace("\r\n", "\n").replace('\r', '\n'));
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String cleaned = line.replaceFirst("^#{1,6}\\s+", "").replace("**", "").replace("__", "");
            if (cleaned.isBlank()) {
                blank++;
                if (blank <= 1 && sb.length() > 0) sb.append('\n');
                continue;
            }
            blank = 0;
            if (sb.length() > 0) sb.append('\n');
            sb.append(cleaned.stripTrailing());
            if (sb.length() >= MAX_BODY_CHARS) break;
        }
        String out = sb.toString().trim();
        if (out.length() > MAX_BODY_CHARS) {
            out = out.substring(0, MAX_BODY_CHARS).trim() + "…";
        }
        return out;
    }

    private static String stripMarkdownLinks(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int bang = (text.charAt(i) == '!' && i + 1 < text.length() && text.charAt(i + 1) == '[') ? 1 : 0;
            if (text.charAt(i) == '[' || bang == 1) {
                int start = i + bang;
                int close = text.indexOf(']', start + 1);
                if (close > start && close - start < 300 && close + 1 < text.length()
                        && text.charAt(close + 1) == '(') {
                    int end = text.indexOf(')', close + 2);
                    if (end > close && end - close < 800) {
                        sb.append(text, start + 1, close);
                        i = end + 1;
                        continue;
                    }
                }
            }
            sb.append(text.charAt(i));
            i++;
        }
        return sb.toString();
    }

    private static String text(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return "";
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return false;
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() && value.getAsBoolean();
    }
}
