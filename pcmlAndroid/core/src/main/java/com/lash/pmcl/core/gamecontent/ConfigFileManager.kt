package com.lash.pmcl.core.gamecontent

import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * 模组配置文件管理器。
 *
 * 管理 Minecraft 模组的配置文件（config/ 目录下），支持：
 *   - 列出配置文件（递归扫描子目录）
 *   - 读取/写入文件内容
 *   - 备份文件（.bak 后缀）
 *   - 删除文件
 *
 * 支持的配置文件格式（按扩展名识别）：
 *   .cfg / .toml / .json / .properties / .txt / .ini / .conf / .xml / .yml / .yaml
 *
 * Android 版本：从 Java 移植。Files.readString/writeString（Java 11）改用 [FileUtils]；
 * Path.of（Java 11）改用 [Paths.get]；保留递归扫描（walkFileTree）、自动备份(.bak)、
 * 1MB 读取上限、原子写入。
 */
class ConfigFileManager(val configDir: Path) {

    /** 配置文件信息 */
    class ConfigFileEntry(
        val relativePath: String,
        val fileName: String,
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean,
        val format: String
    ) {
        override fun toString(): String = fileName + if (isDirectory) "/" else ""
    }

    /** 确保配置目录存在 */
    @Throws(IOException::class)
    fun ensureConfigDir() {
        Files.createDirectories(configDir)
    }

    /**
     * 列出配置文件（非递归，仅顶层）。
     * 目录排在前面，按名称排序。
     */
    @Throws(IOException::class)
    fun listFiles(): List<ConfigFileEntry> {
        if (!Files.isDirectory(configDir)) return emptyList()
        val result = ArrayList<ConfigFileEntry>()
        Files.list(configDir).use { stream ->
            val paths = stream.sorted().collect(Collectors.toList())
            for (p in paths) {
                val entry = toEntry(p, configDir)
                if (entry != null) result.add(entry)
            }
        }
        return result
    }

