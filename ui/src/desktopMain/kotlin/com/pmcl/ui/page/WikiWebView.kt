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
import javafx.scene.web.WebView
import java.util.concurrent.atomic.AtomicReference

/**
 * desktop 目标的 WikiWebView 实现：JavaFX WebView 通过 JFXPanel 嵌入 Compose SwingPanel。
 *
 * 线程模型：
 * - SwingPanel.factory / update 在 EDT 调用。
 * - JavaFX WebView 必须在 JavaFX Application Thread 操作（Platform.runLater）。
 * - WebView 的回调（location/title/worker）在 JavaFX 线程触发，更新 Compose state 时
 *   Compose snapshot 系统线程安全，可直接 set。
 *
 * 防回环：地址栏 → load → location 回调 → 地址栏 的循环用 [lastLoaded] 原子引用阻断。
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
    // 记录最近一次主动 load 的 url，避免 location 回调 → 地址栏更新 → 再 load 的回环
    val lastLoaded = remember { AtomicReference("") }

    SwingPanel(
        background = Color.White,
        modifier = modifier,
        factory = {
            val jfxPanel = JFXPanel()
            // JFXPanel 构造可在 EDT 完成；Scene 必须在 FX 线程设置
            Platform.runLater {
                val webView = WebView()
                webView.isContextMenuEnabled = true
                webView.zoom = 1.0
                val engine = webView.engine
                engine.isJavaScriptEnabled = true
                // 伪装成现代 Safari，避免部分站点（Google/CF）对老 WebView 的 UA 拦截
                engine.userAgent =
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                    "(KHTML, like Gecko) Version/17.0 Safari/605.1.15"

                // location 变化 = WebView 内导航（点击链接/重定向）
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
                // history 变化时也刷新按钮状态（go/back 后 entries 已变）
                engine.history.currentIndexProperty().addListener { _, _, _ ->
                    val h = engine.history
                    val idx = h.currentIndex
                    onNavigationStateChanged(idx > 0, idx < h.entries.size - 1)
                }

                jfxPanel.scene = Scene(webView)
                webViewRef.value = webView

                if (url.isNotBlank()) {
                    lastLoaded.set(url)
                    engine.load(url)
                }
            }
            jfxPanel
        },
        update = {
            // url 变化时加载；lastLoaded 阻断 WebView 内导航产生的回环
            if (url.isNotBlank() && url != lastLoaded.get()) {
                lastLoaded.set(url)
                Platform.runLater {
                    val w = webViewRef.value ?: return@runLater
                    w.engine.load(url)
                }
            }
        }
    )

    // 注入 controller 命令
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
