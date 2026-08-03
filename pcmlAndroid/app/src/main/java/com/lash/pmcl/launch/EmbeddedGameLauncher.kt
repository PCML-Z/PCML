package com.lash.pmcl.launch

import android.content.Context
import com.lash.pmcl.core.launch.GameLauncher
import com.lash.pmcl.core.launch.GameProcess
import com.lash.pmcl.core.launch.LaunchProfile
import com.oracle.dalvik.VMLauncher
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内嵌 Amethyst 运行时启动器。
 *
 * 通过 JNI 直接调用 libpojavexec.so → VMLauncher.launchJVM(String[])
 * 不依赖外部 Amethyst/PojavLauncher App。
 */
class EmbeddedGameLauncher(private val context: Context,
                            private val appDataDir: File) : GameLauncher {

    private val appDataPath: String = appDataDir.absolutePath

    companion object {
        fun defaultJrePath(appDir: File): File {
            for (ver in listOf(21, 17, 8)) {
                val d = File(appDir, "pmcl/runtimes/jre$ver")
                if (d.isDirectory && File(d, "release").exists()) return d
            }
            return File(appDir, "pmcl/runtimes/jre21")
        }
    }

    init {
        // Native 库延迟到 launch() 时加载，避免启动时 native crash
    }

    override fun checkAvailability(): String? = try {
        if (VMLauncher.isNativeAvailable()) null else "Native 库无法加载"
    } catch (e: Exception) { "运行时异常: ${e.message}" }

    /**
     * 确保 JRE 已安装。首次启动时自动从 APK assets 解压。
     */
    fun ensureJreInstalled(onProgress: (Int, String) -> Unit = { _, _ -> }) {
        if (!JreInstaller.isInstalled(context)) {
            onProgress(0, "正在安装 Java 运行时...")
            JreInstaller.install(context) { p, msg -> onProgress(p, msg) }
            onProgress(100, "Java 运行时就绪")
        }
    }

    @Throws(IOException::class)
    override fun launch(profile: LaunchProfile, javaExecutable: String,
                         onLog: java.util.function.Consumer<String>?): GameProcess {
        // 自动安装 JRE
        if (!JreInstaller.isInstalled(context)) {
            onLog?.accept("[PMCL] 首次启动，正在安装 Java 运行时...")
            JreInstaller.install(context) { p, msg ->
                if (p % 25 == 0) onLog?.accept("[PMCL] JRE 安装: $p% - $msg")
            }
            onLog?.accept("[PMCL] JRE 安装完成")
        }

        val jreDir = JreInstaller.installDir(context)
        if (!jreDir.isDirectory)
            throw IOException("JRE 安装失败。请检查存储空间是否充足。")

        val cmd = profile.buildCommand(javaExecutable)
        onLog?.accept("[PMCL] ── 内嵌 Amethyst 运行时启动 ──")
        onLog?.accept("[PMCL] JRE : ${jreDir.absolutePath}")

        // 构造 args: [jrePath, --jvm-arg1, ..., -cp, classpath, mainClass, --game-arg1, ...]
        val args = buildLaunchArgs(jreDir.absolutePath, cmd)

        onLog?.accept("[PMCL] 主类: ${profile.mainClass}")
        onLog?.accept("[PMCL] 参数数: ${args.size}")

        val proc = EmbeddedGameProcess(args, onLog)
        proc.start()
        return proc
    }

    private fun buildLaunchArgs(jrePath: String, cmd: List<String>): Array<String> {
        val result = mutableListOf<String>()
        // 第一个参数：JRE 路径
        result.add(jrePath)
        // 后续：完整命令行（跳过 java 可执行文件路径，即 cmd[0]）
        for (i in 1 until cmd.size) {
            val arg = cmd[i]
            if (arg == "-cp" || arg == "-classpath") {
                result.add(arg)
                if (i + 1 < cmd.size) result.add(cmd[i + 1])
                // -cp 已经由 skipNext 机制处理，但这里直接追加
            } else if (i > 1 && (cmd[i - 1] == "-cp" || cmd[i - 1] == "-classpath")) {
                continue // classpath 值已在上一轮追加
            } else {
                result.add(arg)
            }
        }
        return result.toTypedArray()
    }
}

class EmbeddedGameProcess(
    private val args: Array<String>,
    private val onLog: java.util.function.Consumer<String>?
) : GameProcess {
    private val alive = AtomicBoolean(false)
    private val exitCode = AtomicInteger(-1)
    private val latch = CountDownLatch(1)

    fun start() {
        alive.set(true)
        Thread({
            try {
                onLog?.accept("[PMCL] ▸ 正在启动 JVM...")
                val result = VMLauncher.launchJVM(args)
                exitCode.set(result)
                onLog?.accept("[PMCL] ✓ JVM 退出 (code=$result)")
            } catch (e: Exception) {
                onLog?.accept("[PMCL] ✗ JVM 异常: ${e.message}")
                exitCode.set(-1)
            } finally { alive.set(false); latch.countDown() }
        }, "pmcl-jvm").apply { isDaemon = true; start() }
    }

    override fun waitFor(): Int { latch.await(); return exitCode.get() }
    override fun waitFor(t: Long, u: TimeUnit): Int = if (latch.await(t, u)) exitCode.get() else -1
    override fun isAlive(): Boolean = alive.get()
    override fun destroy() { alive.set(false); latch.countDown() }
    override fun destroyForcibly() = destroy()
    override fun exitCode(): Int = exitCode.get()
}
