package com.pmcl.mario

import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

internal fun rgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

/**
 * 极简像素光栅：所有精灵在插件首次进入页面时一次性烘焙成 [Image]，
 * 运行期只做 drawImage（关闭插值 = 最近邻放大），保证像素风且不掉帧。
 */
internal class Raster(val w: Int, val h: Int) {
    private val px = IntArray(w * h) // 0xAARRGGBB，0 表示全透明

    fun set(x: Int, y: Int, argb: Int) {
        if (x in 0 until w && y in 0 until h) px[y * w + x] = argb
    }

    fun rect(x: Int, y: Int, rw: Int, rh: Int, argb: Int) {
        val x1 = max(x, 0); val y1 = max(y, 0)
        val x2 = min(x + rw, w); val y2 = min(y + rh, h)
        for (yy in y1 until y2) {
            val base = yy * w
            for (xx in x1 until x2) px[base + xx] = argb
        }
    }

    /** 1px 描边 */
    fun frame(x: Int, y: Int, rw: Int, rh: Int, argb: Int) {
        rect(x, y, rw, 1, argb)
        rect(x, y + rh - 1, rw, 1, argb)
        rect(x, y, 1, rh, argb)
        rect(x + rw - 1, y, 1, rh, argb)
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, argb: Int) {
        val x0 = kotlin.math.floor(cx - rx).toInt()
        val x1 = kotlin.math.ceil(cx + rx).toInt()
        val y0 = kotlin.math.floor(cy - ry).toInt()
        val y1 = kotlin.math.ceil(cy + ry).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                if (dx * dx + dy * dy <= 1f) set(x, y, argb)
            }
        }
    }

    /**
     * 把字符画贴到 (x0,y0)。'.' 视为透明；行长度不一致也没关系。
     * @param sw 精灵逻辑宽度，flip 时以此为镜像轴
     */
    fun blit(x0: Int, y0: Int, rows: Array<String>, pal: Map<Char, Int>, flip: Boolean = false, sw: Int = w) {
        for (y in rows.indices) {
            val row = rows[y]
            for (x in row.indices) {
                val c = row[x]
                if (c == '.') continue
                val argb = pal[c] ?: continue
                set(if (flip) x0 + (sw - 1 - x) else x0 + x, y0 + y, argb)
            }
        }
    }

    fun toImage(): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        return img
    }
}

// ============================================================================
// 调色板
// ============================================================================

private val C_BLACK = rgb(0x00, 0x00, 0x00)
private val C_WHITE = rgb(0xFC, 0xFC, 0xFC)

private val GROUND = rgb(0xC8, 0x4C, 0x0C)
private val GROUND_HI = rgb(0xFC, 0xB0, 0x60)
private val GROUND_LO = rgb(0x8C, 0x28, 0x00)

private val GOLD = rgb(0xFC, 0xD8, 0x00)
private val GOLD_HI = rgb(0xFC, 0xF0, 0x80)
private val GOLD_LO = rgb(0xC0, 0x80, 0x00)

private val GREEN = rgb(0x00, 0xA8, 0x00)
private val GREEN_HI = rgb(0x68, 0xD0, 0x00)
private val GREEN_LO = rgb(0x00, 0x68, 0x00)

private val PAL_MARIO = mapOf(
    'R' to rgb(0xE0, 0x28, 0x00), // 帽子 / 上衣
    'H' to rgb(0x5C, 0x24, 0x00), // 头发 / 胡子
    'S' to rgb(0xF8, 0xB8, 0x88), // 皮肤
    'E' to rgb(0x18, 0x18, 0x18), // 眼睛
    'B' to rgb(0x20, 0x48, 0xE0), // 背带裤
    'O' to rgb(0x8C, 0x30, 0x00), // 鞋
)

private val PAL_FIRE = mapOf(
    'R' to rgb(0xF8, 0xF8, 0xF8),
    'H' to rgb(0x5C, 0x24, 0x00),
    'S' to rgb(0xF8, 0xB8, 0x88),
    'E' to rgb(0x18, 0x18, 0x18),
    'B' to rgb(0xE0, 0x28, 0x00),
    'O' to rgb(0x8C, 0x30, 0x00),
)

