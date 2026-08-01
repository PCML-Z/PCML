package com.lash.pmcl.core.instance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.util.SafeZipExtractor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.comparisons.compareByDescending

/**
 * 实例导入器：从 .pmcl-instance（ZIP）文件导入实例。
 *
 * 含 ZipSlip 防护与单条目/总量大小上限，避免恶意包导致 OOM。
 */
object InstanceImporter {

    private const val MAX_JSON_BYTES = 8L * 1024 * 1024       // 8 MB
    private const val MAX_ICON_BYTES = 16L * 1024 * 1024      // 16 MB
    private const val MAX_CONFIG_ENTRY = 32L * 1024 * 1024    // 32 MB / file
    private const val MAX_CONFIG_TOTAL = 256L * 1024 * 1024   // 256 MB configs
    private const val MAX_ENTRIES = 50_000

    /**
     * 从 zip 文件导入实例。
     *
     * @param zipPath 导入的 zip 文件路径
     * @param manager 实例管理器（用于创建新实例目录）
     * @return 导入结果（含新实例信息和模组清单）
     * @throws IOException 导入失败
     */
    @Throws(IOException::class)
    fun importInstance(zipPath: Path, manager: InstanceManager): ImportResult {
        if (!Files.exists(zipPath)) {
            throw IOException("导入文件不存在: $zipPath")
        }

        var instanceName = "Imported Instance"
        var baseVersionId = ""
        var loader: String? = null
        var loaderVersion: String? = null
        var description: String? = null
        var iconFileName: String? = null
        var iconData: ByteArray? = null
        var modsJson: String? = null
        var tempConfigDir: Path? = null
        var configTotal = 0L
        var entryCount = 0

        ZipInputStream(Files.newInputStream(zipPath), StandardCharsets.UTF_8).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (++entryCount > MAX_ENTRIES) {
                    throw IOException("导入包 entry 数量超过上限 $MAX_ENTRIES")
                }
                val name = entry.name
                if (entry.isDirectory) continue

                // ZipSlip：任一非法条目即失败，禁止静默跳过导致「导入成功但内容残缺」
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")
                    || name.matches(Regex("^[A-Za-z]:[\\\\/].*"))
                ) {
                    throw IOException("ZipSlip: 导入包含非法路径条目: $name")
                }

                when {
                    name == "instance.json" -> {
                        val json = String(SafeZipExtractor.readLimited(zis, MAX_JSON_BYTES), StandardCharsets.UTF_8)
                        val o = JsonParser.parseString(json).asJsonObject
                        if (o.has("name")) instanceName = o.get("name").asString
                        if (o.has("baseVersionId")) baseVersionId = o.get("baseVersionId").asString
                        if (o.has("loader")) loader = o.get("loader").asString
                        if (o.has("loaderVersion")) loaderVersion = o.get("loaderVersion").asString
                        if (o.has("description")) description = o.get("description").asString
                    }
                    name == "mods.json" -> {
                        modsJson = String(SafeZipExtractor.readLimited(zis, MAX_JSON_BYTES), StandardCharsets.UTF_8)
                    }
                    name.startsWith("config/") -> {
                        if (tempConfigDir == null) {
                            tempConfigDir = Files.createTempDirectory("pmcl-import-config")
                        }
                        val relative = name.substring("config/".length)
                        if (relative.isEmpty() || relative.contains("..")) {
                            throw IOException("ZipSlip: config 条目非法: $name")
                        }
                        val tempAbs = tempConfigDir!!.toAbsolutePath().normalize()
                        val targetFile = tempAbs.resolve(relative).normalize()
                        if (!targetFile.startsWith(tempAbs)) {
                            throw IOException("ZipSlip: config 路径越界: $name")
                        }
                        targetFile.parent?.let { Files.createDirectories(it) }
                        val written = SafeZipExtractor.copyLimited(zis, targetFile, MAX_CONFIG_ENTRY)
                        configTotal += written
                        if (configTotal > MAX_CONFIG_TOTAL) {
                            throw IOException("config/ 解压总量超过上限 $MAX_CONFIG_TOTAL")
                        }
                    }
                    isIconFile(name) -> {
                        iconFileName = Paths.get(name).fileName?.toString()
                        iconData = SafeZipExtractor.readLimited(zis, MAX_ICON_BYTES)
                    }
                }
            }
        }

        var newInfo: InstanceInfo? = null
        try {
            val info = manager.createInstance(instanceName, baseVersionId, loader, loaderVersion)
            newInfo = info
            if (description != null) info.description = description
            val newInstanceDir = info.instanceDir ?: throw IOException("实例目录未初始化")

            val tempConfig = tempConfigDir
            if (tempConfig != null && Files.isDirectory(tempConfig)) {
                val targetConfig = newInstanceDir.resolve("config")
                Files.newDirectoryStream(tempConfig).use { stream ->
                    for (src in stream) {
                        val fileName = src.fileName ?: continue
                        val dst = targetConfig.resolve(fileName)
                        dst.parent?.let { Files.createDirectories(it) }
                        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                deleteRecursive(tempConfig)
                tempConfigDir = null
            }

            val iconFile = iconFileName
            val iconBytes = iconData
            if (iconFile != null && iconBytes != null) {
                Files.write(newInstanceDir.resolve(iconFile), iconBytes)
                info.iconPath = iconFile
            }

            manager.saveInstanceInfo(info)
        } catch (e: Exception) {
            val tempConfig = tempConfigDir
            if (tempConfig != null) deleteRecursive(tempConfig)
            newInfo?.let { info ->
                try { manager.deleteInstance(info.instanceId) } catch (_: Throwable) {}
            }
            if (e is IOException) throw e
            throw IOException("导入实例失败: ${e.message}", e)
        }

        val modList = ArrayList<ModEntry>()
        val modsJsonStr = modsJson
        if (!modsJsonStr.isNullOrEmpty()) {
            try {
                val arr = JsonParser.parseString(modsJsonStr).asJsonArray
                for (elem in arr) {
                    val o = elem.asJsonObject
                    val mod = ModEntry()
                    mod.modId = safeStr(o, "modId")
                    mod.version = safeStr(o, "version")
                    mod.name = safeStr(o, "name")
                    mod.loader = safeStr(o, "loader")
                    mod.jarFile = safeStr(o, "jarFile")
                    mod.disabled = o.has("disabled") && o.get("disabled").asBoolean
                    modList.add(mod)
                }
            } catch (t: Throwable) {
                System.err.println("[InstanceImporter] 解析 mods.json 失败: ${t.message}")
            }
        }

        return ImportResult(newInfo!!, modList)
    }

    private fun isIconFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    private fun deleteRecursive(dir: Path?) {
        if (dir == null) return
        try {
            Files.walk(dir).use { stream ->
                stream.sorted(compareByDescending { p: Path -> p })
                    .forEach { p -> try { Files.deleteIfExists(p) } catch (_: IOException) {} }
            }
        } catch (_: IOException) {}
    }

    private fun safeStr(o: JsonObject, key: String): String {
        return if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else ""
    }

    /** 模组清单条目 */
    class ModEntry {
        var modId: String = ""
        var version: String = ""
        var name: String = ""
        var loader: String = ""
        var jarFile: String = ""
        var disabled: Boolean = false

        override fun toString(): String {
            return "$name ($modId v$version, $loader)"
        }
    }

    /** 导入结果 */
    class ImportResult(val info: InstanceInfo, val mods: List<ModEntry>)
}
