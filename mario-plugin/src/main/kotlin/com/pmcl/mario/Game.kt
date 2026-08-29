package com.pmcl.mario

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

internal enum class Form { SMALL, BIG, FIRE }

internal enum class Phase { TITLE, PLAYING, PAUSED, DYING, FLAG, CLEAR, GAME_OVER }

internal enum class EnemyKind { GOOMBA, KOOPA, SHELL }

internal enum class PowerKind { MUSHROOM, FIREFLOWER, STAR }

// ---------------- 物理常量（单位：像素 / 秒） ----------------
private const val GRAVITY = 2000f
private const val MAX_FALL = 720f
private const val JUMP_V = 560f      // 起跳初速 -> 理论最高约 78px（≈4.9 格）
private const val JUMP_CUT = 190f    // 松开跳跃键后的上升速度上限（可变跳跃高度）
private const val ACCEL = 1100f
private const val FRICTION = 1000f
private const val MAX_WALK = 130f
private const val MAX_RUN = 210f
private const val GOOMBA_V = 34f
private const val KOOPA_V = 34f
private const val SHELL_V = 230f
private const val POWER_V = 62f
private const val FIRE_VX = 270f
private const val START_TIME = 300

private val SKY = Color(0x5C, 0x94, 0xFC)
private val HUD_FONT by lazy { Font("Monospace", Font.BOLD, 8) }
private val BIG_FONT by lazy { Font("Monospace", Font.BOLD, 12) }

internal class Input {
    var left = false
    var right = false
    var down = false
    var jump = false
    var fire = false
    fun clear() {
        left = false; right = false; down = false; jump = false; fire = false
    }
}

internal open class Ent(var x: Float, var y: Float, var w: Int, var h: Int) {
    var vx = 0f
    var vy = 0f
    var dead = false
    var face = -1
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
}

internal class Player(x: Float, y: Float) : Ent(x, y, 12, 16) {
    var form = Form.SMALL
    var invuln = 0f
    var star = 0f
    var fireCd = 0f
    var walkPhase = 0f
    var onGround = false
    /** 变身/受伤后的短暂僵直（不可操作），与经典作品一致。 */
    var powerFreeze = 0f
}

internal class Enemy(var kind: EnemyKind, x: Float, y: Float) : Ent(x, y, 14, 16) {
    var shellMoving = false
    var squashed = false
    var squashTimer = 0f
    var animT = 0f
}

internal class Fireball(x: Float, y: Float, face: Int) : Ent(x, y, 8, 8) {
    var animT = 0f
    var life = 2.6f
    init { this.face = face; vx = face * FIRE_VX }
}

internal class Powerup(val kind: PowerKind, x: Float, y: Float) : Ent(x, y, 14, 14) {
    var emerge = 0.55f
    var animT = 0f
    init { face = 1 }
}

internal class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var color: Int, var life: Float)

internal class Popup(var x: Float, var y: Float, var text: String, var life: Float)

internal class PopCoin(var x: Float, var y: Float, var t: Float)

internal class Flip(var img: java.awt.image.BufferedImage, var x: Float, var y: Float, var vy: Float, var life: Float)

