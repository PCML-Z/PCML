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
fun main() = application {
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

    Window(
        onCloseRequest = ::exitApplication,
        title = "PMCL — Minecraft Launcher",
        state = state,
        undecorated = borderless,
        transparent = borderless
    ) {
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
            // 视差背景层：放在最底层，所有内容悬浮其上
            if (parallaxBg) {
                com.pmcl.ui.theme.ParallaxBackground(useDark = useDark)
            }
            if (borderless) {
                // 无边框模式：transparent=true 让边缘像素 alpha 混合（抗锯齿），
                // 拖动期间临时设为不透明矩形避免 SwingPanel 重绘延迟导致闪烁
                val isDark = useDark
                val scheme = if (isDark) darkColorScheme() else lightColorScheme()
                val surfaceColor = scheme.surface
                val isDragging = remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    val updateShape = {
                        if (isDragging.value) {
                            // 拖动中：移除 shape 和透明，用不透明矩形避免闪烁
                            window.shape = null
                            window.background = java.awt.Color(
                                surfaceColor.red, surfaceColor.green, surfaceColor.blue
                            )
                        } else if (window.extendedState == Frame.MAXIMIZED_BOTH) {
                            // 最大化：直角填满屏幕
                            window.shape = null
                            window.background = java.awt.Color(
                                surfaceColor.red, surfaceColor.green, surfaceColor.blue
                            )
                        } else {
                            // 正常：透明背景 + 圆角 shape（边缘抗锯齿）
                            window.background = java.awt.Color(0, 0, 0, 0)
                            window.shape = RoundRectangle2D.Double(
                                0.0, 0.0,
                                window.width.toDouble(), window.height.toDouble(),
                                14.0, 14.0
                            )
                        }
                    }
                    updateShape()
                    val listener = object : ComponentAdapter() {
                        override fun componentResized(e: ComponentEvent?) { updateShape() }
                        override fun componentMoved(e: ComponentEvent?) { updateShape() }
                    }
                    window.addComponentListener(listener)
                    onDispose { window.removeComponentListener(listener) }
                }
                // 拖动状态变化时刷新 shape
                LaunchedEffect(isDragging.value) {
                    if (!isDragging.value) {
                        window.background = java.awt.Color(0, 0, 0, 0)
                        window.shape = if (window.extendedState == Frame.MAXIMIZED_BOTH) null
                        else RoundRectangle2D.Double(
                            0.0, 0.0,
                            window.width.toDouble(), window.height.toDouble(),
                            14.0, 14.0
                        )
                    }
                }
                MaterialTheme(colorScheme = scheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
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
                                onOpenCompanion = { showCompanionDialog.value = true }
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
                        onOpenCompanion = { showCompanionDialog.value = true }
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        App(vm)
                    }
                }
            }
            // iOS 伴随 App 配对对话框
            if (showCompanionDialog.value) {
                val scheme = if (useDark) darkColorScheme() else lightColorScheme()
                MaterialTheme(colorScheme = scheme) {
                    com.pmcl.ui.companion.CompanionPairDialog(
                        pairing = pairingManager,
                        hostServer = hostServer,
                        onDismiss = { showCompanionDialog.value = false }
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

/**
 * 轻量读取 borderlessWindow 偏好（仅正则匹配 JSON 字段，不构造完整 Preferences，
 * 避免与 LauncherCore 中的 Preferences 重复加载文件+解析+创建线程池）。
 */
private fun readBorderlessPref(path: String): Boolean {
    return try {
        val json = java.nio.file.Files.readString(java.nio.file.Paths.get(path), java.nio.charset.StandardCharsets.UTF_8)
        // 简单正则提取，避免完整 JSON 解析开销
        val m = Regex("\"borderlessWindow\"\\s*:\\s*(true|false)").find(json)
        m?.groupValues?.get(1)?.toBoolean() ?: false
    } catch (_: Throwable) { false }
}

/**
 * 轻量读取 useDarkTheme 偏好。
 */
private fun readDarkThemePref(path: String): Boolean {
    return try {
        val json = java.nio.file.Files.readString(java.nio.file.Paths.get(path), java.nio.charset.StandardCharsets.UTF_8)
        val m = Regex("\"useDarkTheme\"\\s*:\\s*(true|false)").find(json)
        m?.groupValues?.get(1)?.toBoolean() ?: false
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
 */
@Composable
private fun FrameWindowScope.BorderlessTitleBar(
    onClose: () -> Unit,
    isDragging: MutableState<Boolean>,
    vm: LauncherViewModel,
    searchFocusRequester: FocusRequester,
    onOpenAi: () -> Unit,
    onOpenCompanion: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().height(38.dp)
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
 */
@Composable
private fun SlimSearchBar(
    vm: LauncherViewModel,
    searchFocusRequester: FocusRequester,
    onOpenAi: () -> Unit,
    onOpenCompanion: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().height(38.dp)
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