private val PAL_STAR_A = mapOf(
    'R' to rgb(0xFC, 0xD8, 0x00), 'H' to rgb(0xC0, 0x80, 0x00),
    'S' to rgb(0xFC, 0xF0, 0x80), 'E' to rgb(0x18, 0x18, 0x18),
    'B' to rgb(0xF8, 0xA8, 0x00), 'O' to rgb(0xC0, 0x80, 0x00),
)

private val PAL_STAR_B = mapOf(
    'R' to rgb(0x40, 0xE0, 0x40), 'H' to rgb(0x00, 0x80, 0x00),
    'S' to rgb(0xB0, 0xF8, 0xB0), 'E' to rgb(0x18, 0x18, 0x18),
    'B' to rgb(0x00, 0xA8, 0x00), 'O' to rgb(0x00, 0x68, 0x00),
)

private val PAL_STAR_C = mapOf(
    'R' to rgb(0xFC, 0x80, 0xA0), 'H' to rgb(0xA0, 0x20, 0x40),
    'S' to rgb(0xFC, 0xD0, 0xD8), 'E' to rgb(0x18, 0x18, 0x18),
    'B' to rgb(0xE0, 0x40, 0x70), 'O' to rgb(0xA0, 0x20, 0x40),
)

private val PAL_GOOMBA = mapOf(
    'G' to rgb(0xA0, 0x50, 0x00),
    'g' to rgb(0x6B, 0x30, 0x00),
    'W' to rgb(0xFC, 0xFC, 0xFC),
    'K' to rgb(0x18, 0x18, 0x18),
    'F' to rgb(0x3A, 0x20, 0x00),
)

private val PAL_KOOPA = mapOf(
    'K' to rgb(0x00, 0xA8, 0x00),
    'k' to rgb(0x00, 0x68, 0x00),
    'Y' to rgb(0xFC, 0xD8, 0x00),
    'W' to rgb(0xFC, 0xFC, 0xFC),
    'E' to rgb(0x18, 0x18, 0x18),
    'O' to rgb(0xF8, 0x80, 0x00),
)

private val PAL_MUSHROOM = mapOf(
    'R' to rgb(0xE0, 0x20, 0x20),
    'W' to rgb(0xFC, 0xFC, 0xFC),
    'S' to rgb(0xF8, 0xC8, 0x88),
    'E' to rgb(0x18, 0x18, 0x18),
)

private val PAL_FLOWER = mapOf(
    'R' to rgb(0xE0, 0x20, 0x20),
    'W' to rgb(0xFC, 0xFC, 0xFC),
    'G' to rgb(0x00, 0xA8, 0x00),
    'Y' to rgb(0xFC, 0xD8, 0x00),
)

private val PAL_STAR_ITEM = mapOf(
    'Y' to rgb(0xFC, 0xD8, 0x00),
    'y' to rgb(0xF8, 0xA8, 0x00),
    'W' to rgb(0xFC, 0xFC, 0xFC),
)

// ============================================================================
// 马里奥字符画（身体 + 腿部组合，减少重复作画）
//   小马里奥 = 12 行身体 + 4 行腿 = 16
//   大马里奥 = 18 行身体 + 6 行腿 = 24
// ============================================================================

private val SMALL_BODY = arrayOf(
    "....RRRRRR......",
    "...RRRRRRRRRR...",
    "...HHHSSSS......",
    "..HHSSSSSSSE....",
    "..HHSSSSSSSSE...",
    "..HHSSSSSSSS....",
    "...SSSSSSSS.....",
    "..RRRBRRR.......",
    ".RRRRBRRRRR.....",
    ".SSRRBBBBRR.....",
    ".SSSRBBBBRSS....",
    ".SS.SBBBBS.SS...",
)

private val SMALL_LEGS_STAND = arrayOf(
    "....BBBBBB......",
    "....BBB.BBB.....",
    "...OOOO.OOOO....",
    "...OOOO.OOOO....",
)

private val SMALL_LEGS_W0 = arrayOf(
    "....BBBBBBB.....",
    "...OOOOO.OOO....",
    "..OOOOO...OOO...",
    "..OOO......OO...",
)

