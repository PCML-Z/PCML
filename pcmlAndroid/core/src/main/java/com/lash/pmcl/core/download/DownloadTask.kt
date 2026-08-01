package com.lash.pmcl.core.download

/**
 * 单个下载任务描述。
 */
data class DownloadTask(
    val url: String,
    val sha1: String?,
    val size: Long,
    val relativePath: String
)
