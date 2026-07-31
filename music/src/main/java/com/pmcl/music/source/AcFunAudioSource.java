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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A站音频源：从 acfun.cn 视频页嵌入的 {@code window.videoInfo} 解析 HLS 流。
 *
 * <p>支持 URL 格式：
 * <ul>
 *   <li>{@code https://www.acfun.cn/v/ac123456}</li>
 *   <li>{@code https://www.acfun.cn/v/ac123456_2}（多 P）</li>
 *   <li>{@code https://m.acfun.cn/v/?ac=123456}</li>
 *   <li>纯 {@code ac123456}</li>
 * </ul>
 *
 * <p>说明：旧版 {@code /rest/pc-direct/play/playInfo?videoId=} 需要内部 videoId
 *（不是稿件 ac 号），且常被风控成 HTML；现改为与 yt-dlp 一致的页面 / ajaxpipe 解析。
 * A站 {@code ksPlayJson} 的 representation 多为音视频一体的 m3u8（无独立 audio mime），
 * 播放端用 FFmpeg 抽音频即可。
 */
public class AcFunAudioSource implements AudioSource {

    private static final String TYPE = "acfun";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.acfun.cn/";

    /** 稿件 ID：ac123456 或多 P 的 ac123456_2 */
    private static final Pattern AC_PATTERN =
            Pattern.compile("(?:^|[/?=&])ac([\\d]+(?:_[\\d]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AC_LOOSE_PATTERN =
            Pattern.compile("ac([\\d]+(?:_[\\d]+)?)", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(30))
            .writeTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(true)
            .build();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean matches(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("acfun.cn") || lower.contains("acfun.com")) return true;
        return AC_LOOSE_PATTERN.matcher(url.trim()).find();
    }

    @Override
    public AudioStreamInfo resolve(String url) throws IOException {
        String originalUrl = url;
        String acId = extractAcId(url);
        if (acId == null) {
            throw new IOException("A站解析失败: 无法识别的 URL " + originalUrl);
        }

        JsonObject videoInfo = fetchVideoInfo(acId);
        JsonObject currentVideoInfo = videoInfo.has("currentVideoInfo")
                && videoInfo.get("currentVideoInfo").isJsonObject()
                ? videoInfo.getAsJsonObject("currentVideoInfo")
                : null;
        if (currentVideoInfo == null) {
            throw new IOException("A站解析失败: 响应缺少 currentVideoInfo");
        }

        String ksPlayJsonStr = safeStr(currentVideoInfo, "ksPlayJson");
        if (ksPlayJsonStr.isEmpty()) {
            throw new IOException("A站解析失败: 缺少 ksPlayJson（可能需登录或视频已下架）");
        }
        JsonObject ksPlayJson = JsonParser.parseString(ksPlayJsonStr).getAsJsonObject();
        JsonArray adaptationSet = ksPlayJson.has("adaptationSet")
                ? ksPlayJson.getAsJsonArray("adaptationSet")
                : null;
        if (adaptationSet == null || adaptationSet.isEmpty()) {
            throw new IOException("A站解析失败: adaptationSet 为空");
        }

        JsonObject bestRep = pickStreamRepresentation(adaptationSet);
        if (bestRep == null) {
            throw new IOException("A站解析失败: 未找到可用播放地址");
        }
        String streamUrl = firstNonEmpty(
                safeStr(bestRep, "url"),
                safeStr(bestRep, "m3u8Slice"),
                safeStr(bestRep, "backupUrl"));
        if (streamUrl.isEmpty() && bestRep.has("backupUrl") && bestRep.get("backupUrl").isJsonArray()) {
            JsonArray backups = bestRep.getAsJsonArray("backupUrl");
            if (!backups.isEmpty()) {
                streamUrl = backups.get(0).getAsString();
            }
        }
        if (streamUrl.isEmpty()) {
            throw new IOException("A站解析失败: representation 无 url");
        }

        String title = safeStr(videoInfo, "title");
        if (title.isEmpty()) {
            title = safeStr(currentVideoInfo, "title");
        }
        // 多 P：附上分 P 标题
        if (videoInfo.has("videoList") && videoInfo.get("videoList").isJsonArray()) {
            long curId = currentVideoInfo.has("id") && currentVideoInfo.get("id").isJsonPrimitive()
                    ? currentVideoInfo.get("id").getAsLong() : -1L;
            JsonArray videoList = videoInfo.getAsJsonArray("videoList");
            if (curId >= 0 && videoList.size() > 1) {
                for (int i = 0; i < videoList.size(); i++) {
                    JsonObject part = videoList.get(i).getAsJsonObject();
                    long pid = part.has("id") && part.get("id").isJsonPrimitive()
                            ? part.get("id").getAsLong() : -2L;
                    if (pid == curId) {
                        String partTitle = safeStr(part, "title");
                        if (!partTitle.isEmpty()) {
                            title = title + " P" + (i + 1) + " " + partTitle;
                        }
                        break;
                    }
                }
            }
        }

        long durationMs = 0L;
        if (currentVideoInfo.has("durationMillis") && currentVideoInfo.get("durationMillis").isJsonPrimitive()) {
            durationMs = currentVideoInfo.get("durationMillis").getAsLong();
        } else if (currentVideoInfo.has("durationMs") && currentVideoInfo.get("durationMs").isJsonPrimitive()) {
            durationMs = currentVideoInfo.get("durationMs").getAsLong();
        }

        String coverUrl = safeStr(videoInfo, "coverUrl");
        if (coverUrl.isEmpty()) {
            coverUrl = safeStr(videoInfo, "image");
        }

        String uploader = "";
        if (videoInfo.has("user") && videoInfo.get("user").isJsonObject()) {
            uploader = safeStr(videoInfo.getAsJsonObject("user"), "name");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", REFERER);
        headers.put("User-Agent", USER_AGENT);
        headers.put("Origin", "https://www.acfun.cn");

        return new AudioStreamInfo(
                title,
                uploader,
                durationMs,
                streamUrl,
                coverUrl,
                TYPE,
                originalUrl,
                headers,
                "ac" + acId
        );
    }

    /**
     * 选取适合听歌的流：优先含 mp4a/aac 的轨，并选码率最低的（带宽小、听感足够）。
     * A站 representation 通常无独立 audio mimeType。
     */
    private static JsonObject pickStreamRepresentation(JsonArray adaptationSet) {
        JsonObject best = null;
        long bestScore = Long.MAX_VALUE; // 越小越好：优先低码率
        boolean bestHasAudio = false;

        for (JsonElement asEl : adaptationSet) {
            if (asEl == null || !asEl.isJsonObject()) continue;
            JsonObject asObj = asEl.getAsJsonObject();
            if (!asObj.has("representation") || !asObj.get("representation").isJsonArray()) continue;
            for (JsonElement repEl : asObj.getAsJsonArray("representation")) {
                if (repEl == null || !repEl.isJsonObject()) continue;
                JsonObject rep = repEl.getAsJsonObject();
                String url = safeStr(rep, "url");
                if (url.isEmpty()) continue;

                String mime = safeStr(rep, "mimeType").toLowerCase(Locale.ROOT);
                String codecs = safeStr(rep, "codecs").toLowerCase(Locale.ROOT);
                boolean hasAudio = mime.contains("audio")
                        || codecs.contains("mp4a")
                        || codecs.contains("aac")
                        || codecs.contains("opus");
                // 纯视频无音轨：仅在没有更好候选时接受
                boolean videoOnly = !hasAudio && (mime.contains("video")
                        || codecs.contains("avc") || codecs.contains("hev") || codecs.contains("hvc"));

                long bitrate = 0L;
                if (rep.has("avgBitrate") && rep.get("avgBitrate").isJsonPrimitive()) {
                    bitrate = rep.get("avgBitrate").getAsLong();
                } else if (rep.has("bitrate") && rep.get("bitrate").isJsonPrimitive()) {
                    bitrate = rep.get("bitrate").getAsLong();
                }
                long score = bitrate > 0 ? bitrate : 1_000_000L;
                if (videoOnly) score += 10_000_000L; // 惩罚无音轨

                if (best == null
                        || (hasAudio && !bestHasAudio)
                        || (hasAudio == bestHasAudio && score < bestScore)) {
                    best = rep;
                    bestScore = score;
                    bestHasAudio = hasAudio;
                }
            }
        }
        return best;
    }

    private JsonObject fetchVideoInfo(String acId) throws IOException {
        // 1) ajaxpipe：体积小、结构稳定（与 acfun-video-cli / 网页 quickView 一致）
        String ajaxUrl = "https://www.acfun.cn/v/ac" + acId
                + "?quickViewId=videoInfo_new&ajaxpipe=1";
        try {
            String body = fetchText(ajaxUrl);
            JsonObject info = parseVideoInfoFromAjaxPipe(body);
            if (info != null) return info;
            // 少数情况响应直接是带转义的 HTML 片段
            info = parseVideoInfoFromHtml(body);
            if (info != null) return info;
        } catch (Exception e) {
            System.err.println("[AcFun] ajaxpipe 解析失败，回退整页: " + e.getMessage());
        }

        // 2) 整页 HTML 回退
        String pageUrl = "https://www.acfun.cn/v/ac" + acId;
        String page = fetchText(pageUrl);
        JsonObject info = parseVideoInfoFromHtml(page);
        if (info != null) return info;
        throw new IOException("A站解析失败: 页面中未找到 videoInfo（ac" + acId + "）");
    }

    /**
     * ajaxpipe 响应形如 {@code {"html":"<script>...window.videoInfo={...}</script>"}...}，
     * 后面可能拼接其它 JSON。先抽出外层对象，再从已反转义的 html 里取 videoInfo。
     */
    static JsonObject parseVideoInfoFromAjaxPipe(String body) throws IOException {
        if (body == null || body.isBlank()) return null;
        int start = body.indexOf('{');
        if (start < 0) return null;
        String outer = extractBalancedObject(body, start);
        if (outer == null) return null;
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(outer);
            if (!el.isJsonObject()) return null;
            root = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        if (!root.has("html") || root.get("html").isJsonNull()) return null;
        return parseVideoInfoFromHtml(root.get("html").getAsString());
    }

    /**
     * 从页面 HTML（已是正常文本，非 JSON 二次转义）提取 {@code window.videoInfo}。
     */
    static JsonObject parseVideoInfoFromHtml(String body) throws IOException {
        if (body == null || body.isBlank()) return null;
        int marker = indexOfIgnoreCase(body, "window.videoInfo");
        if (marker < 0) marker = indexOfIgnoreCase(body, "window.pageInfo");
        if (marker < 0) return null;

        int eq = body.indexOf('=', marker);
        if (eq < 0) return null;
        int start = body.indexOf('{', eq);
        if (start < 0) return null;

        String rawJson = extractBalancedObject(body, start);
        if (rawJson == null) return null;

        try {
            JsonElement el = JsonParser.parseString(rawJson);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            throw new IOException("A站解析失败: videoInfo JSON 无效: " + e.getMessage(), e);
        }
    }

    /** 从 {@code start}（指向 '{'}）提取平衡的 JSON 对象子串。 */
    static String extractBalancedObject(String s, int start) {
        if (start < 0 || start >= s.length() || s.charAt(start) != '{') return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String extractAcId(String s) {
        if (s == null) return null;
        Matcher m = AC_PATTERN.matcher(s);
        if (m.find()) return m.group(1);
        m = AC_LOOSE_PATTERN.matcher(s.trim());
        if (m.find()) return m.group(1);
        // m.acfun.cn/v/?ac=123456
        try {
            java.net.URI uri = java.net.URI.create(s.trim());
            String q = uri.getQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && part.substring(0, eq).equalsIgnoreCase("ac")) {
                        String v = part.substring(eq + 1).trim();
                        if (v.matches("\\d+(?:_\\d+)?")) return v;
                    }
                }
            }
        } catch (Exception ignored) {
            // not a URI
        }
        return null;
    }

    private String fetchText(String url) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Accept", "text/html,application/json,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("A站 HTTP " + resp.code() + ": " + body.substring(0, Math.min(120, body.length())));
            }
            return body;
        }
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    private static String safeStr(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()
                ? obj.get(key).getAsString() : "";
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }
}
