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
