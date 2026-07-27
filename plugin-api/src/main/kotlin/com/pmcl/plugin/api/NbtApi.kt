package com.pmcl.plugin.api

import java.nio.file.Path

/**
 * Safe NBT helpers for plugins (no core NBT types leak).
 * Requires [com.pmcl.plugin.PluginPermission.FILESYSTEM].
 *
 * Key paths use `/` separators from the compound root, e.g. `Data/LevelName`.
 */
interface NbtApi {

    /** Dump root compound as SNBT text. */
    fun readSnbt(path: Path): String

    /**
     * Read a scalar string representation at [keyPath].
     * Returns null if missing.
     */
    fun getValue(path: Path, keyPath: String): String?

    /** Set a string tag at [keyPath] (creates intermediate compounds as needed). */
    fun setString(path: Path, keyPath: String, value: String)

    /** Set an int tag at [keyPath]. */
    fun setInt(path: Path, keyPath: String, value: Int)

    /** Whether [path] exists and is readable as NBT. */
    fun isNbtFile(path: Path): Boolean
}
