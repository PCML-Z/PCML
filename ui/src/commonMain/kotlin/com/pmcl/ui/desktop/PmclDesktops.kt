package com.pmcl.ui.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.min

/** 启动页上滑时调用。不在调度中心里时是空操作。 */
val LocalOpenMissionControl = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * 当前这套桌面是否在前台。后台桌面不注册插件桥、不抢全局弹窗。
 * 外面没有调度中心时保持 true，单窗口行为和原来一样。
 */
val LocalDesktopHost = staticCompositionLocalOf { true }

/** 调度中心打开时，前台桌面也不吃页面点击。 */
val LocalDesktopInteractive = staticCompositionLocalOf { true }

/**
 * 多套桌面共用一个标记。后接手的桌面换掉标记后，先离开的那套不能再把插件桥清掉。
 */
internal class DesktopBridgeOwner {
    var token: Any? = null
}

internal val LocalDesktopBridgeOwner = staticCompositionLocalOf { DesktopBridgeOwner() }

private const val MAX_DESKTOPS = 6
private val ThumbRadius = 8.dp
private val SelectedBorder = Color(0xFF0A84FF)

private class DesktopSession(val id: Int)

/**
 * 同一窗口里的多套 PMCL。调度中心打开时，各套界面缩成一排桌面，点加号再加一套。
 * 账号、游戏目录和进程仍是同一个启动器；每套桌面有自己的页面。
 */
