package com.lash.pmcl.core.nbt

import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPOutputStream

/**
 * NBT 二进制写入器。
 *
 * 写入 Notchian NBT 格式（大端字节序），默认 gzip 压缩（与 level.dat 兼容）。
 * 落盘采用临时文件 + 原子替换，避免写一半断电/崩溃导致存档损坏。
 *
 * Android 版本：从 Java 移植，使用 sealed class when 替代 instanceof 链。
 */
object NbtWriter {

    /**
     * 写入 gzip 压缩的 NBT 文件（level.dat 标准格式）。
     */
    @Throws(IOException::class)
    fun write(root: NbtTag, file: Path) {
        write(root, file, true)
    }

    /**
     * 写入 NBT 文件，可选择是否 gzip。
     * 先写 `file.tmp`，再原子替换到目标路径。
     */
    @Throws(IOException::class)
    fun write(root: NbtTag, file: Path, gzipped: Boolean) {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        try {
            Files.newOutputStream(tmp).use { fos ->
                if (gzipped) {
                    GZIPOutputStream(fos).use { gz ->
                        DataOutputStream(gz).use { dos ->
                            writeRoot(dos, root)
                        }
                    }
                } else {
                    DataOutputStream(fos).use { dos ->
                        writeRoot(dos, root)
                    }
                }
            }
            try {
                Files.move(
                    tmp, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            try { Files.deleteIfExists(tmp) } catch (ignored: IOException) {}
            throw e
        }
    }

    /**
     * 写入 NBT 到输出流。
     * @param out 输出流
     * @param gzipped 是否 gzip 压缩
     */
    @Throws(IOException::class)
    fun write(root: NbtTag, out: OutputStream, gzipped: Boolean) {
        val stream: OutputStream = if (gzipped) GZIPOutputStream(out) else out
        DataOutputStream(stream).use { dos ->
            writeRoot(dos, root)
        }
    }

    private fun writeRoot(dos: DataOutputStream, tag: NbtTag) {
        dos.writeByte(tag.type)
        writeString(dos, tag.name)
        writePayload(dos, tag)
    }

    private fun writePayload(dos: DataOutputStream, tag: NbtTag) {
        when (tag) {
            is NbtTag.ByteTag -> dos.writeByte(tag.value.toInt())
            is NbtTag.ShortTag -> dos.writeShort(tag.value.toInt())
            is NbtTag.IntTag -> dos.writeInt(tag.value)
            is NbtTag.LongTag -> dos.writeLong(tag.value)
            is NbtTag.FloatTag -> dos.writeFloat(tag.value)
            is NbtTag.DoubleTag -> dos.writeDouble(tag.value)
            is NbtTag.ByteArrayTag -> {
                val arr = tag.value
                dos.writeInt(arr.size)
                dos.write(arr)
            }
            is NbtTag.StringTag -> writeString(dos, tag.value)
            is NbtTag.ListTag -> {
                dos.writeByte(tag.listType)
                dos.writeInt(tag.size)
                for (item in tag.items) {
                    writePayload(dos, item)
                }
            }
            is NbtTag.CompoundTag -> {
                for ((key, value) in tag.children) {
                    dos.writeByte(value.type)
                    writeString(dos, key)
                    writePayload(dos, value)
                }
                dos.writeByte(NbtTag.TYPE_END)
            }
            is NbtTag.IntArrayTag -> {
                val arr = tag.value
                dos.writeInt(arr.size)
                for (v in arr) dos.writeInt(v)
            }
            is NbtTag.LongArrayTag -> {
                val arr = tag.value
                dos.writeInt(arr.size)
                for (v in arr) dos.writeLong(v)
            }
        }
    }

    private fun writeString(dos: DataOutputStream, s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        // NBT 字符串长度字段为 unsigned short；超长时 writeShort 会静默截断但仍写出全部字节，
        // 导致后续标签流损坏。与 NbtReader.MAX_STRING_LEN 对齐，前置拒绝。
        if (bytes.size > 65535) {
            throw IOException("NBT string too long: ${bytes.size} > 65535")
        }
        dos.writeShort(bytes.size)
        dos.write(bytes)
    }
}
