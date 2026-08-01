package com.lash.pmcl.core.instance

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lash.pmcl.core.mods.ModScanner
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 实例导出器：将实例导出为可分享的 .pmcl-instance（ZIP）文件。
 *
 * 导出内容：
 *   - instance.json — 实例元数据（精简版：去掉 instanceId、boundAccountUuid、
 *       lastPlayedAt、totalPlayTimeSeconds 等运行时字段，保留 name/baseVersionId/loader/
 *       loaderVersion/description）
 *   - mods.json — 模组清单（每个 mod 的 modId/version/name/loader/jarFile/disabled），
 *       不含 jar 本体（版权 + 体积考量），导入时根据清单重新下载
 *   - config/ — 配置文件目录（模组配置，体积小且不可从公开源恢复）
 *   - icon.* — 实例图标（如有）
 *
 * 不导出：mods/（jar 本体）、saves/（存档）、screenshots/（截图）、logs/（日志）、
 * resourcepacks/（资源包，体积可能很大）、shaderpacks/（光影包，体积大且有版权）。
 */
object InstanceExporter {

    private val gson = Gson()

    /**
     * 导出实例到指定 zip 文件路径。
     *
     * @param info       实例元数据
     * @param outputPath 输出 zip 文件路径
     * @return 导出的模组数量（用于 UI 提示）
     * @throws IOException 导出失败
     */
    @Throws(IOException::class)
    fun export(info: InstanceInfo, outputPath: Path): Int {
        val instanceDir = info.instanceDir
            ?: throw IOException("实例目录不存在")
        if (!Files.isDirectory(instanceDir)) {
            throw IOException("实例目录不存在")
        }

        outputPath.parent?.let { Files.createDirectories(it) }
        var modCount = 0

        ZipOutputStream(Files.newOutputStream(outputPath), StandardCharsets.UTF_8).use { zos ->
            // 1. 写入精简版 instance.json
            val metaJson = JsonObject()
            metaJson.addProperty("name", info.name)
            metaJson.addProperty("baseVersionId", info.baseVersionId)
            metaJson.addProperty("type", info.type.name)
            info.loader?.let { metaJson.addProperty("loader", it) }
            info.loaderVersion?.let { metaJson.addProperty("loaderVersion", it) }
            info.description?.let { metaJson.addProperty("description", it) }
            metaJson.addProperty("exportFormat", "pmcl-instance")
            metaJson.addProperty("exportVersion", 1)
            writeZipEntry(zos, "instance.json", metaJson.toString().toByteArray(StandardCharsets.UTF_8))

            // 2. 扫描 mods 目录并写入 mods.json 清单
            val modsDir = instanceDir.resolve("mods")
            if (Files.isDirectory(modsDir)) {
                val mods = ModScanner.scanDirectory(modsDir)
                modCount = mods.size
                val modsArray = JsonArray()
                for (mod in mods) {
                    val modObj = JsonObject()
                    modObj.addProperty("modId", mod.modId)
                    modObj.addProperty("version", mod.version)
                    modObj.addProperty("name", mod.name)
                    modObj.addProperty("loader", mod.loader)
                    modObj.addProperty("jarFile", mod.jarFile)
                    modObj.addProperty("disabled", mod.disabled)
                    // 依赖列表（帮助导入时检查缺失前置）
                    if (mod.depends.isNotEmpty()) {
                        val deps = JsonArray()
                        for (dep in mod.depends) deps.add(dep)
                        modObj.add("depends", deps)
                    }
                    modsArray.add(modObj)
                }
                writeZipEntry(zos, "mods.json", gson.toJson(modsArray).toByteArray(StandardCharsets.UTF_8))
            }

            // 3. 复制 config/ 目录（模组配置文件，体积小且无法从公开源恢复）
            val configDir = instanceDir.resolve("config")
            if (Files.isDirectory(configDir)) {
                addDirectoryToZip(zos, configDir, "config/")
            }

            // 4. 复制图标文件（如有）——仅允许实例目录内的简单文件名
            val iconPath = info.iconPath
            if (!iconPath.isNullOrEmpty()) {
                val iconFile = InstanceManager.resolveSafeIconPath(instanceDir, iconPath)
                if (iconFile != null && Files.isRegularFile(iconFile)) {
                    val zipEntryName = iconFile.fileName?.toString()
                    if (zipEntryName != null) {
                        writeZipEntry(zos, zipEntryName, Files.readAllBytes(iconFile))
                    }
                }
            }
        }

        return modCount
    }

    @Throws(IOException::class)
    private fun writeZipEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(data)
        zos.closeEntry()
    }

    /** 递归将目录添加到 zip。H25: 深度上限 32 + 不跟随符号链接。 */
    @Throws(IOException::class)
    private fun addDirectoryToZip(zos: ZipOutputStream, dir: Path, zipPrefix: String) {
        try {
            Files.walk(dir, 32).use { stream ->
                stream.forEach { p ->
                    try {
                        if (Files.isSymbolicLink(p)) return@forEach
                        val relative = dir.relativize(p).toString()
                            .replace(FileSystems.getDefault().separator, "/")
                        var zipPath = zipPrefix + relative
                        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                            if (!zipPath.endsWith("/")) zipPath += "/"
                            zos.putNextEntry(ZipEntry(zipPath))
                            zos.closeEntry()
                        } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                            zos.putNextEntry(ZipEntry(zipPath))
                            Files.copy(p, zos)
                            zos.closeEntry()
                        }
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    }
                }
            }
        } catch (e: UncheckedIOException) {
            throw e.cause ?: IOException(e)
        }
    }
}
