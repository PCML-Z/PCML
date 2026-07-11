package com.pmcl.hmcl

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.stage.Stage
import javafx.stage.StageStyle
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles JavaFX initialization and HMCL scene embedding.
 *
 * The embedding flow:
 * 1. [getOrCreatePanel] creates a [JFXPanel] (this initializes JavaFX toolkit)
 * 2. [startHmclAsync] queues HMCL initialization on the JavaFX Application Thread
 * 3. [loadHmclInto] creates a hidden [Stage] and calls HMCL's `Launcher.start(stage)`
 *    which does ConfigHolder.init() and queues Controllers.initialize(stage) + stage.show()
 * 4. A `showingProperty` listener intercepts `stage.show()` and steals the Scene:
 *    - Detaches the scene from the hidden stage
 *    - Attaches it to the JFXPanel via [JFXPanel.setScene]
 * 5. The JFXPanel (a Swing JComponent) is wrapped in Compose's [SwingPanel]
 */
object HmclEmbedder {

    private const val TAG = "[HmclEmbedder]"

    private val panelRef = AtomicReference<JFXPanel?>(null)
    private val statusRef = AtomicReference("Idle — click Start to embed HMCL")
    @Volatile
    private var sceneStolen = false

    private fun log(msg: String) {
        println("$TAG $msg")
        System.out.flush()
    }

    /**
     * Get or create the JFXPanel that hosts HMCL's scene.
     * Called by Compose's SwingPanel factory on the UI thread.
     */
    fun getOrCreatePanel(): JFXPanel {
        return panelRef.get() ?: synchronized(this) {
            panelRef.get() ?: run {
                log("Creating JFXPanel (bootstraps JavaFX)...")
                statusRef.set("Creating JavaFX panel...")
                val panel = JFXPanel()
                panelRef.set(panel)
                log("JFXPanel created, queuing HMCL init on JavaFX thread")
                startHmclAsync(panel)
                panel
            }
        }
    }

