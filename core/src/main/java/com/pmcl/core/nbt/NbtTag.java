package com.pmcl.core.nbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NBT (Named Binary Tag) 标签抽象基类。
 * <p>
 * NBT 是 Minecraft 使用的树形二进制数据格式，用于 level.dat、playerdata、servers.dat 等。
 * 类型常量符合 Notchian NBT 规范。
 */
public abstract class NbtTag {

    public static final int TYPE_END = 0;
    public static final int TYPE_BYTE = 1;
    public static final int TYPE_SHORT = 2;
    public static final int TYPE_INT = 3;
    public static final int TYPE_LONG = 4;
    public static final int TYPE_FLOAT = 5;
    public static final int TYPE_DOUBLE = 6;
    public static final int TYPE_BYTE_ARRAY = 7;
    public static final int TYPE_STRING = 8;
    public static final int TYPE_LIST = 9;
    public static final int TYPE_COMPOUND = 10;
    public static final int TYPE_INT_ARRAY = 11;
    public static final int TYPE_LONG_ARRAY = 12;

    private String name = "";

    /** 返回此标签的 NBT 类型 ID */
    public abstract int getType();

    /** 类型名称（用于 UI 展示） */
    public abstract String getTypeName();

    /** 获取标签名 */
    public String getName() { return name; }

    /** 设置标签名 */
    public void setName(String name) { this.name = name != null ? name : ""; }

    /** 转为可读的字符串值（用于 UI 展示叶节点值） */
    public abstract String getValueString();

    // ===== 子类 =====

    /** TAG_Byte */
    public static class ByteTag extends NbtTag {
        private byte value;
        public ByteTag(byte value) { this.value = value; }
        public byte getValue() { return value; }
        public void setValue(byte value) { this.value = value; }
        @Override public int getType() { return TYPE_BYTE; }
        @Override public String getTypeName() { return "Byte"; }
        @Override public String getValueString() { return String.valueOf(value); }
    }

    /** TAG_Short */
    public static class ShortTag extends NbtTag {
        private short value;
        public ShortTag(short value) { this.value = value; }
        public short getValue() { return value; }
        public void setValue(short value) { this.value = value; }
        @Override public int getType() { return TYPE_SHORT; }
        @Override public String getTypeName() { return "Short"; }
        @Override public String getValueString() { return String.valueOf(value); }
    }

    /** TAG_Int */
    public static class IntTag extends NbtTag {
        private int value;
        public IntTag(int value) { this.value = value; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
        @Override public int getType() { return TYPE_INT; }
        @Override public String getTypeName() { return "Int"; }
        @Override public String getValueString() { return String.valueOf(value); }
    }

    /** TAG_Long */
    public static class LongTag extends NbtTag {
        private long value;
        public LongTag(long value) { this.value = value; }
        public long getValue() { return value; }
        public void setValue(long value) { this.value = value; }
        @Override public int getType() { return TYPE_LONG; }
        @Override public String getTypeName() { return "Long"; }
        @Override public String getValueString() { return String.valueOf(value) + "L"; }
    }

    /** TAG_Float */
    public static class FloatTag extends NbtTag {
        private float value;
        public FloatTag(float value) { this.value = value; }
        public float getValue() { return value; }
        public void setValue(float value) { this.value = value; }
        @Override public int getType() { return TYPE_FLOAT; }
        @Override public String getTypeName() { return "Float"; }
        @Override public String getValueString() { return String.valueOf(value) + "F"; }
    }

    /** TAG_Double */
    public static class DoubleTag extends NbtTag {
        private double value;
        public DoubleTag(double value) { this.value = value; }
        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }
        @Override public int getType() { return TYPE_DOUBLE; }
        @Override public String getTypeName() { return "Double"; }
        @Override public String getValueString() { return String.valueOf(value) + "D"; }
    }

    /** TAG_Byte_Array */
    public static class ByteArrayTag extends NbtTag {
        private byte[] value;
        public ByteArrayTag(byte[] value) { this.value = value != null ? value : new byte[0]; }
        public byte[] getValue() { return value; }
        public void setValue(byte[] value) { this.value = value != null ? value : new byte[0]; }
        @Override public int getType() { return TYPE_BYTE_ARRAY; }
        @Override public String getTypeName() { return "ByteArray"; }
        @Override public String getValueString() { return "[" + value.length + " bytes]"; }
    }

    /** TAG_String */
    public static class StringTag extends NbtTag {
        private String value;
        public StringTag(String value) { this.value = value != null ? value : ""; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value != null ? value : ""; }
        @Override public int getType() { return TYPE_STRING; }
        @Override public String getTypeName() { return "String"; }
        @Override public String getValueString() { return "\"" + value + "\""; }
    }

    /** TAG_Int_Array */
    public static class IntArrayTag extends NbtTag {
        private int[] value;
        public IntArrayTag(int[] value) { this.value = value != null ? value : new int[0]; }
        public int[] getValue() { return value; }
        public void setValue(int[] value) { this.value = value != null ? value : new int[0]; }
        @Override public int getType() { return TYPE_INT_ARRAY; }
        @Override public String getTypeName() { return "IntArray"; }
        @Override public String getValueString() { return "[" + value.length + " ints]"; }
    }

    /** TAG_Long_Array */
    public static class LongArrayTag extends NbtTag {
        private long[] value;
        public LongArrayTag(long[] value) { this.value = value != null ? value : new long[0]; }
        public long[] getValue() { return value; }
        public void setValue(long[] value) { this.value = value != null ? value : new long[0]; }
        @Override public int getType() { return TYPE_LONG_ARRAY; }
        @Override public String getTypeName() { return "LongArray"; }
        @Override public String getValueString() { return "[" + value.length + " longs]"; }
    }

    /**
     * TAG_List — 同类型标签的有序集合。
     * 元素类型由 listType 指定，元素无名称。
     */
    public static class ListTag extends NbtTag {
        private final List<NbtTag> items = new ArrayList<>();
        private int listType = TYPE_END;

        public List<NbtTag> getItems() { return items; }

        public int getListType() { return listType; }
        public void setListType(int listType) { this.listType = listType; }

        public void add(NbtTag tag) {
            items.add(tag);
            if (listType == TYPE_END) listType = tag.getType();
        }

        public void remove(int index) {
            if (index >= 0 && index < items.size()) items.remove(index);
        }

        public int size() { return items.size(); }

        @Override public int getType() { return TYPE_LIST; }
        @Override public String getTypeName() { return "List"; }
        @Override public String getValueString() { return "[" + items.size() + " items]"; }
    }

    /**
     * TAG_Compound — 命名标签的无序键值对集合（类似 Map）。
     * 保持插入顺序（LinkedHashMap）。
     */
    public static class CompoundTag extends NbtTag {
        private final Map<String, NbtTag> children = new LinkedHashMap<>();

        public Map<String, NbtTag> getChildren() { return children; }

        public NbtTag get(String key) { return children.get(key); }

        public void put(String key, NbtTag tag) {
            tag.setName(key);
            children.put(key, tag);
        }

        public void remove(String key) { children.remove(key); }

        public boolean contains(String key) { return children.containsKey(key); }

        public int size() { return children.size(); }

        @Override public int getType() { return TYPE_COMPOUND; }
        @Override public String getTypeName() { return "Compound"; }
        @Override public String getValueString() { return "{" + children.size() + " entries}"; }
    }
}
