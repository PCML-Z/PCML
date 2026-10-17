package com.pmcl.ui.studio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n

internal data class PaletteItem(
    val label: String,
    val group: String,
    val icon: ImageVector,
    val color: Color,
    val hint: String = "",
    val action: () -> Unit,
)

private fun score(label: String, query: String): Int? {
    if (query.isEmpty()) return 0
    val l = label.lowercase()
    val q = query.lowercase()
    val at = l.indexOf(q)
    if (at >= 0) return at
    var i = 0
    for (c in l) {
        if (i < q.length && c == q[i]) i++
    }
    return if (i == q.length) 100 + l.length else null
}

/** ⌘K 命令面板：动作、目标、模块、内容一起搜。 */
@Composable
internal fun CommandPalette(items: List<PaletteItem>, onDismiss: () -> Unit) {
    val p = LocalStudio.current
    var query by remember { mutableStateOf("") }
    var index by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val shown = remember(items, query) {
        items.mapNotNull { item -> score(item.label + " " + item.group, query.trim())?.let { it to item } }
            .sortedBy { it.first }
            .map { it.second }
            .take(80)
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        appear.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(query) { index = 0 }
    LaunchedEffect(index) { if (shown.isNotEmpty()) list.scrollToItem((index - 4).coerceAtLeast(0)) }
    fun pick(item: PaletteItem) {
        onDismiss()
        item.action()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f * appear.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .width(640.dp)
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * -16.dp.toPx()
                    val s = 0.97f + 0.03f * appear.value
                    scaleX = s
                    scaleY = s
                }
                .background(p.panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            Row(Modifier.fillMaxWidth().height(44.dp).background(p.accent), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(p.accentDeep), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                FlatField(
                    query,
                    { query = it },
                    I18n.t("studio.palette_hint"),
                    Modifier.weight(1f),
                    color = p.accent,
                    focus = focus,
                    onKey = { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else when (e.key) {
                            Key.DirectionDown -> { if (shown.isNotEmpty()) index = (index + 1) % shown.size; true }
                            Key.DirectionUp -> { if (shown.isNotEmpty()) index = (index - 1 + shown.size) % shown.size; true }
                            Key.Enter -> { shown.getOrNull(index)?.let { pick(it) }; true }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    }
                )
                FlatText("Esc", color = Color.White.copy(alpha = 0.7f), size = 11, modifier = Modifier.padding(horizontal = 12.dp))
            }
            if (shown.isEmpty()) {
                FlatText(I18n.t("studio.palette_none"), color = p.muted, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), state = list) {
                itemsIndexed(shown) { i, item ->
                    val on = i == index
                    val rowBg by animateColorAsState(if (on) item.color else if (i % 2 == 0) p.panel else p.panelAlt, tween(120), label = "paletteRow")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .entrance(i.coerceAtMost(12))
                            .background(rowBg)
                            .clickable { pick(item) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(38.dp).background(if (on) item.color.lift(0.14f) else item.color), contentAlignment = Alignment.Center) {
                            Icon(item.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        FlatText(
                            item.label,
                            color = if (on) Color.White else p.text,
                            weight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 12.dp).weight(1f)
                        )
                        if (item.hint.isNotEmpty()) {
                            FlatText(item.hint, color = if (on) Color.White.copy(alpha = 0.8f) else p.muted, size = 11, modifier = Modifier.padding(end = 10.dp))
                        }
                        FlatText(
                            item.group,
                            color = if (on) Color.White else p.muted,
                            size = 10,
                            modifier = Modifier.fillMaxHeight().background(if (on) item.color.lift(0.14f) else p.strip).padding(horizontal = 10.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
