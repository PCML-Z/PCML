package com.pmcl.live2d

import com.pmcl.plugin.OverlayConfig
import com.pmcl.plugin.PluginContext
import com.pmcl.plugin.PmclPlugin

/**
 * Live2D Companion 插件主类。
 *
 * 在主窗口右下角注册一个全局 overlay，通过 JavaFX WebView 加载
 * pixi-live2d-display 渲染 Live2D 模型。支持鼠标跟随视线、点击触发
 * 动作/表情、拖拽移动位置。
 *
 * 模型来源：默认从 CDN 加载 Shizuku 示例模型，用户可通过插件配置
 * (config key: "modelUrl") 指定自定义模型 URL 或本地路径。
 */
class Live2dPlugin : PmclPlugin {
    override val pluginId = "live2d"

    override fun onLoad() { /* No-op */ }

    override fun onEnable(ctx: PluginContext) {
        ctx.info("Live2D plugin enabled — registering overlay")
        val modelUrl = ctx.getConfig("modelUrl")
            ?: "https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display/test/assets/shizuku/shizuku.model.json"
        ctx.registerOverlay(
            id = "live2d-model",
            content = Live2dOverlayContent(ctx, modelUrl),
            config = OverlayConfig(zIndex = 100)
        )
    }

    override fun onDisable() {
        Live2dWebView.shutdown()
    }
}
