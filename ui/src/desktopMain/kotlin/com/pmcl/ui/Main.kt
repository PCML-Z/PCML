package com.pmcl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.ui.animation.SplashIconReveal
import com.pmcl.ui.page.PerfHudWindow
import com.pmcl.ui.page.TopBarSearchField
import com.pmcl.ui.theme.LauncherTheme
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.ThemeState
import com.pmcl.ui.widget.TaskCenterPanel
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.playNextMusic
import com.pmcl.ui.viewmodel.playPreviousMusic
import com.pmcl.ui.viewmodel.stopMusic
import com.pmcl.ui.viewmodel.toggleMusicPlayPause
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D
import java.nio.file.Paths

/**
 * 预加载协程异常处理机制相关类（top-level val 在 main() 前的 MainKt.<clinit> 执行）。
 *
 * 这些类由 kotlinx.coroutines 惰性加载——只有第一次协程异常发生时才会从 classpath 读取。
 * 如果运行期间 fat jar 被 gradle 重新构建覆盖（开着启动器执行 ./gradlew :ui:fatJar），
 * 此时再加载会失败，表现为：
 *   "Could not initialize class kotlinx.coroutines.internal.CoroutineExceptionHandlerImplKt"
 *   "Fatal exception in coroutines machinery ..." 弹窗风暴（Recomposer 崩溃，UI 全挂）。
 * 启动时趁 jar 完好提前加载，可保证协程异常处理路径始终可用。
 */
private val coroutinesErrorMachineryPreloaded: Boolean = run {
    listOf(
        "kotlinx.coroutines.internal.CoroutineExceptionHandlerImplKt",
        "kotlinx.coroutines.CoroutineExceptionHandlerKt",
        "kotlinx.coroutines.internal.StackTraceRecoveryKt",
        "kotlinx.coroutines.CompletionHandlerException",
        // 统计页用到的嵌套类：启动时趁 jar 完好预加载，避免运行中覆盖 fat jar 后进统计页炸 NoClassDefFoundError
        "com.pmcl.core.stats.PlayTimeTracker",
        "com.pmcl.core.stats.PlayTimeTracker\$OverallStat",
        "com.pmcl.core.stats.PlayTimeTracker\$DailyStat",
        "com.pmcl.core.stats.PlayTimeTracker\$HeatmapStat",
        "com.pmcl.core.stats.PlayTimeTracker\$WeekdayStat",
        "com.pmcl.core.stats.PlayTimeTracker\$RecordsStat",
        "com.pmcl.core.stats.PlayTimeTracker\$VersionStat",
        "com.pmcl.core.stats.PlayTimeTracker\$BreakdownStat",
        "com.pmcl.core.stats.PlayTimeTracker\$Session",
        // 系统信息 / 实时性能卡依赖 oshi；嵌套类 OSVersionInfo 最容易在 jar 被覆盖后炸
        "oshi.SystemInfo",
        "oshi.software.os.OperatingSystem",
        "oshi.software.os.OperatingSystem\$OSVersionInfo",
        "oshi.software.os.OperatingSystem\$ProcessFiltering",
        "oshi.software.os.OperatingSystem\$ProcessSorting",
        "oshi.software.common.AbstractOperatingSystem",
        "oshi.hardware.HardwareAbstractionLayer",
        "oshi.hardware.CentralProcessor",
        "oshi.hardware.CentralProcessor\$ProcessorIdentifier",
        "oshi.hardware.GlobalMemory",
        "oshi.hardware.GraphicsCard",
        "oshi.hardware.NetworkIF",
        "oshi.software.os.OSFileStore",
        "com.pmcl.core.runtime.RuntimeManager",
    ).forEach { name ->
        try {
            Class.forName(name)
        } catch (e: Throwable) {
            System.err.println("[Main] 预加载 $name 失败: $e")
        }
    }
    // 平台实现类 + 一次真实初始化，把 Mac/Win/Linux OperatingSystem 与 OSVersionInfo 全部钉进 Metaspace
    try {
        when {
            System.getProperty("os.name", "").lowercase().contains("mac") ->
                Class.forName("oshi.software.os.mac.MacOperatingSystem")
            System.getProperty("os.name", "").lowercase().contains("win") ->
                Class.forName("oshi.software.os.windows.WindowsOperatingSystem")
            else ->
                Class.forName("oshi.software.os.linux.LinuxOperatingSystem")
        }
        com.pmcl.core.runtime.RuntimeManager().getOsName()
    } catch (e: Throwable) {
        System.err.println("[Main] oshi/RuntimeManager 预热失败: $e")
    }
    true
}

