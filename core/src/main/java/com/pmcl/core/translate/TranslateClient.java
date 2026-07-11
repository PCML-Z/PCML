package com.pmcl.core.translate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.CurlFallback;
import com.pmcl.core.download.DownloadManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 翻译客户端：多源 fallback，零新增依赖。
 *
 * 翻译源优先级：
 * <ol>
 *   <li>Google Translate（translate.googleapis.com）：质量最佳，需代理或非 GFW 环境</li>
 *   <li>MyMemory（api.mymemory.translated.net）：免费无需 Key，中国大陆可直连</li>
 * </ol>
 * 两者均复用 DownloadManager 的 OkHttpClient（自动应用代理），
 * 并在 SSL 握手失败时 fallback 到系统 curl。
 */
public class TranslateClient {

    private static final String GOOGLE_ENDPOINT =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=";
    private static final String MYMEMORY_ENDPOINT =
            "https://api.mymemory.translated.net/get?q=";
    private static final String MYMEMORY_LANGPAIR = "&langpair=en|zh-CN";

    private static final int RETRY = 2;
    private static final long RETRY_BASE_MS = 500L;

    /** 清理 MyMemory 译文中的 HTML 标签和实体 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

    private volatile OkHttpClient http;

    public TranslateClient(DownloadManager downloads) {
        this.http = downloads.httpClient();
    }

    /** 用更新后的 OkHttpClient 重建引用（代理变更后调用） */
    public void updateHttpClient(OkHttpClient client) {
        this.http = client;
    }

    /**
     * 翻译文本（auto → zh-CN）。
     *
     * @param text 待翻译文本（可为多段，用换行分隔）
     * @return 翻译后的文本；若已是中文或翻译失败则返回原文
     */
    public String translate(String text) {
        if (text == null || text.isBlank()) return text;

        // 源1：Google Translate（有代理时质量最佳）
        String googleResult = tryGoogle(text);
        if (googleResult != null) return googleResult;

        // 源2：MyMemory（中国大陆可直连）
        String myMemoryResult = tryMyMemory(text);
        if (myMemoryResult != null) return myMemoryResult;

        // 全部失败：返回原文
        return text;
    }

    /**
     * 异步翻译
     */
    public CompletableFuture<String> translateAsync(String text) {
        return CompletableFuture.supplyAsync(() -> translate(text));
    }

