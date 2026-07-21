package com.pmcl.core.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 磁盘持久化缓存：统一管理启动器各数据加载点的缓存。
 * <p>
 * 缓存目录：{@code ~/.pmcl/cache/}
 * <p>
 * 工作模式：stale-while-revalidate
 * <ul>
 *   <li>启动时优先读取磁盘缓存秒开</li>
 *   <li>后台异步重新加载最新数据并更新缓存</li>
 *   <li>网络数据用 TTL 判断是否过期</li>
 *   <li>本地扫描数据用文件元数据（mtime/size）校验是否需要重扫</li>
 * </ul>
 */
public final class DataCache {

    private static final Path CACHE_DIR = Paths.get(System.getProperty("user.home"), ".pmcl", "cache");
    // allowFinal 字段修改：Gson 默认会跳过 final 字段，需要显式允许
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT, java.lang.reflect.Modifier.STATIC)
            .create();

    /** 内存级缓存避免重复读盘（key = 缓存文件名） */
    private static final Map<String, CacheEntry<?>> memCache = new ConcurrentHashMap<>();

    /** 缓存条目：数据 + 时间戳 */
    private static final class CacheEntry<T> {
        final T data;
        final long savedAt;
        CacheEntry(T data, long savedAt) { this.data = data; this.savedAt = savedAt; }
    }

    static {
        try { Files.createDirectories(CACHE_DIR); } catch (IOException ignored) {}
    }

    private DataCache() {}

    /**
     * 保存数据到缓存。
     * M51 修复：移除 synchronized 关键字，统一使用 ConcurrentHashMap + 原子文件移动。
     * save() 和 load() 都通过 ConcurrentHashMap 保证内存层面线程安全，
     * 磁盘层面通过 tmp + ATOMIC_MOVE 保证写入原子性。
     * 旧的 synchronized 只在 save() 上，load() 无锁，导致锁策略不一致且不必要地串行化不同 key 的写入。
     * @param key  缓存键（用作文件名，如 "versions_remote"）
     * @param data 数据对象（会被 JSON 序列化）
     */
    public static <T> void save(String key, T data) {
        try {
            Path file = CACHE_DIR.resolve(key + ".json");
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("savedAt", Instant.now().toEpochMilli());
            wrapper.put("data", data);
            // 原子写入：防止并发写损坏
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(wrapper), java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // 先写磁盘成功再更新内存缓存，避免内存指向尚未持久化的数据
            memCache.put(key, new CacheEntry<>(data, System.currentTimeMillis()));
        } catch (Exception e) {
            System.err.println("[DataCache] save failed for " + key + ": " + e.getMessage());
        }
    }

    /**
     * 读取缓存。
     * @param key       缓存键
     * @param typeToken Gson TypeToken（处理泛型 List 等）
     * @return 缓存数据，不存在或解析失败返回 null
     */
    public static <T> T load(String key, TypeToken<T> typeToken) {
        // 先查内存缓存
        CacheEntry<?> entry = memCache.get(key);
        if (entry != null) {
            @SuppressWarnings("unchecked")
            T data = (T) entry.data;
            return data;
        }
        try {
            Path file = CACHE_DIR.resolve(key + ".json");
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
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读取缓存并附带保存时间。
     * @return [数据, savedAtMillis]，不存在返回 null
     */
    public static <T> Object[] loadWithTimestamp(String key, TypeToken<T> typeToken) {
        // 先查内存缓存，命中则直接返回，避免磁盘 I/O
        CacheEntry<?> entry = memCache.get(key);
        if (entry != null) {
            @SuppressWarnings("unchecked")
            T data = (T) entry.data;
            return new Object[]{data, entry.savedAt};
        }
        try {
            Path file = CACHE_DIR.resolve(key + ".json");
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
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 判断缓存是否过期。
     * @param savedAt   缓存保存时间（毫秒）
     * @param ttlMillis TTL（毫秒）
     * @return true 表示已过期需要刷新
     */
    public static boolean isExpired(long savedAt, long ttlMillis) {
        return System.currentTimeMillis() - savedAt > ttlMillis;
    }

    /** 清除指定缓存 */
    public static void remove(String key) {
        memCache.remove(key);
        try {
            Files.deleteIfExists(CACHE_DIR.resolve(key + ".json"));
        } catch (IOException ignored) {}
    }

    /** 清除所有缓存 */
    public static void clearAll() {
        memCache.clear();
        try (var stream = Files.list(CACHE_DIR)) {
            stream.forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    /** 缓存目录路径 */
    public static Path getCacheDir() { return CACHE_DIR; }
}
