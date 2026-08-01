package com.lash.pmcl.core.gamecontent

import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale
import java.util.stream.Stream
import java.util.zip.ZipFile

/**
 * 光影包管理：扫描 shaderpacks 目录，识别 .zip 光影包。
 *
 * 光影包是 .zip 文件，内部包含 shaders/ 目录（Iris/OptiFine 规范）。
 * 当前选中状态由 options.txt 的 "shaderPack" 字段记录。
 *
 * Android 版本：从 Java 移植。Files.writeString（Java 11）改用 [FileUtils]；
 * readAllLines（Java 8 NIO.2）在 API 26+ 可用。
 */
class ShaderPackManager(workDir: Path) {

    val shaderPacksDir: Path = workDir.resolve("shaderpacks")
    private val optionsFile: Path = workDir.resolve("options.txt")

    class ShaderPack(
        val name: String,
        val path: Path,
        val size: Long,
        val valid: Boolean,
        val active: Boolean,
        val disabled: Boolean,
        var source: String
    )

    @Throws(IOException::class)
    fun list(): List<ShaderPack> = list(shaderPacksDir, "全局")

    /** 扫描指定目录下的光影包，附带来源标签 */
    @Throws(IOException::class)
    fun list(dir: Path, source: String): List<ShaderPack> {
        val result = ArrayList<ShaderPack>()
        if (!Files.isDirectory(dir)) return result
        val active = readActiveShaderPack()
        Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .forEach { p ->
                    val fileName = p.fileName.toString()
                    val lower = fileName.lowercase(Locale.ROOT)
                    var disabled = false
                    val display: String
                    // 识别 .zip.disabled 与 .zip 两种文件
                    when {
                        lower.endsWith(".zip.disabled") -> {
                            disabled = true
                            display = fileName.substring(0, fileName.length - ".disabled".length)
                        }
                        lower.endsWith(".zip") -> {
                            display = fileName
                        }
                        else -> return@forEach
                    }
                    try {
                        val size = Files.size(p)
                        val valid = hasShadersDir(p)
                        // active 比较时使用去除 .disabled 后的显示名
                        val isActive = display == active ||
                            stripZipSuffix(display) == active
                        result.add(ShaderPack(display, p, size, valid, isActive, disabled, source))
                    } catch (e: Throwable) {
                        // 单个光影包解析失败不应中断其他扫描
                    }
                }
        }
        return result
    }

    /**
     * 启用光影包：将 xxx.zip.disabled 重命名为 xxx.zip。
     * 已启用的文件不变；若目标 xxx.zip 已存在，则删除 .disabled 副本。
     * @return 新文件名（启用后）
     */
    @Throws(IOException::class)
    fun enable(fileName: String): String {
        if (!fileName.lowercase(Locale.ROOT).endsWith(".disabled")) return fileName
        val src = shaderPacksDir.resolve(fileName).normalize()
        if (!src.startsWith(shaderPacksDir)) throw IOException("非法文件名: $fileName")
        val enabledName = fileName.substring(0, fileName.length - ".disabled".length)
        val dst = shaderPacksDir.resolve(enabledName).normalize()
        if (!dst.startsWith(shaderPacksDir)) throw IOException("非法文件名: $enabledName")
        if (!Files.exists(src)) throw IOException("文件不存在: $fileName")
        // 目标已存在（同名 zip 已启用）→ 删除禁用副本
        if (Files.exists(dst)) {
            Files.delete(src)
            return enabledName
        }
        Files.move(src, dst)
        return enabledName
    }

    /**
     * 禁用光影包：将 xxx.zip 重命名为 xxx.zip.disabled。
     * 已禁用的文件不变。
     * @return 新文件名（禁用后）
     */
    @Throws(IOException::class)
    fun disable(fileName: String): String {
        if (fileName.lowercase(Locale.ROOT).endsWith(".disabled")) return fileName
        val src = shaderPacksDir.resolve(fileName).normalize()
        if (!src.startsWith(shaderPacksDir)) throw IOException("非法文件名: $fileName")
        val dst = shaderPacksDir.resolve("$fileName.disabled").normalize()
        if (!dst.startsWith(shaderPacksDir)) throw IOException("非法文件名: $fileName")
        if (!Files.exists(src)) throw IOException("文件不存在: $fileName")
        Files.move(src, dst)
        return dst.fileName.toString()
    }

    @Throws(IOException::class)
    fun delete(pack: ShaderPack) {
        val target = ResourcePackManager.assertUnderNamedParent(pack.path, "shaderpacks")
        Files.deleteIfExists(target)
    }

    /**
     * 将指定光影包设为当前选中（写入 options.txt 的 shaderPack 字段）。
     * 传入 null 表示关闭光影（设为空）。
     *
     * 注意：options.txt 中 shaderPack 字段记录的是不含 .zip 后缀的名称。
     * 游戏运行时修改不会生效，需在游戏未运行时调用。
     */
    @Throws(IOException::class)
    fun setActive(pack: ShaderPack?) {
        val value = if (pack == null) "" else stripZipSuffix(pack.name)
        writeOption("shaderPack", value)
        // 同时开启 enableShaders 选项
        writeOption("enableShaders", "true")
    }

    /** 关闭光影（清空当前选中） */
    @Throws(IOException::class)
    fun clearActive() {
        writeOption("shaderPack", "")
    }

    /**
     * 写入/更新 options.txt 中的某个键值对，保留其它行。
     * 若文件不存在则新建；若键已存在则更新，否则追加。
     */
    @Throws(IOException::class)
    private fun writeOption(key: String, value: String) {
        if (!Files.exists(optionsFile)) {
            optionsFile.parent?.let { Files.createDirectories(it) }
            FileUtils.writeString(optionsFile, "$key:$value\n")
            return
        }
        val lines = ArrayList(Files.readAllLines(optionsFile, StandardCharsets.UTF_8))
        var found = false
        for (i in lines.indices) {
            if (lines[i].startsWith("$key:")) {
                lines[i] = "$key:$value"
                found = true
                break
            }
        }
        if (!found) lines.add("$key:$value")
        FileUtils.writeString(optionsFile, lines.joinToString("\n") + "\n")
    }

    /** 校验 zip 内是否含 shaders/ 目录 */
    private fun hasShadersDir(zipPath: Path): Boolean {
        ZipFile(zipPath.toFile()).use { zip ->
            return zip.getEntry("shaders/") != null ||
                zip.stream().anyMatch { e -> e.name.startsWith("shaders/") }
        }
    }

    /** 从 options.txt 读取当前选中的光影包名 */
    private fun readActiveShaderPack(): String? {
        if (!Files.exists(optionsFile)) return null
        try {
            for (line in Files.readAllLines(optionsFile, StandardCharsets.UTF_8)) {
                if (line.startsWith("shaderPack:")) {
                    val parts = line.split(":".toRegex(), 2)
                    if (parts.size == 2) return parts[1].trim()
                }
            }
        } catch (e: Throwable) {
            // 静默忽略
        }
        return null
    }

    private fun stripZipSuffix(s: String): String =
        if (s.lowercase(Locale.ROOT).endsWith(".zip")) s.substring(0, s.length - 4) else s
}
