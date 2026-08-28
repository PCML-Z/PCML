package com.pmcl.ui.util

import com.pmcl.core.LauncherConfig
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 图片磁盘缓存：把下载的原始图片字节落盘（`~/.pmcl/cache/images/`），
 * 键为 URL 的 SHA-256，跨启动器会话复用，避免新闻等远程图片重复下载。
 *
 * - 存原始字节（不重编码），命中后仍走 [decodeSampledBitmap] 按需降采样
 * - 原子写入（tmp + move），进程中断不产生半截文件
 * - 目录总量超预算（64MB）时按 lastModified 从旧到新淘汰，回落到 90%
 * - 任何缓存异常均静默降级：读失败当未命中，写失败不影响显示
 */
object DiskImageCache {
    private const val MAX_DIR_BYTES: Long = 64L * 1024 * 1024
    private val CACHE_DIR: Path = LauncherConfig.pmclHome()
        .resolve("cache").resolve("images").toAbsolutePath().normalize()

    private fun fileFor(url: String): Path {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return CACHE_DIR.resolve(name)
    }

    /** 读取缓存的原始字节；未命中或读取失败返回 null */
    fun readBytes(url: String): ByteArray? = try {
        val file = fileFor(url)
        if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    } catch (_: Throwable) {
        null
    }

    /** 把原始字节写入缓存（原子替换）；失败静默 */
    fun writeBytes(url: String, bytes: ByteArray) {
        try {
            Files.createDirectories(CACHE_DIR)
            val target = fileFor(url)
            val tmp = Files.createTempFile(CACHE_DIR, "img-", ".tmp")
            try {
                Files.write(tmp, bytes)
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
            evictIfNeeded()
        } catch (_: Throwable) {
            // 缓盘失败不影响显示
        }
    }

    /** 目录超预算时按最旧优先淘汰，总量回落到 90% 以内 */
    private fun evictIfNeeded() {
        try {
            val files = Files.list(CACHE_DIR).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
            var total = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
            if (total <= MAX_DIR_BYTES) return
            val oldestFirst = files.sortedBy { f ->
                runCatching { Files.getLastModifiedTime(f).toMillis() }.getOrDefault(0L)
            }
            val budget = MAX_DIR_BYTES * 9 / 10
            for (f in oldestFirst) {
                if (total <= budget) break
                val sz = runCatching { Files.size(f) }.getOrDefault(0L)
                if (Files.deleteIfExists(f)) total -= sz
            }
        } catch (_: Throwable) {
            // 清理失败下次再试
        }
    }
}
