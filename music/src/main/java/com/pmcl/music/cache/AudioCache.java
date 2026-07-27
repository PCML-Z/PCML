package com.pmcl.music.cache;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 音频流磁盘缓存：按 sourceType+originalId 或 URL 哈希落盘到 ~/.pmcl/music/cache/。
 * 远程 URL 带 headers 下载；本地路径直接返回。
 */
public final class AudioCache {

    private static final long DEFAULT_TTL_MS = 2L * 60L * 60L * 1000L; // 2h（对齐 B站流时效）

    private final Path cacheDir;
    private final OkHttpClient client;
    private final long ttlMs;

    public AudioCache(Path cacheDir) {
        this(cacheDir, DEFAULT_TTL_MS);
    }

    public AudioCache(Path cacheDir, long ttlMs) {
        this.cacheDir = cacheDir;
        this.ttlMs = ttlMs;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    /**
     * 确保返回可播放的本地路径或原 URL。
     * 本地文件原样返回；远程流下载到缓存（命中且未过期则复用）。
     *
     * @return 本地缓存路径，或无法缓存时返回原始 audioUrl
     */
    public String ensureCached(String sourceType,
                               String originalId,
                               String audioUrl,
                               Map<String, String> headers) throws IOException {
        if (audioUrl == null || audioUrl.isBlank()) {
            throw new IOException("empty audioUrl");
        }
        // 已是本地路径
        if (!audioUrl.startsWith("http://") && !audioUrl.startsWith("https://")) {
            Path p = Path.of(audioUrl);
            if (Files.isRegularFile(p)) return p.toAbsolutePath().toString();
            return audioUrl;
        }

        if (audioUrl.startsWith("http://") || audioUrl.startsWith("https://")) {
            String err = com.pmcl.core.util.SsrfChecker.validate(audioUrl);
            if (err != null) throw new IOException("Unsafe audio URL: " + err);
        }

        Files.createDirectories(cacheDir);
        String key = cacheKey(sourceType, originalId, audioUrl);
        Path meta = cacheDir.resolve(key + ".meta");
        Path data = cacheDir.resolve(key + ".bin");

        if (Files.isRegularFile(data) && Files.isRegularFile(meta)) {
            try {
                long savedAt = Long.parseLong(Files.readString(meta).trim());
                if (System.currentTimeMillis() - savedAt < ttlMs && Files.size(data) > 0) {
                    return data.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {
                // 重新下载
            }
        }

        Request.Builder rb = new Request.Builder().url(audioUrl);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
        }
        Path tmp = cacheDir.resolve(key + ".tmp");
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("cache download HTTP " + resp.code());
            }
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("empty body");
            try (InputStream in = body.byteStream();
                 OutputStream out = Files.newOutputStream(tmp)) {
                long MAX_AUDIO_SIZE = 500L * 1024 * 1024; // 500MB
                long total = 0;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_AUDIO_SIZE) {
                        try { out.close(); } catch (IOException ignored) {}
                        try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                        throw new IOException("音频文件过大，超过 " + MAX_AUDIO_SIZE + " 字节");
                    }
                    out.write(buf, 0, n);
                }
            }
        }
        Files.move(tmp, data, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(meta, Long.toString(System.currentTimeMillis()));
        return data.toAbsolutePath().toString();
    }

    public void clear() throws IOException {
        if (!Files.isDirectory(cacheDir)) return;
        try (var stream = Files.list(cacheDir)) {
            stream.forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    private static String cacheKey(String sourceType, String originalId, String audioUrl) {
        String raw = (sourceType == null ? "" : sourceType) + "|"
                + (originalId == null || originalId.isBlank() ? audioUrl : originalId);
        return sha1Hex(raw);
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(dig.length * 2);
            for (byte b : dig) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
