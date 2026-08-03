package com.lash.pmcl.launch

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * JRE 运行时下载管理器。
 *
 * 第一次启动时自动下载 OpenJDK for Android（Amethyst JRE），
 * 解压到 /sdcard/PMCL/runtimes/ 目录。
 *
 * JRE 下载源可在 build.gradle 中配置，默认使用：
 * - GitHub Releases (需要手动配置 token 访问私有 artifact)
 * - 或自定义 CDN
 */
object JreDownloader {

    /**
     * 默认 JRE 下载配置。
     *
     * 架构映射：
     * - arm64-v8a → aarch64
     * - armeabi-v7a → arm
     * - x86_64 → x86_64
     */
    data class JreSource(
        val name: String,           // 如 "jre21"
        val javaVersion: Int,       // 如 21
        val universalUrl: String,   // 平台无关 .tar.xz
        val binArchUrl: String,     // 平台相关 .tar.xz ({arch} 占位符)
        val binpackVersion: String, // 版本标识
    )

    /** 默认 JRE 源列表（占位，需替换为实际 CDN URL） */
    val DEFAULT_SOURCES = listOf(
        JreSource(
            name = "jre21",
            javaVersion = 21,
            universalUrl = "https://github.com/AngelAuraMC/Amethyst-Android/releases/download/jre-latest/jre21-universal.tar.xz",
            binArchUrl = "https://github.com/AngelAuraMC/Amethyst-Android/releases/download/jre-latest/jre21-bin-{arch}.tar.xz",
            binpackVersion = "21.0.0-amethyst"
        )
    )

    fun installDir(context: Context): File =
        File(context.filesDir, "pmcl/runtimes")

    fun isInstalled(context: Context, name: String): Boolean =
        File(installDir(context), name).isDirectory

    fun archSuffix(): String = when (System.getProperty("os.arch")) {
        "aarch64" -> "aarch64"
        "armv7l", "arm" -> "arm"
        "x86_64" -> "x86_64"
        else -> "aarch64"
    }

    /**
     * 下载并安装 JRE。
     *
     * @param context   上下文
     * @param source    JRE 源配置
     * @param onProgress 进度回调 (0-100, 描述)
     */
    @Throws(Exception::class)
    fun downloadAndInstall(
        context: Context,
        source: JreSource,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ) {
        val destDir = File(installDir(context), source.name)
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        // 1. 下载平台无关部分
        onProgress(0, "下载 ${source.name} 通用文件...")
        val universalFile = File(destDir, "universal.tar.xz")
        download(source.universalUrl, universalFile) { p -> onProgress(p / 2, "通用: $p%") }

        // 2. 下载平台相关部分
        val arch = archSuffix()
        val binUrl = source.binArchUrl.replace("{arch}", arch)
        onProgress(50, "下载 ${source.name} $arch 库...")
        val binFile = File(destDir, "bin-$arch.tar.xz")
        download(binUrl, binFile) { p -> onProgress(50 + p / 2, "库: $p%") }

        // 3. 解压
        onProgress(100, "解压中...")
        extractTarXz(universalFile, destDir)
        extractTarXz(binFile, destDir)

        // 4. 写入版本文件
        File(destDir, "pojav_version").writeText(source.binpackVersion)

        // 5. 清理下载缓存
        universalFile.delete()
        binFile.delete()

        onProgress(100, "${source.name} 安装完成")
    }

    private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 300000
        conn.connect()

        val total = conn.contentLength
        var downloaded = 0L
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var bytes: Int
                while (input.read(buffer).also { bytes = it } != -1) {
                    output.write(buffer, 0, bytes)
                    downloaded += bytes
                    if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                }
            }
        }
        conn.disconnect()
    }

    private fun extractTarXz(tarFile: File, destDir: File) {
        // 简化版解压 — 使用系统 tar 命令
        val process = Runtime.getRuntime().exec(
            arrayOf("tar", "-xJf", tarFile.absolutePath, "-C", destDir.absolutePath)
        )
        process.waitFor()
        if (process.exitValue() != 0) {
            throw RuntimeException("解压失败: ${process.exitValue()}")
        }
    }
}
