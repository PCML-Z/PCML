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
        return searchPage(new MarketSearchQuery().query(query).gameVersion(gameVersion)
                .loader(loader).limit(limit)).thenApply(MarketSearchPage::getItems);
    }

    @Override
    public CompletableFuture<List<ModProject>> search(String query, String gameVersion,
                                                     String loader, String category, int limit) {
        return doSearch(query, gameVersion, loader, category, limit, null, null, 0)
                .thenApply(MarketSearchPage::getItems);
    }

    @Override
    public CompletableFuture<List<ModProject>> searchByCategory(String category, String gameVersion,
                                                                 String loader, int limit) {
        return doSearch("", gameVersion, loader, category, limit, "downloads", "mod", 0)
                .thenApply(MarketSearchPage::getItems);
    }

    @Override
    public CompletableFuture<List<ModProject>> popular(String gameVersion, String loader, int limit) {
        return doSearch("", gameVersion, loader, null, limit, "downloads", "mod", 0)
                .thenApply(MarketSearchPage::getItems);
    }

    @Override
    public CompletableFuture<MarketSearchPage> searchPage(MarketSearchQuery query) {
        MarketSearchQuery q = query != null ? query : new MarketSearchQuery();
        boolean emptyQuery = q.getQuery() == null || q.getQuery().isBlank();
        String index = searchIndexParam(q.getSort(), emptyQuery);
        return doSearch(q.getQuery(), q.getGameVersion(), q.getLoader(), null,
                q.getLimit(), index, q.getProjectType(), q.getOffset());
    }

    /** Modrinth GET /v2/search 的排序参数名是 index，不是 sort。 */
    static String searchIndexParam(String sort, boolean emptyQuery) {
        if (sort == null || sort.isBlank() || "default".equalsIgnoreCase(sort)) {
            return emptyQuery ? "downloads" : "relevance";
        }
        return switch (sort.toLowerCase(java.util.Locale.ROOT)) {
            case "downloads" -> "downloads";
            case "follows" -> "follows";
            case "newest" -> "newest";
            case "updated" -> "updated";
            case "relevance" -> emptyQuery ? "downloads" : "relevance";
            default -> emptyQuery ? "downloads" : "relevance";
        };
    }

    private CompletableFuture<MarketSearchPage> doSearch(String query, String gameVersion,
                                                        String loader, String category,
                                                        int limit, String sort,
                                                        String projectType, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            HttpUrl parsed = HttpUrl.parse(BASE + "/search");
            if (parsed == null) throw new RuntimeException("无效的 URL: " + BASE + "/search");
            HttpUrl.Builder ub = parsed.newBuilder()
                    .addQueryParameter("query", query == null ? "" : query)
                    .addQueryParameter("limit", String.valueOf(Math.max(1, Math.min(limit, 50))))
                    .addQueryParameter("offset", String.valueOf(Math.max(0, offset)))
                    .addQueryParameter("facets", buildFacets(gameVersion, loader, category, projectType));
            if (sort != null && !sort.isEmpty()) {
                ub.addQueryParameter("index", sort);
            }
            Request req = new Request.Builder().url(ub.build())
                    .header("User-Agent", "PMCL/1.0").get().build();
            Exception last = null;
            for (int attempt = 0; attempt <= RETRY; attempt++) {
                long retryAfterMs = -1;
                try (Response resp = http.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        if (resp.code() == 429) {
                            retryAfterMs = parseRetryAfterMs(resp.header("Retry-After"));
                        }
                        throw new IOException("HTTP " + resp.code() + ": " + body);
                    }
                    return parseSearchPage(body, Math.max(0, offset), Math.max(1, Math.min(limit, 50)));
                } catch (Exception e) {
                    last = e;
                    // SSL 握手失败：立即 fallback 到 curl
                    if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                        try {
                            String body = CurlFallback.getString(ub.build().toString());
                            return parseSearchPage(body, Math.max(0, offset), Math.max(1, Math.min(limit, 50)));
                        } catch (Exception curlEx) {
                            throw new RuntimeException("Modrinth 搜索失败（curl fallback 也失败）：" + curlEx.getMessage(), curlEx);
                        }
                    }
                    if (attempt < RETRY) {
                        long sleepMs = retryAfterMs > 0 ? retryAfterMs : RETRY_BASE_MS * (1L << attempt);
                        try {
                            Thread.sleep(sleepMs);
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
                    return parseSearchPage(body, Math.max(0, offset), Math.max(1, Math.min(limit, 50)));
                } catch (Exception curlEx) {
                    throw new RuntimeException("Modrinth 搜索失败（curl fallback）：" + curlEx.getMessage(), curlEx);
                }
            }
            String msg = last != null ? last.getMessage() : "未知错误";
            throw new RuntimeException("Modrinth 搜索失败：" + friendlyError(msg), last);
        });
    }

    private MarketSearchPage parseSearchPage(String body, int offset, int limit) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray hits = root.has("hits") ? root.getAsJsonArray("hits") : new JsonArray();
        int total = root.has("total_hits") && !root.get("total_hits").isJsonNull()
                ? root.get("total_hits").getAsInt() : hits.size();
        List<ModProject> result = new ArrayList<>();
        for (JsonElement e : hits) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            List<String> categories = jsonArrToStrings(o, "display_categories");
            if (categories.isEmpty()) categories = jsonArrToStrings(o, "categories");
            List<String> loaders = new ArrayList<>();
            for (String c : jsonArrToStrings(o, "categories")) {
                if (isLoaderTag(c) && !loaders.contains(c)) loaders.add(c);
            }
            String type = safeStr(o, "project_type");
            if (type.isEmpty()) type = "mod";
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
            ).categories(categories).loaders(loaders).projectType(type)
                    .dateModified(parseIsoMillis(safeStr(o, "date_modified")))
                    .cover(galleryCover(o))
                    .follows(o.has("follows") && o.get("follows").isJsonPrimitive()
                            ? o.get("follows").getAsLong() : 0));
        }
        return new MarketSearchPage(result, total, offset, limit);
    }

    /** 搜索结果里的封面：featured_gallery，否则 gallery 的第一张。 */
    static String galleryCover(JsonObject o) {
        if (o == null) return "";
        String featured = primitiveString(o, "featured_gallery");
        if (!featured.isEmpty()) return featured;
        if (!o.has("gallery") || !o.get("gallery").isJsonArray()) return "";
        String first = "";
        for (JsonElement e : o.getAsJsonArray("gallery")) {
            if (e == null || e.isJsonNull()) continue;
            if (e.isJsonPrimitive()) {
                String url = e.getAsString();
                if (url != null && !url.isBlank()) return url.trim();
            } else if (e.isJsonObject()) {
                JsonObject g = e.getAsJsonObject();
                String url = primitiveString(g, "url");
                if (url.isEmpty()) url = primitiveString(g, "raw_url");
                boolean marked = g.has("featured") && g.get("featured").isJsonPrimitive()
                        && g.get("featured").getAsBoolean();
                if (marked && !url.isEmpty()) return url;
                if (first.isEmpty()) first = url;
            }
        }
        return first;
    }

    private static String primitiveString(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return "";
        String value = o.get(key).getAsString();
        return value == null ? "" : value.trim();
    }

    private static boolean isLoaderTag(String c) {
        if (c == null) return false;
        String s = c.toLowerCase(java.util.Locale.ROOT);
        return s.equals("fabric") || s.equals("forge") || s.equals("quilt") || s.equals("neoforge")
                || s.equals("rift") || s.equals("liteloader")
                || s.equals("iris") || s.equals("optifine") || s.equals("canvas") || s.equals("vanilla");
    }

    private static long parseIsoMillis(String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
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
     * 解析 HTTP Retry-After 头为休眠毫秒数。
     * <p>
     * 支持两种格式：
     * <ul>
     *   <li>数字（秒）：如 "120" 表示 120 秒后重试</li>
     *   <li>HTTP-date：如 "Wed, 21 Oct 2025 07:28:00 GMT"</li>
     * </ul>
     * 返回值上限 60 秒，避免单次重试等待过久导致 UI 卡死。
     * 解析失败或 header 缺失时返回 -1，由调用方使用默认退避。
     */
    private static long parseRetryAfterMs(String header) {
        if (header == null || header.isEmpty()) return -1;
        try {
            long seconds = Long.parseLong(header.trim());
            return Math.min(seconds * 1000, 60_000);
        } catch (NumberFormatException e) {
            try {
                java.util.Date date = new java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH).parse(header.trim());
                long diff = date.getTime() - System.currentTimeMillis();
                return Math.max(1000, Math.min(diff, 60_000));
            } catch (Exception ignored) {
                return -1;
            }
        }
    }

    /**
     * Forbric 同时跑 Fabric、Forge、NeoForge，搜索时这三项是或关系。
     * 其它加载器仍是原来的一个分类。
     */
    static java.util.List<String> loaderCategories(String loader) {
        if (loader != null && ("forbric".equalsIgnoreCase(loader)
                || "ecxp-forbric".equalsIgnoreCase(loader))) {
            return java.util.List.of("fabric", "forge", "neoforge");
        }
        if (loader == null || loader.isEmpty()) return java.util.List.of();
        return java.util.List.of(loader);
    }

    /**
     * Modrinth facets 数组字符串：[["project_type:mod"],["versions:1.20.4"],["categories:fabric"],["categories:performance"]]
     * 每个条件是一个独立的子数组，子数组之间是 AND 关系。
     * loader 与 category 都通过 categories 字段过滤（Modrinth 把加载器和功能分类统一归类为 category）。
     */
    private String buildFacets(String gameVersion, String loader, String category, String projectType) {
        com.google.gson.JsonArray facets = new com.google.gson.JsonArray();
        com.google.gson.JsonArray typeGroup = new com.google.gson.JsonArray();
        if (projectType == null || projectType.isBlank()) {
            typeGroup.add("project_type:mod");
            typeGroup.add("project_type:modpack");
            typeGroup.add("project_type:resourcepack");
            typeGroup.add("project_type:shader");
        } else {
            typeGroup.add("project_type:" + projectType);
        }
        facets.add(typeGroup);
        if (gameVersion != null && !gameVersion.isEmpty()) {
            com.google.gson.JsonArray g = new com.google.gson.JsonArray();
            g.add("versions:" + gameVersion);
            facets.add(g);
        }
        java.util.List<String> loaderNames = loaderCategories(loader);
        if (!loaderNames.isEmpty()) {
            com.google.gson.JsonArray l = new com.google.gson.JsonArray();
            for (String name : loaderNames) l.add("categories:" + name);
            facets.add(l);
        }
        if (category != null && !category.isEmpty()) {
            com.google.gson.JsonArray c = new com.google.gson.JsonArray();
            c.add("categories:" + category);
            facets.add(c);
        }
        return facets.toString();
    }

    @Override
    public CompletableFuture<List<ModFile>> listFiles(String projectId) {
        return listFiles(projectId, null, null);
    }

    @Override
    public CompletableFuture<List<ModFile>> listFiles(String projectId, String gameVersion, String loader) {
        return CompletableFuture.supplyAsync(() -> {
            HttpUrl parsed = HttpUrl.parse(BASE);
            if (parsed == null || projectId == null || projectId.isBlank()) {
                throw new RuntimeException("无效的 URL");
            }
            HttpUrl.Builder ub = parsed.newBuilder()
                    .addPathSegment("project")
                    .addPathSegment(projectId)
                    .addPathSegment("version");
            if (safeFacetToken(gameVersion)) {
                ub.addQueryParameter("game_versions", "[\"" + gameVersion + "\"]");
            }
            if (safeFacetToken(loader)) {
                ub.addQueryParameter("loaders", "[\"" + loader.toLowerCase(java.util.Locale.ROOT) + "\"]");
            }
            String url = ub.build().toString();
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", "PMCL/1.0").get().build();
            Exception last = null;
            for (int attempt = 0; attempt <= RETRY; attempt++) {
                long retryAfterMs = -1;
                try (Response resp = http.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "[]";
                    if (!resp.isSuccessful()) {
                        if (resp.code() == 429) {
                            retryAfterMs = parseRetryAfterMs(resp.header("Retry-After"));
                        }
                        throw new IOException("HTTP " + resp.code() + ": " + body);
                    }
                    JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
                    List<ModFile> result = new ArrayList<>();
                    for (JsonElement e : versions) {
                        if (e == null || !e.isJsonObject()) continue;
                        try {
                            JsonObject v = e.getAsJsonObject();
                            String versionId = safeStr(v, "id");
                            String versionNumber = safeStr(v, "version_number");
                            String versionType = v.has("version_type") && v.get("version_type").isJsonPrimitive()
                                    ? v.get("version_type").getAsString() : "release";
                            List<String> gameVersions = jsonArrToStrings(v, "game_versions");
                            List<String> loaders = jsonArrToStrings(v, "loaders");
                            List<String> deps = parseModrinthDependencies(v);

                            JsonObject primaryFile = pickPrimaryFile(v);
                            if (primaryFile == null) continue;
                            String sha1 = "";
                            String sha512 = "";
                            if (primaryFile.has("hashes") && primaryFile.get("hashes").isJsonObject()) {
                                JsonObject h = primaryFile.getAsJsonObject("hashes");
                                sha1 = safeStr(h, "sha1");
                                sha512 = safeStr(h, "sha512");
                            }
                            long size = 0;
                            if (primaryFile.has("size") && primaryFile.get("size").isJsonPrimitive()) {
                                try {
                                    size = primaryFile.get("size").getAsLong();
                                } catch (RuntimeException ignored) {
                                    size = 0;
                                }
                            }
                            result.add(new ModFile(
                                    "modrinth", projectId, versionId,
                                    safeStr(primaryFile, "filename"),
                                    size,
                                    safeStr(primaryFile, "url"),
                                    gameVersions, loaders, versionType, deps
                            ).hashes(sha1, sha512).versionNumber(
                                    !versionNumber.isEmpty() ? versionNumber : safeStr(v, "name")));
                        } catch (RuntimeException ignored) {
                            // 单条版本字段异常时跳过，避免整份列表加载失败
                        }
                    }
                    return result;
                } catch (Exception e) {
                    last = e;
                    if (attempt < RETRY) {
                        long sleepMs = retryAfterMs > 0 ? retryAfterMs : RETRY_BASE_MS * (1L << attempt);
                        try {
                            Thread.sleep(sleepMs);
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
            long retryAfterMs = -1;
            try (Response resp = http.newCall(req).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "{}";
                if (!resp.isSuccessful()) {
                    if (resp.code() == 429) {
                        retryAfterMs = parseRetryAfterMs(resp.header("Retry-After"));
                    }
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
                    long sleepMs = retryAfterMs > 0 ? retryAfterMs : RETRY_BASE_MS * (1L << attempt);
                    try { Thread.sleep(sleepMs); }
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
    /**
     * 获取项目元数据（含 game_versions / loaders 等）。
     *
     * @param projectId 项目 slug 或 id
     * @return 项目 JSON；不存在时返回 null
     */
    /** 用 slug 或项目 id 取回可打开的市场项目。不存在时返回 null。 */
    public ModProject loadProject(String idOrSlug) {
        JsonObject o = getProject(idOrSlug);
        if (o == null) return null;
        String type = safeStr(o, "project_type");
        if (type.isEmpty()) type = "mod";
        List<String> categories = jsonArrToStrings(o, "categories");
        List<String> loaders = jsonArrToStrings(o, "loaders");
        if (loaders.isEmpty()) {
            loaders = new ArrayList<>();
            for (String c : categories) {
                if (isLoaderTag(c) && !loaders.contains(c)) loaders.add(c);
            }
        }
        String slug = safeStr(o, "slug");
        String id = safeStr(o, "id");
        if (id.isEmpty()) id = slug.isEmpty() ? idOrSlug : slug;
        String siteSlug = slug.isEmpty() ? id : slug;
        return new ModProject(
                "modrinth",
                id,
                slug,
                safeStr(o, "title"),
                safeStr(o, "description"),
                "",
                o.has("downloads") && !o.get("downloads").isJsonNull() ? o.get("downloads").getAsLong() : 0,
                safeStr(o, "icon_url"),
                "https://modrinth.com/project/" + siteSlug
        ).categories(categories).loaders(loaders).projectType(type)
                .dateModified(parseIsoMillis(safeStr(o, "date_modified")));
    }

    public JsonObject getProject(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        Request req = new Request.Builder()
                .url(BASE + "/project/" + projectId)
                .header("User-Agent", "PMCL/1.0")
                .get()
                .build();
        Exception last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            long retryAfterMs = -1;
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "{}";
                if (resp.code() == 404) return null;
                if (!resp.isSuccessful()) {
                    if (resp.code() == 429) {
                        retryAfterMs = parseRetryAfterMs(resp.header("Retry-After"));
                    }
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                return JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                last = e;
                if (attempt < RETRY) {
                    long sleepMs = retryAfterMs > 0 ? retryAfterMs : RETRY_BASE_MS * (1L << attempt);
                    try { Thread.sleep(sleepMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        String msg = last != null ? last.getMessage() : "未知错误";
        throw new RuntimeException("Modrinth 获取项目失败：" + friendlyError(msg), last);
    }

    public JsonObject getLatestVersion(String projectId, String gameVersion, String loader) {
        List<JsonObject> versions = listVersions(projectId, gameVersion, loader);
        if (versions.isEmpty()) return null;
        // 过滤 release 类型优先（除非只有 beta/alpha）
        JsonObject latest = null;
        for (JsonObject v : versions) {
            String vt = v.has("version_type") ? v.get("version_type").getAsString() : "release";
            if ("release".equals(vt)) return v;
            if (latest == null) latest = v;
        }
        return latest != null ? latest : versions.get(0);
    }

    /**
     * 列出项目在指定游戏版本 / 加载器下的全部版本（按 Modrinth 返回顺序，通常新→旧）。
     * 项目不存在时返回空列表。
     */
    public List<JsonObject> listVersions(String projectId, String gameVersion, String loader) {
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
            long retryAfterMs = -1;
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "[]";
                if (resp.code() == 404) return Collections.emptyList();
                if (!resp.isSuccessful()) {
                    if (resp.code() == 429) {
                        retryAfterMs = parseRetryAfterMs(resp.header("Retry-After"));
                    }
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
                List<JsonObject> out = new ArrayList<>();
                for (JsonElement e : versions) {
                    if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject());
                }
                return out;
            } catch (Exception e) {
                last = e;
                if (attempt < RETRY) {
                    long sleepMs = retryAfterMs > 0 ? retryAfterMs : RETRY_BASE_MS * (1L << attempt);
                    try { Thread.sleep(sleepMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        String msg = last != null ? last.getMessage() : "未知错误";
        throw new RuntimeException("Modrinth 获取最新版本失败：" + friendlyError(msg), last);
    }

    private List<String> jsonArrToStrings(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            if (e != null && !e.isJsonNull() && e.isJsonPrimitive()) list.add(e.getAsString());
        }
        return list;
    }

    /** Modrinth dependencies 是对象数组：{ project_id, dependency_type }。 */
    private List<String> parseModrinthDependencies(JsonObject version) {
        if (!version.has("dependencies") || !version.get("dependencies").isJsonArray()) {
            return Collections.emptyList();
        }
        List<String> deps = new ArrayList<>();
        for (JsonElement e : version.getAsJsonArray("dependencies")) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            String type = safeStr(d, "dependency_type").toLowerCase(java.util.Locale.ROOT);
            if (!type.isEmpty() && !"required".equals(type)) continue;
            String pid = safeStr(d, "project_id");
            if (!pid.isEmpty() && !deps.contains(pid)) deps.add(pid);
        }
        return deps;
    }

    private JsonObject pickPrimaryFile(JsonObject version) {
        if (!version.has("files") || !version.get("files").isJsonArray()) return null;
        JsonArray files = version.getAsJsonArray("files");
        JsonObject first = null;
        for (JsonElement f : files) {
            if (!f.isJsonObject()) continue;
            JsonObject fo = f.getAsJsonObject();
            if (first == null) first = fo;
            if (fo.has("primary") && !fo.get("primary").isJsonNull()
                    && fo.get("primary").getAsBoolean()) {
                return fo;
            }
        }
        return first;
    }

    private static boolean safeFacetToken(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c == '[' || c == ']' || c == '&' || c == '?' || c < 32) return false;
        }
        return true;
    }

    private static String safeStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
