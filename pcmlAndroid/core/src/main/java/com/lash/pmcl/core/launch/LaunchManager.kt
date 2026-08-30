package com.lash.pmcl.core.launch

import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.preferences.Preferences
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * 启动管理 — Android 版。
 *
 * 与桌面版的差异：
 * - 移除 ProcessBuilder 直接 fork JVM（Android Runtime 不是标准 JVM）
 * - 引入 [GameLauncher] 接口，实际启动方式由 UI 层注入
 *   （如 PojavLauncher Intent / 内嵌 JNI 桥接）
 * - 移除插件 beforeLaunch / applyLaunchContributions（Android MVP 暂不支持插件）
 * - 移除设备绑定保护 DeviceBinder（桌面专属，Android MVP 暂不迁移）
 * - 保留启动前门禁校验、异步启动 + 日志回调、活跃进程追踪
 */
class LaunchManager(
    private val paths: PmclPaths,
    private val preferences: Preferences?
) {

    companion object {
        /** 启动被取消时的伪退出码，UI 不应当作游戏崩溃 */
        const val EXIT_CANCELLED = -100
    }

    private val profileBuilder = LaunchProfileBuilder(paths, preferences)

    /** UI 层注入的游戏启动器实现 */
    @Volatile
    var gameLauncher: GameLauncher? = null

    /** 构造启动配置（委托给 LaunchProfileBuilder） */
    @Throws(IOException::class)
    fun buildProfile(versionId: String, account: com.lash.pmcl.core.auth.Account?): LaunchProfile =
        profileBuilder.build(versionId, account)

    fun resolveGameDir(versionId: String): java.nio.file.Path =
        GameDirResolver(paths, preferences).resolveGameDir(versionId)

    fun workDir(): java.nio.file.Path = paths.root

    /** 活跃游戏进程集合（应用退出时强制清理） */
    private val activeProcesses: MutableSet<GameProcess> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** 专用线程池：避免 waitFor() 长时间占用 ForkJoinPool.commonPool */
    private val launchExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "pmcl-launch").apply { isDaemon = true }
    }

    /**
     * 启动前门禁校验。
     *
     * @return null 表示允许启动；非 null 为拒绝原因（可对用户展示）
     */
    fun verifyBeforeLaunch(profile: LaunchProfile?): String? {
        if (profile == null) {
            return "[PMCL] 启动配置为空，已取消"
        }
        val launcher = gameLauncher
        if (launcher == null) {
            return "[PMCL] 未配置游戏启动器（GameLauncher），无法启动"
        }
        val unavailable = launcher.checkAvailability()
        if (unavailable != null) {
            return "[PMCL] 启动器不可用: $unavailable"
        }
        return null
    }

    /**
     * 异步启动 Minecraft。
     *
     * @param profile         启动配置
     * @param javaExecutable  Java 可执行文件路径
     * @param onLog           日志回调
     * @return CompletableFuture<GameProcess>，失败时 completeExceptionally
     */
    fun launchAsync(
        profile: LaunchProfile?,
        javaExecutable: String,
        onLog: Consumer<String>?
    ): CompletableFuture<GameProcess> {
        val launcher = gameLauncher
        val deny = verifyBeforeLaunch(profile)
        if (deny != null) {
            val future = CompletableFuture<GameProcess>()
            future.completeExceptionally(IOException(deny))
            return future
        }
        val launcherNonNull = launcher!!
        return CompletableFuture.supplyAsync({
            try {
                val proc = launcherNonNull.launch(profile!!, javaExecutable, onLog)
                synchronized(activeProcesses) { activeProcesses.add(proc) }
                // 异步等待退出，退出后从活跃集合移除
                launchExecutor.execute {
                    try {
                        proc.waitFor()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        synchronized(activeProcesses) { activeProcesses.remove(proc) }
                    }
                }
                proc
            } catch (e: IOException) {
                throw RuntimeException("启动失败: ${e.message}", e)
            }
        }, launchExecutor)
    }

    /**
     * 同步启动 Minecraft。
     */
    @Throws(IOException::class)
    fun launch(
        profile: LaunchProfile?,
        javaExecutable: String,
        onLog: Consumer<String>?
    ): GameProcess {
        val launcher = gameLauncher
        val deny = verifyBeforeLaunch(profile)
        if (deny != null) throw IOException(deny)
        val launcherNonNull = launcher ?: throw IOException("未配置 GameLauncher")
        val proc = launcherNonNull.launch(profile!!, javaExecutable, onLog)
        synchronized(activeProcesses) { activeProcesses.add(proc) }
        // 后台线程等待进程退出后清理
        launchExecutor.execute {
            try { proc.waitFor() } catch (_: InterruptedException) {}
            synchronized(activeProcesses) { activeProcesses.remove(proc) }
        }
        return proc
    }

    /**
     * 强制终止所有活跃游戏进程（应用退出时调用）。
     */
    fun shutdownAll() {
        synchronized(activeProcesses) {
            for (proc in activeProcesses) {
                try { proc.destroyForcibly() } catch (_: Throwable) {}
            }
            activeProcesses.clear()
        }
        launchExecutor.shutdownNow()
        try {
            if (!launchExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                System.err.println("[LaunchManager] 线程池未能在 3s 内退出")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
