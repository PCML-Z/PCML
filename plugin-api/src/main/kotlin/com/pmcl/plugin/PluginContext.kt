package com.pmcl.plugin

import androidx.compose.runtime.Composable
import java.nio.file.Path

/**
 * Context provided to plugins during [PmclPlugin.onEnable].
 *
 * Gives plugins access to:
 * - PMCL core services via [getService]
 * - Plugin-specific data directory via [getDataDir]
 * - Registration methods for commands, pages, hooks, and event listeners
 * - Logging
 */
interface PluginContext {

    // ==================== Services ====================

    /**
     * Retrieve a PMCL service by type.
     * Available services include:
     * - `com.pmcl.core.LauncherCore` — the main launcher core (requires core as compileOnly dep)
     * - `com.pmcl.core.preferences.Preferences`
     * - `com.pmcl.core.version.VersionManager`
     * - `com.pmcl.core.download.DownloadManager`
     * - `com.pmcl.core.launch.LaunchManager`
     * - `com.pmcl.core.multiplayer.MultiplayerManager`
     * - and other core managers...
     *
     * @return the service instance, or null if not available
     */
    fun <T> getService(type: Class<T>): T?

    // ==================== Plugin Data ====================

    /** Plugin-specific data directory (created if not exists). */
    fun getDataDir(): Path

    /** Plugin configuration value (persisted in plugins.json). */
    fun getConfig(key: String): String?

    /** Set a plugin configuration value (persisted in plugins.json). */
    fun setConfig(key: String, value: String)

    // ==================== Logging ====================

    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)

    // ==================== Registration ====================

    /**
     * Register a custom shell command.
     * The command will be available as `plugin:<pluginId>:<name>` in the shell.
     *
     * @param name Command name (lowercase, alphanumeric, 1-32 chars)
     * @param description Short description shown in help
     * @param handler Function that receives args and returns text output
     */
    fun registerCommand(name: String, description: String, handler: CommandHandler)

    /**
     * Register a custom GUI page that appears in the sidebar navigation.
     *
     * @param id Page identifier (lowercase, alphanumeric, 1-32 chars)
     * @param title Display title shown in sidebar
     * @param content Composable function that renders the page content
     */
    fun registerPage(id: String, title: String, content: ComposableContent)

    /**
     * Register a global overlay that floats above the main window content.
     * Overlays are rendered on top of all pages, positioned by the overlay's own
     * Composable (e.g. using Modifier.align or Modifier.offset).
     *
     * @param id Overlay identifier (lowercase, alphanumeric, 1-32 chars, same rules as page id)
     * @param content Composable that renders the overlay content
     * @param config Positioning/behavior config (z-index, click-through, initial visibility)
     */
    fun registerOverlay(id: String, content: ComposableContent, config: OverlayConfig = OverlayConfig.DEFAULT)

    /**
     * Register a launch hook that runs before/after Minecraft launches.
     */
    fun registerLaunchHook(hook: LaunchHook)

    /**
     * Register an event listener for PMCL events.
     */
    fun addEventListener(listener: EventListener)

    /**
     * Fire a custom event that other plugins can listen to.
     */
    fun fireEvent(event: PmclEvent)
}

/**
 * Handler for a custom command.
 * Receives command-line args (excluding the command name itself).
 * Returns text output to display in the terminal.
 */
fun interface CommandHandler {
    fun execute(args: Array<String>): String
}

/**
 * A composable content provider for plugin pages.
 * Implement this in Kotlin to provide a @Composable function.
 *
 * Example:
 * ```kotlin
 * class MyPageContent : ComposableContent {
 *     @Composable
 *     override fun invoke() {
 *         Text("Hello from plugin!")
 *     }
 * }
 * ```
 *
 * For Java plugins, use a Kotlin lambda or implement invoke() with @Composable.
 */
fun interface ComposableContent {
    @Composable
    fun invoke()
}

/**
 * Configuration for a plugin overlay registered via [PluginContext.registerOverlay].
 *
 * @param zIndex Layering order among multiple overlays (higher = on top)
 * @param clickThrough If true, mouse events pass through to underlying content
 * @param initialVisible Whether the overlay is visible when first registered
 */
data class OverlayConfig(
    val zIndex: Int = 0,
    val clickThrough: Boolean = false,
    val initialVisible: Boolean = true
) {
    companion object {
        @JvmField
        val DEFAULT = OverlayConfig()
    }
}
