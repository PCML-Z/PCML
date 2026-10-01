package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
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
    accountName: String,
    accountDetail: String,
    buttonLabel: String,
    buttonEnabled: Boolean,
    busy: Boolean,
    showMusic: Boolean = false,
    music: @Composable () -> Unit = {},
    onLaunch: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val dark = LocalThemeState.current.useDark
    val ink = if (dark) Color.White else Color(0xFF1C1C1E)
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VersionWithActions(
                versionName = versionName,
                ink = ink,
                buttonLabel = buttonLabel,
                buttonEnabled = buttonEnabled,
                busy = busy,
                onLaunch = onLaunch,
                onOpenLibrary = onOpenLibrary,
                modifier = Modifier.weight(1f)
            )
            if (accountName.isNotBlank()) {
                Column(
                    Modifier.padding(start = 16.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        accountName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (accountDetail.isNotBlank()) {
                        Text(
                            accountDetail,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8E8E93),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
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
    modifier: Modifier = Modifier
) {
    val textStyle = LocalTextStyle.current.merge(
        TextStyle(color = ink, fontSize = 48.sp, fontWeight = FontWeight.Bold)
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
            LaunchActions(ink, buttonLabel, buttonEnabled, busy, onLaunch, onOpenLibrary)
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val overflow = textWidthPx + gap + actions.width > constraints.maxWidth
        val fadePx = if (overflow) actions.width + gap else 0
        val title = subcompose("title") {
            VersionTitle(versionName, textStyle, fadePx.toFloat(), onOpenLibrary)
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val height = maxOf(title.height, actions.height)
        layout(constraints.maxWidth, height) {
            title.place(0, (height - title.height) / 2)
            val x = if (overflow) {
                constraints.maxWidth - actions.width
            } else {
                (textWidthPx + gap).coerceAtMost(constraints.maxWidth - actions.width)
            }
            actions.place(x, (height - actions.height) / 2)
        }
    }
}

@Composable
private fun VersionTitle(
    versionName: String,
    textStyle: TextStyle,
    fadePx: Float,
    onOpenLibrary: () -> Unit
) {
    Box(Modifier.fillMaxWidth().clipToBounds().padding(vertical = 6.dp)) {
        if (fadePx > 0f) {
            Text(
                versionName,
                modifier = Modifier.fillMaxWidth().blurredTail(fadePx),
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
                .fadeOutEnd(fadePx)
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
    onOpenLibrary: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onOpenLibrary,
            colors = ButtonDefaults.textButtonColors(contentColor = ink),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(
                I18n.t("material.open_library"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
        }
        Spacer(Modifier.width(4.dp))
        Button(
            onClick = onLaunch,
            enabled = buttonEnabled && !busy,
            modifier = Modifier.height(32.dp).widthIn(max = 168.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp).width(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.height(16.dp).width(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    buttonLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

/** 名字右侧在按钮底下淡出，左边保持清晰。 */
private fun Modifier.fadeOutEnd(fadePx: Float): Modifier {
    if (fadePx <= 0f) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.width <= 0f) return@drawWithContent
            val start = ((size.width - fadePx) / size.width).coerceIn(0f, 1f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    start to Color.Black,
                    1f to Color.Transparent
                ),
                blendMode = BlendMode.DstIn
            )
        }
}

/** 只留下按钮覆盖的那一段，并把它虚化。 */
private fun Modifier.blurredTail(fadePx: Float): Modifier {
    return blur(12.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.width <= 0f) return@drawWithContent
            val start = ((size.width - fadePx) / size.width).coerceIn(0f, 1f)
            val mid = ((size.width - fadePx * 0.5f) / size.width).coerceIn(start, 1f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    start to Color.Transparent,
                    mid to Color.Black,
                    1f to Color.Black.copy(alpha = 0.55f)
                ),
                blendMode = BlendMode.DstIn
            )
        }
}
