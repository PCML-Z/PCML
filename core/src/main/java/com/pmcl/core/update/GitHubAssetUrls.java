package com.pmcl.core.update;

import com.google.gson.JsonObject;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Release 文件的浏览器地址在 github.com，这台网络连上去会超时。
 * API 资产地址在 api.github.com，连得上，字节与浏览器地址相同。
 */
public final class GitHubAssetUrls {
    private GitHubAssetUrls() {}

    public static Map<String, String> index(JsonObject release) {
        Map<String, String> out = new HashMap<>();
        if (release == null || !release.has("assets") || !release.get("assets").isJsonArray()) {
            return Map.of();
        }
        for (var el : release.getAsJsonArray("assets")) {
            if (!el.isJsonObject()) continue;
            JsonObject asset = el.getAsJsonObject();
            String api = text(asset, "url");
            if (!isApiAsset(api)) continue;
            String browser = text(asset, "browser_download_url");
            if (browser.startsWith("https://")) out.put(browser, api);
            String name = text(asset, "name");
            if (!name.isEmpty()) out.put("name:" + name, api);
        }
        return Map.copyOf(out);
    }

    /** 有对应的 API 地址就用它，否则仍用原来的下载地址。 */
    public static String resolve(String downloadUrl, Map<String, String> index) {
        if (downloadUrl == null || downloadUrl.isBlank() || index == null || index.isEmpty()) {
            return downloadUrl;
        }
        String api = index.get(downloadUrl);
        if (api != null) return api;
        String name = fileName(downloadUrl);
        if (name.isEmpty()) return downloadUrl;
        api = index.get("name:" + name);
        return api != null ? api : downloadUrl;
    }

    public static boolean isApiAsset(String url) {
        if (url == null || !url.startsWith("https://api.github.com/repos/")) return false;
        try {
            URI uri = URI.create(url);
            if (!"api.github.com".equalsIgnoreCase(uri.getHost())) return false;
            String path = uri.getPath() == null ? "" : uri.getPath();
            return path.contains("/releases/assets/") && !path.endsWith("/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean allowedDownloadHost(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("api.github.com")
                || host.equals("github.com")
                || host.equals("objects.githubusercontent.com")
                || host.equals("release-assets.githubusercontent.com")
                || host.equals("github-releases.githubusercontent.com")
                || host.endsWith(".githubusercontent.com");
    }

    static String fileName(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isEmpty()) return "";
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return name.isBlank() ? "" : name;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
