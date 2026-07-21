package com.pmcl.downloader

import com.pmcl.core.download.DownloadManager
import com.pmcl.plugin.PmclPlugin
import com.pmcl.plugin.PluginContext
import java.nio.file.Paths

/**
 * 自定义下载器插件 —— 使用 PMCL 内置的 DownloadManager 下载任意 URL 内容。
 *
 * 通过 PluginContext.getService(DownloadManager::class.java) 获取下载器实例，
 * 自动继承 PMCL 的代理配置、镜像源重写、SSL fallback 等能力。
 *
 * GUI 页面：在侧边栏添加 "Downloader" 页面，提供可视化下载操作。
 *
 * 注册的命令（通过 plugin:custom-downloader:<name> 调用）：
 *   dl-text <url>           下载 URL 文本内容并显示
 *   dl-file <url> [path]    下载文件到本地（默认保存到 ~/.pmcl/downloads/）
 *   dl-mirror               显示当前镜像源状态
 *   dl-head <url>           只下载前 500 字符预览
 */
class CustomDownloaderPlugin : PmclPlugin {
    override val pluginId = "custom-downloader"

    private var dl: DownloadManager? = null

    override fun onEnable(ctx: PluginContext) {
        // 获取 PMCL 下载器实例
        dl = ctx.getService(DownloadManager::class.java)
        if (dl == null) {
            ctx.error("DownloadManager not available! Plugin cannot function.")
            return
        }
        ctx.info("Custom Downloader enabled — DownloadManager acquired.")

        // dl-text: 下载文本内容
        ctx.registerCommand("dl-text", "Download URL content as text and display it") { args ->
            val url = args.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@registerCommand "Usage: dl-text <url>"
            if (!UrlValidator.isValidUrl(url)) {
                return@registerCommand "Error: Invalid URL — ${UrlValidator.getValidationError(url)}"
            }
            try {
                val content = dl!!.downloadString(url)
                val display = if (content.length > 5000) {
                    content.substring(0, 5000) + "\n\n... (truncated, total ${content.length} chars)"
                } else {
                    content
                }
                display
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
        }

        // dl-file: 下载文件到本地
        ctx.registerCommand("dl-file", "Download a file to local disk (default: ~/.pmcl/downloads/)") { args ->
            val url = args.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@registerCommand "Usage: dl-file <url> [savePath]"
            if (!UrlValidator.isValidUrl(url)) {
                return@registerCommand "Error: Invalid URL — ${UrlValidator.getValidationError(url)}"
            }
            // S23 安全修复：保存路径必须位于 ~/.pmcl/downloads/ 内，防止路径穿越
            val savePath = if (args.size >= 2 && args[1].isNotBlank()) {
                try {
                    FileHelper.sanitizeSavePath(args[1])
                } catch (e: IllegalArgumentException) {
                    return@registerCommand "Error: ${e.message}"
                }
            } else {
                val filename = FileHelper.extractFilename(url)
                Paths.get(System.getProperty("user.home"), ".pmcl", "downloads", filename)
            }
            try {
                FileHelper.ensureParentDir(savePath)
                dl!!.downloadTo(url, savePath) { bytesRead ->
                    // 进度回调（静默，CLI单行输出不适合实时进度）
                }
                val size = java.nio.file.Files.size(savePath)
                "Downloaded: $url\nSaved to: $savePath\nSize: ${formatSize(size)}"
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
        }

        // dl-mirror: 显示镜像源状态
        ctx.registerCommand("dl-mirror", "Show current mirror source status") { _ ->
            val mirror = dl!!.mirror()
            buildString {
                appendLine("=== Mirror Source Status ===")
                appendLine("Type:        ${mirror.type}")
                appendLine("Custom base: ${mirror.customBase.ifEmpty { "(not set)" }}")
                appendLine()
                appendLine("Available types: OFFICIAL, BMCLAPI, CUSTOM")
                appendLine("Change via: Settings > Network > Mirror Source")
            }.trim()
        }

        // dl-head: 只下载前500字符预览
        ctx.registerCommand("dl-head", "Download and preview first 500 chars of URL content") { args ->
            val url = args.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@registerCommand "Usage: dl-head <url>"
            if (!UrlValidator.isValidUrl(url)) {
                return@registerCommand "Error: Invalid URL — ${UrlValidator.getValidationError(url)}"
            }
            try {
                val content = dl!!.downloadString(url)
                if (content.length <= 500) {
                    content
                } else {
                    content.substring(0, 500) + "\n\n... (${content.length} chars total)"
                }
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
        }

        ctx.info("Registered 4 commands: dl-text, dl-file, dl-mirror, dl-head")

        // 注册 GUI 页面 — 在 PMCL 侧边栏添加 "Downloader" 页面
        ctx.registerPage(
            "downloader-page",
            "Downloader",
            DownloaderPageContent(dl!!)
        )
        ctx.info("Registered GUI page: Downloader")
    }

    override fun onDisable() {
        dl = null
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
        return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
