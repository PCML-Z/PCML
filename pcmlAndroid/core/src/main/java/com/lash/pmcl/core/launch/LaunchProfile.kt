package com.lash.pmcl.core.launch

import com.lash.pmcl.core.auth.Account
import com.lash.pmcl.core.paths.PmclPaths
import java.nio.file.Path

/**
 * 启动参数构造 — Android 版。
 *
 * 与桌面版的差异：
 * - 移除 LauncherConfig 依赖，改用 [PmclPaths]
 * - 移除 Windows 长命令行 argfile 优化（Android 不存在 CreateProcess 32K 限制）
 * - 移除 Java Agent 支持（authlib-injector / RetroWrapper 为桌面专属，Android 用 PojavLauncher 的
 *   内置 authlib-injector 支持）
 * - 移除插件 LaunchHook 贡献接口（Android MVP 暂不支持插件）
 * - 保留 classpath / JVM 参数 / 游戏参数 / 环境变量 / mainClass / gameDir
 *
 * buildCommand() 返回完整的命令行参数列表，供 [GameLauncher] 实现使用。
 * Android 上实际的"启动"由 [GameLauncher] 接口实现决定（如通过 Intent 唤起 PojavLauncher）。
 */
class LaunchProfile(
    private val paths: PmclPaths,
    val account: Account?,
    val versionId: String
) {

    var mainClass: String = "net.minecraft.client.main.Main"
        private set

    private val classpath: MutableList<String> = ArrayList()
    private val jvmArgs: MutableList<String> = ArrayList()
    private val gameArgs: MutableList<String> = ArrayList()
    private val env: MutableMap<String, String> = LinkedHashMap()

    /** 实际 Minecraft 根目录（默认为 paths.minecraftWorkDir） */
    var gameDir: Path = paths.minecraftWorkDir
        private set

    fun setMainClass(mainClass: String): LaunchProfile {
        this.mainClass = mainClass
        return this
    }

    fun setGameDir(gameDir: Path): LaunchProfile {
        this.gameDir = gameDir
        return this
    }

    val playerName: String? get() = account?.username

    fun addClasspath(p: Path): LaunchProfile {
        classpath.add(p.toString())
        return this
    }

    fun addClasspath(p: String): LaunchProfile {
        classpath.add(p)
        return this
    }

    fun addJvmArg(arg: String): LaunchProfile {
        jvmArgs.add(arg)
        return this
    }

    fun addGameArg(arg: String): LaunchProfile {
        gameArgs.add(arg)
        return this
    }

    fun putEnv(key: String, value: String): LaunchProfile {
        if (key.isNotBlank()) {
            env[key.trim()] = value
        }
        return this
    }

    fun getEnv(): Map<String, String> = LinkedHashMap(env)

    /** 只读视图，供 builder 在解析 tweakerClass 时检查 */
    val gameArgsView: List<String> get() = ArrayList(gameArgs)

    /** 在游戏参数列表头部插入一项（用于注入 --tweakClass） */
    fun prependGameArg(arg: String): LaunchProfile {
        if (arg.isNotEmpty()) gameArgs.add(0, arg)
        return this
    }

    /** 可变 classpath 列表（供 builder 追加 client.jar / libraries） */
    internal fun classpathMutable(): MutableList<String> = classpath

    /** 可变 JVM 参数列表（供 builder 做占位符替换） */
    internal fun jvmArgsMutable(): MutableList<String> = jvmArgs

    /** 可变游戏参数列表（供 builder 做占位符替换） */
    internal fun gameArgsMutable(): MutableList<String> = gameArgs

    /**
     * 构建完整命令行参数列表。
     *
     * 注意：Android 上不通过 ProcessBuilder 启动 JVM 进程，此方法返回的列表
     * 供 [GameLauncher] 实现解析后传递给实际的启动器（如 PojavLauncher）。
     *
     * @param javaExecutable java 可执行文件路径（Android 上通常为 runtime 路径）
     */
    fun buildCommand(javaExecutable: String): List<String> {
        val cmd = ArrayList<String>()
        cmd.add(javaExecutable)

        // IgnoreUnrecognizedVMOptions 必须在所有 -XX 参数之前
        cmd.add("-XX:+IgnoreUnrecognizedVMOptions")
        cmd.addAll(jvmArgs)

        // classpath（Android 上用冒号分隔，与 Linux 一致）
        if (classpath.isNotEmpty()) {
            val cp = classpath.joinToString(":")
            cmd.add("-cp")
            cmd.add(cp)
        }

        cmd.add(mainClass)
        cmd.addAll(gameArgs)
        return cmd
    }
}
