package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.LocalPortrait
import com.pmcl.ui.animation.LocalPortraitOwnsTitle
import com.pmcl.ui.animation.MotionTokens
import com.pmcl.ui.animation.PortraitStage
import com.pmcl.ui.navigation.NavDestination
import com.pmcl.ui.navigation.PmclIcons
import com.pmcl.ui.navigation.SecondarySection
import com.pmcl.ui.navigation.pmclWindowIcon
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.launch
import com.pmcl.ui.widget.pmclHorizontalScroll
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val Paper = Color(0xFFF4F7FB)
private val Ink = Color(0xFF1C1C1E)
private val Muted = Color(0xFF8B919A)
private val Line = Color(0xFFE3E8EF)
private val CardBlue = Color(0xFFE7F2FB)
private val Ring = Color(0xFF8FC0F6)
private val RingSoft = Color(0xFFD3E6FA)
private val Pill = Color(0xFFF3F6F9)
private val PillShape = RoundedCornerShape(20.dp)
private val ChipShape = RoundedCornerShape(50)

/** 竖屏一帧的导航快照。切页时退出中的那一帧继续用离开前的目标，避免两页都画成新页面。 */
class PortraitPane<T>(
    val home: Boolean,
    val payload: T,
    val title: String,
    val sections: List<SecondarySection>,
    val sectionId: String?,
    val dock: NavDestination?,
)

/**
 * 竖屏主界面。纸面、圆环和白胶囊是这一页自己的，不跟主题色走。
 * 搜索和底栏留在原地，只有中间内容按进入方向推进。
 */
@Composable
fun <T> PortraitShell(
    vm: LauncherViewModel,
    pane: PortraitPane<T>,
    stageKey: Any,
    motion: Int,
    searchFocusRequester: FocusRequester,
    onOpenTaskCenter: () -> Unit,
    onBack: () -> Unit,
    onSelectSection: (String) -> Unit,
    onOpen: (NavDestination, String) -> Unit,
    onDock: (NavDestination) -> Unit,
    drawerOpen: Boolean,
    onDismissDrawer: () -> Unit,
    snackbarHostState: SnackbarHostState,
    page: @Composable (T, String?) -> Unit,
) {
    val snaps = remember { HashMap<Any, PortraitPane<T>>() }
    SideEffect { snaps[stageKey] = pane }

    CompositionLocalProvider(LocalPortrait provides true) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            PortraitTopBar(vm, searchFocusRequester, onBack, onOpenTaskCenter)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                PortraitStage(stageKey, motion, Modifier.fillMaxSize()) { key ->
                    val snap = if (key == stageKey) pane else snaps[key]
                    if (snap != null) {
                        if (snap.home) PortraitHomeBody(vm, onDock)
                        else PortraitPageBody(vm, snap, searchFocusRequester, onBack, onSelectSection, onOpen, page)
                    }
                }
                PortraitDrawerLayer(drawerOpen, onDismissDrawer) {
                    PortraitDrawerSheet(vm, onDismissDrawer)
                }
                SnackbarHost(
                    snackbarHostState,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                )
            }
            if (pane.home && !drawerOpen) {
                PortraitDock(dock = null, onDock = onDock)
                PortraitLaunchSlot(vm, visible = true, onOpenDrawer = { onDock(NavDestination.Instances) })
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun PortraitDrawerLayer(
    open: Boolean,
    onDismiss: () -> Unit,
    sheet: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (open) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x33000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
        AnimatedVisibility(
            visible = open,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .graphicsLayer {
                    clip = true
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                },
            enter = slideInVertically(
                tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasizedDecelerate)
            ) { it } + fadeIn(tween(MotionTokens.DURATION_SHORT)),
            exit = slideOutVertically(
                tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasizedAccelerate)
            ) { it } + fadeOut(tween(MotionTokens.DURATION_SHORT))
        ) {
            sheet()
        }
    }
}