/**
 * 桌面端入口。
 *
 * 运行方式：./gradlew :ui:run
 */
fun main() = application {
    // 启动器自身日志收集：tee stdout/stderr 到内存环形缓冲，
    // 供日志页「复制启动器日志」使用（异常堆栈/插件错误/Prism 诊断等）。
    // 越早安装捕获越完整；输出仍原样透传控制台。
    com.pmcl.core.util.LauncherLogCollector.install()

    // JavaFX WebView / HMCL 嵌入（必须在 JavaFX toolkit 初始化前设置）：
    // - javafx.macosx.embed=true：Glass 以嵌入模式运行（JFXPanel 进 Compose SwingPanel）
    // - prism.order=es2：强制 OpenGL ES2 硬件加速管线（默认在 JFXPanel 嵌入场景可能回退到 sw 软件渲染，
    //   导致 WebView 滚动/重绘 FPS 极低，CPU 占用高）
    // - prism.native=true：优先使用 native GL 实现
    // - prism.verbose=true：启动时输出实际渲染管线到 stderr，便于诊断
    // - javafx.animation.fullspeed=false：保持 vsync 同步，避免撕裂但保证流畅
    System.setProperty("javafx.macosx.embed", "true")
    System.setProperty("prism.order", "es2")
    System.setProperty("prism.native", "true")
    System.setProperty("prism.verbose", "true")
    System.setProperty("prism.vsync", "true")

    // 注册 WebView 页面工厂：外部运行时插件（embed=web）据此把自己的本地 Web UI
    // 嵌入 PMCL 主窗口成为一个普通页面，而不是弹出独立的外部应用窗口。
    // 必须早于插件系统启用任何 embed 插件。
    remember { com.pmcl.ui.page.registerEmbeddedWebViewFactory(); Unit }

    // 注册 JavaFX 页面工厂：标准 JVM 插件经 PluginContext.registerJavaFxPage
    // 把自己的 JavaFX UI 嵌入主窗口（HmclEmbedder 能力的通用化）。
    // 必须早于插件系统启用任何插件。
    remember { com.pmcl.ui.page.registerJavaFxPageFactory(); Unit }

    // 注册 DockHost 页面工厂：外部运行时插件（embed=window）据此把声明应用的真实窗口
    // 停靠进 PMCL 主窗口成为普通页面（占位区 + 浮动真实窗口）。
    // 必须早于插件系统启用任何 embed=window 插件。
    remember { com.pmcl.ui.page.registerDockHostFactory(); Unit }

    // 启动时仅轻量读取窗口/主题偏好（不构造完整 Preferences，避免与 LauncherCore 重复加载）
    // 支持 -Dpmcl.workdir 覆盖工作目录（绕过 macOS TCC com.apple.provenance 限制）
    val pmclDir = System.getProperty("pmcl.workdir")
        ?.takeIf { it.isNotEmpty() }
        ?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".pmcl")
    val prefPath = pmclDir.resolve("preferences.json")
    val borderless = remember { readBorderlessPref(prefPath.toString()) }
    val vm = remember { LauncherViewModel() }
    val sharedThemeState = remember {
        ThemeState(initialDark = vm.preferences.isUseDarkTheme())
    }
    val searchFocusRequester = remember { FocusRequester() }
    val restartForUpdate by vm.restartForUpdate.collectAsState()

    // 安装辅助进程已启动：先释放游戏/联机/文件句柄，再退出，让辅助进程替换并重启当前构建。
    LaunchedEffect(restartForUpdate) {
        if (restartForUpdate) {
            vm.shutdown()
            exitApplication()
        }
    }

    // 应用退出时优雅关闭（进程/联机/偏好落盘）；onDispose 作兜底
    DisposableEffect(Unit) {
        onDispose { vm.shutdown() }
    }

    // 伴随模式 WebSocket 服务宿主
    val companionDataFile = remember { pmclDir.resolve("companion.json") }
    val pairingManager = remember { com.pmcl.ui.companion.PairingManager(companionDataFile) }
    val hostServer = remember { com.pmcl.ui.companion.PmclHostServer(vm, pairingManager) }
    DisposableEffect(Unit) {
        hostServer.start()
        onDispose { hostServer.stop() }
    }

    val state = rememberWindowState(
        width = 1100.dp,
        height = 700.dp,
        position = WindowPosition.Aligned(Alignment.Center)
    )

    // iOS 伴随 App 配对对话框开关
    val showCompanionDialog = remember { mutableStateOf(false) }
    // 任务中心开关
    var showTaskCenter by remember { mutableStateOf(false) }

    // 视差背景主题开关（响应式，可在设置中实时切换）
    val parallaxBg by vm.parallaxBackground.collectAsState()
    // 自定义背景（图片/视频，优先级高于视差背景）
    val customBgType by vm.launcherBgType.collectAsState()
    val customBgImage by vm.launcherBgImagePath.collectAsState()
    val customBgVideo by vm.launcherBgVideoPath.collectAsState()
    val customBgOn = (customBgType == "image" && customBgImage.isNotBlank()) ||
            (customBgType == "video" && customBgVideo.isNotBlank())
    // 任一背景层激活时内容 Surface 透明
    val bgLayerOn = parallaxBg || customBgOn
    // 玻璃主题开关（响应式，标题栏/侧边栏分层毛玻璃）
    val glassOn by vm.glassTheme.collectAsState()

    // 启动动画状态：播放期间主窗口隐藏，动画结束 → 切换为主窗口
    var splashDone by remember { mutableStateOf(false) }

    // --- 启动动画窗口（无边框、透明、居中） ---
    if (!splashDone) {
        Window(
            onCloseRequest = { splashDone = true },
            title = "PMCL",
            state = rememberWindowState(
                width = 700.dp,
                height = 400.dp,
                position = WindowPosition.Aligned(Alignment.Center)
            ),
            undecorated = true,
            transparent = true,
            resizable = false
        ) {
            SplashIconReveal(
                modifier = Modifier.fillMaxSize(),
                onFinished = { splashDone = true }
            )
        }
    }

    // --- 主窗口（启动动画期间隐藏以预加载资源） ---
    Window(
        visible = splashDone,
        onCloseRequest = {
            try {
                vm.shutdown()
            } catch (e: Throwable) {
                System.err.println("[PMCL] shutdown 异常（仍将退出）: ${e.message}")
                e.printStackTrace()
            }
            exitApplication()
        },
        title = "PMCL — Minecraft Launcher",
        state = state,
        undecorated = borderless,
        transparent = borderless
    ) {
        // 全局拖放：监听 .jar 文件拖入主窗口 → 触发 mod 拖放安装
        DisposableEffect(Unit) {
            val frame = window
            // DropTarget 构造时自动注册到 frame，保存引用以便 onDispose 时解除
            val dt = java.awt.dnd.DropTarget(frame, java.awt.dnd.DnDConstants.ACTION_COPY,
                object : java.awt.dnd.DropTargetAdapter() {
                    override fun drop(dtde: java.awt.dnd.DropTargetDropEvent) {
                        try {
                            dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                            val transfer = dtde.transferable
                            if (!transfer.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                                dtde.dropComplete(false)
                                return
                            }
                            @Suppress("UNCHECKED_CAST")
                            val files = transfer.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                                    as List<java.io.File>
                            val jars = files.map { it.toPath() }
                                .filter { p ->
                                    val name = p.fileName.toString().lowercase()
                                    // 接受 .jar 与禁用形态 .jar.disabled
                                    name.endsWith(".jar") || name.endsWith(".jar.disabled")
                                }
                            dtde.dropComplete(true)
                            if (jars.isNotEmpty()) {
                                vm.dropInstallMod(jars)
                            }
                        } catch (e: Throwable) {
                            System.err.println("[Main] 拖放处理失败: ${e.message}")
                            dtde.dropComplete(false)
                        }
                    }
                }, true)
            onDispose {
                dt.setActive(false)
                // 解除 DropTarget 与 frame 的关联，让窗口恢复默认拖放行为
                frame.dropTarget = null
            }
        }
        // Ctrl+K 全局快捷键 + 系统媒体键（窗口焦点内）
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.K && (event.isCtrlPressed || event.isMetaPressed) -> {
                        searchFocusRequester.requestFocus()
                        true
                    }
                    event.key == Key.MediaPlayPause ||
                    event.key == Key.MediaPlay ||
                    event.key == Key.MediaPause -> {
                        vm.toggleMusicPlayPause(); true
                    }
                    event.key == Key.MediaNext -> {
                        vm.playNextMusic(); true
                    }
                    event.key == Key.MediaPrevious -> {
                        vm.playPreviousMusic(); true
                    }
                    event.key == Key.MediaStop -> {
                        vm.stopMusic(); true
                    }
                    else -> false
                }
            }
        ) {
            // 最大化状态：提前声明，供 parallaxBg / borderless 两个块共享
            // 最大化时移除圆角裁剪，让内容填满屏幕直角
            var isMaximized by remember { mutableStateOf(false) }

            // 背景层：放在最底层，所有内容悬浮其上
            // 无边框模式下 clip 圆角，避免方形边缘盖住窗口 shape
            // 最大化时不裁剪，让背景填满屏幕直角
            // 优先级：自定义背景（图片/视频）> 视差背景
            val bgModifier = if (borderless && !isMaximized) Modifier.clip(RoundedCornerShape(14.dp))
                             else Modifier
            if (customBgOn) {
                com.pmcl.ui.theme.CustomBackground(
                    type = customBgType,
                    imagePath = customBgImage,
                    videoPath = customBgVideo,
                    useDark = sharedThemeState.useDark,
                    modifier = bgModifier
                )
            } else if (parallaxBg) {
                com.pmcl.ui.theme.ParallaxBackground(
                    modifier = bgModifier,
                    useDark = sharedThemeState.useDark
                )
            }
            val windowDynamicScheme =
                if (sharedThemeState.dynamicColor || sharedThemeState.customAccentColor != -1) {
                    sharedThemeState.dynamicColorScheme
                } else {
                    null
                }
            LauncherTheme(
                useDarkTheme = sharedThemeState.useDark,
                dynamicColorScheme = windowDynamicScheme,
                uiScale = sharedThemeState.uiScale,
                themePreset = sharedThemeState.themePreset,
                colorMode = sharedThemeState.colorMode,
                customThemePack = sharedThemeState.customThemePack
            ) {
                CompositionLocalProvider(LocalThemeState provides sharedThemeState) {
            if (borderless) {
                // 无边框模式：transparent=true 让边缘像素 alpha 混合（抗锯齿），
                // 圆角 shape 始终保持，AWT 背景始终保持透明让视差/玻璃效果生效
                val isDragging = remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    // M49 修复：透明窗口（transparent=true）在 macOS 上会跟随 contentPane
                    // preferredSize 自动缩小。当 AnimatedVisibility(visible=false)（入场动画期间）
                    // 内容不占空间时，preferredSize 仅剩 NavigationRail 宽度，窗口缩到 ~121px。
                    //
                    // 三重兜底：
                    // 1. minimumSize 阻止 AWT pack 主动缩小
                    // 2. componentResized 监听异常缩小并恢复
                    // 3. Timer 周期检查（componentResized 在透明窗口首次 pack 时可能不触发）
                    val minW = 900
                    val minH = 600
                    val targetW = 1100
                    val targetH = 700
                    window.minimumSize = java.awt.Dimension(minW, minH)
                    // 首次强制设置目标尺寸，避免 pack 用过小的 preferredSize
                    window.setSize(targetW, targetH)
                    val updateShape = {
                        val maximized = window.extendedState == Frame.MAXIMIZED_BOTH
                        isMaximized = maximized
                        // 仅最大化时使用直角；任务中心是窗口内面板，不应改变窗口设计语言
                        window.shape = if (maximized) null
                        else RoundRectangle2D.Double(
                            0.0, 0.0,
                            window.width.toDouble(), window.height.toDouble(),
                            14.0, 14.0
                        )
                        // AWT 背景始终保持透明，让 Compose 内部透明渲染（视差/玻璃）生效
                        window.background = java.awt.Color(0, 0, 0, 0)
                    }
                    updateShape()
                    val restoreIfTooSmall = {
                        // 仅恢复异常缩小的尺寸，不调用 toFront/setVisible
                        // （会强制主窗口置顶，盖住 AI 窗口/对话框等弹窗）
                        val state = window.extendedState
                        if (state and Frame.ICONIFIED != 0) {
                            window.extendedState = state and Frame.ICONIFIED.inv()
                        }
                        if (window.width < minW || window.height < minH) {
                            window.setSize(targetW, targetH)
                            updateShape()
                        }
                    }
                    val listener = object : ComponentAdapter() {
                        override fun componentResized(e: ComponentEvent?) {
                            restoreIfTooSmall()
                            updateShape()
                        }
                        override fun componentMoved(e: ComponentEvent?) { updateShape() }
                    }
                    window.addComponentListener(listener)
                    // Timer 兜底：前 3 秒每 100ms 检查（覆盖入场动画期），之后每 1s 检查
                    // 解决 componentResized 在透明窗口首次 pack 时可能不触发的问题
                    val timer = java.util.Timer("PmclWindowSizeGuard", true)
                    var slowTimer: java.util.Timer? = null
                    var tick = 0
                    timer.scheduleAtFixedRate(object : java.util.TimerTask() {
                        override fun run() {
                            javax.swing.SwingUtilities.invokeLater {
                                restoreIfTooSmall()
                            }
                            tick++
                            // 30 次后（约 3 秒）切到低频 1s 检查
                            if (tick == 30) {
                                timer.cancel()
                                val st = java.util.Timer("PmclWindowSizeGuardSlow", true)
                                slowTimer = st
                                st.scheduleAtFixedRate(object : java.util.TimerTask() {
                                    override fun run() {
                                        javax.swing.SwingUtilities.invokeLater {
                                            restoreIfTooSmall()
                                        }
                                    }
                                }, 0, 1000)
                            }
                        }
                    }, 0, 100)
                    onDispose {
                        timer.cancel()
                        slowTimer?.cancel()
                        window.removeComponentListener(listener)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize().then(
                        // 仅最大化时不裁剪；任务中心展开期间继续保持窗口圆角
                        if (isMaximized) Modifier
                        else Modifier.clip(RoundedCornerShape(14.dp))
                    ),
                    color = if (bgLayerOn) Color.Transparent else MaterialTheme.colorScheme.surface,
                    tonalElevation = if (bgLayerOn) 0.dp else 1.dp
                ) {
                    Column(Modifier.fillMaxSize()) {
                        BorderlessTitleBar(
                            onClose = ::exitApplication,
                            isDragging = isDragging,
                            vm = vm,
                            searchFocusRequester = searchFocusRequester,
                            onOpenCompanion = { showCompanionDialog.value = true },
                            onOpenTaskCenter = { showTaskCenter = true },
                            glassOn = glassOn
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            App(vm, sharedThemeState)
                        }
                    }
                }
            } else {
                // 非无边框模式：OS 标题栏 + 应用内搜索条
                Column(Modifier.fillMaxSize()) {
                    SlimSearchBar(
                        vm = vm,
                        searchFocusRequester = searchFocusRequester,
                        onOpenCompanion = { showCompanionDialog.value = true },
                        onOpenTaskCenter = { showTaskCenter = true },
                        glassOn = glassOn
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        App(vm, sharedThemeState)
                    }
                }
            }
            // iOS 伴随 App 配对对话框（保持与主窗口主题一致）
            if (showCompanionDialog.value) {
                com.pmcl.ui.companion.CompanionPairDialog(
                    pairing = pairingManager,
                    hostServer = hostServer,
                    onDismiss = { showCompanionDialog.value = false },
                    parallaxBg = bgLayerOn,
                    glassOn = glassOn,
                    useDark = sharedThemeState.useDark
                )
            }
            // 任务中心：右侧滑入面板（内嵌，非独立窗口）
            TaskCenterPanel(
                visible = showTaskCenter,
                vm = vm,
                onDismiss = { showTaskCenter = false },
                modifier = if (borderless && !isMaximized) {
                    Modifier.clip(RoundedCornerShape(14.dp))
                } else {
                    Modifier
                }
            )
            // Mod 拖放安装对话框：拖入 .jar 文件后展示
            val dropState by vm.dropInstallState.collectAsState()
            if (dropState != null) {
                com.pmcl.ui.page.ModDropDialog(
                    state = dropState!!,
                    vm = vm,
                    useDark = sharedThemeState.useDark
                )
            }
                }
            }
        }
    }

    // 性能 HUD 浮窗（由设置中 showPerfHud 开关控制）
    val showPerfHud by vm.perfHudVisible.collectAsState()
    val perfHudMetrics by vm.perfHudMetrics.collectAsState()
    if (showPerfHud) {
        PerfHudWindow(
            metrics = perfHudMetrics,
            onClose = { vm.setPerfHudVisible(false) }
        )
    }
}

