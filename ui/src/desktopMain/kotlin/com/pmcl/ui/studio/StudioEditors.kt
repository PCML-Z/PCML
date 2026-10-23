package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

/** 打开的文件缓冲区。放在窗口级别，切换标签页时不丢失未保存的修改。 */
internal class FileBuffer {
    var load by mutableStateOf<FileLoad?>(null)
    var original by mutableStateOf("")
    var value by mutableStateOf(TextFieldValue(""))
    var message by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var findOpen by mutableStateOf(false)
    var find by mutableStateOf("")
    var replace by mutableStateOf("")
    var jumpLine by mutableStateOf<Int?>(null)
    var showDiff by mutableStateOf(false)

    val text: String get() = value.text
    val editable: Boolean get() = (load as? FileLoad.Text)?.editable == true
    val dirty: Boolean get() = editable && text != original
}

private data class TileSpec(
    val title: String,
    val value: String,
    val color: Color,
    val icon: ImageVector,
    val onClick: (() -> Unit)?,
)

@Composable
internal fun OverviewEditor(
    vm: LauncherViewModel,
    target: StudioTarget,
    reload: Int,
    onRun: () -> Unit,
    onOpenJson: () -> Unit,
    onOpenContent: (ContentKind) -> Unit,
    onOpenFile: (Path, Path) -> Unit,
    onReveal: (Path) -> Unit,
    onOpenStorage: () -> Unit,
    onOpenDiagnose: () -> Unit = {},
    onOpenCompare: () -> Unit = {},
    onOpenConfigs: () -> Unit = {},
    onOpenExport: () -> Unit = {},
) {
    val p = LocalStudio.current
    val javas by vm.javaInstallations.collectAsState()
    val facts by produceState<TargetFacts?>(null, target, reload) {
        value = withContext(Dispatchers.IO) { vm.factsOf(target) }
    }
    val kindColor = if (target.kind == TargetKind.VERSION) Tiles.Blue else Tiles.Navy
    Column(Modifier.fillMaxSize().background(p.editor).verticalScroll(rememberScrollState())) {
        val f = facts
        Row(Modifier.fillMaxWidth().height(132.dp)) {
            Column(
                Modifier.weight(2f).fillMaxHeight().background(kindColor).padding(18.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                FlatText(
                    if (target.kind == TargetKind.VERSION) I18n.t("studio.kind_version") else I18n.t("studio.kind_instance") + " · " + target.versionId,
                    color = Color.White.copy(alpha = 0.85f),
                    size = 11
                )
                FlatText(target.name, color = Color.White, size = 26, weight = FontWeight.Light, lines = 1)
                FlatText(f?.loader ?: "…", color = Color.White.copy(alpha = 0.85f))
            }
            ColorTile(I18n.t("studio.run"), "▶", Tiles.Forest, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.PlayArrow, onRun, valueSize = 34, index = 1)
            val required = f?.requiredJava ?: 0
            val matching = javas.count { it.majorVersion == required }
            ColorTile(
                I18n.t("studio.fact_java"),
                if (required > 0) "Java $required" else "—",
                Tiles.Teal,
                Modifier.weight(1f).fillMaxHeight(),
                Icons.Filled.Coffee,
                valueSize = 22,
                index = 2,
            )
            ColorTile(
                I18n.t("studio.java_found"),
                if (required > 0) matching.toString() else javas.size.toString(),
                if (required > 0 && matching == 0) Tiles.Red else Tiles.Sky,
                Modifier.weight(1f).fillMaxHeight(),
                Icons.Filled.Coffee,
                onClick = { vm.scanJavaInstallations() },
                valueSize = 22,
                index = 3,
            )
        }
        if (f == null) {
            FlatText(I18n.t("studio.loading"), color = p.muted, modifier = Modifier.padding(18.dp))
            return@Column
        }
        val last = if (f.lastPlayed > 0) SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(f.lastPlayed)) else I18n.t("studio.never")
        val tiles = listOf(
            TileSpec(I18n.t("studio.fact_mods"), f.mods.toString(), Tiles.Blue, Icons.Filled.Extension) { onOpenContent(ContentKind.MODS) },
            TileSpec(I18n.t("studio.fact_worlds"), f.worlds.toString(), Tiles.Navy, Icons.Filled.Public) { onOpenContent(ContentKind.WORLDS) },
            TileSpec(I18n.t("studio.fact_resourcepacks"), f.resourcePacks.toString(), Tiles.Sky, Icons.Filled.Palette) { onOpenContent(ContentKind.RESOURCE_PACKS) },
            TileSpec(I18n.t("studio.fact_shaders"), f.shaderPacks.toString(), Tiles.Teal, Icons.Filled.Tune) { onOpenContent(ContentKind.SHADERS) },
            TileSpec(I18n.t("studio.fact_screenshots"), f.screenshots.toString(), Tiles.Plum, Icons.Filled.Image) { onOpenContent(ContentKind.SCREENSHOTS) },
            TileSpec(I18n.t("studio.fact_loader"), f.loader, Tiles.Slate, Icons.Filled.Layers, null),
            TileSpec(I18n.t("studio.fact_last"), last, Tiles.Graphite, Icons.Filled.Schedule, null),
            TileSpec(I18n.t("studio.reveal_dir"), f.gameDir?.fileName?.toString() ?: "—", Tiles.Gold, Icons.Filled.FolderOpen, f.gameDir?.let { d -> { onReveal(d) } }),
            TileSpec(
                I18n.t("studio.fact_playtime"),
                if (target.kind == TargetKind.INSTANCE) formatDuration(f.playSeconds) else "—",
                Tiles.Navy,
                Icons.Filled.Timer,
                null
            ),
            TileSpec(I18n.t("studio.storage"), I18n.t("studio.storage_open"), Tiles.Blue, Icons.Filled.Storage, if (f.gameDir != null) onOpenStorage else null),
            TileSpec(
                I18n.t("studio.latest_log"),
                f.latestLog?.let { formatSize(f.latestLogSize) } ?: "—",
                Tiles.Slate,
                Icons.AutoMirrored.Filled.Article,
                f.latestLog?.let { log -> f.gameDir?.let { d -> { onOpenFile(d, log) } } }
            ),
            TileSpec(I18n.t("studio.version_json"), if (f.jsonPath != null) target.versionId else "—", Tiles.Sky, Icons.Filled.Code, if (f.jsonPath != null) onOpenJson else null),
            TileSpec(I18n.t("studio.diagnose"), I18n.t("studio.diag_open"), Tiles.Orange, Icons.Filled.HealthAndSafety, onOpenDiagnose),
            TileSpec(I18n.t("studio.compare"), I18n.t("studio.compare_open"), Tiles.Navy, Icons.Filled.CompareArrows, onOpenCompare),
            TileSpec(I18n.t("studio.configs"), I18n.t("studio.configs_open"), Tiles.Graphite, Icons.Filled.Tune, if (f.gameDir != null) onOpenConfigs else null),
            TileSpec(I18n.t("studio.export"), I18n.t("studio.export_open"), Tiles.Plum, Icons.Filled.Share, if (f.gameDir != null) onOpenExport else null),
        )
        tiles.chunked(4).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().height(96.dp)) {
                row.forEachIndexed { c, t ->
                    ColorTile(t.title, t.value, t.color, Modifier.weight(1f).fillMaxHeight(), t.icon, t.onClick, index = 4 + r * 4 + c)
                }
            }
        }
        val dir = f.gameDir
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            Block(I18n.t("studio.open_json"), onClick = onOpenJson, icon = Icons.Filled.Code, fill = Tiles.Sky, enabled = f.jsonPath != null, modifier = Modifier.weight(1f), height = 44.dp)
            Block(
                I18n.t("studio.open_options"),
                onClick = { if (dir != null) onOpenFile(dir, dir.resolve("options.txt")) },
                icon = Icons.Filled.Settings,
                fill = Tiles.Slate,
                enabled = dir != null && Files.isRegularFile(dir.resolve("options.txt")),
                modifier = Modifier.weight(1f),
                height = 44.dp
            )
            Block(
                I18n.t("studio.open_latest_log"),
                onClick = { if (dir != null) onOpenFile(dir, dir.resolve("logs").resolve("latest.log")) },
                icon = Icons.AutoMirrored.Filled.Article,
                fill = Tiles.Graphite,
                enabled = dir != null && Files.isRegularFile(dir.resolve("logs").resolve("latest.log")),
                modifier = Modifier.weight(1f),
                height = 44.dp
            )
        }
        val props = listOf(
            I18n.t("studio.prop_game_dir") to (f.gameDir?.toString() ?: "—"),
            I18n.t("studio.prop_json") to (f.jsonPath?.toString() ?: "—"),
            I18n.t("studio.prop_inherits") to f.inheritsFrom.ifBlank { "—" },
            I18n.t("studio.prop_main_class") to f.mainClass.ifBlank { "—" },
            I18n.t("studio.prop_assets") to f.assets.ifBlank { "—" },
        )
        props.forEachIndexed { index, (label, value) ->
            Row(
                Modifier.fillMaxWidth().background(if (index % 2 == 0) p.panel else p.panelAlt).padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                FlatText(label, color = p.muted, modifier = Modifier.width(120.dp))
                SelectionContainer(Modifier.weight(1f)) { FlatText(value, lines = 2) }
            }
        }
    }
}

