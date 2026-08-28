package com.pmcl.plugin

import javafx.scene.Parent

/**
 * 插件提供的 JavaFX 页面内容。
 *
 * 与 [ComposableContent]（Compose UI）平行：插件实现 [createRoot] 返回 JavaFX 场景根节点，
 * PMCL 宿主负责 JFXPanel/SwingPanel 嵌入、线程调度与生命周期，
 * 插件**不需要**自己处理 Swing 互操作——这正是把 HMCL 嵌入经验（HmclEmbedder）
 * 泛化为通用能力后的产物。
 *
 * ## 类加载与 JavaFX 运行时
 *
 * `javafx.*` / `com.sun.javafx.*` 由宿主统一提供（插件类加载器已桥接），
 * 插件**不应**在自己的 jar 里打包 JavaFX——即使打包也会被宿主版本遮蔽，
 * 反而可能引入版本错配。
 *
 * ## 调用时机与线程
 *
 * - [createRoot] 在 **JavaFX Application Thread** 上调用（通常在页面首次进入时）；
 * - 调用期间 `Thread.currentThread().contextClassLoader` 已临时设置为插件类加载器，
 *   因此 FXMLLoader / ServiceLoader / 反射加载插件自身类可直接工作；
 *   若插件在之后的异步回调中再触发 FXML 加载，需自行设置 contextClassLoader。
 * - 场景会被宿主缓存：页面离开仅解除挂载（不销毁），再次进入时直接复用，
 *   [createRoot] 对同一实例**通常只调用一次**。不要依赖“每次进入重建”的语义。
 *
 * 示例：
 * ```kotlin
 * class MyPlugin : PmclPlugin {
 *     override fun onEnable(ctx: PluginContext) {
 *         ctx.registerJavaFxPage("fx-demo", "JavaFX Demo", JavaFxContent {
 *             StackPane(Button("Hello from JavaFX").apply {
 *                 setOnAction { println("clicked") }
 *             })
 *         })
 *     }
 * }
 * ```
 */
fun interface JavaFxContent {

    /**
     * 构建 JavaFX 场景根节点。在 JavaFX Application Thread 上调用。
     *
     * 实现应快速返回可见内容；耗时数据加载请在后台线程完成后回填 UI。
     *
     * @return 场景根节点（将包进 Scene 挂到 JFXPanel）
     * @throws Throwable 构建失败时抛出，宿主在页面上显示错误占位（不影响主窗口）
     */
    fun createRoot(): Parent
}

/**
 * 把 [JavaFxContent] 包装成可注册为普通插件页面的 [ComposableContent]。
 *
 * ## 为什么需要这个工厂
 *
 * 嵌入引擎（`SwingPanel` + `JFXPanel` + 线程/生命周期管理）位于 `:ui`，
 * 而 `:core` 的 [com.pmcl.core.plugin.PluginManager] 不能依赖 Compose/JavaFX。
 * 因此 `:ui` 启动时把实现注册进 [JavaFxPageFactories]，
 * `:core` 在插件调用 `PluginContext.registerJavaFxPage` 时取出工厂生成页面内容，
 * 再走既有的 `registerPage` 通道（导航、徽标、卸载清理全部复用）。
 *
 * 与 [WebViewPageFactories]（embed=web 外部运行时插件）同一套注册表模式。
 */
fun interface JavaFxPageFactory {

    /**
     * @param content 插件提供的 JavaFX 内容
     * @return 可交给 `registerPage` 的页面内容
     */
    fun create(content: JavaFxContent): ComposableContent
}

/**
 * 全局单例注册表：`:ui` 注册实现，`:core` 消费。
 *
 * 无 UI 环境（CLI / headless）下不会有注册，[get] 返回 null，
 * 此时 `registerJavaFxPage` 会注册一个错误占位页而非崩溃。
 */
object JavaFxPageFactories {

    @Volatile
    private var factory: JavaFxPageFactory? = null

    /** 由 UI 层在启动早期调用。重复调用以最后一次为准。 */
    @JvmStatic
    fun register(factory: JavaFxPageFactory) {
        this.factory = factory
    }

    /** 取出当前实现；无 UI 环境返回 null。 */
    @JvmStatic
    fun get(): JavaFxPageFactory? = factory

    /** 当前环境是否支持 JavaFX 嵌入。 */
    @JvmStatic
    fun isAvailable(): Boolean = factory != null

    /** 主要供测试使用。 */
    @JvmStatic
    fun clear() {
        factory = null
    }

    /**
     * 无 UI 宿主时的降级页面内容：组合即抛异常，
     * 由宿主的 SafePluginPage 捕获并渲染错误占位符（不崩溃主窗口）。
     * 供 `:core` 在 [get] 返回 null 时取用。
     */
    @JvmStatic
    fun unavailableContent(): ComposableContent = ComposableContent {
        throw IllegalStateException(
            "JavaFX embedding is not available in this environment (no UI host registered)"
        )
    }
}
