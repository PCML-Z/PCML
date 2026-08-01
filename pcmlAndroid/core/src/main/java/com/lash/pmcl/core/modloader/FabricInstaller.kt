package com.lash.pmcl.core.modloader

import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.paths.PmclPaths
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Fabric 安装器。
 *
 * Fabric Meta API：
 *   https://meta.fabricmc.net/v2/versions/loader/{game_version}
 *   https://meta.fabricmc.net/v2/versions/loader/{game_version}/{loader_version}/profile/json
 *
 * 实际逻辑由 [FabricMetaInstaller] 提供，本类仅固定 Fabric 的 Meta 端点。
 */
class FabricInstaller(
    paths: PmclPaths,
    downloads: DownloadManager,
    versionInstaller: VersionInstaller?
) : ModLoaderInstaller {

    private val delegate = FabricMetaInstaller(
        ModLoader.FABRIC,
        FABRIC_META,
        "Fabric",
        paths,
        downloads,
        versionInstaller
    )

    override fun listVersions(gameVersion: String): CompletableFuture<List<ModLoaderVersion>> =
        delegate.listVersions(gameVersion)

    override fun install(
        gameVersion: String,
        loaderVersion: String,
        onProgress: Consumer<InstallProgress>?
    ): CompletableFuture<Void> =
        delegate.install(gameVersion, loaderVersion, onProgress)

    companion object {
        private const val FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/"
    }
}
