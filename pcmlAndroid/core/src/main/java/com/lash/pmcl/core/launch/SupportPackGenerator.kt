package com.lash.pmcl.core.launch

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 崩溃现场 zip：报告、日志、模组列表。不含令牌。 */
object SupportPackGenerator {
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_CRASH_REPORTS = 5
    private const val MAX_LOG_CHARS = 400_000

    fun write(
        zipPath: Path,
        versionId: String,
        workDir: Path?,
        gameDir: Path?,
        recentLogs: List<String>,
        launcherLog: String,
        extraInfo: String,
    ) {
        zipPath.parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            putText(zos, "info.txt", buildInfo(versionId, workDir, gameDir, extraInfo))
            putText(zos, "logs/game-recent.log", joinTruncated(recentLogs))
            if (launcherLog.isNotEmpty()) {
                putText(zos, "logs/launcher.log", truncate(launcherLog, MAX_LOG_CHARS))
            }
            copyIfExists(zos, "logs/latest.log", gameDir?.resolve("logs")?.resolve("latest.log"))
            putText(zos, "mods.txt", listMods(gameDir))
            copyCrashReports(zos, gameDir)
            if (gameDir == null || workDir == null || gameDir != workDir) {
                copyCrashReports(zos, workDir)
            }
        }
    }

    private fun buildInfo(versionId: String, workDir: Path?, gameDir: Path?, extraInfo: String): String {
        val sb = StringBuilder()
        sb.append("PMCL support pack\n")
        sb.append("time=").append(Instant.now()).append('\n')
        sb.append("versionId=").append(versionId).append('\n')
        sb.append("os.name=").append(System.getProperty("os.name", "")).append('\n')
        sb.append("java.version=").append(System.getProperty("java.version", "")).append('\n')
        sb.append("workDir=").append(workDir ?: "").append('\n')
        sb.append("gameDir=").append(gameDir ?: "").append('\n')
        if (extraInfo.isNotBlank()) sb.append('\n').append(extraInfo).append('\n')
        return sb.toString()
    }

    private fun copyCrashReports(zos: ZipOutputStream, root: Path?) {
        if (root == null) return
        val dir = root.resolve("crash-reports")
        if (!Files.isDirectory(dir)) return
        val files = ArrayList<Path>()
        Files.newDirectoryStream(dir, "*.txt").use { stream ->
            for (p in stream) files.add(p)
        }
        files.sortByDescending {
            try { Files.getLastModifiedTime(it).toMillis() } catch (_: Exception) { 0L }
        }
        for (p in files.take(MAX_CRASH_REPORTS)) {
            copyIfExists(zos, "crash-reports/${p.fileName}", p)
        }
    }

    private fun listMods(gameDir: Path?): String {
        if (gameDir == null) return "(no gameDir)\n"
        val mods = gameDir.resolve("mods")
        if (!Files.isDirectory(mods)) return "(no mods dir)\n"
        val names = ArrayList<String>()
        Files.newDirectoryStream(mods).use { stream ->
            for (p in stream) {
                val n = p.fileName.toString()
                if (n.endsWith(".jar") || n.endsWith(".jar.disabled")) names.add(n)
            }
        }
        names.sortWith(String.CASE_INSENSITIVE_ORDER)
        return if (names.isEmpty()) "(empty)\n" else names.joinToString("\n", postfix = "\n")
    }

    private fun copyIfExists(zos: ZipOutputStream, entryName: String, file: Path?) {
        if (file == null || !Files.isRegularFile(file)) return
        zos.putNextEntry(ZipEntry(entryName))
        val size = Files.size(file)
        Files.newInputStream(file).use { inn ->
            val buf = ByteArray(8192)
            var remaining = minOf(size, MAX_FILE_BYTES)
            while (remaining > 0) {
                val n = inn.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                zos.write(buf, 0, n)
                remaining -= n
            }
        }
        zos.closeEntry()
    }

    private fun putText(zos: ZipOutputStream, entryName: String, text: String) {
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(text.toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }

    private fun joinTruncated(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.length > MAX_LOG_CHARS) break
            sb.append(line).append('\n')
        }
        return truncate(sb.toString(), MAX_LOG_CHARS)
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(s.length - max)
}
