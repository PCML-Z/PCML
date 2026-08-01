package com.lash.pmcl.core.mods

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mod 文件管理：删除 / 禁用 / 启用 / 打开 mods 目录。
 *
 * 禁用机制：将 jar 重命名为 .jar.disabled（MC 加载器不会加载此后缀的文件）。
 * 启用机制：去掉 .disabled 后缀还原。
 *
 * 所有操作均针对 mods 根目录（即 config.getWorkDir().resolve("mods")），
 * 与 MC 加载器约定一致。MC 1.20+ 也支持子目录分类，但禁用操作只处理根目录文件。
 */
class ModManager(val modsDir: Path) {

    /** scanDirectory 结果缓存（按 mods 目录 mtime 失效） */
    private class CacheEntry(val mods: List<ModMeta>, val mtime: Long)

    @Volatile
    private var cache: CacheEntry? = null

    /** 确保 mods 目录存在 */
    @Throws(IOException::class)
    fun ensureModsDir() {
        if (!Files.isDirectory(modsDir)) {
            Files.createDirectories(modsDir)
        }
    }

    /**
     * 删除指定 jar 文件（按文件名定位，支持 .disabled 后缀）。
     * @return true 删除成功
     */
    @Throws(IOException::class)
    fun deleteMod(jarFileName: String): Boolean = deleteModAt(resolveJar(jarFileName))

    /**
     * 按绝对路径删除 jar（支持版本目录 / 实例目录下的 mod）。
     */
    @Throws(IOException::class)
    fun deleteModAt(jarPath: Path?): Boolean {
        if (jarPath == null || !Files.exists(jarPath)) return false
        Files.delete(jarPath)
        invalidateCache()
        return true
    }

    /**
     * 禁用 mod：将 xxx.jar 重命名为 xxx.jar.disabled。
     * 已禁用的文件不变。
     * @return 新文件名（禁用后）
     */
    @Throws(IOException::class)
    fun disableMod(jarFileName: String): String = disableModAt(resolveJar(jarFileName))

    /**
     * 按绝对路径禁用 mod（支持版本目录 / 实例目录）。
     */
    @Throws(IOException::class)
    fun disableModAt(jarPath: Path?): String {
        if (jarPath == null) throw IOException("文件路径为空")
        val name = jarPath.fileName.toString()
        if (name.lowercase().endsWith(".disabled")) return name
        val dst = jarPath.resolveSibling("$name.disabled")
        if (!Files.exists(jarPath)) throw IOException("文件不存在: $jarPath")
        Files.move(jarPath, dst)
        invalidateCache()
        return dst.fileName.toString()
    }

    /**
     * 启用 mod：将 xxx.jar.disabled 重命名为 xxx.jar。
     * 已启用的文件不变。
     * @return 新文件名（启用后）
     */
    @Throws(IOException::class)
    fun enableMod(jarFileName: String): String = enableModAt(resolveJar(jarFileName))

    /**
     * 按绝对路径启用 mod（支持版本目录 / 实例目录）。
     */
    @Throws(IOException::class)
    fun enableModAt(jarPath: Path?): String {
        if (jarPath == null) throw IOException("文件路径为空")
        val name = jarPath.fileName.toString()
        if (!name.lowercase().endsWith(".disabled")) return name
        val enabledName = name.substring(0, name.length - ".disabled".length)
        val dst = jarPath.resolveSibling(enabledName)
        if (!Files.exists(jarPath)) throw IOException("文件不存在: $jarPath")
        // 目标已存在（同名 jar 已启用）→ 删除禁用副本
        if (Files.exists(dst)) {
            Files.delete(jarPath)
            invalidateCache()
            return enabledName
        }
        Files.move(jarPath, dst)
        invalidateCache()
        return enabledName
    }

    /** 优先按绝对路径；否则回退到全局 mods 目录下的文件名 */
    private fun resolveJar(jarFileName: String?): Path {
        if (jarFileName.isNullOrEmpty()) {
            throw IllegalArgumentException("jar 文件名为空")
        }
        if (jarFileName.contains("..") || jarFileName.contains("/") ||
            jarFileName.contains("\\") || jarFileName.indexOf('\u0000') >= 0
        ) {
            throw IllegalArgumentException("非法 jar 文件名: $jarFileName")
        }
        val resolved = modsDir.resolve(jarFileName).toAbsolutePath().normalize()
        if (!resolved.startsWith(modsDir.toAbsolutePath().normalize())) {
            throw IllegalArgumentException("路径越界: $jarFileName")
        }
        return resolved
    }

    /**
     * 判断指定 mod 文件当前是否被禁用。
     */
    fun isDisabled(jarFileName: String?): Boolean {
        return jarFileName != null && jarFileName.lowercase().endsWith(".disabled")
    }

    /**
     * 检测同名 mod 是否已安装（按 modId 匹配，避免重复下载）。
     * 利用 mods 目录 mtime 缓存扫描结果，目录未变动时直接复用缓存。
     */
    @Throws(IOException::class)
    fun isModInstalled(modId: String): Boolean {
        val mods = getCachedMods()
        for (m in mods) {
            if (modId == m.modId && !m.disabled) return true
        }
        return false
    }

    /**
     * 获取（必要时扫描并缓存）mods 目录下的 mod 列表。
     * 当 mods 目录 mtime 与缓存一致时直接返回缓存，避免重复扫描。
     */
    @Throws(IOException::class)
    private fun getCachedMods(): List<ModMeta> {
        val currentMtime: Long
        try {
            currentMtime = Files.getLastModifiedTime(modsDir).toMillis()
        } catch (e: IOException) {
            // 目录不存在等异常：直接扫描不缓存
            return ModScanner.scanDirectory(modsDir)
        }
        val e = cache
        if (e != null && e.mtime == currentMtime) {
            return e.mods
        }
        val mods = ModScanner.scanDirectory(modsDir)
        cache = CacheEntry(mods, currentMtime)
        return mods
    }

    /**
     * 清除扫描结果缓存。应在 mod 安装/卸载/增删后调用，确保下次查询重新扫描。
     */
    fun invalidateCache() {
        cache = null
    }
}
