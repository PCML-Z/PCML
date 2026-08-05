package com.pmcl.plugin.api

/**
 * 游戏进程监控 API。
 *
 * 通过此 API 查询当前运行中的 Minecraft 进程状态，
 * 包括 PID、版本 ID、运行时长和内存使用等。
 */
interface GameProcessApi {

    /**
     * 获取当前所有活跃的游戏进程摘要。
     *
     * @return 进程摘要列表（可能为空）
     */
    fun listProcesses(): List<GameProcessSummary>

    /**
     * 强制终止指定游戏进程。
     *
     * @param pid 进程 ID
     * @return 是否成功发送终止信号
     */
    fun killProcess(pid: Long): Boolean
}
