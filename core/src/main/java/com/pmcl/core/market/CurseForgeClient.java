package com.pmcl.core.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.DownloadManager;
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

    private OkHttpClient http;
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

    @Override
    public String source() { return "curseforge"; }

    @Override
    public CompletableFuture<List<ModProject>> search(String query, String gameVersion,
                                                     String loader, int limit) {
        return doSearch(query, gameVersion, loader, limit);
    }

    /**
     * 获取 CurseForge 热门项目。
     * 通过 sort=Popularity + 空 searchFilter 实现。
     */
    @Override
    public CompletableFuture<List<ModProject>> popular(String gameVersion, String loader, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            HttpUrl.Builder ub = HttpUrl.parse(BASE + "/mods/search").newBuilder()
                    .addQueryParameter("gameId", String.valueOf(MINECRAFT_GAME_ID))
                    .addQueryParameter("pageSize", String.valueOf(limit))
                    .addQueryParameter("sort", "Popularity");
            if (gameVersion != null && !gameVersion.isEmpty()) {
                ub.addQueryParameter("gameVersion", gameVersion);
            }
            if (loader != null && !loader.isEmpty()) {
                ub.addQueryParameter("modLoaderType", capitalize(loader));
            }
            return executeSearch(ub);
        });
    }

    private CompletableFuture<List<ModProject>> doSearch(String query, String gameVersion,
                                                        String loader, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            HttpUrl.Builder ub = HttpUrl.parse(BASE + "/mods/search").newBuilder()
                    .addQueryParameter("gameId", String.valueOf(MINECRAFT_GAME_ID))
                    .addQueryParameter("searchFilter", query == null ? "" : query)
                    .addQueryParameter("pageSize", String.valueOf(limit));
            if (gameVersion != null && !gameVersion.isEmpty()) {
                ub.addQueryParameter("gameVersion", gameVersion);
            }
            if (loader != null && !loader.isEmpty()) {
                // CurseForge modLoaderType 接受首字母大写：Fabric / Forge / Quilt
                ub.addQueryParameter("modLoaderType", capitalize(loader));
            }
            return executeSearch(ub);
        });
    }

    /** 执行搜索请求并解析响应为 ModProject 列表 */
    private List<ModProject> executeSearch(HttpUrl.Builder ub) {
        Request req = new Request.Builder().url(ub.build())
                .header("X-API-Key", apiKey)
                .header("User-Agent", "PMCL/1.0")
                .get().build();
        Exception last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "{}";
                if (!resp.isSuccessful()) {
                    throw new IOException("HTTP " + resp.code() + ": " + body);
                }
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonArray data = root.has("data") ? root.getAsJsonArray("data") : new JsonArray();
                List<ModProject> result = new ArrayList<>();
                for (JsonElement e : data) {
                    JsonObject o = e.getAsJsonObject();
                    long downloads = o.has("downloadCount")
                            ? o.get("downloadCount").getAsLong() : 0;
                    String iconUrl = "";
                    if (o.has("logo") && !o.get("logo").isJsonNull()) {
                        JsonObject logo = o.getAsJsonObject("logo");
                        iconUrl = logo.has("thumbnailUrl") ? logo.get("thumbnailUrl").getAsString() : "";
                    }
                    result.add(new ModProject(
                            "curseforge",
                            safeStr(o, "id"),
                            o.has("slug") ? o.get("slug").getAsString() : "",
                            safeStr(o, "name"),
                            o.has("summary") ? o.get("summary").getAsString() : "",
                            o.has("authors") && o.getAsJsonArray("authors").size() > 0
                                    ? (o.getAsJsonArray("authors").get(0).getAsJsonObject().has("name")
                                        ? o.getAsJsonArray("authors").get(0).getAsJsonObject().get("name").getAsString()
                                        : "")
                                    : "",
                            downloads,
                            iconUrl,
                            o.has("websiteUrl") ? o.get("websiteUrl").getAsString() : ""
                    ));
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
        throw new RuntimeException("CurseForge 搜索失败：" + friendlyError(msg), last);
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

    @Override
    public CompletableFuture<List<ModFile>> listFiles(String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = BASE + "/mods/" + projectId + "/files";
            Request req = new Request.Builder().url(url)
                    .header("X-API-Key", apiKey)
                    .header("User-Agent", "PMCL/1.0").get().build();
            Exception last = null;
            for (int attempt = 0; attempt <= RETRY; attempt++) {
                try (Response resp = http.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "{}";
                    if (!resp.isSuccessful()) {
                        throw new IOException("HTTP " + resp.code() + ": " + body);
                    }
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray data = root.has("data") ? root.getAsJsonArray("data") : new JsonArray();
                    List<ModFile> result = new ArrayList<>();
                    for (JsonElement e : data) {
                        JsonObject o = e.getAsJsonObject();
                        List<String> gameVersions = jsonArrToStrings(o, "gameVersions");
                        List<String> loaders = new ArrayList<>();
                        if (o.has("gameVersions")) {
                            for (JsonElement gv : o.getAsJsonArray("gameVersions")) {
                                String s = gv.getAsString();
                                if (s.equalsIgnoreCase("Fabric") || s.equalsIgnoreCase("Forge")
                                        || s.equalsIgnoreCase("Quilt")) {
                                    loaders.add(s.toLowerCase());
                                }
                            }
                        }
                        String releaseType = o.has("releaseType")
                                ? cfReleaseType(o.get("releaseType").getAsInt()) : "release";
                        result.add(new ModFile(
                                "curseforge", projectId,
                                safeStr(o, "id"),
                                safeStr(o, "fileName"),
                                o.has("fileLength") ? o.get("fileLength").getAsLong() : 0,
                                safeStr(o, "downloadUrl"),
                                gameVersions, loaders, releaseType
                        ));
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
            throw new RuntimeException("CurseForge 拉取文件失败：" + friendlyError(msg), last);
        });
    }

    private static String cfReleaseType(int code) {
        return switch (code) {
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> "release";
        };
    }

    private List<String> jsonArrToStrings(JsonObject o, String key) {
        if (!o.has(key)) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) list.add(e.getAsString());
        return list;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String safeStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
