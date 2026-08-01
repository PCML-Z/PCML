package com.lash.pmcl.core.download

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Dns

/**
 * DNS 缓存：60 秒 TTL，避免频繁 DNS 查询。
 * 从 PMCL 桌面版 DownloadManager.FastDns 提取为独立类。
 */
class FastDns : Dns {
    /** DNS 缓存 TTL（毫秒） */
    private val cacheTtlMs = 60_000L

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isEmpty()) {
            throw UnknownHostException("hostname is empty")
        }
        // 命中缓存且未过期
        val cached = cache[hostname]
        if (cached != null) {
            if (System.currentTimeMillis() < cached.expiresAt) {
                return cached.addresses
            }
            // 过期：移除旧条目
            cache.remove(hostname, cached)
        }
        // 系统 DNS 解析
        val result = Dns.SYSTEM.lookup(hostname)
        // 写入缓存（带 TTL）
        cache[hostname] = CacheEntry(result, System.currentTimeMillis() + cacheTtlMs)
        return result
    }
}
