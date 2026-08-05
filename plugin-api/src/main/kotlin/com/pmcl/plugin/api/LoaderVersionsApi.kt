package com.pmcl.plugin.api

import java.util.concurrent.CompletableFuture

/**
 * 模组加载器版本列表查询。
 *
 * 通过此 API 查询 Fabric/Forge/NeoForge/Quilt/OptiFine 等
 * 加载器针对特定 Minecraft 版本的可安装版本号。
 */
interface LoaderVersionsApi {

    /**
     * 获取指定加载器在某 MC 版本下的可用版本列表。
     *
     * @param loader  加载器标识："Fabric", "Forge", "NeoForge", "Quilt", "OptiFine"
     * @param gameVersion  Minecraft 版本号，如 "1.20.4"
     * @return 加载器版本号列表（例如 ["0.16.10", "0.16.9"]），异步返回
     */
    fun listVersions(loader: String, gameVersion: String): CompletableFuture<List<String>>
}
