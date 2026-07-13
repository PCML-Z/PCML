package com.pmcl.core.nbt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * NBT 二进制读取器。
 * <p>
 * 读取 Notchian NBT 格式（大端字节序），支持 gzip 压缩（level.dat 等默认 gzip 压缩）。
 * 使用方式：{@code NbtTag root = NbtReader.read(path);} 或
 *           {@code NbtTag root = NbtReader.read(inputStream, true);}
 */
public final class NbtReader {

    private NbtReader() {}

    /**
     * 读取 gzip 压缩的 NBT 文件（level.dat 标准格式）。
     */
    public static NbtTag read(java.nio.file.Path file) throws IOException {
        try (InputStream fis = java.nio.file.Files.newInputStream(file);
             GZIPInputStream gz = new GZIPInputStream(fis);
             DataInputStream dis = new DataInputStream(gz)) {
            return readRoot(dis);
        }
    }

    /**
     * 从输入流读取 NBT。
     * @param in 输入流
     * @param gzipped 是否 gzip 压缩
     */
    public static NbtTag read(InputStream in, boolean gzipped) throws IOException {
        InputStream stream = gzipped ? new GZIPInputStream(in) : in;
        try (DataInputStream dis = new DataInputStream(stream)) {
            return readRoot(dis);
        }
    }

    /** 读取根标签（含根 Compound 的名称） */
    private static NbtTag readRoot(DataInputStream dis) throws IOException {
        int type = dis.readByte() & 0xFF;
        if (type == NbtTag.TYPE_END) return null;
        String name = readString(dis);
        NbtTag tag = readPayload(dis, type);
        tag.setName(name);
        return tag;
    }

    /** 读取指定类型的 payload（不含名称和类型前缀） */
    private static NbtTag readPayload(DataInputStream dis, int type) throws IOException {
        switch (type) {
            case NbtTag.TYPE_BYTE:    return new NbtTag.ByteTag(dis.readByte());
            case NbtTag.TYPE_SHORT:   return new NbtTag.ShortTag(dis.readShort());
            case NbtTag.TYPE_INT:     return new NbtTag.IntTag(dis.readInt());
            case NbtTag.TYPE_LONG:    return new NbtTag.LongTag(dis.readLong());
            case NbtTag.TYPE_FLOAT:   return new NbtTag.FloatTag(dis.readFloat());
            case NbtTag.TYPE_DOUBLE:  return new NbtTag.DoubleTag(dis.readDouble());
            case NbtTag.TYPE_BYTE_ARRAY: {
                int len = dis.readInt();
                byte[] arr = new byte[len];
                dis.readFully(arr);
                return new NbtTag.ByteArrayTag(arr);
            }
            case NbtTag.TYPE_STRING:
                return new NbtTag.StringTag(readString(dis));
            case NbtTag.TYPE_LIST: {
                int listType = dis.readByte() & 0xFF;
                int len = dis.readInt();
                NbtTag.ListTag list = new NbtTag.ListTag();
                list.setListType(listType);
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(dis, listType));
                }
                return list;
            }
            case NbtTag.TYPE_COMPOUND: {
                NbtTag.CompoundTag compound = new NbtTag.CompoundTag();
                while (true) {
                    int childType = dis.readByte() & 0xFF;
                    if (childType == NbtTag.TYPE_END) break;
                    String childName = readString(dis);
                    NbtTag child = readPayload(dis, childType);
                    child.setName(childName);
                    compound.getChildren().put(childName, child);
                }
                return compound;
            }
            case NbtTag.TYPE_INT_ARRAY: {
                int len = dis.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readInt();
                return new NbtTag.IntArrayTag(arr);
            }
            case NbtTag.TYPE_LONG_ARRAY: {
                int len = dis.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readLong();
                return new NbtTag.LongArrayTag(arr);
            }
            default:
                throw new IOException("未知的 NBT 类型: " + type);
        }
    }

    private static String readString(DataInputStream dis) throws IOException {
        int len = dis.readShort() & 0xFFFF;
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
