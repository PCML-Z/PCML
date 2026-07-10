package com.pmcl.core.modloader;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;

import java.util.EnumMap;
import java.util.Map;

/**
 * 模组加载器管理：注册并获取各加载器的安装器实例。
 */
public final class ModLoaderManager {

    private final Map<ModLoader, ModLoaderInstaller> installers = new EnumMap<>(ModLoader.class);

    public ModLoaderManager(LauncherConfig config, DownloadManager downloads) {
        installers.put(ModLoader.FABRIC, new FabricInstaller(config, downloads));
        installers.put(ModLoader.QUILT, new QuiltInstaller(config, downloads));
        installers.put(ModLoader.FORGE, new ForgeInstaller(config, downloads, false));
        installers.put(ModLoader.NEOFORGE, new ForgeInstaller(config, downloads, true));
        // VANILLA 不需要安装器
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
