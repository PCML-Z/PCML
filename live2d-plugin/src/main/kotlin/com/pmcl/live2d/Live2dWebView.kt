package com.pmcl.live2d

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.awt.Color
import java.util.concurrent.atomic.AtomicReference

/**
 * 管理 JavaFX WebView 的生命周期，将 Live2D 渲染（pixi-live2d-display）嵌入 Compose。
 *
 * 通过 JFXPanel（JavaFX-Swing 桥）将 JavaFX WebView 包装为 Swing 组件，
 * 再由 Compose 的 SwingPanel 互操作显示在 UI 中。
 *
 * 拖拽支持：JavaScript 端检测 mousedown/mousemove/mouseup，区分点击与拖拽，
 * 通过 JS-Java 桥（window.dragBridge）将拖拽增量回调给 Kotlin，由 Compose
 * 更新 overlay 的 offset 实现位置移动。
 */
object Live2dWebView {

    private val panelRef = AtomicReference<JFXPanel?>(null)
    private var initialized = false

    /**
     * 获取或创建 JFXPanel。
     * [onDrag] 回调在 JS 检测到拖拽时被调用，参数为屏幕坐标增量 (dx, dy)。
     */
    fun getOrCreatePanel(modelUrl: String, onDrag: (Float, Float) -> Unit): JFXPanel {
        panelRef.get()?.let { return it }
        synchronized(this) {
            panelRef.get()?.let { return it }
            val panel = JFXPanel()
            // 透明背景，让 Live2D 模型浮在 PMCL 界面上
            panel.isOpaque = false
            panel.background = Color(0, 0, 0, 0)
            panelRef.set(panel)
            Platform.runLater {
                initWebView(panel, modelUrl, onDrag)
            }
            return panel
        }
    }

    private fun initWebView(panel: JFXPanel, modelUrl: String, onDrag: (Float, Float) -> Unit) {
        // 设置上下文 ClassLoader 为插件 ClassLoader，确保 JavaFX 反射能找到插件资源
        Thread.currentThread().contextClassLoader = javaClass.classLoader

        val webView = WebView()
        webView.isCache = true
        webView.setPrefSize(300.0, 400.0)
        // WebView 默认白色背景，用 CSS 强制透明
        webView.setStyle("-fx-background-color: transparent;")

        val engine = webView.getEngine()
        engine.setJavaScriptEnabled(true)

        // 加载 HTML 内容（从插件资源读取，注入模型 URL）
        val html = loadHtml(modelUrl)
        engine.loadContent(html)

        // 监听控制台输出，方便调试
        engine.setOnError { event ->
            System.err.println("[Live2D WebView Error] " + event.message)
        }

        // 暴露拖拽桥接对象给 JavaScript（延迟注入，确保页面已加载）
        val bridge = object {
            fun drag(dx: Double, dy: Double) {
                onDrag(dx.toFloat(), dy.toFloat())
            }
        }
        // 在 JavaFX 线程上延迟执行，确保 window 对象已创建
        Platform.runLater {
            try {
                val win = engine.executeScript("window") as netscape.javascript.JSObject
                win.setMember("dragBridge", bridge)
            } catch (_: Throwable) {
                // 页面尚未就绪，再延迟重试
                Platform.runLater {
                    try {
                        val win = engine.executeScript("window") as netscape.javascript.JSObject
                        win.setMember("dragBridge", bridge)
                    } catch (_: Throwable) {}
                }
            }
        }

        val scene = Scene(Group(webView))
        scene.fill = null // 透明场景背景
        panel.setScene(scene)
        initialized = true
    }

    /** 从插件资源读取 HTML 模板，内联 JS 库并注入模型 URL */
    private fun loadHtml(modelUrl: String): String {
        val cl = javaClass.classLoader
        // 内联所有 JS 库（避免依赖 CDN，国内访问不稳定）
        val cubismCore = readResource(cl, "resources/live2d/lib/live2dcubismcore.min.js")
        val live2d = readResource(cl, "resources/live2d/lib/live2d.min.js")
        val pixi = readResource(cl, "resources/live2d/lib/pixi.min.js")
        val pixiLive2d = readResource(cl, "resources/live2d/lib/pixi-live2d-display.min.js")

        if (cubismCore == null || live2d == null || pixi == null || pixiLive2d == null) {
            return fallbackHtml(modelUrl)
        }

        val htmlStream = cl.getResourceAsStream("resources/live2d/index.html")
            ?: return fallbackHtml(modelUrl)
        val html = htmlStream.bufferedReader().use { it.readText() }

        // 替换 CDN script 标签为内联 script
        return html
            .replace(
                """<script src="https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js"></script>""",
                "<script>$cubismCore</script>"
            )
            .replace(
                """<script src="https://cdn.jsdelivr.net/gh/dylanNew/live2d/webgl/Live2D/lib/live2d.min.js"></script>""",
                "<script>$live2d</script>"
            )
            .replace(
                """<script src="https://cdn.jsdelivr.net/npm/pixi.js@6.5.10/dist/browser/pixi.min.js"></script>""",
                "<script>$pixi</script>"
            )
            .replace(
                """<script src="https://cdn.jsdelivr.net/npm/pixi-live2d-display@0.4.0/dist/index.min.js"></script>""",
                "<script>$pixiLive2d</script>"
            )
            .replace("__MODEL_URL__", modelUrl)
    }

