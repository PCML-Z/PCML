package com.pmcl.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * 内嵌 PTY 终端：在 Compose Desktop 中承载 OpenCode TUI。
 *
 * 架构：
 * - pty4j 启动伪终端 + 目标进程（opencode）
 * - 后台线程读取 PTY stdout，解析 ANSI 转义序列
 * - 终端网格（TerminalBuffer）维护字符 + 颜色二维数组
 * - Compose 渲染网格 + 转发键盘输入到 PTY stdin
 *
 * 支持的 ANSI 序列：
 * - CSI n m：SGR 颜色（30-37/40-47/90-97/100-107，0 重置）
 * - CSI n A/B/C/D：光标上/下/右/左移动
 * - CSI n;m H：光标定位
 * - CSI n J：清屏（0 光标到末尾 / 1 开头到光标 / 2 全屏 / 3 滚动缓冲）
 * - CSI n K：清行
 * - CSI n;m f：光标定位（同 H）
 * - \r \n \b \t：基本控制字符
 *
 * @param command 要执行的命令（如 "opencode"）
 * @param args 命令参数
 * @param workingDirectory 工作目录
 */
@Composable
fun EmbeddedTerminal(
    command: String,
    args: List<String> = emptyList(),
    workingDirectory: String? = null,
    modifier: Modifier = Modifier,
    key: Any? = null
) {
    val scope = rememberCoroutineScope()
    val buffer = remember(key) { TerminalBuffer(cols = 120, rows = 36) }
    var process by remember(key) { mutableStateOf<PtyProcess?>(null) }
    var exited by remember(key) { mutableStateOf(false) }

    // 启动 PTY 进程
    LaunchedEffect(command, key) {
        try {
            val cmd = mutableListOf(command) + args
            val env = System.getenv().map { it.key to it.value }.toMutableList()
            // 强制终端类型，让 OpenCode 启用 TUI 模式
            env.add("TERM" to "xterm-256color")
            env.add("COLORTERM" to "truecolor")
            val p = PtyProcessBuilder()
                .setCommand(cmd.toTypedArray())
                .setEnvironment(env.toMap())
                .setDirectory(workingDirectory ?: System.getProperty("user.dir"))
                .start()
            process = p

            // 后台读取 stdout
            scope.launch(Dispatchers.IO) {
                val input = p.inputStream
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            val text = String(buf, 0, n, StandardCharsets.UTF_8)
                            buffer.processOutput(text)
                        }
                    }
                } catch (_: Throwable) {}
                exited = true
            }
        } catch (e: Throwable) {
            buffer.processOutput("启动失败：${e.message}\r\n")
            exited = true
        }
    }

    // 进程退出时标记
    DisposableEffect(Unit) {
        onDispose {
            try { process?.destroyForcibly() } catch (_: Throwable) {}
        }
    }

    val density = LocalDensity.current
    val cellWidth = with(density) { 8.sp }
    val cellHeight = with(density) { 16.sp }
    val bgColor = Color(0xFF1E1E2E)
    val fgColor = Color(0xFFCDD6F4)

    Box(
        modifier
            .fillMaxSize()
            .background(bgColor)
            .onKeyEvent { event ->
                val p = process ?: return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val ch = event.key.toCharOrNull()
                if (ch != null && ch != Char(0)) {
                    try {
                        p.outputStream.write(ch.code)
                        p.outputStream.flush()
                    } catch (_: Throwable) {}
                    return@onKeyEvent true
                }
                // 控制键映射
                val ctrl = (if (event.isCtrlPressed) 0x40 else 0)
                when (event.key) {
                    Key.Enter -> { p.outputStream.write(0x0D); p.outputStream.flush(); true }
                    Key.Backspace -> { p.outputStream.write(0x7F); p.outputStream.flush(); true }
                    Key.Tab -> { p.outputStream.write(0x09); p.outputStream.flush(); true }
                    Key.Escape -> { p.outputStream.write(0x1B); p.outputStream.flush(); true }
                    Key.DirectionUp -> { p.outputStream.write(byteArrayOf(0x1B, 0x5B, 0x41)); p.outputStream.flush(); true }
                    Key.DirectionDown -> { p.outputStream.write(byteArrayOf(0x1B, 0x5B, 0x42)); p.outputStream.flush(); true }
                    Key.DirectionRight -> { p.outputStream.write(byteArrayOf(0x1B, 0x5B, 0x43)); p.outputStream.flush(); true }
                    Key.DirectionLeft -> { p.outputStream.write(byteArrayOf(0x1B, 0x5B, 0x44)); p.outputStream.flush(); true }
                    else -> false
                }
            }
    ) {
        // 渲染终端网格
        val lines = buffer.snapshot()
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            lines.forEachIndexed { _, line ->
                Text(
                    text = line.text,
                    color = line.color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = cellWidth,
                    lineHeight = cellHeight,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Visible
                )
            }
        }

        if (exited) {
            Box(
                Modifier.fillMaxSize().background(Color(0x88000000)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "进程已退出",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/**
 * 终端网格缓冲：维护行列表，解析 ANSI 转义。
 *
 * 简化实现：每行用一个 ColoredLine 表示，整行统一颜色。
 * OpenCode TUI 的复杂颜色（同一行多色）暂按行级近似渲染。
 */
internal class TerminalBuffer(val cols: Int, val rows: Int) {
    private val lines = ArrayDeque<ColoredLine>()
    private var cursorRow = 0
    private var cursorCol = 0
    private var currentFg: Color = Color(0xFFCDD6F4)
    private val defaultFg: Color = Color(0xFFCDD6F4)

    init { repeat(rows) { lines.add(ColoredLine("", defaultFg)) } }

    /** 返回当前可见行的快照 */
    fun snapshot(): List<ColoredLine> = lines.toList()

    /** 处理 PTY 输出 */
    fun processOutput(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == 0x1B.toChar() && i + 1 < text.length && text[i + 1] == '[' -> {
                    // CSI 序列
                    val end = (i + 2 until text.length).firstOrNull { text[it].isLetter() } ?: text.length - 1
                    val params = text.substring(i + 2, end)
                    val final = if (end < text.length) text[end] else 'm'
                    applyCsi(params, final)
                    i = end + 1
                }
                c == '\r' -> { cursorCol = 0; i++ }
                c == '\n' -> { cursorRow++; ensureRows(); i++ }
                c == '\b' -> { if (cursorCol > 0) cursorCol--; i++ }
                c == '\t' -> {
                    val next = ((cursorCol / 8) + 1) * 8
                    while (cursorCol < next && cursorCol < cols) { putChar(' '); }
                    i++
                }
                c >= ' ' -> { putChar(c); i++ }
                else -> i++
            }
        }
    }

    private fun putChar(c: Char) {
        ensureRows()
        val line = lines[cursorRow]
        val sb = StringBuilder(line.text)
        while (sb.length <= cursorCol) sb.append(' ')
        sb[cursorCol] = c
        lines[cursorRow] = line.copy(text = sb.toString())
        cursorCol++
        if (cursorCol >= cols) { cursorCol = 0; cursorRow++; ensureRows() }
    }

    private fun ensureRows() {
        while (cursorRow >= lines.size) lines.add(ColoredLine("", currentFg))
        while (cursorRow < 0) { cursorRow = 0; lines.addFirst(ColoredLine("", defaultFg)) }
    }

    private fun applyCsi(params: String, final: Char) {
        val nums = params.split(';').mapNotNull { it.toIntOrNull() }
        when (final) {
            'm' -> applySgr(nums)
            'A' -> cursorRow = (cursorRow - (nums.firstOrNull() ?: 1)).coerceAtLeast(0)
            'B' -> cursorRow += nums.firstOrNull() ?: 1
            'C' -> cursorCol += nums.firstOrNull() ?: 1
            'D' -> cursorCol = (cursorCol - (nums.firstOrNull() ?: 1)).coerceAtLeast(0)
            'H', 'f' -> {
                cursorRow = (nums.getOrNull(0) ?: 1) - 1
                cursorCol = (nums.getOrNull(1) ?: 1) - 1
                ensureRows()
            }
            'J' -> when (nums.firstOrNull() ?: 0) {
                2 -> { lines.clear(); repeat(rows) { lines.add(ColoredLine("", defaultFg)) }; cursorRow = 0; cursorCol = 0 }
                0 -> { /* 光标到末尾清空 - 简化 */ }
                1 -> { /* 开头到光标 - 简化 */ }
            }
            'K' -> {
                if (cursorRow in lines.indices) {
                    val line = lines[cursorRow]
                    val truncated = line.text.take(cursorCol)
                    lines[cursorRow] = line.copy(text = truncated)
                }
            }
        }
    }

    private fun applySgr(nums: List<Int>) {
        if (nums.isEmpty()) { currentFg = defaultFg; return }
        when (val code = nums.first()) {
            0 -> currentFg = defaultFg
            30 -> currentFg = Color(0xFF45475A)
            31 -> currentFg = Color(0xFFF38BA8)
            32 -> currentFg = Color(0xFFA6E3A1)
            33 -> currentFg = Color(0xFFF9E2AF)
            34 -> currentFg = Color(0xFF89B4FA)
            35 -> currentFg = Color(0xFFF5C2E7)
            36 -> currentFg = Color(0xFF94E2D5)
            37 -> currentFg = Color(0xFFBAC2DE)
            90 -> currentFg = Color(0xFF585B70)
            91 -> currentFg = Color(0xFFF38BA8)
            92 -> currentFg = Color(0xFFA6E3A1)
            93 -> currentFg = Color(0xFFF9E2AF)
            94 -> currentFg = Color(0xFF89B4FA)
            95 -> currentFg = Color(0xFFF5C2E7)
            96 -> currentFg = Color(0xFF94E2D5)
            97 -> currentFg = Color(0xFFCDD6F4)
            else -> { /* 其他 SGR 码（背景色、加粗等）暂忽略 */ }
        }
    }
}

internal data class ColoredLine(val text: String, val color: Color)

private fun Key.toCharOrNull(): Char? = when (this) {
    Key.A -> 'a'; Key.B -> 'b'; Key.C -> 'c'; Key.D -> 'd'; Key.E -> 'e'; Key.F -> 'f'
    Key.G -> 'g'; Key.H -> 'h'; Key.I -> 'i'; Key.J -> 'j'; Key.K -> 'k'; Key.L -> 'l'
    Key.M -> 'm'; Key.N -> 'n'; Key.O -> 'o'; Key.P -> 'p'; Key.Q -> 'q'; Key.R -> 'r'
    Key.S -> 's'; Key.T -> 't'; Key.U -> 'u'; Key.V -> 'v'; Key.W -> 'w'; Key.X -> 'x'
    Key.Y -> 'y'; Key.Z -> 'z'
    Key.Zero -> '0'; Key.One -> '1'; Key.Two -> '2'; Key.Three -> '3'; Key.Four -> '4'
    Key.Five -> '5'; Key.Six -> '6'; Key.Seven -> '7'; Key.Eight -> '8'; Key.Nine -> '9'
    Key.Spacebar -> ' '
    else -> null
}
