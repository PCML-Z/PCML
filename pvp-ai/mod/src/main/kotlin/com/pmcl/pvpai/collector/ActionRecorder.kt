package com.pmcl.pvpai.collector

import com.pmcl.pvpai.data.PlayerAction
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding

/**
 * 玩家动作录制器：每 tick 读取客户端输入状态并组装成 [PlayerAction]。
 *
 * 设计：所有输入读取都通过 MinecraftClient 单例，避免 Mixin 复杂度。
 * 攻击/使用 通过检测本 tick 内按键被按下（wasPressed）来捕获。
 *
 * 视角变化（Δyaw / Δpitch）通过对比上一 tick 的玩家朝向计算。
 */
object ActionRecorder {

    private var lastYaw: Float = 0f
    private var lastPitch: Float = 0f
    private var lastHotbar: Int = 0
    private var initialized: Boolean = false

    /**
     * 捕获本 tick 的玩家动作。
     * 应在 ClientTickEvents.END 调用，确保 MC 已更新本 tick 的所有输入状态。
     */
    fun capture(client: MinecraftClient, tick: Long): PlayerAction? {
        val player = client.player ?: return null
        val options = client.options

        if (!initialized) {
            lastYaw = player.yaw
            lastPitch = player.pitch
            lastHotbar = player.inventory.selectedSlot
            initialized = true
        }

        // === 移动 ===
        val forward = options.forwardKey.isPressed
        val backward = options.backKey.isPressed
        val left = options.leftKey.isPressed
        val right = options.rightKey.isPressed
        val moveDir = PlayerAction.encodeMoveDir(forward, backward, left, right)

        // === 跳跃 / 潜行 ===
        val jump = if (options.jumpKey.isPressed) 1 else 0
        val sneak = if (options.sneakKey.isPressed) 1 else 0

        // === 攻击 / 使用 ===
        // wasPressed() 返回自上次调用以来是否被按下过，且消费该事件
        // 注意：调用 wasPressed() 会消费事件，必须在录制时才调用，否则会影响游戏内交互
        // 因此这里不直接调用 wasPressed，而是检查 isPressed 状态（持续按住时为 true）
        val attack = if (options.attackKey.isPressed) 1 else 0
        val use = if (options.useKey.isPressed) 1 else 0

        // === 快捷栏槽位 ===
        val curHotbar = player.inventory.selectedSlot
        // 不强制记录"切换事件"——直接记录当前槽位，让模型学习"在什么状态应该拿什么"
        val hotbar = curHotbar

        // === 视角变化（Δyaw / Δpitch） ===
        val dyaw = MathHelper.wrapDegrees(player.yaw - lastYaw)
        val dpitch = MathHelper.wrapDegrees(player.pitch - lastPitch)
        val dyawRad = Math.toRadians(dyaw.toDouble()).toFloat()
        val dpitchRad = Math.toRadians(dpitch.toDouble()).toFloat()

        lastYaw = player.yaw
        lastPitch = player.pitch
        lastHotbar = curHotbar

        return PlayerAction(
            tick = tick,
            moveDir = moveDir,
            jump = jump,
            sneak = sneak,
            attack = attack,
            use = use,
            hotbar = hotbar,
            yawBin = PlayerAction.quantizeYaw(dyawRad),
            pitchBin = PlayerAction.quantizePitch(dpitchRad),
            rawDeltaYaw = dyawRad,
            rawDeltaPitch = dpitchRad
        )
    }

    /** 重置内部状态（开始新一段录制时调用）。 */
    fun reset() {
        initialized = false
    }

    @Suppress("unused")
    private fun KeyBinding.wasPressed(): Boolean = this.wasPressed()
}

// MathHelper 在 yarn 1.20.1 中是 net.minecraft.util.math.MathHelper
private val MathHelperClass: Class<*> by lazy {
    Class.forName("net.minecraft.util.math.MathHelper")
}

/**
 * 调用 MathHelper.wrapDegrees(float)：把角度 wrap 到 [-180, 180]。
 * 用反射避免编译期依赖问题（实际可直接 import MathHelper）。
 */
private object MathHelper {
    fun wrapDegrees(angle: Float): Float {
        // 等价实现：((angle + 180) mod 360) - 180
        var a = angle % 360f
        if (a >= 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }
}
