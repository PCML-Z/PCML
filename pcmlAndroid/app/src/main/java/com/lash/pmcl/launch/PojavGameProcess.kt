package com.lash.pmcl.launch

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.lash.pmcl.core.launch.GameProcess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * PojavLauncher 进程句柄。
 *
 * 由于通过 Intent 唤起 PojavLauncher 无法获得标准 java.lang.Process 对象，
 * 本类通过以下方式追踪游戏退出：
 *
 * 1. 进程监控：定期检查 PojavLauncher 进程是否仍在运行
 * 2. 强制终止：通过 killBackgroundProcesses 发送 SIGKILL
 */
class PojavGameProcess(
    private val context: Context,
    private val versionId: String,
    private val onLog: java.util.function.Consumer<String>?
) : GameProcess {

    companion object {
        private val POJAV_PKG = "net.kdt.pojavlaunch"
        private const val POLL_INTERVAL_MS = 2000L
    }

    private val exited = AtomicBoolean(false)
    private val exitCode = AtomicInteger(-1)
    private val latch = CountDownLatch(1)

    // 启动监控线程
    private val monitorThread: Thread = Thread({
        try {
            onLog?.accept("[PMCL] 等待 PojavLauncher 退出...")
            Thread.sleep(3000) // 先等 3 秒让 PojavLauncher 启动

            var consecutiveGone = 0
            while (!exited.get()) {
                val running = isPojavRunning()
                if (!running) {
                    consecutiveGone++
                    if (consecutiveGone >= 2) { // 连续 2 次检测不到才认为退出
                        onLog?.accept("[PMCL] PojavLauncher 已退出")
                        exitCode.set(0)
                        break
                    }
                } else {
                    consecutiveGone = 0
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            exited.set(true)
            latch.countDown()
        }
    }, "pmcl-pojav-monitor").apply { isDaemon = true }

    init {
        monitorThread.start()
    }

    /**
     * 检查 PojavLauncher 进程是否在运行。
     */
    private fun isPojavRunning(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val processes = am.runningAppProcesses ?: return false
            processes.any { it.processName.contains(POJAV_PKG) }
        } catch (e: Exception) {
            false
        }
    }

    override fun waitFor(): Int {
        latch.await()
        return exitCode.get()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Int {
        return if (latch.await(timeout, unit)) exitCode.get() else -1
    }

    override fun isAlive(): Boolean = !exited.get()

    override fun destroy() {
        onLog?.accept("[PMCL] 正在终止 PojavLauncher...")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses(POJAV_PKG)
        } catch (e: Exception) {
            onLog?.accept("[PMCL] 终止失败: ${e.message}")
        }
        exitCode.set(-1)
        exited.set(true)
        latch.countDown()
        monitorThread.interrupt()
    }

    override fun destroyForcibly() {
        destroy()
    }

    override fun exitCode(): Int = exitCode.get()
}