private val SMALL_LEGS_W1 = arrayOf(
    "....BBBBBB......",
    "....OOO.OOO.....",
    "...OOOO.OOOO....",
    "...OOO...OOO....",
)

private val SMALL_LEGS_JUMP = arrayOf(
    ".....BBBBB......",
    "....BBB.BBB.....",
    "...OOOOO.OOOO...",
    "..OOOOO...OOOO..",
)

private val SMALL_DEAD = arrayOf(
    "................",
    "....RRRRRR......",
    "...RRRRRRRRRR...",
    "...HHHSSSS......",
    "..HHSSSSSSSE....",
    "..HHSSSSSSSSE...",
    "..HHSSSSSSSS....",
    "...SSSSSSSS.....",
    ".S..RRRBRRR..S..",
    ".SS.SSBBBBSS.SS.",
    ".SSS.BBBBBB.SS..",
    ".SS..BBBBBB..SS.",
    ".....BBBBBB.....",
    "....OOOO.OOOO...",
    "...OOOO...OOOO..",
    "...OOO......OOO.",
)

private val BIG_BODY = arrayOf(
    "....RRRRRR......",
    "...RRRRRRRRRR...",
    "...HHHSSSS......",
    "..HHSSSSSSSE....",
    "..HHSSSSSSSSE...",
    "..HHSSSSSSSS....",
    "...SSSSSSSS.....",
    "...SSSSSSSS.....",
    "....SSSSSS......",
    "..RRRRBRRRR.....",
    ".RRRRRBRRRRR....",
    ".SSRRRBBBBRR....",
    ".SSSRRBBBBRSS...",
    ".SSS.RBBBBRSS...",
    ".SS..RBBBBR.....",
    ".....RBBBBR.....",
    "....RBBBBBR.....",
    "..SS.BBBBB.SS...",
)

private val BIG_LEGS_STAND = arrayOf(
    "..SS.BBBBB.SS...",
    ".....BBBBB......",
    "....BBB.BBB.....",
    "....BBB.BBB.....",
    "...OOOO.OOOO....",
    "...OOOO.OOOO....",
)

private val BIG_LEGS_W0 = arrayOf(
    "...S.BBBBB.S....",
    ".....BBBBBB.....",
    "....BBBB.BBB....",
    "...OOOOO..OOO...",
    "..OOOOO....OOO..",
    "..OOO.......OO..",
)

private val BIG_LEGS_W1 = arrayOf(
    "..SS.BBBBB.SS...",
    ".....BBBBB......",
    "....BBB.BBB.....",
    "...OOOOO.OOO....",
    "..OOOOO...OOOO..",
    "..OOO......OOO..",
)

private val BIG_LEGS_JUMP = arrayOf(
    "...S.BBBBB.S....",
    ".....BBBBB......",
    "....BBB.BBB.....",
    "...OOOOO.OOOO...",
    "..OOOOO...OOOO..",
    "..OOO.......OOO.",
)

private val BIG_DEAD = arrayOf(
    "................",
    "....RRRRRR......",
    "...RRRRRRRRRR...",
    "...HHHSSSS......",
    "..HHSSSSSSSE....",
    "..HHSSSSSSSSE...",
    "..HHSSSSSSSS....",
    "...SSSSSSSS.....",
    "....SSSSSS......",
    ".S..RRRRBRRR..S.",
    ".SS.RRRRBRRRR.S.",
    ".SSSBBBBBBBBBSS.",
    ".SS.BBBBBBBBBSS.",
    ".....RBBBBR.....",
    ".....RBBBBR.....",
    "....RBBBBBR.....",
    "...SS.BBBBB.SS..",
    "..SS.BBBBBB.SS..",
    "...S.BBBBBBB.S..",
    ".....BBBBBBB....",
    "....BBBBBBBB....",
    "...OOOOO.OOO....",
    "..OOOOO...OOO...",
    "..OOO.......OO..",
)

