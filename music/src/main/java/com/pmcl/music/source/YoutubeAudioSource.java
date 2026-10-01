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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 油管音频源。用 InnerTube ANDROID_VR / iOS 客户端拿可直接播放的音频地址，不解密签名。
 *
 * <p>支持 youtube.com/watch、youtu.be、Shorts、直播回放和 music.youtube.com。
 */
public final class YoutubeAudioSource implements AudioSource {

    static final String TYPE = "youtube";
    private static final String REFERER = "https://www.youtube.com/";
    private static final String PLAYER = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false";
    private static final String VISITOR = "https://youtubei.googleapis.com/youtubei/v1/visitor_id?prettyPrint=false";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern SHORT = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})");
    private static final Pattern PATH = Pattern.compile("(?:embed/|shorts/|live/|v/)([A-Za-z0-9_-]{11})");
    private static final Pattern QUERY = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");

    private static final ClientSpec[] CLIENTS = {
            new ClientSpec(
                    "ANDROID_VR",
                    "1.65.10",
                    "com.google.android.apps.youtube.vr.oculus/1.65.10 "
                            + "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                    "Oculus",
                    "Quest 3",
                    "Android",
                    "12L",
                    32),
            new ClientSpec(
                    "IOS",
                    "20.11.6",
                    "com.google.ios.youtube/20.11.6 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)",
                    "Apple",
                    "iPhone16,2",
                    "iOS",
                    "18.3.2.22D82",
                    0)
    };

    private static volatile String visitorData;

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
        return SourceText.hostIs(url, "youtube.com", "youtu.be", "youtube-nocookie.com");
    }

    @Override
    public AudioStreamInfo resolve(String url) throws IOException {
        String videoId = videoId(url);
        if (videoId == null) {
            throw new IOException("油管解析失败: 无法识别的链接");
        }
        String visitor = visitor(false);
        String lastReason = "";
        for (ClientSpec spec : CLIENTS) {
            PlayerHit hit = requestPlayer(spec, videoId, visitor);
            if (hit.needsVisitor) {
                visitor = visitor(true);
                hit = requestPlayer(spec, videoId, visitor);
            }
            if (hit.audioUrl != null) {
                SourceText.requirePublicHttp(hit.audioUrl);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Referer", REFERER);
                headers.put("User-Agent", spec.userAgent);
                return new AudioStreamInfo(
                        hit.title.isBlank() ? videoId : hit.title,
                        hit.author,
                        hit.durationMs,
                        hit.audioUrl,
                        hit.cover,
                        TYPE,
                        url,
                        headers,
                        videoId,
                        hit.videoUrl
                );
            }
            if (!hit.reason.isBlank()) lastReason = hit.reason;
        }
        throw new IOException("油管解析失败: " + (lastReason.isBlank() ? "没有可直接播放的音频" : lastReason));
    }

    static String videoId(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher shortLink = SHORT.matcher(url);
        if (shortLink.find()) return shortLink.group(1);
        Matcher path = PATH.matcher(url);
        if (path.find()) return path.group(1);
        Matcher query = QUERY.matcher(url);
        if (query.find()) return query.group(1);
        return null;
    }

    /** 优先 128k AAC（itag 140），其次其它 audio/mp4，再退到任意带直链的音轨。 */
    static String pickAudioUrl(JsonObject root) {
        JsonObject streaming = object(root, "streamingData");
        if (streaming == null) return null;
        String aac128 = null;
        String bestMp4 = null;
        int bestMp4Rate = -1;
        String lowAac = null;
        String bestAudio = null;
        int bestAudioRate = -1;
        for (String key : new String[]{"adaptiveFormats", "formats"}) {
            if (!streaming.has(key) || !streaming.get(key).isJsonArray()) continue;
            for (JsonElement el : streaming.getAsJsonArray(key)) {
                if (!el.isJsonObject()) continue;
                JsonObject format = el.getAsJsonObject();
                String streamUrl = SourceText.text(format, "url");
                if (streamUrl.isBlank()) continue;
                String mime = SourceText.text(format, "mimeType");
                int itag = 0;
                if (format.has("itag") && format.get("itag").isJsonPrimitive()) {
                    try {
                        itag = format.get("itag").getAsInt();
                    } catch (RuntimeException ignored) {
                        itag = 0;
                    }
                }
                int bitrate = 0;
                if (format.has("bitrate") && format.get("bitrate").isJsonPrimitive()) {
                    try {
                        bitrate = format.get("bitrate").getAsInt();
                    } catch (RuntimeException ignored) {
                        bitrate = 0;
                    }
                }
                if (mime.startsWith("audio/mp4") && itag == 140) {
                    aac128 = streamUrl;
                } else if (mime.startsWith("audio/mp4") && itag == 139) {
                    lowAac = streamUrl;
                } else if (mime.startsWith("audio/mp4") && bitrate >= bestMp4Rate) {
                    bestMp4 = streamUrl;
                    bestMp4Rate = bitrate;
                } else if (mime.startsWith("audio/") && bitrate >= bestAudioRate) {
                    bestAudio = streamUrl;
                    bestAudioRate = bitrate;
                }
            }
        }
        if (aac128 != null) return aac128;
        if (bestMp4 != null) return bestMp4;
        if (lowAac != null) return lowAac;
        return bestAudio;
    }

    /** 选择接近 360p 的 MP4 无声视频流，供小尺寸悬浮窗预览。 */
    static String pickVideoUrl(JsonObject root) {
        JsonObject streaming = object(root, "streamingData");
        if (streaming == null) return "";
        String best = "";
        long bestScore = Long.MAX_VALUE;
        for (String key : new String[]{"adaptiveFormats", "formats"}) {
            if (!streaming.has(key) || !streaming.get(key).isJsonArray()) continue;
            for (JsonElement el : streaming.getAsJsonArray(key)) {
                if (!el.isJsonObject()) continue;
                JsonObject format = el.getAsJsonObject();
                String url = SourceText.text(format, "url");
                String mime = SourceText.text(format, "mimeType");
                if (url.isBlank() || !mime.startsWith("video/")) continue;
                int height = intValue(format, "height");
                int bitrate = intValue(format, "bitrate");
                long score = Math.abs((height > 0 ? height : 360) - 360L) * 100_000L
                        + Math.max(0, bitrate);
                if (!mime.startsWith("video/mp4")) score += 100_000_000L;
                if (score < bestScore) {
                    bestScore = score;
                    best = url;
                }
            }
        }
        return best;
    }

    private static int intValue(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) return 0;
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private PlayerHit requestPlayer(ClientSpec spec, String videoId, String visitor) {
        JsonObject body = new JsonObject();
        body.add("context", spec.context(visitor));
        body.addProperty("videoId", videoId);
        body.addProperty("contentCheckOk", true);
        body.addProperty("racyCheckOk", true);
        Request request = new Request.Builder()
                .url(PLAYER)
                .header("Content-Type", "application/json")
                .header("User-Agent", spec.userAgent)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) return PlayerHit.fail("空响应");
            String text = responseBody.string().trim();
            if (!text.startsWith("{")) return PlayerHit.fail("接口没有返回播放数据");
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject status = object(root, "playabilityStatus");
            String state = SourceText.text(status, "status");
            if ("LOGIN_REQUIRED".equals(state)) return PlayerHit.visitor();
            if (!"OK".equals(state)) {
                String reason = SourceText.text(status, "reason");
                return PlayerHit.fail(reason.isBlank() ? state : reason);
            }
            String audioUrl = pickAudioUrl(root);
            if (audioUrl == null || audioUrl.isBlank()) return PlayerHit.fail("");
            JsonObject details = object(root, "videoDetails");
            PlayerHit hit = new PlayerHit();
            hit.audioUrl = audioUrl;
            hit.videoUrl = pickVideoUrl(root);
            hit.title = SourceText.text(details, "title");
            hit.author = SourceText.text(details, "author");
            hit.cover = thumbnail(details);
            if (details != null && details.has("lengthSeconds")) {
                try {
                    hit.durationMs = details.get("lengthSeconds").getAsLong() * 1000L;
                } catch (RuntimeException ignored) {
                    hit.durationMs = 0;
                }
            }
            return hit;
        } catch (Exception e) {
            return PlayerHit.fail(e.getMessage() == null ? "请求失败" : e.getMessage());
        }
    }

    private String visitor(boolean force) {
        if (!force && visitorData != null && !visitorData.isBlank()) return visitorData;
        synchronized (YoutubeAudioSource.class) {
            if (!force && visitorData != null && !visitorData.isBlank()) return visitorData;
            JsonObject body = new JsonObject();
            body.add("context", CLIENTS[0].context(null));
            Request request = new Request.Builder()
                    .url(VISITOR)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", CLIENTS[0].userAgent)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                if (responseBody == null) return visitorData == null ? "" : visitorData;
                JsonObject root = JsonParser.parseString(responseBody.string()).getAsJsonObject();
                JsonObject ctx = object(root, "responseContext");
                String value = SourceText.text(ctx, "visitorData");
                if (!value.isBlank()) visitorData = value;
            } catch (Exception ignored) {
                // 没有 visitor 时仍尝试播放接口
            }
            return visitorData == null ? "" : visitorData;
        }
    }

    private static String thumbnail(JsonObject details) {
        JsonObject thumbs = object(details, "thumbnail");
        if (thumbs == null || !thumbs.has("thumbnails") || !thumbs.get("thumbnails").isJsonArray()) return "";
        JsonArray arr = thumbs.getAsJsonArray("thumbnails");
        if (arr.isEmpty() || !arr.get(arr.size() - 1).isJsonObject()) return "";
        return SourceText.text(arr.get(arr.size() - 1).getAsJsonObject(), "url");
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return null;
        return parent.getAsJsonObject(key);
    }

    private static final class ClientSpec {
        final String name;
        final String version;
        final String userAgent;
        final String deviceMake;
        final String deviceModel;
        final String osName;
        final String osVersion;
        final int androidSdk;

        ClientSpec(String name, String version, String userAgent,
                   String deviceMake, String deviceModel, String osName, String osVersion, int androidSdk) {
            this.name = name;
            this.version = version;
            this.userAgent = userAgent;
            this.deviceMake = deviceMake;
            this.deviceModel = deviceModel;
            this.osName = osName;
            this.osVersion = osVersion;
            this.androidSdk = androidSdk;
        }

        JsonObject context(String visitor) {
            JsonObject client = new JsonObject();
            client.addProperty("clientName", name);
            client.addProperty("clientVersion", version);
            client.addProperty("hl", "en");
            client.addProperty("gl", "US");
            client.addProperty("deviceMake", deviceMake);
            client.addProperty("deviceModel", deviceModel);
            client.addProperty("osName", osName);
            client.addProperty("osVersion", osVersion);
            if (androidSdk > 0) client.addProperty("androidSdkVersion", androidSdk);
            if (visitor != null && !visitor.isBlank()) client.addProperty("visitorData", visitor);
            JsonObject context = new JsonObject();
            context.add("client", client);
            return context;
        }
    }

    private static final class PlayerHit {
        String audioUrl;
        String videoUrl = "";
        String title = "";
        String author = "";
        String cover = "";
        String reason = "";
        long durationMs;
        boolean needsVisitor;

        static PlayerHit fail(String reason) {
            PlayerHit hit = new PlayerHit();
            hit.reason = reason == null ? "" : reason;
            return hit;
        }

        static PlayerHit visitor() {
            PlayerHit hit = new PlayerHit();
            hit.needsVisitor = true;
            hit.reason = "LOGIN_REQUIRED";
            return hit;
        }
    }
}
