package com.pmcl.ui.util

import androidx.compose.ui.graphics.ImageBitmap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 按字节预算淘汰的 LRU 图片缓存。
 *
 * 之前按条目数（64）淘汰，全尺寸位图下 64 张峰值可达数百 MB，OOM 风险。
 * 现改为按字节预算淘汰，默认 maxBytes = JVM 最大堆的 1/8。
 *
 * 线程安全：内部用 synchronized 包裹 LinkedHashMap（需要 accessOrder）。
 *
 * @param maxBytes 最大字节预算，默认为 JVM 最大堆的 1/8
 */
class LruImageCache(
    private val maxBytes: Long = Runtime.getRuntime().maxMemory() / 8
) {
    private var currentBytes: Long = 0L

    private val map = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return false  // 手动淘汰在 put() 中处理
        }
    }

    private data class FailureEntry(val timestamp: Long)
    private val failedUrls = ConcurrentHashMap<String, FailureEntry>()
    private val failureTtlMs: Long = 60_000L

    /** 估算 ImageBitmap 占用内存（ARGB 8888 = 4 bytes/pixel） */
    private fun estimateBitmapBytes(bmp: ImageBitmap): Long {
        return try {
            bmp.width.toLong() * bmp.height.toLong() * 4L
        } catch (_: Throwable) {
            // 无法获取尺寸时保守估计 1MB
            1L * 1024 * 1024
        }
    }

    @Synchronized
    fun get(url: String): ImageBitmap? = map[url]

    fun isKnownFailed(url: String): Boolean {
        val entry = failedUrls[url] ?: return false
        if (System.currentTimeMillis() - entry.timestamp > failureTtlMs) {
            failedUrls.remove(url, entry)
            return false
        }
        return true
    }

    fun markFailed(url: String) {
        failedUrls[url] = FailureEntry(System.currentTimeMillis())
    }

    fun clearFailures() {
        failedUrls.clear()
    }

    @Synchronized
    fun put(url: String, bitmap: ImageBitmap) {
        // 如果已存在旧值，先减去旧字节数
        map[url]?.let { old -> currentBytes -= estimateBitmapBytes(old) }
        map[url] = bitmap
        currentBytes += estimateBitmapBytes(bitmap)
        // 淘汰直到低于预算
        while (currentBytes > maxBytes && map.size > 1) {
            val eldest = map.entries.iterator().next()
            currentBytes -= estimateBitmapBytes(eldest.value)
            map.remove(eldest.key)
        }
        failedUrls.remove(url)
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun bytes(): Long = currentBytes

    @Synchronized
    fun clear() {
        map.clear()
        currentBytes = 0L
    }
}
