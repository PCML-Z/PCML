package com.pmcl.core.nbt;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * NBT 二进制写入器。
 * <p>
 * 写入 Notchian NBT 格式（大端字节序），默认 gzip 压缩（与 level.dat 兼容）。
 * 落盘采用临时文件 + 原子替换，避免写一半断电/崩溃导致存档损坏。
 */
public final class NbtWriter {

    private NbtWriter() {}

    /**
     * 写入 gzip 压缩的 NBT 文件（level.dat 标准格式）。
     */
    public static void write(NbtTag root, Path file) throws IOException {
        write(root, file, true);
    }

    /**
     * 写入 NBT 文件，可选择是否 gzip。
     * 先写 {@code file.tmp}，再原子替换到目标路径。
     */
    public static void write(NbtTag root, Path file, boolean gzipped) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            try (OutputStream fos = Files.newOutputStream(tmp)) {
                if (gzipped) {
                    try (GZIPOutputStream gz = new GZIPOutputStream(fos);
                         DataOutputStream dos = new DataOutputStream(gz)) {
                        writeRoot(dos, root);
                    }
                } else {
                    try (DataOutputStream dos = new DataOutputStream(fos)) {
                        writeRoot(dos, root);
                    }
                }
            }
            try {
                Files.move(tmp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
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
                dos.writeByte(NbtTag.TYPE_END);
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
