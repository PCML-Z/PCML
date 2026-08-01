package com.lash.pmcl.core.modloader

import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.Library
import com.lash.pmcl.core.install.VersionJson
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer

/**
 * 安装期下载 Fabric/Quilt profile JSON 声明的 libraries，
 * 避免「安装完成」后首次启动才在 verifyLibraries 里补齐依赖。
 */
internal object ModLoaderProfileLibraries {

    private data class LibDownload(
        val name: String,
        val url: String,
        val dest: Path,
        val sha1: String?
    )

    /**
     * 根据 profile JSON 下载缺失库到 [librariesDir]。
     *
     * @param label 进度文案前缀（如 `Fabric`）
     * @return 实际下载次数
     */
    @Throws(IOException::class)
    fun downloadMissing(
        downloads: DownloadManager,
        librariesDir: Path,
        profileJson: String,
        label: String,
        onProgress: Consumer<InstallProgress>?
    ): Int {
        if (profileJson.isBlank()) return 0
        val vj = VersionJson.parse(profileJson)
        val pending = ArrayList<LibDownload>()

        for (lib in vj.libraries) {
            if (!lib.appliesToCurrentOs()) continue

            if (lib.nameClassifier != null && lib.nameClassifier.startsWith("natives-")) {
                if (!Library.matchesCurrentNative(lib.nameClassifier)) continue
                val art = lib.artifact
                val dest = librariesDir.resolve(lib.getPath())
                if (isHealthy(dest, art?.sha1)) continue
                val url = resolveUrl(lib, art)
                if (url != null) {
                    pending.add(LibDownload("${lib.name} (native)", url, dest, art?.sha1))
                }
                continue
            }

            val art = lib.artifact
            if (art != null) {
                val dest = librariesDir.resolve(lib.getPath())
                if (!isHealthy(dest, art.sha1)) {
                    val url = resolveUrl(lib, art)
                    if (url != null) {
                        pending.add(LibDownload(lib.name, url, dest, art.sha1))
                    }
                }
            } else if (lib.url.isNotEmpty()) {
                val dest = librariesDir.resolve(lib.getPath())
                if (!isHealthy(dest, null)) {
                    var mavenUrl = lib.url
                    if (!mavenUrl.endsWith("/")) mavenUrl += "/"
                    mavenUrl += lib.getPath()
                    pending.add(LibDownload(lib.name, mavenUrl, dest, null))
                }
            }

            if (lib.isNativeLib && lib.getNativeClassifier() != null) {
                val nativeClassifier = lib.getNativeClassifier()
                val nativeJar = librariesDir.resolve(lib.getPathForClassifier(nativeClassifier))
                val nativeArt = lib.getNativeArtifact()
                val sha1 = nativeArt?.sha1
                if (isHealthy(nativeJar, sha1)) continue
                if (nativeArt != null && nativeArt.url.isNotEmpty()) {
                    pending.add(
                        LibDownload(
                            "${lib.name}:$nativeClassifier",
                            nativeArt.url, nativeJar, sha1
                        )
                    )
                }
            }
        }

        if (pending.isEmpty()) return 0

        val failed = ArrayList<String>()
        var done = 0
        for (i in pending.indices) {
            val d = pending[i]
            onProgress?.accept(
                InstallProgress(
                    InstallProgress.Stage.DOWNLOAD_LIBRARIES,
                    i.toLong(),
                    pending.size.toLong(),
                    "$label 依赖库: ${d.name}"
                )
            )
            try {
                Files.createDirectories(d.dest.parent)
                if (!d.sha1.isNullOrBlank()) {
                    downloads.downloadToVerified(d.url, d.dest, d.sha1, null)
                } else {
                    downloads.downloadTo(d.url, d.dest)
                }
                done++
            } catch (e: IOException) {
                failed.add("${d.name}: ${e.message}")
            }
        }
        if (failed.isNotEmpty()) {
            throw IOException("$label 依赖库下载失败:\n  - " + failed.joinToString("\n  - "))
        }
        return done
    }

    private fun resolveUrl(lib: Library, art: VersionJson.Artifact?): String? {
        if (art != null && art.url.isNotEmpty()) {
            return art.url
        }
        if (lib.url.isNotEmpty()) {
            var mavenUrl = lib.url
            if (!mavenUrl.endsWith("/")) mavenUrl += "/"
            return mavenUrl + lib.getPath()
        }
        return null
    }

    private fun isHealthy(file: Path, sha1: String?): Boolean {
        return try {
            if (!Files.isRegularFile(file) || Files.size(file) < 32) return false
            // 有 sha1 时交给 downloadToVerified 在缺失/损坏时重下；此处仅存在性快速路径
            true
        } catch (e: IOException) {
            false
        }
    }
}
