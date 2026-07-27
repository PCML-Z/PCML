package com.pmcl.plugin

/**
 * Extension point: launch hooks.
 *
 * Allows plugins to gate, observe, or contribute JVM/game arguments / env / agents
 * to Minecraft launches.
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
     * Optional human-readable cancel reason when [beforeLaunch] returned false.
     * Host shows this in logs / UI.
     */
    fun cancelReason(): String? = null

    /**
     * Extra JVM arguments appended after the host profile is built.
     * Blank / null entries are ignored. Requires the plugin to have registered a launch hook
     * (typically with [com.pmcl.plugin.PluginPermission.CONTROL_LAUNCH]).
     */
    fun contributeJvmArgs(versionId: String, accountName: String): List<String> = emptyList()

    /**
     * Extra game (Minecraft) arguments appended after the host profile is built.
     */
    fun contributeGameArgs(versionId: String, accountName: String): List<String> = emptyList()

    /**
     * Extra environment variables merged into the game process environment.
     * Keys must be non-blank; values may be empty. Host may reject unsafe keys
     * (e.g. `LD_PRELOAD`, `DYLD_*`, `PATH` overwrite attempts).
     */
    fun contributeEnv(versionId: String, accountName: String): Map<String, String> = emptyMap()

    /**
     * Extra JARs appended to the launch classpath (absolute paths).
     * Non-existent paths are skipped.
     */
    fun contributeClasspathJars(versionId: String, accountName: String): List<String> = emptyList()

    /**
     * Extra Java agents as `jarPath` or `jarPath=options`.
     * Injected before other JVM args.
     */
    fun contributeJavaAgents(versionId: String, accountName: String): List<String> = emptyList()

    /**
     * Called after Minecraft exits.
     * @param versionId The Minecraft version that was launched
     * @param exitCode Process exit code
     */
    fun afterLaunch(versionId: String, exitCode: Int) {}
}
