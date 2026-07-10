package com.pmcl.plugin

/**
 * Core interface that all PMCL plugins must implement.
 *
 * Plugin lifecycle:
 *   1. [onLoad]    — called once when the plugin JAR is first loaded (before enable)
 *   2. [onEnable]  — called when the plugin is enabled; register extensions here
 *   3. [onDisable] — called when the plugin is disabled; cleanup here
 *
 * A plugin must have a no-arg constructor.
 *
 * Example (Kotlin):
 * ```
 * class MyPlugin : PmclPlugin {
 *     override val pluginId = "my-plugin"
 *     override fun onEnable(ctx: PluginContext) {
 *         ctx.registerCommand("hello", "Say hello") { args ->
 *             "Hello, ${args.firstOrNull() ?: "World"}!"
 *         }
 *     }
 * }
 * ```
 *
 * Example (Java):
 * ```java
 * public class MyPlugin implements PmclPlugin {
 *     @Override public String getPluginId() { return "my-plugin"; }
 *     @Override public void onEnable(PluginContext ctx) {
 *         ctx.registerCommand("hello", "Say hello", args -> "Hello, World!");
 *     }
 * }
 * ```
 */
interface PmclPlugin {

    /** Unique plugin identifier. Must be lowercase, alphanumeric with hyphens, 3-32 chars. */
    val pluginId: String
        get() = javaClass.simpleName.lowercase()

    /**
     * Called once when the plugin is first loaded into the JVM.
     * Use for early initialization that does not require PMCL services.
     * This is called BEFORE [onEnable].
     */
    fun onLoad() {}

    /**
     * Called when the plugin is enabled.
     * Register commands, pages, hooks, and event listeners here.
     * @param context Provides access to PMCL services and registration methods
     */
    fun onEnable(context: PluginContext)

    /**
     * Called when the plugin is disabled (before unload or PMCL shutdown).
     * Cleanup resources, save state, etc.
     */
    fun onDisable() {}
}
