package com.pmcl.core.modloader;

import com.pmcl.core.install.InstallProgress;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 模组加载器安装器接口。
 */
public interface ModLoaderInstaller {

    /**
     * 列出某 MC 版本下所有可用的加载器版本。
     */
    CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion);

    /**
     * 安装指定加载器版本到本地。
     *
     * @param gameVersion   MC 版本，如 "1.20.4"
     * @param loaderVersion 加载器版本，如 "0.15.7"
     * @param onProgress    进度回调
     */
    CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                    Consumer<InstallProgress> onProgress);
}
