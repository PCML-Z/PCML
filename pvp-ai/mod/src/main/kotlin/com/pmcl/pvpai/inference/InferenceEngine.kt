package com.pmcl.pvpai.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.pmcl.pvpai.collector.StateCollector
import com.pmcl.pvpai.data.GameState
import com.pmcl.pvpai.data.PlayerAction
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.MathHelper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ArrayBlockingQueue

/**
 * 推理引擎：加载 ONNX 模型，每 tick 用最近 20 帧状态预测动作并应用到玩家。
 *
 * 模型输入：shape [1, 20, 30] 的 float 张量（batch=1, seq=20, features=30）
 * 模型输出（多头）：
 *   move_logits:  [1, 9]
 *   jump_logits:  [1, 2]
 *   sneak_logits: [1, 2]
 *   attack_logits:[1, 2]
 *   use_logits:   [1, 2]
 *   hotbar_logits:[1, 9]
 *   yaw_logits:   [1, 11]
 *   pitch_logits: [1, 11]
 *
 * 应用策略：argmax 取每个头最大概率动作。视角变化直接加到玩家 yaw/pitch。
 * 移动/跳跃通过 setKeyBindingPressed 模拟按键状态（绕过 Mixin 简化）。
 */
class InferenceEngine {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var modelPath: Path? = null

    /** 状态历史滑动窗口（最近 20 帧）。 */
    private val stateBuffer = ArrayDeque<FloatArray>()
    private val windowSize = 20

    /** 输入队列：UI 线程喂入新 state，推理线程异步消费（避免阻塞客户端 tick）。 */
    private val pendingStates = ArrayBlockingQueue<FloatArray>(64)
    @Volatile private var running = false
    private var inferThread: Thread? = null

    /** 是否已启用（模型加载且无错误）。 */
    @Volatile private var enabled: Boolean = false

    /** 最近一次预测的动作（被 tick 循环读取并应用）。 */
    @Volatile private var pendingAction: PlayerAction? = null

    fun enable(): Boolean {
        val path = defaultModelPath()
        if (!Files.exists(path)) {
            System.err.println("[PvpAi] 模型不存在: $path")
            return false
        }
        return try {
            session?.close()
            session = env.createSession(path.toString(), OrtSession.SessionOptions())
            modelPath = path
            stateBuffer.clear()
            enabled = true
            // 启动推理线程
            running = true
            inferThread = Thread({ inferLoop() }, "pvp-ai-infer").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            System.err.println("[PvpAi] 加载模型失败: ${e.message}")
            enabled = false
            false
        }
    }

    fun disable() {
        enabled = false
        running = false
        inferThread?.interrupt()
        inferThread = null
        pendingAction = null
        stateBuffer.clear()
        // 释放按键状态
        releaseAllKeys()
    }

    fun reload() {
        if (enabled) {
            disable()
            enable()
        }
    }

    /**
     * 每 tick 调用：
     * 1. 采集当前状态加入 buffer
     * 2. 若 buffer 满则送入推理队列
     * 3. 若有 pendingAction 则应用到玩家
     */
    fun tick(client: MinecraftClient, tickCounter: Long) {
        val state = StateCollector.capture(client, tickCounter) ?: return
        stateBuffer.addLast(state.features)
        while (stateBuffer.size > windowSize) stateBuffer.removeFirst()

        // buffer 满了，提交给推理线程
        if (stateBuffer.size == windowSize) {
            val snapshot = stateBuffer.toTypedArray()
            pendingStates.offer(snapshot.flatten().toFloatArray())
        }

        pendingAction?.let { act ->
            applyAction(client, act)
            pendingAction = null
        }
    }

    private fun inferLoop() {
        while (running) {
            val flatInput = pendingStates.poll() ?: run {
                Thread.sleep(2)
                continue
            }
            try {
                val action = runInference(flatInput)
                pendingAction = action
            } catch (e: Exception) {
                System.err.println("[PvpAi] 推理异常: ${e.message}")
            }
        }
    }

