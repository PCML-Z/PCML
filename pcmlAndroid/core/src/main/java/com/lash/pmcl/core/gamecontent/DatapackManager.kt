package com.lash.pmcl.core.gamecontent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.util.SafeZipExtractor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale
import java.util.stream.Stream
import java.util.zip.ZipFile

/**
 * 数据包管理：扫描指定世界的 datapacks/ 子目录。
 *
 * 数据包位于 `saves/<world>/datapacks/`，可以是 .zip 或目录。
 * 每个数据包根目录含 pack.mcmeta，pack_format 标识兼容版本。
 *
 * 注意：本类只读不写启用状态。Minecraft 在加载时根据 enabled.json 决定启用列表，
 * 修改该文件需要游戏未运行，否则会被覆盖。
 *
 * Android 版本：从 Java 移植，保留与 ResourcePackManager 相似的结构、
 * .zip 和目录两种形式解析、.disabled 后缀启用/禁用、路径穿越防护。
 */
class DatapackManager {

    class Datapack(
        val name: String,
        val path: Path,
        val packFormat: Int,
        val description: String,
        val isZip: Boolean,
        val disabled: Boolean
    )

    /** 扫描指定世界的 datapacks 目录 */
    @Throws(IOException::class)
    fun list(worldDir: Path): List<Datapack> {
        val dpDir = worldDir.resolve("datapacks")
        val result = ArrayList<Datapack>()
        if (!Files.isDirectory(dpDir)) return result
        Files.list(dpDir).use { stream ->
            stream.forEach { p ->
                val name = p.fileName.toString()
                val lower = name.lowercase(Locale.ROOT)
                val dp: Datapack? = when {
                    lower.endsWith(".zip.disabled") && Files.isRegularFile(p) -> parseZip(p, true)
                    lower.endsWith(".zip") && Files.isRegularFile(p) -> parseZip(p, false)
                    Files.isDirectory(p) -> parseDir(p, lower.endsWith(".disabled"))
                    else -> null
                }
                if (dp != null) result.add(dp)
            }
        }
        return result
    }

    /**
     * 启用数据包：将 xxx.zip.disabled 重命名为 xxx.zip，或将 xxx.disabled 目录重命名为 xxx。
     * 若目标已存在，则删除 .disabled 副本。
     * @return 新文件名（启用后）
     */
    @Throws(IOException::class)
    fun enable(worldDir: Path, fileName: String): String {
        if (!fileName.lowercase(Locale.ROOT).endsWith(".disabled")) return fileName
        val dpDir = worldDir.resolve("datapacks")
        val src = dpDir.resolve(fileName).normalize()
        if (!src.startsWith(dpDir)) throw IOException("非法文件名: $fileName")
        val enabledName = fileName.substring(0, fileName.length - ".disabled".length)
        val dst = dpDir.resolve(enabledName).normalize()
        if (!dst.startsWith(dpDir)) throw IOException("非法文件名: $enabledName")
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
     * 禁用数据包：将 xxx.zip 重命名为 xxx.zip.disabled，或将 xxx 目录重命名为 xxx.disabled。
     * 已禁用的文件不变。
     * @return 新文件名（禁用后）
     */
    @Throws(IOException::class)
    fun disable(worldDir: Path, fileName: String): String {
        if (fileName.lowercase(Locale.ROOT).endsWith(".disabled")) return fileName
        val dpDir = worldDir.resolve("datapacks")
        val src = dpDir.resolve(fileName).normalize()
        if (!src.startsWith(dpDir)) throw IOException("非法文件名: $fileName")
        val dst = dpDir.resolve("$fileName.disabled").normalize()
        if (!dst.startsWith(dpDir)) throw IOException("非法文件名: $fileName")
        if (!Files.exists(src)) throw IOException("文件不存在: $fileName")
        Files.move(src, dst)
        return dst.fileName.toString()
    }

    @Throws(IOException::class)
    fun delete(pack: Datapack) {
        val target = ResourcePackManager.assertUnderNamedParent(pack.path, "datapacks")
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

    private fun parseZip(zipPath: Path, disabled: Boolean): Datapack? {
        try {
            ZipFile(zipPath.toFile()).use { zip ->
                val entry = zip.getEntry("pack.mcmeta")
                // 显示名需去除 .disabled 后缀
                val display = if (disabled)
                    stripDisabledSuffix(zipPath.fileName.toString())
                else zipPath.fileName.toString()
                val name = stripZipSuffix(display)
                if (entry == null) return Datapack(name, zipPath, 0, "", true, disabled)
                val meta = zip.getInputStream(entry).use {
                    String(SafeZipExtractor.readLimited(it, MAX_MCMETA_BYTES), StandardCharsets.UTF_8)
                }
                return build(name, zipPath, meta, true, disabled)
            }
        } catch (e: Throwable) {
            return null
        }
    }

    private fun parseDir(dir: Path, disabled: Boolean): Datapack? {
        val meta = dir.resolve("pack.mcmeta")
        // 显示名需去除 .disabled 后缀
        val name = if (disabled)
            stripDisabledSuffix(dir.fileName.toString())
        else dir.fileName.toString()
        if (!Files.exists(meta)) return Datapack(name, dir, 0, "", false, disabled)
        try {
            if (Files.size(meta) > MAX_MCMETA_BYTES) return null
            val content = Files.newInputStream(meta).use {
                String(SafeZipExtractor.readLimited(it, MAX_MCMETA_BYTES), StandardCharsets.UTF_8)
            }
            return build(name, dir, content, false, disabled)
        } catch (e: Throwable) {
            return null
        }
    }

    private fun build(name: String, path: Path, mcmeta: String, isZip: Boolean, disabled: Boolean): Datapack {
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
        return Datapack(name, path, packFormat, description, isZip, disabled)
    }

    private fun stripDisabledSuffix(s: String): String =
        if (s.lowercase(Locale.ROOT).endsWith(".disabled"))
            s.substring(0, s.length - ".disabled".length) else s

    private fun stripZipSuffix(s: String): String =
        if (s.lowercase(Locale.ROOT).endsWith(".zip")) s.substring(0, s.length - 4) else s

    companion object {
        private const val MAX_MCMETA_BYTES: Long = 1L * 1024 * 1024
    }
}
