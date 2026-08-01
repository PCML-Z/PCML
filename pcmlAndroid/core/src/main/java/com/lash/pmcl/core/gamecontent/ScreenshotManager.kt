package com.lash.pmcl.core.gamecontent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * 截图管理：扫描 screenshots 目录下的图片文件。
 *
 * Android 版本：从 Java 移植，保留符号链接拒绝（LinkOption.NOFOLLOW_LINKS）、
 * 按修改时间倒序、父目录名校验等全部安全防护。
 */
class ScreenshotManager(workDir: Path) {

    private val workDir: Path = workDir
    val screenshotsDir: Path = workDir.resolve("screenshots")

    /** 单个截图信息 */
    class Screenshot(
        val name: String,
        val path: Path,
        val size: Long,
        val modified: Long,
        val source: String = "PMCL"
    )

    /** 扫描默认 screenshots 目录 */
    @Throws(IOException::class)
    fun list(): List<Screenshot> = list(screenshotsDir, "PMCL")

    /**
     * 扫描指定 screenshots 目录下的所有图片。
     * @param screenshotsDir 某个 screenshots 目录
     * @param source         来源标签（用于 UI 区分截图归属）
     */
    @Throws(IOException::class)
    fun list(screenshotsDir: Path, source: String): List<Screenshot> {
        val result = ArrayList<Screenshot>()
        if (!Files.isDirectory(screenshotsDir)) return result
        Files.list(screenshotsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { isImage(it.fileName.toString()) }
                .forEach { p ->
                    try {
                        val attrs = Files.readAttributes(p, BasicFileAttributes::class.java)
                        result.add(
                            Screenshot(
                                p.fileName.toString(),
                                p,
                                attrs.size(),
                                attrs.lastModifiedTime().toMillis(),
                                source
                            )
                        )
                    } catch (e: Throwable) {
                        // 单个文件读取失败不应中断其他截图扫描
                    }
                }
        }
        // 按修改时间倒序
        result.sortWith { a, b -> b.modified.compareTo(a.modified) }
        return result
    }

    @Throws(IOException::class)
    fun delete(shot: Screenshot) {
        val path = shot.path
        val file = path.toAbsolutePath().normalize()
        val parent = file.parent
        if (parent == null || parent.fileName == null
            || !"screenshots".equals(parent.fileName.toString(), ignoreCase = true)
        ) {
            throw IOException("拒绝删除：路径不在 screenshots 目录下: $file")
        }
        // normalize + startsWith；拒绝符号链接（弱父目录名校验不足以防穿越）
        val parentAbs = parent.toAbsolutePath().normalize()
        if (!file.startsWith(parentAbs) || parentAbs != file.parent) {
            throw IOException("拒绝删除：路径越界: $file")
        }
        // 工作目录内截图必须仍落在 workDir 下（防御路径规范化后的越界）
        val workAbs = workDir.toAbsolutePath().normalize()
        if (parentAbs.startsWith(workAbs) && !file.startsWith(workAbs)) {
            throw IOException("拒绝删除：路径越出工作目录: $file")
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("拒绝删除：不是普通图片文件: $file")
        }
        if (!isImage(file.fileName.toString())) {
            throw IOException("拒绝删除：不是图片文件: $file")
        }
        Files.deleteIfExists(file)
    }

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".gif") || lower.endsWith(".bmp")
    }
}
