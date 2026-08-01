package com.lash.pmcl.core.download

import java.util.concurrent.ConcurrentHashMap

/**
 * 下载镜像源管理。
 * <p>
 * 支持官方 / BMCLAPI / 自定义三种模式。BMCLAPI 通过域名重写加速 Minecraft 资源。
 * <p>
 * 重写规则参考 <a href="https://bmclapi2.bangbang93.com/">BMCLAPI 文档</a>。
 * <p>
 * H1 修复：内置 per-host 熔断器。镜像域名连续失败 N 次后临时熔断（默认 5 分钟 TTL），
 * rewrite 跳过被熔断的镜像域名回退到官方源，避免 BMCLAPI 宕机时全线下载失败。
 */
class MirrorManager {

    enum class MirrorType {
        OFFICIAL,
        BMCLAPI,
        CUSTOM
    }

    @Volatile
    var type: MirrorType = MirrorType.OFFICIAL

    @Volatile
    var customBase: String = ""

    /** 官方域名 → BMCLAPI 域名映射 */
    private val BMCLAPI_MAP: MutableMap<String, String> = linkedMapOf()

    init {
        // 版本清单 / 元数据
        BMCLAPI_MAP["piston-meta.mojang.com"] = "bmclapi2.bangbang93.com"
        BMCLAPI_MAP["launchermeta.mojang.com"] = "bmclapi2.bangbang93.com"
        BMCLAPI_MAP["launcher.mojang.com"] = "bmclapi2.bangbang93.com"
        // Java runtime 文件对象（Mojang 分文件 JRE）
        BMCLAPI_MAP["piston-data.mojang.com"] = "bmclapi2.bangbang93.com"
        // 库
        BMCLAPI_MAP["libraries.minecraft.net"] = "bmclapi2.bangbang93.com/maven"
        // 资产
        BMCLAPI_MAP["resources.download.minecraft.net"] = "bmclapi2.bangbang93.com/assets"
        // Forge / Fabric / Quilt
        BMCLAPI_MAP["files.minecraftforge.net"] = "bmclapi2.bangbang93.com"
        BMCLAPI_MAP["maven.fabricmc.net"] = "bmclapi2.bangbang93.com/maven"
        BMCLAPI_MAP["meta.fabricmc.net"] = "bmclapi2.bangbang93.com/fabric-meta"
        BMCLAPI_MAP["meta.quiltmc.org"] = "bmclapi2.bangbang93.com/quilt-meta"
        BMCLAPI_MAP["maven.quiltmc.org"] = "bmclapi2.bangbang93.com/maven"
    }

    /** 熔断阈值：连续失败 N 次后熔断 */
    private val CIRCUIT_FAILURE_THRESHOLD = 3
    /** 熔断 TTL：5 分钟后允许半开探测 */
    private val CIRCUIT_TTL_MS = 5 * 60_000L

    /** per-host 熔断状态：host → [失败计数, 熔断到期时间戳] */
    private val circuit = ConcurrentHashMap<String, LongArray>()

    /**
     * 标记某 host 下载失败（连续失败达阈值后熔断）。
     * 在 DownloadManager 捕获到 HTTP 4xx/5xx 或网络错误时调用。
     */
    fun markFailure(host: String?) {
        if (host.isNullOrEmpty()) return
        circuit.compute(host) { _, v ->
            val now = System.currentTimeMillis()
            if (v == null) return@compute longArrayOf(1, 0)
            // 已熔断状态下不累加
            if (v[1] > 0 && now < v[1]) return@compute v
            // 之前熔断已过期，重置计数
            if (v[1] > 0 && now >= v[1]) return@compute longArrayOf(1, 0)
            v[0]++
            if (v[0] >= CIRCUIT_FAILURE_THRESHOLD) {
                v[1] = now + CIRCUIT_TTL_MS
            }
            v
        }
    }

    /** 标记某 host 下载成功（重置失败计数，关闭熔断） */
    fun markSuccess(host: String?) {
        if (host.isNullOrEmpty()) return
        circuit.remove(host)
    }

    /** 判断某 host 是否当前处于熔断状态 */
    private fun isCircuitOpen(host: String): Boolean {
        val state = circuit[host] ?: return false
        val now = System.currentTimeMillis()
        if (state[1] == 0L) return false
        if (now >= state[1]) {
            // 熔断过期，允许半开探测
            return false
        }
        return true
    }

    /** 提取 URL 的 host 部分 */
    private fun extractHost(url: String): String {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx < 0) return ""
        val hostStart = schemeIdx + 3
        var pathIdx = url.indexOf('/', hostStart)
        if (pathIdx < 0) pathIdx = url.length
        // 去掉端口
        val colon = url.indexOf(':', hostStart)
        val end = if (colon > 0 && colon < pathIdx) colon else pathIdx
        return url.substring(hostStart, end)
    }

    /**
     * 根据当前镜像类型重写 URL。
     * 自定义模式：将原 URL 的 scheme+host 替换为 customBase（保持 path）。
     * H1: 镜像 host 熔断时回退到官方源 URL。
     */
    fun rewrite(url: String?): String {
        if (url.isNullOrEmpty()) return url ?: ""
        if (type == MirrorType.OFFICIAL) return url

        if (type == MirrorType.BMCLAPI) {
            for ((origin, mirrorHost) in BMCLAPI_MAP) {
                val originFull = "https://$origin"
                if (url.startsWith(originFull)) {
                    // H1: 检查 BMCLAPI 镜像 host 是否熔断
                    if (isCircuitOpen(mirrorHost)) {
                        return url // 回退到官方源 URL
                    }
                    return "https://$mirrorHost${url.substring(originFull.length)}"
                }
            }
            return url
        }

        // CUSTOM：替换 scheme+host，保留 path/query
        if (customBase.isEmpty()) return url
        val schemeIdx = url.indexOf("://")
        if (schemeIdx < 0) return url
        val customHost = extractHost(customBase)
        if (customHost.isNotEmpty() && isCircuitOpen(customHost)) {
            return url // 回退到官方源
        }
        val pathIdx = url.indexOf('/', schemeIdx + 3)
        val path = if (pathIdx >= 0) url.substring(pathIdx) else "/"
        val base = if (customBase.endsWith("/")) customBase.substring(0, customBase.length - 1) else customBase
        return base + path
    }

    /** 仅供测试 / 调试：手动清除所有熔断状态 */
    fun resetCircuit() {
        circuit.clear()
    }
}
