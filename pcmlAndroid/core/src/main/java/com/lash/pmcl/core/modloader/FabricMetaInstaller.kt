package com.lash.pmcl.core.modloader

import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallInterruptedException
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.install.VersionStaging
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.Exceptions
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * 兼容 Fabric Meta 协议的加载器安装器（Fabric / Quilt / Legacy Fabric / Babric / Ornithe 等）。
 *
 * 约定端点：
 *   {metaBase}{gameVersion}
 *   {metaBase}{gameVersion}/{loaderVersion}/profile/json
 * 其中 [metaBase] 形如 `https://meta.fabricmc.net/v2/versions/loader/`
 *
 * FabricInstaller 与 QuiltInstaller 均委托给本类实现，仅 metaBase / displayName 不同。
 */
class FabricMetaInstaller(
    private val loader: ModLoader,
    metaBase: String,
    private val displayName: String,
    private val paths: PmclPaths,
    private val downloads: DownloadManager,
    private val versionInstaller: VersionInstaller?
) : ModLoaderInstaller {

    private val metaBase: String = if (metaBase.endsWith("/")) metaBase else "$metaBase/"

    override fun listVersions(gameVersion: String): CompletableFuture<List<ModLoaderVersion>> {
        return CompletableFuture.supplyAsync {
            try {
                val json = downloads.downloadString(metaBase + encode(gameVersion))
                val arr = JsonParser.parseString(json).asJsonArray
                val result = ArrayList<ModLoaderVersion>()
                for (e in arr) {
                    val o = e.asJsonObject
                    val loaderObj = o.getAsJsonObject("loader") ?: continue
                    result.add(
                        ModLoaderVersion(
                            loader,
                            gameVersion,
                            if (loaderObj.has("version") && !loaderObj.get("version").isJsonNull)
                                loaderObj.get("version").asString else "",
                            !loaderObj.has("stable") || loaderObj.get("stable").isJsonNull
                                || loaderObj.get("stable").asBoolean
                        )
                    )
                }
                result
            } catch (ex: Throwable) {
                throw RuntimeException("拉取 $displayName 版本失败", ex)
            }
        }
    }

    override fun install(
        gameVersion: String,
        loaderVersion: String,
        onProgress: Consumer<InstallProgress>?
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            var id: String? = null
            try {
                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "下载 $displayName profile JSON"
                    )
                )
                val profileJsonUrl = metaBase + encode(gameVersion) + "/" +
                        encode(loaderVersion) + "/profile/json"
                val profileJson = downloads.downloadString(profileJsonUrl)

                val profile = JsonParser.parseString(profileJson).asJsonObject
                id = if (profile.has("id") && !profile.get("id").isJsonNull)
                    profile.get("id").asString else ""
                if (id.isNullOrBlank()) {
                    throw IOException("$displayName profile 缺少有效 id")
                }

                val parentId = if (profile.has("inheritsFrom") && !profile.get("inheritsFrom").isJsonNull)
                    profile.get("inheritsFrom").asString else gameVersion
                ParentVersionSupport.ensureParentInstalled(paths, versionInstaller, parentId, onProgress)

                val staging = VersionStaging.writeVersionJson(paths.versions, id, profileJson)
                val libCount = ModLoaderProfileLibraries.downloadMissing(
                    downloads, paths.libraries, profileJson, displayName, onProgress
                )
                VersionStaging.promote(paths.versions, id, staging)

                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        "$displayName 安装完成: $id" +
                                if (libCount > 0) "（依赖库 $libCount）" else ""
                    )
                )
            } catch (e: Exception) {
                if (!InstallInterruptedException.isInterrupted(e) && !id.isNullOrBlank()) {
                    VersionStaging.discard(paths.versions, id)
                }
                val detail = Exceptions.rootMessage(e)
                onProgress?.accept(
                    InstallProgress(InstallProgress.Stage.FAILED, 0, 0, detail)
                )
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw if (e is RuntimeException) e
                    else InstallInterruptedException("$displayName 安装已中断", e)
                }
                throw RuntimeException("$displayName 安装失败: $detail", e)
            }
        }
    }

    companion object {
        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
