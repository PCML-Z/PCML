package com.pmcl.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * WebView 控制器：由平台 actual 实现注入回调，commonMain 侧通过它命令 WebView 后退/前进/刷新。
 *
 * 使用普通类 + 可变 lambda 字段而非接口，避免 expect/actual 接口在 KMP 中的 companion 限制。
 * actual 实现会在 [WikiWebView] 内部用 LaunchedEffect 注入真正的实现。
 */
class WikiWebController {
    var goBack: () -> Unit = {}
    var goForward: () -> Unit = {}
    var reload: () -> Unit = {}
}

/**
 * 内嵌 WebView 组件（expect）。
 *
 * 由各平台 actual 提供真实实现。desktop 目标使用 JavaFX WebView 嵌入 SwingPanel。
 *
 * 状态契约：
 * - [url]：外部（地址栏）驱动的目标 URL。WebView 检测到变化时加载。
 * - [onUrlChanged]：WebView 内部导航（点击链接/重定向）产生新 URL 时回调，外部应同步地址栏。
 *   避免回环：actual 实现会跳过与当前已加载 URL 相同的回调。
 * - [onTitleChanged]：页面标题变化。
 * - [onLoadingChanged]：true=加载中，false=完成（含失败）。
 * - [onNavigationStateChanged]：后退/前进按钮可用性变化。
 */
@Composable
expect fun WikiWebView(
    url: String,
    controller: WikiWebController,
    onUrlChanged: (String) -> Unit,
    onTitleChanged: (String) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onNavigationStateChanged: (canBack: Boolean, canForward: Boolean) -> Unit,
    modifier: Modifier = Modifier
)
