package com.pmcl.plugin.api

import java.nio.file.Path

/**
 * Installed modpack listing and import.
 * Listing is free; import requires [com.pmcl.plugin.PluginPermission.MANAGE_MODPACKS].
 */
interface ModpackApi {
    fun listInstalled(): List<ModpackSummary>

    /**
     * Import a .mrpack / CurseForge zip into instances.
     * @return imported instance/modpack display name when known
     */
    fun importModpack(file: Path): String
}
