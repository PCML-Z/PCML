package com.lash.pmcl.core.mods

import com.lash.pmcl.core.util.SafeZipExtractor
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarFile

/**
 * 从 mod jar 中提取图标字节。
 *
 * 优先使用元数据声明的 `iconEntry`；否则尝试常见路径 / 文件名。
 */
object ModIconExtractor {

    private val FALLBACK_NAMES = arrayOf(
        "icon.png", "logo.png", "pack.png", "mod_icon.png",
        "assets/icon.png", "META-INF/icon.png"
    )

    /** 单图标最大字节，防止恶意 jar 撑爆 UI */
    private const val MAX_ICON_BYTES = 2_000_000L

    /**
     * @param jarPath   jar 绝对路径
     * @param iconEntry 元数据中的图标条目（可空）
     * @return PNG/JPG 等图片字节；失败返回 null
     */
    fun extract(jarPath: String?, iconEntry: String?): ByteArray? {
        if (jarPath.isNullOrEmpty()) return null
        val path = Path.of(jarPath)
        if (!Files.isRegularFile(path)) return null
        try {
            JarFile(path.toFile()).use { jar ->
                if (!iconEntry.isNullOrEmpty()) {
                    readEntry(jar, iconEntry)?.let { return it }
                    if (iconEntry.startsWith("/")) {
                        readEntry(jar, iconEntry.substring(1))?.let { return it }
                    }
                }
                for (name in FALLBACK_NAMES) {
                    readEntry(jar, name)?.let { return it }
                }
                // 扫描 assets/*/icon.png
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val n = e.name.lowercase(Locale.ROOT)
                    if ((n.endsWith("/icon.png") || n.endsWith("/logo.png"))
                        && e.size > 0 && e.size < MAX_ICON_BYTES
                    ) {
                        readEntry(jar, e.name)?.let { return it }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun readEntry(jar: JarFile, name: String): ByteArray? {
        try {
            val entry = jar.getJarEntry(name) ?: return null
            if (entry.isDirectory) return null
            val declared = entry.size
            if (declared > MAX_ICON_BYTES) return null
            jar.getInputStream(entry).use { inp ->
                return SafeZipExtractor.readLimited(inp, MAX_ICON_BYTES)
            }
        } catch (_: Throwable) {
            return null
        }
    }
}
