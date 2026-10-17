package com.pmcl.ui.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.cli.PmclCli
import com.pmcl.core.LauncherCore
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/** 终端固定用深色底，和编辑区的明暗无关。 */
private val TermBg = Color(0xFF1B1F24)
private val TermBlockBg = Color(0xFF22272E)
private val TermHeaderBg = Color(0xFF2B313A)
private val TermText = Color(0xFFD7DCE2)
private val TermMuted = Color(0xFF8B949E)
private val TermErr = Color(0xFFF28B82)
private val TermWarn = Color(0xFFF6C26B)
private val TermHint = Color(0xFF8AB4F8)

private const val MAX_BLOCKS = 200
private const val MAX_LINES_PER_BLOCK = 5000

internal enum class TermKind { OUT, ERR, WARN, HINT }

internal class TermLine(val text: String, val kind: TermKind)

internal class TermBlock(val id: Long, val command: String, val started: Long) {
    val lines = mutableStateListOf<TermLine>()
    var finished by mutableStateOf<Long?>(null)
    var failed by mutableStateOf(false)
    var cancelled by mutableStateOf(false)
    var collapsed by mutableStateOf(false)
}

internal class TermSession(val id: Int, core: LauncherCore) {
    val cli = PmclCli(core)
    val blocks = mutableStateListOf<TermBlock>()
    val history = mutableStateListOf<String>()
    var input by mutableStateOf(TextFieldValue(""))
    var busy by mutableStateOf(false)
    var job: Job? = null
    var historyIndex = -1
    val title: String get() = I18n.t("studio.term_session", id.toString())
}

/** 终端状态挂在窗口上，收起底部面板或切换标签都不会丢会话。 */
internal class StudioTerminalState(private val core: LauncherCore, private val scope: CoroutineScope) {
    val sessions = mutableStateListOf<TermSession>()
    var activeId by mutableIntStateOf(0)
    var fontSize by mutableIntStateOf(12)
    var sideOpen by mutableStateOf(true)
    var query by mutableStateOf("")
    private var nextSession = 1
    private var nextBlock = 1L

    init {
        newSession()
    }

    val active: TermSession get() = sessions.firstOrNull { it.id == activeId } ?: sessions.first()

    fun newSession() {
        val s = TermSession(nextSession++, core)
        sessions += s
        activeId = s.id
    }

    fun close(session: TermSession) {
        session.job?.cancel()
        if (sessions.size == 1) {
            session.blocks.clear()
            return
        }
        val index = sessions.indexOf(session)
        sessions.remove(session)
        if (activeId == session.id) activeId = sessions[(index - 1).coerceAtLeast(0)].id
    }

    fun cancel(session: TermSession) {
        session.job?.cancel()
    }

    fun run(session: TermSession, raw: String) {
        val command = raw.trim()
        if (command.isEmpty() || session.busy) return
        session.historyIndex = -1
        if (session.history.lastOrNull() != command) {
            session.history += command
            if (session.history.size > 500) session.history.removeAt(0)
        }
        val head = command.substringBefore(' ').lowercase()
        if (head == "clear" || head == "cls") {
            session.blocks.clear()
            return
        }
        val block = TermBlock(nextBlock++, command, System.currentTimeMillis())
        session.blocks += block
        while (session.blocks.size > MAX_BLOCKS) session.blocks.removeAt(0)
        if (head == "exit" || head == "quit") {
            block.lines += TermLine(I18n.t("terminal.exit_hint"), TermKind.HINT)
            block.finished = System.currentTimeMillis()
            return
        }
        session.busy = true
        session.job = scope.launch {
            try {
                val parts = command.split(Regex("\\s+")).toTypedArray()
                val output = withContext(Dispatchers.IO) { session.cli.executeCaptured(parts) }
                output.split('\n').asSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.take(MAX_LINES_PER_BLOCK).forEach {
                    block.lines += TermLine(it, classify(it))
                }
                block.failed = block.lines.any { it.kind == TermKind.ERR }
            } catch (e: CancellationException) {
                block.cancelled = true
                block.lines += TermLine(I18n.t("terminal.cancelled"), TermKind.HINT)
                throw e
            } catch (e: Throwable) {
                block.failed = true
                block.lines += TermLine("Error: " + (e.message ?: e.javaClass.simpleName), TermKind.ERR)
            } finally {
                block.finished = System.currentTimeMillis()
                session.busy = false
                session.job = null
            }
        }
    }
}

