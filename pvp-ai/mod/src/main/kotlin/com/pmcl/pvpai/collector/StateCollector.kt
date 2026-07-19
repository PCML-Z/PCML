package com.pmcl.pvpai.collector

import com.pmcl.pvpai.data.GameState
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.AxeItem
import net.minecraft.item.SwordItem
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 每 tick 从 MC 客户端读取游戏状态并组装成 [GameState]。
 *
 * 关键策略：
 * - "敌人" 选取距离自身最近的其他玩家（PvP 场景）；非玩家时回退到最近 LivingEntity
 * - 视线检测：用 raycast 简化判定（has_line_of_sight）
 * - 视野锥：60° 半角，基于自身 yaw/pitch
 *
 * 所有数值都做了归一化，保证 LSTM 输入大致在 [-1, 1]。
 */
object StateCollector {

    fun capture(client: MinecraftClient, tick: Long): GameState? {
        val player = client.player ?: return null
        val world = client.world ?: return null

        // === 寻找最近敌人 ===
        val enemy = findNearestEnemy(client, player, maxDist = 64.0) ?: return null

        // === 自身状态（14 维） ===
        val posSelf = player.pos
        val velSelf = Vec3d(player.x - player.prevX, player.y - player.prevY, player.z - player.prevZ)
        val selfYawRad = Math.toRadians(player.yaw.toDouble()).toFloat()
        val selfPitchRad = Math.toRadians(player.pitch.toDouble()).toFloat()

        val selfHealth = player.health / 20f
        val selfHunger = player.hungerManager.foodLevel / 20f
        val cooldown = player.attackCooldownProgress(0f)  // 0-1
        val blocking = if (player.isBlocking) 1f else 0f
        val onGround = if (player.isOnGround) 1f else 0f
        val heldSlot = player.inventory.selectedSlot.toFloat() / 8f

        // === 敌人状态（10 维） ===
        val posEnemy = enemy.pos
        val velEnemy = Vec3d(enemy.x - enemy.prevX, enemy.y - enemy.prevY, enemy.z - enemy.prevZ)
        val enemyYawRad = Math.toRadians(enemy.yaw.toDouble()).toFloat()
        val enemyHealth = (enemy as? LivingEntity)?.health?.let { it / 20f } ?: 1f
        val enemyBlocking = if ((enemy as? PlayerEntity)?.isBlocking == true) 1f else 0f
        val enemyWeapon = if (isHoldingWeapon(enemy as? PlayerEntity)) 1f else 0f

        // === 相对位置 ===
        // 自身相对敌人（用于自身感知"敌人在哪"的方向感）
        val dxSelf = (posSelf.x - posEnemy.x).toFloat() / 10f
        val dySelf = (posSelf.y - posEnemy.y).toFloat() / 10f
        val dzSelf = (posSelf.z - posEnemy.z).toFloat() / 10f

        // 敌人相对自身
        val dxEnemy = (posEnemy.x - posSelf.x).toFloat() / 10f
        val dyEnemy = (posEnemy.y - posSelf.y).toFloat() / 10f
        val dzEnemy = (posEnemy.z - posSelf.z).toFloat() / 10f

        // === 关系特征（6 维） ===
        val horizDist = kotlin.math.sqrt(
            (posEnemy.x - posSelf.x) * (posEnemy.x - posSelf.x) +
            (posEnemy.z - posSelf.z) * (posEnemy.z - posSelf.z)
        ).toFloat() / 10f
        val vertDist = (posEnemy.y - posSelf.y).toFloat() / 5f

        val los = if (hasLineOfSight(player, enemy)) 1f else 0f
        val inView = if (isInFront(player, enemy, fovHalfDeg = 30.0)) 1f else 0f
        val selfFacing = if (isInFront(player, enemy, fovHalfDeg = 15.0)) 1f else 0f
        val enemyFacing = if (isInFront(enemy, player, fovHalfDeg = 15.0)) 1f else 0f

        val features = floatArrayOf(
            // 自身（14）
            dxSelf, dySelf, dzSelf,
            (velSelf.x / 0.5).toFloat(), (velSelf.y / 0.5).toFloat(), (velSelf.z / 0.5).toFloat(),
            selfYawRad / (PI.toFloat() / 2),
            selfPitchRad / (PI.toFloat() / 2),
            selfHealth, selfHunger, cooldown, blocking, onGround, heldSlot,
            // 敌人（10）
            dxEnemy, dyEnemy, dzEnemy,
            (velEnemy.x / 0.5).toFloat(), (velEnemy.y / 0.5).toFloat(), (velEnemy.z / 0.5).toFloat(),
            enemyYawRad / (PI.toFloat() / 2),
            enemyHealth, enemyBlocking, enemyWeapon,
            // 关系（6）
            horizDist, vertDist, los, inView, selfFacing, enemyFacing
        )
        return GameState(tick, features)
    }

