package com.pmcl.music.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快手音频源。页面里的 Apollo 状态或 visionVideoDetail 接口给出视频地址，播放端再抽音频。
 *
 * <p>支持 www.kuaishou.com/short-video、v.kuaishou.com 短链、分享页和 chenzhongtech 移动页。
 */
public final class KuaishouAudioSource implements AudioSource {

    static final String TYPE = "kuaishou";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.kuaishou.com/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern PHOTO_ID = Pattern.compile(
            "(?:short-video|fw/photo)/([0-9A-Za-z]+)"
                    + "|(?:[?&]shareObjectId=)([0-9A-Za-z]+)"
                    + "|\"photoId\"\\s*:\\s*\"([0-9A-Za-z]+)\"");
    private static final String DETAIL_QUERY = """
            query visionVideoDetail($photoId: String, $page: String) {
              visionVideoDetail(photoId: $photoId, page: $page) {
                author { name }
                photo {
                  caption
                  duration
                  coverUrl
                  photoUrl
                  photoH265Url
                  manifest {
                    adaptationSet {
                      representation { url avgBitrate qualityType }
                    }
                  }
                }
              }
            }
            """;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(20))
            .followRedirects(true)
            .build();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean matches(String url) {
        return SourceText.hostIs(url, "kuaishou.com", "gifshow.com", "chenzhongtech.com");
    }

    @Override
    public AudioStreamInfo resolve(String url) throws IOException {
        SourceText.requirePublicHttp(url);
        Fetch page = fetch(url.trim());
        Parsed parsed = parseBody(page.body);
        String photoId = firstId(page.finalUrl, url, page.body);
        if (parsed.mediaUrl() == null && photoId != null
                && !page.finalUrl.contains("/short-video/" + photoId)) {
            Fetch again = fetch("https://www.kuaishou.com/short-video/" + photoId);
            Parsed retry = parseBody(again.body);
            if (retry.mediaUrl() != null) parsed = retry;
        }
        if (parsed.mediaUrl() == null && photoId != null) {
            Parsed remote = fetchDetail(photoId);
            if (remote != null && remote.mediaUrl() != null) parsed = remote;
        }
        String media = parsed.mediaUrl();
        if (media == null || media.isBlank()) {
            throw new IOException("快手解析失败: 未找到可播放地址");
        }
        SourceText.requirePublicHttp(media);
        if (photoId == null) photoId = media;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", REFERER);
        headers.put("User-Agent", UA);
        return new AudioStreamInfo(
                parsed.title.isBlank() ? "快手" : parsed.title,
                parsed.author,
                parsed.durationMs,
                media,
                parsed.cover,
                TYPE,
                url,
                headers,
                photoId,
                media
        );
    }

    static String photoId(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = PHOTO_ID.matcher(text);
        if (!m.find()) return null;
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) return m.group(i);
        }
        return null;
    }

    static Parsed parseBody(String body) {
        if (body == null || body.isBlank()) return new Parsed();
        String json = SourceText.balancedJson(body, "window.__APOLLO_STATE__");
        if (json == null && body.trim().startsWith("{")) json = body;
        if (json != null) {
            try {
                Parsed parsed = parseTree(JsonParser.parseString(json));
                if (parsed.mediaUrl() != null) return parsed;
            } catch (RuntimeException ignored) {
                // 页面状态不是合法 JSON 时再按字段兜底
            }
        }
        Matcher m = Pattern.compile("\"photoUrl\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (m.find()) {
            Parsed parsed = new Parsed();
            parsed.photoUrl = SourceText.cleanUrl(m.group(1));
            String id = photoId(body);
            if (id != null) parsed.title = id;
            return parsed;
        }
        return new Parsed();
    }

    static Parsed parseTree(JsonElement root) {
        Parsed parsed = new Parsed();
        walk(root, parsed, 0);
        return parsed;
    }

    private static void walk(JsonElement el, Parsed parsed, int depth) {
        if (el == null || el.isJsonNull() || depth > 24) return;
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) walk(child, parsed, depth + 1);
            return;
        }
        if (!el.isJsonObject()) return;
        JsonObject obj = el.getAsJsonObject();
        String photoUrl = SourceText.text(obj, "photoUrl");
        if (!photoUrl.isBlank() && parsed.photoUrl == null) parsed.photoUrl = photoUrl;
        String h265 = SourceText.text(obj, "photoH265Url");
        if (!h265.isBlank() && parsed.h265Url == null) parsed.h265Url = h265;
        String caption = SourceText.text(obj, "caption");
        if (!caption.isBlank() && parsed.title.isBlank()) parsed.title = caption;
        if (parsed.durationMs <= 0 && obj.has("duration") && obj.get("duration").isJsonPrimitive()
                && (obj.has("photoUrl") || obj.has("caption") || obj.has("photoH265Url"))) {
            try {
                parsed.durationMs = obj.get("duration").getAsLong();
            } catch (RuntimeException ignored) {
                parsed.durationMs = 0;
            }
        }
        if (parsed.cover.isBlank()) parsed.cover = coverOf(obj);
        if ("VisionVideoDetailAuthor".equals(SourceText.text(obj, "__typename"))) {
            String name = SourceText.text(obj, "name");
            if (!name.isBlank()) parsed.author = name;
        }
        if (obj.has("author") && obj.get("author").isJsonObject()) {
            String name = SourceText.text(obj.getAsJsonObject("author"), "name");
            if (!name.isBlank()) parsed.author = name;
        }
        if (obj.has("url") && (obj.has("avgBitrate") || obj.has("qualityType") || obj.has("maxBitrate"))) {
            String repUrl = SourceText.text(obj, "url");
            if (repUrl.startsWith("http") || repUrl.startsWith("//")) {
                long bitrate = 0;
                if (obj.has("avgBitrate") && obj.get("avgBitrate").isJsonPrimitive()) {
                    try {
                        bitrate = obj.get("avgBitrate").getAsLong();
                    } catch (RuntimeException ignored) {
                        bitrate = 0;
                    }
                }
                parsed.reps.add(new Rep(repUrl, bitrate));
            }
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            walk(entry.getValue(), parsed, depth + 1);
        }
    }

    private static String coverOf(JsonObject obj) {
        if (obj.has("coverUrl") && obj.get("coverUrl").isJsonPrimitive()) {
            return SourceText.cleanUrl(obj.get("coverUrl").getAsString());
        }
        if (obj.has("coverUrls") && obj.get("coverUrls").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("coverUrls");
            if (!arr.isEmpty() && arr.get(0).isJsonObject()) {
                return SourceText.cleanUrl(SourceText.text(arr.get(0).getAsJsonObject(), "url"));
            }
        }
        return "";
    }

    private Parsed fetchDetail(String photoId) {
        JsonObject body = new JsonObject();
        body.addProperty("operationName", "visionVideoDetail");
        JsonObject vars = new JsonObject();
        vars.addProperty("photoId", photoId);
        vars.addProperty("page", "detail");
        body.add("variables", vars);
        body.addProperty("query", DETAIL_QUERY);
        String did = "web_" + UUID.randomUUID().toString().replace("-", "");
        Request request = new Request.Builder()
                .url("https://www.kuaishou.com/graphql")
                .header("User-Agent", UA)
                .header("Origin", "https://www.kuaishou.com")
                .header("Referer", "https://www.kuaishou.com/short-video/" + photoId)
                .header("Cookie", "kpf=PC_WEB; kpn=KUAISHOU_VISION; clientid=3; did=" + did)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null || !response.isSuccessful()) return null;
            String text = responseBody.string().trim();
            if (!text.startsWith("{")) return null;
            return parseTree(JsonParser.parseString(text));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Fetch fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful() && text.isBlank()) {
                throw new IOException("快手解析失败: HTTP " + response.code());
            }
            return new Fetch(response.request().url().toString(), text);
        }
    }

    private static String firstId(String... texts) {
        for (String text : texts) {
            String id = photoId(text);
            if (id != null) return id;
        }
        return null;
    }

    private record Fetch(String finalUrl, String body) {}

    static final class Parsed {
        String title = "";
        String author = "";
        long durationMs;
        String photoUrl;
        String h265Url;
        String cover = "";
        private final List<Rep> reps = new ArrayList<>();

        String mediaUrl() {
            if (photoUrl != null && !photoUrl.isBlank()) return SourceText.cleanUrl(photoUrl);
            String best = null;
            long bestBitrate = Long.MAX_VALUE;
            for (Rep rep : reps) {
                if (rep.url == null || rep.url.isBlank()) continue;
                long bitrate = rep.bitrate <= 0 ? Long.MAX_VALUE - 1 : rep.bitrate;
                if (bitrate < bestBitrate) {
                    bestBitrate = bitrate;
                    best = rep.url;
                }
            }
            if (best != null) return SourceText.cleanUrl(best);
            if (h265Url != null && !h265Url.isBlank()) return SourceText.cleanUrl(h265Url);
            return null;
        }
    }

    private record Rep(String url, long bitrate) {}
}
