package com.pmcl.plugin.api

import java.nio.file.Path

/**
 * Sandboxed filesystem helpers for plugins.
 *
 * - Paths under the plugin data directory are always allowed.
 * - Paths under the launcher work directory require [com.pmcl.plugin.PluginPermission.FILESYSTEM],
 *   except sensitive host files (`accounts.json`, `.keyfile`, `preferences*`, `plugins/`, …).
 * - Symlinks are rejected to prevent sandbox escapes.
 */
interface FilesystemApi {
    /** Plugin-private data root (same as [com.pmcl.plugin.PluginContext.getDataDir]). */
    fun dataDir(): Path

    /** Launcher work directory (e.g. ~/.pmcl). Requires FILESYSTEM. */
    fun workDir(): Path

    fun exists(path: Path): Boolean

    fun isDirectory(path: Path): Boolean

    fun list(path: Path): List<Path>

    fun readText(path: Path): String

    fun readBytes(path: Path): ByteArray

    fun writeText(path: Path, content: String)

    fun writeBytes(path: Path, content: ByteArray)

    fun createDirectories(path: Path)

    fun delete(path: Path)

    fun copy(source: Path, target: Path)

    fun move(source: Path, target: Path)

    /** Resolve a relative path against the plugin data directory. */
    fun resolveData(relative: String): Path
}
