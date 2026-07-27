package com.pmcl.plugin.api

/**
 * Crash report scan / analyze.
 * Requires [com.pmcl.plugin.PluginPermission.READ_CRASH_LOGS].
 */
interface CrashLogsApi {
    /** Scan crash-reports under the launcher work directory. */
    fun listReports(): List<CrashReportSummary>

    /** Analyze raw crash / log text (e.g. latest.log contents). */
    fun analyzeText(content: String): CrashReportSummary
}
