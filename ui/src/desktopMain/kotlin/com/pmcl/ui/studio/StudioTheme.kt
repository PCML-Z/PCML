package com.pmcl.ui.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor
import kotlinx.coroutines.delay

import kotlin.math.roundToInt

internal data class StudioPalette(
    val dark: Boolean,
    val window: Color,
    val activity: Color,
    val panel: Color,
    val panelAlt: Color,
    val strip: Color,
    val field: Color,
    val hover: Color,
    val selected: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color,
    val editor: Color,
    val gutter: Color,
    val error: Color,
    val warn: Color,
    val ok: Color,
    val synKey: Color,
    val synString: Color,
    val synNumber: Color,
    val synConst: Color,
    val synComment: Color,
    val synSection: Color,
)

/**
 * 色块只用一组蓝灰色：主色、深浅两档蓝、两档蓝灰、两档石墨。
 * 绿色只给“运行”，红色只给错误和危险操作，琥珀色只给警告和“运行中”。
 */
internal object Tiles {
    val Blue = Color(0xFF2B579A)
    val Navy = Color(0xFF1E3F73)
    val Sky = Color(0xFF4472C4)
    val Teal = Color(0xFF24497F)
    val Plum = Color(0xFF3A4A63)
    val Slate = Color(0xFF4A5568)
    val Graphite = Color(0xFF2F3640)
    val Gold = Color(0xFF5A6472)
    val Purple = Color(0xFF34558B)

    val Forest = Color(0xFF217346)
    val Red = Color(0xFFA4373A)
    val Orange = Color(0xFFC55A11)
}

internal fun studioPalette(dark: Boolean): StudioPalette = if (dark) {
    StudioPalette(
        dark = true,
        window = Color(0xFF161616),
        activity = Color(0xFF0F1B2E),
        panel = Color(0xFF232323),
        panelAlt = Color(0xFF282828),
        strip = Color(0xFF1C1C1C),
        field = Color(0xFF333333),
        hover = Color(0xFF3A3A3A),
        selected = Color(0xFF1F3A5F),
        text = Color(0xFFF2F2F2),
        muted = Color(0xFFA0A0A0),
        accent = Color(0xFF2B579A),
        accentDeep = Color(0xFF1B3A6B),
        onAccent = Color.White,
        editor = Color(0xFF1B1B1B),
        gutter = Color(0xFF666666),
        error = Color(0xFFF1707B),
        warn = Color(0xFFF2C55C),
        ok = Color(0xFF6CCB5F),
        synKey = Color(0xFF7CB0F5),
        synString = Color(0xFF94D49A),
        synNumber = Color(0xFFF2A672),
        synConst = Color(0xFFD29BF2),
        synComment = Color(0xFF7A7A7A),
        synSection = Color(0xFF4FD1D9),
    )
} else {
    StudioPalette(
        dark = false,
        window = Color(0xFFE4E4E4),
        activity = Color(0xFF1E3F73),
        panel = Color(0xFFF5F5F5),
        panelAlt = Color(0xFFEDEDED),
        strip = Color(0xFFDADADA),
        field = Color(0xFFE6E6E6),
        hover = Color(0xFFD5D5D5),
        selected = Color(0xFFCFE0F7),
        text = Color(0xFF201F1E),
        muted = Color(0xFF605E5C),
        accent = Color(0xFF2B579A),
        accentDeep = Color(0xFF1E3F73),
        onAccent = Color.White,
        editor = Color(0xFFFFFFFF),
        gutter = Color(0xFF9A9A9A),
        error = Color(0xFFA4262C),
        warn = Color(0xFF8A5A00),
        ok = Color(0xFF107C10),
        synKey = Color(0xFF2B579A),
        synString = Color(0xFF217346),
        synNumber = Color(0xFFC43E1C),
        synConst = Color(0xFF7719AA),
        synComment = Color(0xFF8A8A8A),
        synSection = Color(0xFF038387),
    )
}

internal val LocalStudio = staticCompositionLocalOf { studioPalette(false) }

@Composable
internal fun StudioColors(dark: Boolean, content: @Composable () -> Unit) {
    val palette = remember(dark) { studioPalette(dark) }
    CompositionLocalProvider(LocalStudio provides palette, content = content)
}

internal val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/** 色块上用白字还是深色字。 */
internal fun inkOn(color: Color): Color = if (color.luminance() > 0.45f) Color(0xFF201F1E) else Color.White

internal fun Color.lift(amount: Float): Color = Color.White.copy(alpha = amount).compositeOver(this)

