package com.pmcl.core.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 轻量级 pastebin 上传客户端，用于分享游戏日志。
 * <p>
 * 默认使用 paste.gg（无需 API Key，支持匿名上传）。
 * 上传后返回可直接访问的 URL，便于求助分享。
 * <p>
 * 网络容错：复用 {@link com.pmcl.core.download.DownloadManager#httpClient()} 的 OkHttpClient，
 * 自动应用用户代理配置。
 */
public final class PastebinClient {

    private static final String PASTE_GG_API = "https://paste.gg/api/accept";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private volatile OkHttpClient http;

    /** 默认构造：自建客户端（无代理） */
    public PastebinClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .readTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /** 复用外部 OkHttpClient（推荐）：自动应用代理配置与共享连接池 */
    public PastebinClient(OkHttpClient http) {
        this.http = http;
    }

    public void updateHttpClient(OkHttpClient http) {
        this.http = http;
    }

    /**
     * 异步上传文本到 paste.gg。
     *
     * @param content 日志文本
     * @param name    paste 名称（可为空）
     * @return CompletableFuture，成功时返回 paste URL，失败时异常完成
     */
    public CompletableFuture<String> uploadAsync(String content, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return upload(content, name);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 同步上传文本到 paste.gg。
     *
     * @param content 日志文本
     * @param name    paste 名称（可为空）
     * @return paste URL（如 https://paste.gg/u/anonymous/...）
     */
    public String upload(String content, String name) throws IOException {
        String safeName = (name == null || name.isBlank()) ? "PMCL Log" : name;
        // M55 修复：用 Gson 构建 JSON payload，替代手动转义
        JsonObject payloadObj = new JsonObject();
        payloadObj.addProperty("name", safeName);
        payloadObj.addProperty("visibility", "unlisted");
        JsonObject fileObj = new JsonObject();
        fileObj.addProperty("name", "log.txt");
        JsonObject contentObj = new JsonObject();
        contentObj.addProperty("format", "text");
        contentObj.addProperty("value", content);
        fileObj.add("content", contentObj);
        var filesArray = new com.google.gson.JsonArray();
        filesArray.add(fileObj);
        payloadObj.add("files", filesArray);
        String payload = new Gson().toJson(payloadObj);

        RequestBody body = RequestBody.create(payload, JSON);
        Request req = new Request.Builder()
                .url(PASTE_GG_API)
                .post(body)
                .header("Accept", "application/json")
                .header("User-Agent", "PMCL-Launcher/1.0")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("paste.gg HTTP " + resp.code());
            }
            String respBody = resp.body() != null ? resp.body().string() : "";
            // M55 修复：用 Gson 解析响应，替代手动字符串搜索
            String url = extractUrl(respBody);
            if (url == null) {
                throw new IOException("paste.gg 响应解析失败: " + truncate(respBody, 200));
            }
            return url;
        }
    }

    /** 从 paste.gg JSON 响应中提取 result.url 字段（M55: 改用 Gson 解析） */
    private static String extractUrl(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("result") || root.get("result").isJsonNull()) return null;
            JsonObject result = root.getAsJsonObject("result");
            if (!result.has("url") || result.get("url").isJsonNull()) return null;
            String url = result.get("url").getAsString();
            // paste.gg 返回的是相对路径 /u/anonymous/xxx，补全为完整 URL
            if (url.startsWith("/")) {
                return "https://paste.gg" + url;
            }
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
