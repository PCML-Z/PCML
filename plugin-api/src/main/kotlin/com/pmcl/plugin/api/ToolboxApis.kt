package com.pmcl.plugin.api

/**
 * 启动器里原来只有界面能做的操作。
 * 不返回账号令牌、服务器令牌或 JVM 参数。
 */

data class IntegritySummary(
    val versionId: String,
    val ok: Boolean,
    val missingCount: Int,
    val mismatchCount: Int,
    val okCount: Int,
    val unverifiableCount: Int,
    val missing: List<String>,
    val mismatch: List<String>,
    val error: String,
)

data class BackupSummary(
    val id: String,
    val createdAt: Long,
    val label: String,
    val files: Int,
    val storedBytes: Long,
    val pinned: Boolean,
    val note: String,
)

data class BackupChangeSummary(
    val scope: String,
    val path: String,
    val size: Long,
    val change: String,
)

data class BackupSettingsSummary(
    val schedule: String,
    val keep: Int,
    val worlds: Boolean,
    val mods: Boolean,
    val configs: Boolean,
    val resourcePacks: Boolean,
    val shaders: Boolean,
    val screenshots: Boolean,
    val skins: Boolean,
    val launcher: Boolean,
)

data class MigrationSourceSummary(
    val name: String,
    val gameRoot: String,
    val hasVersions: Boolean,
)

data class KernelSummary(
    val id: String,
    val name: String,
    val version: String,
    val runtime: String,
    val enabled: Boolean,
    val health: String,
    val detail: String,
)

data class SkinSummary(
    val id: String,
    val name: String,
    val player: String,
    val model: String,
    val addedAt: Long,
)

data class RecordingSummary(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val source: String,
)

data class PlaySlice(
    val key: String,
    val name: String,
    val durationMs: Long,
    val sessions: Int,
)

data class WeekdayPlay(
    val day: Int,
    val name: String,
    val durationMs: Long,
    val sessions: Int,
)

data class PlayRecords(
    val longestSessionMs: Long,
    val longestStreakDays: Int,
    val currentStreakDays: Int,
    val firstPlayDate: String,
    val mostPlayedHour: String,
    val totalDays: Long,
)

data class LaunchPresetSummary(
    val name: String,
    val minMemoryMb: Int,
    val maxMemoryMb: Int,
    val width: Int,
    val height: Int,
    val fullscreen: Boolean,
    val renderer: String,
)

interface PinsApi {
    fun pinned(): List<String>
    fun recent(): List<String>
    fun pin(versionId: String)
    fun unpin(versionId: String)
}

interface IntegrityApi {
    fun check(versionId: String): IntegritySummary
}

interface BackupsApi {
    fun listSnapshots(): List<BackupSummary>
    fun settings(): BackupSettingsSummary
    fun storedBytes(): Long
    fun changes(snapshotId: String): List<BackupChangeSummary>
    fun setNote(snapshotId: String, note: String)
    fun setPinned(snapshotId: String, pinned: Boolean)
    fun deleteSnapshot(snapshotId: String)
    /** 整份恢复，不删除快照之后新增的文件。 */
    fun restore(snapshotId: String): Int
}

interface MigrationApi {
    fun detect(): List<MigrationSourceSummary>
}

interface KernelsApi {
    fun list(): List<KernelSummary>
    fun setEnabled(id: String, enabled: Boolean)
    fun remove(id: String)
}

interface SkinsApi {
    fun list(): List<SkinSummary>
    /** png 必须在启动器目录里，不能是符号链接。 */
    fun importPng(path: String, name: String): SkinSummary
    fun delete(id: String)
}

interface RecordingsApi {
    fun list(): List<RecordingSummary>
    fun delete(name: String)
}

interface PlayApi {
    fun weekdays(recentDays: Int = 30): List<WeekdayPlay>
    fun records(): PlayRecords
    fun versions(recentDays: Int = 30): List<PlaySlice>
    fun worlds(): List<PlaySlice>
    fun servers(): List<PlaySlice>
    fun instances(): List<PlaySlice>
    fun mods(top: Int = 10): List<PlaySlice>
}

interface LoadersApi {
    fun names(): List<String>
    /** 开始安装后立即返回，不等到下载结束。 */
    fun install(loader: String, gameVersion: String, loaderVersion: String): String
}

interface WikiApi {
    fun search(query: String): String
    fun minecraft(query: String): String
    fun modrinth(slug: String): String
    fun curseForge(projectId: Int): String
}

interface PresetsApi {
    fun list(): List<LaunchPresetSummary>
    fun apply(name: String)
    fun delete(name: String)
}

interface VersionJavaApi {
    fun get(versionId: String): String
    /** 空路径表示清除。只接受启动器 java 或 runtimes 目录里的可执行文件。 */
    fun set(versionId: String, javaPath: String)
    fun customVersions(): List<String>
}
