package com.pmcl.mario

import com.pmcl.plugin.PluginContext
import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Timer
import kotlin.math.floor
import kotlin.math.min

/**
 * 用 heavyweight 的 java.awt.Canvas 承载马里奥游戏（纯 AWT 渲染）。
 *
 * 为什么必须是 heavyweight：PMCL 主窗口是玻璃/透明窗口。Compose 的 SwingPanel 里，
 * lightweight 组件（JPanel）在透明窗口下渲染不合成——paintComponent 明明被调用、
 * 面板尺寸也正确，但画面透明；而 heavyweight 组件（如 hmcl 的 JFXPanel、java.awt.Canvas）
 * 有独立 native peer，能作为合成锚点正常显示。
 *
 * 生命周期：javax.swing.Timer（约 60fps）驱动主循环，dispose 时停止。
 * 输入：键盘（KeyListener，焦点在 Canvas 上时生效）。
 */
internal class MarioPanel(
    private val ctx: PluginContext,
    highScore: Int,
    private val onRecord: (Int) -> Unit,
) : Canvas() {

    private val game = Game(highScore) { best ->
        onRecord(best)
        ctx.info("[mario] 新纪录: $best")
    }
    private val input = Input()
    private var timer: Timer? = null
    private var lastNs = 0L
    private var acc = 0.0
    private var paintLogged = false

    init {
        background = Color(0x14, 0x16, 0x1C)
        isFocusable = true
        wireKeyboard()

        Sprites.init()
        DiagLog.log("Sprites.init OK")

        DiagLog.log("MarioPanel(Canvas) init, starting timer")
        timer = Timer(16) { tick() }
        timer?.start()
    }

    // ==================== 游戏循环 ====================

    private fun tick() {
        val now = System.nanoTime()
        if (lastNs == 0L) {
            lastNs = now
            return
        }
        var dt = (now - lastNs) / 1_000_000_000.0
        lastNs = now
        if (dt > 0.25) dt = 0.25 // 避免卡顿后一次性补太多帧
        acc += dt
        var steps = 0
        while (acc >= FIXED && steps < 5) {
            game.update(FIXED.toFloat(), input)
            acc -= FIXED
            steps++
        }
        if (steps == 5) acc = 0.0
        repaint()
    }

    // ==================== 渲染 ====================

    override fun paint(g: Graphics) {
        val g2 = g as Graphics2D
        // 手动铺满不透明深色背景
        g2.color = Color(0x14, 0x16, 0x1C)
        g2.fillRect(0, 0, width, height)

        if (!paintLogged) {
            paintLogged = true
            DiagLog.log("first paint: panel=${width}x$height")
        }
        val w = width.toDouble() / VW
        val h = height.toDouble() / VH
        if (w <= 0 || h <= 0) return
        val s = floor(min(w, h)).coerceAtLeast(1.0)
        val ox = ((width - VW * s) / 2.0).toInt()
        val oy = ((height - VH * s) / 2.0).toInt()
        g2.translate(ox.toDouble(), oy.toDouble())
        game.render(g2, s.toFloat())
    }

    /** 直接 paint，跳过默认的「清屏再 paint」，避免闪烁。 */
    override fun update(g: Graphics) {
        paint(g)
    }

    fun dispose() {
        timer?.stop()
        timer = null
        DiagLog.log("MarioPanel disposed")
    }

    // ==================== 输入 ====================

    private val locked = HashSet<Int>()

    private fun wireKeyboard() {
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_LEFT, KeyEvent.VK_A -> input.left = true
                    KeyEvent.VK_RIGHT, KeyEvent.VK_D -> input.right = true
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> input.down = true
                    KeyEvent.VK_SPACE, KeyEvent.VK_UP, KeyEvent.VK_W, KeyEvent.VK_K, KeyEvent.VK_Z -> input.jump = true
                    KeyEvent.VK_SHIFT, KeyEvent.VK_J, KeyEvent.VK_X, KeyEvent.VK_CONTROL -> input.fire = true
                    KeyEvent.VK_ENTER -> { if (locked.add(e.keyCode)) game.confirm() }
                    KeyEvent.VK_ESCAPE, KeyEvent.VK_P -> { if (locked.add(e.keyCode)) game.togglePause() }
                    KeyEvent.VK_R -> { if (locked.add(e.keyCode)) game.restartNow() }
                }
            }

            override fun keyReleased(e: KeyEvent) {
                locked.remove(e.keyCode)
                when (e.keyCode) {
                    KeyEvent.VK_LEFT, KeyEvent.VK_A -> input.left = false
                    KeyEvent.VK_RIGHT, KeyEvent.VK_D -> input.right = false
                    KeyEvent.VK_DOWN, KeyEvent.VK_S -> input.down = false
                    KeyEvent.VK_SPACE, KeyEvent.VK_UP, KeyEvent.VK_W, KeyEvent.VK_K, KeyEvent.VK_Z -> input.jump = false
                    KeyEvent.VK_SHIFT, KeyEvent.VK_J, KeyEvent.VK_X, KeyEvent.VK_CONTROL -> input.fire = false
                }
            }
        })
    }

    private companion object {
        private const val FIXED = 1.0 / 60.0
    }
}
