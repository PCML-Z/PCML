package com.pmcl.plugin.api

/**
 * Safe host preference reads + plugin-scoped config.
 * Host preference writes require [com.pmcl.plugin.PluginPermission.WRITE_SETTINGS].
 */
interface SettingsApi {
    fun getLanguage(): String
    fun getThemePreset(): String
    fun getMaxMemoryMb(): Int
    fun getMinMemoryMb(): Int
    fun isVersionIsolation(): Boolean
    fun isDarkTheme(): Boolean
    fun getJavaPath(): String
    fun getCustomJvmArgs(): String
    fun getMirrorType(): String
    fun getCustomMirrorBase(): String
    fun getGameWindowWidth(): Int
    fun getGameWindowHeight(): Int
    fun isGameFullscreen(): Boolean
    fun getDownloadThreads(): Int

    fun setLanguage(language: String)
    fun setThemePreset(preset: String)
    fun setMaxMemoryMb(mb: Int)
    fun setMinMemoryMb(mb: Int)
    fun setVersionIsolation(enabled: Boolean)
    fun setDarkTheme(enabled: Boolean)
    fun setJavaPath(path: String)
    fun setCustomJvmArgs(args: String)
    fun setMirrorType(type: String)
    fun setCustomMirrorBase(base: String)
    fun setGameWindowSize(width: Int, height: Int)
    fun setGameFullscreen(enabled: Boolean)
    fun setDownloadThreads(threads: Int)

    /** Plugin-scoped key/value (same as PluginContext.getConfig/setConfig). */
    fun getPluginConfig(key: String): String?
    fun setPluginConfig(key: String, value: String)
}
