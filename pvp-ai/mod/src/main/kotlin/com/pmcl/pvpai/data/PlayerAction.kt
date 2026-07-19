package com.pmcl.pvpai.data

/**
 * 单个 tick 的玩家动作（训练目标）。
 *
 * 离散动作用 one-hot / 索引表示，便于分类头输出：
 *   moveDir: 0-8（8 方向 + 0=静止），WASD 组合的离散编码
 *   jump:    0/1
 *   sneak:   0/1
 *   attack:  0/1（本 tick 是否触发攻击）
 *   use:     0/1（本 tick 是否触发使用/举盾/食物）
 *   hotbar:  0-8（快捷栏槽位，仅当切换时记录新槽位）
 *
 * 连续视角变化用 11-bin 离散分类（与训练侧对齐）：
 *   yawBin:   0-10，Δyaw 量化到 [-π/4, π/4] 的 11 个 bin
 *   pitchBin: 0-10，Δpitch 量化到 [-π/8, π/8] 的 11 个 bin
 *
 * 这样所有输出都是分类问题，用 CrossEntropy 训练，避免混合 loss 调参。
 *
 * @param rawDeltaYaw 原始 Δyaw（弧度），仅用于推理时应用，不参与训练
 * @param rawDeltaPitch 同上
 */
data class PlayerAction(
    val tick: Long,
    val moveDir: Int,        // 0-8
    val jump: Int,           // 0/1
    val sneak: Int,          // 0/1
    val attack: Int,         // 0/1
    val use: Int,            // 0/1
    val hotbar: Int,         // 0-8
    val yawBin: Int,         // 0-10
    val pitchBin: Int,       // 0-10
    val rawDeltaYaw: Float,  // 推理应用用
    val rawDeltaPitch: Float
) {
    /** 序列化为 JSON 数组，与 GameState 一行写入 JSONL。 */
    fun toJsonArray(): String =
        "[$moveDir,$jump,$sneak,$attack,$use,$hotbar,$yawBin,$pitchBin]"

    companion object {
        const val MOVE_DIM = 9
        const val HOTBAR_DIM = 9
        const val YAW_BIN = 11
        const val PITCH_BIN = 11
        const val ACTION_DIM = MOVE_DIM + 5 + HOTBAR_DIM + YAW_BIN + PITCH_BIN // 35

        /**
         * 把 Δyaw（弧度）量化到 11 个 bin。
         * 范围 [-π/4, π/4]：超过 ±π/4 截断到边界 bin。
         */
        fun quantizeYaw(delta: Float): Int {
            val max = Math.PI.toFloat() / 4
            val norm = (delta / max + 1f) / 2f  // [0, 1]
            val clamped = norm.coerceIn(0f, 1f)
            return (clamped * (YAW_BIN - 1)).toInt().coerceIn(0, YAW_BIN - 1)
        }

        fun quantizePitch(delta: Float): Int {
            val max = Math.PI.toFloat() / 8
            val norm = (delta / max + 1f) / 2f
            val clamped = norm.coerceIn(0f, 1f)
            return (clamped * (PITCH_BIN - 1)).toInt().coerceIn(0, PITCH_BIN - 1)
        }

        /** 从 bin 还原 Δyaw（取 bin 中心值）。 */
        fun dequantizeYaw(bin: Int): Float {
            val max = Math.PI.toFloat() / 4
            val norm = bin.toFloat() / (YAW_BIN - 1)  // [0, 1]
            return (norm * 2f - 1f) * max
        }

        fun dequantizePitch(bin: Int): Float {
            val max = Math.PI.toFloat() / 8
            val norm = bin.toFloat() / (PITCH_BIN - 1)
            return (norm * 2f - 1f) * max
        }

        /** WASD 输入状态 → 8 方向 + 静止编码（0=静止，1-8=8方向）。 */
        fun encodeMoveDir(forward: Boolean, backward: Boolean,
                          left: Boolean, right: Boolean): Int {
            // 9 宫格编码：以 W/S/A/D 组合映射到 1-8
            val f = if (forward) 1 else 0
            val b = if (backward) 1 else 0
            val l = if (left) 1 else 0
            val r = if (right) 1 else 0
            // 优先级：FW > BW > 静止纵向；横纵向组合
            if (!f && !b && !l && !r) return 0  // 静止
            return when {
                f && l -> 1
                f && r -> 2
                f -> 3
                l -> 4
                r -> 5
                b && l -> 6
                b && r -> 7
                b -> 8
                else -> 0
            }
        }
    }
}