private val GOOMBA_W0 = arrayOf(
    "................",
    ".....GGGGGG.....",
    "...GGGGGGGGGG...",
    "..GGGGGGGGGGGG..",
    "..GGWGGGGGGWGG..",
    "..GGWKGGGGWKGG..",
    "..GGWKGGGGWKGG..",
    "..GGGGGGGGGGGG..",
    "..GGGGGGGGGGGG..",
    "...GGGGGGGGGG...",
    "....GGGGGGGG....",
    "...GGGGGGGGGG...",
    "..GGGGGGGGGGGG..",
    "..FFFGGGGGGFFF..",
    ".FFFFGGGGGGFFFF.",
    ".FFFF......FFFF.",
)

private val GOOMBA_W1 = arrayOf(
    "................",
    ".....GGGGGG.....",
    "...GGGGGGGGGG...",
    "..GGGGGGGGGGGG..",
    "..GGWGGGGGGWGG..",
    "..GGWKGGGGWKGG..",
    "..GGWKGGGGWKGG..",
    "..GGGGGGGGGGGG..",
    "..GGGGGGGGGGGG..",
    "...GGGGGGGGGG...",
    "....GGGGGGGG....",
    "..GGGGGGGGGGGG..",
    "...GGGGGGGGGG...",
    "..FFFFGGGGFFFF..",
    "..FFFFGGGGFFFF..",
    "..FFFF....FFFF..",
)

private val GOOMBA_FLAT = arrayOf(
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "..GGGGGGGGGGGG..",
    ".FFFFGGGGGGFFFF.",
    "FFFFFGGGGGGFFFFF",
    "FFFFF......FFFFF",
)

private val KOOPA_W0 = arrayOf(
    ".......KKK......",
    "......KKKKK.....",
    "......KWWWK.....",
    "......KWKWK.....",
    "......KKKKK.....",
    ".......YYY......",
    ".......YYY......",
    "....YY.YYY.YY...",
    "...YYY.YYY.YYY..",
    "...YY...Y...YY..",
    ".......YYY......",
    "......KKKKK.....",
    ".....KKKKKKK....",
    "....KKKKKKKKK...",
    "....KKkkkkkKK...",
    "....KkkkkkkkK...",
    "....KkkkkkkkK...",
    "....KKkkkkkKK...",
    "....KKKKKKKKK...",
    ".....KKKKKKK....",
    "......KKKKK.....",
    ".....OO...OO....",
    "....OOO...OOO...",
    "...OOO.....OOO..",
)

private val KOOPA_W1 = arrayOf(
    ".......KKK......",
    "......KKKKK.....",
    "......KWWWK.....",
    "......KWKWK.....",
    "......KKKKK.....",
    ".......YYY......",
    ".......YYY......",
    "....YY.YYY.YY...",
    "...YYY.YYY.YYY..",
    "...YY...Y...YY..",
    ".......YYY......",
    "......KKKKK.....",
    ".....KKKKKKK....",
    "....KKKKKKKKK...",
    "....KKkkkkkKK...",
    "....KkkkkkkkK...",
    "....KkkkkkkkK...",
    "....KKkkkkkKK...",
    "....KKKKKKKKK...",
    ".....KKKKKKK....",
    "......KKKKK.....",
    ".....OO...OO....",
    "...OOO.....OOO..",
    "..OOO.......OOO.",
)

private val SHELL_IMG = arrayOf(
    "................",
    "................",
    "......KKKK......",
    "....KKKKKKKK....",
    "...KKKKKKKKKK...",
    "..KKkkkkkkkkKK..",
    "..KkkkkkkkkkkK..",
    "..KkkkkkkkkkkK..",
    "..KkkkkkkkkkkK..",
    "..KkkkkkkkkkkK..",
    "..KkkkkkkkkkkK..",
    "..KKkkkkkkkkKK..",
    "...KKKKKKKKKK...",
    "....KKKKKKKK....",
    "......KKKK......",
    "................",
)