/**
 * 轻量读取 borderlessWindow 偏好（M37 修复：改用 Gson 解析 JsonObject，
 * 避免正则在嵌套对象/转义字符串中误匹配）。
 */
private fun readBorderlessPref(path: String): Boolean {
    return try {
        val json = java.nio.file.Files.readString(java.nio.file.Paths.get(path), java.nio.charset.StandardCharsets.UTF_8)
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        if (obj.has("borderlessWindow") && !obj.get("borderlessWindow").isJsonNull) {
            obj.get("borderlessWindow").asBoolean
        } else false
    } catch (_: Throwable) { false }
}

/**
 * 轻量读取 useDarkTheme 偏好。
 */
private fun readDarkThemePref(path: String): Boolean {
    return try {
        val json = java.nio.file.Files.readString(java.nio.file.Paths.get(path), java.nio.charset.StandardCharsets.UTF_8)
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        if (obj.has("useDarkTheme") && !obj.get("useDarkTheme").isJsonNull) {
            obj.get("useDarkTheme").asBoolean
        } else false
    } catch (_: Throwable) { false }
}

/**
 * 窗口拖拽修饰符：按住左键拖拽移动窗口（Compose 1.7 无 WindowDragArea，手动实现）。
 * 拖动开始/结束 时更新 isDragging 状态，用于切换透明/不透明渲染避免闪烁。
 *
 * 仅挂在标题栏空白区（标题文字 / Spacer），不要挂整行 Row，
 * 这样搜索框与按钮会先命中自身，不会整窗被拖走。
 *
 * 注意：不要用 Final pass + isConsumed 过滤——Compose Desktop 上许多事件在 Final
 * 时已被标记 consumed，会导致拖拽永远无法启动。
 */
