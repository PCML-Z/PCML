package com.lash.pmcl.core.launch

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局日志收集器 — 让 TerminalScreen 和 LaunchScreen 共享游戏日志。
 * 使用固定容量环形缓冲区，避免内存无限增长。
 */
object LogCollector {
    data class Entry(
        val seq: Long,
        val text: String
    )

    private const val MAX_ENTRIES = 5000
    private val entries = CopyOnWriteArrayList<Entry>()
    private var seq = 0L

    @Synchronized
    fun add(text: String) {
        entries.add(Entry(++seq, text))
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    @Synchronized
    fun clear() { entries.clear() }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun all(): List<Entry> = entries.toList()
}
