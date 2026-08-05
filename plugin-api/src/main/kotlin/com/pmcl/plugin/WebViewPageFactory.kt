package com.pmcl.plugin

/**
 * 把一个本地 URL 包装成可嵌入 PMCL 主窗口的插件页面内容。
 *
 * ## 为什么需要这个工厂
 *
 * 外部运行时插件（.NET / Python / Node.js）的进程托管逻辑位于 `:core`
 * （[com.pmcl.plugin] 的宿主实现），而真正能渲染网页的 WebView 组件位于 `:ui`
 * （Compose + JavaFX，`:core` 不依赖也不应依赖它们）。
 *
 * 因此 `:ui` 在启动时把自己的实现注册进 [WebViewPageFactories]，
 * `:core` 在启用「embed=web」的外部插件时取出工厂生成 [ComposableContent]，
 * 再通过 `PluginContext.registerPage` 注册为一个普通插件页面。
 *
 * 这样就实现了**真正的窗口内嵌**（同 `hmcl-plugin` 用 `JFXPanel` 嵌入的思路），
 * 而不是弹出一个独立的外部应用窗口。
 */
fun interface WebViewPageFactory {
    /**
     * @param url 要加载的地址，通常是 `http://127.0.0.1:<port>/`
     * @return 可交给 `registerPage` 的页面内容
     */
    fun create(url: String): ComposableContent
}

/**
 * 全局单例注册表：`:ui` 注册实现，`:core` 消费。
 *
 * 在无 UI 的环境（CLI / headless）下不会有任何注册，
 * [get] 返回 null，宿主应降级为「只跑后台进程、不注册页面」。
 */
object WebViewPageFactories {

    @Volatile
    private var factory: WebViewPageFactory? = null

    /** 由 UI 层在启动早期调用。重复调用以最后一次为准。 */
    @JvmStatic
    fun register(factory: WebViewPageFactory) {
        this.factory = factory
    }

    /** 取出当前实现；无 UI 环境返回 null。 */
    @JvmStatic
    fun get(): WebViewPageFactory? = factory

    /** 当前环境是否支持 WebView 嵌入。 */
    @JvmStatic
    fun isAvailable(): Boolean = factory != null

    /** 主要供测试使用。 */
    @JvmStatic
    fun clear() {
        factory = null
    }
}