private val MUSHROOM_IMG = arrayOf(
    "................",
    ".....RRRRRR.....",
    "...RRRRRRRRRR...",
    "..RRWWRRRRWWRR..",
    ".RRWWWWRRWWWWRR.",
    ".RRWWWWRRWWWWRR.",
    "RRWWWWRRRRWWWWRR",
    "RRWWWWRRRRWWWWRR",
    "RRRRRRRRRRRRRRRR",
    ".RRRRRRRRRRRRRR.",
    "..SSSSSSSSSSSS..",
    "..SSEESSEESSSS..",
    "..SSEESSEESSSS..",
    "..SSSSSSSSSSSS..",
    "..SSSSSSSSSSSS..",
    "...SSSSSSSSSS...",
)

private val FIREFLOWER_IMG = arrayOf(
    "................",
    ".......RR.......",
    "......RRRR......",
    ".....RRRRRR.....",
    "....RRRWWWRR....",
    "....RRRWWWRR....",
    ".....RRRRRR.....",
    "......RRRR......",
    "...YYYY..YYYY...",
    "..YYYYYYYYYYYY..",
    "..G.YYYYYYYY.G..",
    ".GG.YYYYYYYY.GG.",
    ".GG..GYYYYG..GG.",
    "..G..GGYYGG..G..",
    "......GGGG......",
    "......GGGG......",
)

private val STAR_ITEM_IMG = arrayOf(
    ".......YY.......",
    ".......YY.......",
    "......YYYY......",
    "YYYY..YYYY..YYYY",
    ".YYYYYYYYYYYYYY.",
    "..YYYYYYYYYYYY..",
    "...YYYYYYYYYY...",
    "....YYYYYYYY....",
    "....YYYYYYYY....",
    "...YYYYYYYYYY...",
    "..YYYyYYYYyYYY..",
    ".YYYyYYYYYYyYYY.",
    "YYYyYYYYYYYYyYYY",
    ".YY.yyYYYYyy.YY.",
    "......YYYY......",
    ".......YY.......",
)

private val QUESTION_GLYPH = arrayOf(
    "..####..",
    ".##..##.",
    ".....##.",
    "....##..",
    "...##...",
    "...##...",
    "........",
    "...##...",
)

// ============================================================================

internal enum class MAnim { STAND, WALK0, WALK1, WALK2, JUMP, DEAD }

internal object Sprites {

    private var ready = false

    lateinit var ground: BufferedImage
    lateinit var brick: BufferedImage
    lateinit var solid: BufferedImage
    lateinit var used: BufferedImage
    lateinit var platform: BufferedImage
    lateinit var question: Array<BufferedImage>
    lateinit var coin: Array<BufferedImage>
    lateinit var pipeTopL: BufferedImage
    lateinit var pipeTopR: BufferedImage
    lateinit var pipeBodyL: BufferedImage
    lateinit var pipeBodyR: BufferedImage
    lateinit var hill: BufferedImage
    lateinit var cloud: BufferedImage
    lateinit var bush: BufferedImage
    lateinit var castle: BufferedImage
    lateinit var goomba: Array<BufferedImage>
    lateinit var goombaFlat: BufferedImage
    lateinit var koopa: Array<BufferedImage>
    lateinit var shell: BufferedImage
    lateinit var mushroom: BufferedImage
    lateinit var fireflower: BufferedImage
    lateinit var star: BufferedImage
    lateinit var fireball: Array<BufferedImage>

    private val marioCache = HashMap<String, BufferedImage>()

