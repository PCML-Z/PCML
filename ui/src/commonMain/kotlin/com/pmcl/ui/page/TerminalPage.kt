package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * Terminal page: embeds a terminal-like interface in the GUI to operate
 * all launcher core features via commands.
 *
 * Reuses PmclCli's command parsing logic, capturing output by temporarily
 * redirecting System.out/System.err. Commands run in background coroutines
 * without blocking the UI.
 */
@Composable
fun TerminalPage(vm: LauncherViewModel) {
    // Terminal output lines
    val lines = remember { mutableStateListOf<TerminalLine>() }
    // Current input
    var input by remember { mutableStateOf("") }
    // Command history
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    // Whether a command is executing
    var executing by remember { mutableStateOf(false) }
    // PmclCli instance — share the same LauncherCore as the GUI
    val cli = remember { PmclCli(vm.core) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Show welcome banner on startup
    LaunchedEffect(Unit) {
        lines.add(TerminalLine("", LineType.EMPTY))
        lines.add(TerminalLine("+===========================================+", LineType.BANNER))
        lines.add(TerminalLine("|          PMCL Terminal  v3.0.0            |", LineType.BANNER))
        lines.add(TerminalLine("|   Minecraft Launcher - Shell Mode         |", LineType.BANNER))
        lines.add(TerminalLine("|   Access all launcher core features       |", LineType.BANNER))
        lines.add(TerminalLine("+===========================================+", LineType.BANNER))
        lines.add(TerminalLine("", LineType.EMPTY))
        lines.add(TerminalLine("Type 'help' for available commands. Type 'clear' to clear screen.", LineType.HINT))
        lines.add(TerminalLine("", LineType.EMPTY))
    }

    // Auto-scroll to bottom
    LaunchedEffect(lines.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E2E))
            .padding(8.dp)
    ) {
        // Top toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "PMCL Shell",
                color = Color(0xFF89B4FA),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            if (executing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFF9E2AF)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Executing...",
                    color = Color(0xFFF9E2AF),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "History: ${history.size}",
                color = Color(0xFF6C7086),
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
                    tint = Color(0xFF6C7086),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Terminal output area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF11111B), RoundedCornerShape(6.dp))
                .verticalScroll(scrollState)
                .padding(12.dp)
        ) {
            Column {
                lines.forEach { line ->
                    when (line.type) {
                        LineType.EMPTY -> Spacer(Modifier.height(2.dp))
                        LineType.BANNER -> Text(
                            line.text,
                            color = Color(0xFF89B4FA),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        LineType.COMMAND -> Text(
                            line.text,
                            color = Color(0xFFA6E3A1),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        LineType.OUTPUT -> Text(
                            line.text,
                            color = Color(0xFFCDD6F4),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        LineType.ERROR -> Text(
                            line.text,
                            color = Color(0xFFF38BA8),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        LineType.HINT -> Text(
                            line.text,
                            color = Color(0xFFF9E2AF),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (executing) {
                    Text(
                        "_",
                        color = Color(0xFFF9E2AF),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Input box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF181825), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "pmcl> ",
                color = Color(0xFFA6E3A1),
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
                                    }
                                    scope.launch {
                                        executeCommand(cli, cmd, lines) { executing = it }
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
                    color = Color(0xFFCDD6F4),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(Color(0xFFA6E3A1)),
                singleLine = true,
                enabled = !executing
            )
        }
    }
}

/** Execute a single command, capturing PmclCli's System.out output */
private suspend fun executeCommand(
    cli: PmclCli,
    command: String,
    lines: MutableList<TerminalLine>,
    setExecuting: (Boolean) -> Unit
) {
    lines.add(TerminalLine("pmcl> $command", LineType.COMMAND))
    setExecuting(true)

    try {
        // Intercept clear command
        if (command.trim().equals("clear", ignoreCase = true) || command.trim().equals("cls", ignoreCase = true)) {
            lines.clear()
            return
        }
        // Intercept exit/quit command (do not exit the app in GUI)
        val lowerCmd = command.trim().split("\\s+".toRegex()).firstOrNull()?.lowercase()
        if (lowerCmd == "exit" || lowerCmd == "quit") {
            lines.add(TerminalLine("Exit is not supported in GUI terminal. Use 'clear' to clear screen.", LineType.HINT))
            return
        }

        val parts = command.trim().split("\\s+".toRegex()).toTypedArray()
        if (parts.isEmpty() || parts[0].isEmpty()) return

        // Execute in IO thread, capture output by redirecting System.out
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

        // Split output into lines and add to terminal
        if (output.isNotEmpty()) {
            output.split("\n").forEach { line ->
                if (line.isNotEmpty()) {
                    // Simple error line detection
                    val type = if (line.startsWith("Error") || line.startsWith("Failed") ||
                        line.contains("Exception") || line.contains("Error:") ||
                        line.contains("not found") || line.contains("Unknown"))
                        LineType.ERROR else LineType.OUTPUT
                    lines.add(TerminalLine(line, type))
                    // 限制终端行数，防止 OOM
                    if (lines.size > 5000) {
                        lines.removeAt(0)
                    }
                }
            }
        }
    } catch (e: Throwable) {
        lines.add(TerminalLine("Error: ${e.message}", LineType.ERROR))
    } finally {
        setExecuting(false)
    }
}

/** Terminal line type */
private enum class LineType {
    EMPTY, BANNER, COMMAND, OUTPUT, ERROR, HINT
}

/** Terminal line data */
private data class TerminalLine(val text: String, val type: LineType)
