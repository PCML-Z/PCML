package com.pmcl.ui.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.page.rememberMusicUrlImage
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.io.File
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 模块原生页拿到的上下文：ViewModel、模块色和窗口提供的确认框。 */
internal class ModuleCtx(
    val vm: LauncherViewModel,
    val color: Color,
    val section: String,
    val confirm: (title: String, body: String, action: () -> Unit) -> Unit,
    val openSection: (String) -> Unit,
)

internal fun mt(key: String, vararg args: Any?): String = I18n.t("studio.m.$key", *args)

private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT) }

internal fun fmtDate(ms: Long): String = if (ms <= 0) "—" else dateFormat.get().format(Date(ms))

internal fun fmtMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
internal fun rememberUrlImage(url: String?): ImageBitmap? = rememberMusicUrlImage(url.orEmpty())

/** 模块页顶部的大色块：图标、标题、副标题，右侧放主要操作。 */
@Composable
internal fun ModuleHero(
    title: String,
    subtitle: String,
    color: Color,
    icon: ImageVector,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val bg by animateColorAsState(color, tween(220), label = "heroBg")
    Row(
        Modifier.fillMaxWidth().height(76.dp).background(bg).padding(start = 22.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                title,
                transitionSpec = { (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 }) togetherWith fadeOut(tween(120)) },
                label = "heroTitle"
            ) { FlatText(it, color = Color.White, size = 20, weight = FontWeight.Light) }
            if (subtitle.isNotEmpty()) FlatText(subtitle, color = Color.White.copy(alpha = 0.75f), size = 11)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
internal fun HeroButton(label: String, icon: ImageVector, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Block(label, onClick, icon = icon, fill = color.lift(0.16f), enabled = enabled, height = 34.dp)
}

/** 一排等宽磁贴。 */
@Composable
internal fun TileRow(height: Dp = 84.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(height), content = content)
}

/** 工具条：一条浅色带，按钮从左往右排。 */
@Composable
internal fun Toolbar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val p = LocalStudio.current
    Row(
        modifier.fillMaxWidth().height(36.dp).background(p.panelAlt).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** 小节标题条。 */
@Composable
internal fun LabelBar(text: String, color: Color? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val p = LocalStudio.current
    Row(Modifier.fillMaxWidth().height(30.dp).background(p.panelAlt), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color ?: p.accent))
        FlatText(text, color = p.text, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp).weight(1f))
        trailing()
    }
}

/**
 * 列表行：左边一块方色块（图标、图片或短字），中间两行文字，右边操作按钮。
 * 悬停铺底色，选中用选中色，按 index 错开入场。
 */
@Composable
internal fun ItemRow(
    title: String,
    subtitle: String,
    color: Color,
    index: Int,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    image: ImageBitmap? = null,
    badge: String? = null,
    selected: Boolean = false,
    dim: Boolean = false,
    height: Dp = 52.dp,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> p.selected
            hovered -> p.hover
            index % 2 == 0 -> p.editor
            else -> p.panel
        },
        tween(140),
        label = "itemRow"
    )
    val square by animateDpAsState(if (hovered || selected) height + 6.dp else height, tween(160), label = "itemSquare")
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .entrance(index)
            .background(bg)
            .hoverable(source)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(square).fillMaxHeight().background(if (dim) color.copy(alpha = 0.35f) else color).clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            when {
                image != null -> Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(if (dim) 0.5f else 1f))
                badge != null -> FlatText(badge, color = inkOn(color), size = 12, weight = FontWeight.SemiBold)
                icon != null -> Icon(icon, contentDescription = null, tint = inkOn(color), modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            FlatText(title, color = if (dim) p.muted else p.text, weight = FontWeight.Medium, size = 13)
            if (subtitle.isNotEmpty()) FlatText(subtitle, color = p.muted, size = 11)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        Spacer(Modifier.width(6.dp))
    }
}

/** 行尾的小色块按钮。 */
@Composable
internal fun RowButton(icon: ImageVector, onClick: () -> Unit, color: Color? = null, enabled: Boolean = true, label: String = "") {
    Block(label, onClick, icon = icon, fill = color, enabled = enabled, height = 30.dp)
}

/** 空状态：一句提示，可带一个主操作。 */
@Composable
internal fun EmptyState(text: String, icon: ImageVector? = null, action: String? = null, color: Color? = null, onAction: () -> Unit = {}) {
    val p = LocalStudio.current
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (icon != null) {
            Box(Modifier.size(56.dp).entrance(0).background(color ?: p.accent), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
        }
        FlatText(text, color = p.muted, size = 13, lines = 4)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            Block(action, onAction, fill = color ?: p.accent, height = 32.dp)
        }
    }
}

