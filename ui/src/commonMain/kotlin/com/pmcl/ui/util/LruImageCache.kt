package com.pmcl.ui.util

import androidx.compose.ui.graphics.ImageBitmap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 按"插入顺序 + 容量上限"淘汰的图片缓存，替代之前散落各页面的 ConcurrentHashMap + clear() 粗暴策略。
 *
 * 之前 [launchAvatarCache](../page/LaunchPage.kt) 等缓存命中 51 张时直接 clear()，
 * 会让所有正在显示的头像瞬间失效重新下载。这里改为 LRU 淘汰最旧的条目，命中阈值时只淘汰一部分。
 *
 * 线程安全：内部用 synchronized 包裹 LinkedHashMap（非 ConcurrentHashMap，因为需要 accessOrder）。
 * 图片解码本身在 IO 调度器，缓存读写只是 O(1) 哈希操作，synchronized 开销可忽略。
 *
 * @param maxSize 最大条目数（按数量而非字节，简化实现；ImageBitmap 字节数难以精确测量）
 */
class LruImageCache(private val maxSize: Int = 64) {

    private val map = object : LinkedHashMap<String, ImageBitmap>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > maxSize
        }
    }

    // M31 修复：failedUrls 永不清理会导致网络恢复后也不重试。
    // 改为带时间戳的失败记录，超过 TTL 自动失效，允许重试。
    // 记录 (url -> 失败时间戳)，TTL 默认 60 秒（足够避免短时间内反复重试，
    // 又不会因网络抖动永久放弃）。
    private data class FailureEntry(val timestamp: Long)
    private val failedUrls = ConcurrentHashMap<String, FailureEntry>()
    private val failureTtlMs: Long = 60_000L

    /** 获取缓存中的图片，未命中返回 null */
    @Synchronized
    fun get(url: String): ImageBitmap? = map[url]

    /** 是否为已知失败 URL（避免重试）。M31：超过 TTL 的失败记录视为可重试 */
    fun isKnownFailed(url: String): Boolean {
        val entry = failedUrls[url] ?: return false
        if (System.currentTimeMillis() - entry.timestamp > failureTtlMs) {
            // TTL 过期，清除并允许重试
            failedUrls.remove(url, entry)
            return false
        }
        return true
    }

    /** 标记某 URL 下载/解码失败 */
    fun markFailed(url: String) {
        failedUrls[url] = FailureEntry(System.currentTimeMillis())
    }

    /**
     * M31 修复：主动清除失败记录（网络恢复后调用，或用户手动触发刷新时）。
     * 清除后所有 URL 都会重新尝试下载。
     */
    fun clearFailures() {
        failedUrls.clear()
    }

    /** 写入缓存，超过容量时自动淘汰最久未访问的条目（LRU） */
    @Synchronized
    fun put(url: String, bitmap: ImageBitmap) {
        map[url] = bitmap
        // 下载成功，清除该 URL 的失败记录
        failedUrls.remove(url)
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() {
        map.clear()
    }
}
