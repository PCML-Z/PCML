package com.pmcl.plugin.api

import java.util.function.DoubleConsumer

/**
 * Host Java runtime discovery / install and host metrics.
 * Remote list/install require [com.pmcl.plugin.PluginPermission.NETWORK].
 */
interface JavaRuntimesApi {

    /** Installed java executable paths under the host runtimes directory. */
    fun listInstalled(): List<String>

    /**
     * List downloadable Mojang runtime entries.
     * [type] is one of: `java-runtime-alpha`, `java-runtime-beta`,
     * `java-runtime-gamma`, `java-runtime-delta`, `jre-legacy` (or short names
     * `alpha`/`beta`/`gamma`/`delta`/`legacy`).
     */
    fun listAvailable(type: String): List<JavaRuntimeSummary>

    /**
     * Install a runtime by [type] + [version] matching an entry from [listAvailable].
     * [onProgress] receives 0.0..1.0 when the host can estimate progress; may be omitted.
     */
    fun install(type: String, version: String, onProgress: DoubleConsumer? = null)

    fun hostMetrics(): HostMetricsSummary
}
