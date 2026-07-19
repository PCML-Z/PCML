package com.pmcl.pvpai.data

/**
 * 单个 tick 的游戏状态快照（30 维特征向量）。
 *
 * 字段布局（与 Python 训练侧 `dataset.py` 的 STATE_DIM 必须严格一致）：
 *
 * 自身（14 维）：
 *   0  pos_x（相对敌人，归一化 /10）
 *   1  pos_y（相对敌人）
 *   2  pos_z（相对敌人）
 *   3  vel_x（自身速度，tick/0.5）
 *   4  vel_y
 *   5  vel_z
 *   6  yaw（弧度，/π 归一化到 [-1,1]）
 *   7  pitch（弧度，/（π/2）归一化）
 *   8  health（/20）
 *   9  hunger（/20）
 *  10  cooldown（0-1，1.9+ 攻击冷却进度）
 *  11  blocking（盾牌举起 0/1）
 *  12  on_ground（0/1）
 *  13  held_slot（/8）
 *
 * 敌人（10 维）：
 *  14  pos_x（相对自身）
 *  15  pos_y
 *  16  pos_z
 *  17  vel_x
 *  18  vel_y
 *  19  vel_z
 *  20  yaw（弧度 /π）
 *  21  health（/20）
 *  22  blocking（0/1）
 *  23  holding_weapon（0/1，剑/斧）
 *
 * 关系（6 维）：
 *  24  horizontal_dist（/10）
 *  25  vertical_dist（/5）
 *  26  has_line_of_sight（0/1）
 *  27  enemy_in_view_cone（0/1，是否在自身视野 60° 内）
 *  28  self_facing_enemy（0/1，自身是否面朝敌人）
 *  29  enemy_facing_self（0/1）
 *
 * 所有字段都做了归一化，使 LSTM 输入大致在 [-1, 1] 范围。
 */
data class GameState(
    val tick: Long,
    val features: FloatArray
) {
    init {
        require(features.size == STATE_DIM) {
            "GameState features 必须是 $STATE_DIM 维，实际 ${features.size}"
        }
    }

    companion object {
        const val STATE_DIM = 30
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameState) return false
        if (tick != other.tick) return false
        if (!features.contentEquals(other.features)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tick.hashCode()
        result = 31 * result + features.contentHashCode()
        return result
    }
}
