package com.pmcl.plugin

import androidx.compose.runtime.Composable
import com.pmcl.plugin.api.AccountsApi
import com.pmcl.plugin.api.ActionHandler
import com.pmcl.plugin.api.CrashLogsApi
import com.pmcl.plugin.api.DownloadQueueApi
import com.pmcl.plugin.api.DownloadsApi
import com.pmcl.plugin.api.FilesystemApi
import com.pmcl.plugin.api.GameContentApi
import com.pmcl.plugin.api.GameProcessApi
import com.pmcl.plugin.api.HttpApi
import com.pmcl.plugin.api.I18nApi
import com.pmcl.plugin.api.InstancesApi
import com.pmcl.plugin.api.JavaRuntimesApi
import com.pmcl.plugin.api.LaunchApi
import com.pmcl.plugin.api.LoaderVersionsApi
import com.pmcl.plugin.api.ModMarketApi
import com.pmcl.plugin.api.ModpackApi
import com.pmcl.plugin.api.ModsApi
import com.pmcl.plugin.api.MusicApi
import com.pmcl.plugin.api.NbtApi
import com.pmcl.plugin.api.NewsApi
import com.pmcl.plugin.api.PluginsApi
import com.pmcl.plugin.api.RoomsApi
import com.pmcl.plugin.api.SchedulerApi
import com.pmcl.plugin.api.ServersApi
import com.pmcl.plugin.api.SettingsApi
import com.pmcl.plugin.api.StatsApi
import com.pmcl.plugin.api.UiApi
import com.pmcl.plugin.api.VersionsApi
import java.nio.file.Path

/**
 * Context provided to plugins during [PmclPlugin.onEnable].
 *
 * Prefer the typed APIs ([versions], [instances], [accounts], …) over legacy
 * [getService] — typed APIs never require `com.pmcl.core.*` class references
 * and work under the isolating class loader.
 */
interface PluginContext {

    // ==================== Typed host APIs (preferred) ====================

    fun versions(): VersionsApi
    fun instances(): InstancesApi
    fun accounts(): AccountsApi
    fun launch(): LaunchApi
    fun loaderVersions(): LoaderVersionsApi
    fun downloads(): DownloadsApi
    fun downloadQueue(): DownloadQueueApi
    fun mods(): ModsApi
    fun modMarket(): ModMarketApi
    fun modpacks(): ModpackApi
    fun gameContent(): GameContentApi
    fun gameProcess(): GameProcessApi
    fun rooms(): RoomsApi
    fun servers(): ServersApi
    fun javaRuntimes(): JavaRuntimesApi
    fun nbt(): NbtApi
    fun crashLogs(): CrashLogsApi
    fun music(): MusicApi
    fun stats(): StatsApi
    fun news(): NewsApi
    fun i18n(): I18nApi
    fun settings(): SettingsApi
    fun ui(): UiApi
    fun filesystem(): FilesystemApi
    fun scheduler(): SchedulerApi
    fun plugins(): PluginsApi
    fun http(): HttpApi

    // ==================== Legacy services ====================

    /**
     * Retrieve a PMCL service by type.
     *
     * **Deprecated for plugins:** isolation class loader blocks `com.pmcl.core.*`.
     * Use typed APIs above instead. Kept for host-side tests / privileged bridges.
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

    // ==================== Threading ====================

    /**
     * Create a daemon thread in this plugin's [ThreadGroup].
     * Prefer this (or [threadFactory]) over raw `Thread(...)` so unload can interrupt
     * the thread when the plugin is disabled.
     */
    fun newThread(name: String = "", task: Runnable): Thread

    /** ThreadFactory that always creates threads in this plugin's [ThreadGroup]. */
    fun threadFactory(): java.util.concurrent.ThreadFactory

    // ==================== Registration ====================

    /**
     * Register a custom shell command.
     * The command will be available as `plugin:<pluginId>:<name>` in the shell.
     */
    fun registerCommand(name: String, description: String, handler: CommandHandler)

    /**
     * Register a custom GUI page that appears in the sidebar navigation.
     */
    fun registerPage(id: String, title: String, content: ComposableContent)

    /**
     * Register a page whose content is a **JavaFX** scene root, embedded into the
     * PMCL main window (JFXPanel → SwingPanel) — the same mechanism the HMCL
     * embed plugin uses, generalized for any plugin.
     *
     * The plugin only implements [JavaFxContent.createRoot]; the host handles
     * JavaFX/Swing/Compose bridging, threading (FX Application Thread),
     * scene caching across page navigation, and cleanup on dispose.
     *
     * `javafx.*` classes resolve to the host-provided JavaFX runtime through the
     * plugin classloader bridge — plugins must NOT bundle their own JavaFX jars.
     *
     * Page id/title rules and sidebar placement are identical to [registerPage].
     * In headless hosts (no UI), an error placeholder page is registered instead.
     */
    fun registerJavaFxPage(id: String, title: String, content: JavaFxContent)

    /**
     * Register a settings section rendered inside Settings > Extensions.
     */
    fun registerSettingsSection(id: String, title: String, content: ComposableContent)

    /**
     * Register a lightweight action shown in Plugins page / the command palette.
     *
     * @param id Action id (same rules as page id)
     * @param title Display title
     * @param description Optional short description
     * @param handler Invoked on the host UI thread when possible
     */
    fun registerMenuAction(id: String, title: String, description: String = "", handler: ActionHandler) =
        registerMenuAction(id, title, description, emptyList(), handler)

    /**
     * Register a lightweight action shown in Plugins page / the command palette,
     * with optional search keywords (API 1.8+).
     *
     * Keywords only affect discoverability in the host command palette —
     * they are matched case-insensitively against the palette query in addition
     * to title/description. Plugins using this overload should declare
     * `plugin.api-version=1.8`.
     *
     * @param keywords Search hints; blank entries are dropped by the host,
     *                 which may also cap the total count/length.
     */
    fun registerMenuAction(
        id: String,
        title: String,
        description: String,
        keywords: List<String>,
        handler: ActionHandler,
    )

    /**
     * Register a compact action shown in the host status / music bar area.
     */
    fun registerStatusBarAction(id: String, title: String, description: String = "", handler: ActionHandler)

    /**
     * Register a compact card on the Launch / home surface.
     */
    fun registerHomeCard(card: HomeCard)

    /**
     * Register a launch hook that runs before/after Minecraft launches.
     */
    fun registerLaunchHook(hook: LaunchHook)

    /**
     * Register a URL rewrite hook applied after the built-in mirror rewrite.
     * Requires [PluginPermission.NETWORK].
     */
    fun registerUrlRewriteHook(hook: UrlRewriteHook)

    /**
     * Register an event listener for PMCL events.
     */
    fun addEventListener(listener: EventListener)

    /**
     * Fire a custom event that other plugins can listen to.
     */
    fun fireEvent(event: PmclEvent)

    /**
     * Register a custom theme pack that users can select in Settings > Appearance.
     */
    fun registerThemePack(pack: ThemePack)
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
 * A composable content provider for plugin pages / settings sections.
 */
fun interface ComposableContent {
    @Composable
    fun invoke()
}
