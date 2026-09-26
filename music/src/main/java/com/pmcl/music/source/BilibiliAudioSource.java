package com.pmcl.music.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站音频源：支持解析视频链接获取 DASH 音频流（完整 WBI 签名）。
 */
public class BilibiliAudioSource implements AudioSource {

    private static final String TYPE = "bilibili";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com";

    private static final Pattern BV_PATTERN = Pattern.compile("BV[a-zA-Z0-9]{10}");
    private static final Pattern AV_PATTERN = Pattern.compile("av(\\d+)", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(30))
            .writeTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(true)
            .build();

    private final BilibiliWbiSigner wbi = new BilibiliWbiSigner(client, USER_AGENT, REFERER);

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean matches(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase();
        return lower.contains("bilibili.com")
                || lower.contains("b23.tv")
                || BV_PATTERN.matcher(url).find()
                || AV_PATTERN.matcher(url).find();
    }

    @Override
    public AudioStreamInfo resolve(String url) throws IOException {
        String originalUrl = url;
        if (url.toLowerCase().contains("b23.tv")) {
            url = resolveShortUrl(url);
        }

        String bvid = extractBvId(url);
        if (bvid == null) {
            String aid = extractAvId(url);
            if (aid != null) {
                bvid = aidToBvid(aid);
            }
        }
        if (bvid == null) {
            throw new IOException("B站解析失败: 无法识别的 URL " + originalUrl);
        }

        JsonObject viewData = fetchJson(
                "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid);
        int code = viewData.get("code").getAsInt();
        if (code != 0) {
            throw new IOException("B站解析失败: " + safeStr(viewData, "message"));
        }
        JsonObject data = viewData.getAsJsonObject("data");
        long cid = data.get("cid").getAsLong();
        String title = safeStr(data, "title");
        String pic = safeStr(data, "pic");
        String ownerName = data.has("owner") && data.getAsJsonObject("owner").has("name")
                ? data.getAsJsonObject("owner").get("name").getAsString() : "";
        long durationSec = data.has("duration") ? data.get("duration").getAsLong() : 0L;
        long durationMs = durationSec * 1000L;

        JsonObject playUrlData = fetchPlayUrl(bvid, cid);
        int pcode = playUrlData.get("code").getAsInt();
        if (pcode != 0) {
            // 密钥可能过期，刷新后再试一次
            wbi.invalidate();
            playUrlData = fetchPlayUrl(bvid, cid);
            pcode = playUrlData.get("code").getAsInt();
            if (pcode != 0) {
                throw new IOException("B站解析失败: " + safeStr(playUrlData, "message")
                        + " (code=" + pcode + ")");
            }
        }
        JsonObject pData = playUrlData.getAsJsonObject("data");
        if (!pData.has("dash")) {
            throw new IOException("B站解析失败: 未返回 DASH 流（视频可能不支持 DASH）");
        }
        JsonObject dash = pData.getAsJsonObject("dash");
        if (!dash.has("audio")) {
            throw new IOException("B站解析失败: DASH 流中无 audio 数组");
        }
        JsonArray audioArr = dash.getAsJsonArray("audio");

        JsonObject best = null;
        int bestId = -1;
        for (JsonElement e : audioArr) {
            JsonObject a = e.getAsJsonObject();
            int id = a.has("id") ? a.get("id").getAsInt() : 0;
            if (id > bestId) {
                bestId = id;
                best = a;
            }
        }
        if (best == null) {
            throw new IOException("B站解析失败: audio 数组为空");
        }
        String audioUrl = best.has("base_url") && !best.get("base_url").isJsonNull()
                ? best.get("base_url").getAsString() : null;
        if (audioUrl == null && best.has("backup_url") && best.get("backup_url").isJsonArray()) {
            JsonArray backup = best.getAsJsonArray("backup_url");
            if (backup.size() > 0) {
                audioUrl = backup.get(0).getAsString();
            }
        }
        if (audioUrl == null) {
            throw new IOException("B站解析失败: 无可用的 audio base_url");
        }
        String videoUrl = dash.has("video") && dash.get("video").isJsonArray()
                ? pickPreviewVideoUrl(dash.getAsJsonArray("video"))
                : "";

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", REFERER);
        headers.put("User-Agent", USER_AGENT);

        return new AudioStreamInfo(
                title,
                ownerName,
                durationMs,
                audioUrl,
                pic,
                TYPE,
                originalUrl,
                headers,
                bvid,
                videoUrl
        );
    }

    /**
     * 悬浮窗只需要小画面：优先 AVC，并选择不低于 360p 的最低码率，
     * 避免为了 380px 宽的预览额外解码 1080p/4K。
     */
    static String pickPreviewVideoUrl(JsonArray videos) {
        JsonObject best = null;
        long bestScore = Long.MAX_VALUE;
        for (JsonElement element : videos) {
            if (!element.isJsonObject()) continue;
            JsonObject video = element.getAsJsonObject();
            String url = safeStr(video, "base_url");
            if (url.isBlank() && video.has("backup_url") && video.get("backup_url").isJsonArray()) {
                JsonArray backup = video.getAsJsonArray("backup_url");
                if (!backup.isEmpty()) url = backup.get(0).getAsString();
            }
            if (url.isBlank()) continue;
            long height = number(video, "height");
            long bandwidth = number(video, "bandwidth");
            String codecs = safeStr(video, "codecs").toLowerCase(java.util.Locale.ROOT);
            long score = bandwidth > 0 ? bandwidth : 10_000_000L;
            if (height > 0 && height < 360) score += 20_000_000L;
            if (!codecs.isBlank() && !codecs.contains("avc")) score += 10_000_000L;
            if (score < bestScore) {
                bestScore = score;
                best = video;
            }
        }
        if (best == null) return "";
        String url = safeStr(best, "base_url");
        if (!url.isBlank()) return url;
        JsonArray backup = best.getAsJsonArray("backup_url");
        return backup.isEmpty() ? "" : backup.get(0).getAsString();
    }

    private static long number(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) return 0L;
        try {
            return obj.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private JsonObject fetchPlayUrl(String bvid, long cid) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", Long.toString(cid));
        params.put("fnval", "16");
        params.put("fnver", "0");
        params.put("qn", "64");
        params.put("fourk", "1");
        String query = wbi.signQuery(params);
        return fetchJson("https://api.bilibili.com/x/player/wbi/playurl?" + query);
    }

    private String resolveShortUrl(String shortUrl) throws IOException {
        // 安全修复：校验 host 确实为 b23.tv，防止 contains("b23.tv") 误匹配攻击者 URL
        java.net.URL parsed;
        try {
            parsed = new java.net.URL(shortUrl);
        } catch (java.net.MalformedURLException e) {
            throw new IOException("无效的短链 URL: " + shortUrl);
        }
        if (!"b23.tv".equalsIgnoreCase(parsed.getHost())) {
            throw new IOException("非 b23.tv 短链，拒绝解析: " + parsed.getHost());
        }
        // SSRF 校验
        String ssrf = com.pmcl.core.util.SsrfChecker.validate(shortUrl);
        if (ssrf != null) {
            throw new IOException("短链 SSRF 拒绝: " + ssrf);
        }
        Request req = new Request.Builder()
                .url(shortUrl)
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String location = resp.header("Location");
            if (location != null && !location.isBlank()) {
                // 校验重定向目标
                String redirectSsrf = com.pmcl.core.util.SsrfChecker.validate(location);
                if (redirectSsrf != null) {
                    throw new IOException("短链重定向 SSRF 拒绝: " + redirectSsrf);
                }
                return location;
            }
            return resp.request().url().toString();
        }
    }

    private String extractBvId(String s) {
        Matcher m = BV_PATTERN.matcher(s);
        return m.find() ? m.group() : null;
    }

    private String extractAvId(String s) {
        Matcher m = AV_PATTERN.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private String aidToBvid(String aid) throws IOException {
        JsonObject obj = fetchJson("https://api.bilibili.com/x/web-interface/view?aid=" + aid);
        if (obj.get("code").getAsInt() != 0) return null;
        JsonObject data = obj.getAsJsonObject("data");
        return data.has("bvid") ? data.get("bvid").getAsString() : null;
    }

    private JsonObject fetchJson(String url) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private static String safeStr(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : "";
    }
}