    fun init() {
        if (ready) return
        ground = makeGround()
        brick = makeBrick()
        solid = makeSolid()
        used = makeUsed()
        platform = makePlatform()
        question = arrayOf(
            makeQuestion(rgb(0xE8, 0xA0, 0x00)),
            makeQuestion(rgb(0xF8, 0xC0, 0x20)),
            makeQuestion(rgb(0xFC, 0xE0, 0x60)),
            makeQuestion(rgb(0xF8, 0xC0, 0x20)),
        )
        coin = arrayOf(makeCoin(3.6f), makeCoin(2.6f), makeCoin(1.6f), makeCoin(2.6f))
        pipeTopL = makePipe(true, true)
        pipeTopR = makePipe(false, true)
        pipeBodyL = makePipe(true, false)
        pipeBodyR = makePipe(false, false)
        hill = makeHill()
        cloud = makeCloud()
        bush = makeBush()
        castle = makeCastle()
        goomba = arrayOf(draw(GOOMBA_W0, PAL_GOOMBA), draw(GOOMBA_W1, PAL_GOOMBA))
        goombaFlat = draw(GOOMBA_FLAT, PAL_GOOMBA)
        koopa = arrayOf(draw(KOOPA_W0, PAL_KOOPA), draw(KOOPA_W1, PAL_KOOPA))
        shell = draw(SHELL_IMG, PAL_KOOPA)
        mushroom = draw(MUSHROOM_IMG, PAL_MUSHROOM)
        fireflower = draw(FIREFLOWER_IMG, PAL_FLOWER)
        star = draw(STAR_ITEM_IMG, PAL_STAR_ITEM)
        fireball = arrayOf(
            makeFireball(3.6f, 3.6f),
            makeFireball(3.9f, 2.9f),
            makeFireball(3.4f, 3.4f),
            makeFireball(2.9f, 3.9f),
        )
        ready = true
    }

    private fun draw(rows: Array<String>, pal: Map<Char, Int>, flip: Boolean = false): BufferedImage {
        val r = Raster(16, rows.size)
        r.blit(0, 0, rows, pal, flip, 16)
        return r.toImage()
    }

    // ---------------- 瓦片 ----------------

    private fun makeGround(): BufferedImage {
        val r = Raster(16, 16)
        r.rect(0, 0, 16, 16, GROUND)
        r.rect(0, 0, 16, 3, GROUND_HI)
        r.rect(0, 0, 2, 16, GROUND_HI)
        r.rect(4, 5, 3, 3, GROUND_LO)
        r.rect(10, 6, 3, 3, GROUND_LO)
        r.rect(2, 11, 3, 2, GROUND_LO)
        r.rect(9, 12, 4, 2, GROUND_LO)
        r.rect(15, 0, 1, 16, C_BLACK)
        r.rect(0, 15, 16, 1, C_BLACK)
        return r.toImage()
    }

    private fun makeBrick(): BufferedImage {
        val r = Raster(16, 16)
        r.rect(0, 0, 16, 16, GROUND)
        for (y in 0..15) {
            val off = (y / 8) * 4
            val n = y % 8
            for (x in 0..15) {
                val m = ((x - off) % 8 + 8) % 8
                when {
                    n == 0 || m == 0 -> r.set(x, y, C_BLACK)
                    n == 1 || m == 1 -> r.set(x, y, GROUND_HI)
                }
            }
        }
        return r.toImage()
    }

    private fun makeSolid(): BufferedImage {
        val r = Raster(16, 16)
        r.rect(0, 0, 16, 16, rgb(0xF8, 0xC0, 0x90))
        r.rect(1, 1, 14, 3, rgb(0xFC, 0xE0, 0xC0))
        r.rect(1, 12, 14, 3, rgb(0xC0, 0x78, 0x40))
        r.frame(0, 0, 16, 16, rgb(0x38, 0x10, 0x00))
        r.rect(3, 3, 2, 2, rgb(0x38, 0x10, 0x00))
        r.rect(11, 3, 2, 2, rgb(0x38, 0x10, 0x00))
        r.rect(3, 11, 2, 2, rgb(0x38, 0x10, 0x00))
        r.rect(11, 11, 2, 2, rgb(0x38, 0x10, 0x00))
        return r.toImage()
    }

    private fun makeUsed(): BufferedImage {
        val r = Raster(16, 16)
        r.rect(0, 0, 16, 16, rgb(0xA0, 0x50, 0x10))
        r.rect(1, 1, 14, 2, rgb(0xC0, 0x70, 0x30))
        r.rect(1, 13, 14, 2, rgb(0x70, 0x30, 0x08))
        r.frame(0, 0, 16, 16, C_BLACK)
        r.rect(5, 5, 6, 6, rgb(0x88, 0x40, 0x0C))
        return r.toImage()
    }

    private fun makePlatform(): BufferedImage {
        val r = Raster(16, 16)
        r.rect(0, 0, 16, 16, GREEN)
        r.rect(0, 0, 16, 3, GREEN_HI)
        r.rect(0, 13, 16, 3, GREEN_LO)
        r.frame(0, 0, 16, 16, C_BLACK)
        r.rect(3, 6, 2, 4, GREEN_LO)
        r.rect(11, 6, 2, 4, GREEN_LO)
        return r.toImage()
    }