    /**
     * 递归列出所有配置文件（包括子目录内的文件）。
     */
    @Throws(IOException::class)
    fun listAllFiles(): List<ConfigFileEntry> {
        if (!Files.isDirectory(configDir)) return emptyList()
        val result = ArrayList<ConfigFileEntry>()
        Files.walkFileTree(configDir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                val entry = toEntry(file, configDir)
                if (entry != null) result.add(entry)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                if (dir == configDir) return java.nio.file.FileVisitResult.CONTINUE
                val entry = toEntry(dir, configDir)
                if (entry != null) result.add(entry)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        return result
    }

    /**
     * 列出指定子目录下的文件。
     * @param subDir 相对于 config/ 的子目录路径（如 "jei" 或 "" 表示顶层）
     */
    @Throws(IOException::class)
    fun listFiles(subDir: String): List<ConfigFileEntry> {
        val dir = if (subDir.isEmpty() || subDir == "/") configDir
        else configDir.resolve(subDir).normalize()
        // 安全检查：确保路径在 configDir 内
        if (!dir.startsWith(configDir)) throw IOException("非法路径: $subDir")
        if (!Files.isDirectory(dir)) return emptyList()
        val result = ArrayList<ConfigFileEntry>()
        Files.list(dir).use { stream ->
            val paths = stream.sorted().collect(Collectors.toList())
            for (p in paths) {
                val entry = toEntry(p, configDir)
                if (entry != null) result.add(entry)
            }
        }
        return result
    }

    /**
     * 读取文件内容。
     * @param relativePath 相对于 config/ 的路径
     * @return 文件内容字符串
     */
    @Throws(IOException::class)
    fun readFile(relativePath: String): String {
        val file = configDir.resolve(relativePath).normalize()
        if (!file.startsWith(configDir)) throw IOException("非法路径: $relativePath")
        if (!Files.exists(file)) throw IOException("文件不存在: $relativePath")
        val size = Files.size(file)
        if (size > MAX_FILE_SIZE) {
            throw IOException("文件过大（${formatSize(size)}），超过 1MB 限制，请使用外部编辑器")
        }
        return FileUtils.readString(file)
    }

    /**
     * 写入文件内容。
     * 写入前自动备份（如果 .bak 不存在）。
     */
    @Throws(IOException::class)
    fun writeFile(relativePath: String, content: String) {
        val file = configDir.resolve(relativePath).normalize()
        if (!file.startsWith(configDir)) throw IOException("非法路径: $relativePath")
        val parent = file.parent
        parent?.let { Files.createDirectories(it) }
        // 自动备份
        val backup = Paths.get("$file.bak")
        if (Files.exists(file) && !Files.exists(backup)) {
            Files.copy(file, backup)
        }
        val tmp = file.resolveSibling("${file.fileName}.tmp.${UUID.randomUUID()}")
        FileUtils.writeString(tmp, content)
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 删除文件 */
    @Throws(IOException::class)
    fun deleteFile(relativePath: String) {
        val file = configDir.resolve(relativePath).normalize()
        if (!file.startsWith(configDir)) throw IOException("非法路径: $relativePath")
        Files.deleteIfExists(file)
    }

    /** 重命名文件 */
    @Throws(IOException::class)
    fun renameFile(relativePath: String, newName: String) {
        val file = configDir.resolve(relativePath).normalize()
        if (!file.startsWith(configDir)) throw IOException("非法路径: $relativePath")
        val target = file.resolveSibling(newName).normalize()
        if (!target.startsWith(configDir)) throw IOException("非法目标路径: $newName")
        try {
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(file, target)
        }
    }

    /** 创建新文件 */
    @Throws(IOException::class)
    fun createFile(relativePath: String) {
        val file = configDir.resolve(relativePath).normalize()
        if (!file.startsWith(configDir)) throw IOException("非法路径: $relativePath")
        Files.createDirectories(file.parent)
        Files.createFile(file)
    }

    /** 创建目录 */
    @Throws(IOException::class)
    fun createDirectory(relativePath: String) {
        val dir = configDir.resolve(relativePath).normalize()
        if (!dir.startsWith(configDir)) throw IOException("非法路径: $relativePath")
        Files.createDirectories(dir)
    }

    /** Path 转 ConfigFileEntry */
    private fun toEntry(p: Path, configDir: Path): ConfigFileEntry? {
        try {
            val relative = configDir.relativize(p).toString().replace('\\', '/')
            val name = p.fileName.toString()
            val attrs = Files.readAttributes(p, BasicFileAttributes::class.java)
            val isDir = attrs.isDirectory
            // 目录始终显示，文件需要是支持的格式
            if (!isDir && !isSupportedConfig(p)) return null
            val format = if (!isDir) {
                val dotIdx = name.lastIndexOf('.')
                if (dotIdx >= 0) name.substring(dotIdx + 1).lowercase(Locale.ROOT) else "txt"
            } else ""
            return ConfigFileEntry(
                relative, name, attrs.size(),
                attrs.lastModifiedTime().toMillis(), isDir, format
            )
        } catch (e: IOException) {
            return null
        }
    }

    /** 判断文件是否为支持的配置文件格式 */
    private fun isSupportedConfig(file: Path): Boolean {
        val name = file.fileName.toString().lowercase(Locale.ROOT)
        // 隐藏文件和备份文件不显示
        if (name.startsWith(".")) return false
        if (name.endsWith(".bak") || name.endsWith(".disabled") || name.endsWith(".old")) return false
        val dotIdx = name.lastIndexOf('.')
        if (dotIdx < 0) return true // 无扩展名的文本文件也显示
        val ext = name.substring(dotIdx)
        return ext in SUPPORTED_EXTENSIONS
    }

    companion object {
        /** 支持的配置文件扩展名 */
        private val SUPPORTED_EXTENSIONS: Set<String> = setOf(
            ".cfg", ".toml", ".json", ".properties", ".txt",
            ".ini", ".conf", ".xml", ".yml", ".yaml", ".props"
        )

        /** 最大文件大小（1MB），超过则不读取内容（防止 OOM） */
        private const val MAX_FILE_SIZE: Long = 1024 * 1024

        /** 格式化文件大小 */
        @JvmStatic
        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
            return "%.1f MB".format(bytes / (1024.0 * 1024))
        }
    }
}
