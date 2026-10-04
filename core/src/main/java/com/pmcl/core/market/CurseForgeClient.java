package com.pmcl.core.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.version.ShaderLoaders;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CurseForge API 客户端。
 * <p>
 * 文档：https://docs.curseforge.com/
 * 端点基础：https://api.curseforge.com/v1
 * <p>
 * 注意：CurseForge 要求在请求头中携带 API Key（X-API-Key）。
 * 生产环境应通过环境变量或配置文件注入；此处允许构造时传入。
 * <p>
 * 网络容错：复用 DownloadManager 的 OkHttpClient（自动应用用户代理配置），
 * 内置 3 次重试（间隔 1s/2s/4s），针对 SSL 握手失败/网络抖动做容错。
 */
public final class CurseForgeClient implements ModMarketClient {

    private static final String BASE = "https://api.curseforge.com/v1";
    /** Minecraft 在 CurseForge 的 gameId 固定为 432 */
    private static final int MINECRAFT_GAME_ID = 432;

    /** 重试次数（总请求次数 = RETRY + 1） */
    private static final int RETRY = 3;
    /** 重试基础间隔（毫秒），实际为 base * 2^attempt */
    private static final long RETRY_BASE_MS = 1000L;

    private volatile OkHttpClient http;
    private final String apiKey;
    private final DownloadManager downloads;

    public CurseForgeClient(String apiKey, DownloadManager downloads) {
        this.apiKey = apiKey;
        this.downloads = downloads;
        this.http = downloads.httpClient();
    }

    /**
     * 更新 OkHttpClient 引用（用户在设置中修改代理后调用）。
     */
    public void updateHttpClient(OkHttpClient http) {
        this.http = http;
    }

