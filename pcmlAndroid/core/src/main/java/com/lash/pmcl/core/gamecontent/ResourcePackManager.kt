package com.lash.pmcl.core.gamecontent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.util.SafeZipExtractor
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale
import java.util.stream.Stream
import java.util.zip.ZipFile

/**
 * 资源包管理：扫描 resourcepacks 目录，解析 pack.mcmeta 获取格式版本与描述。
 *
 * 资源包可以是目录或 .zip 文件，pack.mcmeta 位于根目录。
 *
 * Android 版本：从 Java 移植，保留 .zip 和目录两种形式解析、pack.mcmeta 解析、
 * .disabled 后缀启用/禁用、路径穿越防护。
 */
class ResourcePackManager(workDir: Path) {

    val resourcePacksDir: Path = workDir.resolve("resourcepacks")

    class Pack(
        val name: String,
        val path: Path,
        val packFormat: Int,
        val description: String,
        val isZip: Boolean,
        val disabled: Boolean,
        var source: String? = null
    )

    @Throws(IOException::class)
    fun list(): List<Pack> = list(resourcePacksDir, "全局")

    /** 扫描指定目录下的资源包，附带来源标签 */
    @Throws(IOException::class)
    fun list(dir: Path, source: String): List<Pack> {
        val result = ArrayList<Pack>()
        if (!Files.isDirectory(dir)) return result
        Files.list(dir).use { stream ->
            stream.forEach { p ->
                val name = p.fileName.toString()
                val lower = name.lowercase(Locale.ROOT)
                val pack: Pack? = when {
                    lower.endsWith(".zip.disabled") && Files.isRegularFile(p) -> parseZipPack(p, true)
                    lower.endsWith(".zip") && Files.isRegularFile(p) -> parseZipPack(p, false)
                    Files.isDirectory(p) -> parseDirPack(p, lower.endsWith(".disabled"))
                    else -> null
                }
                if (pack != null) {
                    pack.source = source
                    result.add(pack)
                }
            }
        }
        return result
    }

    /**
     * 启用资源包：将 xxx.zip.disabled 重命名为 xxx.zip，或将 xxx.disabled 目录重命名为 xxx。
     * 若目标已存在，则删除 .disabled 副本。
     * @return 新文件名（启用后）
     */
    @Throws(IOException::class)
    fun enable(fileName: String): String {
        if (!fileName.lowercase(Locale.ROOT).endsWith(".disabled")) return fileName
        val src = resourcePacksDir.resolve(fileName).normalize()
        if (!src.startsWith(resourcePacksDir)) throw IOException("非法文件名: $fileName")
        val enabledName = fileName.substring(0, fileName.length - ".disabled".length)
        val dst = resourcePacksDir.resolve(enabledName).normalize()
        if (!dst.startsWith(resourcePacksDir)) throw IOException("非法文件名: $enabledName")
        if (!Files.exists(src)) throw IOException("文件不存在: $fileName")
        // 目标已存在 → 删除禁用副本
        if (Files.exists(dst)) {
            if (Files.isDirectory(src)) {
                Files.walk(src).use { s ->
                    s.sorted(Comparator.reverseOrder())
                        .forEach { p ->
                            try { Files.delete(p) } catch (e: IOException) {}
                        }
                }
            } else {
                Files.delete(src)
            }
            return enabledName
        }
        Files.move(src, dst)
        return enabledName
    }

    /**
     * 禁用资源包：将 xxx.zip 重命名为 xxx.zip.disabled，或将 xxx 目录重命名为 xxx.disabled。
     * 已禁用的文件不变。
     * @return 新文件名（禁用后）
     */
    @Throws(IOException::class)
    fun disable(fileName: String): String {
        if (fileName.lowercase(Locale.ROOT).endsWith(".disabled")) return fileName
        val src = resourcePacksDir.resolve(fileName).normalize()
        if (!src.startsWith(resourcePacksDir)) throw IOException("非法文件名: $fileName")
        val dst = resourcePacksDir.resolve("$fileName.disabled").normalize()
        if (!dst.startsWith(resourcePacksDir)) throw IOException("非法文件名: $fileName")
        if (!Files.exists(src)) throw IOException("文件不存在: $fileName")
        Files.move(src, dst)
        return dst.fileName.toString()
    }

