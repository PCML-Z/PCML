package com.pmcl.core.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 磁盘持久化缓存：统一管理启动器各数据加载点的缓存。
 * <p>
 * 缓存目录：{@code ~/.pmcl/cache/}
 */
public final class DataCache {

    private static final Path CACHE_DIR = com.pmcl.core.LauncherConfig.pmclHome().resolve("cache")
            .toAbsolutePath().normalize();
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT, java.lang.reflect.Modifier.STATIC)
            .create();

    private static final Map<String, CacheEntry<?>> memCache = new ConcurrentHashMap<>();
    /** Per-key locks so concurrent saves of the same key don't clobber .tmp (H28). */
    private static final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    private static final class CacheEntry<T> {
        final T data;
        final long savedAt;
        CacheEntry(T data, long savedAt) { this.data = data; this.savedAt = savedAt; }
    }

    static {
        try { Files.createDirectories(CACHE_DIR); } catch (IOException ignored) {}
    }

    private DataCache() {}

    /** C15: reject path traversal in cache keys. */
    static Path resolveCacheFile(String key) {
        if (key == null || key.isBlank()
                || key.contains("..") || key.contains("/") || key.contains("\\")
                || key.indexOf('\0') >= 0
                || !key.matches("[A-Za-z0-9._\\-]{1,200}")) {
            throw new IllegalArgumentException("illegal cache key: " + key);
        }
        Path file = CACHE_DIR.resolve(key + ".json").normalize();
        if (!file.startsWith(CACHE_DIR)) {
            throw new IllegalArgumentException("cache path escapes cache dir: " + key);
        }
        return file;
    }

    public static <T> void save(String key, T data) {
        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                Path file = resolveCacheFile(key);
                Map<String, Object> wrapper = new HashMap<>();
                wrapper.put("savedAt", Instant.now().toEpochMilli());
                wrapper.put("data", data);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + Thread.currentThread().getId());
                Files.writeString(tmp, GSON.toJson(wrapper), java.nio.charset.StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                memCache.put(key, new CacheEntry<>(data, System.currentTimeMillis()));
            } catch (IllegalArgumentException e) {
                System.err.println("[DataCache] " + e.getMessage());
            } catch (Exception e) {
                System.err.println("[DataCache] save failed for " + key + ": " + e.getMessage());
            }
        }
    }

    public static <T> T load(String key, TypeToken<T> typeToken) {
        CacheEntry<?> entry = memCache.get(key);
        if (entry != null) {
            @SuppressWarnings("unchecked")
            T data = (T) entry.data;
            return data;
        }
        try {
            Path file = resolveCacheFile(key);
            if (!Files.exists(file)) return null;
            String json = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonElement dataEl = root.getAsJsonObject().get("data");
            if (dataEl == null || dataEl.isJsonNull()) return null;
            T data = GSON.fromJson(dataEl, typeToken.getType());
            long savedAt = root.getAsJsonObject().has("savedAt")
                    ? root.getAsJsonObject().get("savedAt").getAsLong()
                    : 0;
            memCache.put(key, new CacheEntry<>(data, savedAt));
            return data;
        } catch (IllegalArgumentException e) {
            System.err.println("[DataCache] " + e.getMessage());
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static <T> Object[] loadWithTimestamp(String key, TypeToken<T> typeToken) {
        CacheEntry<?> entry = memCache.get(key);
        if (entry != null) {
            @SuppressWarnings("unchecked")
            T data = (T) entry.data;
            return new Object[]{data, entry.savedAt};
        }
        try {
            Path file = resolveCacheFile(key);
            if (!Files.exists(file)) return null;
            String json = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonElement dataEl = root.getAsJsonObject().get("data");
            if (dataEl == null || dataEl.isJsonNull()) return null;
            T data = GSON.fromJson(dataEl, typeToken.getType());
            long savedAt = root.getAsJsonObject().has("savedAt")
                    ? root.getAsJsonObject().get("savedAt").getAsLong()
                    : 0;
            return new Object[]{data, savedAt};
        } catch (IllegalArgumentException e) {
            System.err.println("[DataCache] " + e.getMessage());
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isExpired(long savedAt, long ttlMillis) {
        return System.currentTimeMillis() - savedAt > ttlMillis;
    }

    public static void remove(String key) {
        memCache.remove(key);
        try {
            Files.deleteIfExists(resolveCacheFile(key));
        } catch (IllegalArgumentException e) {
            System.err.println("[DataCache] " + e.getMessage());
        } catch (IOException ignored) {}
    }

    public static void clearAll() {
        memCache.clear();
        try (var stream = Files.list(CACHE_DIR)) {
            stream.forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    public static Path getCacheDir() { return CACHE_DIR; }
}
