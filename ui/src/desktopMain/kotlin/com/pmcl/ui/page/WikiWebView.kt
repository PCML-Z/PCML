package com.pmcl.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.BorderPane
import javafx.scene.paint.Color as FxColor
import javafx.scene.web.WebView
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicReference

/**
 * desktop 目标的 WikiWebView 实现：JavaFX WebView 通过 JFXPanel 嵌入 Compose SwingPanel。
 *
 * 前置条件：Main.kt 启动前设置 `javafx.macosx.embed=true`，让 Glass 以嵌入模式运行。
 *
 * 网页空白修复：
 * - JFXPanel 必须设 preferredSize（否则 SwingPanel 首次布局可能给 0 尺寸）
 * - JFXPanel 用 BorderLayout 容器包裹（强制填满）
 * - Scene 用 BorderPane 包裹 WebView（强制填满 Scene）
 * - 工厂里立即触发首次 load，不依赖 update 回调
 */
@Composable
actual fun WikiWebView(
    url: String,
    controller: WikiWebController,
    onUrlChanged: (String) -> Unit,
    onTitleChanged: (String) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onNavigationStateChanged: (canBack: Boolean, canForward: Boolean) -> Unit,
    modifier: Modifier
) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val lastLoaded = remember { AtomicReference("") }

    SwingPanel(
        background = Color.White,
        modifier = modifier,
        factory = {
            // 透明窗口下直接把 JFXPanel 放进 SwingPanel 会渲染空白：
            // JFXPanel 是 heavyweight 组件，但 Glass/Prism 在透明父窗口下没有不透明合成层。
            // 解决方案：用 heavyweight JPanel 包裹 JFXPanel，JPanel 设为不透明白色背景，
            // 给 AWT 一个不透明锚点，让 JFXPanel 的 NSView 能正确合成到透明窗口上。
            val wrapper = javax.swing.JPanel(BorderLayout())
            wrapper.isOpaque = true
            wrapper.background = java.awt.Color.WHITE
            // 滚动卡顿修复：禁用 AWT 双缓冲层。
            // JFXPanel 由 JavaFX Prism 直接渲染，AWT 双缓冲会叠加一层离屏合成，
            // 滚动时每帧都要经历 AWT 离屏 → JavaFX 上屏的两次拷贝，造成卡顿。
            wrapper.isDoubleBuffered = false

            // JFXPanel 必须设 preferredSize，否则在 SwingPanel 首次布局时可能拿到 0 尺寸
            val jfxPanel = object : JFXPanel() {
                override fun getPreferredSize(): Dimension {
                    val p = super.getPreferredSize()
                    // 父容器未布局时给一个兜底尺寸，避免 0x0 导致 Scene 渲染空白
                    return if (p.width <= 0 || p.height <= 0) Dimension(800, 600) else p
                }
            }
            jfxPanel.isOpaque = true
            jfxPanel.background = java.awt.Color.WHITE
            wrapper.add(jfxPanel, BorderLayout.CENTER)

            // 关键时序修复：必须在 EDT（factory 上下文）就标记 lastLoaded，
            // 否则 update 回调紧随 factory 执行时 lastLoaded 仍为空，
            // 会立即触发 load(url) 取消工厂里的 loadContent 测试内容。
            lastLoaded.set(url)

            Platform.runLater {
                // 关键修复：JavaFX WebView 的 WebKit 网络栈读取 JVM 系统属性 http/https.proxyHost。
                // LauncherCore.applyNetworkPreferences() 会把用户配置的代理（可能是失效的 127.0.0.1:12000）
                // 写入这些系统属性，导致 WebView 无法加载任何网页（minecraft.wiki 直连本可达）。
                // Wiki 浏览器走直连，不与下载器共享代理配置。
                System.clearProperty("http.proxyHost")
                System.clearProperty("http.proxyPort")
                System.clearProperty("https.proxyHost")
                System.clearProperty("https.proxyPort")

                val webView = WebView()
                webView.isContextMenuEnabled = true
                webView.zoom = 1.0
                val engine = webView.engine
                engine.isJavaScriptEnabled = true
                engine.userAgent =
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                    "(KHTML, like Gecko) Version/17.0 Safari/605.1.15"

                engine.locationProperty().addListener { _, _, new ->
                    if (new != null && new != lastLoaded.get()) {
                        lastLoaded.set(new)
                        onUrlChanged(new)
                    }
                }
                engine.titleProperty().addListener { _, _, new ->
                    if (new != null && new.isNotEmpty()) onTitleChanged(new)
                }
                engine.loadWorker.stateProperty().addListener { _, _, newState ->
                    onLoadingChanged(newState == Worker.State.RUNNING || newState == Worker.State.SCHEDULED)
                    if (newState == Worker.State.SUCCEEDED || newState == Worker.State.FAILED || newState == Worker.State.CANCELLED) {
                        val h = engine.history
                        onNavigationStateChanged(h.currentIndex > 0, h.currentIndex < h.entries.size - 1)
                    }
                }
                engine.history.currentIndexProperty().addListener { _, _, _ ->
                    val h = engine.history
                    val idx = h.currentIndex
                    onNavigationStateChanged(idx > 0, idx < h.entries.size - 1)
                }

                // BorderPane 强制 WebView 填满整个 Scene 区域
                // Scene fill 必须设为不透明白色，否则透明窗口下 Scene 背景透明，WebView 内容无处合成
                val root = BorderPane(webView)
                root.background = Background(BackgroundFill(FxColor.WHITE, null, null))
                jfxPanel.scene = Scene(root, FxColor.WHITE)
                webViewRef.value = webView

                // 工厂里立即加载首个 URL（不等 update 回调）
                if (url.isNotBlank()) {
                    lastLoaded.set(url)
                    engine.load(url)
                }
            }
            wrapper
        },
        update = {
            // factory 已在 EDT 设置 lastLoaded=url，首次 update 不会误触发
            if (url.isNotBlank() && url != lastLoaded.get()) {
                lastLoaded.set(url)
                Platform.runLater {
                    val w = webViewRef.value ?: return@runLater
                    w.engine.load(url)
                }
            }
        }
    )

    LaunchedEffect(controller) {
        controller.goBack = {
            Platform.runLater {
                val w = webViewRef.value ?: return@runLater
                val h = w.engine.history
                if (h.currentIndex > 0) h.go(-1)
            }
        }
        controller.goForward = {
            Platform.runLater {
                val w = webViewRef.value ?: return@runLater
                val h = w.engine.history
                if (h.currentIndex < h.entries.size - 1) h.go(1)
            }
        }
        controller.reload = {
            Platform.runLater {
                webViewRef.value?.engine?.reload()
            }
        }
    }
}
