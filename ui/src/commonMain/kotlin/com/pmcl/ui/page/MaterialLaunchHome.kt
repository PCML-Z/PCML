package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.LocalThemeState

@Composable
fun MaterialLaunchHome(
    versionName: String,
    buttonLabel: String,
    buttonEnabled: Boolean,
    busy: Boolean,
    showMusic: Boolean = false,
    music: @Composable () -> Unit = {},
    onLaunch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenStudio: (() -> Unit)? = null
) {
    val dark = LocalThemeState.current.useDark
    val ink = if (dark) Color.White else Color(0xFF1C1C1E)
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        VersionWithActions(
            versionName = versionName,
            ink = ink,
            buttonLabel = buttonLabel,
            buttonEnabled = buttonEnabled,
            busy = busy,
            onLaunch = onLaunch,
            onOpenLibrary = onOpenLibrary,
            onOpenStudio = onOpenStudio
        )
        AnimatedVisibility(
            visible = showMusic,
            enter = expandVertically(tween(320)) + fadeIn(tween(320)),
            exit = shrinkVertically(tween(260)) + fadeOut(tween(200))
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                music()
            }
        }
    }
}

@Composable
private fun VersionWithActions(
    versionName: String,
    ink: Color,
    buttonLabel: String,
    buttonEnabled: Boolean,
    busy: Boolean,
    onLaunch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenStudio: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val textStyle = LocalTextStyle.current.merge(
        TextStyle(color = ink, fontSize = 64.sp, fontWeight = FontWeight.Bold)
    )
    val measurer = rememberTextMeasurer()
    val textWidthPx = remember(versionName, textStyle) {
        measurer.measure(
            versionName,
            style = textStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        ).size.width
    }
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val gap = 12.dp.roundToPx()
        val actions = subcompose("actions") {
            LaunchActions(ink, buttonLabel, buttonEnabled, busy, onLaunch, onOpenLibrary, onOpenStudio)
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val x = (textWidthPx + gap).coerceAtMost(constraints.maxWidth - actions.width).coerceAtLeast(0)
        val fadeEnd = minOf(textWidthPx, constraints.maxWidth).toFloat()
        val covered = fadeEnd - (x - gap)
        val fade = if (covered > 0f) {
            val t = (covered / 32.dp.toPx()).coerceIn(0f, 1f)
            val length = maxOf(covered, (actions.width + gap) * t * t * (3f - 2f * t))
            FadeSpan((fadeEnd - length).coerceAtLeast(0f), fadeEnd)
        } else null
        val title = subcompose("title") {
            VersionTitle(versionName, textStyle, fade, onOpenLibrary)
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val height = maxOf(title.height, actions.height)
        layout(constraints.maxWidth, height) {
            title.place(0, (height - title.height) / 2)
            actions.place(x, (height - actions.height) / 2)
        }
    }
}

@Composable
private fun VersionTitle(
    versionName: String,
    textStyle: TextStyle,
    fade: FadeSpan?,
    onOpenLibrary: () -> Unit
) {
    Box(Modifier.fillMaxWidth().clipToBounds().padding(vertical = 6.dp)) {
        if (fade != null) {
            Text(
                versionName,
                modifier = Modifier.fillMaxWidth().blurredTail(fade),
                style = textStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
        Text(
            versionName,
            modifier = Modifier
                .fillMaxWidth()
                .fadeOutEnd(fade)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenLibrary
                ),
            style = textStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun LaunchActions(
    ink: Color,
    buttonLabel: String,
    buttonEnabled: Boolean,
    busy: Boolean,
    onLaunch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenStudio: (() -> Unit)?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onOpenStudio != null) {
            StudioHoverButton(onOpenStudio)
            Spacer(Modifier.width(4.dp))
        }
        TextButton(
            onClick = onOpenLibrary,
            modifier = Modifier.height(44.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = ink),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
        ) {
            Text(
                I18n.t("material.open_library"),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onLaunch,
            enabled = buttonEnabled && !busy,
            modifier = Modifier.height(44.dp).widthIn(max = 240.dp),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.height(20.dp).width(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    buttonLabel,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
            }
        }
    }
}

/** 平时是一颗 44dp 的圆形图标，悬停时向右展开出文字。 */
@Composable
private fun StudioHoverButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val label = I18n.t("launch.try_studio")
    val base = Color(0xFF2B579A)
    val fill by animateColorAsState(if (hovered) base else base.copy(alpha = 0.86f), tween(220), label = "studioFill")
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(160), label = "studioScale")
    val turn by animateFloatAsState(if (hovered) -90f else 0f, tween(320, easing = FastOutSlowInEasing), label = "studioTurn")
    Row(
        Modifier
            .height(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(22.dp))
            .background(fill)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Dashboard,
            contentDescription = if (hovered) null else label,
            tint = Color.White,
            modifier = Modifier.size(20.dp).rotate(turn)
        )
        AnimatedVisibility(
            visible = hovered,
            enter = expandHorizontally(tween(280, easing = FastOutSlowInEasing), expandFrom = Alignment.Start) +
                fadeIn(tween(200, delayMillis = 80)),
            exit = shrinkHorizontally(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Start) +
                fadeOut(tween(120))
        ) {
            Text(
                label,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
            )
        }
    }
}

/** 名字的虚化段，单位 px：止于名字末尾（或行尾），长度与按钮组加间隙相同。 */
private data class FadeSpan(val start: Float, val end: Float)

/** 名字右侧在按钮底下淡出，左边保持清晰。 */
private fun Modifier.fadeOutEnd(fade: FadeSpan?): Modifier {
    if (fade == null) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.width <= 0f) return@drawWithContent
            val start = (fade.start / size.width).coerceIn(0f, 1f)
            val end = (fade.end / size.width).coerceIn(start, 1f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    start to Color.Black,
                    end to Color.Transparent,
                    1f to Color.Transparent
                ),
                blendMode = BlendMode.DstIn
            )
        }
}

/** 只留下按钮覆盖的那一段，并把它虚化。 */
private fun Modifier.blurredTail(fade: FadeSpan): Modifier {
    return blur(12.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.width <= 0f) return@drawWithContent
            val start = (fade.start / size.width).coerceIn(0f, 1f)
            val end = (fade.end / size.width).coerceIn(start, 1f)
            val mid = (start + end) / 2f
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    start to Color.Transparent,
                    mid to Color.Black,
                    end to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Black.copy(alpha = 0.55f)
                ),
                blendMode = BlendMode.DstIn
            )
        }
}
