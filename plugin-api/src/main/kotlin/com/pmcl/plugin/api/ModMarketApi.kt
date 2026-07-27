package com.pmcl.plugin.api

/**
 * Modrinth / CurseForge market search and install.
 * Requires [com.pmcl.plugin.PluginPermission.NETWORK];
 * install also requires [com.pmcl.plugin.PluginPermission.MANAGE_MODS].
 */
interface ModMarketApi {

    fun search(
        query: String,
        gameVersion: String? = null,
        loader: String? = null,
        limit: Int = 20,
    ): List<MarketProjectSummary>

    fun listFiles(source: String, projectId: String): List<MarketFileSummary>

    /**
     * Install a market file into the mods directory for [gameVersion].
     * When version isolation is on, pass [versionOrInstanceId] to target that instance.
     */
    fun install(
        source: String,
        projectId: String,
        fileId: String,
        gameVersion: String,
        versionOrInstanceId: String? = null,
    )
}
