package com.lash.pmcl.core.modloader

import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.paths.PmclPaths
import java.util.EnumMap

/**
 * 模组加载器管理：注册并获取各加载器的安装器实例。
 */
class ModLoaderManager(
    private val paths: PmclPaths,
    private val downloadManager: DownloadManager,
    private val versionInstaller: VersionInstaller
) {
    private val installers: MutableMap<ModLoader, ModLoaderInstaller> =
        EnumMap(ModLoader::class.java)

    init {
        installers[ModLoader.FABRIC] = FabricMetaInstaller(
            ModLoader.FABRIC, FABRIC_META, "Fabric",
            paths, downloadManager, versionInstaller
        )
        installers[ModLoader.QUILT] = FabricMetaInstaller(
            ModLoader.QUILT, QUILT_META, "Quilt",
            paths, downloadManager, versionInstaller
        )
        installers[ModLoader.LEGACY_FABRIC] = FabricMetaInstaller(
            ModLoader.LEGACY_FABRIC, LEGACY_FABRIC_META, "Legacy Fabric",
            paths, downloadManager, versionInstaller
        )
        installers[ModLoader.BABRIC] = FabricMetaInstaller(
            ModLoader.BABRIC, BABRIC_META, "Babric",
            paths, downloadManager, versionInstaller
        )
        installers[ModLoader.BTA_BABRIC] = FabricMetaInstaller(
            ModLoader.BTA_BABRIC, BABRIC_META, "BTA (Babric)",
            paths, downloadManager, versionInstaller
        )
        installers[ModLoader.ORNITHE] = FabricMetaInstaller(
            ModLoader.ORNITHE, ORNITHE_META, "Ornithe",
            paths, downloadManager, versionInstaller
        )
        installers[ModLoader.LITELOADER] = LiteLoaderInstaller(paths, downloadManager)
        installers[ModLoader.RIFT] = RiftInstaller(paths, downloadManager, versionInstaller)
        installers[ModLoader.RISUGAMI] = RisugamiInstaller(paths, downloadManager, versionInstaller)
        installers[ModLoader.NILLOADER] = NilLoaderInstaller(paths, downloadManager, versionInstaller)
        installers[ModLoader.JAVA_AGENT] = JavaAgentInstaller(paths, downloadManager, versionInstaller)
        installers[ModLoader.FORGE] = ForgeInstaller(paths, downloadManager, false)
        installers[ModLoader.NEOFORGE] = ForgeInstaller(paths, downloadManager, true)
    }

    fun get(loader: ModLoader): ModLoaderInstaller =
        installers[loader] ?: throw IllegalArgumentException("不支持的加载器: $loader")

    fun supports(loader: ModLoader): Boolean = installers.containsKey(loader)

    companion object {
        private const val FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/"
        private const val QUILT_META = "https://meta.quiltmc.org/v3/versions/loader/"
        private const val LEGACY_FABRIC_META = "https://meta.legacyfabric.net/v2/versions/loader/"
        private const val BABRIC_META = "https://meta.babric.glass-launcher.net/v2/versions/loader/"
        private const val ORNITHE_META = "https://meta.ornithemc.net/v2/versions/loader/"
    }
}
