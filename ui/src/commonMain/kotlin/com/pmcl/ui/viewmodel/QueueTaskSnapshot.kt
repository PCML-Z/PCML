package com.pmcl.ui.viewmodel

import com.pmcl.core.download.DownloadQueueManager

/**
 * 队列任务的不可变快照。界面只读这份数据，标题和卡片用同一次拷贝，进度条才会一起动。
 */
data class QueueTaskSnapshot(
    val id: String,
    val name: String,
    val type: DownloadQueueManager.TaskType,
    val status: DownloadQueueManager.TaskStatus,
    val completedBytes: Long,
    val totalBytes: Long,
    val message: String?,
    val errorMessage: String?
) {
    fun progress(): Double {
        if (totalBytes <= 0L) return 0.0
        return (completedBytes.toDouble() / totalBytes).coerceAtMost(1.0)
    }
}

fun DownloadQueueManager.QueueTask.toSnapshot(): QueueTaskSnapshot = QueueTaskSnapshot(
    id = id,
    name = name,
    type = type,
    status = status,
    completedBytes = completedBytes,
    totalBytes = totalBytes,
    message = message,
    errorMessage = errorMessage
)

fun summarizeSnapshots(views: List<QueueTaskSnapshot>): DownloadQueueManager.QueueSummary {
    var queued = 0
    var running = 0
    var paused = 0
    var done = 0
    var failed = 0
    var cancelled = 0
    var totalBytes = 0L
    var completedBytes = 0L
    for (task in views) {
        when (task.status) {
            DownloadQueueManager.TaskStatus.QUEUED -> queued++
            DownloadQueueManager.TaskStatus.RUNNING -> running++
            DownloadQueueManager.TaskStatus.PAUSED -> paused++
            DownloadQueueManager.TaskStatus.DONE -> done++
            DownloadQueueManager.TaskStatus.FAILED -> failed++
            DownloadQueueManager.TaskStatus.CANCELLED -> cancelled++
        }
        totalBytes += task.totalBytes
        completedBytes += task.completedBytes
    }
    return DownloadQueueManager.QueueSummary(
        queued, running, paused, done, failed, cancelled, totalBytes, completedBytes
    )
}