    /** 向官方接口确认这把密钥。401/403 视为拒绝，不读取响应正文。 */
    public void ping() throws IOException {
        Request req = new Request.Builder()
                .url(BASE + "/games/" + MINECRAFT_GAME_ID)
                .header("X-API-Key", apiKey)
                .header("User-Agent", "PMCL/1.0")
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 401 || resp.code() == 403) throw new IOException("rejected");
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
        }
    }

    @Override
    public String source() { return "curseforge"; }

    @Override
    public CompletableFuture<List<ModProject>> search(String query, String gameVersion,
                                                     String loader, int limit) {
        return searchPage(new MarketSearchQuery().query(query).gameVersion(gameVersion)
                .loader(loader).limit(limit).projectType("mod"))
                .thenApply(MarketSearchPage::getItems);
    }

    /**
     * 获取 CurseForge 热门项目（模组 classId=6，按人气）。
     */
    @Override
    public CompletableFuture<List<ModProject>> popular(String gameVersion, String loader, int limit) {
        return searchPage(new MarketSearchQuery().gameVersion(gameVersion).loader(loader)
                .limit(limit).sort("downloads").projectType("mod"))
                .thenApply(MarketSearchPage::getItems);
    }

    @Override
    public CompletableFuture<MarketSearchPage> searchPage(MarketSearchQuery query) {
        MarketSearchQuery q = query != null ? query : new MarketSearchQuery();
        return CompletableFuture.supplyAsync(() -> doSearchPage(q));
    }

    private MarketSearchPage doSearchPage(MarketSearchQuery q) {
        int limit = q.getLimit();
        int offset = q.getOffset();
        Integer classId = classIdForType(q.getProjectType());
        if (classId == null) {
            int[] classIds = {6, 4471, 12, 6552};
            List<ModProject> merged = new ArrayList<>();
            int total = 0;
            RuntimeException last = null;
            int ok = 0;
            for (int cid : classIds) {
                try {
                    MarketSearchPage page = executeSearchPage(
                            buildSearchUrl(q, cid), offset, limit, false);
                    merged.addAll(page.getItems());
                    total = Math.max(total, page.getTotal());
                    ok++;
                } catch (RuntimeException e) {
                    last = e;
                }
            }
            if (ok == 0 && last != null) throw last;
            String sort = q.getSort() != null ? q.getSort().toLowerCase(java.util.Locale.ROOT) : "";
            if ("downloads".equals(sort)) {
                merged.sort((a, b) -> Long.compare(b.getDownloadCount(), a.getDownloadCount()));
            } else if ("updated".equals(sort) || "newest".equals(sort)) {
                merged.sort((a, b) -> Long.compare(b.getDateModified(), a.getDateModified()));
            }
            return new MarketSearchPage(merged, total, offset, limit);
        }
        return executeSearchPage(buildSearchUrl(q, classId), offset, limit, false);
    }

    private HttpUrl.Builder buildSearchUrl(MarketSearchQuery q, Integer classId) {
        HttpUrl parsed = HttpUrl.parse(BASE + "/mods/search");
        if (parsed == null) throw new RuntimeException("无效的 URL: " + BASE + "/mods/search");
        HttpUrl.Builder ub = parsed.newBuilder()
                .addQueryParameter("gameId", String.valueOf(MINECRAFT_GAME_ID))
                .addQueryParameter("searchFilter", q.getQuery())
                .addQueryParameter("pageSize", String.valueOf(q.getLimit()))
                .addQueryParameter("index", String.valueOf(q.getOffset()))
                .addQueryParameter("sortField", String.valueOf(sortFieldId(q.getSort())))
                .addQueryParameter("sortOrder", "desc");
        if (classId != null) {
            ub.addQueryParameter("classId", String.valueOf(classId));
        }
        boolean hasGv = q.getGameVersion() != null && !q.getGameVersion().isEmpty();
        if (hasGv) {
            ub.addQueryParameter("gameVersion", q.getGameVersion());
        }
        Integer loaderType = modLoaderTypeId(q.getLoader());
        if (shouldSendModLoaderType(q.getGameVersion(), q.getLoader()) && loaderType != null) {
            ub.addQueryParameter("modLoaderType", String.valueOf(loaderType));
        }
        return ub;
    }

    /** CurseForge：modLoaderType 必须搭配 gameVersion。 */
    static boolean shouldSendModLoaderType(String gameVersion, String loader) {
        return gameVersion != null && !gameVersion.isBlank()
                && modLoaderTypeId(loader) != null;
    }

    /** 执行搜索请求并解析为分页结果 */
    private MarketSearchPage executeSearchPage(HttpUrl.Builder ub, int offset, int limit,
                                               boolean filterToContentClasses) {
        Request req = new Request.Builder().url(ub.build())
                .header("X-API-Key", apiKey)
                .header("User-Agent", "PMCL/1.0")
                .get().build();
        Throwable last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "{}";
                if (!resp.isSuccessful()) {
                    if (resp.code() == 429) {
                        String retryAfter = resp.header("Retry-After");
                        long waitMs = 5000; // 默认 5s
                        if (retryAfter != null) {
                            try { waitMs = Long.parseLong(retryAfter) * 1000L; } catch (NumberFormatException ignored) {}
                        }
                        try { Thread.sleep(Math.min(waitMs, 60000)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("中断", ie); }
                        continue; // 重试
                    }
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonArray data = root.has("data") ? root.getAsJsonArray("data") : new JsonArray();
                int total = data.size();
                if (root.has("pagination") && root.get("pagination").isJsonObject()) {
                    JsonObject pg = root.getAsJsonObject("pagination");
                    if (pg.has("totalCount") && !pg.get("totalCount").isJsonNull()) {
                        total = pg.get("totalCount").getAsInt();
                    }
                }
                List<ModProject> result = new ArrayList<>();
                for (JsonElement e : data) {
                    if (e == null || !e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    int cid = o.has("classId") && !o.get("classId").isJsonNull()
                            ? o.get("classId").getAsInt() : 6;
                    if (filterToContentClasses && !isContentClassId(cid)) continue;
                    result.add(parseProject(o, cid));
                }
                return new MarketSearchPage(result, total, offset, limit);
            } catch (Throwable e) {
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
        throw new RuntimeException("CurseForge 搜索失败：" + friendlyError(msg), last);
    }

    private static ModProject parseProject(JsonObject o, int classId) {
        long downloads = o.has("downloadCount") ? o.get("downloadCount").getAsLong() : 0;
        String iconUrl = "";
        if (o.has("logo") && o.get("logo").isJsonObject()) {
            JsonObject logo = o.getAsJsonObject("logo");
            iconUrl = logo.has("thumbnailUrl") ? logo.get("thumbnailUrl").getAsString() : "";
            if (iconUrl.isEmpty() && logo.has("url")) iconUrl = logo.get("url").getAsString();
        }
        String website = safeStr(o, "websiteUrl");
        if (website.isEmpty() && o.has("links") && o.get("links").isJsonObject()) {
            website = safeStr(o.getAsJsonObject("links"), "websiteUrl");
        }
        String author = "";
        if (o.has("authors") && o.get("authors").isJsonArray() && o.getAsJsonArray("authors").size() > 0) {
            JsonElement a0 = o.getAsJsonArray("authors").get(0);
            if (a0 != null && a0.isJsonObject() && a0.getAsJsonObject().has("name")) {
                author = a0.getAsJsonObject().get("name").getAsString();
            }
        }
        return new ModProject(
                "curseforge",
                safeStr(o, "id"),
                o.has("slug") ? o.get("slug").getAsString() : "",
                safeStr(o, "name"),
                o.has("summary") ? o.get("summary").getAsString() : "",
                author,
                downloads,
                iconUrl,
                website
        ).categories(parseCategoryNames(o))
                .loaders(parseLoaderNames(o))
                .projectType(projectTypeFromClassId(classId))
                .dateModified(parseCfMillis(safeStr(o, "dateModified")))
                .cover(firstScreenshot(o))
                .follows(o.has("thumbsUpCount") && o.get("thumbsUpCount").isJsonPrimitive()
                        ? o.get("thumbsUpCount").getAsLong() : 0);
    }

    private static String firstScreenshot(JsonObject o) {
        if (!o.has("screenshots") || !o.get("screenshots").isJsonArray()) return "";
        JsonArray shots = o.getAsJsonArray("screenshots");
        if (shots.isEmpty() || !shots.get(0).isJsonObject()) return "";
        JsonObject shot = shots.get(0).getAsJsonObject();
        String thumb = safeStr(shot, "thumbnailUrl");
        return thumb.isEmpty() ? safeStr(shot, "url") : thumb;
    }

    private static List<String> parseCategoryNames(JsonObject o) {
        if (!o.has("categories") || !o.get("categories").isJsonArray()) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("categories")) {
            if (e == null || !e.isJsonObject()) continue;
            String name = safeStr(e.getAsJsonObject(), "slug");
            if (name.isEmpty()) name = safeStr(e.getAsJsonObject(), "name");
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private static List<String> parseLoaderNames(JsonObject o) {
        if (!o.has("latestFilesIndexes") || !o.get("latestFilesIndexes").isJsonArray()) {
            return Collections.emptyList();
        }
        List<String> loaders = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("latestFilesIndexes")) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject idx = e.getAsJsonObject();
            if (!idx.has("modLoader") || idx.get("modLoader").isJsonNull()) continue;
            String name = loaderName(idx.get("modLoader").getAsInt());
            if (name != null && !loaders.contains(name)) loaders.add(name);
        }
        return loaders;
    }

    static Integer classIdForType(String projectType) {
        if (projectType == null || projectType.isBlank()) return null;
        return switch (projectType.toLowerCase(java.util.Locale.ROOT)) {
            case "mod" -> 6;
            case "modpack" -> 4471;
            case "resourcepack", "resource_pack" -> 12;
            case "shader", "shaderpack" -> 6552;
            default -> null;
        };
    }

    static String projectTypeFromClassId(int classId) {
        return switch (classId) {
            case 12 -> "resourcepack";
            case 4471 -> "modpack";
            case 6552 -> "shader";
            default -> "mod";
        };
    }

    static boolean isContentClassId(int classId) {
        return classId == 6 || classId == 4471 || classId == 12 || classId == 6552;
    }

    static int sortFieldId(String sort) {
        if (sort == null || sort.isBlank() || "default".equalsIgnoreCase(sort)
                || "relevance".equalsIgnoreCase(sort)) {
            return 2; // Popularity
        }
        return switch (sort.toLowerCase(java.util.Locale.ROOT)) {
            case "downloads" -> 6;
            case "updated", "newest" -> 3;
            case "name" -> 4;
            default -> 2;
        };
    }

    static String loaderName(int modLoaderType) {
        return switch (modLoaderType) {
            case 1 -> "forge";
            case 4 -> "fabric";
            case 5 -> "quilt";
            case 6 -> "neoforge";
            default -> null;
        };
    }

    private static long parseCfMillis(String iso) {
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
            return "无法连接 api.curseforge.com（SSL 握手失败），请检查网络或在设置中配置代理。原始错误：" + rawMsg;
        }
        if (rawMsg.contains("timeout") || rawMsg.contains("timed out")) {
            return "连接 api.curseforge.com 超时，请检查网络或配置代理。原始错误：" + rawMsg;
        }
        if (rawMsg.contains("UnknownHost") || rawMsg.contains("Unable to resolve")) {
            return "无法解析 api.curseforge.com 域名，请检查网络或 DNS 设置。原始错误：" + rawMsg;
        }
        return rawMsg;
    }

    /** 用 slug 精确查找。没有同名项目时返回 null。 */
    public ModProject findBySlug(String slug, String projectType) {
        if (slug == null || slug.isBlank() || apiKey == null || apiKey.isEmpty()) return null;
        HttpUrl parsed = HttpUrl.parse(BASE + "/mods/search");
        if (parsed == null) throw new RuntimeException("无效的 URL");
        HttpUrl.Builder ub = parsed.newBuilder()
                .addQueryParameter("gameId", String.valueOf(MINECRAFT_GAME_ID))
                .addQueryParameter("slug", slug.trim())
                .addQueryParameter("pageSize", "5")
                .addQueryParameter("index", "0");
        Integer classId = classIdForType(projectType);
        if (classId != null) ub.addQueryParameter("classId", String.valueOf(classId));
        MarketSearchPage page = executeSearchPage(ub, 0, 5, false);
        for (ModProject project : page.getItems()) {
            if (slug.equalsIgnoreCase(project.getSlug())) return project;
        }
        return null;
    }

    /** 用数字项目 id 查找。不存在时返回 null。 */
    public ModProject findById(String id) {
        if (id == null || !id.matches("\\d{1,12}") || apiKey == null || apiKey.isEmpty()) return null;
        Request req = new Request.Builder()
                .url(BASE + "/mods/" + id)
                .header("X-API-Key", apiKey)
                .header("User-Agent", "PMCL/1.0")
                .get()
                .build();
        Throwable last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "{}";
                if (resp.code() == 404) return null;
                if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + ": " + body);
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (!root.has("data") || !root.get("data").isJsonObject()) return null;
                JsonObject data = root.getAsJsonObject("data");
                int cid = data.has("classId") && !data.get("classId").isJsonNull()
                        ? data.get("classId").getAsInt() : 6;
                return parseProject(data, cid);
            } catch (Throwable e) {
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
        throw new RuntimeException("CurseForge 获取项目失败：" + friendlyError(msg), last);
    }

    @Override
    public CompletableFuture<List<ModFile>> listFiles(String projectId) {
        return listFiles(projectId, null, null);
    }

    @Override
    public CompletableFuture<List<ModFile>> listFiles(String projectId, String gameVersion, String loader) {
        return CompletableFuture.supplyAsync(() -> {
            List<ModFile> result = new ArrayList<>();
            int index = 0;
            final int pageSize = 50;
            final int maxFiles = 500;
            Integer total = null;
            Exception last = null;
            Integer loaderType = modLoaderTypeId(loader);
            while (result.size() < maxFiles) {
                HttpUrl parsed = HttpUrl.parse(BASE + "/mods/" + projectId + "/files");
                if (parsed == null) throw new RuntimeException("无效的 URL");
                HttpUrl.Builder ub = parsed.newBuilder()
                        .addQueryParameter("index", String.valueOf(index))
                        .addQueryParameter("pageSize", String.valueOf(pageSize));
                if (safeQueryToken(gameVersion)) {
                    ub.addQueryParameter("gameVersion", gameVersion);
                }
                if (shouldSendModLoaderType(gameVersion, loader) && loaderType != null) {
                    ub.addQueryParameter("modLoaderType", String.valueOf(loaderType));
                }
                String url = ub.build().toString();
                Request req = new Request.Builder().url(url)
                        .header("X-API-Key", apiKey)
                        .header("User-Agent", "PMCL/1.0").get().build();
                boolean pageOk = false;
                for (int attempt = 0; attempt <= RETRY; attempt++) {
                    try (Response resp = http.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "{}";
                        if (resp.code() == 429) {
                            sleepRetryAfter(resp);
                            continue;
                        }
                        if (!resp.isSuccessful()) {
                            throw new IOException("HTTP " + resp.code() + ": " + body);
                        }
                        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                        JsonArray data = root.has("data") ? root.getAsJsonArray("data") : new JsonArray();
                        if (total == null && root.has("pagination") && root.get("pagination").isJsonObject()) {
                            JsonObject pg = root.getAsJsonObject("pagination");
                            if (pg.has("totalCount") && !pg.get("totalCount").isJsonNull()) {
                                total = pg.get("totalCount").getAsInt();
                            }
                        }
                        int added = 0;
                        for (JsonElement e : data) {
                            if (!e.isJsonObject()) continue;
                            result.add(parseCfFile(e.getAsJsonObject(), projectId));
                            added++;
                        }
                        pageOk = true;
                        if (added == 0) return result;
                        index += added;
                        break;
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
                if (!pageOk) {
                    if (!result.isEmpty()) return result;
                    String msg = last != null ? last.getMessage() : "未知错误";
                    throw new RuntimeException("CurseForge 拉取文件失败：" + friendlyError(msg), last);
                }
                if (total != null && index >= total) return result;
            }
            return result;
        });
    }

    /**
     * 按 fileId 批量查询（POST /mods/files）。整合包应走这条，而不是 listFiles 首页。
     * 每批最多 50 个 id。
     */
    public List<ModFile> getFilesByIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return Collections.emptyList();
        List<ModFile> out = new ArrayList<>();
        for (int i = 0; i < fileIds.size(); i += 50) {
            List<String> chunk = fileIds.subList(i, Math.min(i + 50, fileIds.size()));
            out.addAll(postFilesByIds(chunk));
        }
        return out;
    }

    /** GET /mods/{modId}/files/{fileId}，批量未命中时的单条回退。 */
    public ModFile getFile(String projectId, String fileId) {
        if (projectId == null || projectId.isEmpty() || fileId == null || fileId.isEmpty()) {
            return null;
        }
        String url = BASE + "/mods/" + projectId + "/files/" + fileId;
        Request req = new Request.Builder().url(url)
                .header("X-API-Key", apiKey)
                .header("User-Agent", "PMCL/1.0").get().build();
        Exception last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "{}";
                if (resp.code() == 429) {
                    sleepRetryAfter(resp);
                    continue;
                }
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonObject data = root.has("data") && root.get("data").isJsonObject()
                        ? root.getAsJsonObject("data") : null;
                if (data == null) return null;
                return parseCfFile(data, projectId);
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
        if (last != null) {
            System.err.println("[CurseForgeClient] getFile " + projectId + "/" + fileId
                    + " 失败: " + last.getMessage());
        }
        return null;
    }

    private List<ModFile> postFilesByIds(List<String> fileIds) {
        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String id : fileIds) {
            try {
                arr.add(Long.parseLong(id.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (arr.isEmpty()) return Collections.emptyList();
        body.add("fileIds", arr);
        okhttp3.RequestBody rb = okhttp3.RequestBody.create(body.toString(),
                okhttp3.MediaType.get("application/json; charset=utf-8"));
        Request req = new Request.Builder()
                .url(BASE + "/mods/files")
                .header("X-API-Key", apiKey)
                .header("User-Agent", "PMCL/1.0")
                .header("Accept", "application/json")
                .post(rb)
                .build();
        Exception last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                String json = resp.body() != null ? resp.body().string() : "{}";
                if (resp.code() == 429) {
                    sleepRetryAfter(resp);
                    continue;
                }
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + json);
                }
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonArray data = root.has("data") ? root.getAsJsonArray("data") : new JsonArray();
                List<ModFile> result = new ArrayList<>();
                for (JsonElement e : data) {
                    if (!e.isJsonObject()) continue;
                    result.add(parseCfFile(e.getAsJsonObject(), ""));
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
        throw new RuntimeException("CurseForge 批量文件查询失败："
                + (last != null ? friendlyError(last.getMessage()) : "未知错误"), last);
    }

    private void sleepRetryAfter(Response resp) throws IOException {
        String retryAfter = resp.header("Retry-After");
        long waitMs = 5000;
        if (retryAfter != null) {
            try { waitMs = Long.parseLong(retryAfter) * 1000L; } catch (NumberFormatException ignored) {}
        }
        try {
            Thread.sleep(Math.min(waitMs, 60_000));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("中断", ie);
        }
    }

    private ModFile parseCfFile(JsonObject o, String fallbackProjectId) {
        String projectId = safeStr(o, "modId");
        if (projectId.isEmpty()) projectId = fallbackProjectId != null ? fallbackProjectId : "";
        List<String> gameVersions = jsonArrToStrings(o, "gameVersions");
        List<String> loaders = new ArrayList<>();
        for (String s : gameVersions) {
            if (s.equalsIgnoreCase("Fabric") || s.equalsIgnoreCase("Forge")
                    || s.equalsIgnoreCase("Quilt") || s.equalsIgnoreCase("NeoForge")) {
                loaders.add(s.toLowerCase());
            }
            String shader = ShaderLoaders.normalize(s);
            if (!shader.isEmpty() && !loaders.contains(shader)) loaders.add(shader);
        }
        String releaseType = o.has("releaseType") && !o.get("releaseType").isJsonNull()
                ? cfReleaseType(o.get("releaseType").getAsInt()) : "release";
        String sha1 = "";
        if (o.has("hashes") && o.get("hashes").isJsonArray()) {
            for (JsonElement he : o.getAsJsonArray("hashes")) {
                if (!he.isJsonObject()) continue;
                JsonObject ho = he.getAsJsonObject();
                int algo = ho.has("algo") ? ho.get("algo").getAsInt() : -1;
                // HashAlgo: 1 = SHA-1，2 = MD5。禁止把 MD5 写进 sha512 字段，
                // downloadToVerified 会优先按 SHA-512 校验并直接失败。
                if (algo == 1) sha1 = safeStr(ho, "value");
            }
        }
        String fileName = safeStr(o, "fileName");
        String fileId = safeStr(o, "id");
        String downloadUrl = safeStr(o, "downloadUrl");
        if (downloadUrl.isEmpty() && !fileName.isEmpty()) {
            try {
                downloadUrl = forgeCdnUrl(Long.parseLong(fileId), fileName);
            } catch (NumberFormatException ignored) {
            }
        }
        return new ModFile(
                "curseforge", projectId,
                fileId,
                fileName,
                o.has("fileLength") && !o.get("fileLength").isJsonNull()
                        ? o.get("fileLength").getAsLong() : 0,
                downloadUrl,
                gameVersions, loaders, releaseType
        ).hashes(sha1, "").versionNumber(versionHintFromFileName(fileName));
    }

    private static String cfReleaseType(int code) {
        return switch (code) {
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> "release";
        };
    }

    private List<String> jsonArrToStrings(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            if (e != null && !e.isJsonNull() && e.isJsonPrimitive()) list.add(e.getAsString());
        }
        return list;
    }

    private static boolean safeQueryToken(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c == '&' || c == '?' || c == '#' || c < 32) return false;
        }
        return true;
    }

    /** Get Mod Files 的 modLoaderType 是整数枚举，不是搜索接口的 "Fabric" 字符串。 */
    static Integer modLoaderTypeId(String loader) {
        if (loader == null || loader.isBlank()) return null;
        return switch (loader.toLowerCase(java.util.Locale.ROOT)) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> null;
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if ("neoforge".equalsIgnoreCase(s)) return "NeoForge";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String forgeCdnUrl(long fileId, String fileName) {
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            encoded = fileName;
        }
        long n1 = fileId / 1000;
        long n2 = fileId % 1000;
        return "https://edge.forgecdn.net/files/" + n1 + "/" + n2 + "/" + encoded;
    }

    /** 从 jar 文件名取数字开头的末段，供 semver 比较；displayName 常含空格标题，不能当版本号。 */
    static String versionHintFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "";
        String base = fileName;
        int dot = base.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".jar");
        if (dot > 0) base = base.substring(0, dot);
        String[] parts = base.split("[-_]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            char c = p.charAt(0);
            if (c >= '0' && c <= '9') return p;
        }
        return "";
    }

    private static String safeStr(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = e.getAsJsonPrimitive();
            return p.isString() ? p.getAsString() : p.toString();
        }
        return "";
    }

    /**
     * 通过 Murmur2 哈希批量查询 mod 文件的 CurseForge projectID/fileID。
     * <p>
     * CurseForge fingerprint API：POST /mods/fingerprint，body 为 JSON 数组 of Murmur2 哈希值。
     * 返回每个哈希对应的 mod 信息（projectID、fileID、fileName 等）。
     * 用于 CurseForge 整合包在线导出时补全 manifest.files 数组。
     *
     * @param murmur2Hashes mod 文件的 Murmur2 哈希值列表
     * @return Map: murmur2 hash → CurseForge 文件信息 JsonObject（含 projectId、fileId、fileName）
     */
    public java.util.Map<Long, JsonObject> fingerprintLookup(java.util.List<Long> murmur2Hashes) {
        if (murmur2Hashes == null || murmur2Hashes.isEmpty()) {
            return Collections.emptyMap();
        }
        if (apiKey == null || apiKey.isEmpty()) {
            return Collections.emptyMap();
        }
        String url = BASE + "/mods/fingerprint";
        // body: {"fingerprints": [hash1, hash2, ...]}
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Long h : murmur2Hashes) arr.add(h);
        body.add("fingerprints", arr);

        okhttp3.RequestBody rb = okhttp3.RequestBody.create(body.toString(),
                okhttp3.MediaType.get("application/json; charset=utf-8"));
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json")
                .post(rb)
                .build();

        okhttp3.Response resp;
        try {
            resp = http.newCall(req).execute();
        } catch (IOException e) {
            throw new RuntimeException("CurseForge fingerprint 查询失败: " + e.getMessage(), e);
        }
        try (resp) {
            if (!resp.isSuccessful()) {
                throw new RuntimeException("CurseForge fingerprint 查询失败: HTTP " + resp.code());
            }
            String json = resp.body() != null ? resp.body().string() : "";
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            // 响应结构: {"data":{"exact_matches":[{...mod file info...}],...}}
            if (!root.has("data")) return Collections.emptyMap();
            JsonObject data = root.getAsJsonObject("data");
            if (!data.has("exact_matches")) return Collections.emptyMap();
            java.util.Map<Long, JsonObject> result = new java.util.HashMap<>();
            for (JsonElement e : data.getAsJsonArray("exact_matches")) {
                if (e.isJsonNull()) continue;
                JsonObject match = e.getAsJsonObject();
                // match.id = fileID, match.file.fingerprint = murmur2 hash
                // match.file.modId = projectID
                long fileId = match.has("id") ? match.get("id").getAsLong() : 0L;
                long modId = 0L;
                long fingerprint = 0L;
                String fileName = "";
                if (match.has("file") && !match.get("file").isJsonNull()) {
                    JsonObject file = match.getAsJsonObject("file");
                    modId = file.has("modId") ? file.get("modId").getAsLong() : 0L;
                    fingerprint = file.has("fileFingerprint")
                            ? file.get("fileFingerprint").getAsLong() : 0L;
                    fileName = file.has("fileName") ? file.get("fileName").getAsString() : "";
                }
                if (fingerprint != 0L && modId != 0L) {
                    JsonObject info = new JsonObject();
                    info.addProperty("projectID", modId);
                    info.addProperty("fileID", fileId);
                    info.addProperty("fileName", fileName);
                    result.put(fingerprint, info);
                }
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("CurseForge fingerprint 响应读取失败: " + e.getMessage(), e);
        }
    }
}