    /**
     * Start HMCL initialization on the JavaFX Application Thread.
     */
    private fun startHmclAsync(panel: JFXPanel) {
        Platform.runLater {
            // Set the plugin's classloader as the context classloader on the JavaFX
            // Application Thread. HMCL's FXMLLoader, ServiceLoader, and other reflection
            // APIs use Thread.currentThread().contextClassLoader — without this, they
            // would use PMCL's system classloader and fail to find HMCL's classes.
            val pluginClassLoader = javaClass.classLoader
            Thread.currentThread().contextClassLoader = pluginClassLoader
            log("Context classloader set to plugin classloader: ${pluginClassLoader.javaClass.name}")

            try {
                loadHmclInto(panel)
            } catch (e: Throwable) {
                log("ERROR in startHmclAsync: ${e.message ?: e.toString()}")
                statusRef.set("Error: ${e.message ?: e.toString()}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Initialize HMCL and steal its scene into the JFXPanel.
     * Must be called on the JavaFX Application Thread.
     */
    private fun loadHmclInto(panel: JFXPanel) {
        log("loadHmclInto: starting HMCL initialization")
        statusRef.set("Initializing HMCL...")

        // Pre-set system properties (mirrors HMCL's EntryPoint.main)
        System.getProperties().putIfAbsent("java.net.useSystemProxies", "true")
        System.getProperties().putIfAbsent("javafx.autoproxy.disable", "true")

        // Force HMCL config directories to ~/.hmcl to avoid "无法加载配置文件" errors.
        // HMCL's Metadata class decides config paths at class-load time:
        //   hmcl.dir  / HMCL_LOCAL_HOME  → if unset, falls back to user.dir/.hmcl
        //   hmcl.home / HMCL_USER_HOME   → if unset, falls back to OS-specific path
        // When PMCL is launched from a protected directory (Desktop/Downloads/Documents on macOS,
        // or a read-only location), user.dir/.hmcl has no write permission, causing ConfigHolder.init()
        // to fail. We set these properties BEFORE any HMCL class is loaded so Metadata's static
        // initializer picks up the correct path.
        val userHome = System.getProperty("user.home")
        val hmclDir = "$userHome/.hmcl"
        System.getProperties().putIfAbsent("hmcl.dir", hmclDir)
        System.getProperties().putIfAbsent("hmcl.home", hmclDir)
        log("HMCL config directory set to: $hmclDir")

        // Create a hidden stage — HMCL's Launcher.start(Stage) expects a Stage
        val stage = Stage()
        stage.initStyle(StageStyle.UTILITY)
        stage.opacity = 0.0
        stage.width = 800.0
        stage.height = 600.0
        log("Hidden stage created (UTILITY, opacity=0)")

        // Intercept stage.show() to steal the Scene before it's displayed.
        stage.showingProperty().addListener { _, _, showing ->
            log("Stage showing property changed: showing=$showing, sceneStolen=$sceneStolen")
            if (showing && !sceneStolen) {
                sceneStolen = true
                try {
                    log("Stealing scene from Controllers.getScene()...")
                    val controllersClass = Class.forName("org.jackhuang.hmcl.ui.Controllers")
                    val getSceneMethod = controllersClass.getMethod("getScene")
                    val scene = getSceneMethod.invoke(null) as Scene
                    log("Scene obtained: ${scene.javaClass.name}, root=${scene.root?.javaClass?.name}")

                    // Detach scene from hidden stage
                    stage.scene = null
                    // Prevent stage close handler from calling Platform.exit()
                    stage.setOnCloseRequest { _ -> }
                    log("Scene detached from hidden stage, hiding stage")

                    // Attach scene to JFXPanel (deferred to next pulse to avoid
                    // re-entrancy issues during stage.show())
                    Platform.runLater {
                        try {
                            log("Attaching scene to JFXPanel...")
                            panel.setScene(scene)
                            log("Scene attached to JFXPanel successfully!")
                            statusRef.set("HMCL UI embedded successfully")

                            // 删除 HMCL 窗口的最小化和关闭按钮
                            // （HMCL 嵌入 PMCL 后不应有独立窗口按钮，关闭应由 PMCL 控制）
                            Platform.runLater {
                                try {
                                    removeWindowButtons(scene)
                                } catch (e: Throwable) {
                                    log("Button removal failed (non-fatal): ${e.message}")
                                }
                                try {
                                    injectPmclIcon(scene)
                                } catch (e: Throwable) {
                                    log("Icon injection failed (non-fatal): ${e.message}")
                                }
                            }
                        } catch (e: Throwable) {
                            log("FAILED to attach scene to JFXPanel: ${e.message}")
                            statusRef.set("Failed to attach scene: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } catch (e: Throwable) {
                    log("FAILED to steal scene: ${e.message}")
                    statusRef.set("Failed to steal scene: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        // Call HMCL's Launcher.start(stage) via reflection
        log("Loading HMCL Launcher class...")
        val launcherClass = Class.forName("org.jackhuang.hmcl.Launcher")
        log("Launcher class loaded: ${launcherClass.name}")
        val launcher = launcherClass.getDeclaredConstructor().newInstance()
        log("Launcher instance created")
        val startMethod = launcherClass.getMethod("start", Stage::class.java)
        log("Calling Launcher.start(stage)...")
        startMethod.invoke(launcher, stage)
        log("Launcher.start(stage) returned — HMCL should have queued Controllers.initialize on JavaFX thread")

        // Restore default uncaught exception handler (HMCL sets CrashReporter)
        Platform.runLater {
            Thread.currentThread().uncaughtExceptionHandler = null
        }

        statusRef.set("Waiting for HMCL scene to build...")
        log("Waiting for HMCL scene to build (stage.show listener will fire when ready)...")
    }

    /** Current initialization status, for display in the Compose UI. */
    fun getStatus(): String = statusRef.get()

    /** Whether the HMCL scene has been successfully embedded. */
    fun isEmbedded(): Boolean = sceneStolen && statusRef.get().contains("successfully")

    /**
     * 删除 HMCL 窗口的最小化和关闭按钮。
     *
     * HMCL 的 DecoratorSkin 构造函数创建一个 HBox（位于 AnchorPane 内），包含：
     * - HELP（问号，帮助按钮）
     * - MINIMIZE_CENTER（最小化）
     * - CLOSE（关闭）
     *
     * 这些按钮都是局部变量（不是 DecoratorSkin 的字段），styleClass 统一为
     * "jfx-decorator-button"。按钮的 graphic 是 SVGPath，其 content 由
     * SVG.getPath() 生成（rawPath 前加 "M24 24ZM0 0Z" 前缀）。
     *
     * 识别方法：检查按钮的 graphic（SVGPath）的 content 属性：
     * - MINIMIZE_CENTER 路径含 "v-2h12v2"（减号形状）
     * - CLOSE 路径含 "6.4 19 5 17.6"（X 形状）
     *
     * 由于 DecoratorSkin 可能在 scene steal 后才创建按钮（Control 的 skin 在
     * 首次 layout 时创建），需要重试机制。
     */
    private fun removeWindowButtons(scene: Scene, retryCount: Int = 0) {
        val root = scene.root ?: return
        log("Removing window buttons (attempt ${retryCount + 1}), root: ${root.javaClass.name}")

        // 先 dump 所有 jfx-decorator-button 节点用于调试
        val decoratorButtons = mutableListOf<Node>()
        collectDecoratorButtons(root, decoratorButtons)
        log("Found ${decoratorButtons.size} jfx-decorator-button nodes")
        for (btn in decoratorButtons) {
            val graphic = (btn as? javafx.scene.control.ButtonBase)?.graphic
            val graphicType = graphic?.javaClass?.name ?: "null"
            val content = (graphic as? javafx.scene.shape.SVGPath)?.content ?: "N/A"
            log("  Button: ${btn.javaClass.simpleName}, graphic=$graphicType, content=${content.take(80)}")
        }

        val removed = intArrayOf(0)
        findAndRemoveButtons(root, removed)
        log("Window button removal: ${removed[0]} buttons removed")

        // 如果没找到按钮，可能是 DecoratorSkin 还没创建，重试
        if (removed[0] < 2 && retryCount < 10) {
            Platform.runLater {
                try {
                    Thread.sleep(100) // 给 skin 创建一些时间
                } catch (_: InterruptedException) {}
                removeWindowButtons(scene, retryCount + 1)
            }
        }
    }

    /**
     * 收集所有 styleClass 含 "jfx-decorator-button" 的节点（用于调试）。
     */
    private fun collectDecoratorButtons(node: Node, list: MutableList<Node>) {
        val styleClass = node.styleClass
        if (styleClass.isNotEmpty() && styleClass.any { it.contains("jfx-decorator-button") }) {
            list.add(node)
        }
        if (node is Pane) {
            for (child in node.childrenUnmodifiable) {
                collectDecoratorButtons(child, list)
            }
        }
    }

    /**
     * 递归遍历场景图，查找 JFXButton（styleClass 含 "jfx-decorator-button"），
     * 检查其 SVGPath 图形内容来识别最小化和关闭按钮并移除。
     */
    private fun findAndRemoveButtons(node: Node, removed: IntArray) {
        val styleClass = node.styleClass
        if (styleClass.isNotEmpty() && styleClass.any { it.contains("jfx-decorator-button") }) {
            val isMinimize = isMinimizeButton(node)
            val isClose = isCloseButton(node)
            if (isMinimize || isClose) {
                val type = if (isMinimize) "minimize" else "close"
                val parent = node.parent
                if (parent is Pane) {
                    parent.children.remove(node)
                    removed[0]++
                    log("Removed $type button from ${parent.javaClass.simpleName}")
                } else {
                    node.isVisible = false
                    node.isManaged = false
                    removed[0]++
                    log("Hidden $type button (parent not a Pane: ${parent?.javaClass?.name})")
                }
                return
            }
        }

        if (node is Pane) {
            val children = node.childrenUnmodifiable.toList()
            for (child in children) {
                findAndRemoveButtons(child, removed)
            }
        }
    }

    /**
     * 检查按钮的 graphic 是否为最小化图标。
     * SVG.getPath() 在 rawPath 前加 "M24 24ZM0 0Z" 前缀，但 rawPath 内容保留在内。
     * MINIMIZE_CENTER rawPath = "M6 13v-2h12v2H6Z"
     */
    private fun isMinimizeButton(node: Node): Boolean {
        val graphic = (node as? javafx.scene.control.ButtonBase)?.graphic ?: return false
        if (graphic is javafx.scene.shape.SVGPath) {
            val content = graphic.content ?: ""
            return content.contains("v-2h12v2")
        }
        // SVGContainer 包裹了 SVGPath
        if (graphic is Pane) {
            for (child in graphic.childrenUnmodifiable) {
                if (child is javafx.scene.shape.SVGPath && child.content?.contains("v-2h12v2") == true) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 检查按钮的 graphic 是否为关闭图标。
     * CLOSE rawPath = "M6.4 19 5 17.6 10.6 12 5 6.4 6.4 5 12 10.6 17.6 5 19 6.4 ..."
     */
    private fun isCloseButton(node: Node): Boolean {
        val graphic = (node as? javafx.scene.control.ButtonBase)?.graphic ?: return false
        if (graphic is javafx.scene.shape.SVGPath) {
            val content = graphic.content ?: ""
            return content.contains("6.4 19 5 17.6") || content.contains("17.6 5 19 6.4")
        }
        // SVGContainer 包裹了 SVGPath
        if (graphic is Pane) {
            for (child in graphic.childrenUnmodifiable) {
                if (child is javafx.scene.shape.SVGPath) {
                    val c = child.content ?: ""
                    if (c.contains("6.4 19 5 17.6") || c.contains("17.6 5 19 6.4")) return true
                }
            }
        }
        return false
    }

    /**
     * Inject PMCL icon into HMCL's title bar.
     *
     * Strategy:
     * 1. Try to find any text-bearing node (Label, Text, Labeled) containing "HMCL"
     * 2. If found, insert icon into its parent container
     * 3. If not found, use reflection on DecoratorSkin to access titleContainer
     */
    private fun injectPmclIcon(scene: Scene) {
        // Load PMCL icon from plugin resources
        val cl = javaClass.classLoader
        val iconStream = cl.getResourceAsStream("resources/pmcl_icon.png")
            ?: cl.getResourceAsStream("pmcl_icon.png")
        if (iconStream == null) {
            log("PMCL icon resource not found in plugin classpath")
            return
        }
        val iconImage = Image(iconStream)
        if (iconImage.isError) {
            log("Failed to load PMCL icon: ${iconImage.exception?.message}")
            return
        }
        log("PMCL icon loaded: ${iconImage.width}x${iconImage.height}")

        // Debug: dump top-level scene graph structure
        log("Scene graph root: ${scene.root?.javaClass?.name}")
        dumpSceneGraph(scene.root, 0, 3)

        // Strategy 1: Search for any text-bearing node containing "HMCL"
        val titleNode = findTextNode(scene.root, "HMCL")
        if (titleNode != null) {
            log("Found text node containing 'HMCL': ${titleNode.javaClass.name}")
            val parent = titleNode.parent
            if (parent is Pane) {
                val iconView = createIconView(iconImage)
                val idx = parent.children.indexOf(titleNode)
                if (idx >= 0) {
                    parent.children.add(idx, iconView)
                    if (parent is HBox && parent.spacing < 1.0) {
                        parent.spacing = 6.0
                    }
                    log("PMCL icon injected before text node at index $idx")
                    return
                }
            }
            log("Could not inject icon via text node parent (${parent?.javaClass?.name})")
        }

        // Strategy 2: Use reflection on DecoratorSkin to access titleContainer
        log("Trying reflection approach on DecoratorSkin...")
        try {
            val iconView = createIconView(iconImage)
            if (injectViaReflection(scene.root, iconView)) {
                log("PMCL icon injected via DecoratorSkin reflection")
                return
            }
        } catch (e: Throwable) {
            log("Reflection injection failed: ${e.message}")
        }

        log("Icon injection skipped — no suitable target found")
    }

    /**
     * Dump scene graph structure for debugging (limited depth).
     */
    private fun dumpSceneGraph(node: Node?, depth: Int, maxDepth: Int) {
        if (node == null || depth > maxDepth) return
        val indent = "  ".repeat(depth)
        val text = when (node) {
            is javafx.scene.control.Labeled -> " text='${node.text}'"
            is javafx.scene.text.Text -> " text='${node.text}'"
            is ImageView -> " img=${node.image?.width}x${node.image?.height} fit=${node.fitWidth}x${node.fitHeight}"
            else -> ""
        }
        // Also show layout bounds for positioning info
        val bounds = node.layoutBounds
        val pos = " x=${String.format("%.0f", bounds.minX)} y=${String.format("%.0f", bounds.minY)} w=${String.format("%.0f", bounds.width)} h=${String.format("%.0f", bounds.height)}"
        log("${indent}${node.javaClass.simpleName}$text$pos")
        if (node is Pane) {
            for (child in node.childrenUnmodifiable) {
                dumpSceneGraph(child, depth + 1, maxDepth)
            }
        }
    }

    /**
     * Search scene graph for any text-bearing node (Label, Text, Labeled) containing the keyword.
     */
    private fun findTextNode(node: Node, keyword: String): Node? {
        // Check Label and any Labeled control (Button, etc.)
        if (node is javafx.scene.control.Labeled) {
            val text = node.text
            if (text != null && text.contains(keyword)) return node
        }
        // Check Text nodes
        if (node is javafx.scene.text.Text) {
            val text = node.text
            if (text != null && text.contains(keyword)) return node
        }
        // Recurse into children
        if (node is Pane) {
            for (child in node.childrenUnmodifiable) {
                val found = findTextNode(child, keyword)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Use reflection to access DecoratorSkin's private titleContainer field
     * and inject the icon there.
     */
    private fun injectViaReflection(root: Node, iconView: ImageView): Boolean {
        // Navigate to Decorator → get skin → DecoratorSkin
        if (root !is javafx.scene.control.Control) return false
        val skin = root.skin ?: return false
        val skinClass = skin.javaClass
        log("Skin class: ${skinClass.name}")

        // Try to access 'titleContainer' field (StackPane)
        val titleContainerField = try {
            skinClass.getDeclaredField("titleContainer")
        } catch (e: NoSuchFieldException) {
            log("titleContainer field not found on ${skinClass.name}")
            null
        }

        if (titleContainerField != null) {
            titleContainerField.isAccessible = true
            val titleContainer = titleContainerField.get(skin) as? Pane
            if (titleContainer != null) {
                log("titleContainer found: ${titleContainer.javaClass.simpleName}, children=${titleContainer.children.size}")
                // Debug: dump full structure of titleContainer to find HMCL icon position
                log("=== titleContainer full dump ===")
                dumpSceneGraph(titleContainer, 0, 8)
                log("=== end dump ===")

                // The structure is: StackPane → BorderPane → TransitionPane → ... → HBox
                // The HBox contains: [HMCL ImageView, title Label]
                // We want to insert PMCL icon AFTER the HMCL icon, BEFORE the label.
                // Search for the HBox that contains an ImageView (HMCL icon).
                val titleHBox = findHBoxWithImageView(titleContainer)
                if (titleHBox != null) {
                    log("Found HBox with ImageView: children=${titleHBox.children.size}")
                    // Find the Label index and insert PMCL icon before it
                    val labelIdx = titleHBox.children.indexOfFirst { it is javafx.scene.control.Labeled || it is javafx.scene.text.Text }
                    val insertIdx = if (labelIdx >= 0) labelIdx else titleHBox.children.size
                    titleHBox.children.add(insertIdx, iconView)
                    titleHBox.spacing = 6.0
                    log("PMCL icon inserted into title HBox at index $insertIdx")
                    return true
                } else {
                    log("HBox with ImageView not found, falling back to titleContainer add")
                    titleContainer.children.add(0, iconView)
                    javafx.scene.layout.StackPane.setAlignment(iconView, javafx.geometry.Pos.CENTER_LEFT)
                    return true
                }
            }
        }

        // Try navBarPane → TransitionPane as fallback
        val navBarField = try {
            skinClass.getDeclaredField("navBarPane")
        } catch (e: NoSuchFieldException) { null }

        if (navBarField != null) {
            navBarField.isAccessible = true
            val navBar = navBarField.get(skin) as? Pane
            if (navBar != null) {
                log("navBarPane found: ${navBar.javaClass.simpleName}, children=${navBar.children.size}")
                navBar.children.add(0, iconView)
                return true
            }
        }

        return false
    }

    /**
     * Create an ImageView sized for the title bar (27x27 px).
     */
    private fun createIconView(image: Image): ImageView {
        val view = ImageView(image)
        view.fitWidth = 27.0
        view.fitHeight = 27.0
        view.isPreserveRatio = true
        view.isSmooth = true
        HBox.setMargin(view, javafx.geometry.Insets(0.0, 4.0, 0.0, 4.0))
        return view
    }

    /**
     * Recursively search for an HBox that contains an ImageView child.
     * This finds the title bar HBox: [HMCL ImageView, title Label]
     */
    private fun findHBoxWithImageView(node: Node): HBox? {
        if (node is HBox) {
            if (node.children.any { it is ImageView }) {
                return node
            }
        }
        if (node is Pane) {
            for (child in node.childrenUnmodifiable) {
                val found = findHBoxWithImageView(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Shutdown HMCL and release resources.
     */
    fun shutdown() {
        log("Shutdown requested")
        Platform.runLater {
            try {
                val controllersClass = Class.forName("org.jackhuang.hmcl.ui.Controllers")
                val stopMethod = controllersClass.getMethod("onApplicationStop")
                stopMethod.invoke(null)
                log("HMCL onApplicationStop called")
            } catch (e: Throwable) {
                log("Shutdown error (non-fatal): ${e.message}")
            }
        }
        panelRef.set(null)
        sceneStolen = false
        statusRef.set("Stopped")
    }
}
