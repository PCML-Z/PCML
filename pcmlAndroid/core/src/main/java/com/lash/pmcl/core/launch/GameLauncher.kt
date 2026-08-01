package com.lash.pmcl.core.launch

/**
 * 游戏启动器接口 — Android 平台抽象。
 *
 * 桌面版 PMCL 通过 ProcessBuilder 直接 fork JVM 进程启动 Minecraft。
 * Android 上无法直接 fork JVM（Android Runtime 不是标准 JVM），需要通过以下方式之一：
 *
 * 1. **PojavLauncher Intent**：通过 Intent 唤起已安装的 PojavLauncher，
 *    传递启动参数（version / account / gameDir 等），由 PojavLauncher 完成 JVM 启动。
 *
 * 2. **内嵌 JNI 桥接**：将 PojavLauncher 的 JNI 运行时作为库嵌入（复杂，需 NDK 编译）。
 *
 * 3. **远程启动**：通过 ADB 或网络协议在另一台设备上启动（开发调试用）。
 *
 * 本接口封装启动方式差异，[LaunchManager] 只负责构造 [LaunchProfile]，
 * 实际启动委托给 [GameLauncher] 实现。UI 层在初始化时注入具体实现。
 */
interface GameLauncher {

    /**
     * 启动 Minecraft 游戏。
     *
     * @param profile 已构造完成的启动配置（classpath / JVM args / game args / account 等）
     * @param javaExecutable Java 可执行文件路径（Android 上可能为 runtime 目录或占位符）
     * @param onLog 日志回调（每行输出调用一次）
     * @return 启动句柄，可用于等待退出或强制终止；失败抛 IOException
     */
    @Throws(java.io.IOException::class)
    fun launch(
        profile: LaunchProfile,
        javaExecutable: String,
        onLog: java.util.function.Consumer<String>?
    ): GameProcess

    /**
     * 检查启动器是否可用（如 PojavLauncher 是否已安装）。
     * @return 可用性状态描述，null 表示可用；非 null 为不可用原因
     */
    fun checkAvailability(): String?
}