@Composable
internal fun FlatText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalStudio.current.text,
    size: Int = 12,
    weight: FontWeight = FontWeight.Normal,
    lines: Int = 1,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        // bodySmall 的行高只有十几 sp，大字号换行时两行会叠在一起
        lineHeight = (size * 1.35f).sp,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall
    )
}

/** 卡片入场：按 index 错开，淡入并向上滑。 */
@Composable
internal fun Modifier.entrance(index: Int, enabled: Boolean = true): Modifier {
    // 长列表滚动到后面的行不再做入场，否则一滚动就闪
    if (!enabled || index > 24) return this
    // 播过一次就记下来：列表行滚出再滚回、标签页切走再切回都不重播
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        delay((index.coerceAtLeast(0) * 28).toLong())
        progress.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        played = true
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 18.dp.toPx()
    }
}

/** 悬停微微放大、按下回弹，悬停时提到相邻色块上面。 */
@Composable
internal fun Modifier.cardMotion(source: MutableInteractionSource, enabled: Boolean, lift: Float = 1.03f): Modifier {
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            !enabled -> 1f
            pressed -> 0.96f
            hovered -> lift
            else -> 1f
        },
        spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "cardScale"
    )
    return this.zIndex(if (hovered && enabled) 1f else 0f).graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 整数就从 0 数上去，其他文字换值时上下滑动切换。 */
@Composable
internal fun AnimatedValue(
    value: String,
    color: Color,
    size: Int,
    weight: FontWeight = FontWeight.SemiBold,
    lines: Int = 2,
    modifier: Modifier = Modifier,
) {
    val number = value.toIntOrNull()
    if (number != null && number in 0..1_000_000) {
        // 只从上次停下的数继续滚，切回标签页时不再从 0 重数
        var settled by rememberSaveable { mutableStateOf(-1) }
        val shown = remember { Animatable(if (settled >= 0) settled.toFloat() else 0f) }
        LaunchedEffect(number) {
            if (shown.value.roundToInt() != number) {
                shown.animateTo(number.toFloat(), tween(if (number > 0) 720 else 0, easing = FastOutSlowInEasing))
            }
            settled = number
        }
        FlatText(shown.value.roundToInt().toString(), color = color, size = size, weight = weight, lines = lines, modifier = modifier)
    } else {
        AnimatedContent(
            targetState = value,
            modifier = modifier.clipToBounds(),
            transitionSpec = {
                (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                    (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(200)))
            },
            label = "tileValue"
        ) { v ->
            FlatText(v, color = color, size = size, weight = weight, lines = lines, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 色块底部的高亮条：悬停或选中时从左向右长出来。 */
@Composable
internal fun GrowBar(visible: Boolean, color: Color, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val fraction by animateFloatAsState(if (visible) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "growBar")
    Box(modifier.fillMaxWidth(fraction).height(height).background(color))
}

/**
 * 纯色块按钮。fill 为空时是透明块，悬停时铺底色；有 fill 时就是一整块颜色。
 * 所有按钮都没有圆角、边框和阴影，相邻摆放即可拼成一条色带。
 */
@Composable
internal fun Block(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fill: Color? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    height: Dp = 28.dp,
) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val base = fill ?: if (selected) p.selected else Color.Transparent
    val target = when {
        !enabled && fill != null -> fill.copy(alpha = 0.35f)
        !enabled -> Color.Transparent
        pressed && fill != null -> fill.lift(0.24f)
        hovered && fill != null -> fill.lift(0.14f)
        pressed -> p.selected
        hovered -> p.hover
        else -> base
    }
    val bg by animateColorAsState(target, tween(140), label = "blockBg")
    val fg = when {
        fill != null -> inkOn(fill).copy(alpha = if (enabled) 1f else 0.6f)
        !enabled -> p.muted.copy(alpha = 0.5f)
        else -> p.text
    }
    Row(
        modifier
            .height(height)
            .background(bg)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label.ifEmpty { null }, tint = fg, modifier = Modifier.size(15.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (label.isNotEmpty()) FlatText(label, color = fg)
    }
}

/** 命令色块：图标在上、文字在下，整块一个颜色。悬停时图标上浮，选中时底部长出高亮条。 */
@Composable
internal fun CommandBlock(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    width: Dp = 84.dp,
    index: Int = 0,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val target = when {
        !enabled -> color.copy(alpha = 0.35f)
        active -> color.lift(0.2f)
        hovered -> color.lift(0.12f)
        else -> color
    }
    val bg by animateColorAsState(target, tween(160), label = "commandBg")
    val iconShift by animateDpAsState(if (hovered && enabled) (-3).dp else 0.dp, spring(0.55f, 600f), label = "commandIcon")
    val ink = inkOn(color).copy(alpha = if (enabled) 1f else 0.55f)
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .entrance(index)
            .cardMotion(source, enabled, 1.04f)
            .background(bg)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.offset(y = iconShift).size(20.dp))
            FlatText(label, color = ink, size = 11, weight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        }
        GrowBar(active || (hovered && enabled), Color.White.copy(alpha = if (active) 0.9f else 0.45f), Modifier.align(Alignment.BottomStart))
    }
}

/** 磁贴：大号数值 + 小标题，整块一个颜色。带入场、悬停放大、按下回弹和数值滚动动画。 */
@Composable
internal fun ColorTile(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    valueSize: Int = 20,
    index: Int = 0,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val clickable = onClick != null
    val bg by animateColorAsState(if (hovered && clickable) color.lift(0.1f) else color, tween(180), label = "tileBg")
    val iconShift by animateDpAsState(if (hovered && clickable) 4.dp else 0.dp, spring(0.55f, 600f), label = "tileIcon")
    val ink = inkOn(color)
    Box(
        modifier
            .entrance(index)
            .cardMotion(source, clickable)
            .background(bg)
            .clipToBounds()
            .hoverable(source)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                else Modifier
            )
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = ink.copy(alpha = 0.9f), modifier = Modifier.offset(x = iconShift).size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                if (title.isNotEmpty()) FlatText(title, color = ink.copy(alpha = 0.82f), size = 11)
            }
            if (value.isNotEmpty()) AnimatedValue(value, ink, valueSize, modifier = Modifier.fillMaxWidth())
        }
        if (clickable) GrowBar(hovered, Color.White.copy(alpha = 0.55f), Modifier.align(Alignment.BottomStart))
    }
}

