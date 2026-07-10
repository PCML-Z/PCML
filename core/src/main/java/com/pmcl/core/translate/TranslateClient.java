package com.pmcl.core.translate;

import com.google.gson.JsonElement;
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

/**
 * 翻译客户端：复用 DownloadManager 的 OkHttpClient + curl fallback，零新增依赖。
 *
 * 使用 Google Translate 免费 endpoint（无需 API Key），GET 请求。
 * GFW 环境下 SSL 握手失败时自动 fallback 到系统 curl。
 */
public class TranslateClient {

    private static final String ENDPOINT =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=";

    private static final int RETRY = 2;
    private static final long RETRY_BASE_MS = 500L;

    private OkHttpClient http;

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

        String url = ENDPOINT + URLEncoder.encode(text, StandardCharsets.UTF_8);
        IOException last = null;

        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try {
                String body = doGet(url);
                return parseResponse(body, text);
            } catch (IOException e) {
                last = e;
                // SSL 握手失败：立即 fallback 到 curl
                if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                    try {
                        String body = CurlFallback.getString(url);
                        return parseResponse(body, text);
                    } catch (IOException ce) {
                        last = ce;
                    }
                }
                try { Thread.sleep(RETRY_BASE_MS * (1L << attempt)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return text; }
            }
        }

        // 所有重试失败后，最后尝试 curl
        if (CurlFallback.isAvailable()) {
            try {
                String body = CurlFallback.getString(url);
                return parseResponse(body, text);
            } catch (IOException ignored) { }
        }
        // 翻译失败：返回原文（不阻断 UI）
        return text;
    }

    /**
     * 异步翻译
     */
    public CompletableFuture<String> translateAsync(String text) {
        return CompletableFuture.supplyAsync(() -> translate(text));
    }

    /**
     * 批量翻译：保持顺序，单条失败不影响其他。
     */
    public CompletableFuture<java.util.List<String>> translateBatchAsync(java.util.List<String> texts) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.List<String> results = new java.util.ArrayList<>();
            for (String t : texts) {
                results.add(translate(t));
            }
            return results;
        });
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
            return resp.body().string();
        }
    }

    /**
     * 解析 Google Translate 响应。
     * 格式：[[["translated","original",null,null,1],...],null,"en",...]
     * 第一个数组包含翻译片段，每个片段的第一元素是译文。
     */
    private String parseResponse(String body, String fallback) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) return fallback;
            var arr = root.getAsJsonArray();
            if (arr.isEmpty()) return fallback;
            var segments = arr.get(0);
            if (!segments.isJsonArray()) return fallback;

            StringBuilder sb = new StringBuilder();
            for (JsonElement seg : segments.getAsJsonArray()) {
                if (seg.isJsonArray() && seg.getAsJsonArray().size() > 0) {
                    String translated = seg.getAsJsonArray().get(0).getAsString();
                    sb.append(translated);
                }
            }
            String result = sb.toString();
            return result.isBlank() ? fallback : result;
        } catch (Exception e) {
            return fallback;
        }
    }
}
