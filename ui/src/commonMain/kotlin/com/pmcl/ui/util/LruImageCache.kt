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

    // 已知下载/解码失败的 URL，避免每次重组都重试
    private val failedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 获取缓存中的图片，未命中返回 null */
    @Synchronized
    fun get(url: String): ImageBitmap? = map[url]

    /** 是否为已知失败 URL（避免重试） */
    fun isKnownFailed(url: String): Boolean = url in failedUrls

    /** 标记某 URL 下载/解码失败 */
    fun markFailed(url: String) { failedUrls.add(url) }

    /** 写入缓存，超过容量时自动淘汰最久未访问的条目（LRU） */
    @Synchronized
    fun put(url: String, bitmap: ImageBitmap) {
        map[url] = bitmap
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() {
        map.clear()
    }
}
