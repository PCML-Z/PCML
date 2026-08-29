package com.pmcl.mario

// ============================================================================
// 关卡数据
//
// 采用经典 NES《超级马力欧兄弟》的瓦片坐标：一个瓦片 16px，可视区 256x240
// (16x15 个瓦片)，关卡横向 210 个瓦片。地面占最下面两行(row 13/14)。
//
// 瓦片字符含义：
//   ' '  空
//   'X'  地面（实心）
//   'B'  砖块（大马力欧可顶碎）
//   '?'  问号块（顶出金币）
//   '!'  问号块（顶出道具：小马里欧->蘑菇，否则->火力花）
//   'U'  已顶过的方块
//   '#'  硬砖块（阶梯 / 实心）
//   'P'  水管（两列一组）
//   'o'  金币（可拾取、无碰撞）
//   '='  半实心平台（只能从上方落到上面，可从下方穿过）
//   'g'  敌人生成点：栗宝宝
//   'k'  敌人生成点：慢慢龟
// ============================================================================

internal const val TILE = 16
internal const val VW = 256
internal const val VH = 240
internal const val ROWS = 15
internal const val LW = 210

internal enum class DecorKind { HILL, CLOUD, BUSH, CASTLE }

internal class Decor(val tx: Int, val tyBottom: Int, val kind: DecorKind)

internal enum class SpawnKind { GOOMBA, KOOPA }

internal class Spawn(val tx: Int, val kind: SpawnKind)

internal class LevelData(
    val grid: Array<CharArray>,
    val decor: List<Decor>,
    val spawns: List<Spawn>,
    /** 旗杆的世界像素 X 坐标 */
    val flagX: Int,
    /** 城堡的世界像素 X 坐标（左下角） */
    val castleX: Int,
) {
    val widthPx: Int = LW * TILE

    fun tile(tx: Int, ty: Int): Char =
        if (tx < 0 || tx >= LW || ty < 0 || ty >= ROWS) ' ' else grid[ty][tx]

    fun setTile(tx: Int, ty: Int, c: Char) {
        if (tx in 0 until LW && ty in 0 until ROWS) grid[ty][tx] = c
    }
}

internal fun isSolid(c: Char): Boolean =
    c == 'X' || c == 'B' || c == '?' || c == '!' || c == '#' || c == 'P' || c == 'U'

internal fun isPlatform(c: Char): Boolean = c == '='

internal fun buildLevel(): LevelData {
    val g = Array(ROWS) { CharArray(LW) { ' ' } }

    fun put(x: Int, y: Int, s: String) {
        for (i in s.indices) {
            val xx = x + i
            if (xx in 0 until LW && y in 0 until ROWS) g[y][xx] = s[i]
        }
    }

    /** 在 x 与 x+1 两列上竖一根高 h 格的水管，底部贴地。 */
    fun pipe(x: Int, h: Int) {
        for (i in 0 until h) put(x, 13 - i, "PP")
    }

    // ---------------- 地面 + 深坑 ----------------
    for (y in 13..14) for (x in 0 until LW) g[y][x] = 'X'
    for (p in intArrayOf(38, 74, 96, 118, 140, 162)) {
        put(p, 11, "ooo") // 坑上方奖励金币
        for (x in p until p + 3) {
            g[13][x] = ' '
            g[14][x] = ' '
        }
    }

    // ---------------- 第 1 段：0 - 37 ----------------
    put(8, 9, "?")
    put(12, 9, "B?B?B")
    put(19, 11, "ooo")
    pipe(22, 2)
    put(16, 12, "g")
    put(26, 9, "?B?B?")
    put(28, 5, "?")
    put(25, 12, "g")
    put(33, 12, "g")
    // 注意：每个深坑前必须留出至少 3 列没有任何 row 9/10 方块的助跑区，
    // 否则玩家一起跳就撞到头顶的砖块、上升速度被打断，必定掉坑。
    put(32, 9, "BBB")

    // ---------------- 第 2 段：38 - 73（坑 38） ----------------
    pipe(43, 3)
    put(47, 12, "g")
    put(50, 12, "g")
    // 阶梯金字塔
    put(52, 12, "#"); put(53, 11, "#"); put(54, 10, "#"); put(55, 9, "#")
    put(56, 9, "#"); put(57, 10, "#"); put(58, 11, "#"); put(59, 12, "#")
    put(62, 9, "BBBBB")
    put(62, 8, "ooooo")
    put(64, 12, "k")
    put(67, 9, "?")
    put(69, 9, "?")

    // ---------------- 第 3 段：74 - 95（坑 74） ----------------
    pipe(80, 4)
    put(84, 12, "g"); put(86, 12, "g"); put(88, 12, "g")
    put(88, 9, "?BB?")
    put(92, 9, "!")

    // ---------------- 第 4 段：96 - 117（坑 96） ----------------
    put(102, 9, "BBBB")
    put(102, 8, "oooo")
    put(104, 12, "k")
    put(107, 10, "====")
    put(108, 9, "ooo")
    pipe(112, 2)

    // ---------------- 第 5 段：118 - 139（坑 118） ----------------
    pipe(124, 3)
    put(129, 12, "g"); put(131, 12, "g")
    put(134, 9, "?B?")
    put(137, 12, "k")

    // ---------------- 第 6 段：140 - 161（坑 140） ----------------
    put(146, 9, "BBBBB")
    put(147, 8, "?")
    put(152, 12, "g"); put(154, 12, "g"); put(156, 12, "g")
    pipe(157, 3)

    // ---------------- 第 7 段：162 - 191（坑 162） ----------------
    put(168, 9, "?")
    put(171, 9, "B!B")
    put(176, 9, "BB")
    put(176, 8, "oo")
    // 旗杆前的阶梯
    put(180, 12, "#"); put(181, 11, "#"); put(182, 10, "#"); put(183, 9, "#")
    put(184, 9, "#"); put(185, 10, "#"); put(186, 11, "#"); put(187, 12, "#")

    // ---------------- 敌人生成点抽离 ----------------
    val spawns = ArrayList<Spawn>()
    for (y in 0 until ROWS) {
        for (x in 0 until LW) {
            when (g[y][x]) {
                'g' -> { spawns.add(Spawn(x, SpawnKind.GOOMBA)); g[y][x] = ' ' }
                'k' -> { spawns.add(Spawn(x, SpawnKind.KOOPA)); g[y][x] = ' ' }
            }
        }
    }

    // ---------------- 背景装饰（无碰撞，随镜头滚动） ----------------
    val decor = listOf(
        Decor(5, 13, DecorKind.HILL),
        Decor(30, 13, DecorKind.HILL),
        Decor(62, 13, DecorKind.HILL),
        Decor(100, 13, DecorKind.HILL),
        Decor(155, 13, DecorKind.HILL),
        Decor(170, 13, DecorKind.HILL),
        Decor(12, 5, DecorKind.CLOUD),
        Decor(44, 5, DecorKind.CLOUD),
        Decor(76, 5, DecorKind.CLOUD),
        Decor(110, 5, DecorKind.CLOUD),
        Decor(145, 5, DecorKind.CLOUD),
        Decor(185, 5, DecorKind.CLOUD),
        Decor(18, 13, DecorKind.BUSH),
        Decor(66, 13, DecorKind.BUSH),
        Decor(92, 13, DecorKind.BUSH),
        Decor(132, 13, DecorKind.BUSH),
        Decor(166, 13, DecorKind.BUSH),
        Decor(198, 13, DecorKind.CASTLE),
    )

    return LevelData(g, decor, spawns, 192 * TILE, 198 * TILE)
}
