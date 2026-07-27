package com.pmcl.plugin.api

import java.nio.file.Path

/**
 * Instance management surface.
 * Requires [com.pmcl.plugin.PluginPermission.MANAGE_INSTANCES] for mutating methods.
 */
interface InstancesApi {
    fun listInstances(): List<InstanceSummary>

    fun getInstance(instanceId: String): InstanceSummary?

    /**
     * Create a custom instance.
     * @return new instance id
     */
    fun createInstance(name: String, baseVersionId: String, loader: String? = null, loaderVersion: String? = null): String

    fun renameInstance(instanceId: String, newName: String)

    fun deleteInstance(instanceId: String)

    fun setDescription(instanceId: String, description: String?)

    /** Resolve the instance directory on disk. */
    fun resolveInstanceDir(instanceId: String): Path
}