private fun classify(line: String): TermKind {
    val lower = line.lowercase()
    return when {
        line.startsWith("Error") || line.startsWith("Failed") || lower.contains("exception") || lower.contains("error:") ||
            lower.contains("not found") || lower.contains("unknown") || lower.contains("[error") || lower.contains("/error]") -> TermKind.ERR
        lower.contains("[warn") || lower.contains("/warn]") || lower.contains("warning") -> TermKind.WARN
        else -> TermKind.OUT
    }
}

private fun lineColor(kind: TermKind): Color = when (kind) {
    TermKind.OUT -> TermText
    TermKind.ERR -> TermErr
    TermKind.WARN -> TermWarn
    TermKind.HINT -> TermHint
}

private fun blockText(block: TermBlock): String = "pmcl> " + block.command + "\n" + block.lines.joinToString("\n") { it.text }

/** 主命令（不含别名）按用途分组，侧栏里点一下就填进输入框。 */
private val commandGroups = listOf(
    "studio.term_group_versions" to listOf("versions", "remote", "install", "launch", "integrity", "modloaders", "pin", "unpin", "recent", "playtime"),
    "studio.term_group_content" to listOf("mods", "search", "install-mod", "worlds", "datapacks", "resourcepacks", "shaders", "screenshots", "skin"),
    "studio.term_group_account" to listOf("account", "whoami", "login", "logout"),
    "studio.term_group_system" to listOf("java", "config", "sysinfo", "status", "cache", "log", "crash", "migrate", "update", "version", "theme"),
    "studio.term_group_web" to listOf("news", "wiki", "download", "open", "url", "mp", "plugin"),
)

private val quickCommands = listOf("help", "versions", "java", "sysinfo", "status")

private sealed interface TermRow {
    data class Header(val block: TermBlock) : TermRow
    data class Line(val block: TermBlock, val index: Int, val line: TermLine) : TermRow
}

