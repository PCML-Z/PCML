package com.pmcl.ui.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.MotionTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SLIDE_CONFIRM_AT = 0.92f

/**
 * 拖到轨道尽头并松手才触发。中途松手会弹回，单击不会确认。
 */
@Composable
fun SlideToConfirm(
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = I18n.t("common.slide_to_delete"),
    height: Dp = 44.dp,
    square: Boolean = false,
    trackColor: Color = MaterialTheme.colorScheme.errorContainer,
    knobColor: Color = MaterialTheme.colorScheme.error,
    labelColor: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var travel by remember { mutableFloatStateOf(1f) }
    var finger by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf<Float?>(null) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val shown = when {
        dragging -> finger
        hold != null -> hold ?: 0f
        else -> progress.value
    }
    val ready = shown >= SLIDE_CONFIRM_AT
    val dark = labelColor.luminance() > 0.65f
    val ink = if (dark) Color.White.copy(alpha = 0.88f) else Color(0xFF1D1D1F)
    val shape = if (square) RoundedCornerShape(10.dp) else RoundedCornerShape(height / 2)
    val knobShape = if (square) RoundedCornerShape(8.dp) else CircleShape

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { size ->
                val slot = with(density) { height.toPx() }
                travel = (size.width - slot).coerceAtLeast(1f)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var moved = 0f
                    var origin = 0f
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        if (!dragging) {
                            settle?.cancel()
                            origin = hold ?: progress.value
                            hold = null
                            dragging = true
                        }
                        moved += over
                        finger = (origin + moved / travel.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    }
                    if (slop == null) {
                        if (dragging) {
                            dragging = false
                            finger = 0f
                        }
                        return@awaitEachGesture
                    }
                    val finished = horizontalDrag(slop.id) { change ->
                        change.consume()
                        moved += change.position.x - change.previousPosition.x
                        finger = (origin + moved / travel.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    }
                    val from = finger
                    val done = finished && from >= SLIDE_CONFIRM_AT
                    hold = from
                    dragging = false
                    finger = 0f
                    settle = scope.launch {
                        progress.snapTo(from)
                        hold = null
                        if (done) {
                            progress.animateTo(
                                1f,
                                tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingEmphasized),
                            )
                            onConfirmed()
                        } else {
                            progress.animateTo(0f, MotionTokens.springDefault())
                        }
                    }
                }
            },
    ) {
        val track = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.62f)
        val edge = if (dark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.85f)
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(track)
                .background(trackColor.copy(alpha = if (dark) 0.08f else 0.12f))
                .border(0.5.dp, edge, shape),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = if (dark) 0.18f else 0.7f)),
            )
            if (shown > 0.02f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(shown.coerceIn(0.001f, 1f))
                        .background(knobColor.copy(alpha = if (ready) 0.38f else 0.18f)),
                )
            }
        }
        Text(
            label,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = height),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = ink.copy(alpha = if (ready) 0.95f else (1f - shown * 0.45f).coerceIn(0.55f, 1f)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            Modifier
                .offset { IntOffset((shown * travel).roundToInt(), 0) }
                .size(height)
                .padding(3.dp)
                .shadow(2.dp, knobShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.08f))
                .clip(knobShape)
                .background(if (ready) knobColor else if (dark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.94f))
                .border(0.5.dp, Color.White.copy(alpha = if (dark) 0.35f else 0.9f), knobShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ready) Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (ready) Color.White else ink.copy(alpha = 0.72f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 取消仍是按钮。确认只能把滑块拖到尽头。 */
@Composable
fun DangerConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(16.dp))
                SlideToConfirm(onConfirmed = onConfirm)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
        },
    )
}
