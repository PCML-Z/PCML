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
 * A站音频源：解析 acfun.cn 视频链接获取音频流。
 *
 * <p>支持 URL 格式：
 * <ul>
 *   <li>{@code https://www.acfun.cn/v/ac123456}</li>
 *   <li>纯 {@code ac123456} 或 {@code 123456}（数字 ID）</li>
 * </ul>
 */
public class AcFunAudioSource implements AudioSource {

    private static final String TYPE = "acfun";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.acfun.cn";

    private static final Pattern AC_PATTERN = Pattern.compile("ac(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");

    private final OkHttpClient client = new OkHttpClient();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean matches(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase();
        if (lower.contains("acfun.cn")) return true;
        // 纯 ac123456
        if (AC_PATTERN.matcher(url).find()) return true;
        // 纯数字 ID（谨慎匹配，避免误判）
        return DIGIT_PATTERN.matcher(url.trim()).matches();
    }

    @Override
    public AudioStreamInfo resolve(String url) throws IOException {
        String originalUrl = url;
        String videoId = extractVideoId(url);
        if (videoId == null) {
            throw new IOException("A站解析失败: 无法识别的 URL " + originalUrl);
        }

        // 1. 调用 playInfo 接口
        JsonObject root = fetchJson(
                "https://www.acfun.cn/rest/pc-direct/play/playInfo?videoId=" + videoId);
        // result != 1 视为失败
        if (!root.has("result") || root.get("result").getAsInt() != 1) {
            throw new IOException("A站解析失败: " + safeStr(root, "msg"));
        }

        // 2. currentVideoInfo
        if (!root.has("currentVideoInfo")) {
            throw new IOException("A站解析失败: 响应缺少 currentVideoInfo");
        }
        JsonObject currentVideoInfo = root.getAsJsonObject("currentVideoInfo");

        // 3. ksPlayJson 中的 adaptationSet[0].representation
        String ksPlayJsonStr = currentVideoInfo.has("ksPlayJson")
                && !currentVideoInfo.get("ksPlayJson").isJsonNull()
                ? currentVideoInfo.get("ksPlayJson").getAsString()
                : null;
        if (ksPlayJsonStr == null) {
            throw new IOException("A站解析失败: 缺少 ksPlayJson");
        }
        JsonObject ksPlayJson = JsonParser.parseString(ksPlayJsonStr).getAsJsonObject();
        JsonArray adaptationSet = ksPlayJson.has("adaptationSet")
                ? ksPlayJson.getAsJsonArray("adaptationSet")
                : null;
        if (adaptationSet == null || adaptationSet.isEmpty()) {
            throw new IOException("A站解析失败: adaptationSet 为空");
        }

        // 寻找 audio 类型的 representation（按 mimeType 包含 audio）
        JsonObject bestRep = null;
        long bestBitrate = -1;
        for (JsonElement asEl : adaptationSet) {
            JsonObject asObj = asEl.getAsJsonObject();
            if (!asObj.has("representation")) continue;
            JsonArray reps = asObj.getAsJsonArray("representation");
            for (JsonElement repEl : reps) {
                JsonObject rep = repEl.getAsJsonObject();
                String mime = safeStr(rep, "mimeType");
                // 只接受音频
                if (!mime.toLowerCase().contains("audio")) continue;
                long bitrate = rep.has("bitrate") && rep.get("bitrate").isJsonPrimitive()
                        ? rep.get("bitrate").getAsLong() : 0L;
                if (bitrate > bestBitrate) {
                    bestBitrate = bitrate;
                    bestRep = rep;
                }
            }
        }
        if (bestRep == null) {
            throw new IOException("A站解析失败: 未找到音频 representation");
        }
        String audioUrl = safeStr(bestRep, "url");
        if (audioUrl.isEmpty()) {
            throw new IOException("A站解析失败: representation 无 url");
        }

        // 4. 元数据
        String title = safeStr(currentVideoInfo, "title");
        long durationMs = currentVideoInfo.has("durationMs")
                && currentVideoInfo.get("durationMs").isJsonPrimitive()
                ? currentVideoInfo.get("durationMs").getAsLong() : 0L;

        // videoList[0]
        String coverUrl = "";
        if (root.has("videoList") && root.get("videoList").isJsonArray()) {
            JsonArray videoList = root.getAsJsonArray("videoList");
            if (!videoList.isEmpty()) {
                JsonObject firstVideo = videoList.get(0).getAsJsonObject();
                if (title.isEmpty()) {
                    title = safeStr(firstVideo, "title");
                }
                coverUrl = safeStr(firstVideo, "coverUrl");
            }
        }

        // user.name
        String uploader = "";
        if (root.has("user") && root.get("user").isJsonObject()) {
            uploader = safeStr(root.getAsJsonObject("user"), "name");
        }

        // 5. headers
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", REFERER);
        headers.put("User-Agent", USER_AGENT);

        return new AudioStreamInfo(
                title,
                uploader,
                durationMs,
                audioUrl,
                coverUrl,
                TYPE,
                originalUrl,
                headers,
                "ac" + videoId
        );
    }

    /** 从 URL 或字符串提取视频 ID（数字）。
     *  M62 修复：从 URL 路径提取数字，排除查询参数中的数字（如 ?t=123 不会误提取）。
     */
    private String extractVideoId(String s) {
        Matcher m = AC_PATTERN.matcher(s);
        if (m.find()) return m.group(1);
        // 纯数字
        String trimmed = s.trim();
        if (DIGIT_PATTERN.matcher(trimmed).matches()) return trimmed;
        // M62: 从 URL 路径提取数字，排除查询参数
        String path = s;
        try {
            java.net.URL url = new java.net.URL(s);
            path = url.getPath();
        } catch (java.net.MalformedURLException ignored) {
            // 非 URL 格式，回退到对整个字符串匹配
        }
        Matcher dm = DIGIT_PATTERN.matcher(path);
        String last = null;
        while (dm.find()) {
            last = dm.group();
        }
        return last;
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