private fun gutterWidth(lines: Int): Dp = (lines.toString().length * 8 + 20).dp

/** 只读、带行号、高亮和搜索的文本查看器。大文件用 LazyColumn 逐行渲染。 */
@Composable
internal fun LineViewer(
    text: String,
    syntax: Syntax,
    modifier: Modifier = Modifier,
    banner: String? = null,
    jumpLine: Int? = null,
    onJumped: () -> Unit = {},
) {
    val p = LocalStudio.current
    val lines = remember(text) { text.lines() }
    var query by remember { mutableStateOf("") }
    var cursor by remember { mutableIntStateOf(0) }
    val matches = remember(lines, query) {
        val q = query.trim()
        if (q.isEmpty()) emptyList() else lines.indices.filter { lines[it].contains(q, ignoreCase = true) }
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun jump(delta: Int) {
        if (matches.isEmpty()) return
        cursor = ((cursor + delta) % matches.size + matches.size) % matches.size
        scope.launch { list.scrollToItem((matches[cursor] - 3).coerceAtLeast(0)) }
    }
    LaunchedEffect(matches) {
        cursor = 0
        if (matches.isNotEmpty()) list.scrollToItem((matches[0] - 3).coerceAtLeast(0))
    }
    var flash by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(jumpLine) {
        val line = jumpLine ?: return@LaunchedEffect
        flash = line
        list.scrollToItem((line - 4).coerceAtLeast(0))
        onJumped()
    }
    val gutter = gutterWidth(lines.size)
    Column(modifier.background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(32.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            FlatField(
                query, { query = it }, I18n.t("studio.find"),
                Modifier.widthIn(max = 300.dp).weight(1f, fill = false), Icons.Filled.Search,
                onKey = { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { jump(if (e.isShiftPressed) -1 else 1); true } else false
                }
            )
            FlatText(
                if (query.isBlank()) I18n.t("studio.lines_n", lines.size.toString())
                else I18n.t("studio.matches_n", if (matches.isEmpty()) "0" else "${cursor + 1}/${matches.size}"),
                color = p.muted,
                size = 11,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Block("", onClick = { jump(-1) }, icon = Icons.Filled.KeyboardArrowUp, enabled = matches.isNotEmpty(), height = 32.dp)
            Block("", onClick = { jump(1) }, icon = Icons.Filled.KeyboardArrowDown, enabled = matches.isNotEmpty(), height = 32.dp)
            Spacer(Modifier.weight(1f))
            if (banner != null) {
                FlatText(banner, color = Color.White, size = 11, modifier = Modifier.background(Tiles.Graphite).padding(horizontal = 10.dp, vertical = 9.dp))
            }
        }
        val hScroll = rememberScrollState()
        val current = matches.getOrNull(cursor)
        val matchSet = remember(matches) { matches.toHashSet() }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(hScroll), state = list) {
                itemsIndexed(lines) { index, line ->
                    Row(
                        Modifier.background(
                            when {
                                index == current || index == flash -> p.selected
                                index in matchSet -> p.selected.copy(alpha = 0.45f)
                                else -> Color.Transparent
                            }
                        )
                    ) {
                        Text(
                            (index + 1).toString(),
                            style = mono.copy(color = p.gutter, textAlign = TextAlign.End),
                            modifier = Modifier.width(gutter).background(p.panel).padding(end = 8.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(highlight(line, syntax, p), style = mono.copy(color = p.text), softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
internal fun VersionJsonEditor(vm: LauncherViewModel, versionId: String) {
    VersionJsonStructured(vm, versionId)
}

@Composable
internal fun FileEditor(tab: StudioTab.FileTab, buffer: FileBuffer, onSave: () -> Unit) {
    val p = LocalStudio.current
    LaunchedEffect(tab.key) {
        if (buffer.load == null) {
            val load = withContext(Dispatchers.IO) { loadFile(tab.root, tab.file) }
            val text = (load as? FileLoad.Text)?.text ?: ""
            buffer.original = text
            buffer.value = TextFieldValue(text)
            buffer.load = if (load is FileLoad.Text && tab.readOnly) load.copy(editable = false) else load
        }
    }
    val syntax = remember(tab.file) { syntaxOf(tab.file.fileName?.toString().orEmpty()) }
    when (val load = buffer.load) {
        null -> Editorial { CenterNote(I18n.t("studio.loading")) }
        is FileLoad.Refused -> Editorial { CenterNote(I18n.t("studio.file_refused")) }
        is FileLoad.Binary -> Editorial { CenterNote(I18n.t("studio.file_binary", formatSize(load.size))) }
        is FileLoad.TooLarge -> Editorial { CenterNote(I18n.t("studio.file_too_large", formatSize(load.size))) }
        is FileLoad.Failed -> Editorial { CenterNote(I18n.t("studio.file_failed", load.message)) }
        is FileLoad.Text -> if (!load.editable) {
            LineViewer(
                load.text, syntax, Modifier.fillMaxSize(),
                banner = I18n.t("studio.read_only") + " · " + tab.file.fileName,
                jumpLine = buffer.jumpLine,
                onJumped = { buffer.jumpLine = null }
            )
        } else {
            TextEditor(tab, buffer, syntax, onSave)
        }
    }
}

@Composable
private fun Editorial(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(LocalStudio.current.editor)) { content() }
}

private fun lineStart(text: String, line: Int): Int {
    var offset = 0
    var current = 0
    while (current < line) {
        val next = text.indexOf('\n', offset)
        if (next < 0) return text.length
        offset = next + 1
        current++
    }
    return offset
}

@Composable
private fun TextEditor(tab: StudioTab.FileTab, buffer: FileBuffer, syntax: Syntax, onSave: () -> Unit) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    val vScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val transformation = remember(syntax, p) { SyntaxTransformation(syntax, p) }

    fun reveal(offset: Int) {
        val l = layout ?: return
        val safe = offset.coerceIn(0, l.layoutInput.text.length)
        val top = l.getLineTop(l.getLineForOffset(safe))
        scope.launch { vScroll.animateScrollTo((top - 80f).toInt().coerceAtLeast(0)) }
    }

    fun findNext(backward: Boolean = false) {
        val q = buffer.find
        if (q.isEmpty()) return
        val text = buffer.text
        val sel = buffer.value.selection
        val index = if (backward) {
            text.lastIndexOf(q, (sel.min - 1).coerceAtLeast(0), ignoreCase = true).takeIf { it >= 0 && it < sel.min }
                ?: text.lastIndexOf(q, ignoreCase = true)
        } else {
            text.indexOf(q, sel.max, ignoreCase = true).takeIf { it >= 0 } ?: text.indexOf(q, ignoreCase = true)
        }
        if (index >= 0) {
            buffer.value = buffer.value.copy(selection = TextRange(index, index + q.length))
            reveal(index)
        }
    }

    fun replaceOne() {
        val q = buffer.find
        if (q.isEmpty()) return
        val sel = buffer.value.selection
        val text = buffer.text
        if (!sel.collapsed && text.substring(sel.min, sel.max).equals(q, ignoreCase = true)) {
            val next = text.substring(0, sel.min) + buffer.replace + text.substring(sel.max)
            val caret = sel.min + buffer.replace.length
            buffer.value = TextFieldValue(next, TextRange(caret))
        }
        findNext()
    }

    fun replaceAll() {
        val q = buffer.find
        if (q.isEmpty()) return
        val text = buffer.text
        val count = Regex(Regex.escape(q), RegexOption.IGNORE_CASE).findAll(text).count()
        if (count == 0) return
        buffer.value = TextFieldValue(text.replace(q, buffer.replace, ignoreCase = true))
        buffer.message = I18n.t("studio.replaced_n", count.toString())
    }

    LaunchedEffect(buffer.jumpLine, layout) {
        val line = buffer.jumpLine ?: return@LaunchedEffect
        if (layout == null) return@LaunchedEffect
        val offset = lineStart(buffer.text, line)
        val end = buffer.text.indexOf('\n', offset).let { if (it < 0) buffer.text.length else it }
        buffer.value = buffer.value.copy(selection = TextRange(offset, end))
        reveal(offset)
        buffer.jumpLine = null
    }

    val matchCount = remember(buffer.text, buffer.find) {
        if (buffer.find.isEmpty()) 0 else Regex(Regex.escape(buffer.find), RegexOption.IGNORE_CASE).findAll(buffer.text).count()
    }

    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(32.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            FlatText(
                tab.root.relativize(tab.file).toString(),
                color = p.muted,
                size = 11,
                modifier = Modifier.padding(horizontal = 12.dp).weight(1f)
            )
            buffer.message?.let { FlatText(it, color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 8.dp)) }
            Block("", onClick = { buffer.findOpen = !buffer.findOpen }, icon = Icons.Filled.FindReplace, selected = buffer.findOpen, height = 32.dp)
            Block(
                I18n.t("studio.diff"),
                onClick = { buffer.showDiff = !buffer.showDiff },
                icon = Icons.Filled.Difference,
                selected = buffer.showDiff,
                enabled = buffer.dirty || buffer.showDiff,
                height = 32.dp
            )
            Block(
                I18n.t("studio.revert"),
                onClick = { buffer.value = TextFieldValue(buffer.original); buffer.message = null },
                icon = Icons.AutoMirrored.Filled.Undo,
                enabled = buffer.dirty,
                height = 32.dp
            )
            Block(I18n.t("studio.save"), onClick = onSave, icon = Icons.Filled.Save, fill = p.accent, enabled = buffer.dirty && !buffer.saving, height = 32.dp)
        }
        if (buffer.findOpen) {
            var goto by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().height(30.dp).background(p.panelAlt), verticalAlignment = Alignment.CenterVertically) {
                FlatField(
                    buffer.find, { buffer.find = it }, I18n.t("studio.find"), Modifier.width(220.dp), Icons.Filled.Search,
                    onKey = { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { findNext(e.isShiftPressed); true } else false
                    }
                )
                FlatText(I18n.t("studio.matches_n", matchCount.toString()), color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 8.dp))
                Block("", onClick = { findNext(true) }, icon = Icons.Filled.KeyboardArrowUp, enabled = matchCount > 0, height = 30.dp)
                Block("", onClick = { findNext() }, icon = Icons.Filled.KeyboardArrowDown, enabled = matchCount > 0, height = 30.dp)
                FlatField(buffer.replace, { buffer.replace = it }, I18n.t("studio.replace_with"), Modifier.width(200.dp))
                Block(I18n.t("studio.replace"), onClick = { replaceOne() }, enabled = matchCount > 0, height = 30.dp)
                Block(I18n.t("studio.replace_all"), onClick = { replaceAll() }, enabled = matchCount > 0, fill = Tiles.Navy, height = 30.dp)
                Spacer(Modifier.weight(1f))
                FlatField(
                    goto, { v -> goto = v.filter { it.isDigit() }.take(7) }, I18n.t("studio.goto_line"), Modifier.width(110.dp),
                    onKey = { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                            goto.toIntOrNull()?.let { buffer.jumpLine = (it - 1).coerceAtLeast(0) }
                            true
                        } else false
                    }
                )
            }
        }
        val lineCount = remember(buffer.text) { buffer.text.count { it == '\n' } + 1 }
        val caretLine = remember(buffer.value.selection, buffer.text) {
            val c = buffer.value.selection.start.coerceIn(0, buffer.text.length)
            buffer.text.substring(0, c).count { it == '\n' } + 1
        }
        if (buffer.showDiff) {
            DiffView(buffer.original, buffer.text, Modifier.weight(1f).fillMaxWidth())
        } else Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                Text(
                    (1..lineCount).joinToString("\n"),
                    style = mono.copy(color = p.gutter, textAlign = TextAlign.End),
                    modifier = Modifier.width(gutterWidth(lineCount)).background(p.panel).padding(end = 8.dp, top = 8.dp, bottom = 40.dp)
                )
                BasicTextField(
                    value = buffer.value,
                    onValueChange = { buffer.value = it; if (it.text != buffer.text) buffer.message = null },
                    textStyle = TextStyle(fontFamily = mono.fontFamily, fontSize = mono.fontSize, color = p.text),
                    cursorBrush = SolidColor(p.accent),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    modifier = Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 40.dp)
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(22.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            FlatText(
                I18n.t("studio.caret", caretLine.toString(), lineCount.toString()),
                color = p.muted,
                size = 11,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            FlatText(syntax.name, color = Color.White, size = 10, modifier = Modifier.fillMaxHeight().background(Tiles.Slate).padding(horizontal = 8.dp, vertical = 4.dp))
            FlatText("UTF-8", color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.weight(1f))
            FlatText(formatSize(buffer.text.length.toLong()), color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

internal fun lineColor(line: String, p: StudioPalette): Color = when {
    line.contains("ERROR") || line.contains("FATAL") || line.contains("Exception") -> p.error
    line.contains("WARN") -> p.warn
    else -> p.text
}

internal suspend fun saveBuffer(tab: StudioTab.FileTab, buffer: FileBuffer) {
    if (!buffer.dirty || buffer.saving) return
    buffer.saving = true
    val text = buffer.text
    try {
        withContext(Dispatchers.IO) { saveFile(tab.root, tab.file, text) }
        buffer.original = text
        buffer.message = I18n.t("studio.saved")
    } catch (e: Throwable) {
        buffer.message = I18n.t("studio.save_failed", e.message ?: e.javaClass.simpleName)
    } finally {
        buffer.saving = false
    }
}
