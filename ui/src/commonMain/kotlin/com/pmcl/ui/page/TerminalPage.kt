package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.cli.PmclCli
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * 终端页面：在 GUI 中嵌入终端式界面，通过命令操作启动器所有核心功能。
 *
 * 复用 PmclCli 的命令解析逻辑，通过临时重定向 System.out/System.err 捕获输出。
 * 命令在后台协程中执行，不阻塞 UI。
 *
 * 配色统一使用 MaterialTheme.colorScheme，与启动器主题保持一致。
 */
@Composable
fun TerminalPage(vm: LauncherViewModel) {
    val lines = remember { mutableStateListOf<TerminalLine>() }
    // 全局递增序号，作为 LazyColumn 稳定 key，避免裁剪头部时整列重组
    val seqCounter = remember { java.util.concurrent.atomic.AtomicLong(0) }
    fun nextSeq() = seqCounter.incrementAndGet()
    var input by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    var executing by remember { mutableStateOf(false) }
    val cli = remember { PmclCli(vm.core) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()

    // 主题色（终端专用语义化映射）
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline

    LaunchedEffect(Unit) {
        lines.add(TerminalLine(nextSeq(), "", LineType.EMPTY))
        lines.add(TerminalLine(nextSeq(), "+===========================================+", LineType.BANNER))
        lines.add(TerminalLine(nextSeq(), "|          PMCL Terminal  v3.0.0            |", LineType.BANNER))
        lines.add(TerminalLine(nextSeq(), "|   Minecraft Launcher - Shell Mode         |", LineType.BANNER))
        lines.add(TerminalLine(nextSeq(), "|   Access all launcher core features       |", LineType.BANNER))
        lines.add(TerminalLine(nextSeq(), "+===========================================+", LineType.BANNER))
        lines.add(TerminalLine(nextSeq(), "", LineType.EMPTY))
        lines.add(TerminalLine(nextSeq(), "Type 'help' for available commands. Type 'clear' to clear screen.", LineType.HINT))
        lines.add(TerminalLine(nextSeq(), "", LineType.EMPTY))
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) scrollState.scrollToItem(lines.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(8.dp)
    ) {
        // 顶部工具栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "PMCL Shell",
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
                Spacer(Modifier.width(8.dp))
                Text(
                    "Executing...",
                    color = tertiary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "History: ${history.size}",
                color = outline,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { lines.clear() },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = "Clear",
                    tint = outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 终端输出区
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(surface, RoundedCornerShape(6.dp))
                .padding(12.dp),
            state = scrollState
        ) {
            itemsIndexed(lines, key = { _, line -> line.seq }) { _, line ->
                when (line.type) {
                    LineType.EMPTY -> Spacer(Modifier.height(2.dp))
                    LineType.BANNER -> Text(
                        line.text,
                        color = primary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    LineType.COMMAND -> Text(
                        line.text,
                        color = secondary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    LineType.OUTPUT -> Text(
                        line.text,
                        color = onSurface,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    LineType.ERROR -> Text(
                        line.text,
                        color = error,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    LineType.HINT -> Text(
                        line.text,
                        color = tertiary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (executing) {
                item {
                    Text(
                        "_",
                        color = tertiary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 输入框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "pmcl> ",
                color = secondary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .onKeyEvent { event ->
                        when {
                            event.key == Key.Enter && event.type == KeyEventType.KeyUp -> {
                                if (input.isNotBlank() && !executing) {
                                    val cmd = input.trim()
                                    input = ""
                                    historyIndex = -1
                                    if (history.isEmpty() || history.last() != cmd) {
                                        history.add(cmd)
                                        if (history.size > 1000) history.removeAt(0)
                                    }
                                    scope.launch {
                                        executeCommand(cli, cmd, lines, ::nextSeq) { executing = it }
                                    }
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
                                    historyIndex = (historyIndex + 1)
                                    input = if (historyIndex >= history.size) {
                                        historyIndex = -1
                                        ""
                                    } else {
                                        history[historyIndex]
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    },
                textStyle = TextStyle(
                    color = onSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(secondary),
                singleLine = true,
                enabled = !executing
            )
        }
    }
}

/** 执行单条命令，捕获 PmclCli 的 System.out 输出 */
private suspend fun executeCommand(
    cli: PmclCli,
    command: String,
    lines: MutableList<TerminalLine>,
    nextSeq: () -> Long,
    setExecuting: (Boolean) -> Unit
) {
    lines.add(TerminalLine(nextSeq(), "pmcl> $command", LineType.COMMAND))
    setExecuting(true)

    try {
        if (command.trim().equals("clear", ignoreCase = true) || command.trim().equals("cls", ignoreCase = true)) {
            lines.clear()
            return
        }
        val lowerCmd = command.trim().split("\\s+".toRegex()).firstOrNull()?.lowercase()
        if (lowerCmd == "exit" || lowerCmd == "quit") {
            lines.add(TerminalLine(nextSeq(), "Exit is not supported in GUI terminal. Use 'clear' to clear screen.", LineType.HINT))
            return
        }

        val parts = command.trim().split("\\s+".toRegex()).toTypedArray()
        if (parts.isEmpty() || parts[0].isEmpty()) return

        val output = withContext(Dispatchers.IO) {
            val baos = ByteArrayOutputStream()
            val originalOut = System.out
            val originalErr = System.err
            val ps = PrintStream(baos, true, "UTF-8")
            try {
                System.setOut(ps)
                System.setErr(ps)
                cli.execute(parts)
                ps.flush()
                baos.toString("UTF-8")
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        }

        if (output.isNotEmpty()) {
            output.split("\n").forEach { line ->
                if (line.isNotEmpty()) {
                    val type = if (line.startsWith("Error") || line.startsWith("Failed") ||
                        line.contains("Exception") || line.contains("Error:") ||
                        line.contains("not found") || line.contains("Unknown"))
                        LineType.ERROR else LineType.OUTPUT
                    lines.add(TerminalLine(nextSeq(), line, type))
                    if (lines.size > 5000) {
                        lines.removeAt(0)
                    }
                }
            }
        }
    } catch (e: Throwable) {
        lines.add(TerminalLine(nextSeq(), "Error: ${e.message}", LineType.ERROR))
    } finally {
        setExecuting(false)
    }
}

/** 终端行类型 */
private enum class LineType {
    EMPTY, BANNER, COMMAND, OUTPUT, ERROR, HINT
}

/** 终端行数据 */
private data class TerminalLine(val seq: Long, val text: String, val type: LineType)
