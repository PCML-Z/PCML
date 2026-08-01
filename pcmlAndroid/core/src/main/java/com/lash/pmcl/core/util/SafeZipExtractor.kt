package com.lash.pmcl.core.util

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.function.Predicate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 安全的 ZIP 解压工具，统一防护 ZipSlip（路径穿越）与 ZipBomb（解压炸弹）。
 *
 * Android 版本：从 Java 移植，保留全部安全防护（ZipSlip + ZipBomb）。
 * - Android 的 java.util.zip 与 JVM 行为一致，可直接复用
 * - Path/Files API 在 Android API 26+ 可用
 */
object SafeZipExtractor {

    /** 单个 entry 解压后最大字节数（默认 256 MB）。 */
    const val DEFAULT_MAX_ENTRY_SIZE: Long = 256L * 1024 * 1024

    /** 所有 entry 解压后总最大字节数（默认 2 GB）。 */
    const val DEFAULT_MAX_TOTAL_SIZE: Long = 2L * 1024 * 1024 * 1024

    /** 最多 entry 数量（默认 100,000）。 */
    const val DEFAULT_MAX_ENTRIES: Int = 100_000

    /** 最大压缩比（解压后/压缩前，默认 100:1）。 */
    const val DEFAULT_MAX_RATIO: Int = 100

    /** 复制缓冲区大小。 */
    private const val BUFFER_SIZE = 8192

