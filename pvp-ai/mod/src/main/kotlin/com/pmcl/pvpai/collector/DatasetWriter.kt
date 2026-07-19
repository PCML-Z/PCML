package com.pmcl.pvpai.collector

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.pmcl.pvpai.data.GameState
import com.pmcl.pvpai.data.PlayerAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * 数据集写入器：把每 tick 的 (GameState, PlayerAction) 序列化为 JSONL 行。
 *
 * 输出目录：~/.pmcl/pvp-ai/datasets/
 * 文件名：session_<timestamp>_<seq>.jsonl
 *
 * 每行格式：
 * {"tick": 12345, "state": [...30 floats...], "action": [moveDir, jump, sneak, attack, use, hotbar, yawBin, pitchBin]}
 *
 * 一行 = 一个 tick 样本。一个 session 内连续的行构成一条时间序列，
 * 训练时按 session 分组、按 20-tick 窗口切片。
 */
class DatasetWriter {

    private val gson = GsonBuilder().create()
    private val counter = AtomicLong(0)
    private var currentFile: Path? = null
    private var sessionStartTick: Long = 0
    private var sampleCount: Int = 0

    /**
     * 开始新 session：创建新文件，写入 header 元数据。
     */
    fun startSession(startTick: Long) {
        val dir = Paths.get(System.getProperty("user.home"), ".pmcl", "pvp-ai", "datasets")
        Files.createDirectories(dir)
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val seq = counter.incrementAndGet()
        val file = dir.resolve("session_${ts}_$seq.jsonl")
        currentFile = file
        sessionStartTick = startTick
        sampleCount = 0
    }

    /**
     * 写入一对 (state, action)。
     * 若文件未启动则忽略。
     */
    fun write(state: GameState, action: PlayerAction) {
        val file = currentFile ?: return
        val obj = JsonObject()
        obj.addProperty("tick", state.tick)
        obj.addProperty("rel_tick", state.tick - sessionStartTick)

        // state 数组
        val stateArr = com.google.gson.JsonArray()
        for (f in state.features) stateArr.add(f)
        obj.add("state", stateArr)

        // action 数组
        val actionArr = com.google.gson.JsonArray()
        actionArr.add(action.moveDir)
        actionArr.add(action.jump)
        actionArr.add(action.sneak)
        actionArr.add(action.attack)
        actionArr.add(action.use)
        actionArr.add(action.hotbar)
        actionArr.add(action.yawBin)
        actionArr.add(action.pitchBin)
        obj.add("action", actionArr)

        val line = gson.toJson(obj) + "\n"
        try {
            Files.writeString(
                file, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
            sampleCount++
        } catch (e: Exception) {
            System.err.println("[PvpAi] 写入数据集失败: ${e.message}")
        }
    }

    /**
     * 结束当前 session，返回写入的样本数。
     */
    fun endSession(): Int {
        val n = sampleCount
        currentFile = null
        sampleCount = 0
        return n
    }

    /** 当前 session 路径（用于 UI 显示）。 */
    fun currentPath(): Path? = currentFile
}