@Composable
private fun PortraitTopBar(
    vm: LauncherViewModel,
    searchFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onOpenTaskCenter: () -> Unit,
) {
    val queue by vm.queueSummary.collectAsState()
    val tasks = queue.active()
    Row(verticalAlignment = Alignment.CenterVertically) {
        PortraitMark(onClick = onBack)
        Spacer(Modifier.width(8.dp))
        MaterialTheme(colorScheme = lightColorScheme(
            primary = Color(0xFF3B82C4),
            onSurface = Ink,
            onSurfaceVariant = Muted,
            surface = Color.White,
            surfaceVariant = Color(0xFFEEF2F6)
        )) {
            TopBarSearchField(
                modifier = Modifier.weight(1f),
                vm = vm,
                focusRequester = searchFocusRequester,
                pill = true
            )
        }
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onOpenTaskCenter, modifier = Modifier.size(34.dp)) {
            BadgedBox(badge = { PortraitTaskBadge(tasks) }) {
                Icon(Icons.Filled.Notifications, contentDescription = I18n.t("nav.download"), tint = Ink)
            }
        }
    }
}

@Composable
private fun PortraitTaskBadge(tasks: Int) {
    AnimatedVisibility(
        visible = tasks > 0,
        enter = fadeIn(tween(MotionTokens.DURATION_SHORT)) +
            androidx.compose.animation.scaleIn(tween(MotionTokens.DURATION_SHORT), initialScale = 0.7f),
        exit = fadeOut(tween(MotionTokens.DURATION_SHORT))
    ) {
        Badge(containerColor = Color(0xFF3B82F6)) {
            Text(tasks.coerceAtMost(9).toString())
        }
    }
}

@Composable
private fun PortraitHomeBody(vm: LauncherViewModel, onDock: (NavDestination) -> Unit) {
    val account by vm.account.collectAsState()
    val name = account?.username?.takeIf { it.isNotBlank() } ?: I18n.t("portrait.no_account")
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    I18n.t("portrait.hello", name),
                    color = Ink,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 36.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    I18n.t("portrait.launch_using", name),
                    color = Muted,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            PortraitAvatar(onClick = { onDock(NavDestination.Accounts) })
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            PortraitRings()
        }
    }
}

@Composable
private fun <T> PortraitPageBody(
    vm: LauncherViewModel,
    snap: PortraitPane<T>,
    searchFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onSelectSection: (String) -> Unit,
    onOpen: (NavDestination, String) -> Unit,
    page: @Composable (T, String?) -> Unit,
) {
    val section = snap.sections.firstOrNull { it.id == snap.sectionId }
    val atRoot = snap.sectionId == null
    when {
        atRoot && snap.dock == NavDestination.Settings -> PortraitGroupedIndex(
            title = snap.title,
            subtitle = I18n.t("portrait.version_line", vm.core.launcherVersion()),
            groups = portraitGroups(snap.sections),
            onBack = onBack,
            onOpen = onSelectSection
        )
        atRoot && snap.dock == NavDestination.Accounts -> PortraitGroupedIndex(
            title = snap.title,
            subtitle = null,
            groups = listOf(I18n.t("portrait.group.account") to snap.sections),
            onBack = onBack,
            onOpen = onSelectSection
        )
        atRoot && snap.dock == NavDestination.Download -> PortraitResourceHub(
            vm = vm,
            title = snap.title,
            onBack = onBack,
            onOpen = onOpen,
            onSearch = { searchFocusRequester.requestFocus() }
        )
        snap.dock == NavDestination.Settings && snap.sectionId == "java" -> PortraitJavaPage(vm, onBack)
        snap.dock == NavDestination.Settings && snap.sectionId == "launcher" -> PortraitLauncherPage(vm, onBack)
        else -> Column(Modifier.fillMaxSize()) {
            val marketTitle = marketSectionTitleKey(snap.sectionId)
            PortraitBigHeader(
                title = when {
                    marketTitle != null -> I18n.t(marketTitle)
                    section != null -> I18n.t(section.labelKey)
                    else -> snap.title
                },
                subtitle = null,
                onBack = onBack
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = Color(0xFF3B82C4),
                        onSurface = PortraitInk,
                        onSurfaceVariant = PortraitMuted,
                        surface = Color.White,
                        background = Color.Transparent
                    )
                ) {
                    CompositionLocalProvider(LocalPortraitOwnsTitle provides true) {
                        page(snap.payload, snap.sectionId)
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitLaunchSlot(vm: LauncherViewModel, visible: Boolean, onOpenDrawer: () -> Unit) {
    val version by vm.selectedVersion.collectAsState()
    val running by vm.gameRunning.collectAsState()
    val versionName = version?.takeIf { it.isNotBlank() }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasizedDecelerate)) +
            slideInVertically(
                tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasizedDecelerate)
            ) { it / 3 },
        exit = fadeOut(tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingEmphasizedAccelerate)) +
            slideOutVertically(
                tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasizedAccelerate)
            ) { it / 3 }
    ) {
        Column {
            Spacer(Modifier.height(10.dp))
            PortraitLaunchCard(
                title = versionName ?: I18n.t("portrait.no_version"),
                hint = when {
                    versionName == null -> I18n.t("portrait.no_version_hint")
                    running -> I18n.t("portrait.running")
                    else -> versionName
                },
                onOpen = {
                    if (versionName != null && !running) vm.launch() else onOpenDrawer()
                },
                onChevron = onOpenDrawer
            )
        }
    }
}