private fun WindowScope.windowDragModifier(isDragging: MutableState<Boolean>): Modifier =
    Modifier.pointerInput(Unit) {
        var initialMouse: Point? = null
        var initialWindowLoc: Point? = null

        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val mouseLocation = MouseInfo.getPointerInfo()?.location

                if (event.buttons.isPrimaryPressed) {
                    if (initialMouse == null && mouseLocation != null) {
                        initialMouse = mouseLocation
                        initialWindowLoc = Point(window.x, window.y)
                        isDragging.value = true
                    }
                    val im = initialMouse
                    val iwl = initialWindowLoc
                    if (event.type == PointerEventType.Move && im != null && iwl != null && mouseLocation != null) {
                        val dx = mouseLocation.x - im.x
                        val dy = mouseLocation.y - im.y
                        window.setLocation(iwl.x + dx, iwl.y + dy)
                    }
                } else {
                    if (initialMouse != null) {
                        isDragging.value = false
                    }
                    initialMouse = null
                    initialWindowLoc = null
                }
            }
        }
    }

/**
 * 无边框窗口自定义标题栏：可拖拽 + 搜索框 + 最小化/最大化/关闭按钮。
 * 玻璃主题开启时分层渲染：底层模糊背景 + 上层透明 Surface 清晰内容。
 */