@Composable
fun PmclDesktops(vm: LauncherViewModel, onOpenSettings: () -> Unit = {}, content: @Composable () -> Unit) {
    val workDir = vm.config.workDir
    val sessions = remember { mutableStateListOf(DesktopSession(1)) }
    var nextId by remember { mutableIntStateOf(2) }
    var activeId by remember { mutableIntStateOf(1) }
    var open by remember { mutableStateOf(false) }
    val bridgeOwner = remember { DesktopBridgeOwner() }
    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
        label = "missionControl"
    )
    val showChrome = open || progress > 0.02f
    val openMission = remember { { open = true } }

    // 调度中心只改 open。各桌面的组合不拆掉，页面、滚动和输入都还在。
    DisposableEffect(Unit) {
        val held = HashSet<Int>()
        val mac = System.getProperty("os.name", "").lowercase().contains("mac")
        val dispatcher = java.awt.KeyEventDispatcher { event ->
            val code = event.keyCode
            if (event.id == java.awt.event.KeyEvent.KEY_RELEASED) {
                held.remove(code)
                return@KeyEventDispatcher false
            }
            if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            val commandE = code == java.awt.event.KeyEvent.VK_E &&
                !event.isAltDown && !event.isShiftDown &&
                if (mac) event.isMetaDown && !event.isControlDown
                else event.isControlDown && !event.isMetaDown
            if (commandE) {
                if (code !in held) {
                    held.add(code)
                    open = !open
                }
                return@KeyEventDispatcher true
            }
            if (open && code == java.awt.event.KeyEvent.VK_ESCAPE &&
                !event.isMetaDown && !event.isControlDown && !event.isAltDown
            ) {
                open = false
                return@KeyEventDispatcher true
            }
            false
        }
        val manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }

    CompositionLocalProvider(
        LocalOpenMissionControl provides openMission,
        LocalDesktopBridgeOwner provides bridgeOwner
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fullW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val fullH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val strip = rememberDesktopStrip(sessions.size, fullW, fullH)

            if (showChrome) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                        .graphicsLayer { alpha = progress }
                        .background(
                            Brush.horizontalGradient(
                                0f to Color(0xFFB7A89C),
                                0.45f to Color(0xFF9AA3B0),
                                1f to Color(0xFF8B97A8)
                            )
                        )
                )
            }

            for (index in sessions.indices) {
                val session = sessions[index]
                val active = session.id == activeId
                key(session.id) {
                    DesktopLayer(
                        active = active,
                        progress = progress,
                        showChrome = showChrome,
                        targetScale = strip.thumbW / fullW,
                        slotX = strip.x(index),
                        slotY = strip.y
                    ) {
                        CompositionLocalProvider(
                            LocalDesktopHost provides active,
                            LocalDesktopInteractive provides (active && !open)
                        ) {
                            content()
                        }
                    }
                }
            }

            if (showChrome) {
                MissionControlChrome(
                    workDir = workDir,
                    vm = vm,
                    onOpenSettings = {
                        open = false
                        onOpenSettings()
                    },
                    count = sessions.size,
                    activeIndex = sessions.indexOfFirst { it.id == activeId }.coerceAtLeast(0),
                    strip = strip,
                    progress = progress,
                    onDismiss = { open = false },
                    onSelect = { index ->
                        activeId = sessions[index].id
                        open = false
                    },
                    onAdd = {
                        if (sessions.size >= MAX_DESKTOPS) return@MissionControlChrome
                        val id = nextId
                        nextId += 1
                        sessions.add(DesktopSession(id))
                    },
                    onClose = { index ->
                        if (sessions.size <= 1) return@MissionControlChrome
                        val removed = sessions.removeAt(index)
                        if (removed.id == activeId) {
                            activeId = sessions[index.coerceAtMost(sessions.lastIndex)].id
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DesktopLayer(
    active: Boolean,
    progress: Float,
    showChrome: Boolean,
    targetScale: Float,
    slotX: Float,
    slotY: Float,
    content: @Composable () -> Unit
) {
    val z = when {
        showChrome -> 1f
        active -> 2f
        else -> 0f
    }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(z)
            .graphicsLayer {
                val scale = if (active) lerp(1f, targetScale, progress) else targetScale
                val safeScale = scale.coerceAtLeast(0.05f)
                scaleX = safeScale
                scaleY = safeScale
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = if (active) lerp(0f, slotX, progress) else slotX
                translationY = if (active) lerp(0f, slotY, progress) else slotY
                alpha = if (active) 1f else progress
                clip = progress > 0.01f
                shape = RoundedCornerShape(ThumbRadius / safeScale)
            }
    ) {
        content()
    }
}

@Composable
private fun MissionControlChrome(
    vm: LauncherViewModel,
    workDir: Path,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    count: Int,
    activeIndex: Int,
    strip: MeasuredStrip,
    progress: Float,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onClose: (Int) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(3f)
            .graphicsLayer { alpha = progress }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        Row(
            Modifier.offset(x = strip.startX, y = strip.imageTop),
            verticalAlignment = Alignment.Top
        ) {
            for (index in 0 until count) {
                if (index > 0) Spacer(Modifier.width(strip.gap))
                DesktopThumbnail(
                    index = index,
                    selected = index == activeIndex,
                    thumbW = strip.thumbWDp,
                    thumbH = strip.thumbHDp,
                    canClose = count > 1,
                    onSelect = { onSelect(index) },
                    onClose = { onClose(index) }
                )
            }
            Spacer(Modifier.width(strip.plusGap))
            val plusTop = ((strip.thumbHDp - 28.dp) / 2).coerceAtLeast(0.dp)
            Box(
                Modifier
                    .padding(top = plusTop)
                    .size(28.dp)
                    .clickable(
                        enabled = count < MAX_DESKTOPS,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAdd
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = I18n.t("launch.desktop_add"),
                    tint = Color.White.copy(alpha = if (count < MAX_DESKTOPS) 0.96f else 0.35f),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 28.dp, end = 28.dp, bottom = 24.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = (1f - progress) * 40.dp.toPx()
                    val settle = 0.96f + 0.04f * progress
                    scaleX = settle
                    scaleY = settle
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
        ) {
            AgentPanel(vm, onOpenSettings, onDismiss)
        }
    }
}

@Composable
private fun DesktopThumbnail(
    index: Int,
    selected: Boolean,
    thumbW: Dp,
    thumbH: Dp,
    canClose: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onSelect
        )
    ) {
        Box(
            Modifier
                .size(thumbW, thumbH)
                .hoverable(interaction)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(18.dp, RoundedCornerShape(ThumbRadius), clip = false, ambientColor = Color(0x66000000), spotColor = Color(0x88000000))
                    .clip(RoundedCornerShape(ThumbRadius))
                    .border(
                        width = if (selected) 3.dp else 0.dp,
                        color = if (selected) SelectedBorder else Color.Transparent,
                        shape = RoundedCornerShape(ThumbRadius)
                    )
            )
            if (canClose && hovered) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 6.dp, y = 6.dp)
                        .size(18.dp)
                        .shadow(2.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(Color(0xF01C1C1E))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = I18n.t("launch.desktop_close"),
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            I18n.t("launch.desktop_name", index + 1),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            style = androidx.compose.ui.text.TextStyle(
                shadow = Shadow(
                    color = Color(0x99000000),
                    offset = Offset(0f, 1f),
                    blurRadius = 3f
                )
            )
        )
    }
}

@Composable
private fun rememberDesktopStrip(count: Int, fullW: Float, fullH: Float): MeasuredStrip {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(count, fullW, fullH, density) {
        with(density) {
            val gapPx = 18.dp.toPx()
            val plusGapPx = 34.dp.toPx()
            val plusPx = 28.dp.toPx()
            val sidePx = 36.dp.toPx()
            val labelPx = 26.dp.toPx()
            val naturalW = 168.dp.toPx()
            val fixed = sidePx * 2 + plusGapPx + plusPx + gapPx * (count - 1).coerceAtLeast(0)
            val available = (fullW - fixed).coerceAtLeast(56.dp.toPx())
            val thumbW = min(naturalW, available / count.coerceAtLeast(1))
            val thumbH = thumbW * (fullH / fullW)
            val rowW = thumbW * count + gapPx * (count - 1).coerceAtLeast(0) + plusGapPx + plusPx
            val startX = ((fullW - rowW) / 2f).coerceAtLeast(12.dp.toPx())
            val imageTop = ((fullH - thumbH - labelPx) / 2f).coerceAtLeast(16.dp.toPx())
            MeasuredStrip(
                thumbW = thumbW,
                thumbH = thumbH,
                thumbWDp = thumbW.toDp(),
                thumbHDp = thumbH.toDp(),
                gap = 18.dp,
                plusGap = 34.dp,
                startX = startX.toDp(),
                imageTop = imageTop.toDp(),
                startXPx = startX,
                imageTopPx = imageTop,
                stridePx = thumbW + gapPx
            )
        }
    }
}

private class MeasuredStrip(
    val thumbW: Float,
    val thumbH: Float,
    val thumbWDp: Dp,
    val thumbHDp: Dp,
    val gap: Dp,
    val plusGap: Dp,
    val startX: Dp,
    val imageTop: Dp,
    val startXPx: Float,
    val imageTopPx: Float,
    val stridePx: Float
) {
    fun x(index: Int): Float = startXPx + index * stridePx
    val y: Float get() = imageTopPx
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

/**
 * 启动页向上拖，或滚到头之后继续上滑，打开调度中心。
 * 列表还没滑到底时，滚动留给列表。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.swipeUpForMissionControl(): Modifier = composed {
    val openMission = LocalOpenMissionControl.current
    val connection = remember(openMission) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < -48f) openMission()
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (available.y < -900f) openMission()
                return Velocity.Zero
            }
        }
    }
    this
        .nestedScroll(connection)
        .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Final) { event ->
            val change = event.changes.firstOrNull() ?: return@onPointerEvent
            if (change.isConsumed) return@onPointerEvent
            if (change.scrollDelta.y < -28f) openMission()
        }
        .pointerInput(openMission) {
            val trigger = 72.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dy = 0f
                var dx = 0f
                var opened = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.position - change.previousPosition
                    dy += delta.y
                    dx += delta.x
                    if (change.isConsumed && dy > -trigger) break
                    if (dy < -trigger && abs(dy) > abs(dx) * 1.15f) {
                        change.consume()
                        openMission()
                        opened = true
                        break
                    }
                }
                if (!opened) return@awaitEachGesture
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                }
            }
        }
}
