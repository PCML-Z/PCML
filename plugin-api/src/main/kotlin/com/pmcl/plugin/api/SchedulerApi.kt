package com.pmcl.plugin.api

/**
 * Host-managed background scheduling for plugins.
 * Tasks are cancelled automatically when the plugin is disabled.
 */
interface SchedulerApi {
    /**
     * Run [task] once after [delayMs] milliseconds.
     * @return task id for [cancel]
     */
    fun scheduleOnce(delayMs: Long, task: Runnable): String

    /**
     * Run [task] repeatedly every [periodMs] after an initial [delayMs].
     * @return task id for [cancel]
     */
    fun scheduleRepeating(delayMs: Long, periodMs: Long, task: Runnable): String

    /** Cancel a previously scheduled task. No-op if already finished / unknown. */
    fun cancel(taskId: String)

    /** Cancel all tasks owned by this plugin. */
    fun cancelAll()
}
