package com.lash.pmcl.core.launch

import java.util.AbstractMap
import java.util.Locale

/**
 * 启动流程分阶段计时器：记录从用户点击启动到 MC 主菜单就绪的完整时间线。
 *
 * 设计目标：用数据驱动优化决策，避免凭直觉投入工程。
 * 启动器侧阶段由 VM/LaunchManager 显式 mark；
 * MC 进程内部阶段由 LaunchManager 从进程日志识别（LWJGL/OpenGL/音频引擎等里程碑）。
 * 进程退出时调用 [outputTo] 输出完整时间线。
 *
 * 线程安全：mark 可能从 VM 协程和 MC 日志读取线程并发调用，用 synchronized 保护。
 */
class LaunchTracer {

    private val startNanos: Long = System.nanoTime()
    private val phases: MutableList<MutableMap.MutableEntry<String, Long>> = ArrayList()

    /** 记录一个阶段里程碑。同名阶段只记第一次（用于 MC 日志标记去重）。 */
    @Synchronized
    fun mark(name: String) {
        for (e in phases) {
            if (e.key == name) return
        }
        phases.add(AbstractMap.SimpleEntry(name, System.nanoTime() - startNanos))
    }

    /** 输出完整时间线到 GameLogger（进程退出时调用）。 */
    @Synchronized
    fun outputTo(logger: GameLogger?) {
        if (logger == null || phases.isEmpty()) return
        val sb = StringBuilder()
        sb.append("[LaunchTracer] ===== 启动时间线（单位 ms）=====")
        var prev = 0L
        for (e in phases) {
            val t = e.value / 1_000_000
            val delta = t - prev
            sb.append(String.format(
                Locale.ROOT,
                "\n[LaunchTracer] %-26s +%6dms  (累计 %6dms)",
                e.key, delta, t
            ))
            prev = t
        }
        sb.append("\n[LaunchTracer] ====================================")
        logger.append(sb.toString())
    }

    /**
     * 从 MC 进程日志行识别内部阶段里程碑。
     * 每个标记只触发一次（由 mark 的去重逻辑保证）。
     */
    fun detectMcMilestone(line: String?) {
        if (line.isNullOrEmpty()) return
        val lower = line.lowercase(Locale.ROOT)
        // Fabric Loader 开始加载 MC
        if (lower.contains("loading for game minecraft")) {
            mark("mc_fabric_load")
        }
        // LWJGL 初始化（窗口/输入库加载）
        else if (lower.contains("lwjgl version") || lower.contains("lwjgl openal")) {
            mark("mc_lwjgl_init")
        }
        // OpenGL 上下文创建（窗口即将可见）
        else if (lower.contains("opengl:")) {
            mark("mc_gl_context")
        }
        // 音频引擎就绪（资源加载接近完成，接近主菜单）
        else if (lower.contains("sound engine started")) {
            mark("mc_sound_ready")
        }
        // 资源管理器重载完成（主菜单数据就绪）
        else if (lower.contains("reloading resourcemanager") && lower.contains("took")) {
            mark("mc_resources_done")
        }
    }
}
