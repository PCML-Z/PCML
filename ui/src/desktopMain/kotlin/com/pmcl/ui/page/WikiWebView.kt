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
import javafx.scene.layout.BorderPane
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
            // JFXPanel 必须设 preferredSize，否则在 SwingPanel 首次布局时可能拿到 0 尺寸
            val jfxPanel = object : JFXPanel() {
                override fun getPreferredSize(): Dimension {
                    val p = super.getPreferredSize()
                    // 父容器未布局时给一个兜底尺寸，避免 0x0 导致 Scene 渲染空白
                    return if (p.width <= 0 || p.height <= 0) Dimension(800, 600) else p
                }
            }
            jfxPanel.layout = BorderLayout()

            Platform.runLater {
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
                    val loading = newState == Worker.State.RUNNING || newState == Worker.State.SCHEDULED
                    onLoadingChanged(loading)
                    if (newState == Worker.State.SUCCEEDED || newState == Worker.State.FAILED || newState == Worker.State.CANCELLED) {
                        val h = engine.history
                        val idx = h.currentIndex
                        onNavigationStateChanged(idx > 0, idx < h.entries.size - 1)
                    }
                }
                engine.history.currentIndexProperty().addListener { _, _, _ ->
                    val h = engine.history
                    val idx = h.currentIndex
                    onNavigationStateChanged(idx > 0, idx < h.entries.size - 1)
                }

                // BorderPane 强制 WebView 填满整个 Scene 区域
                jfxPanel.scene = Scene(BorderPane(webView))
                webViewRef.value = webView

                // 工厂里立即加载首个 URL（不等 update 回调）
                if (url.isNotBlank()) {
                    lastLoaded.set(url)
                    engine.load(url)
                }
            }
            jfxPanel
        },
        update = {
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
