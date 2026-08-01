package com.lash.pmcl.core.launch

/**
 * 游戏进程句柄 — Android 平台抽象。
 *
 * 桌面版直接返回 [Process]（java.lang.Process），Android 上启动方式不同
 * （如通过 Intent 唤起 PojavLauncher），无法获得标准 Process 对象。
 *
 * 本接口封装进程生命周期操作，UI 层可通过它等待退出、获取退出码、强制终止。
 */
interface GameProcess {

    /**
     * 等待游戏退出。
     * @return 退出码（0=正常退出；非 0=异常退出）
     */
    @Throws(InterruptedException::class)
    fun waitFor(): Int

    /**
     * 等待游戏退出，带超时。
     * @param timeout 超时时长
     * @param unit 时间单位
     * @return 退出码；超时返回 -1
     */
    @Throws(InterruptedException::class)
    fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Int

    /** 游戏是否仍在运行 */
    fun isAlive(): Boolean

    /** 强制终止游戏进程 */
    fun destroy()

    /** 强制终止游戏进程（SIGKILL 等价） */
    fun destroyForcibly()

    /** 退出码；进程未结束时返回 -1 */
    fun exitCode(): Int
}
