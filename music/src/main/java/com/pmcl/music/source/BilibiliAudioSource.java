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
 * B站音频源：支持解析视频链接获取 DASH 音频流。
 *
 * <p>支持 URL 格式：
 * <ul>
 *   <li>{@code https://www.bilibili.com/video/BV1xxxxxxxx}</li>
 *   <li>{@code https://www.bilibili.com/video/av123456}</li>
 *   <li>{@code https://b23.tv/xxxxxxx}（短链，需重定向解析）</li>
 *   <li>纯 {@code BV1xxxxxxxx} 或 {@code av123456} 字符串</li>
 * </ul>
 *
 * <p>实现说明：wbi 签名先简化处理——直接调用不带 w_rid 的 playurl 接口。
 * 对大多数未登录场景不强制 wbi，若返回 code=-403 则按规范应实现完整 wbi 签名
 * （此处保留简化实现，未登录场景下通常可用）。
 */
public class BilibiliAudioSource implements AudioSource {

    private static final String TYPE = "bilibili";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com";

    private static final Pattern BV_PATTERN = Pattern.compile("BV[a-zA-Z0-9]{10}");
    private static final Pattern AV_PATTERN = Pattern.compile("av(\\d+)", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client = new OkHttpClient();

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
        // 1. b23.tv 短链重定向解析
        if (url.toLowerCase().contains("b23.tv")) {
            url = resolveShortUrl(url);
        }

        // 2. 提取 BV 号
        String bvid = extractBvId(url);
        if (bvid == null) {
            // 尝试 AV 号
            String aid = extractAvId(url);
            if (aid != null) {
                bvid = aidToBvid(aid);
            }
        }
        if (bvid == null) {
            throw new IOException("B站解析失败: 无法识别的 URL " + originalUrl);
        }

        // 3. 获取视频信息（cid / title / pic / owner.name / duration）
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

        // 4. 获取 DASH 流
        JsonObject playUrlData = fetchJson(
                "https://api.bilibili.com/x/player/wbi/playurl?bvid=" + bvid
                        + "&cid=" + cid + "&fnval=16&fnver=0&qn=64");
        int pcode = playUrlData.get("code").getAsInt();
        if (pcode != 0) {
            throw new IOException("B站解析失败: " + safeStr(playUrlData, "message"));
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

        // 选 ID 最大的（最高码率）
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

        // 5. headers
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
                bvid
        );
    }

    /** 解析 b23.tv 短链，从 Location header 取重定向后的 URL（不跟随重定向） */
    private String resolveShortUrl(String shortUrl) throws IOException {
        Request req = new Request.Builder()
                .url(shortUrl)
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String location = resp.header("Location");
            if (location != null && !location.isBlank()) {
                return location;
            }
            // 有些场景会自动跟随重定向，使用最终 URL
            return resp.request().url().toString();
        }
    }

    /** 从 URL 或字符串中提取 BV 号 */
    private String extractBvId(String s) {
        Matcher m = BV_PATTERN.matcher(s);
        return m.find() ? m.group() : null;
    }

    /** 从 URL 或字符串中提取 AV 号数字 */
    private String extractAvId(String s) {
        Matcher m = AV_PATTERN.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** AV 号转 BV 号（API 接受 aid 参数，但此处仍转换以便统一） */
    private String aidToBvid(String aid) throws IOException {
        JsonObject obj = fetchJson("https://api.bilibili.com/x/web-interface/view?aid=" + aid);
        if (obj.get("code").getAsInt() != 0) return null;
        JsonObject data = obj.getAsJsonObject("data");
        return data.has("bvid") ? data.get("bvid").getAsString() : null;
    }

    /** GET 请求并解析 JSON */
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
