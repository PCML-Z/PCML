package com.lash.pmcl.core.install

/**
 * 安装进度事件，UI 通过它显示进度条与状态文本。
 *
 * Android 版本：从 Java 移植，逻辑保持一致。
 */
class InstallProgress(
    val stage: Stage,
    val completed: Long,
    val total: Long,
    val message: String
) {

    enum class Stage {
        DOWNLOAD_VERSION_JSON,
        DOWNLOAD_CLIENT,
        DOWNLOAD_LIBRARIES,
        DOWNLOAD_ASSET_INDEX,
        DOWNLOAD_ASSETS,
        DONE,
        FAILED
    }

    fun percent(): Double {
        if (total <= 0) return 0.0
        return (completed * 100.0) / total
    }
}
