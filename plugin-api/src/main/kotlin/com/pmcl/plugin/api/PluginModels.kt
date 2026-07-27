package com.pmcl.plugin.api

/**
 * Stable DTOs exposed to plugins. These never reference `com.pmcl.core.*`.
 */

data class VersionSummary(
    val id: String,
    val launchable: Boolean,
    val inheritsFrom: String?,
    val lastModified: Long,
)

data class InstanceSummary(
    val id: String,
    val name: String,
    val baseVersionId: String,
    val loader: String?,
    val loaderVersion: String?,
    val type: String,
    val description: String?,
)

data class AccountSummary(
    val uuid: String,
    val username: String,
    val type: String,
    val selected: Boolean,
)

data class ModSummary(
    val modId: String,
    val name: String,
    val version: String,
    val loader: String,
    val fileName: String,
    val disabled: Boolean,
)

data class NewsSummary(
    val title: String,
    val link: String,
    val description: String,
    val pubDate: String,
    val category: String,
    val imageUrl: String,
)

data class ModpackSummary(
    val name: String,
    val gameVersion: String,
    val loader: String?,
    val loaderVersion: String?,
    val instanceDir: String,
    val modCount: Long,
    val source: String,
)

enum class NotificationLevel {
    INFO,
    WARN,
    ERROR,
    SUCCESS,
}

data class PluginNotification(
    val pluginId: String,
    val title: String,
    val message: String,
    val level: NotificationLevel = NotificationLevel.INFO,
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class DialogKind {
    INFO,
    CONFIRM,
}

/**
 * Host-mediated dialog request. Plugins call [UiApi.showDialog]; the host UI
 * presents it and optionally invokes [onResult] with true/false.
 */
data class PluginDialogRequest(
    val id: String,
    val pluginId: String,
    val title: String,
    val message: String,
    val kind: DialogKind = DialogKind.INFO,
    val confirmLabel: String = "OK",
    val cancelLabel: String = "Cancel",
    @Transient val onResult: BooleanCallback? = null,
)

/**
 * Lightweight menu / command-palette style action contributed by a plugin.
 */
data class PluginMenuAction(
    val pluginId: String,
    val id: String,
    val title: String,
    val description: String = "",
    @Transient val handler: ActionHandler? = null,
)

fun interface ActionHandler {
    fun run()
}

data class WorldSummary(
    val name: String,
    val dir: String,
    val displayName: String,
    val gameType: Int,
    val difficulty: Int,
    val hardcore: Boolean,
    val lastPlayed: Long,
    val source: String,
)

data class PackSummary(
    val name: String,
    val path: String,
    val description: String,
    val disabled: Boolean,
    val source: String = "",
    val active: Boolean = false,
)

data class ScreenshotSummary(
    val name: String,
    val path: String,
    val lastModified: Long,
    val source: String = "",
)

data class MarketProjectSummary(
    val source: String,
    val id: String,
    val slug: String,
    val name: String,
    val summary: String,
    val author: String,
    val downloadCount: Long,
    val iconUrl: String,
    val websiteUrl: String,
)

data class MarketFileSummary(
    val source: String,
    val projectId: String,
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val downloadUrl: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val releaseType: String,
)

data class OverallStatsSummary(
    val totalDurationMs: Long,
    val totalSessions: Int,
    val versions: List<VersionStatSummary>,
)

data class VersionStatSummary(
    val version: String,
    val totalDurationMs: Long,
    val sessionCount: Int,
    val lastPlayedMs: Long,
)

data class SessionSummary(
    val version: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val instanceId: String,
    val server: String,
    val worldName: String,
)

enum class FilePickerMode {
    OPEN_FILE,
    SAVE_FILE,
    OPEN_FOLDER,
}

/**
 * Host-mediated file / folder picker request.
 */
data class PluginFilePickerRequest(
    val id: String,
    val pluginId: String,
    val title: String,
    val mode: FilePickerMode = FilePickerMode.OPEN_FILE,
    /** Optional semicolon-separated extensions, e.g. `jar;zip`. */
    val filters: String = "",
    @Transient val onResult: PathCallback? = null,
)

/**
 * Host-mediated progress indicator.
 * [progress] in `0.0..1.0`, or negative for indeterminate.
 */
data class PluginProgressUpdate(
    val id: String,
    val pluginId: String,
    val title: String,
    val progress: Double,
    val dismiss: Boolean = false,
)

fun interface PathCallback {
    fun call(path: String?)
}

data class RoomStateSummary(
    val state: String,
    val backend: String,
    val inRoom: Boolean,
    val busy: Boolean,
    val virtualIp: String,
    val networkName: String,
    val roomCode: String,
    val invitation: String,
    val lastError: String,
)

data class JavaRuntimeSummary(
    val type: String,
    val name: String,
    val version: String,
    val sizeBytes: Long,
    val sha1: String,
)

data class HostMetricsSummary(
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val recommendedMaxMemoryMb: Long,
    val cpuLogicalCores: Int,
    val cpuPhysicalCores: Int,
    val cpuName: String,
    val osName: String,
    val primaryGpuName: String,
)

data class QueueTaskSummary(
    val id: String,
    val name: String,
    val type: String,
    val status: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val message: String,
    val errorMessage: String,
    val progress: Double,
)

data class QueueSummaryDto(
    val queued: Int,
    val running: Int,
    val paused: Int,
    val done: Int,
    val failed: Int,
    val cancelled: Int,
    val totalBytes: Long,
    val completedBytes: Long,
)

data class ServerSummary(
    val name: String,
    val host: String,
    val port: Int,
)

data class ServerPingResult(
    val online: Boolean,
    val latencyMs: Long,
    val motd: String,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val versionName: String,
    val protocolVersion: Int,
    val error: String,
)

data class CrashReportSummary(
    val filePath: String,
    val causes: List<String>,
    val suggestions: List<String>,
    val recoveryActions: List<CrashRecoverySummary>,
    val contentPreview: String,
)

data class CrashRecoverySummary(
    val type: String,
    val title: String,
    val description: String,
)

data class MusicPlaybackSummary(
    val state: String,
    val title: String,
    val index: Int,
    val currentMs: Long,
    val durationMs: Long,
    val volume: Int,
)

/**
 * Status-bar action contributed by a plugin (shown near the music mini bar).
 */
data class PluginStatusBarAction(
    val pluginId: String,
    val id: String,
    val title: String,
    val description: String = "",
    @Transient val handler: ActionHandler? = null,
)

data class RemoteVersionSummary(
    val id: String,
    val type: String,
    val releaseTime: String,
    val url: String,
)

data class HttpResponseSummary(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
    val finalUrl: String,
)

data class PluginInfoSummary(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val enabled: Boolean,
    val state: String,
    val permissions: List<String>,
)

/**
 * Host-mediated text input dialog.
 * [onResult] receives the entered text, or null when cancelled.
 */
data class PluginInputDialogRequest(
    val id: String,
    val pluginId: String,
    val title: String,
    val message: String,
    val defaultValue: String = "",
    val confirmLabel: String = "OK",
    val cancelLabel: String = "Cancel",
    @Transient val onResult: PathCallback? = null,
)

/** Sidebar badge text for a built-in or plugin page route. */
data class NavBadge(
    val target: String,
    val text: String,
)

data class GameProcessSummary(
    val versionId: String,
    val pid: Long,
    val running: Boolean,
)