    private fun makeQuestion(base: Int): BufferedImage {
        val r = Raster(16, 16)
        r.rect(0, 0, 16, 16, base)
        r.rect(1, 1, 14, 2, GOLD_HI)
        r.rect(1, 13, 14, 2, GOLD_LO)
        r.rect(1, 1, 2, 14, GOLD_HI)
        r.rect(13, 1, 2, 14, GOLD_LO)
        r.frame(0, 0, 16, 16, C_BLACK)
        r.rect(2, 2, 2, 2, C_BLACK)
        r.rect(12, 2, 2, 2, C_BLACK)
        r.rect(2, 12, 2, 2, C_BLACK)
        r.rect(12, 12, 2, 2, C_BLACK)
        r.blit(4, 4, QUESTION_GLYPH, mapOf('#' to C_BLACK))
        return r.toImage()
    }

    private fun makeCoin(rx: Float): BufferedImage {
        val r = Raster(16, 16)
        r.ellipse(8f, 8f, rx, 7f, GOLD_LO)
        r.ellipse(8f, 8f, rx * 0.62f, 5f, GOLD)
        if (rx > 2f) r.ellipse(8f, 8f, rx * 0.24f, 3f, GOLD_HI)
        return r.toImage()
    }

    /** 水管：isLeft=左半；top=带管口的一节。 shading 在 32px 宽度上连续。 */
    private fun makePipe(isLeft: Boolean, top: Boolean): BufferedImage {
        val r = Raster(16, 16)
        r.rect(0, 0, 16, 16, GREEN)
        val bodyTop = if (top) 5 else 0
        if (isLeft) {
            r.rect(0, 0, 1, 16, C_BLACK)
            r.rect(1, bodyTop, 5, 16 - bodyTop, GREEN_HI)
        } else {
            r.rect(11, bodyTop, 4, 16 - bodyTop, GREEN_LO)
            r.rect(15, 0, 1, 16, C_BLACK)
        }
        if (top) {
            // 管口：整幅宽度上的一圈唇边
            r.rect(0, 0, 16, 5, GREEN)
            if (isLeft) {
                r.rect(1, 1, 5, 4, GREEN_HI)
                r.rect(13, 1, 3, 4, GREEN_LO)
                r.rect(0, 0, 1, 5, C_BLACK)
            } else {
                r.rect(1, 1, 4, 4, GREEN_HI)
                r.rect(12, 1, 3, 4, GREEN_LO)
                r.rect(15, 0, 1, 5, C_BLACK)
            }
            r.rect(0, 0, 16, 1, C_BLACK)
            r.rect(0, 4, 16, 1, C_BLACK)
        }
        return r.toImage()
    }

    // ---------------- 背景 ----------------

    private fun makeHill(): BufferedImage {
        val r = Raster(80, 48)
        for (x in 0 until 80) {
            val t = (x - 39.5f) / 40f
            val hgt = (48 * (1f - t * t) * 0.98f).toInt().coerceAtLeast(0)
            r.rect(x, 48 - hgt - 2, 1, hgt + 2, GREEN_LO)
            r.rect(x, 48 - hgt, 1, hgt, GREEN)
        }
        r.ellipse(24f, 30f, 5f, 4f, GREEN_LO)
        r.ellipse(52f, 34f, 6f, 5f, GREEN_LO)
        r.ellipse(38f, 21f, 4f, 3f, GREEN_LO)
        return r.toImage()
    }

    private fun makeCloud(): BufferedImage {
        val r = Raster(48, 32)
        r.ellipse(16f, 17f, 13f, 11f, C_WHITE)
        r.ellipse(30f, 15f, 14f, 12f, C_WHITE)
        r.ellipse(24f, 22f, 20f, 8f, C_WHITE)
        r.ellipse(24f, 26f, 17f, 4f, rgb(0xD8, 0xE8, 0xFC))
        return r.toImage()
    }

