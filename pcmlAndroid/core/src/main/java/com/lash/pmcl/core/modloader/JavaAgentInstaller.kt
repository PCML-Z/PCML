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

/**
 * Java Agent「加载器」：为任意 MC 版本创建可注入 `-javaagent` 的配置。
 *
 * 版本列表：
 * - `blank` — 仅创建 `agents/` 目录，自行放入 agent jar
 * - `nilloader-<ver>` — 预装 NilLoader 作为默认 agent
 */
class JavaAgentInstaller(
    private val paths: PmclPaths,
    private val downloads: DownloadManager,
    private val versionInstaller: VersionInstaller?
) : ModLoaderInstaller {

    private val nilLoader = NilLoaderInstaller(paths, downloads, versionInstaller)

    override fun listVersions(gameVersion: String): CompletableFuture<List<ModLoaderVersion>> {
        return nilLoader.listVersions(gameVersion).thenApply { nilVers ->
            val out = ArrayList<ModLoaderVersion>()
            out.add(ModLoaderVersion(ModLoader.JAVA_AGENT, gameVersion, "blank", true))
            for (nv in nilVers) {
                out.add(
                    ModLoaderVersion(
                        ModLoader.JAVA_AGENT, gameVersion,
                        "nilloader-" + nv.loaderVersion, nv.stable
                    )
                )
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
            val id = "$gameVersion-javaagent" +
                    if (loaderVersion.isNullOrBlank() || "blank" == loaderVersion) ""
                    else "-" + loaderVersion.replace(':', '_')
            try {
                VersionStaging.assertSafeVersionId(id)
                ParentVersionSupport.ensureParentInstalled(
                    paths, versionInstaller, gameVersion, onProgress
                )

                val profile = JsonObject()
                profile.addProperty("id", id)
                profile.addProperty("inheritsFrom", gameVersion)
                profile.addProperty("type", "release")
                profile.add("libraries", JsonArray())

                val nilVer: String? = if (loaderVersion.startsWith("nilloader-"))
                    loaderVersion.substring("nilloader-".length) else null
                if (!nilVer.isNullOrEmpty()) {
                    onProgress?.accept(
                        InstallProgress(
                            InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                            "下载 NilLoader $nilVer"
                        )
                    )
                    val jar = paths.libraries.resolve(
                        AgentLaunchSupport.mavenPath("com.unascribed", "nilloader", nilVer)
                    )
                    ParentVersionSupport.downloadFirstOk(
                        downloads, jar,
                        NilLoaderInstaller.REPO + AgentLaunchSupport.mavenPath(
                            "com.unascribed", "nilloader", nilVer
                        )
                    )
                    profile.add(
                        "pmclAgents",
                        AgentLaunchSupport.singleAgentArray(
                            "com.unascribed:nilloader:$nilVer", NilLoaderInstaller.REPO
                        )
                    )
                }

                val staging = VersionStaging.writeVersionJson(
                    paths.versions, id, profile.toString()
                )
                Files.createDirectories(staging.resolve("agents"))
                VersionStaging.promote(paths.versions, id, staging)

                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1, "Java Agent 配置完成: $id"
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
                    else InstallInterruptedException("Java Agent 安装已中断", e)
                }
                throw RuntimeException("Java Agent 安装失败: $detail", e)
            }
        }
    }
}