/** 输入框：一块比底色稍深的色块，没有边框。 */
@Composable
internal fun FlatField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    focus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val p = LocalStudio.current
    val bg = color ?: p.field
    val ink = if (color != null) inkOn(color) else p.text
    Row(
        modifier.height(28.dp).background(bg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) FlatText(placeholder, color = ink.copy(alpha = 0.55f))
            var fieldModifier = Modifier.fillMaxWidth()
            if (focus != null) fieldModifier = fieldModifier.focusRequester(focus)
            if (onKey != null) fieldModifier = fieldModifier.onPreviewKeyEvent(onKey)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = ink, fontSize = 12.sp),
                cursorBrush = SolidColor(ink),
                modifier = fieldModifier
            )
        }
    }
}

/** 面板标题：一整条色块。 */
@Composable
internal fun PanelHeader(title: String, color: Color? = null, actions: @Composable () -> Unit = {}) {
    val p = LocalStudio.current
    val bg = color ?: p.strip
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(bg).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlatText(
            title,
            color = if (color != null) inkOn(color) else p.text,
            size = 12,
            weight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/** 可拖动的分隔：本身就是窗口底色的一道缝，两侧色块靠颜色区分。 */
@Composable
internal fun Splitter(horizontal: Boolean, onDrag: (Dp) -> Unit) {
    val p = LocalStudio.current
    val density = LocalDensity.current
    val cursor = PointerIcon(Cursor(if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR))
    val base = if (horizontal) Modifier.width(3.dp).fillMaxHeight() else Modifier.height(3.dp).fillMaxWidth()
    Box(
        base
            .background(p.window)
            .pointerHoverIcon(cursor)
            .pointerInput(horizontal) {
                detectDragGestures { change, amount ->
                    change.consume()
                    val px = if (horizontal) amount.x else amount.y
                    onDrag(with(density) { px.toDp() })
                }
            }
    )
}

/** 活动栏里的方块按钮。选中时左侧长出一道白条。 */
@Composable
internal fun ActivityBlock(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            active -> p.accent
            hovered -> p.activity.lift(0.1f)
            else -> p.activity
        },
        tween(160),
        label = "activityBg"
    )
    val bar by animateDpAsState(if (active) 3.dp else 0.dp, tween(200), label = "activityBar")
    Box(
        Modifier
            .size(44.dp)
            .background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.align(Alignment.CenterStart).width(bar).fillMaxHeight().background(Color.White))
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Color.White else Color.White.copy(alpha = if (hovered) 0.9f else 0.62f),
            modifier = Modifier.size(20.dp)
        )
    }
}