/** 输入框：BasicTextField 外面一块浅色底。secret 时显示为圆点。 */
@Composable
internal fun InputBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    mono: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    val p = LocalStudio.current
    Box(modifier.height(30.dp).background(p.field).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) FlatText(placeholder, color = p.muted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = p.text, fontSize = 12.sp, fontFamily = if (mono) com.pmcl.ui.studio.mono.fontFamily else null),
            cursorBrush = SolidColor(p.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Go else ImeAction.Default),
            keyboardActions = KeyboardActions(onGo = { onSubmit?.invoke() }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 表单一行：左边标签，右边输入框。 */
@Composable
internal fun FormRow(label: String, index: Int, content: @Composable RowScope.() -> Unit) {
    val p = LocalStudio.current
    Row(
        Modifier.fillMaxWidth().entrance(index).background(if (index % 2 == 0) p.editor else p.panel).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlatText(label, color = p.text, weight = FontWeight.Medium, modifier = Modifier.width(160.dp))
        content()
    }
}

/** 一组互斥色块，用来选类型、筛选和排序。放在 Toolbar 等已可横向滚动的容器里时传 scrollable = false，避免嵌套滚动导致无限宽约束崩溃。 */
@Composable
internal fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, color: Color? = null, enabled: Boolean = true, scrollable: Boolean = true) {
    val p = LocalStudio.current
    Row(Modifier.then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier).alpha(if (enabled) 1f else 0.45f)) {
        options.forEach { (id, label) ->
            Block(label, { onPick(id) }, fill = if (id == selected) (color ?: p.accent) else p.field, enabled = enabled, height = 30.dp)
        }
    }
}

/** 细进度条，宽度变化带动画；fraction 为空时显示一段来回跑的条。 */
@Composable
internal fun ProgressLine(fraction: Float?, color: Color, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val p = LocalStudio.current
    val shown by animateFloatAsState((fraction ?: 0.3f).coerceIn(0f, 1f), tween(300, easing = FastOutSlowInEasing), label = "progress")
    Box(modifier.fillMaxWidth().height(height).background(p.field)) {
        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(color))
    }
}

/** 可点击、可拖动的进度条，用于播放进度和音量。 */
@Composable
internal fun SeekBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, onSeek: (Float) -> Unit) {
    val p = LocalStudio.current
    var width by remember { mutableIntStateOf(1) }
    val latest by rememberUpdatedState(onSeek)
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(120), label = "seek")
    Box(
        modifier
            .height(height + 12.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { latest((it.x / width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); latest((change.position.x / width).coerceIn(0f, 1f)) } },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(height).background(p.field))
        Box(Modifier.fillMaxWidth(shown).height(height).background(color))
    }
}

/** 右侧检查器：标题色带 + 内容。 */
@Composable
internal fun Inspector(title: String, color: Color, onClose: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalStudio.current
    Column(modifier.fillMaxHeight().background(p.panel)) {
        PanelHeader(title, color) {
            if (onClose != null) Block("", onClose, icon = Icons.Filled.Close, fill = color, height = 32.dp)
        }
        Column(Modifier.weight(1f).fillMaxWidth().clipToBounds(), content = content)
    }
}

/** 检查器里的一条“名称：值”。 */
@Composable
internal fun FactRow(label: String, value: String, index: Int, lines: Int = 2) {
    val p = LocalStudio.current
    Row(
        Modifier.fillMaxWidth().entrance(index).background(if (index % 2 == 0) p.panel else p.panelAlt).padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        FlatText(label, color = p.muted, size = 11, modifier = Modifier.width(96.dp))
        FlatText(value.ifBlank { "—" }, color = p.text, size = 12, lines = lines, modifier = Modifier.weight(1f))
    }
}

/** 竖条图：每根柱子从底部长出来，最高那根铺满。 */
@Composable
internal fun BarChart(values: List<Long>, labels: List<String>, color: Color, modifier: Modifier = Modifier, highlight: Int = -1) {
    val p = LocalStudio.current
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(modifier.fillMaxWidth().background(p.panel).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            val target = v.toFloat() / max
            val h by animateFloatAsState(target, tween(520, delayMillis = i * 18, easing = FastOutSlowInEasing), label = "bar")
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(h.coerceAtLeast(if (v > 0) 0.02f else 0f)).background(if (i == highlight) color.lift(0.25f) else color))
                }
                if (labels.size == values.size) FlatText(labels[i], color = p.muted, size = 9)
            }
        }
    }
}

/** 本地图片缩略图，后台解码，走 ThumbCache。 */
@Composable
internal fun rememberThumb(path: Path?, max: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, path, max) {
        value = if (path == null) null else withContext(Dispatchers.IO) { ThumbCache.load(path, max) }
    }
    return image
}

/** 服务器列表里的 base64 图标。 */
internal fun decodeBase64Image(data: String?): ImageBitmap? = try {
    if (data.isNullOrBlank()) null
    else {
        val raw = data.substringAfter("base64,", data).trim()
        org.jetbrains.skia.Image.makeFromEncoded(java.util.Base64.getMimeDecoder().decode(raw)).toComposeImageBitmap()
    }
} catch (_: Throwable) {
    null
}

internal fun browse(url: String): Boolean = try {
    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    true
} catch (_: Throwable) {
    false
}

internal fun copyText(text: String) {
    try {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    } catch (_: Throwable) {
    }
}

internal fun pickFile(title: String, exts: Set<String> = emptySet(), multiple: Boolean = false): List<Path> = try {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = multiple
    if (exts.isNotEmpty()) dialog.filenameFilter = java.io.FilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase(Locale.ROOT) in exts }
    dialog.isVisible = true
    dialog.files.orEmpty().map { it.toPath() }
} catch (_: Throwable) {
    emptyList()
}

internal fun pickSaveFile(title: String, suggested: String): Path? = try {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
    dialog.file = suggested
    dialog.isVisible = true
    if (dialog.file != null) File(dialog.directory, dialog.file).toPath() else null
} catch (_: Throwable) {
    null
}
