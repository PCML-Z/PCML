package com.pmcl.plugin.api

/**
 * Official Minecraft news feed access.
 * Requires [com.pmcl.plugin.PluginPermission.NETWORK].
 */
interface NewsApi {
    /** Fetch recent news items (capped by host). */
    fun fetchNews(limit: Int = 20): List<NewsSummary>
}
