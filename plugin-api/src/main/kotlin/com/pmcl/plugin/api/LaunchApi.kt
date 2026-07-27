package com.pmcl.plugin.api

/**
 * Launch control without exposing mutable LaunchProfile builders.
 * Requires [com.pmcl.plugin.PluginPermission.CONTROL_LAUNCH] for mutating methods.
 */
interface LaunchApi {
    /**
     * Request the host UI / core to launch a version with the currently selected account.
     * @return null if accepted; otherwise a human-readable denial reason
     */
    fun requestLaunch(versionId: String): String?

    /**
     * Request launch of an instance (host may map instance → base version).
     * @return null if accepted; otherwise a denial reason
     */
    fun requestLaunchInstance(instanceId: String): String?

    /** Kill all tracked game processes. Requires KILL_PROCESS as well when enforced by host. */
    fun killAllGames()

    fun isGameRunning(): Boolean

    /** Number of tracked Minecraft processes currently alive. */
    fun activeProcessCount(): Int
}
