package com.lash.pmcl.core.util

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 文件工具：提供 Android 兼容的文件读写（替代 Java 11 的 Files.readString/writeString）。
 * <p>
 * Android API 26+ 的 java.nio.file.Files 不包含 readString/writeString（Java 11+ API），
 * 需通过 newBufferedReader/newBufferedWriter 实现。
 */
object FileUtils {

    /**
     * 读取文件全部内容为字符串（UTF-8）。
     * 替代 Java 11 的 Files.readString(path)。
     */
    @Throws(IOException::class)
    fun readString(path: Path): String {
        val sb = StringBuilder()
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n == -1) break
                sb.append(buf, 0, n)
            }
        }
        return sb.toString()
    }

    /**
     * 读取文件全部内容为字符串（指定编码）。
     */
    @Throws(IOException::class)
    fun readString(path: Path, charset: java.nio.charset.Charset): String {
        val sb = StringBuilder()
        Files.newBufferedReader(path, charset).use { reader ->
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n == -1) break
                sb.append(buf, 0, n)
            }
        }
        return sb.toString()
    }

    /**
     * 将字符串写入文件（UTF-8，覆盖）。
     * 替代 Java 11 的 Files.writeString(path, content)。
     */
    @Throws(IOException::class)
    fun writeString(path: Path, content: String) {
        writeString(path, content, StandardCharsets.UTF_8)
    }

    /**
     * 将字符串写入文件（指定编码，覆盖）。
     */
    @Throws(IOException::class)
    fun writeString(path: Path, content: String, charset: java.nio.charset.Charset) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, charset).use { writer ->
            writer.write(content)
        }
    }

    /**
     * 递归删除目录或文件。
     */
    @Throws(IOException::class)
    fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path)) {
            Files.list(path).use { stream ->
                val it = stream.iterator()
                while (it.hasNext()) {
                    deleteRecursively(it.next())
                }
            }
        }
        Files.deleteIfExists(path)
    }
}
