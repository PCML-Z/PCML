package com.pmcl.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pmcl.plugin.ComposableContent
import com.pmcl.plugin.JavaFxContent
import com.pmcl.plugin.JavaFxPageFactories
import com.pmcl.plugin.JavaFxPageFactory
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.paint.Color as FxColor
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 通用 JavaFX 嵌入页：把任意插件提供的 [JavaFxContent]（JavaFX 场景根）
 * 嵌入 PMCL 主窗口。这是 HMCL 嵌入（HmclEmbedder）的泛化——任何插件
 * 都能用 `PluginContext.registerJavaFxPage` 获得 JavaFX 嵌入能力。
 *
 * 嵌入链路：`JavaFX Parent → Scene → JFXPanel（JavaFX-Swing 桥）→
 * 不透明 JPanel 锚点 → Compose SwingPanel`。
 *
 * ## 关键细节（WikiWebView / HmclEmbedder 经验，缺一个就会白屏/卡死/泄漏）
 *
 * - 透明窗口下直接挂 JFXPanel 会白屏：外层必须用不透明 heavyweight JPanel
 *   包裹作合成锚点，并关闭 AWT 双缓冲（避免与 Prism 叠加渲染卡顿）
 * - JFXPanel 需 preferredSize 兜底，否则首次布局 0x0 白屏
 * - 插件代码在 FX 线程执行时临时设置 contextClassLoader 为插件类加载器，
 *   FXMLLoader / ServiceLoader / 反射才能找到插件类
 * - 场景按 [JavaFxContent] 实例缓存：页面离开仅解除挂载，再次进入直接复用
 *   （状态不丢失，createRoot 不重跑）
 * - 清理分线程：`panel.setScene(null)` 在 FX 线程执行；
 *   `JFXPanel.removeNotify` 必须在 FX 线程外（EDT）执行，否则可能死锁；均带超时兜底
 */
@Composable
fun JavaFxEmbeddedPage(content: JavaFxContent, modifier: Modifier = Modifier) {
    var error by remember(content) { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize()) {
        if (error != null) {
            // 构建失败：错误占位（不崩溃主窗口，与 SafePluginPage 行为一致）
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "JavaFX page failed to build",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            SwingPanel(
                background = Color.White,
                modifier = Modifier.fillMaxSize(),
                factory = {
                    // 不透明 heavyweight 锚点 + 关闭 AWT 双缓冲（透明窗口合成必需）
                    val wrapper = javax.swing.JPanel(BorderLayout())
                    wrapper.isOpaque = true
                    wrapper.background = java.awt.Color.WHITE
                    wrapper.isDoubleBuffered = false

                    val jfxPanel = object : JFXPanel() {
                        override fun getPreferredSize(): Dimension {
                            val p = super.getPreferredSize()
                            return if (p.width <= 0 || p.height <= 0) Dimension(1000, 700) else p
                        }
                    }
                    jfxPanel.isOpaque = true
                    jfxPanel.background = java.awt.Color.WHITE
                    wrapper.add(jfxPanel, BorderLayout.CENTER)

                    attachScene(content, jfxPanel) { e -> error = e }
                    wrapper
                }
            )
        }
    }

    DisposableEffect(content) {
        onDispose {
            // 仅解除挂载；Scene 保留在缓存中，再次进入时直接复用
            detachScene(content)
        }
    }
}

/**
 * 场景缓存：key 为插件注册的 [JavaFxContent] 实例（插件存续期间被
 * RegisteredPage 强引用；插件卸载后条目随弱引用回收）。
 * 一个 Scene 同一时刻只能挂在一个 JFXPanel 上；detach 后可重新 attach。
 */
private val sceneCache: MutableMap<JavaFxContent, Scene> =
    Collections.synchronizedMap(WeakHashMap<JavaFxContent, Scene>())

/** 当前挂载场景的面板（页面可见期间有效；detach 时取用） */
private val activePanels = ConcurrentHashMap<JavaFxContent, JFXPanel>()

/** 把（缓存的）场景挂到面板；首次进入时在 FX 线程调用 createRoot 构建。 */
private fun attachScene(content: JavaFxContent, panel: JFXPanel, onError: (String) -> Unit) {
    activePanels[content] = panel
    val cached = sceneCache[content]
    if (cached != null) {
        // 二次进入：直接复用（Scene 从上个 panel detach 后可重新挂载）
        Platform.runLater {
            try {
                panel.scene = cached
            } catch (e: Throwable) {
                e.printStackTrace()
                onError("Failed to re-attach cached JavaFX scene: ${e.message ?: e.toString()}")
            }
        }
        return
    }

    // 首次进入：FX 线程构建场景根并缓存
    Platform.runLater {
        try {
            val root = buildWithPluginClassloader(content)
            val scene = Scene(root, FxColor.WHITE)
            sceneCache[content] = scene
            panel.scene = scene
        } catch (e: Throwable) {
            e.printStackTrace()
            onError(e.message ?: e.toString())
        }
    }
}

/**
 * 在插件类加载器上下文中构建场景根：
 * FXMLLoader / ServiceLoader / Class.forName 走 contextClassLoader，
 * 不设置会解析到宿主加载器而抛 ClassNotFoundException（HMCL 嵌入的关键教训）。
 * 构建完成后恢复原 contextClassLoader，避免污染共享 FX 线程。
 */
private fun buildWithPluginClassloader(content: JavaFxContent): Parent {
    val fxThread = Thread.currentThread()
    val previous = fxThread.contextClassLoader
    return try {
        fxThread.contextClassLoader = content.javaClass.classLoader
        content.createRoot()
    } finally {
        fxThread.contextClassLoader = previous
    }
}

/** 页面离开：FX 线程解除 Scene 挂载；EDT 上 removeNotify 释放面板（带超时）。 */
private fun detachScene(content: JavaFxContent) {
    val panel = activePanels.remove(content) ?: return
    // FX 线程：Scene 从 JFXPanel 摘下（Scene 保留在缓存中供再次进入复用）
    try {
        Platform.runLater {
            try {
                panel.scene = null
            } catch (_: Throwable) {
            }
        }
    } catch (_: Throwable) {
        // JavaFX toolkit 已关闭等场景：忽略
        return
    }
    // removeNotify 必须在 FX 线程外执行；onDispose 在 EDT 上运行可直接调用
    if (javax.swing.SwingUtilities.isEventDispatchThread()) {
        try {
            panel.removeNotify()
        } catch (_: Throwable) {
        }
    } else {
        val done = CountDownLatch(1)
        try {
            javax.swing.SwingUtilities.invokeLater {
                try {
                    panel.removeNotify()
                } catch (_: Throwable) {
                } finally {
                    done.countDown()
                }
            }
            done.await(3, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }
}

/**
 * 把 [JavaFxEmbeddedPage] 注册为全局工厂，供 `:core` 的
 * PluginContextImpl.registerJavaFxPage 取用。
 *
 * 必须在插件系统启用任何 JavaFX 插件之前调用（`main()` 早期），
 * 与 [registerEmbeddedWebViewFactory] 同一注册点。
 */
fun registerJavaFxPageFactory() {
    JavaFxPageFactories.register(
        JavaFxPageFactory { content ->
            ComposableContent { JavaFxEmbeddedPage(content) }
        }
    )
}