@Composable
private fun FrameWindowScope.BorderlessTitleBar(
    onClose: () -> Unit,
    isDragging: MutableState<Boolean>,
    vm: LauncherViewModel,
    searchFocusRequester: FocusRequester,
    onOpenCompanion: () -> Unit,
    onOpenTaskCenter: () -> Unit = {},
    glassOn: Boolean = false
) {
    Box(Modifier.fillMaxWidth().height(38.dp)) {
        if (glassOn) {
            // 模糊背景层：独立节点被 blur，渲染毛玻璃质感
            Box(
                Modifier
                    .matchParentSize()
                    .blur(24.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            )
        }
        Surface(
            color = if (glassOn) Color.Transparent else MaterialTheme.colorScheme.surface,
            tonalElevation = if (glassOn) 0.dp else 2.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // 标题（可拖拽区域；fillMaxHeight 扩大命中条带）
            Text(
                "PMCL — Minecraft Launcher",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .fillMaxHeight()
                    .wrapContentHeight(Alignment.CenterVertically)
                    .then(windowDragModifier(isDragging))
            )
            Spacer(Modifier.width(12.dp).fillMaxHeight().then(windowDragModifier(isDragging)))
            // 搜索框：独立布局，不挂窗口拖拽，避免抢焦点/无法输入
            TopBarSearchField(
                modifier = Modifier.width(280.dp),
                vm = vm,
                focusRequester = searchFocusRequester,
                compact = true
            )
            Spacer(Modifier.weight(1f).fillMaxHeight().then(windowDragModifier(isDragging)))
            // iOS 伴随 App 配对按钮
            IconButton(onClick = onOpenCompanion, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.PhoneIphone, "iOS 伴随 App 配对", modifier = Modifier.size(16.dp))
            }
            // 任务中心按钮
            IconButton(onClick = onOpenTaskCenter, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Notifications, "任务中心", modifier = Modifier.size(16.dp))
            }
            // 最小化
            IconButton(
                onClick = { window.extendedState = Frame.ICONIFIED },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.Minimize, "最小化", modifier = Modifier.size(16.dp))
            }
            // 最大化/还原
            IconButton(
                onClick = {
                    window.extendedState =
                        if (window.extendedState == Frame.MAXIMIZED_BOTH)
                            Frame.NORMAL
                        else
                            Frame.MAXIMIZED_BOTH
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.CropSquare, "最大化/还原", modifier = Modifier.size(14.dp))
            }
            // 关闭
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(16.dp))
            }
        }
        }
    }
}

/**
 * 非无边框模式下的搜索条（OS 标题栏下方）。
 * 玻璃主题开启时分层渲染：底层模糊背景 + 上层透明 Surface 清晰内容。
 */
@Composable
private fun SlimSearchBar(
    vm: LauncherViewModel,
    searchFocusRequester: FocusRequester,
    onOpenCompanion: () -> Unit,
    onOpenTaskCenter: () -> Unit = {},
    glassOn: Boolean = false
) {
    Box(Modifier.fillMaxWidth().height(38.dp)) {
        if (glassOn) {
            Box(
                Modifier
                    .matchParentSize()
                    .blur(24.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            )
        }
        Surface(
            color = if (glassOn) Color.Transparent else MaterialTheme.colorScheme.surface,
            tonalElevation = if (glassOn) 0.dp else 2.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopBarSearchField(
                    modifier = Modifier.width(320.dp),
                    vm = vm,
                    focusRequester = searchFocusRequester,
                    compact = true
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenCompanion, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.PhoneIphone, "iOS 伴随 App 配对", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onOpenTaskCenter, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Notifications, "任务中心", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
