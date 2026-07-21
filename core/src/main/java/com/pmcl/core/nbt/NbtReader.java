package com.pmcl.core.nbt;

import java.io.BufferedInputStream;
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
 * <p>
 * <b>S8/S9 安全修复：</b>对恶意构造的 NBT 文件做防御性解析，防止崩溃：
 * <ul>
 *   <li>嵌套深度上限：{@link #MAX_DEPTH}（默认 64），防止递归爆栈</li>
 *   <li>数组长度上限：{@link #MAX_ARRAY_LEN}（默认 1,048,576 ≈ 1M 元素），
 *       防止 {@code new byte[Integer.MAX_VALUE]} 导致 OOM</li>
 *   <li>字符串长度上限：{@link #MAX_STRING_LEN}（默认 65535，NBT 规范上限）</li>
 *   <li>总字节读取上限：{@link #MAX_TOTAL_BYTES}（默认 256 MB），防止 gzip 炸弹</li>
 *   <li>list/compound 子元素数量上限：{@link #MAX_CHILDREN}（默认 1,000,000）</li>
 *   <li>所有 {@code readInt} 得到的长度必须 ≥ 0 且不超上限，否则抛 IOException</li>
 * </ul>
 */
public final class NbtReader {

    /** 最大嵌套深度（compound/list 递归）。Minecraft 存档正常深度通常 < 10。 */
    private static final int MAX_DEPTH = 64;

    /** 数组（byte/int/long array）最大元素数。1M 元素对应 byte=1MB / int=4MB / long=8MB。 */
    private static final int MAX_ARRAY_LEN = 1_048_576;

    /** 字符串最大字节数（NBT 规范用 unsigned short，上限 65535）。 */
    private static final int MAX_STRING_LEN = 65535;

    /** 单个 list/compound 的最大子元素数，防止恶意文件构造超长 list。 */
    private static final int MAX_CHILDREN = 1_000_000;

    /** 解压后总字节数上限（256 MB），防止 gzip 炸弹。 */
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    private NbtReader() {}

    /**
     * 读取 NBT 文件，自动检测 gzip 压缩（魔数 0x1f 0x8b）。
     * 兼容 gzip 压缩和未压缩的 NBT 文件。
     */
    public static NbtTag read(java.nio.file.Path file) throws IOException {
        try (InputStream fis = java.nio.file.Files.newInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            bis.mark(2);
            int b1 = bis.read();
            int b2 = bis.read();
            bis.reset();
            boolean gzipped = (b1 == 0x1f && b2 == 0x8b);
            InputStream stream = gzipped ? new GZIPInputStream(bis) : bis;
            try (DataInputStream dis = new DataInputStream(stream)) {
                return readRoot(dis);
            }
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
        if (!isValidType(type)) {
            throw new IOException("Invalid root NBT type: " + type);
        }
        String name = readString(dis);
        NbtTag tag = readPayload(dis, type, 0);
        tag.setName(name);
        return tag;
    }

    /** 读取指定类型的 payload（不含名称和类型前缀）。
     *  @param depth 当前嵌套深度（0 为根 payload） */
    private static NbtTag readPayload(DataInputStream dis, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting depth exceeds limit " + MAX_DEPTH
                    + " (possible malicious file)");
        }
        switch (type) {
            case NbtTag.TYPE_BYTE:    return new NbtTag.ByteTag(dis.readByte());
            case NbtTag.TYPE_SHORT:   return new NbtTag.ShortTag(dis.readShort());
            case NbtTag.TYPE_INT:     return new NbtTag.IntTag(dis.readInt());
            case NbtTag.TYPE_LONG:    return new NbtTag.LongTag(dis.readLong());
            case NbtTag.TYPE_FLOAT:   return new NbtTag.FloatTag(dis.readFloat());
            case NbtTag.TYPE_DOUBLE:  return new NbtTag.DoubleTag(dis.readDouble());
            case NbtTag.TYPE_BYTE_ARRAY: {
                int len = dis.readInt();
                checkArrayLength(len, "byte array");
                byte[] arr = new byte[len];
                dis.readFully(arr);
                return new NbtTag.ByteArrayTag(arr);
            }
            case NbtTag.TYPE_STRING:
                return new NbtTag.StringTag(readString(dis));
            case NbtTag.TYPE_LIST: {
                int listType = dis.readByte() & 0xFF;
                if (!isValidType(listType) && listType != NbtTag.TYPE_END) {
                    throw new IOException("Invalid NBT list element type: " + listType);
                }
                int len = dis.readInt();
                checkListLength(len, "list");
                NbtTag.ListTag list = new NbtTag.ListTag();
                list.setListType(listType);
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(dis, listType, depth + 1));
                }
                return list;
            }
            case NbtTag.TYPE_COMPOUND: {
                NbtTag.CompoundTag compound = new NbtTag.CompoundTag();
                int childCount = 0;
                while (true) {
                    int childType = dis.readByte() & 0xFF;
                    if (childType == NbtTag.TYPE_END) break;
                    if (!isValidType(childType)) {
                        throw new IOException("Invalid NBT compound child type: " + childType);
                    }
                    if (++childCount > MAX_CHILDREN) {
                        throw new IOException("NBT compound children count exceeds limit "
                                + MAX_CHILDREN + " at depth " + depth);
                    }
                    String childName = readString(dis);
                    NbtTag child = readPayload(dis, childType, depth + 1);
                    child.setName(childName);
                    compound.getChildren().put(childName, child);
                }
                return compound;
            }
            case NbtTag.TYPE_INT_ARRAY: {
                int len = dis.readInt();
                checkArrayLength(len, "int array");
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readInt();
                return new NbtTag.IntArrayTag(arr);
            }
            case NbtTag.TYPE_LONG_ARRAY: {
                int len = dis.readInt();
                checkArrayLength(len, "long array");
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
        if (len > MAX_STRING_LEN) {
            throw new IOException("NBT string length " + len + " exceeds limit " + MAX_STRING_LEN);
        }
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 校验数组长度是否在安全范围内。 */
    private static void checkArrayLength(int len, String ctx) throws IOException {
        if (len < 0) {
            throw new IOException("NBT " + ctx + " length is negative: " + len
                    + " (possible corrupted/malicious file)");
        }
        if (len > MAX_ARRAY_LEN) {
            throw new IOException("NBT " + ctx + " length " + len + " exceeds limit " + MAX_ARRAY_LEN
                    + " (possible zip bomb / memory exhaustion attack)");
        }
    }

    /** 校验 list 长度是否在安全范围内。 */
    private static void checkListLength(int len, String ctx) throws IOException {
        if (len < 0) {
            throw new IOException("NBT " + ctx + " length is negative: " + len);
        }
        if (len > MAX_CHILDREN) {
            throw new IOException("NBT " + ctx + " length " + len + " exceeds limit " + MAX_CHILDREN);
        }
    }

    /** 判断 type 是否为合法 NBT 类型 ID（1-12，0 = TYPE_END）。 */
    private static boolean isValidType(int type) {
        return type >= 1 && type <= 12;
    }
}