    /**
     * 从输入流读取最多 [maxBytes] 字节；超出则抛 IOException（防 OOM）。
     */
    @Throws(IOException::class)
    fun readLimited(`in`: InputStream, maxBytes: Long): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0L
        var n: Int
        while (`in`.read(buf).also { n = it } > 0) {
            total += n
            if (total > maxBytes) {
                throw IOException("Entry exceeds size limit $maxBytes bytes")
            }
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    /**
     * 将输入流复制到文件，最多 [maxBytes] 字节；超出则删除目标并抛错。
     */
    @Throws(IOException::class)
    fun copyLimited(`in`: InputStream, dest: Path, maxBytes: Long): Long {
        val parent = dest.parent
        if (parent != null) Files.createDirectories(parent)
        var total = 0L
        Files.newOutputStream(
            dest,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use { out ->
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            while (`in`.read(buf).also { n = it } > 0) {
                total += n
                if (total > maxBytes) {
                    out.close()
                    try { Files.deleteIfExists(dest) } catch (_: IOException) {}
                    throw IOException("Entry exceeds size limit $maxBytes bytes")
                }
                out.write(buf, 0, n)
            }
        }
        return total
    }

    /**
     * 安全解压 ZIP 文件到目标目录（无 entry 过滤）。
     */
    @Throws(IOException::class)
    fun extractSafely(zipFile: Path, targetDir: Path) {
        extractSafely(zipFile, targetDir, null)
    }

    /**
     * 安全解压 ZIP 文件到目标目录，可选 entry 过滤。
     */
    @Throws(IOException::class)
    fun extractSafely(
        zipFile: Path, targetDir: Path,
        entryFilter: Predicate<ZipEntry>?
    ) {
        extractSafely(
            zipFile, targetDir, entryFilter,
            DEFAULT_MAX_ENTRY_SIZE, DEFAULT_MAX_TOTAL_SIZE, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RATIO
        )
    }

    /**
     * 安全解压 ZIP 文件到目标目录（可自定义 ZipBomb 阈值）。
     */
    @Throws(IOException::class)
    fun extractSafely(
        zipFile: Path, targetDir: Path,
        entryFilter: Predicate<ZipEntry>?,
        maxEntrySize: Long, maxTotalSize: Long, maxEntries: Int, maxRatio: Int
    ) {
        Files.createDirectories(targetDir)
        val normalizedBase = targetDir.normalize()

        var totalSize = 0L
        var entryCount = 0
        var compressedSize = 0L
        val zipFileSize = if (Files.exists(zipFile)) Files.size(zipFile) else 0L

        ZipFile(zipFile.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()

                // entry 数量限制
                if (++entryCount > maxEntries) {
                    throw IOException("ZipBomb detected: entry count exceeds limit ($maxEntries) in $zipFile")
                }

                if (entry.isDirectory) continue
                if (entryFilter != null && !entryFilter.test(entry)) continue

                val entryName = entry.name
                val entryCompressed = entry.compressedSize
                if (entryCompressed > 0) compressedSize += entryCompressed

                // ZipSlip 防护
                val dest = targetDir.resolve(entryName).normalize()
                if (!dest.startsWith(normalizedBase)) {
                    throw IOException("ZipSlip detected: entry '$entryName' resolves outside target dir $targetDir")
                }

                Files.createDirectories(dest.parent)

                // 流式复制并累计字节数，防止超大 entry 导致 OOM
                var entrySize = 0L
                zip.getInputStream(entry).use { `in` ->
                    Files.newOutputStream(
                        dest,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                    ).use { out ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var n: Int
                        while (`in`.read(buf).also { n = it } > 0) {
                            entrySize += n
                            totalSize += n
                            if (entrySize > maxEntrySize) {
                                throw IOException("ZipBomb detected: entry '$entryName' exceeds max entry size $maxEntrySize (extracted $entrySize bytes)")
                            }
                            if (totalSize > maxTotalSize) {
                                throw IOException("ZipBomb detected: total extracted size exceeds limit $maxTotalSize bytes in $zipFile")
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
        }

        // 压缩比检查（zip 文件总大小 > 0 时）
        if (zipFileSize > 0 && compressedSize > 0) {
            val ratio = totalSize / Math.max(1, compressedSize)
            if (ratio > maxRatio) {
                throw IOException("ZipBomb detected: compression ratio $ratio:1 exceeds limit $maxRatio:1 in $zipFile (extracted=$totalSize, compressed=$compressedSize)")
            }
        }
    }

    /**
     * 安全流式解压（适用于 ZipInputStream，无预先 entry 索引的场景）。
     */
    @Throws(IOException::class)
    fun extractStreamSafely(
        zis: ZipInputStream, targetDir: Path,
        entryFilter: Predicate<ZipEntry>?
    ) {
        extractStreamSafely(
            zis, targetDir, entryFilter,
            DEFAULT_MAX_ENTRY_SIZE, DEFAULT_MAX_TOTAL_SIZE, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RATIO
        )
    }

    /**
     * 安全流式解压（可自定义 ZipBomb 阈值）。
     */
    @Throws(IOException::class)
    fun extractStreamSafely(
        zis: ZipInputStream, targetDir: Path,
        entryFilter: Predicate<ZipEntry>?,
        maxEntrySize: Long, maxTotalSize: Long, maxEntries: Int, maxRatio: Int
    ) {
        Files.createDirectories(targetDir)
        val normalizedBase = targetDir.normalize()

        var totalSize = 0L
        var entryCount = 0

        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            if (++entryCount > maxEntries) {
                throw IOException("ZipBomb detected: entry count exceeds limit ($maxEntries)")
            }
            if (entry.isDirectory) {
                entry = zis.nextEntry
                continue
            }
            if (entryFilter != null && !entryFilter.test(entry)) {
                entry = zis.nextEntry
                continue
            }

            val entryName = entry.name
            val dest = targetDir.resolve(entryName).normalize()
            if (!dest.startsWith(normalizedBase)) {
                throw IOException("ZipSlip detected: entry '$entryName' resolves outside target dir $targetDir")
            }

            Files.createDirectories(dest.parent)

            var entrySize = 0L
            Files.newOutputStream(
                dest,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                while (zis.read(buf).also { n = it } > 0) {
                    entrySize += n
                    totalSize += n
                    if (entrySize > maxEntrySize) {
                        throw IOException("ZipBomb detected: entry '$entryName' exceeds max entry size $maxEntrySize (extracted $entrySize bytes)")
                    }
                    if (totalSize > maxTotalSize) {
                        throw IOException("ZipBomb detected: total extracted size exceeds limit $maxTotalSize bytes")
                    }
                    out.write(buf, 0, n)
                }
            }
            entry = zis.nextEntry
        }
    }
}
