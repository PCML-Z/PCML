package com.pmcl.core.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.CurlFallback;
import com.pmcl.core.download.DownloadManager;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Modrinth API 客户端。
 * <p>
 * 文档：https://docs.modrinth.com/
 * 端点基础：https://api.modrinth.com/v2
 * <p>
 * 网络容错：复用 DownloadManager 的 OkHttpClient（自动应用用户代理配置），
 * 内置 3 次重试（间隔 1s/2s/4s），针对 SSL 握手失败/网络抖动做容错。
 */
public final class ModrinthClient implements ModMarketClient {

    private static final String BASE = "https://api.modrinth.com/v2";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 重试次数（总请求次数 = RETRY + 1） */
    private static final int RETRY = 3;
    /** 重试基础间隔（毫秒），实际为 base * 2^attempt */
    private static final long RETRY_BASE_MS = 1000L;

    private volatile OkHttpClient http;
    private final DownloadManager downloads;

    public ModrinthClient(DownloadManager downloads) {
        this.downloads = downloads;
        this.http = downloads.httpClient();
    }

    /**
     * 更新 OkHttpClient 引用（用户在设置中修改代理后调用）。
     */
    public void updateHttpClient(OkHttpClient http) {
        this.http = http;
    }

    @Override
    public String source() { return "modrinth"; }

    @Override
    public CompletableFuture<List<ModProject>> search(String query, String gameVersion,
                                                     String loader, int limit) {
        return doSearch(query, gameVersion, loader, null, limit, null);
    }

    /**
     * 带分类过滤的关键字搜索：关键字与分类同时生效（AND 关系）。
     */
    @Override
    public CompletableFuture<List<ModProject>> search(String query, String gameVersion,
                                                     String loader, String category, int limit) {
        return doSearch(query, gameVersion, loader, category, limit, null);
    }

    /**
     * 按分类浏览项目（无关键字，按下载量排序）。
     * 用于「分类推荐」功能：用户点击分类标签后加载该分类下的热门项目。
     */
    @Override
    public CompletableFuture<List<ModProject>> searchByCategory(String category, String gameVersion,
                                                                 String loader, int limit) {
        return doSearch("", gameVersion, loader, category, limit, "downloads");
    }

    /**
     * 获取 Modrinth 热门项目（按下载量排序）。
     * 通过 sort=downloads 实现。
     */
    @Override
    public CompletableFuture<List<ModProject>> popular(String gameVersion, String loader, int limit) {
        return doSearch("", gameVersion, loader, null, limit, "downloads");
    }