@Composable
private fun PortraitMark(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = portraitPressScale(interaction)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        modifier = Modifier.size(28.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        Image(
            painter = pmclWindowIcon(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun PortraitAvatar(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = portraitPressScale(interaction)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = CircleShape,
        color = Color(0xFFF7F8FA),
        border = BorderStroke(2.dp, Color.White),
        shadowElevation = 1.dp,
        modifier = Modifier.size(52.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(PmclIcons.Accounts, contentDescription = I18n.t("nav.accounts"), tint = Color(0xFF5C6570), modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun PortraitRings() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width * 0.62f, size.height * 0.46f)
        val rings = floatArrayOf(0.18f, 0.34f, 0.52f, 0.72f, 0.94f)
        rings.forEachIndexed { i, scale ->
            drawCircle(
                color = if (i % 2 == 0) Ring.copy(alpha = 0.28f) else RingSoft.copy(alpha = 0.7f),
                radius = size.minDimension * scale,
                center = c,
                style = Stroke(width = if (i == 2) 2.4.dp.toPx() else 1.6.dp.toPx())
            )
        }
        val x0 = size.width * 0.72f
        repeat(5) { i ->
            val y = size.height * (0.18f + i * 0.12f)
            drawLine(
                color = RingSoft,
                start = Offset(x0 + i * 10.dp.toPx(), y),
                end = Offset(size.width + 8.dp.toPx(), y + size.height * 0.22f),
                strokeWidth = 1.4.dp.toPx()
            )
        }
    }
}

@Composable
private fun PortraitDock(dock: NavDestination?, onDock: (NavDestination) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            shape = shape,
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(6.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x12000000))
                .border(1.dp, Line, shape)
        ) {
            PortraitSelectionPill(
                selectedIndex = dockIndex(dock),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                pillHeight = 40.dp,
                shape = PillShape,
                color = Pill
            ) { slot ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DockIcon(PmclIcons.Settings, I18n.t("nav.settings"), Modifier.slot(0)) {
                        onDock(NavDestination.Settings)
                    }
                    DockLabel(
                        PmclIcons.Globe,
                        I18n.t("portrait.dock.resources"),
                        Modifier.weight(1f).slot(1)
                    ) { onDock(NavDestination.Download) }
                    DockLabel(
                        PmclIcons.Grid,
                        I18n.t("portrait.dock.drawer"),
                        Modifier.weight(1f).slot(2)
                    ) { onDock(NavDestination.Instances) }
                    DockIcon(PmclIcons.Accounts, I18n.t("nav.accounts"), Modifier.slot(3)) {
                        onDock(NavDestination.Accounts)
                    }
                }
            }
        }
    }
}

private fun dockIndex(dest: NavDestination?): Int = when (dest) {
    NavDestination.Settings -> 0
    NavDestination.Download -> 1
    NavDestination.Instances -> 2
    NavDestination.Accounts -> 3
    else -> -1
}

