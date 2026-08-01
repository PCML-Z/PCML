package com.lash.pmcl.core.modloader

import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.paths.PmclPaths
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Quilt 安装器。
 *
 * Quilt Meta API 与 Fabric 几乎一致：
 *   https://meta.quiltmc.org/v3/versions/loader/{game_version}
 *   https://meta.quiltmc.org/v3/versions/loader/{game_version}/{loader_version}/profile/json
 *
 * 实际逻辑由 [FabricMetaInstaller] 提供，本类仅固定 Quilt 的 Meta 端点。
 */
class QuiltInstaller(
    paths: PmclPaths,
    downloads: DownloadManager,
    versionInstaller: VersionInstaller?
) : ModLoaderInstaller {

    private val delegate = FabricMetaInstaller(
        ModLoader.QUILT,
        QUILT_META,
        "Quilt",
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
        private const val QUILT_META = "https://meta.quiltmc.org/v3/versions/loader/"
    }
}