internal class Game(
    var highScore: Int = 0,
    private val onRecord: (Int) -> Unit = {},
) {

    var phase: Phase = Phase.TITLE
    var score = 0
    var coins = 0
    var lives = 3
    var timeLeft = START_TIME
    var camX = 0f
    var flagBonus = 0
    var timeBonus = 0
    /** 最近一次死亡的原因，用于排障（enemy / fall / timeout）。 */
    var deathReason = ""

    private var tick = 0
    private var level: LevelData = buildLevel()
    private var player = Player(3f * TILE, 12f * TILE)
    private val enemies = ArrayList<Enemy>()
    private val fireballs = ArrayList<Fireball>()
    private val powerups = ArrayList<Powerup>()
    private val popCoins = ArrayList<PopCoin>()
    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private val flips = ArrayList<Flip>()
    private val bumps = ArrayList<FloatArray>()

    private var prevJump = false
    private var prevFire = false
    private var dyingTimer = 0f
    private var flagStep = 0
    private var flagTimer = 0f
    private var flagY = 84f

    init {
        resetLevel()
        phase = Phase.TITLE
    }

    // ==================== 关卡生命周期 ====================

    private fun resetLevel() {
        level = buildLevel()
        enemies.clear(); fireballs.clear(); powerups.clear(); popCoins.clear()
        particles.clear(); popups.clear(); flips.clear(); bumps.clear()
        player = Player(3f * TILE, 12f * TILE).also { it.face = 1 }
        for (s in level.spawns) {
            val h = if (s.kind == SpawnKind.GOOMBA) 16 else 24
            enemies.add(Enemy(
                if (s.kind == SpawnKind.GOOMBA) EnemyKind.GOOMBA else EnemyKind.KOOPA,
                s.tx * TILE.toFloat() + 1f,
                13f * TILE - h,
            ).also { it.h = h })
        }
        timeLeft = START_TIME
        camX = 0f
        tick = 0
        flagY = 84f
        prevJump = false
        prevFire = false
    }

    fun startGame() {
        score = 0
        coins = 0
        lives = 3
        resetLevel()
        phase = Phase.PLAYING
    }

    fun nextLevel() {
        resetLevel()
        phase = Phase.PLAYING
    }

    fun confirm() {
        when (phase) {
            Phase.TITLE -> startGame()
            Phase.CLEAR -> nextLevel()
            Phase.GAME_OVER -> startGame()
            Phase.PAUSED -> phase = Phase.PLAYING
            else -> {}
        }
    }

    fun togglePause() {
        when (phase) {
            Phase.PLAYING -> phase = Phase.PAUSED
            Phase.PAUSED -> phase = Phase.PLAYING
            else -> {}
        }
    }

    fun restartNow() {
        if (phase != Phase.TITLE) startGame()
    }

    // ==================== 碰撞 ====================

    private fun tileSolid(tx: Int, ty: Int): Boolean {
        if (ty < 0 || ty >= ROWS) return false
        if (tx < 0 || tx >= LW) return true // 关卡左右边界视为墙
        return isSolid(level.tile(tx, ty))
    }

    /** 水平移动，返回是否撞墙。 */
    private fun moveX(e: Ent, dx: Float): Boolean {
        if (dx == 0f) return false
        e.x += dx
        val top = Math.floorDiv((e.y + 1f).toInt(), TILE)
        val bot = Math.floorDiv((e.y + e.h - 1f).toInt(), TILE)
        if (dx > 0) {
            val tx = Math.floorDiv((e.x + e.w - 1f).toInt(), TILE)
            for (ty in top..bot) {
                if (tileSolid(tx, ty)) {
                    e.x = tx * TILE - e.w - 0.01f
                    return true
                }
            }
        } else {
            val tx = Math.floorDiv(e.x.toInt(), TILE)
            for (ty in top..bot) {
                if (tileSolid(tx, ty)) {
                    e.x = (tx + 1) * TILE + 0.01f
                    return true
                }
            }
        }
        return false
    }

    /** 垂直移动。返回 0=无 1=落地 2=撞头。semiSolid 允许踩半实心平台。 */
    private fun moveY(e: Ent, dy: Float, prevBottom: Float, semiSolid: Boolean): Int {
        if (dy == 0f) return 0
        e.y += dy
        val left = Math.floorDiv((e.x + 1f).toInt(), TILE)
        val right = Math.floorDiv((e.x + e.w - 1f).toInt(), TILE)
        if (dy > 0) {
            // 底部探针多留 0.5px 容差：静止站立时底边正好压在瓦片交界上，
            // 若按「最后一个被占据的像素」取行，会落进行上方那一格而检测不到地面，
            // 导致 onGround 隔帧为真（跳跃输入有一半被吞掉、画面上下抖动）。
            val ty = Math.floorDiv((e.y + e.h + 0.5f).toInt(), TILE)
            for (tx in left..right) {
                if (tileSolid(tx, ty)) {
                    e.y = (ty * TILE - e.h).toFloat()
                    return 1
                }
            }
            if (semiSolid) {
                for (tx in left..right) {
                    if (isPlatform(level.tile(tx, ty))) {
                        val top = ty * TILE.toFloat()
                        if (prevBottom <= top + 1f) {
                            e.y = top - e.h
                            return 1
                        }
                    }
                }
            }
        } else {
            val ty = Math.floorDiv(e.y.toInt(), TILE)
            for (tx in left..right) {
                if (tileSolid(tx, ty)) {
                    e.y = (ty + 1) * TILE + 0.01f
                    return 2
                }
            }
        }
        return 0
    }

    private fun overlap(a: Ent, b: Ent, shrink: Float = 0f): Boolean =
        a.x + shrink < b.x + b.w - shrink &&
            a.x + a.w - shrink > b.x + shrink &&
            a.y + shrink < b.y + b.h - shrink &&
            a.y + a.h - shrink > b.y + shrink

    // ==================== 主循环 ====================

    fun update(dt: Float, input: Input) {
        when (phase) {
            Phase.TITLE -> { idleAnim(dt) }
            Phase.PAUSED -> {}
            Phase.PLAYING -> updatePlaying(dt, input)
            Phase.DYING -> updateDying(dt)
            Phase.FLAG -> updateFlag(dt)
            Phase.CLEAR -> idleAnim(dt)
            Phase.GAME_OVER -> idleAnim(dt)
        }
        prevJump = input.jump
        prevFire = input.fire
    }

    private fun idleAnim(dt: Float) {
        tick++
        stepEffects(dt)
        player.walkPhase = 0f
    }

    private fun updatePlaying(dt: Float, input: Input) {
        tick++

        // ---- 计时 ----
        if (tick % 24 == 0) {
            timeLeft--
            if (timeLeft <= 0) {
                timeLeft = 0
                deathReason = "timeout"
                killPlayer(true)
                return
            }
        }

        val p = player
        if (p.invuln > 0f) p.invuln -= dt
        if (p.star > 0f) p.star = (p.star - dt).coerceAtLeast(0f)
        if (p.fireCd > 0f) p.fireCd -= dt
        if (p.powerFreeze > 0f) p.powerFreeze = (p.powerFreeze - dt).coerceAtLeast(0f)

        // ---- 水平 ----
        var dir = 0f
        if (input.left) dir -= 1f
        if (input.right) dir += 1f
        if (p.powerFreeze > 0f) dir = 0f
        if (dir != 0f) {
            if (p.powerFreeze <= 0f) p.face = if (dir > 0) 1 else -1
            val max = if (input.fire) MAX_RUN else MAX_WALK
            p.vx += dir * ACCEL * dt
            if (dir * p.vx < 0f) p.vx += dir * ACCEL * 0.9f * dt // 反向时刹车更快
            if (p.vx > max) p.vx = max
            if (p.vx < -max) p.vx = -max
        } else {
            val f = if (p.onGround) FRICTION else FRICTION * 0.35f
            if (p.vx > 0f) p.vx = (p.vx - f * dt).coerceAtLeast(0f)
            else if (p.vx < 0f) p.vx = (p.vx + f * dt).coerceAtMost(0f)
        }

        // ---- 跳跃 ----
        val jumpEdge = input.jump && !prevJump
        if (jumpEdge && p.onGround && p.powerFreeze <= 0f) {
            p.vy = -JUMP_V
            p.onGround = false
            Sfx.jump()
        }
        if (!input.jump && p.vy < -JUMP_CUT) p.vy = -JUMP_CUT

        // ---- 火球 ----
        if (p.form == Form.FIRE && input.fire && !prevFire && p.fireCd <= 0f &&
            fireballs.size < 2 && p.powerFreeze <= 0f
        ) {
            fireballs.add(Fireball(p.cx + p.face * 6f - 4f, p.y + 8f, p.face))
            p.fireCd = 0.26f
            Sfx.fireball()
        }

        // ---- 重力与移动 ----
        p.vy = (p.vy + GRAVITY * dt).coerceAtMost(MAX_FALL)
        val prevBottom = p.y + p.h
        val wasOnGround = p.onGround
        p.onGround = false
        moveX(p, p.vx * dt)
        val res = moveY(p, p.vy * dt, prevBottom, !input.down)
        if (res == 1) {
            if (!wasOnGround) p.walkPhase = 0f
            p.onGround = true
            p.vy = 0f
        } else if (res == 2) {
            p.vy = 40f
            bumpBlockAbove(p)
        }

        // ---- 走路动画相位 ----
        if (p.onGround) p.walkPhase += abs(p.vx) * dt * 0.055f

        // ---- 掉出屏幕 ----
        if (p.y > VH + 48f && phase == Phase.PLAYING) {
            deathReason = "fall"
            killPlayer(false)
            return
        }

        // ---- 金币 ----
        collectCoins(p)

        // ---- 实体 ----
        updateEnemies(dt)
        updateFireballs(dt)
        updatePowerups(dt)
        checkEnemyContacts(prevBottom)

        // ---- 过关 ----
        if (p.cx >= level.flagX - 8f) startFlag()

        // ---- 镜头 ----
        camX = (p.cx - VW / 2f).coerceIn(0f, (level.widthPx - VW).toFloat())

        stepEffects(dt)
    }

    // ==================== 顶砖块 ====================

    private fun bumpBlockAbove(p: Player) {
        val ty = Math.floorDiv(p.y.toInt(), TILE) - 1
        if (ty < 0 || ty >= ROWS) return
        val left = Math.floorDiv((p.x + 1f).toInt(), TILE)
        val right = Math.floorDiv((p.x + p.w - 1f).toInt(), TILE)
        for (tx in left..right) {
            val c = level.tile(tx, ty)
            if (isSolid(c)) {
                hitBlock(tx, ty, c, p)
                return
            }
        }
    }

    private fun hitBlock(tx: Int, ty: Int, c: Char, p: Player) {
        when (c) {
            '?' -> {
                level.setTile(tx, ty, 'U')
                bumps.add(floatArrayOf(tx.toFloat(), ty.toFloat(), 0f))
                popCoins.add(PopCoin(tx * TILE.toFloat(), ty * TILE.toFloat(), 0f))
                addScore(200)
                addCoin()
                Sfx.coin()
            }
            '!' -> {
                level.setTile(tx, ty, 'U')
                bumps.add(floatArrayOf(tx.toFloat(), ty.toFloat(), 0f))
                val kind = if (p.form == Form.SMALL) PowerKind.MUSHROOM else PowerKind.FIREFLOWER
                powerups.add(Powerup(kind, tx * TILE.toFloat() + 1f, (ty * TILE).toFloat()))
                addScore(1000)
                Sfx.appear()
            }
            'B' -> {
                if (p.form == Form.SMALL) {
                    bumps.add(floatArrayOf(tx.toFloat(), ty.toFloat(), 0f))
                    Sfx.bump()
                } else {
                    level.setTile(tx, ty, ' ')
                    addScore(50)
                    spawnDebris(tx, ty, 0xE0A040)
                    Sfx.brick()
                }
            }
            else -> {
                bumps.add(floatArrayOf(tx.toFloat(), ty.toFloat(), 0f))
                Sfx.bump()
            }
        }
    }

    // ==================== 敌人 ====================

    private fun updateEnemies(dt: Float) {
        for (e in enemies) {
            if (e.dead) continue
            if (e.x < camX - 140f || e.x > camX + VW + 160f) continue
            e.animT += dt

            if (e.squashed) {
                e.squashTimer -= dt
                if (e.squashTimer <= 0f) e.dead = true
                continue
            }

            val prevB = e.y + e.h
            if (e.kind == EnemyKind.SHELL) {
                if (e.shellMoving) {
                    e.vx = e.face * SHELL_V
                    if (moveX(e, e.vx * dt)) {
                        e.face = -e.face
                        Sfx.bump()
                    }
                    e.vy = (e.vy + GRAVITY * dt).coerceAtMost(MAX_FALL)
                    if (moveY(e, e.vy * dt, prevB, false) == 1) e.vy = 0f
                    for (o in enemies) {
                        if (o === e || o.dead || o.squashed) continue
                        if (overlap(e, o)) {
                            killEnemy(o)
                            addScore(200)
                        }
                    }
                } else {
                    e.vx = 0f
                    e.vy = (e.vy + GRAVITY * dt).coerceAtMost(MAX_FALL)
                    if (moveY(e, e.vy * dt, prevB, false) == 1) e.vy = 0f
                }
            } else {
                e.vx = e.face * (if (e.kind == EnemyKind.GOOMBA) GOOMBA_V else KOOPA_V)
                if (moveX(e, e.vx * dt)) e.face = -e.face
                e.vy = (e.vy + GRAVITY * dt).coerceAtMost(MAX_FALL)
                if (moveY(e, e.vy * dt, prevB, false) == 1) e.vy = 0f
            }
            if (e.y > VH + 64f) e.dead = true
        }
        enemies.removeAll { it.dead && it.squashTimer <= 0f }
    }

    private fun killEnemy(e: Enemy) {
        if (e.dead) return
        val img = when {
            e.squashed -> Sprites.goombaFlat
            e.kind == EnemyKind.SHELL -> Sprites.shell
            e.kind == EnemyKind.KOOPA -> Sprites.koopa[0]
            else -> Sprites.goomba[0]
        }
        flips.add(Flip(img, e.x - 1f, e.y, -270f, 0.8f))
        e.dead = true
    }

    private fun updateFireballs(dt: Float) {
        for (f in fireballs) {
            f.animT += dt
            f.life -= dt
            f.vy = (f.vy + 1500f * dt).coerceAtMost(560f)
            val hitX = moveX(f, f.vx * dt)
            val prevB = f.y + f.h
            when (moveY(f, f.vy * dt, prevB, false)) {
                1 -> f.vy = -280f
                2 -> f.vy = 80f
            }
            if (hitX || f.life <= 0f) {
                f.dead = true
                spawnSpark(f.cx, f.cy)
            } else if (f.y > VH + 64f) {
                f.dead = true
            } else {
                for (e in enemies) {
                    if (e.dead || e.squashed) continue
                    if (overlap(f, e)) {
                        killEnemy(e)
                        addScore(100)
                        f.dead = true
                        spawnSpark(f.cx, f.cy)
                        break
                    }
                }
            }
        }
        fireballs.removeAll { it.dead }
    }

    private fun updatePowerups(dt: Float) {
        for (pu in powerups) {
            if (pu.dead) continue
            pu.animT += dt
            if (pu.emerge > 0f) {
                pu.emerge -= dt
                pu.y -= 30f * dt
                if (pu.emerge <= 0f) pu.emerge = 0f
                continue
            }
            when (pu.kind) {
                PowerKind.FIREFLOWER -> { /* 静止 */ }
                PowerKind.MUSHROOM, PowerKind.STAR -> {
                    pu.vx = pu.face * POWER_V
                    if (moveX(pu, pu.vx * dt)) pu.face = -pu.face
                    pu.vy = (pu.vy + GRAVITY * dt).coerceAtMost(MAX_FALL)
                    val prevB = pu.y + pu.h
                    if (moveY(pu, pu.vy * dt, prevB, false) == 1) {
                        pu.vy = if (pu.kind == PowerKind.STAR) -300f else 0f
                    }
                }
            }
            if (pu.y > VH + 64f) pu.dead = true
            if (overlap(player, pu)) collect(pu)
        }
        powerups.removeAll { it.dead }
    }

    private fun collect(pu: Powerup) {
        pu.dead = true
        addScore(1000)
        when (pu.kind) {
            PowerKind.MUSHROOM -> if (player.form == Form.SMALL) grow()
            PowerKind.FIREFLOWER -> {
                if (player.form == Form.SMALL) grow()
                player.form = Form.FIRE
            }
            PowerKind.STAR -> player.star = 10f
        }
        popups.add(Popup(pu.x, pu.y, "1000", 0.8f))
        Sfx.powerUp()
    }

    private fun grow() {
        val p = player
        p.form = Form.BIG
        p.h = 24
        p.y -= 8f
        p.powerFreeze = 0.45f
        unstick(p)
    }

    private fun shrink() {
        val p = player
        p.form = Form.SMALL
        p.h = 16
        p.y += 8f
        p.powerFreeze = 0.45f
    }

    private fun unstick(p: Player) {
        for (i in 0 until 24) {
            val top = Math.floorDiv(p.y.toInt(), TILE)
            val left = Math.floorDiv((p.x + 1f).toInt(), TILE)
            val right = Math.floorDiv((p.x + p.w - 1f).toInt(), TILE)
            var hit = false
            for (tx in left..right) if (tileSolid(tx, top)) hit = true
            if (!hit) break
            p.y += 1f
        }
    }

    private fun checkEnemyContacts(prevBottom: Float) {
        val p = player
        if (p.powerFreeze > 0f) return
        for (e in enemies) {
            if (e.dead || e.squashed) continue
            if (!overlap(p, e, 1f)) continue

            if (p.star > 0f) {
                killEnemy(e)
                addScore(100)
                continue
            }

            val falling = p.vy > 0f
            val fromAbove = prevBottom <= e.y + 8f
            if (falling && fromAbove) {
                when (e.kind) {
                    EnemyKind.GOOMBA -> {
                        e.squashed = true
                        e.squashTimer = 0.45f
                        e.vx = 0f
                        p.vy = if (prevJump) -430f else -300f
                        addScore(100)
                        popups.add(Popup(e.x, e.y, "100", 0.7f))
                        Sfx.stomp()
                    }
                    EnemyKind.KOOPA -> {
                        e.kind = EnemyKind.SHELL
                        e.y += 8f
                        e.h = 16
                        e.vx = 0f
                        e.shellMoving = false
                        p.vy = if (prevJump) -430f else -300f
                        addScore(100)
                        popups.add(Popup(e.x, e.y, "100", 0.7f))
                        Sfx.stomp()
                    }
                    EnemyKind.SHELL -> {
                        e.shellMoving = false
                        e.vx = 0f
                        p.vy = if (prevJump) -430f else -300f
                        addScore(100)
                        Sfx.stomp()
                    }
                }
                continue
            }

            if (e.kind == EnemyKind.SHELL && !e.shellMoving) {
                e.shellMoving = true
                e.face = if (p.cx < e.cx) 1 else -1
                e.vx = e.face * SHELL_V
                addScore(200)
                Sfx.kick()
                continue
            }
            deathReason = "enemy"
            damagePlayer()
            break
        }
    }

    private fun damagePlayer() {
        val p = player
        if (p.invuln > 0f || p.star > 0f) return
        when (p.form) {
            Form.FIRE -> { p.form = Form.BIG; p.invuln = 2f; Sfx.powerDown() }
            Form.BIG -> { shrink(); p.invuln = 2f; Sfx.powerDown() }
            Form.SMALL -> killPlayer(false)
        }
    }

    private fun killPlayer(timeOut: Boolean) {
        if (phase != Phase.PLAYING) return
        phase = Phase.DYING
        val p = player
        p.vx = 0f
        p.vy = if (timeOut) -300f else -480f
        dyingTimer = 2.7f
        Sfx.die()
    }

    private fun updateDying(dt: Float) {
        val p = player
        p.vy = (p.vy + GRAVITY * dt).coerceAtMost(MAX_FALL)
        p.y += p.vy * dt
        dyingTimer -= dt
        stepEffects(dt)
        if (dyingTimer <= 0f) {
            lives--
            if (lives <= 0) {
                commitRecord()
                phase = Phase.GAME_OVER
            } else {
                resetLevel()
                phase = Phase.PLAYING
            }
        }
    }

    // ==================== 过关 ====================

    private fun startFlag() {
        if (phase != Phase.PLAYING) return
        phase = Phase.FLAG
        flagStep = 0
        flagTimer = 0f
        val p = player
        p.vx = 0f
        p.vy = 0f
        p.face = 1
        p.x = level.flagX - 14f
        val grabY = min(p.y.toDouble(), 196.0).toFloat()
        flagBonus = (((200f - (grabY + p.h)) / 16f).toInt().coerceAtLeast(0)) * 100
        addScore(flagBonus)
        Sfx.flagPole()
    }

    private fun updateFlag(dt: Float) {
        val p = player
        when (flagStep) {
            0 -> {
                p.y += 95f * dt
                flagY = min(flagY + 95f * dt, 188f)
                if (p.y + p.h >= 200f) {
                    p.y = 200f - p.h
                    flagStep = 1
                    flagTimer = 0f
                }
            }
            1 -> {
                flagTimer += dt
                if (flagTimer > 0.35f) {
                    p.vx = 90f
                    p.walkPhase += dt * 6f
                    moveX(p, p.vx * dt)
                }
                if (p.x > level.castleX + 20f || flagTimer > 3.4f) {
                    flagStep = 2
                    timeBonus = timeLeft * 50
                    addScore(timeBonus)
                    commitRecord()
                    phase = Phase.CLEAR
                    Sfx.clear()
                }
            }
        }
        camX = (p.cx - VW / 2f).coerceIn(0f, (level.widthPx - VW).toFloat())
        stepEffects(dt)
    }

    // ==================== 效果 ====================

    private fun collectCoins(p: Player) {
        val left = Math.floorDiv(p.x.toInt(), TILE)
        val right = Math.floorDiv((p.x + p.w).toInt(), TILE)
        val top = Math.floorDiv(p.y.toInt(), TILE)
        val bot = Math.floorDiv((p.y + p.h).toInt(), TILE)
        for (ty in top..bot) {
            for (tx in left..right) {
                if (level.tile(tx, ty) == 'o') {
                    level.setTile(tx, ty, ' ')
                    addScore(200)
                    addCoin()
                    popCoins.add(PopCoin(tx * TILE.toFloat(), ty * TILE.toFloat(), 0f))
                    Sfx.coin()
                }
            }
        }
    }

    private fun addScore(n: Int) {
        score += n
        if (score > 9_999_999) score = 9_999_999
    }

    private fun addCoin() {
        coins++
        if (coins >= 100) {
            coins -= 100
            lives++
            Sfx.oneUp()
        }
    }

    private fun commitRecord() {
        if (score > highScore) {
            highScore = score
            onRecord(highScore)
        }
    }

    private fun spawnDebris(tx: Int, ty: Int, color: Int) {
        val bx = tx * TILE + 8f
        val by = ty * TILE + 8f
        for (i in 0 until 6) {
            val dir = if (i % 2 == 0) -1 else 1
            particles.add(
                Particle(
                    bx + (i % 3 - 1) * 5f, by,
                    dir * (40f + i * 12f), -230f - i * 18f,
                    color, 1.1f,
                )
            )
        }
    }

    private fun spawnSpark(x: Float, y: Float) {
        for (i in 0 until 5) {
            particles.add(
                Particle(x, y, (i - 2) * 60f, -120f - i * 20f, 0xF8C000, 0.35f)
            )
        }
    }

    private fun stepEffects(dt: Float) {
        var i = particles.size - 1
        while (i >= 0) {
            val pt = particles[i]
            pt.vy += 900f * dt
            pt.x += pt.vx * dt
            pt.y += pt.vy * dt
            pt.life -= dt
            if (pt.life <= 0f) particles.removeAt(i)
            i--
        }
        i = popups.size - 1
        while (i >= 0) {
            val pu = popups[i]
            pu.y -= 26f * dt
            pu.life -= dt
            if (pu.life <= 0f) popups.removeAt(i)
            i--
        }
        i = popCoins.size - 1
        while (i >= 0) {
            val pc = popCoins[i]
            pc.t += dt
            if (pc.t > 0.42f) popCoins.removeAt(i)
            i--
        }
        i = bumps.size - 1
        while (i >= 0) {
            bumps[i][2] += dt
            if (bumps[i][2] > 0.18f) bumps.removeAt(i)
            i--
        }
        i = flips.size - 1
        while (i >= 0) {
            val f = flips[i]
            f.vy += 900f * dt
            f.y += f.vy * dt
            f.life -= dt
            if (f.life <= 0f) flips.removeAt(i)
            i--
        }
    }

    /** 一行状态快照，供无头冒烟测试与线上排障使用（无填充空格，便于解析）。 */
    fun debug(): String {
        val p = player
        return "phase=$phase x=${f(p.x)} y=${f(p.y)} vy=${f(p.vy)} vx=${f(p.vx)} " +
            "ground=$onGroundName cam=${f(camX, 0)} form=$formName " +
            "enemies=${enemies.count { !it.dead }} fire=${fireballs.size} power=${powerups.size} " +
            "score=$score coins=$coins lives=$lives time=$timeLeft death=$deathReason"
    }

    private val onGroundName: String get() = player.onGround.toString()
    private val formName: String get() = player.form.toString()

    private fun f(v: Float, digits: Int = 1): String =
        String.format(java.util.Locale.US, "%.${digits}f", v)

    /** 存活敌人坐标快照，排障用。 */
    fun enemyDump(): String = enemies.filter { !it.dead }
        .joinToString(" ") { "${it.kind.name.first()}@${it.x.toInt()},${it.y.toInt()}/${it.h}" }

    // ==================== 渲染 ====================

    fun render(g: Graphics2D, scale: Float) {
        g.scale(scale.toDouble(), scale.toDouble())
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.color = SKY
        g.fillRect(0, 0, VW, VH)

        drawDecor(g)
        drawTiles(g)
        drawFlag(g)
        drawActors(g)
        drawEffects(g)
        drawHud(g)
        drawOverlay(g)
    }

    private fun drawDecor(g: Graphics2D) {
        for (d in level.decor) {
            val img = when (d.kind) {
                DecorKind.HILL -> Sprites.hill
                DecorKind.CLOUD -> Sprites.cloud
                DecorKind.BUSH -> Sprites.bush
                DecorKind.CASTLE -> Sprites.castle
            }
            val x = (d.tx * TILE - camX).toInt()
            val w = img.width
            if (x > VW || x + w < 0f) continue
            val y = (d.tyBottom * TILE - img.height.toFloat()).toInt()
            g.drawImage(img, x, y, null as java.awt.image.ImageObserver?)
        }
    }

    private fun drawTiles(g: Graphics2D) {
        val qFrame = (tick / 12) % 4
        val cFrame = (tick / 6) % 4
        val x0 = Math.floorDiv(camX.toInt(), TILE)
        val x1 = x0 + VW / TILE + 1
        for (tx in x0..x1) {
            if (tx < 0 || tx >= LW) continue
            for (ty in 0 until ROWS) {
                val c = level.tile(tx, ty)
                if (c == ' ') continue
                if (c == 'g' || c == 'k') continue
                var bump = 0f
                for (b in bumps) {
                    if (b[0].toInt() == tx && b[1].toInt() == ty) {
                        val t = b[2] / 0.18f
                        bump = -6f * sin((t * PI).toFloat())
                    }
                }
                val img = when (c) {
                    'X' -> Sprites.ground
                    'B' -> Sprites.brick
                    '#' -> Sprites.solid
                    'U' -> Sprites.used
                    '?', '!' -> Sprites.question[qFrame]
                    'o' -> Sprites.coin[cFrame]
                    '=' -> Sprites.platform
                    'P' -> {
                        val left = level.tile(tx - 1, ty) != 'P'
                        val top = level.tile(tx, ty - 1) != 'P'
                        when {
                            top && left -> Sprites.pipeTopL
                            top -> Sprites.pipeTopR
                            left -> Sprites.pipeBodyL
                            else -> Sprites.pipeBodyR
                        }
                    }
                    else -> null
                } ?: continue
                g.drawImage(img, (tx * TILE - camX).toInt(), (ty * TILE + bump).toInt(), null)
            }
        }
    }

    private fun drawFlag(g: Graphics2D) {
        val x = (level.flagX - camX + 6.0).toInt()
        if (x < -40 || x > VW + 40) return
        g.color = Color(0x00, 0x68, 0x00)
        g.fillRect(x - 1, 78, 5, 124)
        g.color = Color(0x68, 0xD0, 0x00)
        g.fillRect(x, 79, 2, 122)
        g.color = Color(0xF8, 0xD8, 0x00)
        g.fillRect(x - 1, 74, 6, 6)
        // 旗面
        g.color = Color(0xF8, 0x38, 0x00)
        val fy = flagY.toInt()
        g.fillPolygon(
            Polygon(
                intArrayOf(x + 3, x + 20, x + 3),
                intArrayOf(fy, fy + 8, fy + 16),
                3,
            )
        )
        // 底座
        g.color = Color(0x00, 0xA8, 0x00)
        g.fillRect(x - 5, 196, 14, 12)
        g.color = Color(0x68, 0xD0, 0x00)
        g.fillRect(x - 4, 197, 12, 4)
    }

    private fun drawActors(g: Graphics2D) {
        for (pu in powerups) {
            val img = when (pu.kind) {
                PowerKind.MUSHROOM -> Sprites.mushroom
                PowerKind.FIREFLOWER -> {
                    if (((pu.animT * 8).toInt() % 3) == 0) Sprites.fireflower else Sprites.mushroom
                }
                PowerKind.STAR -> Sprites.star
            }
            g.drawImage(img, (pu.x - camX - 1f).toInt(), (pu.y - 1f).toInt(), null)
        }

        for (e in enemies) {
            if (e.dead) continue
            val x = (e.x - camX - 1f).toInt()
            val y = (e.y).toInt()
            when {
                e.squashed -> g.drawImage(Sprites.goombaFlat, x, y, null)
                e.kind == EnemyKind.SHELL -> g.drawImage(Sprites.shell, x, y, null)
                e.kind == EnemyKind.KOOPA -> {
                    val f = ((e.animT * 5).toInt()) % 2
                    g.drawImage(Sprites.koopa[f], x, y, null)
                }
                else -> {
                    val f = ((e.animT * 5).toInt()) % 2
                    g.drawImage(Sprites.goomba[f], x, y, null)
                }
            }
        }

        for (f in fireballs) {
            val i = ((f.animT * 20).toInt()) % 4
            g.drawImage(Sprites.fireball[i], (f.x - camX).toInt(), (f.y).toInt(), null)
        }

        drawPlayer(g)
    }

    private fun drawPlayer(g: Graphics2D) {
        val p = player
        // 无敌时间内闪烁（死亡动画期间始终绘制）
        if (phase != Phase.DYING && p.invuln > 0f && (tick / 4) % 2 == 0) return
        val anim = when {
            phase == Phase.DYING -> MAnim.DEAD
            !p.onGround -> MAnim.JUMP
            abs(p.vx) > 12f -> {
                when (((p.walkPhase).toInt()) % 4) {
                    0 -> MAnim.WALK0
                    1 -> MAnim.WALK1
                    2 -> MAnim.WALK2
                    else -> MAnim.WALK1
                }
            }
            else -> MAnim.STAND
        }
        val flash = if (p.star > 0f) ((tick / 5) % 3) + 1 else 0
        val img = Sprites.mario(p.form, anim, flash, p.face)
        val dx = (p.x - camX - 2f).toInt()
        val dy = (p.y + p.h - img.height).toInt()
        g.drawImage(img, dx, dy, null)
    }

    private fun drawEffects(g: Graphics2D) {
        for (pc in popCoins) {
            val f = ((pc.t * 18).toInt()) % 4
            val dy = pc.y - 26f * min(pc.t / 0.42f, 1f)
            g.drawImage(Sprites.coin[f], (pc.x - camX).toInt(), (dy).toInt(), null)
        }
        for (pt in particles) {
            g.color = Color((pt.color shr 16) and 0xFF, (pt.color shr 8) and 0xFF, pt.color and 0xFF)
            g.fillRect((pt.x - camX).toInt(), (pt.y).toInt(), 4, 4)
        }
        for (f in flips) {
            val gg = g.create() as Graphics2D
            gg.translate((f.x - camX).toInt(), (f.y).toInt())
            gg.scale(1.0, -1.0)
            gg.drawImage(f.img, 0, -f.img.height, null)
            gg.dispose()
        }
        if (popups.isNotEmpty()) {
            g.font = HUD_FONT
            g.color = Color.WHITE
            for (pu in popups) {
                drawTextCentered(g, pu.text, (pu.x - camX + 8f).toInt().toDouble(), (pu.y).toInt().toDouble())
            }
        }
    }

    private fun pad(v: Int, n: Int): String {
        val s = v.toString()
        return if (s.length >= n) s else "0".repeat(n - s.length) + s
    }

    private fun drawHud(g: Graphics2D) {
        g.font = HUD_FONT
        g.color = Color.WHITE

        drawText(g, "MARIO", 16.0, 8.0)
        drawText(g, pad(score, 6), 16.0, 18.0)

        g.drawImage(Sprites.coin[(tick / 6) % 4], 84, 6, null)
        drawText(g, "x" + pad(coins, 2), 98.0, 18.0)

        drawText(g, "WORLD", 144.0, 8.0)
        drawText(g, "1-1", 152.0, 18.0)

        drawText(g, "TIME", 208.0, 8.0)
        drawText(g, pad(timeLeft, 3), 212.0, 18.0)

        drawText(g, "LIVES x" + lives.coerceAtLeast(0), 16.0, 34.0)
    }

    private fun centerText(g: Graphics2D, text: String, y: Double, font: Font) {
        g.font = font
        g.color = Color.WHITE
        drawTextCentered(g, text, VW / 2.0, y)
    }

    private fun drawText(g: Graphics2D, text: String, x: Double, y: Double) {
        val fm = g.fontMetrics
        g.drawString(text, x.toInt(), (y + fm.ascent).toInt())
    }

    private fun drawTextCentered(g: Graphics2D, text: String, x: Double, y: Double) {
        val fm = g.fontMetrics
        val w = fm.stringWidth(text)
        g.drawString(text, (x - w / 2.0).toInt(), (y + fm.ascent).toInt())
    }

    private fun drawOverlay(g: Graphics2D) {
        when (phase) {
            Phase.TITLE -> {
                g.color = Color(0, 0, 0, 140)
                g.fillRect(0, 0, VW, VH)
                centerText(g, "SUPER MARIO BROS.", 70.0, BIG_FONT)
                centerText(g, "PLUGIN EDITION", 86.0, HUD_FONT)
                centerText(g, "PRESS ENTER TO START", 118.0, HUD_FONT)
                g.font = HUD_FONT
                g.color = Color(0xFC, 0xD8, 0x00)
                drawTextCentered(g, "ARROWS / A D  -  MOVE", VW / 2.0, 146.0)
                drawTextCentered(g, "SPACE / W / UP  -  JUMP", VW / 2.0, 158.0)
                drawTextCentered(g, "SHIFT / J  -  RUN & FIRE", VW / 2.0, 170.0)
                drawTextCentered(g, "P / ESC  -  PAUSE     R  -  RESTART", VW / 2.0, 182.0)
            }
            Phase.PAUSED -> {
                g.color = Color(0, 0, 0, 128)
                g.fillRect(0, 0, VW, VH)
                centerText(g, "PAUSED", 110.0, BIG_FONT)
                centerText(g, "PRESS P OR ENTER", 132.0, HUD_FONT)
            }
            Phase.GAME_OVER -> {
                g.color = Color(0, 0, 0, 166)
                g.fillRect(0, 0, VW, VH)
                centerText(g, "GAME OVER", 100.0, BIG_FONT)
                centerText(g, "SCORE " + pad(score, 6), 124.0, HUD_FONT)
                centerText(g, "BEST  " + pad(highScore, 6), 138.0, HUD_FONT)
                centerText(g, "PRESS ENTER TO RETRY", 158.0, HUD_FONT)
            }
            Phase.CLEAR -> {
                g.color = Color(0, 0, 0, 153)
                g.fillRect(0, 0, VW, VH)
                centerText(g, "COURSE CLEAR!", 80.0, BIG_FONT)
                centerText(g, "FLAG BONUS   " + pad(flagBonus, 5), 112.0, HUD_FONT)
                centerText(g, "TIME BONUS   " + pad(timeBonus, 5), 126.0, HUD_FONT)
                centerText(g, "SCORE        " + pad(score, 6), 140.0, HUD_FONT)
                centerText(g, "BEST         " + pad(highScore, 6), 154.0, HUD_FONT)
                centerText(g, "PRESS ENTER TO PLAY AGAIN", 176.0, HUD_FONT)
            }
            else -> {}
        }
    }
}
