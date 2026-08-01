package com.lash.pmcl.core.modloader

import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.install.VersionStaging
import com.lash.pmcl.core.paths.PmclPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer

/** 安装器共用：确保原版父版本已安装。 */
internal object ParentVersionSupport {

    @Throws(IOException::class)
    fun ensureParentInstalled(
        paths: PmclPaths,
        versionInstaller: VersionInstaller?,
        parentId: String,
        onProgress: Consumer<InstallProgress>?
    ) {
        VersionStaging.assertSafeVersionId(parentId)
        val parentDir = paths.versions.resolve(parentId)
        val parentJson = parentDir.resolve("$parentId.json")
        val parentJar = parentDir.resolve("$parentId.jar")
        if (Files.isRegularFile(parentJson) && Files.isRegularFile(parentJar)
            && Files.size(parentJar) > 1024
        ) {
            return
        }
        if (versionInstaller == null) {
            throw IOException("缺少原版父版本 $parentId，请先安装 Minecraft $parentId")
        }
        onProgress?.accept(
            InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                "安装原版父版本 $parentId"
            )
        )
        try {
            versionInstaller.install(parentId, onProgress).join()
        } catch (ce: java.util.concurrent.CompletionException) {
            val c = ce.cause ?: ce
            if (c is IOException) throw c
            if (c is RuntimeException) throw c
            throw IOException("安装原版父版本失败: $parentId", c)
        }
    }

    @Throws(IOException::class)
    fun downloadFirstOk(downloads: DownloadManager, dest: Path, vararg urls: String) {
        Files.createDirectories(dest.parent)
        var last: IOException? = null
        for (url in urls) {
            if (url.isBlank()) continue
            try {
                downloads.downloadTo(url, dest)
                if (Files.isRegularFile(dest) && Files.size(dest) > 64) return
            } catch (e: IOException) {
                last = e
                try { Files.deleteIfExists(dest) } catch (_: IOException) {}
            }
        }
        throw last ?: IOException("所有下载源均失败")
    }
}
