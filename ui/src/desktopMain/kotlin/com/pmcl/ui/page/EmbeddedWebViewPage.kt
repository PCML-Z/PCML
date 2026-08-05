package com.pmcl.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.pmcl.plugin.ComposableContent
import com.pmcl.plugin.WebViewPageFactories
import com.pmcl.plugin.WebViewPageFactory
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.BorderPane
import javafx.scene.paint.Color as FxColor
import javafx.scene.web.WebView
import java.awt.BorderLayout
import java.awt.Dimension

/**
 * 外部运行时插件（embed=web）的**内嵌**页面。
 *
 * 与 [WikiWebView] 同一套 `SwingPanel` + `JFXPanel` + JavaFX `WebView` 机制，
 * 区别是：固定加载一个本机地址、没有地址栏/前进后退等浏览器外壳，
 * 视觉上就是 PMCL 窗口里的一个普通页面——这正是「嵌入」而非「打开」。
 *
 * 关键细节（与 WikiWebView 一致，缺一个就会白屏或卡顿）：
 * - `JFXPanel` 必须给 preferredSize 兜底，否则首次布局拿到 0x0 直接白屏
 * - 外层用不透明的 heavyweight `JPanel` 包裹，给透明窗口一个合成锚点
 * - 关闭 AWT 双缓冲，避免与 Prism 渲染叠加造成滚动卡顿
 * - `Scene` 与 `BorderPane` 都要填不透明白底
 */
@Composable
fun EmbeddedWebViewPage(url: String, modifier: Modifier = Modifier) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    Box(modifier.fillMaxSize()) {
        SwingPanel(
            background = Color.White,
            modifier = Modifier.fillMaxSize(),
            factory = {
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

                Platform.runLater {
                    val webView = WebView()
                    webView.isContextMenuEnabled = false
                    webView.zoom = 1.0
                    val engine = webView.engine
                    engine.isJavaScriptEnabled = true

                    val root = BorderPane(webView)
                    root.background = Background(BackgroundFill(FxColor.WHITE, null, null))
                    jfxPanel.scene = Scene(root, FxColor.WHITE)
                    webViewRef.value = webView

                    if (url.isNotBlank()) engine.load(url)
                }
                wrapper
            },
            update = {
                // URL 由插件在注册时固定，运行期不变；此处仅在首次为空时补一次加载
                val w = webViewRef.value
                if (w != null && url.isNotBlank() && w.engine.location.isNullOrBlank()) {
                    Platform.runLater { w.engine.load(url) }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            Platform.runLater {
                try { webViewRef.value?.engine?.load(null) } catch (_: Throwable) {}
                webViewRef.value = null
            }
        }
    }
}

/**
 * 把 [EmbeddedWebViewPage] 注册为全局工厂，供 `:core` 的 ExternalRuntimeBridge 取用。
 *
 * 必须在插件系统启用任何 `embed=web` 插件之前调用（`main()` 早期）。
 * `:core` 不依赖 Compose/JavaFX，只能通过这个注册表拿到渲染实现。
 */
fun registerEmbeddedWebViewFactory() {
    WebViewPageFactories.register(
        WebViewPageFactory { url ->
            ComposableContent { EmbeddedWebViewPage(url) }
        }
    )
}
