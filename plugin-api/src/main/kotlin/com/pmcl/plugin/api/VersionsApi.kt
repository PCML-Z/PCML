package com.pmcl.plugin.api

import java.nio.file.Path

/**
 * Local / remote Minecraft versions.
 * Install / delete require [com.pmcl.plugin.PluginPermission.MANAGE_VERSIONS].
 * Remote listing requires [com.pmcl.plugin.PluginPermission.NETWORK].
 */
interface VersionsApi {
    /** List local version IDs under the primary versions directory. */
    fun listLocalVersionIds(): List<String>

    /** Scan all known version directories and return summaries. */
    fun listLocalVersions(): List<VersionSummary>

    /** Whether a version id appears launchable (has version JSON). */
    fun isLaunchable(versionId: String): Boolean

    /**
     * Install a version via the host download queue (preferred) or direct installer.
     * Returns a queue task id when queued; may be empty when installed synchronously.
     */
    fun installVersion(versionId: String): String

    /**
     * Delete a local version directory under the primary versions root.
     * Does not touch shared libraries / assets.
     */
    fun deleteVersion(versionId: String)

    /** Resolve the on-disk directory for a local version id (may not exist yet). */
    fun resolveVersionDir(versionId: String): Path

    /**
     * Fetch remote version manifest summaries (release / snapshot / …).
     * @param limit max entries (host clamps, e.g. 1..500)
     */
    fun listRemoteVersions(limit: Int = 100): List<RemoteVersionSummary>
}
