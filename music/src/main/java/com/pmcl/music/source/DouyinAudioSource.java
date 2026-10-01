package com.pmcl.music.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音音频源。短链先跳到作品页，再读 iesdouyin 分享页里的播放信息。
 *
 * <p>支持 douyin.com/video、modal_id、v.douyin.com 短链和 iesdouyin 分享页。
 */
public final class DouyinAudioSource implements AudioSource {

    static final String TYPE = "douyin";
    private static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";
    private static final String REFERER = "https://www.douyin.com/";
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:video|note|share/video)/(\\d{6,})"
                    + "|(?:[?&]modal_id=)(\\d{6,})"
                    + "|(?:[?&]aweme_id=)(\\d{6,})");
    private static final Pattern RENDER_DATA = Pattern.compile(
            "id=[\"']RENDER_DATA[\"'][^>]*>([^<]+)", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(20))
            .followRedirects(true)
            .build();

    private final OkHttpClient noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean matches(String url) {
        return SourceText.hostIs(url, "douyin.com", "iesdouyin.com");
    }

    @Override
    public AudioStreamInfo resolve(String url) throws IOException {
        SourceText.requirePublicHttp(url);
        String awemeId = videoId(url);
        if (awemeId == null) {
            Fetch opened = fetch(url.trim());
            awemeId = firstId(opened.finalUrl, opened.body);
        }
        if (awemeId == null) {
            throw new IOException("抖音解析失败: 无法识别的链接");
        }
        Fetch share = fetch("https://www.iesdouyin.com/share/video/" + awemeId);
        String json = routerJson(share.body);
        if (json == null) {
            throw new IOException("抖音解析失败: 分享页没有播放信息");
        }
        Item item;
        try {
            item = findItem(JsonParser.parseString(json));
        } catch (RuntimeException e) {
            throw new IOException("抖音解析失败: 分享页数据无法读取");
        }
        if (item == null || item.playRef == null || item.playRef.isBlank()) {
            throw new IOException("抖音解析失败: 未找到可播放地址");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", REFERER);
        headers.put("User-Agent", UA);
        String media = directMedia(mediaUrl(item), headers);
        SourceText.requirePublicHttp(media);
        return new AudioStreamInfo(
                item.title.isBlank() ? "抖音" : item.title,
                item.author,
                item.durationMs,
                media,
                item.cover,
                TYPE,
                url,
                headers,
                awemeId,
                media
        );
    }

    static String videoId(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = VIDEO_ID.matcher(text);
        if (!m.find()) return null;
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) return m.group(i);
        }
        return null;
    }

    static String routerJson(String html) {
        String raw = SourceText.balancedJson(html, "window._ROUTER_DATA");
        if (raw != null) return raw;
        if (html == null) return null;
        Matcher m = RENDER_DATA.matcher(html);
        if (!m.find()) return null;
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    static Item findItem(JsonElement root) {
        Item[] found = new Item[1];
        walk(root, found, 0);
        return found[0];
    }

    static String mediaUrl(Item item) {
        if (item == null || item.playRef == null || item.playRef.isBlank()) return "";
        if (item.direct || item.playRef.startsWith("http://") || item.playRef.startsWith("https://")) {
            return SourceText.cleanUrl(item.playRef);
        }
        return "https://www.iesdouyin.com/aweme/v1/play/?video_id="
                + java.net.URLEncoder.encode(item.playRef, StandardCharsets.UTF_8)
                + "&ratio=720p&line=0";
    }

    private static void walk(JsonElement el, Item[] found, int depth) {
        if (found[0] != null || el == null || el.isJsonNull() || depth > 30) return;
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) walk(child, found, depth + 1);
            return;
        }
        if (!el.isJsonObject()) return;
        JsonObject obj = el.getAsJsonObject();
        String playRef = playRef(obj);
        boolean direct = false;
        if (playRef == null) {
            playRef = musicUrl(obj);
            direct = playRef != null;
        }
        if (playRef != null) {
            Item item = new Item();
            item.playRef = playRef;
            item.direct = direct;
            item.title = SourceText.text(obj, "desc");
            if (obj.has("author") && obj.get("author").isJsonObject()) {
                item.author = SourceText.text(obj.getAsJsonObject("author"), "nickname");
            }
            if (obj.has("video") && obj.get("video").isJsonObject()) {
                JsonObject video = obj.getAsJsonObject("video");
                if (video.has("duration") && video.get("duration").isJsonPrimitive()) {
                    try {
                        item.durationMs = video.get("duration").getAsLong();
                    } catch (RuntimeException ignored) {
                        item.durationMs = 0;
                    }
                }
                item.cover = urlList(video, "cover");
                if (item.cover.isBlank()) item.cover = urlList(video, "origin_cover");
            }
            found[0] = item;
            return;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            walk(entry.getValue(), found, depth + 1);
        }
    }

    private static String playRef(JsonObject item) {
        if (!item.has("video") || !item.get("video").isJsonObject()) return null;
        JsonObject video = item.getAsJsonObject("video");
        JsonObject addr = object(video, "play_addr");
        if (addr == null) addr = object(video, "playAddr");
        if (addr == null) return null;
        String uri = SourceText.text(addr, "uri");
        if (!uri.isBlank()) return uri;
        String url = urlList(video, "play_addr");
        return url.isBlank() ? null : url;
    }

    private static String musicUrl(JsonObject item) {
        JsonObject music = object(item, "music");
        if (music == null) return null;
        String url = urlList(music, "play_url");
        return url.isBlank() ? null : url;
    }

    private static String urlList(JsonObject parent, String key) {
        JsonObject obj = object(parent, key);
        if (obj == null || !obj.has("url_list") || !obj.get("url_list").isJsonArray()) return "";
        JsonArray list = obj.getAsJsonArray("url_list");
        if (list.isEmpty() || !list.get(0).isJsonPrimitive()) return "";
        return SourceText.cleanUrl(list.get(0).getAsString());
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return null;
        return parent.getAsJsonObject(key);
    }

    private String directMedia(String url, Map<String, String> headers) throws IOException {
        try {
            return follow(url, headers);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("播放地址不安全")) throw e;
            return url;
        }
    }

    private String follow(String start, Map<String, String> headers) throws IOException {
        String current = start;
        for (int hop = 0; hop < 5; hop++) {
            String err = com.pmcl.core.util.SsrfChecker.validate(current);
            if (err != null) throw new IOException("播放地址不安全: " + err);
            Request.Builder builder = new Request.Builder().url(current).header("Range", "bytes=0-0");
            headers.forEach(builder::header);
            try (Response response = noRedirect.newCall(builder.build()).execute()) {
                int code = response.code();
                if (code >= 301 && code <= 308) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) return current;
                    okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(current);
                    okhttp3.HttpUrl next = base == null ? null : base.resolve(location);
                    if (next == null) return current;
                    current = next.toString();
                    continue;
                }
                return current;
            }
        }
        return current;
    }

    private Fetch fetch(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            if (!response.isSuccessful() && text.isBlank()) {
                throw new IOException("抖音解析失败: HTTP " + response.code());
            }
            return new Fetch(response.request().url().toString(), text);
        }
    }

    private static String firstId(String... texts) {
        for (String text : texts) {
            String id = videoId(text);
            if (id != null) return id;
        }
        return null;
    }

    private record Fetch(String finalUrl, String body) {}

    static final class Item {
        String title = "";
        String author = "";
        String cover = "";
        String playRef = "";
        boolean direct;
        long durationMs;
    }
}
