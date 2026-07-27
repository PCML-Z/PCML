package com.pmcl.music.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.music.source.BilibiliWbiSigner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 歌词获取：本地同名 .lrc → B站字幕（player/wbi/v2）。
 */
public final class LyricsProvider {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();
    private final BilibiliWbiSigner wbi = new BilibiliWbiSigner(client, USER_AGENT, REFERER);

    /**
     * @param sourceType bilibili / local / ...
     * @param sourceUrl  原始 URL 或本地路径
     * @param originalId BV 号等
     */
    public List<LyricsLine> fetch(String sourceType, String sourceUrl, String originalId) {
        try {
            if ("local".equals(sourceType) || looksLocal(sourceUrl)) {
                List<LyricsLine> local = loadSidecarLrc(sourceUrl);
                if (!local.isEmpty()) return local;
            }
            if ("bilibili".equals(sourceType) && originalId != null && !originalId.isBlank()) {
                List<LyricsLine> bili = fetchBilibili(originalId);
                if (!bili.isEmpty()) return bili;
            }
        } catch (Exception e) {
            System.err.println("[Lyrics] fetch failed: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<LyricsLine> loadSidecarLrc(String sourceUrl) throws IOException {
        Path audio = Path.of(sourceUrl.replaceFirst("^file:", ""));
        if (!Files.isRegularFile(audio)) return Collections.emptyList();
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path lrc = audio.getParent().resolve(base + ".lrc");
        if (!Files.isRegularFile(lrc)) return Collections.emptyList();
        return LyricsParser.parse(Files.readString(lrc, StandardCharsets.UTF_8));
    }

    private List<LyricsLine> fetchBilibili(String bvid) throws IOException {
        // 先拿 cid
        JsonObject view = fetchJson("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid);
        if (view.get("code").getAsInt() != 0) return Collections.emptyList();
        JsonObject data = view.getAsJsonObject("data");
        long cid = data.get("cid").getAsLong();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", Long.toString(cid));
        String query = wbi.signQuery(params);
        JsonObject player = fetchJson("https://api.bilibili.com/x/player/wbi/v2?" + query);
        if (player.get("code").getAsInt() != 0) {
            wbi.invalidate();
            query = wbi.signQuery(params);
            player = fetchJson("https://api.bilibili.com/x/player/wbi/v2?" + query);
            if (player.get("code").getAsInt() != 0) return Collections.emptyList();
        }
        JsonObject pData = player.getAsJsonObject("data");
        if (pData == null || !pData.has("subtitle")) return Collections.emptyList();
        JsonObject subtitle = pData.getAsJsonObject("subtitle");
        if (subtitle == null || !subtitle.has("subtitles")) return Collections.emptyList();
        JsonArray subs = subtitle.getAsJsonArray("subtitles");
        if (subs == null || subs.size() == 0) return Collections.emptyList();

        // 优先 ai-zh / zh-CN
        String subtitleUrl = null;
        for (JsonElement e : subs) {
            JsonObject s = e.getAsJsonObject();
            String lan = s.has("lan") ? s.get("lan").getAsString() : "";
            String url = s.has("subtitle_url") ? s.get("subtitle_url").getAsString() : "";
            if (url.isBlank()) continue;
            if (!url.startsWith("http")) url = "https:" + url;
            if (lan.contains("zh") || lan.contains("ai")) {
                subtitleUrl = url;
                break;
            }
            if (subtitleUrl == null) subtitleUrl = url;
        }
        if (subtitleUrl == null) return Collections.emptyList();

        String body = fetchText(subtitleUrl);
        // B站 JSON 字幕：body[].from / content
        if (body.trim().startsWith("{")) {
            return parseBiliJsonSubtitle(body);
        }
        return LyricsParser.parse(body);
    }

    private static List<LyricsLine> parseBiliJsonSubtitle(String body) {
        List<LyricsLine> out = new java.util.ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("body")) return out;
            for (JsonElement e : root.getAsJsonArray("body")) {
                JsonObject o = e.getAsJsonObject();
                double from = o.has("from") ? o.get("from").getAsDouble() : 0;
                String content = o.has("content") ? o.get("content").getAsString() : "";
                out.add(new LyricsLine(Math.round(from * 1000.0), content));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private JsonObject fetchJson(String url) throws IOException {
        String body = fetchText(url);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private String fetchText(String url) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            return resp.body() != null ? resp.body().string() : "";
        }
    }

    private static boolean looksLocal(String url) {
        if (url == null) return false;
        return url.startsWith("/") || url.regionMatches(true, 0, "file:", 0, 5)
                || (url.length() >= 3 && Character.isLetter(url.charAt(0)) && url.charAt(1) == ':');
    }
}
