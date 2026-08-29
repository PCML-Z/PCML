package com.pmcl.mario

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.pmcl.plugin.ComposableContent
import com.pmcl.plugin.PmclPlugin
import com.pmcl.plugin.PluginContext

/**
 * PMCL 插件：在主窗口内嵌一个纯 Swing 渲染的《超级马力欧兄弟》页面。
 *
 * 注册内容：
 * - 页面 `plugin:mario:mario`（侧边栏「超级马里奥」），用 SwingPanel 嵌入 [MarioPanel]
 * - 命令面板动作、终端命令 `plugin:mario:mario-score` 查看最高分
 *
 * 最高分通过 [PluginContext.setConfig] 持久化到 plugins.json。
 */
class MarioPlugin : PmclPlugin {

    override val pluginId = "mario"

    @Volatile
    private var panel: MarioPanel? = null

    override fun onEnable(ctx: PluginContext) {
        DiagLog.log("onEnable enter  thread=${Thread.currentThread().name}  t=${System.currentTimeMillis()}")
        try {
            Sfx.init(ctx.threadFactory())
            DiagLog.log("Sfx.init OK")

            val best = ctx.getConfig(KEY_BEST)?.toIntOrNull() ?: 0
            val marioPanel = MarioPanel(ctx, best) { value ->
                runCatching { ctx.setConfig(KEY_BEST, value.toString()) }
            }
            panel = marioPanel
            DiagLog.log("MarioPanel constructed OK")

            // 用纯 Swing 面板 + SwingPanel 嵌入（避免 macOS 上 JavaFX 场景图嵌入白屏）。
            ctx.registerPage(
                "mario",
                "超级马里奥",
                ComposableContent {
                    SwingPanel(
                        factory = { marioPanel },
                        modifier = Modifier.fillMaxSize(),
                        background = Color(0x14, 0x16, 0x1C),
                    )
                },
            )
            DiagLog.log("registerPage OK")

            ctx.registerMenuAction(
                id = "mario-open",
                title = "打开超级马里奥",
                description = "跳到侧边栏的马里奥插件页面",
                keywords = listOf("mario", "马里奥", "game", "游戏", "super"),
            ) {
                ctx.ui().navigate("plugin:mario:mario")
            }

            ctx.registerCommand("score", "Show Mario high score") { _ ->
                "Mario best score: ${ctx.getConfig(KEY_BEST) ?: "0"}"
            }

            ctx.info("[mario] 插件已启用，最高分 $best")
            DiagLog.log("onEnable done")
        } catch (e: Throwable) {
            DiagLog.log("onEnable FAILED: ${e}")
            e.printStackTrace()
        }
    }

    override fun onDisable() {
        DiagLog.log("onDisable")
        panel?.dispose()
        panel = null
        Sfx.shutdown()
    }

    private companion object {
        const val KEY_BEST = "highScore"
    }
}

/** 诊断日志：直接写文件 ~/.pmcl/plugins/mario-diag.log，绕开 plugins.json 与 setConfig 的缓存。 */
internal object DiagLog {
    private val logFile: java.nio.file.Path =
        java.nio.file.Paths.get(System.getProperty("user.home"), ".pmcl", "plugins", "mario-diag.log")

    @Synchronized
    fun log(msg: String) {
        try {
            val line = "${java.time.LocalTime.now()} $msg\n"
            java.nio.file.Files.writeString(
                logFile, line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        } catch (e: Throwable) {
            System.err.println("[MarioDiag] log write failed: $e")
        }
    }
}
