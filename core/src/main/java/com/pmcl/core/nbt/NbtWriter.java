package com.pmcl.core.nbt;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * NBT 二进制写入器。
 * <p>
 * 写入 Notchian NBT 格式（大端字节序），默认 gzip 压缩（与 level.dat 兼容）。
 */
public final class NbtWriter {

    private NbtWriter() {}

    /**
     * 写入 gzip 压缩的 NBT 文件（level.dat 标准格式）。
     */
    public static void write(NbtTag root, Path file) throws IOException {
        try (OutputStream fos = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(gz)) {
            writeRoot(dos, root);
        }
    }

    /**
     * 写入 NBT 到输出流。
     * @param out 输出流
     * @param gzipped 是否 gzip 压缩
     */
    public static void write(NbtTag root, OutputStream out, boolean gzipped) throws IOException {
        OutputStream stream = gzipped ? new GZIPOutputStream(out) : out;
        try (DataOutputStream dos = new DataOutputStream(stream)) {
            writeRoot(dos, root);
        }
    }

    private static void writeRoot(DataOutputStream dos, NbtTag tag) throws IOException {
        dos.writeByte(tag.getType());
        writeString(dos, tag.getName());
        writePayload(dos, tag);
    }

    private static void writePayload(DataOutputStream dos, NbtTag tag) throws IOException {
        switch (tag.getType()) {
            case NbtTag.TYPE_BYTE:
                dos.writeByte(((NbtTag.ByteTag) tag).getValue());
                break;
            case NbtTag.TYPE_SHORT:
                dos.writeShort(((NbtTag.ShortTag) tag).getValue());
                break;
            case NbtTag.TYPE_INT:
                dos.writeInt(((NbtTag.IntTag) tag).getValue());
                break;
            case NbtTag.TYPE_LONG:
                dos.writeLong(((NbtTag.LongTag) tag).getValue());
                break;
            case NbtTag.TYPE_FLOAT:
                dos.writeFloat(((NbtTag.FloatTag) tag).getValue());
                break;
            case NbtTag.TYPE_DOUBLE:
                dos.writeDouble(((NbtTag.DoubleTag) tag).getValue());
                break;
            case NbtTag.TYPE_BYTE_ARRAY: {
                byte[] arr = ((NbtTag.ByteArrayTag) tag).getValue();
                dos.writeInt(arr.length);
                dos.write(arr);
                break;
            }
            case NbtTag.TYPE_STRING:
                writeString(dos, ((NbtTag.StringTag) tag).getValue());
                break;
            case NbtTag.TYPE_LIST: {
                NbtTag.ListTag list = (NbtTag.ListTag) tag;
                dos.writeByte(list.getListType());
                dos.writeInt(list.size());
                for (NbtTag item : list.getItems()) {
                    writePayload(dos, item);
                }
                break;
            }
            case NbtTag.TYPE_COMPOUND: {
                NbtTag.CompoundTag compound = (NbtTag.CompoundTag) tag;
                for (Map.Entry<String, NbtTag> e : compound.getChildren().entrySet()) {
                    dos.writeByte(e.getValue().getType());
                    writeString(dos, e.getKey());
                    writePayload(dos, e.getValue());
                }
                dos.writeByte(NbtTag.TYPE_END); // Compound 结束标记
                break;
            }
            case NbtTag.TYPE_INT_ARRAY: {
                int[] arr = ((NbtTag.IntArrayTag) tag).getValue();
                dos.writeInt(arr.length);
                for (int v : arr) dos.writeInt(v);
                break;
            }
            case NbtTag.TYPE_LONG_ARRAY: {
                long[] arr = ((NbtTag.LongArrayTag) tag).getValue();
                dos.writeInt(arr.length);
                for (long v : arr) dos.writeLong(v);
                break;
            }
            default:
                throw new IOException("未知的 NBT 类型: " + tag.getType());
        }
    }

    private static void writeString(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }
}
