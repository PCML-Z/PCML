package com.pmcl.downloader

import com.pmcl.plugin.PmclPlugin
import com.pmcl.plugin.PluginContext
import com.pmcl.plugin.api.DownloadsApi
import java.nio.file.Paths

/**
 * 自定义下载器插件 —— 使用稳定 [DownloadsApi]（隔离 ClassLoader 安全）。
 */
class CustomDownloaderPlugin : PmclPlugin {
    override val pluginId = "custom-downloader"

    private var downloads: DownloadsApi? = null

    override fun onEnable(ctx: PluginContext) {
        downloads = ctx.downloads()
        ctx.info("Custom Downloader enabled — DownloadsApi acquired.")

        ctx.registerCommand("dl-text", "Download URL content as text and display it") { args ->
            val url = args.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@registerCommand "Usage: dl-text <url>"
            if (!UrlValidator.isValidUrl(url)) {
                return@registerCommand "Error: Invalid URL — ${UrlValidator.getValidationError(url)}"
            }
            try {
                val content = downloads!!.downloadString(url)
                if (content.length > 5000) {
                    content.substring(0, 5000) + "\n\n... (truncated, total ${content.length} chars)"
                } else {
                    content
                }
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
        }

        ctx.registerCommand("dl-file", "Download a file into the plugin data directory") { args ->
            val url = args.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@registerCommand "Usage: dl-file <url> [filename]"
            if (!UrlValidator.isValidUrl(url)) {
                return@registerCommand "Error: Invalid URL — ${UrlValidator.getValidationError(url)}"
            }
            val filename = if (args.size >= 2 && args[1].isNotBlank()) {
                Paths.get(args[1]).fileName.toString()
            } else {
                FileHelper.extractFilename(url)
            }
            val savePath = ctx.getDataDir().resolve(filename)
            try {
                downloads!!.downloadTo(url, savePath)
                val size = java.nio.file.Files.size(savePath)
                "Downloaded: $url\nSaved to: $savePath\nSize: ${formatSize(size)}"
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
        }

        ctx.registerCommand("dl-head", "Download and preview first 500 chars of URL content") { args ->
            val url = args.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@registerCommand "Usage: dl-head <url>"
            if (!UrlValidator.isValidUrl(url)) {
                return@registerCommand "Error: Invalid URL — ${UrlValidator.getValidationError(url)}"
            }
            try {
                val content = downloads!!.downloadString(url)
                if (content.length <= 500) content
                else content.substring(0, 500) + "\n\n... (${content.length} chars total)"
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
        }

        ctx.info("Registered commands: dl-text, dl-file, dl-head")

        ctx.registerPage(
            "downloader-page",
            "Downloader",
            DownloaderPageContent(downloads!!, ctx.getDataDir())
        )
        ctx.info("Registered GUI page: Downloader")
    }

    override fun onDisable() {
        downloads = null
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
        return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
