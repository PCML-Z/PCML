package com.pmcl.plugin.api

import java.nio.file.Path

/**
 * Mod scanning and local mod file mutations.
 * Scanning requires [com.pmcl.plugin.PluginPermission.READ_MODS].
 * Enable / disable / delete require [com.pmcl.plugin.PluginPermission.MANAGE_MODS].
 */
interface ModsApi {
    /** Scan mods in a directory (e.g. instance mods folder). */
    fun scanMods(modsDir: Path): List<ModSummary>

    /** Resolve the mods directory for a version/instance id under current preferences. */
    fun resolveModsDir(versionOrInstanceId: String?): Path

    /** Enable a mod jar (absolute path). */
    fun enableMod(jarPath: Path)

    /** Disable a mod jar (renames to `.jar.disabled`). */
    fun disableMod(jarPath: Path)

    /** Delete a mod jar. */
    fun deleteMod(jarPath: Path)
}
