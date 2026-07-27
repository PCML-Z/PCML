package com.pmcl.plugin.api

import java.nio.file.Path

/**
 * Worlds, resource packs, shader packs, datapacks, and screenshots.
 * Mutating methods require [com.pmcl.plugin.PluginPermission.MANAGE_GAME_CONTENT].
 */
interface GameContentApi {

    fun listWorlds(): List<WorldSummary>

    /** Backup a world by folder name; returns backup zip path. */
    fun backupWorld(worldName: String): Path

    fun importWorld(zipFile: Path)

    fun deleteWorld(worldName: String)

    fun listResourcePacks(): List<PackSummary>

    fun enableResourcePack(fileName: String)

    fun disableResourcePack(fileName: String)

    fun listShaderPacks(): List<PackSummary>

    fun enableShaderPack(fileName: String)

    fun disableShaderPack(fileName: String)

    /** List datapacks under a world directory. */
    fun listDatapacks(worldDir: Path): List<PackSummary>

    fun enableDatapack(worldDir: Path, fileName: String)

    fun disableDatapack(worldDir: Path, fileName: String)

    fun listScreenshots(): List<ScreenshotSummary>

    fun deleteScreenshot(fileName: String)
}
