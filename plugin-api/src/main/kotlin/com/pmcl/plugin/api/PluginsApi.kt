package com.pmcl.plugin.api

/**
 * Introspect / manage other plugins.
 * Mutating methods require [com.pmcl.plugin.PluginPermission.MANAGE_PLUGINS].
 */
interface PluginsApi {
    fun listPlugins(): List<PluginInfoSummary>

    fun getPlugin(pluginId: String): PluginInfoSummary?

    fun enablePlugin(pluginId: String)

    fun disablePlugin(pluginId: String)

    fun unloadPlugin(pluginId: String)

    /** Whether [pluginId] is currently enabled. */
    fun isEnabled(pluginId: String): Boolean
}
