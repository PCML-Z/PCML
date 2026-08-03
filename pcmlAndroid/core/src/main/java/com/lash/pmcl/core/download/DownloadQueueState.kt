package com.lash.pmcl.core.download

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载队列状态跟踪器。
 * 桌面端用 DownloadQueueManager，Android 上简化实现。
 */
object DownloadQueueState {
    data class Item(
        val id: String,
        val name: String,
        val totalSize: Long,
        var downloaded: Long,
        var done: Boolean,
        var error: String?
    )

    private val items = CopyOnWriteArrayList<Item>()
    private val idCounter = AtomicLong(0)

    /** 注册一个新的下载任务 */
    fun register(name: String, totalSize: Long): String {
        val id = "dl-${idCounter.incrementAndGet()}"
        items.add(Item(id, name, totalSize, 0, false, null))
        android.util.Log.i("PMCL", "[Queue] 注册任务: $id name=$name total=$totalSize items.size=${items.size}")
        return id
    }

    /** 更新下载进度 */
    fun progress(id: String, bytesDownloaded: Long) {
        items.find { it.id == id }?.let { 
            it.downloaded = bytesDownloaded
            android.util.Log.d("PMCL", "[Queue] 进度: $id downloaded=$bytesDownloaded")
        }
    }

    /** 标记完成 */
    fun complete(id: String) {
        items.find { it.id == id }?.let { 
            it.done = true
            android.util.Log.i("PMCL", "[Queue] 完成: $id items.size=${items.size}")
        }
    }

    /** 标记错误 */
    fun error(id: String, message: String) {
        items.find { it.id == id }?.let {
            it.error = message
            it.done = true
        }
    }

    /** 移除任务 */
    fun remove(id: String) {
        items.removeAll { it.id == id }
    }

    /** 活跃任务数 */
    fun activeCount(): Int = items.count { !it.done && it.error == null }

    /** 总任务数 */
    fun totalCount(): Int = items.size

    /** 总体进度 0.0~1.0 */
    fun overallProgress(): Float {
        val active = items.filter { !it.done && it.error == null }
        if (active.isEmpty()) return if (items.isNotEmpty()) 1f else 0f
        val activeTotal = active.sumOf { it.totalSize }
        if (activeTotal == 0L) return 0f
        val activeDownloaded = active.sumOf { it.downloaded }
        // 加上已完成项目的总量（已完成 + 活跃中的进度）
        val completedSize = items.filter { it.done }.sumOf { it.totalSize }
        val totalSize = activeTotal + completedSize
        if (totalSize == 0L) return if (items.isNotEmpty()) 1f else 0f
        return ((completedSize + activeDownloaded).toFloat() / totalSize).coerceIn(0f, 1f)
    }

    /** 获取所有任务（用于 UI 显示） */
    fun allItems(): List<Item> = items.toList()

    /** 清空已完成的任务 */
    fun clearCompleted() {
        items.removeAll { it.done }
    }

    /** 取出并移除最近完成的任务（供飞入动画使用） */
    fun drainCompleted(): List<Item> {
        val done = items.filter { it.done }
        items.removeAll { it.done }
        return done
    }
}
