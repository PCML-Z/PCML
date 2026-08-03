package com.lash.pmcl.launch

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * JRE 运行时安装器。
 *
 * 首次启动时从 APK assets 中提取 JRE tar.xz 文件，
 * 解压到内部存储目录。支持 4 种 CPU 架构。
 */
object JreInstaller {

    private const val JRE_VERSION = "jre8"
    private val ARCH_MAP = mapOf(
        "aarch64" to "arm64",
        "armv7l" to "arm",
        "arm" to "arm",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    fun archSuffix(): String {
        val osArch = System.getProperty("os.arch") ?: "aarch64"
        return ARCH_MAP[osArch] ?: "arm64"
    }

    fun installDir(context: Context): File =
        File(context.filesDir, "pmcl/runtimes/$JRE_VERSION")

    fun isInstalled(context: Context): Boolean =
        File(installDir(context), "release").exists()

    /**
     * 从 APK assets 中提取并安装 JRE。
     *
     * @param context 上下文
     * @param onProgress 进度回调 (0-100, 描述)
     */
    fun install(context: Context, onProgress: (Int, String) -> Unit = { _, _ -> }) {
        val destDir = installDir(context)
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        // 1. 提取并解压 universal.tar.xz (平台无关)
        onProgress(0, "解压 JRE 通用文件...")
        extractFromAssets(context, "components/jre/universal.tar.xz", destDir)
        onProgress(50, "通用文件完成")

        // 2. 提取并解压 bin-{arch}.tar.xz (平台相关)
        val arch = archSuffix()
        onProgress(50, "解压 JRE $arch 库...")
        extractFromAssets(context, "components/jre/bin-$arch.tar.xz", destDir)
        onProgress(100, "JRE 安装完成")
    }

    private fun extractFromAssets(context: Context, assetPath: String, destDir: File) {
        // 先拷贝到临时文件，再用 tar 解压
        val tmpFile = File.createTempFile("pmcl_jre_", ".tar.xz")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output, 8192)
                }
            }

            // 使用系统 tar 解压
            val process = Runtime.getRuntime().exec(
                arrayOf("tar", "-xJf", tmpFile.absolutePath, "-C", destDir.absolutePath),
                null,
                destDir
            )
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val err = process.errorStream.bufferedReader().readText()
                throw RuntimeException("tar 解压失败 (code=$exitCode): $err")
            }
        } finally {
            tmpFile.delete()
        }
    }
}
