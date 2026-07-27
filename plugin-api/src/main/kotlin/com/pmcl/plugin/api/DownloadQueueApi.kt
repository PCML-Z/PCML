package com.pmcl.plugin.api

/**
 * Download / install queue control.
 * Submitting installs requires [com.pmcl.plugin.PluginPermission.MANAGE_VERSIONS]
 * (and network is used by the host internally).
 */
interface DownloadQueueApi {
    fun summary(): QueueSummaryDto

    fun listTasks(): List<QueueTaskSummary>

    /** Enqueue a Minecraft version install; returns task id. */
    fun enqueueVersionInstall(versionId: String): String

    fun pause(taskId: String)

    fun resume(taskId: String)

    fun cancel(taskId: String)

    fun clearFinished()
}