    private fun makeBush(): BufferedImage {
        val r = Raster(48, 16)
        r.ellipse(12f, 13f, 11f, 8f, GREEN)
        r.ellipse(24f, 11f, 13f, 9f, GREEN)
        r.ellipse(37f, 13f, 11f, 8f, GREEN)
        r.ellipse(14f, 8f, 5f, 3f, GREEN_HI)
        r.ellipse(26f, 6f, 6f, 3f, GREEN_HI)
        return r.toImage()
    }

    private fun makeCastle(): BufferedImage {
        val r = Raster(80, 80)
        val wall = GROUND
        val dark = GROUND_LO
        for (i in 0..4) r.rect(i * 16, 0, 8, 16, wall) // 城齿
        r.rect(0, 16, 80, 8, wall)
        r.rect(4, 24, 72, 56, wall)
        r.rect(0, 15, 80, 1, C_BLACK)
        r.rect(0, 23, 80, 1, C_BLACK)
        for (y in 32 until 80 step 8) r.rect(4, y, 72, 1, dark)
        r.rect(4, 24, 6, 56, dark)
        r.rect(66, 24, 10, 56, dark)
        r.rect(30, 40, 20, 40, C_BLACK) // 门洞
        r.rect(33, 36, 14, 4, C_BLACK)
        r.rect(16, 40, 8, 10, C_BLACK)  // 窗
        r.rect(56, 40, 8, 10, C_BLACK)
        return r.toImage()
    }

    // ---------------- 道具 / 弹丸 ----------------

    private fun makeFireball(rx: Float, ry: Float): BufferedImage {
        val r = Raster(8, 8)
        r.ellipse(4f, 4f, rx, ry, rgb(0xE0, 0x20, 0x20))
        r.ellipse(4f, 4f, rx * 0.6f, ry * 0.6f, rgb(0xF8, 0x80, 0x00))
        r.ellipse(4f, 4f, rx * 0.3f, ry * 0.3f, rgb(0xF8, 0xF8, 0x00))
        return r.toImage()
    }

    /** 马里奥：按需生成并缓存（形态 x 动作 x 无敌闪光 x 朝向）。 */
    fun mario(form: Form, anim: MAnim, flash: Int, face: Int): BufferedImage {
        val key = "${form.ordinal}-${anim.ordinal}-$flash-${if (face < 0) 1 else 0}"
        marioCache[key]?.let { return it }
        val img = buildMario(form, anim, flash, face)
        marioCache[key] = img
        return img
    }

    private fun buildMario(form: Form, anim: MAnim, flash: Int, face: Int): BufferedImage {
        val pal = when (flash) {
            0 -> if (form == Form.FIRE) PAL_FIRE else PAL_MARIO
            1 -> PAL_STAR_A
            2 -> PAL_STAR_B
            else -> PAL_STAR_C
        }
        val flip = face < 0
        if (anim == MAnim.DEAD) {
            val rows = if (form == Form.SMALL) SMALL_DEAD else BIG_DEAD
            val r = Raster(16, rows.size)
            r.blit(0, 0, rows, pal, flip, 16)
            return r.toImage()
        }
        val small = form == Form.SMALL
        val h = if (small) 16 else 24
        val body = if (small) SMALL_BODY else BIG_BODY
        val (legs, doFlip) = when (anim) {
            MAnim.WALK0 -> (if (small) SMALL_LEGS_W0 else BIG_LEGS_W0) to flip
            MAnim.WALK1 -> (if (small) SMALL_LEGS_W1 else BIG_LEGS_W1) to flip
            MAnim.WALK2 -> (if (small) SMALL_LEGS_W0 else BIG_LEGS_W0) to !flip
            MAnim.JUMP -> (if (small) SMALL_LEGS_JUMP else BIG_LEGS_JUMP) to flip
            else -> (if (small) SMALL_LEGS_STAND else BIG_LEGS_STAND) to flip
        }
        val r = Raster(16, h)
        r.blit(0, 0, body, pal, doFlip, 16)
        r.blit(0, if (small) 12 else 18, legs, pal, doFlip, 16)
        return r.toImage()
    }
}
