package com.lash.pmcl.core.nbt

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * NBT 二进制读取器。
 *
 * 读取 Notchian NBT 格式（大端字节序），支持 gzip 压缩（level.dat 等默认 gzip 压缩）。
 * 使用方式：`NbtTag root = NbtReader.read(path)` 或
 *           `NbtTag root = NbtReader.read(inputStream, true)`
 *
 * **安全防护：**对恶意构造的 NBT 文件做防御性解析，防止崩溃：
 * - 嵌套深度上限：MAX_DEPTH（默认 64），防止递归爆栈
 * - 数组长度上限：MAX_ARRAY_LEN（默认 1,048,576 ≈ 1M 元素），
 *   防止 `new byte[Integer.MAX_VALUE]` 导致 OOM
 * - 字符串长度上限：MAX_STRING_LEN（默认 65535，NBT 规范上限）
 * - 总字节读取上限：MAX_TOTAL_BYTES（默认 256 MB），防止 gzip 炸弹
 * - list/compound 子元素数量上限：MAX_CHILDREN（默认 1,000,000）
 * - 所有 readInt 得到的长度必须 ≥ 0 且不超上限，否则抛 IOException
 *
 * Android 版本：从 Java 移植，逻辑保持一致。
 */
object NbtReader {

    /** 最大嵌套深度（compound/list 递归）。Minecraft 存档正常深度通常 < 10。 */
    private const val MAX_DEPTH = 64

    /** 数组（byte/int/long array）最大元素数。1M 元素对应 byte=1MB / int=4MB / long=8MB。 */
    private const val MAX_ARRAY_LEN = 1_048_576

    /** 字符串最大字节数（NBT 规范用 unsigned short，上限 65535）。 */
    private const val MAX_STRING_LEN = 65535

    /** 单个 list/compound 的最大子元素数，防止恶意文件构造超长 list。 */
    private const val MAX_CHILDREN = 1_000_000

    /** 解压后总字节数上限（256 MB），防止 gzip 炸弹。 */
    private const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

    /** 读取结果：根标签 + 是否 gzip（保存时保持原压缩方式） */
    data class ReadResult(val root: NbtTag?, val gzipped: Boolean)

    /**
     * 读取 NBT 文件，自动检测 gzip 压缩（魔数 0x1f 0x8b）。
     * 兼容 gzip 压缩和未压缩的 NBT 文件。
     */
    @Throws(IOException::class)
    fun read(file: Path): NbtTag? = readWithMeta(file).root

    /** 读取 NBT 并返回是否 gzip，供保存时保持压缩方式。 */
    @Throws(IOException::class)
    fun readWithMeta(file: Path): ReadResult {
        Files.newInputStream(file).use { fis ->
            BufferedInputStream(fis).use { bis ->
                bis.mark(2)
                val b1 = bis.read()
                val b2 = bis.read()
                bis.reset()
                val gzipped = (b1 == 0x1f && b2 == 0x8b)
                val stream: InputStream = if (gzipped) GZIPInputStream(bis) else bis
                // 在解压后计数，防止 gzip 炸弹撑爆内存
                DataInputStream(CountingInputStream(stream, MAX_TOTAL_BYTES)).use { dis ->
                    return ReadResult(readRoot(dis), gzipped)
                }
            }
        }
    }

    /**
     * 从输入流读取 NBT。
     * @param input 输入流
     * @param gzipped 是否 gzip 压缩
     */
    @Throws(IOException::class)
    fun read(input: InputStream, gzipped: Boolean): NbtTag? {
        val stream: InputStream = if (gzipped) GZIPInputStream(input) else input
        DataInputStream(CountingInputStream(stream, MAX_TOTAL_BYTES)).use { dis ->
            return readRoot(dis)
        }
    }