    private fun readResource(cl: ClassLoader, path: String): String? {
        val stream = cl.getResourceAsStream(path) ?: return null
        return stream.bufferedReader().use { it.readText() }
    }

    /** 资源读取失败时的内联降级 HTML */
    private fun fallbackHtml(modelUrl: String): String = """
        <!DOCTYPE html><html><head><meta charset="UTF-8">
        <style>*{margin:0;padding:0}html,body{background:transparent;overflow:hidden}canvas{display:block}</style>
        </head><body>
        <script src="https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js"></script>
        <script src="https://cdn.jsdelivr.net/gh/dylanNew/live2d/webgl/Live2D/lib/live2d.min.js"></script>
        <script src="https://cdn.jsdelivr.net/npm/pixi.js@6.5.10/dist/browser/pixi.min.js"></script>
        <script src="https://cdn.jsdelivr.net/npm/pixi-live2d-display@0.4.0/dist/index.min.js"></script>
        <script>
        window.addEventListener('load',function(){
            PIXI.live2d.Live2DModel.registerTicker(PIXI.Ticker);
            var opts={width:300,height:400,backgroundAlpha:0,antialias:true,forceCanvas:true};
            window.app=new PIXI.Application(opts);
            document.body.appendChild(window.app.view);
            window.model=null;
            PIXI.live2d.Live2DModel.from("$modelUrl").then(function(m){
                window.model=m;
                var s=Math.min(app.renderer.width/m.width,app.renderer.height/m.height)*0.85;
                m.scale.set(s);m.x=(app.renderer.width-m.width)/2;m.y=(app.renderer.height-m.height)/2;
                window.app.stage.addChild(m);
            }).catch(function(e){console.error(e);});
            var dragging=false,moved=false,lx=0,ly=0;
            window.app.view.addEventListener('mousedown',function(e){dragging=true;moved=false;lx=e.screenX;ly=e.screenY;});
            window.app.view.addEventListener('mousemove',function(e){if(dragging&&e.buttons===1){var dx=e.screenX-lx,dy=e.screenY-ly;if(Math.abs(dx)>3||Math.abs(dy)>3){moved=true;if(window.dragBridge)window.dragBridge.drag(dx,dy);lx=e.screenX;ly=e.screenY;}}});
            window.app.view.addEventListener('mouseup',function(e){if(dragging&&!moved&&window.model){try{window.model.tap();}catch(ex){}}dragging=false;moved=false;});
        });
        </script>
        </body></html>
    """.trimIndent()

    /** 重新加载指定模型 URL */
    fun reloadModel(modelUrl: String) {
        Platform.runLater {
            try {
                val panel = panelRef.get() ?: return@runLater
                val root = panel.scene?.root
                if (root is Group && root.children.isNotEmpty()) {
                    val webView = root.children[0] as? WebView ?: return@runLater
                    val html = loadHtml(modelUrl)
                    webView.getEngine().loadContent(html)
                }
            } catch (_: Throwable) {}
        }
    }

    /** 调整模型缩放比例（0.1 - 3.0） */
    fun setScale(scale: Float) {
        Platform.runLater {
            try {
                val panel = panelRef.get() ?: return@runLater
                val root = panel.scene?.root
                if (root is Group && root.children.isNotEmpty()) {
                    val webView = root.children[0] as? WebView ?: return@runLater
                    webView.getEngine().executeScript(
                        "if(window.app&&window.model){var s=Math.min(app.renderer.width/model.internalModel.width,app.renderer.height/model.internalModel.height)*$scale*0.85;model.scale.set(s);model.x=(app.renderer.width-model.width)/2;model.y=(app.renderer.height-model.height)/2;}"
                    )
                }
            } catch (_: Throwable) {}
        }
    }

    /** 关闭 WebView，释放 JavaFX 资源 */
    fun shutdown() {
        val panel = panelRef.getAndSet(null) ?: return
        Platform.runLater {
            try {
                val root = panel.scene?.root
                if (root is Group) {
                    root.children.clear()
                }
            } catch (_: Throwable) {}
        }
        initialized = false
    }
}
