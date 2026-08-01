package com.lash.pmcl.core.cache

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 磁盘持久化缓存：统一管理启动器各数据加载点的缓存 — Android Kotlin 版。
 *
 * 与桌面版差异：
 * - 不再使用 System.getProperty("user.home")，cacheDir 通过构造函数注入。
 * - 保留磁盘 + 内存双层缓存。
 * - 保留 per-key 锁防并发覆盖（H28）。
 * - 保留原子写入（.tmp.<threadId> → ATOMIC_MOVE，含回退）与路径穿越防护（C15）。
 *
 * @param cacheDir 缓存目录（由调用方通过 PmclPaths.cache 传入）
 */
class DataCache(cacheDir: Path) {

    private val cacheDir: Path = cacheDir.toAbsolutePath().normalize()

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .excludeFieldsWithModifiers(
            java.lang.reflect.Modifier.TRANSIENT,
            java.lang.reflect.Modifier.STATIC
        )
        .create()

    private val memCache = ConcurrentHashMap<String, CacheEntry<*>>()

    /** Per-key locks so concurrent saves of the same key don't clobber .tmp (H28). */
    private val keyLocks = ConcurrentHashMap<String, Any>()

    private class CacheEntry<T>(val data: T, val savedAt: Long)

    init {
        try {
            Files.createDirectories(this.cacheDir)
        } catch (_: IOException) {
        }
    }

    /** C15: reject path traversal in cache keys. */
    fun resolveCacheFile(key: String): Path {
        if (key.isBlank()
            || key.contains("..") || key.contains("/") || key.contains("\\")
            || key.indexOf('\u0000') >= 0
            || !key.matches(Regex("[A-Za-z0-9._-]{1,200}"))
        ) {
            throw IllegalArgumentException("illegal cache key: $key")
        }
        val file = cacheDir.resolve("$key.json").normalize()
        if (!file.startsWith(cacheDir)) {
            throw IllegalArgumentException("cache path escapes cache dir: $key")
        }
        return file
    }

    fun <T> save(key: String, data: T) {
        val lock = keyLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            try {
                val file = resolveCacheFile(key)
                val wrapper = HashMap<String, Any?>()
                wrapper["savedAt"] = Instant.now().toEpochMilli()
                wrapper["data"] = data
                val tmp = file.resolveSibling(
                    file.fileName.toString() + ".tmp." + Thread.currentThread().id
                )
                FileUtils.writeString(tmp, gson.toJson(wrapper))
                try {
                    Files.move(
                        tmp, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }
                memCache[key] = CacheEntry(data, System.currentTimeMillis())
            } catch (e: IllegalArgumentException) {
                System.err.println("[DataCache] ${e.message}")
            } catch (e: Exception) {
                System.err.println("[DataCache] save failed for $key: ${e.message}")
            }
        }
    }

    fun <T> load(key: String, typeToken: TypeToken<T>): T? {
        val entry = memCache[key]
        if (entry != null) {
            @Suppress("UNCHECKED_CAST")
            return entry.data as T
        }
        try {
            val file = resolveCacheFile(key)
            if (!Files.exists(file)) return null
            val json = FileUtils.readString(file)
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return null
            val dataEl = root.asJsonObject.get("data") ?: return null
            if (dataEl.isJsonNull) return null
            val data: T = gson.fromJson(dataEl, typeToken.type)
            val savedAt = if (root.asJsonObject.has("savedAt"))
                root.asJsonObject.get("savedAt").asLong else 0
            memCache[key] = CacheEntry(data, savedAt)
            return data
        } catch (e: IllegalArgumentException) {
            System.err.println("[DataCache] ${e.message}")
            return null
        } catch (t: Throwable) {
            return null
        }
    }

    fun <T> loadWithTimestamp(key: String, typeToken: TypeToken<T>): Array<Any?>? {
        val entry = memCache[key]
        if (entry != null) {
            @Suppress("UNCHECKED_CAST")
            return arrayOf(entry.data as T, entry.savedAt)
        }
        try {
            val file = resolveCacheFile(key)
            if (!Files.exists(file)) return null
            val json = FileUtils.readString(file)
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return null
            val dataEl = root.asJsonObject.get("data") ?: return null
            if (dataEl.isJsonNull) return null
            val data: T = gson.fromJson(dataEl, typeToken.type)
            val savedAt = if (root.asJsonObject.has("savedAt"))
                root.asJsonObject.get("savedAt").asLong else 0
            return arrayOf(data, savedAt)
        } catch (e: IllegalArgumentException) {
            System.err.println("[DataCache] ${e.message}")
            return null
        } catch (t: Throwable) {
            return null
        }
    }

    fun isExpired(savedAt: Long, ttlMillis: Long): Boolean =
        System.currentTimeMillis() - savedAt > ttlMillis

    fun remove(key: String) {
        memCache.remove(key)
        try {
            Files.deleteIfExists(resolveCacheFile(key))
        } catch (e: IllegalArgumentException) {
            System.err.println("[DataCache] ${e.message}")
        } catch (_: IOException) {
        }
    }

    fun clearAll() {
        memCache.clear()
        try {
            Files.list(cacheDir).use { stream ->
                stream.forEach { f ->
                    try {
                        Files.deleteIfExists(f)
                    } catch (_: IOException) {
                    }
                }
            }
        } catch (_: IOException) {
        }
    }

    fun getCacheDir(): Path = cacheDir
}
