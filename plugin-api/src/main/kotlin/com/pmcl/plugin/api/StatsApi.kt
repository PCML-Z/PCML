package com.pmcl.plugin.api

/**
 * Read-only play-time statistics.
 * Requires [com.pmcl.plugin.PluginPermission.READ_STATS].
 */
interface StatsApi {

    fun overall(recentDays: Int = 30): OverallStatsSummary

    fun sessions(offset: Int = 0, limit: Int = 50): List<SessionSummary>
}
