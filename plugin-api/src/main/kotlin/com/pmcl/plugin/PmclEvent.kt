package com.pmcl.plugin

/**
 * Base class for all PMCL events.
 *
 * Built-in event types:
 * - [VersionInstalledEvent]
 * - [GameLaunchedEvent]
 * - [GameExitedEvent]
 * - [ModInstalledEvent]
 * - [PluginLoadedEvent]
 * - [PluginEnabledEvent]
 * - [PluginDisabledEvent]
 * - [PluginErrorEvent]
 * - [RoomCreatedEvent]
 * - [RoomJoinedEvent]
 *
 * Plugins can also define custom events by subclassing [PmclEvent].
 */
abstract class PmclEvent {
    abstract val type: String
}

/** Fired when a Minecraft version is installed. */
class VersionInstalledEvent(val versionId: String) : PmclEvent() {
    override val type = "version_installed"
}

/** Fired when Minecraft is launched. */
class GameLaunchedEvent(val versionId: String, val accountName: String) : PmclEvent() {
    override val type = "game_launched"
}

/** Fired when Minecraft exits. */
class GameExitedEvent(val versionId: String, val exitCode: Int) : PmclEvent() {
    override val type = "game_exited"
}

/** Fired when a mod is installed. */
class ModInstalledEvent(val modName: String, val modVersion: String) : PmclEvent() {
    override val type = "mod_installed"
}

/** Fired when a plugin is loaded. */
class PluginLoadedEvent(val pluginId: String) : PmclEvent() {
    override val type = "plugin_loaded"
}

/** Fired when a plugin is enabled. */
class PluginEnabledEvent(val pluginId: String) : PmclEvent() {
    override val type = "plugin_enabled"
}

/** Fired when a plugin is disabled. */
class PluginDisabledEvent(val pluginId: String) : PmclEvent() {
    override val type = "plugin_disabled"
}

/** Fired when a plugin encounters an error during lifecycle or command execution. */
class PluginErrorEvent(val pluginId: String, val error: Throwable) : PmclEvent() {
    override val type = "plugin_error"
}

/** Fired when a multiplayer room is created. */
class RoomCreatedEvent(val roomCode: String, val virtualIp: String) : PmclEvent() {
    override val type = "room_created"
}

/** Fired when a multiplayer room is joined. */
class RoomJoinedEvent(val roomCode: String, val virtualIp: String) : PmclEvent() {
    override val type = "room_joined"
}

/** Fired when the selected account changes (identity only, no tokens). */
class AccountSelectedEvent(val accountUuid: String, val username: String) : PmclEvent() {
    override val type = "account_selected"
}

/** Fired when an instance is created. */
class InstanceCreatedEvent(val instanceId: String, val name: String) : PmclEvent() {
    override val type = "instance_created"
}

/** Fired when an instance is deleted. */
class InstanceDeletedEvent(val instanceId: String) : PmclEvent() {
    override val type = "instance_deleted"
}

/** Fired when a download requested via [com.pmcl.plugin.api.DownloadsApi] completes. */
class DownloadCompletedEvent(val url: String, val targetPath: String, val success: Boolean) : PmclEvent() {
    override val type = "download_completed"
}

/** Fired when the host navigates to a page (built-in or plugin). */
class NavigationEvent(val target: String) : PmclEvent() {
    override val type = "navigation"
}

/** Fired when a host preference changes via [com.pmcl.plugin.api.SettingsApi]. */
class SettingsChangedEvent(val key: String, val value: String) : PmclEvent() {
    override val type = "settings_changed"
}

/** Fired when an offline / managed account is added via AccountsApi. */
class AccountAddedEvent(val accountUuid: String, val username: String, val accountType: String) : PmclEvent() {
    override val type = "account_added"
}

/** Fired when an account is removed via AccountsApi. */
class AccountRemovedEvent(val accountUuid: String) : PmclEvent() {
    override val type = "account_removed"
}

/** Fired when a plugin rewrites a download URL. */
class UrlRewrittenEvent(val originalUrl: String, val rewrittenUrl: String, val pluginId: String) : PmclEvent() {
    override val type = "url_rewritten"
}

/** Fired when the active theme preset / pack changes. */
class ThemeChangedEvent(val themePreset: String, val customThemePackId: String) : PmclEvent() {
    override val type = "theme_changed"
}

/** Fired when music playback state changes (host bridge). */
class MusicStateChangedEvent(
    val state: String,
    val title: String,
    val volume: Int,
) : PmclEvent() {
    override val type = "music_state_changed"
}

/** Fired when a download-queue task changes status. */
class QueueTaskChangedEvent(
    val taskId: String,
    val name: String,
    val status: String,
    val progress: Double,
) : PmclEvent() {
    override val type = "queue_task_changed"
}

/** Fired when a plugin enables or disables a local mod. */
class ModToggledEvent(val fileName: String, val enabled: Boolean) : PmclEvent() {
    override val type = "mod_toggled"
}