    /**
     * 批量翻译：并行翻译，保持顺序，单条失败不影响其他。
     * 并发度限制为 8，避免触发限流。
     */
    public CompletableFuture<java.util.List<String>> translateBatchAsync(java.util.List<String> texts) {
        if (texts.isEmpty()) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }
        java.util.List<CompletableFuture<String>> futures = new java.util.ArrayList<>();
        java.util.concurrent.Semaphore rateLimiter = new java.util.concurrent.Semaphore(8);
        for (String t : texts) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    rateLimiter.acquire();
                    return translate(t);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return t;
                } finally {
                    rateLimiter.release();
                }
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    java.util.List<String> results = new java.util.ArrayList<>(futures.size());
                    for (CompletableFuture<String> f : futures) {
                        results.add(f.join());
                    }
                    return results;
                });
    }

    // ===== 翻译源 =====

    /**
     * 尝试 Google Translate，失败返回 null。
     */
    private String tryGoogle(String text) {
        String url = GOOGLE_ENDPOINT + URLEncoder.encode(text, StandardCharsets.UTF_8);
        IOException last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try {
                String body = doGet(url);
                String result = parseGoogleResponse(body, text);
                if (result != null && !result.isBlank() && !result.equals(text)) {
                    return result;
                }
                return null; // 解析失败或结果为空
            } catch (IOException e) {
                last = e;
                // SSL 握手失败：立即 fallback 到 curl
                if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                    try {
                        String body = CurlFallback.getString(url);
                        String result = parseGoogleResponse(body, text);
                        if (result != null && !result.isBlank() && !result.equals(text)) {
                            return result;
                        }
                    } catch (IOException ce) {
                        last = ce;
                    }
                }
                try { Thread.sleep(RETRY_BASE_MS * (1L << attempt)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            }
        }
        // 所有重试失败后，最后尝试 curl
        if (CurlFallback.isAvailable()) {
            try {
                String body = CurlFallback.getString(url);
                String result = parseGoogleResponse(body, text);
                if (result != null && !result.isBlank() && !result.equals(text)) {
                    return result;
                }
            } catch (IOException ignored) { }
        }
        return null;
    }

    /**
     * 尝试 MyMemory 翻译，失败返回 null。
     * MyMemory 免费 API，无需 Key，中国大陆可直连。
     */
    private String tryMyMemory(String text) {
        String url = MYMEMORY_ENDPOINT + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + MYMEMORY_LANGPAIR;
        IOException last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try {
                String body = doGet(url);
                String result = parseMyMemoryResponse(body, text);
                if (result != null && !result.isBlank() && !result.equals(text)) {
                    return result;
                }
                return null;
            } catch (IOException e) {
                last = e;
                // SSL 握手失败：fallback 到 curl
                if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                    try {
                        String body = CurlFallback.getString(url);
                        String result = parseMyMemoryResponse(body, text);
                        if (result != null && !result.isBlank() && !result.equals(text)) {
                            return result;
                        }
                    } catch (IOException ce) {
                        last = ce;
                    }
                }
                try { Thread.sleep(RETRY_BASE_MS * (1L << attempt)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            }
        }
        // 最后 curl 尝试
        if (CurlFallback.isAvailable()) {
            try {
                String body = CurlFallback.getString(url);
                String result = parseMyMemoryResponse(body, text);
                if (result != null && !result.isBlank() && !result.equals(text)) {
                    return result;
                }
            } catch (IOException ignored) { }
        }
        return null;
    }

    // ===== 内部方法 =====

    private String doGet(String url) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "PMCL/1.0")
                .header("Accept", "application/json, */*")
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("翻译请求失败 code=" + resp.code());
            }
            if (resp.body() == null) throw new IOException("翻译响应体为空");
            return resp.body().string();
        }
    }

    /**
     * 解析 Google Translate 响应。
     * 格式：[[["translated","original",null,null,1],...],null,"en",...]
     */
    private String parseGoogleResponse(String body, String fallback) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) return null;
            var arr = root.getAsJsonArray();
            if (arr.isEmpty()) return null;
            var segments = arr.get(0);
            if (!segments.isJsonArray()) return null;

            StringBuilder sb = new StringBuilder();
            for (JsonElement seg : segments.getAsJsonArray()) {
                if (seg.isJsonArray() && seg.getAsJsonArray().size() > 0) {
                    JsonElement first = seg.getAsJsonArray().get(0);
                    if (first.isJsonNull() || !first.isJsonPrimitive()) continue;
                    sb.append(first.getAsString());
                }
            }
            String result = sb.toString();
            return result.isBlank() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 MyMemory 响应。
     * 格式：{"responseData":{"translatedText":"译文","match":1},...}
     * 译文可能含 HTML 标签（如 &lt;g id="1"&gt;），需清理。
     */
    private String parseMyMemoryResponse(String body, String fallback) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("responseData") || !obj.get("responseData").isJsonObject()) return null;
            JsonObject responseData = obj.getAsJsonObject("responseData");
            if (!responseData.has("translatedText")) return null;
            JsonElement textEl = responseData.get("translatedText");
            if (textEl.isJsonNull() || !textEl.isJsonPrimitive()) return null;
            String translated = textEl.getAsString();
            if (translated.isBlank()) return null;

            // 清理 HTML 标签和实体（MyMemory 译文常含 <g id="x"> 标签）
            translated = HTML_TAG.matcher(translated).replaceAll("");
            // 清理常见 HTML 实体
            translated = translated.replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&nbsp;", " ");
            // 压缩多余空格
            translated = MULTI_SPACE.matcher(translated).replaceAll(" ").trim();

            // MyMemory 偶尔返回 "MYMEMORY WARNING" 等错误信息
            if (translated.toUpperCase().contains("MYMEMORY WARNING")
                    || translated.toUpperCase().contains("INVALID")) {
                return null;
            }
            return translated.isBlank() ? null : translated;
        } catch (Exception e) {
            return null;
        }
    }
}
