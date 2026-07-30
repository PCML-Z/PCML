package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.cli.PmclCli
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassContainerColor
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 终端页面：嵌入式 Shell，复用 [PmclCli]。
 *
 * 能力：搜索过滤、复制、字号、Tab 补全、取消执行、自动滚动暂停、语义着色、i18n。
 * 玻璃/壁纸主题下输出区与输入条使用半透明毛玻璃底，避免实色色块。
 */
@Composable
fun TerminalPage(vm: LauncherViewModel) {
    val themeState = LocalThemeState.current
    // 毛玻璃仅跟玻璃主题开关；壁纸存在时仍让页面透明透出背景
    val glassOn = themeState.glassTheme
    val wallpaperOn = themeState.customBackground || themeState.parallaxBackground
    val lines = remember { mutableStateListOf<TerminalLine>() }
    val seqCounter = remember { java.util.concurrent.atomic.AtomicLong(0) }
    fun nextSeq() = seqCounter.incrementAndGet()
    var input by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    var executing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var fontSp by remember { mutableStateOf(13) }
    var autoScroll by remember { mutableStateOf(true) }
    var copiedFlash by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var execJob by remember { mutableStateOf<Job?>(null) }
    val cli = remember { PmclCli(vm.core) }
    val commandNames = remember { PmclCli.listCommandNames() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val warnColor = MaterialTheme.colorScheme.tertiary
    val pageBg = if (glassOn || wallpaperOn) Color.Transparent else MaterialTheme.colorScheme.background
    val outputBg = glassContainerColor()
    val inputBg = glassSurfaceVariantColor()

    LaunchedEffect(Unit) {
        lines.add(TerminalLine(nextSeq(), "", LineType.EMPTY))
        lines.add(TerminalLine(nextSeq(), "+===========================================+", LineType.BANNER))
        lines.add(TerminalLine(nextSeq(), "|          PMCL Terminal  v3.0.0            |", LineType.BANNER))
        lines.add(TerminalLine(nextSeq(), "|   Minecraft Launcher - Shell Mode         |", LineType.BANNER))
        lines.add(TerminalLine(nextSeq(), "+===========================================+", LineType.BANNER))
        lines.add(TerminalLine(nextSeq(), "", LineType.EMPTY))
        lines.add(TerminalLine(nextSeq(), I18n.t("terminal.welcome_hint"), LineType.HINT))
        lines.add(TerminalLine(nextSeq(), "", LineType.EMPTY))
    }

    val filteredLines = remember(lines.size, searchQuery, lines.lastOrNull()?.seq) {
        if (searchQuery.isBlank()) lines.toList()
        else lines.filter {
            it.type == LineType.EMPTY || it.text.contains(searchQuery, ignoreCase = true)
        }
    }

    LaunchedEffect(filteredLines.size, autoScroll, executing) {
        if (autoScroll && filteredLines.isNotEmpty()) {
            scrollState.scrollToItem(filteredLines.lastIndex)
        }
    }

    // 用户上滑时暂停自动滚动；接近底部时恢复
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress && filteredLines.isNotEmpty()) {
            val info = scrollState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            autoScroll = lastVisible >= filteredLines.lastIndex - 2
        }
    }

    LaunchedEffect(copiedFlash) {
        if (copiedFlash) {
            kotlinx.coroutines.delay(1500)
            copiedFlash = false
        }
    }

    fun runCommand(cmd: String) {
        if (cmd.isBlank() || executing) return
        historyIndex = -1
        if (history.isEmpty() || history.last() != cmd) {
            history.add(cmd)
            if (history.size > 1000) history.removeAt(0)
        }
        suggestions = emptyList()
        execJob = scope.launch {
            executeCommand(
                cli = cli,
                command = cmd,
                lines = lines,
                nextSeq = ::nextSeq,
                setExecuting = { executing = it },
                onCancelled = { lines.add(TerminalLine(nextSeq(), I18n.t("terminal.cancelled"), LineType.HINT)) }
            )
        }
    }

    fun completeTab() {
        val token = input.trim().substringAfterLast(' ', input.trim())
        if (token.isEmpty()) {
            suggestions = commandNames.take(12)
            return
        }
        val matches = commandNames.filter { it.startsWith(token, ignoreCase = true) }.distinct()
        when {
            matches.isEmpty() -> suggestions = emptyList()
            matches.size == 1 -> {
                val prefix = input.trim().substringBeforeLast(' ', missingDelimiterValue = "")
                input = if (prefix.isEmpty()) matches[0] + " " else "$prefix ${matches[0]} "
                suggestions = emptyList()
            }
            else -> {
                val common = longestCommonPrefix(matches)
                if (common.length > token.length) {
                    val prefix = input.trim().substringBeforeLast(' ', missingDelimiterValue = "")
                    input = if (prefix.isEmpty()) common else "$prefix $common"
                }
                suggestions = matches.take(16)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                I18n.t("terminal.title"),
                color = primary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            if (executing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = tertiary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    I18n.t("terminal.executing"),
                    color = tertiary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                IconButton(
                    onClick = {
                        execJob?.cancel()
                        executing = false
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.Stop, I18n.t("terminal.cancel"), tint = error, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                I18n.t("terminal.history_count", history.size),
                color = outline,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            IconButton(
                onClick = { fontSp = (fontSp - 1).coerceAtLeast(10) },
                modifier = Modifier.size(28.dp)
            ) { Icon(Icons.Filled.Remove, I18n.t("terminal.font_smaller"), tint = outline, modifier = Modifier.size(16.dp)) }
            Text(
                "${fontSp}sp",
                color = outline,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            IconButton(
                onClick = { fontSp = (fontSp + 1).coerceAtMost(22) },
                modifier = Modifier.size(28.dp)
            ) { Icon(Icons.Filled.Add, I18n.t("terminal.font_larger"), tint = outline, modifier = Modifier.size(16.dp)) }
            IconButton(
                onClick = {
                    val text = lines.filter { it.type != LineType.EMPTY }.joinToString("\n") { it.text }
                    try {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                        copiedFlash = true
                    } catch (_: Throwable) {}
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    if (copiedFlash) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    I18n.t("terminal.copy"),
                    tint = if (copiedFlash) primary else outline,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { autoScroll = !autoScroll },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    if (autoScroll) Icons.Filled.KeyboardArrowDown else Icons.Filled.Pause,
                    I18n.t("terminal.autoscroll"),
                    tint = if (autoScroll) primary else outline,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { lines.clear() },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Filled.Clear, I18n.t("terminal.clear"), tint = outline, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            placeholder = { Text(I18n.t("terminal.search_hint")) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Clear, null) }
                }
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            colors = if (glassOn) {
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = outputBg,
                    unfocusedContainerColor = outputBg,
                    disabledContainerColor = outputBg
                )
            } else {
                OutlinedTextFieldDefaults.colors()
            }
        )

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (glassOn) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .blur(16.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.40f))
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (glassOn) Color.Transparent else outputBg, RoundedCornerShape(6.dp))
                    .padding(12.dp),
                state = scrollState
            ) {
                items(filteredLines, key = { it.seq }) { line ->
                    when (line.type) {
                        LineType.EMPTY -> Spacer(Modifier.height(2.dp))
                        else -> Text(
                            line.text,
                            color = lineColor(line, primary, secondary, tertiary, error, onSurface, warnColor),
                            fontSize = fontSp.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (executing) {
                    item(key = "cursor") {
                        Text("_", color = tertiary, fontSize = fontSp.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                suggestions.joinToString("  "),
                color = outline,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            if (glassOn) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .blur(14.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f))
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (glassOn) Color.Transparent else inputBg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "pmcl> ",
                    color = secondary,
                    fontSize = (fontSp + 1).sp,
                    fontFamily = FontFamily.Monospace
                )
                BasicTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        suggestions = emptyList()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .onKeyEvent { event ->
                            when {
                                event.key == Key.Tab && event.type == KeyEventType.KeyDown -> {
                                    completeTab()
                                    true
                                }
                                event.key == Key.Enter && event.type == KeyEventType.KeyUp -> {
                                    if (input.isNotBlank() && !executing) {
                                        val cmd = input.trim()
                                        input = ""
                                        runCommand(cmd)
                                    }
                                    true
                                }
                                event.key == Key.DirectionUp && event.type == KeyEventType.KeyUp -> {
                                    if (history.isNotEmpty()) {
                                        historyIndex = if (historyIndex < 0) history.lastIndex
                                        else (historyIndex - 1).coerceAtLeast(0)
                                        input = history[historyIndex]
                                    }
                                    true
                                }
                                event.key == Key.DirectionDown && event.type == KeyEventType.KeyUp -> {
                                    if (history.isNotEmpty() && historyIndex >= 0) {
                                        historyIndex += 1
                                        input = if (historyIndex >= history.size) {
                                            historyIndex = -1
                                            ""
                                        } else history[historyIndex]
                                    }
                                    true
                                }
                                event.key == Key.Escape && event.type == KeyEventType.KeyUp -> {
                                    suggestions = emptyList()
                                    true
                                }
                                else -> false
                            }
                        },
                    textStyle = TextStyle(
                        color = onSurface,
                        fontSize = (fontSp + 1).sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(secondary),
                    singleLine = true,
                    enabled = !executing
                )
            }
        }
    }
}

private fun lineColor(
    line: TerminalLine,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    error: Color,
    onSurface: Color,
    warn: Color
): Color = when (line.type) {
    LineType.BANNER -> primary
    LineType.COMMAND -> secondary
    LineType.ERROR -> error
    LineType.HINT -> tertiary
    LineType.WARN -> warn
    LineType.OUTPUT -> onSurface
    LineType.EMPTY -> onSurface
}

private suspend fun executeCommand(
    cli: PmclCli,
    command: String,
    lines: MutableList<TerminalLine>,
    nextSeq: () -> Long,
    setExecuting: (Boolean) -> Unit,
    onCancelled: () -> Unit
) {
    lines.add(TerminalLine(nextSeq(), "pmcl> $command", LineType.COMMAND))
    setExecuting(true)
    try {
        val trimmed = command.trim()
        if (trimmed.equals("clear", ignoreCase = true) || trimmed.equals("cls", ignoreCase = true)) {
            lines.clear()
            return
        }
        val lowerCmd = trimmed.split("\\s+".toRegex()).firstOrNull()?.lowercase()
        if (lowerCmd == "exit" || lowerCmd == "quit") {
            lines.add(TerminalLine(nextSeq(), I18n.t("terminal.exit_hint"), LineType.HINT))
            return
        }
        val parts = trimmed.split("\\s+".toRegex()).toTypedArray()
        if (parts.isEmpty() || parts[0].isEmpty()) return

        val output = withContext(Dispatchers.IO) {
            cli.executeCaptured(parts)
        }
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
            onCancelled()
            return
        }
        if (output.isNotEmpty()) {
            output.split('\n').forEach { line ->
                if (line.isNotEmpty()) {
                    lines.add(TerminalLine(nextSeq(), line, classifyOutputLine(line)))
                    while (lines.size > 5000) lines.removeAt(0)
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        onCancelled()
        throw e
    } catch (e: Throwable) {
        lines.add(TerminalLine(nextSeq(), "Error: ${e.message}", LineType.ERROR))
    } finally {
        setExecuting(false)
    }
}

private fun classifyOutputLine(line: String): LineType {
    val lower = line.lowercase()
    return when {
        line.startsWith("Error") || line.startsWith("Failed") ||
            lower.contains("exception") || lower.contains("error:") ||
            lower.contains("not found") || lower.contains("unknown") ||
            lower.contains("/error]") || lower.contains("[error") -> LineType.ERROR
        lower.contains("/warn]") || lower.contains("[warn") ||
            lower.contains("warning") -> LineType.WARN
        else -> LineType.OUTPUT
    }
}

private fun longestCommonPrefix(items: List<String>): String {
    if (items.isEmpty()) return ""
    var prefix = items[0]
    for (i in 1 until items.size) {
        val s = items[i]
        var j = 0
        while (j < prefix.length && j < s.length && prefix[j].equals(s[j], ignoreCase = true)) j++
        prefix = prefix.substring(0, j)
        if (prefix.isEmpty()) break
    }
    return prefix
}

private enum class LineType {
    EMPTY, BANNER, COMMAND, OUTPUT, ERROR, HINT, WARN
}

private data class TerminalLine(val seq: Long, val text: String, val type: LineType)
