package com.lash.pmcl.core.modloader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallInterruptedException
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.install.VersionStaging
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.Exceptions
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.regex.Pattern

/**
 * NilLoader 安装器（Java Agent）。
 *
 * 版本清单优先拉取 `https://repo.sleeping.town/.../maven-metadata.xml`，
 * 失败时回退内置版本列表。安装后写入带 `pmclAgents` 的继承版 JSON。
 */
class NilLoaderInstaller(
    private val paths: PmclPaths,
    private val downloads: DownloadManager,
    private val versionInstaller: VersionInstaller?
) : ModLoaderInstaller {

    override fun listVersions(gameVersion: String): CompletableFuture<List<ModLoaderVersion>> {
        return CompletableFuture.supplyAsync {
            val vers = fetchVersions()
            val out = ArrayList<ModLoaderVersion>()
            for (i in vers.indices) {
                out.add(ModLoaderVersion(ModLoader.NILLOADER, gameVersion, vers[i], i == 0))
            }
            out
        }
    }

    override fun install(
        gameVersion: String,
        loaderVersion: String,
        onProgress: Consumer<InstallProgress>?
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val id = "$gameVersion-nilloader-$loaderVersion"
            try {
                VersionStaging.assertSafeVersionId(id)
                ParentVersionSupport.ensureParentInstalled(
                    paths, versionInstaller, gameVersion, onProgress
                )

                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                        "下载 NilLoader $loaderVersion"
                    )
                )
                val jar = paths.libraries.resolve(
                    AgentLaunchSupport.mavenPath("com.unascribed", "nilloader", loaderVersion)
                )
                ParentVersionSupport.downloadFirstOk(
                    downloads, jar,
                    REPO + AgentLaunchSupport.mavenPath("com.unascribed", "nilloader", loaderVersion)
                )

                val profile = JsonObject()
                profile.addProperty("id", id)
                profile.addProperty("inheritsFrom", gameVersion)
                profile.addProperty("type", "release")
                profile.add(
                    "pmclAgents",
                    AgentLaunchSupport.singleAgentArray(
                        "com.unascribed:nilloader:$loaderVersion", REPO
                    )
                )
                // 空 libraries，保留继承
                profile.add("libraries", JsonArray())

                val staging = VersionStaging.writeVersionJson(
                    paths.versions, id, profile.toString()
                )
                Files.createDirectories(staging.resolve("agents"))
                Files.createDirectories(staging.resolve("nilmods"))
                VersionStaging.promote(paths.versions, id, staging)

                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1, "NilLoader 安装完成: $id"
                    )
                )
            } catch (e: Exception) {
                if (!InstallInterruptedException.isInterrupted(e)) {
                    VersionStaging.discard(paths.versions, id)
                }
                val detail = Exceptions.rootMessage(e)
                onProgress?.accept(
                    InstallProgress(InstallProgress.Stage.FAILED, 0, 0, detail)
                )
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw if (e is RuntimeException) e
                    else InstallInterruptedException("NilLoader 安装已中断", e)
                }
                throw RuntimeException("NilLoader 安装失败: $detail", e)
            }
        }
    }

    private fun fetchVersions(): List<String> {
        try {
            val xml = downloads.downloadString(META)
            val found = ArrayList<String>()
            val m = VERSION_TAG.matcher(xml)
            while (m.find()) {
                val v = m.group(1)?.trim() ?: continue
                if (v.isNotEmpty() && !found.contains(v)) found.add(v)
            }
            if (found.isNotEmpty()) {
                // maven-metadata 通常旧→新；倒序使最新在前
                found.reverse()
                return found
            }
        } catch (_: Throwable) {
        }
        return ArrayList(FALLBACK_VERSIONS)
    }

    companion object {
        internal const val REPO = "https://repo.sleeping.town/"
        private const val META = REPO + "com/unascribed/nilloader/maven-metadata.xml"
        private val FALLBACK_VERSIONS: List<String> = listOf(
            "1.3.6", "1.3.5", "1.3.4", "1.3.3", "1.3.2", "1.3.1", "1.3.0",
            "1.2.2", "1.2.1", "1.2.0",
            "1.1.6", "1.1.5", "1.1.4", "1.1.3", "1.1.2", "1.1.1", "1.1",
            "1.0.3", "1.0.2", "1.0.1", "1.0"
        )
        private val VERSION_TAG = Pattern.compile("<version>([^<]+)</version>")
    }
}