    /** 统计已读字节并在超过上限时抛出，防御 gzip 炸弹。 */
    private class CountingInputStream(
        input: InputStream,
        private val maxBytes: Long
    ) : FilterInputStream(input) {

        private var readBytes: Long = 0L

        private fun account(n: Long) {
            if (n <= 0) return
            readBytes += n
            if (readBytes > maxBytes) {
                throw IOException("NBT decompressed size exceeds limit $maxBytes bytes (possible gzip bomb)")
            }
        }

        override fun read(): Int {
            val b = `in`.read()
            if (b >= 0) account(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = `in`.read(b, off, len)
            if (n > 0) account(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = `in`.skip(n)
            account(skipped)
            return skipped
        }
    }

    /** 读取根标签（含根 Compound 的名称） */
    private fun readRoot(dis: DataInputStream): NbtTag? {
        val type = dis.readByte().toInt() and 0xFF
        if (type == NbtTag.TYPE_END) return null
        if (!isValidType(type)) {
            throw IOException("Invalid root NBT type: $type")
        }
        val name = readString(dis)
        val tag = readPayload(dis, type, 0)
        tag.name = name
        return tag
    }

    /**
     * 读取指定类型的 payload（不含名称和类型前缀）。
     * @param depth 当前嵌套深度（0 为根 payload）
     */
    private fun readPayload(dis: DataInputStream, type: Int, depth: Int): NbtTag {
        if (depth > MAX_DEPTH) {
            throw IOException("NBT nesting depth exceeds limit $MAX_DEPTH (possible malicious file)")
        }
        return when (type) {
            NbtTag.TYPE_BYTE -> NbtTag.ByteTag(dis.readByte())
            NbtTag.TYPE_SHORT -> NbtTag.ShortTag(dis.readShort())
            NbtTag.TYPE_INT -> NbtTag.IntTag(dis.readInt())
            NbtTag.TYPE_LONG -> NbtTag.LongTag(dis.readLong())
            NbtTag.TYPE_FLOAT -> NbtTag.FloatTag(dis.readFloat())
            NbtTag.TYPE_DOUBLE -> NbtTag.DoubleTag(dis.readDouble())
            NbtTag.TYPE_BYTE_ARRAY -> {
                val len = dis.readInt()
                checkArrayLength(len, "byte array")
                val arr = ByteArray(len)
                dis.readFully(arr)
                NbtTag.ByteArrayTag(arr)
            }
            NbtTag.TYPE_STRING -> NbtTag.StringTag(readString(dis))
            NbtTag.TYPE_LIST -> {
                val listType = dis.readByte().toInt() and 0xFF
                if (!isValidType(listType) && listType != NbtTag.TYPE_END) {
                    throw IOException("Invalid NBT list element type: $listType")
                }
                val len = dis.readInt()
                checkListLength(len, "list")
                val list = NbtTag.ListTag()
                list.listType = listType
                repeat(len) { list.add(readPayload(dis, listType, depth + 1)) }
                list
            }
            NbtTag.TYPE_COMPOUND -> {
                val compound = NbtTag.CompoundTag()
                var childCount = 0
                while (true) {
                    val childType = dis.readByte().toInt() and 0xFF
                    if (childType == NbtTag.TYPE_END) break
                    if (!isValidType(childType)) {
                        throw IOException("Invalid NBT compound child type: $childType")
                    }
                    if (++childCount > MAX_CHILDREN) {
                        throw IOException("NBT compound children count exceeds limit $MAX_CHILDREN at depth $depth")
                    }
                    val childName = readString(dis)
                    val child = readPayload(dis, childType, depth + 1)
                    child.name = childName
                    compound.children[childName] = child
                }
                compound
            }
            NbtTag.TYPE_INT_ARRAY -> {
                val len = dis.readInt()
                checkArrayLength(len, "int array")
                val arr = IntArray(len)
                for (i in 0 until len) arr[i] = dis.readInt()
                NbtTag.IntArrayTag(arr)
            }
            NbtTag.TYPE_LONG_ARRAY -> {
                val len = dis.readInt()
                checkArrayLength(len, "long array")
                val arr = LongArray(len)
                for (i in 0 until len) arr[i] = dis.readLong()
                NbtTag.LongArrayTag(arr)
            }
            else -> throw IOException("未知的 NBT 类型: $type")
        }
    }

    private fun readString(dis: DataInputStream): String {
        val len = dis.readShort().toInt() and 0xFFFF
        if (len == 0) return ""
        if (len > MAX_STRING_LEN) {
            throw IOException("NBT string length $len exceeds limit $MAX_STRING_LEN")
        }
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** 校验数组长度是否在安全范围内。 */
    private fun checkArrayLength(len: Int, ctx: String) {
        if (len < 0) {
            throw IOException("NBT $ctx length is negative: $len (possible corrupted/malicious file)")
        }
        if (len > MAX_ARRAY_LEN) {
            throw IOException("NBT $ctx length $len exceeds limit $MAX_ARRAY_LEN (possible zip bomb / memory exhaustion attack)")
        }
    }

    /** 校验 list 长度是否在安全范围内。 */
    private fun checkListLength(len: Int, ctx: String) {
        if (len < 0) {
            throw IOException("NBT $ctx length is negative: $len")
        }
        if (len > MAX_CHILDREN) {
            throw IOException("NBT $ctx length $len exceeds limit $MAX_CHILDREN")
        }
    }

    /** 判断 type 是否为合法 NBT 类型 ID（1-12，0 = TYPE_END）。 */
    private fun isValidType(type: Int): Boolean = type in 1..12
}