@Composable
private fun DockIcon(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = portraitPressScale(interaction)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = modifier.size(40.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Ink, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DockLabel(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = portraitPressScale(interaction)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = PillShape,
        color = Color.Transparent,
        modifier = modifier.height(40.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PortraitChips(
    sections: List<SecondarySection>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val selected = sections.indexOfFirst { it.id == selectedId }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Box(Modifier.fillMaxWidth().pmclHorizontalScroll(rememberScrollState())) {
            PortraitSelectionPill(
                selectedIndex = selected,
                pillHeight = 30.dp,
                shape = ChipShape,
                color = Color.White,
                border = BorderStroke(1.dp, Line)
            ) { slot ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sections.forEachIndexed { index, section ->
                        val on = section.id == selectedId
                        val color by animateColorAsState(
                            if (on) Ink else Muted,
                            tween(MotionTokens.DURATION_SHORT),
                            label = "portraitChip"
                        )
                        Text(
                            I18n.t(section.labelKey),
                            color = color,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .slot(index)
                                .clip(ChipShape)
                                .clickable { onSelect(section.id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitLaunchCard(title: String, hint: String, onOpen: () -> Unit, onChevron: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = portraitPressScale(interaction, pressedScale = 0.985f)
    Surface(
        onClick = onOpen,
        interactionSource = interaction,
        shape = RoundedCornerShape(20.dp),
        color = CardBlue,
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GrassTile()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hint, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onChevron, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = I18n.t("portrait.dock.drawer"),
                    tint = Color(0xFFB0B6BE)
                )
            }
        }
    }
}

@Composable
private fun GrassTile() {
    Canvas(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))) {
        drawRect(Color(0xFF8D6A43))
        drawRect(Color(0xFF67A33A), size = Size(size.width, size.height * 0.46f))
        drawRect(Color(0xFF4F8A2C), topLeft = Offset(0f, size.height * 0.40f), size = Size(size.width, size.height * 0.06f))
        val cell = size.width / 4f
        val dark = Color(0xFF3E7424)
        val light = Color(0xFF7CB84A)
        for (x in 0 until 4) {
            drawRect(if (x % 2 == 0) dark else light, topLeft = Offset(x * cell, cell * 0.35f), size = Size(cell * 0.7f, cell * 0.45f))
        }
    }
}

@Composable
private fun portraitPressScale(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.96f,
): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        MotionTokens.springDefault(),
        label = "portraitPress"
    )
    return scale
}

/**
 * 选中条按子项实测滑动。子项坐标相对中间的 Row，Row 贴在轨道左上角。
 * 第一次出现直接落上，之后才滑动。
 */
@Composable
private fun PortraitSelectionPill(
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    pillHeight: Dp,
    shape: Shape,
    color: Color,
    border: BorderStroke? = null,
    content: @Composable (slot: Modifier.(Int) -> Modifier) -> Unit,
) {
    val slots = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val measured = slots[selectedIndex]
    if (measured != null) hold = measured
    val place = if (selectedIndex >= 0) measured ?: hold else hold
    val alpha by animateFloatAsState(
        if (selectedIndex >= 0 && measured != null) 1f else 0f,
        tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingStandard),
        label = "portraitPill"
    )
    LaunchedEffect(selectedIndex, place?.first, place?.second) {
        val target = place ?: return@LaunchedEffect
        if (selectedIndex < 0) return@LaunchedEffect
        if (!placed) {
            x.snapTo(target.first)
            w.snapTo(target.second)
            placed = true
        } else {
            coroutineScope {
                launch { x.animateTo(target.first, MotionTokens.tweenEmphasized()) }
                launch { w.animateTo(target.second, MotionTokens.tweenEmphasized()) }
            }
        }
    }
    val drawX = if (placed) x.value else (place?.first ?: 0f)
    val drawW = if (placed) w.value else (place?.second ?: 0f)
    Box(modifier) {
        if (place != null && drawW > 0f) {
            Box(Modifier.matchParentSize().zIndex(0f), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier
                        .offset { IntOffset(drawX.roundToInt(), 0) }
                        .width(with(androidx.compose.ui.platform.LocalDensity.current) { drawW.toDp() })
                        .height(pillHeight)
                        .graphicsLayer { this.alpha = alpha }
                        .background(color, shape)
                        .then(if (border != null) Modifier.border(border, shape) else Modifier)
                )
            }
        }
        Box(Modifier.zIndex(1f)) {
            content { index ->
                this.onGloballyPositioned { coords ->
                    val next = coords.positionInParent().x to coords.size.width.toFloat()
                    val old = slots[index]
                    if (old == null || abs(old.first - next.first) > 0.5f || abs(old.second - next.second) > 0.5f) {
                        slots[index] = next
                    }
                }
            }
        }
    }
}
