package com.pmcl.plugin

/**
 * Extension point: launch hooks.
 *
 * Allows plugins to modify launch behavior before and after Minecraft starts.
 */
interface LaunchHook {

    /**
     * Called before Minecraft launches.
     * @param versionId The Minecraft version being launched
     * @param accountName The player account name
     * @return true to allow launch, false to cancel
     */
    fun beforeLaunch(versionId: String, accountName: String): Boolean

    /**
     * Called after Minecraft exits.
     * @param versionId The Minecraft version that was launched
     * @param exitCode Process exit code
     */
    fun afterLaunch(versionId: String, exitCode: Int) {}
}
