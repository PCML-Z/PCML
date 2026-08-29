package com.pmcl.mario

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.min

/**
 * 极简芯片音效：不依赖任何外部音频文件，直接合成方波 PCM 后写入
 * [SourceDataLine]。所有播放都在插件自己的线程组里的单条守护线程上排队，
 * 插件禁用时通过 [shutdown] 关闭。
 *
 * 任何一步失败（无声卡 / 无权限 / JDK 未带 java.desktop）都静默降级为静音，
 * 绝不影响游戏主循环。
 */
internal object Sfx {

    private const val SR = 22050

    var enabled = true
        set(value) {
            field = value
            if (!value) stopAll()
        }

    private var pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pmcl-mario-sfx").apply { isDaemon = true }
    }
    private var line: SourceDataLine? = null
    private var failed = false

    fun init(factory: ThreadFactory) {
        pool = Executors.newSingleThreadExecutor(factory)
    }

    fun shutdown() {
        enabled = false
        try { pool.shutdownNow() } catch (_: Throwable) {}
        closeLine()
    }

    private fun ensureLine(): SourceDataLine? {
        if (failed) return null
        val existing = line
        if (existing != null && existing.isOpen) return existing
        return try {
            val fmt = AudioFormat(SR.toFloat(), 16, 1, true, false)
            val l = AudioSystem.getSourceDataLine(fmt)
            l.open(fmt, SR / 2)
            l.start()
            line = l
            l
        } catch (_: Throwable) {
            failed = true
            null
        }
    }

    private fun closeLine() {
        try { line?.stop() } catch (_: Throwable) {}
        try { line?.close() } catch (_: Throwable) {}
        line = null
    }

    private fun stopAll() {
        try { line?.stop() } catch (_: Throwable) {}
        try { line?.flush() } catch (_: Throwable) {}
    }

    /** notes: (频率Hz, 时长ms) */
    fun play(notes: List<Pair<Int, Int>>) {
        if (!enabled || failed) return
        try {
            pool.submit(Runnable { render(notes) })
        } catch (_: Throwable) {
            // 线程池已关闭：直接静音
        }
    }

    private fun render(notes: List<Pair<Int, Int>>) {
        val l = ensureLine() ?: return
        val totalSamples = notes.sumOf { (SR * it.second / 1000).coerceAtLeast(1) }
        val buf = ByteArray(totalSamples * 2)
        var idx = 0
        for ((freq, ms) in notes) {
            val n = (SR * ms / 1000).coerceAtLeast(1)
            val half = (SR.toDouble() / freq.coerceAtLeast(40) / 2.0).coerceAtLeast(1.0).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / n
                // 简单包络：快速起音 + 尾部衰减，避免爆音
                val env = min(1.0, min(t * 30.0, (1.0 - t) * 6.0 + 0.25))
                val sign = if (i % (half * 2) < half) 1.0 else -1.0
                val s = (sign * env * 3800.0).toInt()
                buf[idx++] = (s and 0xFF).toByte()
                buf[idx++] = ((s shr 8) and 0xFF).toByte()
            }
        }
        try {
            l.write(buf, 0, idx)
        } catch (_: Throwable) {
            failed = true
        }
    }

    // ---------------- 音效表 ----------------

    fun jump() = play(listOf(392 to 55, 659 to 95))
    fun coin() = play(listOf(988 to 55, 1319 to 190))
    fun stomp() = play(listOf(330 to 70, 180 to 70))
    fun bump() = play(listOf(150 to 60))
    fun brick() = play(listOf(240 to 40, 170 to 70))
    fun fireball() = play(listOf(880 to 35, 620 to 45))
    fun kick() = play(listOf(220 to 60, 160 to 50))
    fun appear() = play(listOf(523 to 60, 784 to 90))
    fun powerUp() = play(listOf(392 to 60, 523 to 60, 659 to 60, 784 to 60, 1047 to 160))
    fun powerDown() = play(listOf(659 to 70, 494 to 70, 392 to 70, 262 to 150))
    fun oneUp() = play(listOf(523 to 70, 659 to 70, 784 to 70, 1047 to 70, 784 to 70, 1047 to 220))
    fun die() = play(listOf(523 to 110, 392 to 110, 262 to 130, 196 to 420))
    fun flagPole() = play(listOf(392 to 70, 523 to 70, 659 to 70, 784 to 70, 1047 to 70, 1319 to 380))
    fun clear() = play(listOf(523 to 90, 659 to 90, 784 to 90, 1047 to 90, 1319 to 90, 1568 to 420))
}