@Composable
internal fun TerminalView(state: StudioTerminalState, modifier: Modifier = Modifier, onExpand: (() -> Unit)? = null) {
    val p = LocalStudio.current
    val session = state.active
    val focus = remember { FocusRequester() }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val commandNames = remember { PmclCli.listCommandNames() }

    fun setInput(text: String) {
        session.input = TextFieldValue(text, TextRange(text.length))
    }

    fun submit() {
        val text = session.input.text
        if (text.isBlank() || session.busy) return
        setInput("")
        suggestions = emptyList()
        state.run(session, text)
    }

    fun complete() {
        val text = session.input.text
        val token = text.substringAfterLast(' ')
        val prefix = text.substring(0, text.length - token.length)
        val matches = commandNames.filter { it.startsWith(token, ignoreCase = true) }.distinct()
        when {
            matches.isEmpty() -> suggestions = emptyList()
            matches.size == 1 -> { setInput(prefix + matches[0] + " "); suggestions = emptyList() }
            else -> {
                var common = matches[0]
                for (m in matches) {
                    var j = 0
                    while (j < common.length && j < m.length && common[j].equals(m[j], true)) j++
                    common = common.substring(0, j)
                }
                if (common.length > token.length) setInput(prefix + common)
                suggestions = matches.take(16)
            }
        }
    }

    Column(modifier.background(TermBg)) {
        // 会话条 + 工具
        Row(Modifier.fillMaxWidth().height(30.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                state.sessions.forEach { s -> SessionTab(s, s.id == state.activeId, { state.activeId = s.id }, { state.close(s) }) }
                Block("", onClick = { state.newSession() }, icon = Icons.Filled.Add, height = 30.dp)
            }
            FlatField(state.query, { state.query = it }, I18n.t("terminal.search_hint"), Modifier.width(180.dp).height(30.dp), icon = Icons.Filled.Search)
            Block("", onClick = { state.fontSize = (state.fontSize - 1).coerceAtLeast(10) }, icon = Icons.Filled.Remove, height = 30.dp)
            FlatText("${state.fontSize}", color = p.muted, size = 11)
            Block("", onClick = { state.fontSize = (state.fontSize + 1).coerceAtMost(20) }, icon = Icons.Filled.Add, height = 30.dp)
            Block("", onClick = { copyText(session.blocks.joinToString("\n\n") { blockText(it) }) }, icon = Icons.Filled.ContentCopy, height = 30.dp)
            Block("", onClick = { session.blocks.clear() }, icon = Icons.Filled.ClearAll, height = 30.dp)
            Block("", onClick = { state.sideOpen = !state.sideOpen }, icon = Icons.Filled.ViewSidebar, selected = state.sideOpen, height = 30.dp)
            if (onExpand != null) Block("", onClick = onExpand, icon = Icons.AutoMirrored.Filled.OpenInNew, height = 30.dp)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                OutputArea(state, session) { setInput(it); focus.requestFocus() }
            }
            AnimatedVisibility(
                state.sideOpen,
                enter = expandHorizontally(tween(200), expandFrom = Alignment.End) + fadeIn(tween(200)),
                exit = shrinkHorizontally(tween(160), shrinkTowards = Alignment.End) + fadeOut(tween(160))
            ) {
                SidePanel(session, onInsert = { setInput(it); focus.requestFocus() }, onRun = { state.run(session, it) })
            }
        }
        AnimatedVisibility(suggestions.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.fillMaxWidth().height(28.dp).background(TermHeaderBg).horizontalScroll(rememberScrollState())) {
                suggestions.forEachIndexed { i, name ->
                    Box(Modifier.fillMaxHeight().entrance(i).clickable {
                        val text = session.input.text
                        val token = text.substringAfterLast(' ')
                        setInput(text.substring(0, text.length - token.length) + name + " ")
                        suggestions = emptyList()
                        focus.requestFocus()
                    }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(name, style = mono.copy(color = TermHint))
                    }
                }
            }
        }
        // 输入条
        Row(Modifier.fillMaxWidth().height(38.dp).background(TermHeaderBg), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().background(if (session.busy) Tiles.Orange else p.accent).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text("pmcl ›", style = mono.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = (state.fontSize + 1).sp))
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (session.input.text.isEmpty()) {
                    Text(
                        if (session.busy) I18n.t("terminal.executing") else I18n.t("studio.term_input_hint"),
                        style = mono.copy(color = TermMuted, fontSize = (state.fontSize + 1).sp)
                    )
                }
                BasicTextField(
                    value = session.input,
                    onValueChange = { session.input = it; suggestions = emptyList() },
                    singleLine = true,
                    textStyle = TextStyle(color = TermText, fontSize = (state.fontSize + 1).sp, fontFamily = mono.fontFamily),
                    cursorBrush = SolidColor(TermHint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val ctrl = e.isCtrlPressed && !e.isMetaPressed
                            when {
                                e.key == Key.Enter -> { submit(); true }
                                e.key == Key.Tab -> { complete(); true }
                                ctrl && e.key == Key.L -> { session.blocks.clear(); true }
                                ctrl && e.key == Key.C && session.busy -> { state.cancel(session); true }
                                e.key == Key.Escape -> {
                                    if (suggestions.isNotEmpty()) suggestions = emptyList() else setInput("")
                                    true
                                }
                                e.key == Key.DirectionUp -> {
                                    val h = session.history
                                    if (h.isNotEmpty()) {
                                        session.historyIndex = if (session.historyIndex < 0) h.lastIndex else (session.historyIndex - 1).coerceAtLeast(0)
                                        setInput(h[session.historyIndex])
                                    }
                                    true
                                }
                                e.key == Key.DirectionDown -> {
                                    val h = session.history
                                    if (session.historyIndex >= 0) {
                                        session.historyIndex++
                                        if (session.historyIndex >= h.size) { session.historyIndex = -1; setInput("") } else setInput(h[session.historyIndex])
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                )
            }
            if (session.busy) {
                Block(I18n.t("terminal.cancel"), onClick = { state.cancel(session) }, icon = Icons.Filled.Stop, fill = Tiles.Red, height = 38.dp)
            } else {
                Block(I18n.t("studio.term_run"), onClick = { submit() }, icon = Icons.Filled.PlayArrow, fill = p.accent, enabled = session.input.text.isNotBlank(), height = 38.dp)
            }
        }
    }
    LaunchedEffect(state.activeId) { focus.requestFocus() }
}

