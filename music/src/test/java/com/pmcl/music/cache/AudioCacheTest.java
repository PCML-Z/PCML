package com.pmcl.music.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AudioCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsLocalFileWithoutNetwork() throws Exception {
        Path audio = tempDir.resolve("song.mp3");
        Files.write(audio, new byte[]{1, 2, 3});

        AudioCache cache = new AudioCache(tempDir.resolve("cache"));

        assertEquals(audio.toAbsolutePath().toString(),
                cache.findCached("local", audio.toString(), audio.toString()));
    }

    @Test
    void returnsFreshRemoteCacheAndSkipsMissingOrExpiredData() throws Exception {
        Path cacheDir = tempDir.resolve("cache");
        Files.createDirectories(cacheDir);
        String source = "bilibili";
        String id = "BV123";
        String url = "https://cdn.example/audio.m4s";
        String key = sha1(source + "|" + id);
        Path data = cacheDir.resolve(key + ".bin");
        Path meta = cacheDir.resolve(key + ".meta");
        AudioCache cache = new AudioCache(cacheDir, 60_000);

        assertNull(cache.findCached(source, id, url));

        Files.write(data, new byte[]{1, 2, 3});
        Files.writeString(meta, Long.toString(System.currentTimeMillis()));
        assertEquals(data.toAbsolutePath().toString(), cache.findCached(source, id, url));

        Files.writeString(meta, Long.toString(System.currentTimeMillis() - 120_000));
        assertNull(cache.findCached(source, id, url));
    }

    private static String sha1(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