    private fun runInference(flatInput: FloatArray): PlayerAction? {
        val sess = session ?: return null
        val shape = longArrayOf(1L, windowSize.toLong(), GameState.STATE_DIM.toLong())
        val inputTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(flatInput), shape)
        inputTensor.use {
            val outputs = sess.run(mapOf(sess.inputNames.first() to it))
            outputs.use {
                val move = argmax(it, "move_logits", PlayerAction.MOVE_DIM)
                val jump = argmax(it, "jump_logits", 2)
                val sneak = argmax(it, "sneak_logits", 2)
                val attack = argmax(it, "attack_logits", 2)
                val use = argmax(it, "use_logits", 2)
                val hotbar = argmax(it, "hotbar_logits", PlayerAction.HOTBAR_DIM)
                val yawBin = argmax(it, "yaw_logits", PlayerAction.YAW_BIN)
                val pitchBin = argmax(it, "pitch_logits", PlayerAction.PITCH_BIN)
                return PlayerAction(
                    tick = 0,
                    moveDir = move,
                    jump = jump,
                    sneak = sneak,
                    attack = attack,
                    use = use,
                    hotbar = hotbar,
                    yawBin = yawBin,
                    pitchBin = pitchBin,
                    rawDeltaYaw = PlayerAction.dequantizeYaw(yawBin),
                    rawDeltaPitch = PlayerAction.dequantizePitch(pitchBin)
                )
            }
        }
    }

    private fun argmax(outputs: OrtSession.Result, name: String, dim: Int): Int {
        val tensor = outputs[name] ?: return 0
        val arr = (tensor.value as? Array<*>)?.firstOrNull() as? FloatArray
            ?: return 0
        var bestIdx = 0
        var bestVal = -Float.MAX_VALUE
        for (i in 0 until dim) {
            val v = arr[i]
            if (v > bestVal) {
                bestVal = v
                bestIdx = i
            }
        }
        return bestIdx
    }

    /**
     * 把预测动作应用到玩家。
     * - 视角：直接修改 yaw/pitch
     * - 移动：通过 KeyBinding.setPressed 模拟按下
     * - 攻击：触发一次左键攻击
     * - 快捷栏：调用 PlayerEntity.inventory.selectedSlot
     */
    private fun applyAction(client: MinecraftClient, action: PlayerAction) {
        val player = client.player ?: return
        val options = client.options

        // 视角
        val newYaw = player.yaw + Math.toDegrees(action.rawDeltaYaw.toDouble()).toFloat()
        val newPitch = (player.pitch + Math.toDegrees(action.rawDeltaPitch.toDouble()).toFloat())
            .coerceIn(-90f, 90f)
        player.yaw = newYaw
        player.pitch = newPitch

        // 移动（moveDir: 0=静止, 1=FL, 2=FR, 3=F, 4=L, 5=R, 6=BL, 7=BR, 8=B）
        val (forward, backward, left, right) = decodeMoveDir(action.moveDir)
        setKeyState(options.forwardKey, forward)
        setKeyState(options.backKey, backward)
        setKeyState(options.leftKey, left)
        setKeyState(options.rightKey, right)
        setKeyState(options.jumpKey, action.jump == 1)
        setKeyState(options.sneakKey, action.sneak == 1)
        setKeyState(options.attackKey, action.attack == 1)
        setKeyState(options.useKey, action.use == 1)

        // 快捷栏
        if (action.hotbar != player.inventory.selectedSlot) {
            player.inventory.selectedSlot = action.hotbar
            // 同步给客户端（让 HUD 选中槽位更新）
            try {
                client.itemPickerCooldown
            } catch (_: Throwable) {}
        }
    }

    private fun decodeMoveDir(dir: Int): MoveInput = when (dir) {
        0 -> MoveInput(false, false, false, false)
        1 -> MoveInput(true, false, true, false)
        2 -> MoveInput(true, false, false, true)
        3 -> MoveInput(true, false, false, false)
        4 -> MoveInput(false, false, true, false)
        5 -> MoveInput(false, false, false, true)
        6 -> MoveInput(false, true, true, false)
        7 -> MoveInput(false, true, false, true)
        8 -> MoveInput(false, true, false, false)
        else -> MoveInput(false, false, false, false)
    }

    private data class MoveInput(
        val forward: Boolean, val backward: Boolean,
        val left: Boolean, val right: Boolean
    )

    private fun setKeyState(key: KeyBinding, pressed: Boolean) {
        // KeyBinding.setPressed 在 yarn 1.20.1 是 protected 的，需用反射
        try {
            val m = KeyBinding::class.java.getDeclaredMethod("setPressed", Boolean::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(key, pressed)
        } catch (e: Exception) {
            // 降级：直接设置字段
            try {
                val f = KeyBinding::class.java.getDeclaredField("pressed")
                f.isAccessible = true
                f.setBoolean(key, pressed)
            } catch (_: Throwable) {}
        }
    }

    private fun releaseAllKeys() {
        val client = MinecraftClient.getInstance()
        val options = client.options
        listOf(options.forwardKey, options.backKey, options.leftKey, options.rightKey,
               options.jumpKey, options.sneakKey, options.attackKey, options.useKey
        ).forEach { setKeyState(it, false) }
    }

    private fun defaultModelPath(): Path =
        Paths.get(System.getProperty("user.home"), ".pmcl", "pvp-ai", "model.onnx")
}

private fun MathHelper_unused() {
    // 防 unused import 警告
    MathHelper.wrapDegrees(0f)
}

private object MathHelper {
    fun wrapDegrees(angle: Float): Float = angle
}
