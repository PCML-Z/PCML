package com.pmcl.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Minimize
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
import com.pmcl.ui.page.AiAgentPage
import com.pmcl.ui.page.PerfHudWindow
import com.pmcl.ui.page.TopBarSearchField
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D
import java.nio.file.Paths

/**
 * 桌面端入口。
 *
 * 运行方式：./gradlew :ui:run
 */
fun main() {
    // macOS 嵌入模式：让 JavaFX Glass 不抢占 NSApplication 主线程，
    // 由 AWT 担任主事件循环。必须在 application{} 启动前设置。
    System.setProperty("javafx.macosx.embed", "true")
    application {
    // 启动时仅轻量读取窗口/主题偏好（不构造完整 Preferences，避免与 LauncherCore 重复加载）
    val prefPath = Paths.get(System.getProperty("user.home"), ".pmcl", "preferences.json")
    val borderless = remember { readBorderlessPref(prefPath.toString()) }
    val useDark = remember { readDarkThemePref(prefPath.toString()) }
    val vm = remember { LauncherViewModel() }
    val searchFocusRequester = remember { FocusRequester() }

    // 应用退出时优雅关闭 VM 协程，避免 JVM 强杀导致正在进行的文件写入损坏
    DisposableEffect(Unit) {
        onDispose { vm.shutdown() }
    }

    // 伴随模式 WebSocket 服务宿主
    val companionDataFile = remember {
        Paths.get(System.getProperty("user.home"), ".pmcl", "companion.json")
    }
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

    // AI 智能体独立窗口开关
    val showAiWindow = remember { mutableStateOf(false) }
    // iOS 伴随 App 配对对话框开关
    val showCompanionDialog = remember { mutableStateOf(false) }
    val aiWindowState = rememberWindowState(
        width = 860.dp,
        height = 640.dp,
        position = WindowPosition.Aligned(Alignment.Center)
    )

    // 视差背景主题开关（响应式，可在设置中实时切换）
    val parallaxBg by vm.parallaxBackground.collectAsState()
    // 玻璃主题开关（响应式，标题栏/侧边栏分层毛玻璃）
    val glassOn by vm.glassTheme.collectAsState()

    Window(
        onCloseRequest = ::exitApplication,
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
        // Ctrl+K 全局快捷键：聚焦搜索框
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.K &&
                    (event.isCtrlPressed || event.isMetaPressed)
                ) {
                    searchFocusRequester.requestFocus()
                    true
                } else false
            }
        ) {
            // 最大化状态：提前声明，供 parallaxBg / borderless 两个块共享
            // 最大化时移除圆角裁剪，让内容填满屏幕直角
            var isMaximized by remember { mutableStateOf(false) }

            // 视差背景层：放在最底层，所有内容悬浮其上
            // 无边框模式下 clip 圆角，避免方形边缘盖住窗口 shape
            // 最大化时不裁剪，让背景填满屏幕直角
            if (parallaxBg) {
                com.pmcl.ui.theme.ParallaxBackground(
                    modifier = if (borderless && !isMaximized) Modifier.clip(RoundedCornerShape(14.dp))
                               else Modifier,
                    useDark = useDark
                )
            }
            if (borderless) {
                // 无边框模式：transparent=true 让边缘像素 alpha 混合（抗锯齿），
                // 圆角 shape 始终保持，AWT 背景始终保持透明让视差/玻璃效果生效
                val isDark = useDark
                val scheme = if (isDark) darkColorScheme() else lightColorScheme()
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
                        // 最大化时直角填满屏幕，其他状态保持 14dp 圆角
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
                        // 窗口可能被 macOS Dock 吸附（ICONIFIED）或异常缩小：
                        // 1. 先 deiconify（清除 ICONIFIED 状态），setSize 对 Dock 窗口无效
                        // 2. 再恢复到目标尺寸
                        // 3. setVisible + toFront 确保窗口前台可见
                        val state = window.extendedState
                        if (state and Frame.ICONIFIED != 0) {
                            window.extendedState = state and Frame.ICONIFIED.inv()
                        }
                        if (window.width < minW || window.height < minH) {
                            window.setSize(targetW, targetH)
                            updateShape()
                        }
                        if (!window.isVisible) {
                            window.isVisible = true
                        }
                        window.toFront()
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
                MaterialTheme(colorScheme = scheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize().then(
                            // 最大化时不裁剪圆角，让内容填满屏幕直角
                            if (isMaximized) Modifier
                            else Modifier.clip(RoundedCornerShape(14.dp))
                        ),
                        color = if (parallaxBg) Color.Transparent else MaterialTheme.colorScheme.surface,
                        tonalElevation = if (parallaxBg) 0.dp else 1.dp
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            BorderlessTitleBar(
                                onClose = ::exitApplication,
                                isDragging = isDragging,
                                vm = vm,
                                searchFocusRequester = searchFocusRequester,
                                onOpenAi = { showAiWindow.value = true },
                                onOpenCompanion = { showCompanionDialog.value = true },
                                glassOn = glassOn
                            )
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                App(vm)
                            }
                        }
                    }
                }
            } else {
                // 非无边框模式：OS 标题栏 + 应用内搜索条
                Column(Modifier.fillMaxSize()) {
                    SlimSearchBar(
                        vm = vm,
                        searchFocusRequester = searchFocusRequester,
                        onOpenAi = { showAiWindow.value = true },
                        onOpenCompanion = { showCompanionDialog.value = true },
                        glassOn = glassOn
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        App(vm)
                    }
                }
            }
            // iOS 伴随 App 配对对话框（保持与主窗口主题一致）
            if (showCompanionDialog.value) {
                val scheme = if (useDark) darkColorScheme() else lightColorScheme()
                MaterialTheme(colorScheme = scheme) {
                    com.pmcl.ui.companion.CompanionPairDialog(
                        pairing = pairingManager,
                        hostServer = hostServer,
                        onDismiss = { showCompanionDialog.value = false },
                        parallaxBg = parallaxBg,
                        glassOn = glassOn,
                        useDark = useDark
                    )
                }
            }
            // Mod 拖放安装对话框：拖入 .jar 文件后展示
            val dropState by vm.dropInstallState.collectAsState()
            if (dropState != null) {
                val scheme = if (useDark) darkColorScheme() else lightColorScheme()
                MaterialTheme(colorScheme = scheme) {
                    com.pmcl.ui.page.ModDropDialog(
                        state = dropState!!,
                        vm = vm,
                        useDark = useDark
                    )
                }
            }
        }
    }

    // AI 智能体独立窗口
    if (showAiWindow.value) {
        Window(
            onCloseRequest = { showAiWindow.value = false },
            title = "PCML智能体",
            state = aiWindowState,
            undecorated = false,
            focusable = true
        ) {
            val isDark = useDark
            val scheme = if (isDark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AiAgentPage(vm)
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
    onOpenAi: () -> Unit,
    onOpenCompanion: () -> Unit,
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
                modifier = Modifier
                    .fillMaxSize()
                    .then(windowDragModifier(isDragging)),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // 标题
            Text(
                "PMCL — Minecraft Launcher",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp)
            )
            Spacer(Modifier.width(12.dp))
            // 搜索框（自身消耗指针事件，不会触发拖动）
            TopBarSearchField(
                modifier = Modifier.width(280.dp),
                vm = vm,
                focusRequester = searchFocusRequester,
                compact = true
            )
            Spacer(Modifier.weight(1f))
            // iOS 伴随 App 配对按钮
            IconButton(onClick = onOpenCompanion, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.PhoneIphone, "iOS 伴随 App 配对", modifier = Modifier.size(16.dp))
            }
            // AI 智能体按钮（鼠标悬停展开标签）
            AiHoverButton(onClick = onOpenAi)
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
 * 标题栏 AI 悬浮按钮：默认仅显示图标，鼠标悬停时水平展开显示「以智能体模式打开」标签。
 */
@Composable
private fun AiHoverButton(onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Surface(
        color = if (hovered) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .height(28.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> hovered = false
                            else -> {}
                        }
                    }
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 6.dp),
        tonalElevation = 0.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Filled.Bolt, "以智能体模式打开",
                tint = if (hovered) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp))
            AnimatedVisibility(
                visible = hovered,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut()
            ) {
                Text("以智能体模式打开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1)
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
    onOpenAi: () -> Unit,
    onOpenCompanion: () -> Unit,
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
                AiHoverButton(onClick = onOpenAi)
            }
        }
    }
}
