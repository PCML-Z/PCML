package com.pmcl.plugin.api

import java.nio.file.Path
import java.util.function.LongConsumer

/**
 * Network downloads with SSRF protection.
 * Requires [com.pmcl.plugin.PluginPermission.NETWORK].
 */
interface DownloadsApi {
    /**
     * Download a remote URL into [target] (atomic temp + move).
     * Destination must be under the plugin data directory unless the host relaxes it.
     */
    fun downloadTo(url: String, target: Path) =
        downloadTo(url, target, null)

    /**
     * Same as [downloadTo] with optional byte-progress callback.
     * @param onProgress receives completed bytes so far (may be called frequently)
     */
    fun downloadTo(url: String, target: Path, onProgress: LongConsumer?)

    /** Fetch a text response (capped by host). */
    fun downloadString(url: String): String
}