@Composable
private fun SessionTab(session: TermSession, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val p = LocalStudio.current
    val bg by animateColorAsState(if (active) TermBg else Color.Transparent, tween(160), label = "sessionTab")
    Row(Modifier.fillMaxHeight().background(bg).clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (session.busy) Tiles.Orange else if (active) p.accent else Color.Transparent))
        Spacer(Modifier.width(10.dp))
        FlatText(session.title, color = if (active) TermText else p.text, weight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        Box(Modifier.size(30.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = I18n.t("studio.close_tab"), tint = if (active) TermMuted else p.muted, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun OutputArea(state: StudioTerminalState, session: TermSession, onInsert: (String) -> Unit) {
    val list = rememberLazyListState()
    val q = state.query.trim()
    val rows = ArrayList<TermRow>()
    for (block in session.blocks) {
        val lines = block.lines
        val shown = if (q.isEmpty()) lines.withIndex().toList() else lines.withIndex().filter { it.value.text.contains(q, true) }
        if (q.isNotEmpty() && shown.isEmpty() && !block.command.contains(q, true)) continue
        rows += TermRow.Header(block)
        if (!block.collapsed) shown.forEach { (i, l) -> rows += TermRow.Line(block, i, l) }
    }
    var follow by remember(session.id) { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) {
        if (!list.isScrollInProgress) follow = !list.canScrollForward
    }
    LaunchedEffect(rows.size, session.id) {
        if (follow && rows.isNotEmpty()) list.scrollToItem(rows.lastIndex)
    }
    if (session.blocks.isEmpty()) {
        TerminalWelcome(onInsert = onInsert, onRun = { state.run(session, it) })
        return
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(bottom = 4.dp), state = list) {
            items(rows, key = {
                when (it) {
                    is TermRow.Header -> "h" + it.block.id
                    is TermRow.Line -> "l" + it.block.id + ":" + it.index
                }
            }) { row ->
                when (row) {
                    is TermRow.Header -> BlockHeader(row.block, state.fontSize, onRerun = { state.run(session, row.block.command) }, onInsert = onInsert)
                    is TermRow.Line -> Row(Modifier.fillMaxWidth().background(TermBlockBg).padding(start = 18.dp, end = 12.dp)) {
                        Text(
                            row.line.text,
                            style = mono.copy(color = lineColor(row.line.kind), fontSize = state.fontSize.sp),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
        AnimatedVisibility(!follow, modifier = Modifier.align(Alignment.BottomEnd), enter = fadeIn(), exit = fadeOut()) {
            Block(I18n.t("studio.term_follow"), onClick = { follow = true }, icon = Icons.Filled.VerticalAlignBottom, fill = Tiles.Slate, height = 30.dp)
        }
    }
}

@Composable
private fun BlockHeader(block: TermBlock, fontSize: Int, onRerun: () -> Unit, onInsert: (String) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val running = block.finished == null
    val status = when {
        running -> Tiles.Orange
        block.cancelled -> Tiles.Slate
        block.failed -> Tiles.Red
        else -> Tiles.Blue
    }
    val bar by animateDpAsState(if (hovered) 8.dp else 4.dp, tween(160), label = "termBar")
    val bg by animateColorAsState(if (hovered) TermHeaderBg.lift(0.04f) else TermHeaderBg, tween(140), label = "termHeader")
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) {
        while (running) {
            delay(250)
            tick++
        }
    }
    val elapsed = ((block.finished ?: System.currentTimeMillis()) - block.started).also { tick }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).entrance(0)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(bg)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null) { block.collapsed = !block.collapsed },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(bar).fillMaxHeight().background(status))
            Icon(
                if (block.collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = TermMuted,
                modifier = Modifier.padding(horizontal = 4.dp).size(16.dp)
            )
            Text("› " + block.command, style = mono.copy(color = TermText, fontWeight = FontWeight.SemiBold, fontSize = fontSize.sp), modifier = Modifier.weight(1f))
            Text(
                I18n.t("studio.term_lines", block.lines.size.toString()) + " · " + formatElapsed(elapsed),
                style = mono.copy(color = TermMuted, fontSize = 11.sp),
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            if (hovered) {
                HeaderButton(Icons.Filled.Replay, I18n.t("studio.term_rerun"), enabled = !running, onClick = onRerun)
                HeaderButton(Icons.Filled.ContentCopy, I18n.t("terminal.copy"), onClick = { copyText(blockText(block)) })
                HeaderButton(Icons.AutoMirrored.Filled.OpenInNew, I18n.t("studio.term_edit"), onClick = { onInsert(block.command) })
            }
            FlatText(
                when {
                    running -> I18n.t("studio.term_running")
                    block.cancelled -> I18n.t("studio.term_cancelled")
                    block.failed -> I18n.t("studio.term_failed")
                    else -> I18n.t("studio.term_done")
                },
                color = Color.White,
                size = 10,
                weight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxHeight().background(status).padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = if (enabled) TermText else TermMuted, modifier = Modifier.size(14.dp))
    }
}

private fun formatElapsed(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms / 1000) % 60}s"
}

@Composable
private fun TerminalWelcome(onInsert: (String) -> Unit, onRun: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("PMCL Terminal", style = mono.copy(color = TermText, fontSize = 22.sp, fontWeight = FontWeight.Light), modifier = Modifier.entrance(0))
        Text(I18n.t("terminal.welcome_hint"), style = mono.copy(color = TermMuted), modifier = Modifier.padding(top = 4.dp).entrance(1))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().height(72.dp)) {
            quickCommands.forEachIndexed { i, cmd ->
                ColorTile(I18n.t("studio.term_quick"), cmd, if (i == 0) Tiles.Blue else listOf(Tiles.Navy, Tiles.Sky, Tiles.Teal, Tiles.Slate)[(i - 1) % 4],
                    Modifier.weight(1f).fillMaxHeight(), Icons.Filled.PlayArrow, { onRun(cmd) }, valueSize = 15, index = 2 + i)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(I18n.t("studio.term_keys"), style = mono.copy(color = TermMuted, fontSize = 11.sp), modifier = Modifier.entrance(8))
    }
}

@Composable
private fun SidePanel(session: TermSession, onInsert: (String) -> Unit, onRun: (String) -> Unit) {
    val p = LocalStudio.current
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.width(220.dp).fillMaxHeight().background(TermBlockBg)) {
        Row(Modifier.fillMaxWidth().height(28.dp)) {
            listOf(I18n.t("studio.term_commands"), I18n.t("studio.term_history")).forEachIndexed { i, label ->
                Block(label, onClick = { tab = i }, fill = if (tab == i) p.accent else TermHeaderBg, modifier = Modifier.weight(1f), height = 28.dp)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (tab == 0) {
                var n = 0
                commandGroups.forEach { (key, cmds) ->
                    Text(I18n.t(key), style = mono.copy(color = TermMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
                    cmds.forEach { cmd -> SideRow(cmd, n++, onClick = { onInsert("$cmd ") }, onDouble = null) }
                }
            } else {
                if (session.history.isEmpty()) {
                    Text(I18n.t("studio.term_history_empty"), style = mono.copy(color = TermMuted, fontSize = 11.sp), modifier = Modifier.padding(12.dp))
                }
                session.history.asReversed().take(100).forEachIndexed { i, cmd ->
                    SideRow(cmd, i, onClick = { onInsert(cmd) }, onDouble = { onRun(cmd) })
                }
            }
        }
    }
}

@Composable
private fun SideRow(text: String, index: Int, onClick: () -> Unit, onDouble: (() -> Unit)?) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) TermHeaderBg else Color.Transparent, tween(120), label = "termSide")
    val bar by animateDpAsState(if (hovered) 3.dp else 0.dp, tween(140), label = "termSideBar")
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .entrance(index)
            .background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(bar).fillMaxHeight().background(TermHint))
        Text(text, style = mono.copy(color = TermText, fontSize = 12.sp), maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp).weight(1f))
        if (onDouble != null && hovered) {
            Box(Modifier.size(26.dp).clickable(onClick = onDouble), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = I18n.t("studio.term_run"), tint = TermHint, modifier = Modifier.size(14.dp))
            }
        }
    }
}
