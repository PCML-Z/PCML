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
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Rift 安装器（当前支持 Minecraft 1.13.2）。
 *
 * 使用 Chocohead 维护的 JitPack 构件 + Sponge Mixin 0.7.11-SNAPSHOT + LaunchWrapper。
 */
class RiftInstaller(
    private val paths: PmclPaths,
    private val downloads: DownloadManager,
    private val versionInstaller: VersionInstaller?
) : ModLoaderInstaller {

    /** gameVersion → (loaderVersion → Chocohead/JitPack commit) */
    private val versions: Map<String, Map<String, String>> = mapOf(
        "1.13.2" to linkedMapOf(
            "1.0.4-2d8bb9bd56" to "2d8bb9bd56",
            "1.0.4" to "2d8bb9bd56"
        )
    )

    override fun listVersions(gameVersion: String): CompletableFuture<List<ModLoaderVersion>> {
        return CompletableFuture.supplyAsync {
            val out = ArrayList<ModLoaderVersion>()
            val map = versions[gameVersion] ?: return@supplyAsync out
            var first = true
            for (ver in map.keys) {
                out.add(ModLoaderVersion(ModLoader.RIFT, gameVersion, ver, first))
                first = false
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
            val id = "$gameVersion-rift-$loaderVersion"
            try {
                val map = versions[gameVersion]
                if (map == null || !map.containsKey(loaderVersion)) {
                    throw IllegalArgumentException(
                        "Rift 不支持 MC $gameVersion / $loaderVersion（当前支持 1.13.2）"
                    )
                }
                val riftCoordVer = map[loaderVersion]!!
                VersionStaging.assertSafeVersionId(id)
                ParentVersionSupport.ensureParentInstalled(
                    paths, versionInstaller, gameVersion, onProgress
                )

                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1, "构造 Rift 版本 JSON"
                    )
                )

                val profile = buildProfile(id, gameVersion, riftCoordVer)
                val staging = writeAndDownload(id, profile, onProgress)
                VersionStaging.promote(paths.versions, id, staging.stagingDir)

                onProgress?.accept(
                    InstallProgress(InstallProgress.Stage.DONE, 1, 1, "Rift 安装完成: $id")
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
                    else InstallInterruptedException("Rift 安装已中断", e)
                }
                throw RuntimeException("Rift 安装失败: $detail", e)
            }
        }
    }

    private fun buildProfile(id: String, gameVersion: String, riftCoordVer: String): JsonObject {
        val profile = JsonObject()
        profile.addProperty("id", id)
        profile.addProperty("inheritsFrom", gameVersion)
        profile.addProperty("type", "release")
        profile.addProperty("mainClass", "net.minecraft.launchwrapper.Launch")

        val args = JsonObject()
        val game = JsonArray()
        game.add("--tweakClass")
        game.add("org.dimdev.riftloader.launch.RiftLoaderClientTweaker")
        args.add("game", game)
        profile.add("arguments", args)

        val libs = JsonArray()
        libs.add(lib("com.github.Chocohead:rift:$riftCoordVer", JITPACK))
        libs.add(lib("org.ow2.asm:asm:6.2", MAVEN_CENTRAL))
        libs.add(lib("org.ow2.asm:asm-commons:6.2", MAVEN_CENTRAL))
        libs.add(lib("org.ow2.asm:asm-tree:6.2", MAVEN_CENTRAL))
        libs.add(lib("net.minecraft:launchwrapper:1.12", null))

        // Mixin：带精确 downloads URL（SNAPSHOT 文件名含时间戳）
        val mixin = JsonObject()
        mixin.addProperty("name", "org.spongepowered:mixin:0.7.11-SNAPSHOT")
        val downloadsObj = JsonObject()
        val artifact = JsonObject()
        artifact.addProperty(
            "path",
            "org/spongepowered/mixin/0.7.11-SNAPSHOT/mixin-0.7.11-SNAPSHOT.jar"
        )
        artifact.addProperty("url", MIXIN_JAR)
        downloadsObj.add("artifact", artifact)
        mixin.add("downloads", downloadsObj)
        libs.add(mixin)

        profile.add("libraries", libs)
        return profile
    }

    private fun lib(name: String, url: String?): JsonObject {
        val o = JsonObject()
        o.addProperty("name", name)
        if (url != null) o.addProperty("url", url)
        return o
    }

    @Throws(Exception::class)
    private fun writeAndDownload(
        id: String,
        profile: JsonObject,
        onProgress: Consumer<InstallProgress>?
    ): PathStagingResult {
        val json = profile.toString()
        val staging = VersionStaging.writeVersionJson(paths.versions, id, json)
        val n = ModLoaderProfileLibraries.downloadMissing(
            downloads, paths.libraries, json, "Rift", onProgress
        )
        return PathStagingResult(staging, n)
    }

    private data class PathStagingResult(val stagingDir: Path, val libCount: Int)

    companion object {
        private const val JITPACK = "https://jitpack.io/"
        private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2/"
        private const val SPONGE = "https://repo.spongepowered.org/repository/maven-public/"
        private const val MIXIN_JAR =
            SPONGE + "org/spongepowered/mixin/0.7.11-SNAPSHOT/mixin-0.7.11-20180703.121122-1.jar"
    }
}