    /**
     * 在玩家周围 maxDist 范围内查找最近的其他玩家（PvP 场景）。
     * 若无其他玩家，回退到最近 LivingEntity（PvE 兼容）。
     */
    private fun findNearestEnemy(
        client: MinecraftClient,
        self: PlayerEntity,
        maxDist: Double
    ): Entity? {
        val world = client.world ?: return null
        var nearest: Entity? = null
        var nearestDist = maxDist * maxDist

        // 优先其他玩家
        for (other in world.players) {
            if (other === self) continue
            if (other.isSpectator || !other.isAlive) continue
            val d = other.squaredDistanceTo(self)
            if (d < nearestDist) {
                nearestDist = d
                nearest = other
            }
        }
        if (nearest != null) return nearest

        // 回退：最近 LivingEntity
        world.entities.forEach { e ->
            if (e === self || e !is LivingEntity) return@forEach
            if (!e.isAlive) return@forEach
            val d = e.squaredDistanceTo(self)
            if (d < nearestDist) {
                nearestDist = d
                nearest = e
            }
        }
        return nearest
    }

    private fun isHoldingWeapon(p: PlayerEntity?): Boolean {
        val stack = p?.mainHandStack ?: return false
        val item = stack.item
        return item is SwordItem || item is AxeItem
    }

    /**
     * 简化视线判定：基于水平距离直接判定可见（粗略，避免 raycast 性能开销）。
     * 真正的 raycast 在密集 PvP 场景下每 tick 调用会引起 GC 压力。
     * TODO: 后续可换成 chunks 内 block raycast。
     */
    private fun hasLineOfSight(a: Entity, b: Entity): Boolean {
        // 若水平距离 < 32 且高度差 < 8，假设可见
        val dx = a.x - b.x
        val dz = a.z - b.z
        val dy = a.y - b.y
        return dx * dx + dz * dz < 32 * 32 && kotlin.math.abs(dy) < 8
    }

    /**
     * 判断 target 是否在 self 视野锥内（半角 fovHalfDeg）。
     * 视野锥中心是 self 的视线方向（基于 yaw + pitch）。
     */
    private fun isInFront(self: Entity, target: Entity, fovHalfDeg: Double): Boolean {
        val dx = target.x - self.x
        val dy = target.y + target.eyeY - (self.y + self.eyeY)
        val dz = target.z - self.z
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01) return true

        // self 视线方向向量
        val yawRad = Math.toRadians(self.yaw.toDouble())
        val pitchRad = Math.toRadians(self.pitch.toDouble())
        val fx = -sin(yawRad) * cos(pitchRad)
        val fy = -sin(pitchRad)
        val fz = cos(yawRad) * cos(pitchRad)

        // 余弦相似度
        val dot = (dx * fx + dy * fy + dz * fz) / len
        val cosHalf = cos(Math.toRadians(fovHalfDeg))
        return dot >= cosHalf
    }
}

private val Entity.eyeY: Double
    get() = this.standingEyeHeight.toDouble()
