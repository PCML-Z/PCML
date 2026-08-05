package com.pmcl.plugin

/**
 * `embed=window` 模式的页面工厂注册表。
 *
 * <p>与 [WebViewPageFactories] 同理：核心层（`:core`）不依赖 Compose/JavaFX，
 * 无法自己构造 UI 组合；因此由 UI 层（`:ui`）在 `main()` 早期注册一个工厂实现，
 * 核心层取出后用它生成 [ComposableContent] 并 `ctx.registerPage` 注册为插件页面。
 *
 * <p>该页面本身只是一块"占位宿主区域"（[com.pmcl.ui.page.DockHost]），
 * 真正渲染内容的是被停靠应用的真实窗口——它浮动在这块区域之上。
 */
fun interface DockHostPageFactory {
    fun create(pluginId: String): ComposableContent
}

object DockHostPageFactories {
    @Volatile
    private var factory: DockHostPageFactory? = null

    @JvmStatic
    fun register(factory: DockHostPageFactory) {
        this.factory = factory
    }

    @JvmStatic
    fun get(): DockHostPageFactory? = factory

    @JvmStatic
    fun isAvailable(): Boolean = factory != null
}