    /**
     * 通用搜索：可指定排序方式与分类过滤。
     *
     * @param query       关键字，空字符串表示无关键字（仅按 sort 排序）
     * @param category    可选分类（如 "performance"/"technology"），null 表示不按分类过滤
     * @param sort        排序方式（relevance/downloads/updated/newest/follows），null 表示默认
     */
    private CompletableFuture<List<ModProject>> doSearch(String query, String gameVersion,
                                                        String loader, String category,
                                                        int limit, String sort) {
        return CompletableFuture.supplyAsync(() -> {
            HttpUrl parsed = HttpUrl.parse(BASE + "/search");
            if (parsed == null) throw new RuntimeException("无效的 URL: " + BASE + "/search");
            HttpUrl.Builder ub = parsed.newBuilder()
                    .addQueryParameter("query", query == null ? "" : query)
                    .addQueryParameter("limit", String.valueOf(limit))
                    .addQueryParameter("facets", buildFacets(gameVersion, loader, category));
            if (sort != null && !sort.isEmpty()) {
                ub.addQueryParameter("sort", sort);
            }
            Request req = new Request.Builder().url(ub.build())
                    .header("User-Agent", "PMCL/1.0").get().build();
            Exception last = null;
            for (int attempt = 0; attempt <= RETRY; attempt++) {
                try (Response resp = http.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        throw new IOException("HTTP " + resp.code() + ": " + body);
                    }
                    return parseSearchResult(body);
                } catch (Exception e) {
                    last = e;
                    // SSL 握手失败：立即 fallback 到 curl
                    if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                        try {
                            String body = CurlFallback.getString(ub.build().toString());
                            return parseSearchResult(body);
                        } catch (Exception curlEx) {
                            throw new RuntimeException("Modrinth 搜索失败（curl fallback 也失败）：" + curlEx.getMessage(), curlEx);
                        }
                    }
                    if (attempt < RETRY) {
                        try {
                            Thread.sleep(RETRY_BASE_MS * (1L << attempt));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            // 所有重试失败后，最后尝试 curl
            if (CurlFallback.isAvailable()) {
                try {
                    String body = CurlFallback.getString(ub.build().toString());
                    return parseSearchResult(body);
                } catch (Exception curlEx) {
                    throw new RuntimeException("Modrinth 搜索失败（curl fallback）：" + curlEx.getMessage(), curlEx);
                }
            }
            String msg = last != null ? last.getMessage() : "未知错误";
            throw new RuntimeException("Modrinth 搜索失败：" + friendlyError(msg), last);
        });
    }

    /**
     * 解析 Modrinth 搜索响应 JSON 为 ModProject 列表。
     */
    private List<ModProject> parseSearchResult(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray hits = root.has("hits") ? root.getAsJsonArray("hits") : new JsonArray();
        List<ModProject> result = new ArrayList<>();
        for (JsonElement e : hits) {
            JsonObject o = e.getAsJsonObject();
            result.add(new ModProject(
                    "modrinth",
                    safeStr(o, "project_id"),
                    o.has("slug") ? o.get("slug").getAsString() : "",
                    safeStr(o, "title"),
                    o.has("description") ? o.get("description").getAsString() : "",
                    o.has("author") ? o.get("author").getAsString() : "",
                    o.has("downloads") ? o.get("downloads").getAsLong() : 0,
                    o.has("icon_url") ? o.get("icon_url").getAsString() : "",
                    "https://modrinth.com/project/" + (o.has("slug") ? o.get("slug").getAsString() : safeStr(o, "project_id"))
            ));
        }
        return result;
    }

    /**
     * 生成友好的中文错误信息，提示用户可能的解决方案。
     */
    private String friendlyError(String rawMsg) {
        if (rawMsg == null) rawMsg = "";
        if (rawMsg.contains("handshake") || rawMsg.contains("SSL") || rawMsg.contains("TLS")
                || rawMsg.contains("reset") || rawMsg.contains("broken pipe")) {
            return "无法连接 api.modrinth.com（SSL 握手失败），请检查网络或在设置中配置代理。原始错误：" + rawMsg;
        }
        if (rawMsg.contains("timeout") || rawMsg.contains("timed out")) {
            return "连接 api.modrinth.com 超时，请检查网络或配置代理。原始错误：" + rawMsg;
        }
        if (rawMsg.contains("UnknownHost") || rawMsg.contains("Unable to resolve")) {
            return "无法解析 api.modrinth.com 域名，请检查网络或 DNS 设置。原始错误：" + rawMsg;
        }
        return rawMsg;
    }

    /**
     * Modrinth facets 数组字符串：[["project_type:mod"],["versions:1.20.4"],["categories:fabric"],["categories:performance"]]
     * 每个条件是一个独立的子数组，子数组之间是 AND 关系。
     * loader 与 category 都通过 categories 字段过滤（Modrinth 把加载器和功能分类统一归类为 category）。
     */
    private String buildFacets(String gameVersion, String loader, String category) {
        List<String> groups = new ArrayList<>();
        groups.add("[\"project_type:mod\"]");
        if (gameVersion != null && !gameVersion.isEmpty()) {
            groups.add("[\"versions:" + gameVersion + "\"]");
        }
        if (loader != null && !loader.isEmpty()) {
            groups.add("[\"categories:" + loader + "\"]");
        }
        if (category != null && !category.isEmpty()) {
            groups.add("[\"categories:" + category + "\"]");
        }
        return "[" + String.join(",", groups) + "]";
    }

    @Override
    public CompletableFuture<List<ModFile>> listFiles(String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            // Modrinth listFiles: GET /project/{id}/version
            String url = BASE + "/project/" + projectId + "/version";
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", "PMCL/1.0").get().build();
            Exception last = null;
            for (int attempt = 0; attempt <= RETRY; attempt++) {
                try (Response resp = http.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "[]";
                    if (!resp.isSuccessful()) {
                        throw new IOException("HTTP " + resp.code() + ": " + body);
                    }
                    JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
                    List<ModFile> result = new ArrayList<>();
                    for (JsonElement e : versions) {
                        JsonObject v = e.getAsJsonObject();
                        String versionId = safeStr(v, "id");
                        String name = safeStr(v, "name");
                        String versionType = v.has("version_type") ? v.get("version_type").getAsString() : "release";
                        List<String> gameVersions = jsonArrToStrings(v, "game_versions");
                        List<String> loaders = jsonArrToStrings(v, "loaders");

                        // 一个 version 可能含多个 file，通常取主 jar
                        if (v.has("files")) {
                            for (JsonElement f : v.getAsJsonArray("files")) {
                                JsonObject fo = f.getAsJsonObject();
                                result.add(new ModFile(
                                        "modrinth", projectId, versionId,
                                        safeStr(fo, "filename"),
                                        fo.has("size") ? fo.get("size").getAsLong() : 0,
                                        safeStr(fo, "url"),
                                        gameVersions, loaders, versionType
                                ));
                            }
                        }
                    }
                    return result;
                } catch (Exception e) {
                    last = e;
                    if (attempt < RETRY) {
                        try {
                            Thread.sleep(RETRY_BASE_MS * (1L << attempt));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            String msg = last != null ? last.getMessage() : "未知错误";
            throw new RuntimeException("Modrinth 拉取版本失败：" + friendlyError(msg), last);
        });
    }

    /**
     * 批量通过 SHA1 哈希查询文件对应的版本信息。
     * <p>
     * 调用 {@code POST /version_files}，返回每个哈希对应的版本（含 project_id / version_id / version_number）。
     * 未找到的哈希返回 null 值。
     *
     * @param sha1Hashes SHA1 哈希列表
     * @return 哈希 -> 版本信息 JsonObject 的映射（project_id, id, version_number, files）
     */
    public java.util.Map<String, JsonObject> batchCheckBySha1(List<String> sha1Hashes) {
        if (sha1Hashes == null || sha1Hashes.isEmpty()) return Collections.emptyMap();
        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String h : sha1Hashes) arr.add(h);
        body.add("hashes", arr);
        body.addProperty("algorithm", "sha1");

        Request req = new Request.Builder()
                .url(BASE + "/version_files")
                .header("User-Agent", "PMCL/1.0")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        Exception last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "{}";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + respBody);
                }
                JsonObject result = JsonParser.parseString(respBody).getAsJsonObject();
                java.util.Map<String, JsonObject> map = new java.util.HashMap<>();
                for (String hash : sha1Hashes) {
                    if (result.has(hash) && result.get(hash).isJsonObject()) {
                        map.put(hash, result.getAsJsonObject(hash));
                    }
                }
                return map;
            } catch (Exception e) {
                last = e;
                if (attempt < RETRY) {
                    try { Thread.sleep(RETRY_BASE_MS * (1L << attempt)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        String msg = last != null ? last.getMessage() : "未知错误";
        throw new RuntimeException("Modrinth 批量哈希查询失败：" + friendlyError(msg), last);
    }

    /**
     * 获取项目在指定游戏版本和加载器下的最新版本。
     * <p>
     * 调用 {@code GET /project/{id}/version?game_versions=[...]&loaders=[...]}，
     * 返回第一个（最新）版本的信息。gameVersion 或 loader 为空时不进行过滤。
     *
     * @param projectId   Modrinth 项目 ID
     * @param gameVersion Minecraft 版本（如 "1.20.1"），可为空
     * @param loader      加载器（如 "fabric"），可为空
     * @return 最新版本 JsonObject（含 id, version_number, files），无匹配返回 null
     */
    public JsonObject getLatestVersion(String projectId, String gameVersion, String loader) {
        StringBuilder url = new StringBuilder(BASE + "/project/").append(projectId).append("/version");
        List<String> params = new ArrayList<>();
        if (gameVersion != null && !gameVersion.isEmpty()) {
            params.add("game_versions=" + java.net.URLEncoder.encode("[\"" + gameVersion + "\"]",
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (loader != null && !loader.isEmpty()) {
            params.add("loaders=" + java.net.URLEncoder.encode("[\"" + loader + "\"]",
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!params.isEmpty()) {
            url.append("?").append(String.join("&", params));
        }

        Request req = new Request.Builder().url(url.toString())
                .header("User-Agent", "PMCL/1.0").get().build();
        Exception last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "[]";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
                if (versions.isEmpty()) return null;
                // 过滤 release 类型优先（除非只有 beta/alpha）
                JsonObject latest = null;
                for (JsonElement e : versions) {
                    JsonObject v = e.getAsJsonObject();
                    String vt = v.has("version_type") ? v.get("version_type").getAsString() : "release";
                    if ("release".equals(vt)) { latest = v; break; }
                    if (latest == null) latest = v;
                }
                return latest != null ? latest : versions.get(0).getAsJsonObject();
            } catch (Exception e) {
                last = e;
                if (attempt < RETRY) {
                    try { Thread.sleep(RETRY_BASE_MS * (1L << attempt)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        String msg = last != null ? last.getMessage() : "未知错误";
        throw new RuntimeException("Modrinth 获取最新版本失败：" + friendlyError(msg), last);
    }

    private List<String> jsonArrToStrings(JsonObject o, String key) {
        if (!o.has(key)) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) list.add(e.getAsString());
        return list;
    }

    private static String safeStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
