package com.pmcl.core.modloader;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.VersionInstaller;

import java.util.EnumMap;
import java.util.Map;

/**
 * 模组加载器管理：注册并获取各加载器的安装器实例。
 */
public final class ModLoaderManager {

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/";
    private static final String QUILT_META = "https://meta.quiltmc.org/v3/versions/loader/";
    private static final String LEGACY_FABRIC_META = "https://meta.legacyfabric.net/v2/versions/loader/";
    private static final String BABRIC_META = "https://meta.babric.glass-launcher.net/v2/versions/loader/";
    private static final String ORNITHE_META = "https://meta.ornithemc.net/v2/versions/loader/";

    private final Map<ModLoader, ModLoaderInstaller> installers = new EnumMap<>(ModLoader.class);

    public ModLoaderManager(LauncherConfig config, DownloadManager downloads,
                            VersionInstaller versionInstaller) {
        installers.put(ModLoader.FABRIC, new FabricMetaInstaller(
                ModLoader.FABRIC, FABRIC_META, "Fabric", config, downloads, versionInstaller));
        installers.put(ModLoader.QUILT, new FabricMetaInstaller(
                ModLoader.QUILT, QUILT_META, "Quilt", config, downloads, versionInstaller));
        installers.put(ModLoader.LEGACY_FABRIC, new FabricMetaInstaller(
                ModLoader.LEGACY_FABRIC, LEGACY_FABRIC_META, "Legacy Fabric",
                config, downloads, versionInstaller));
        installers.put(ModLoader.BABRIC, new FabricMetaInstaller(
                ModLoader.BABRIC, BABRIC_META, "Babric", config, downloads, versionInstaller));
        installers.put(ModLoader.BTA_BABRIC, new FabricMetaInstaller(
                ModLoader.BTA_BABRIC, BABRIC_META, "BTA (Babric)",
                config, downloads, versionInstaller));
        installers.put(ModLoader.ORNITHE, new FabricMetaInstaller(
                ModLoader.ORNITHE, ORNITHE_META, "Ornithe", config, downloads, versionInstaller));
        installers.put(ModLoader.FORGE, new ForgeInstaller(config, downloads, false, versionInstaller));
        installers.put(ModLoader.NEOFORGE, new ForgeInstaller(config, downloads, true, versionInstaller));
        installers.put(ModLoader.OPTIFINE, new OptiFineInstaller(config, downloads));
        installers.put(ModLoader.LITELOADER, new LiteLoaderInstaller(config, downloads));
        installers.put(ModLoader.RIFT, new RiftInstaller(config, downloads, versionInstaller));
        installers.put(ModLoader.NILLOADER, new NilLoaderInstaller(config, downloads, versionInstaller));
        installers.put(ModLoader.JAVA_AGENT, new JavaAgentInstaller(config, downloads, versionInstaller));
        installers.put(ModLoader.RISUGAMI, new RisugamiInstaller(config, downloads, versionInstaller));
    }

    public ModLoaderInstaller get(ModLoader loader) {
        ModLoaderInstaller inst = installers.get(loader);
        if (inst == null) {
            throw new IllegalArgumentException("不支持的加载器: " + loader);
        }
        return inst;
    }

    public boolean supports(ModLoader loader) {
        return installers.containsKey(loader);
    }
}