    @Throws(IOException::class)
    fun delete(pack: Pack) {
        val target = assertUnderNamedParent(pack.path, "resourcepacks")
        if (pack.isZip || Files.isRegularFile(target)) {
            Files.deleteIfExists(target)
        } else {
            Files.walk(target).use { s ->
                s.sorted(Comparator.reverseOrder())
                    .forEach { p ->
                        try { Files.delete(p) } catch (e: IOException) {}
                    }
            }
        }
    }

    private fun parseZipPack(zipPath: Path, disabled: Boolean): Pack? {
        try {
            ZipFile(zipPath.toFile()).use { zip ->
                val entry = zip.getEntry("pack.mcmeta")
                // 显示名需去除 .disabled 后缀
                val display = if (disabled)
                    stripDisabledSuffix(zipPath.fileName.toString())
                else zipPath.fileName.toString()
                if (entry == null) return Pack(
                    stripZipSuffix(display), zipPath, 0, "", true, disabled, null
                )
                val meta = zip.getInputStream(entry).use { readAll(it) }
                return buildPack(display, zipPath, meta, true, disabled)
            }
        } catch (e: Throwable) {
            return null
        }
    }

    private fun parseDirPack(dir: Path, disabled: Boolean): Pack? {
        val meta = dir.resolve("pack.mcmeta")
        // 显示名需去除 .disabled 后缀
        val display = if (disabled)
            stripDisabledSuffix(dir.fileName.toString())
        else dir.fileName.toString()
        if (!Files.exists(meta)) {
            return Pack(display, dir, 0, "", false, disabled, null)
        }
        try {
            if (Files.size(meta) > MAX_MCMETA_BYTES) return null
            val content = Files.newInputStream(meta).use { readAll(it) }
            return buildPack(display, dir, content, false, disabled)
        } catch (e: Throwable) {
            return null
        }
    }

    private fun buildPack(fileName: String, path: Path, mcmeta: String, isZip: Boolean, disabled: Boolean): Pack {
        var packFormat = 0
        var description = ""
        try {
            val root = JsonParser.parseString(mcmeta).asJsonObject
            if (root.has("pack")) {
                val pack = root.getAsJsonObject("pack")
                if (pack.has("pack_format") && pack.get("pack_format").isJsonPrimitive)
                    packFormat = pack.get("pack_format").asInt
                if (pack.has("description") && pack.get("description").isJsonPrimitive)
                    description = pack.get("description").asString
            }
        } catch (e: Throwable) {
            // 解析失败保留默认值
        }
        // fileName 已经是去除 .disabled 的显示名
        val name = if (isZip) stripZipSuffix(fileName) else fileName
        return Pack(name, path, packFormat, description, isZip, disabled, null)
    }

    private fun stripDisabledSuffix(s: String): String =
        if (s.lowercase(Locale.ROOT).endsWith(".disabled"))
            s.substring(0, s.length - ".disabled".length) else s

    private fun stripZipSuffix(s: String): String =
        if (s.lowercase(Locale.ROOT).endsWith(".zip")) s.substring(0, s.length - 4) else s

    @Throws(IOException::class)
    private fun readAll(`in`: InputStream): String =
        String(SafeZipExtractor.readLimited(`in`, MAX_MCMETA_BYTES), StandardCharsets.UTF_8)

    companion object {
        private const val MAX_MCMETA_BYTES: Long = 1L * 1024 * 1024

        /** 删除目标必须直接位于名为 expectedParent 的目录下。 */
        @Throws(IOException::class)
        internal fun assertUnderNamedParent(path: Path?, expectedParent: String): Path {
            if (path == null) throw IOException("路径为空")
            val abs = path.toAbsolutePath().normalize()
            val parent = abs.parent
            if (parent == null || parent.fileName == null
                || !expectedParent.equals(parent.fileName.toString(), ignoreCase = true)
            ) {
                throw IOException("拒绝删除：路径不在 $expectedParent 目录下: $abs")
            }
            return abs
        }
    }
}
