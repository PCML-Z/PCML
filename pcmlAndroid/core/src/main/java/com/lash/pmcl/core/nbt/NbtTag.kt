package com.lash.pmcl.core.nbt

/**
 * NBT (Named Binary Tag) 标签抽象基类（密封类）。
 *
 * NBT 是 Minecraft 使用的树形二进制数据格式，用于 level.dat、playerdata、servers.dat 等。
 * 类型常量符合 Notchian NBT 规范。
 *
 * Android 版本：从 Java 移植，使用 sealed class + when 替代 instanceof 链。
 */
sealed class NbtTag {

    /** 标签名 */
    var name: String = ""

    /** 返回此标签的 NBT 类型 ID */
    abstract val type: Int

    /** 类型名称（用于 UI 展示） */
    abstract val typeName: String

    /** 转为可读的字符串值（用于 UI 展示叶节点值） */
    abstract fun getValueString(): String

    /** SNBT 字符串表示（用于导出/复制）。默认返回 getValueString()，子类按需覆写。 */
    open fun toSnbt(): String = getValueString()

    /** 深拷贝（用于撤销栈 / 剪贴板） */
    abstract fun copy(): NbtTag

    companion object {
        const val TYPE_END = 0
        const val TYPE_BYTE = 1
        const val TYPE_SHORT = 2
        const val TYPE_INT = 3
        const val TYPE_LONG = 4
        const val TYPE_FLOAT = 5
        const val TYPE_DOUBLE = 6
        const val TYPE_BYTE_ARRAY = 7
        const val TYPE_STRING = 8
        const val TYPE_LIST = 9
        const val TYPE_COMPOUND = 10
        const val TYPE_INT_ARRAY = 11
        const val TYPE_LONG_ARRAY = 12

        /** 所有可创建的标签类型 ID 列表（用于 UI 类型选择器） */
        val CREATABLE_TYPES: IntArray = intArrayOf(
            TYPE_BYTE, TYPE_SHORT, TYPE_INT, TYPE_LONG,
            TYPE_FLOAT, TYPE_DOUBLE, TYPE_STRING,
            TYPE_BYTE_ARRAY, TYPE_INT_ARRAY, TYPE_LONG_ARRAY,
            TYPE_LIST, TYPE_COMPOUND
        )

        /** 根据类型 ID 获取类型名称（用于 UI 类型选择器） */
        fun getTypeName(type: Int): String = when (type) {
            TYPE_BYTE -> "Byte"
            TYPE_SHORT -> "Short"
            TYPE_INT -> "Int"
            TYPE_LONG -> "Long"
            TYPE_FLOAT -> "Float"
            TYPE_DOUBLE -> "Double"
            TYPE_BYTE_ARRAY -> "ByteArray"
            TYPE_STRING -> "String"
            TYPE_LIST -> "List"
            TYPE_COMPOUND -> "Compound"
            TYPE_INT_ARRAY -> "IntArray"
            TYPE_LONG_ARRAY -> "LongArray"
            else -> "Unknown"
        }

        /** 创建指定类型的默认标签实例 */
        fun createDefault(type: Int): NbtTag = when (type) {
            TYPE_BYTE -> ByteTag(0)
            TYPE_SHORT -> ShortTag(0)
            TYPE_INT -> IntTag(0)
            TYPE_LONG -> LongTag(0L)
            TYPE_FLOAT -> FloatTag(0f)
            TYPE_DOUBLE -> DoubleTag(0.0)
            TYPE_BYTE_ARRAY -> ByteArrayTag(ByteArray(0))
            TYPE_STRING -> StringTag("")
            TYPE_LIST -> ListTag()
            TYPE_COMPOUND -> CompoundTag()
            TYPE_INT_ARRAY -> IntArrayTag(IntArray(0))
            TYPE_LONG_ARRAY -> LongArrayTag(LongArray(0))
            else -> throw IllegalArgumentException("Unknown NBT type: $type")
        }

        /**
         * 将标签转换为目标类型（尽量保留数值语义）。
         * 不支持的转换返回 null。
         */
        fun convert(src: NbtTag?, targetType: Int): NbtTag? {
            if (src == null) return null
            if (src.type == targetType) return src.copy()
            return try {
                when (targetType) {
                    TYPE_BYTE -> {
                        val n = asNumber(src) ?: return null
                        ByteTag(n.toByte()).apply { name = src.name }
                    }
                    TYPE_SHORT -> {
                        val n = asNumber(src) ?: return null
                        ShortTag(n.toShort()).apply { name = src.name }
                    }
                    TYPE_INT -> {
                        val n = asNumber(src) ?: return null
                        IntTag(n.toInt()).apply { name = src.name }
                    }
                    TYPE_LONG -> {
                        val n = asNumber(src) ?: return null
                        LongTag(n.toLong()).apply { name = src.name }
                    }
                    TYPE_FLOAT -> {
                        val n = asNumber(src) ?: return null
                        FloatTag(n.toFloat()).apply { name = src.name }
                    }
                    TYPE_DOUBLE -> {
                        val n = asNumber(src) ?: return null
                        DoubleTag(n.toDouble()).apply { name = src.name }
                    }
                    TYPE_STRING -> {
                        val s = when (src) {
                            is StringTag -> src.value
                            is ByteTag, is ShortTag, is IntTag,
                            is LongTag, is FloatTag, is DoubleTag ->
                                stripNumericSuffix(src.getValueString())
                            else -> return null
                        }
                        StringTag(s).apply { name = src.name }
                    }
                    TYPE_BYTE_ARRAY -> {
                        if (src is IntArrayTag) {
                            val b = ByteArray(src.value.size) { i -> src.value[i].toByte() }
                            ByteArrayTag(b).apply { name = src.name }
                        } else null
                    }
                    TYPE_INT_ARRAY -> {
                        when (src) {
                            is ByteArrayTag -> {
                                val b = IntArray(src.value.size) { i -> src.value[i].toInt() }
                                IntArrayTag(b).apply { name = src.name }
                            }
                            is LongArrayTag -> {
                                val b = IntArray(src.value.size) { i -> src.value[i].toInt() }
                                IntArrayTag(b).apply { name = src.name }
                            }
                            else -> null
                        }
                    }
                    TYPE_LONG_ARRAY -> {
                        if (src is IntArrayTag) {
                            val b = LongArray(src.value.size) { i -> src.value[i].toLong() }
                            LongArrayTag(b).apply { name = src.name }
                        } else null
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        /** 尝试将标签解析为 Number（数值类型直接返回，字符串尝试解析）。 */
        private fun asNumber(src: NbtTag): Number? = when (src) {
            is ByteTag -> src.value
            is ShortTag -> src.value
            is IntTag -> src.value
            is LongTag -> src.value
            is FloatTag -> src.value
            is DoubleTag -> src.value
            is StringTag -> {
                val s = src.value.trim()
                if (s.isEmpty()) null
                else try {
                    if ('.' in s || s.endsWith("f", true) || s.endsWith("d", true)) {
                        s.replace(Regex("[fFdD]$"), "").toDouble()
                    } else {
                        s.replace(Regex("[bBsSlL]$"), "").toLong()
                    }
                } catch (e: NumberFormatException) {
                    null
                }
            }
            is ByteArrayTag, is ListTag, is CompoundTag,
            is IntArrayTag, is LongArrayTag -> null
        }

        /** 去除数值字符串的 SNBT 类型后缀（b/B/s/S/L/f/F/d/D）。 */
        private fun stripNumericSuffix(s: String): String {
            if (s.isEmpty()) return ""
            val c = s.last()
            return if (c in "bBsSlLfFdD") s.dropLast(1) else s
        }
    }

    // ===== 子类 =====

    /** TAG_Byte */
    class ByteTag(var value: Byte) : NbtTag() {
        override val type = TYPE_BYTE
        override val typeName = "Byte"
        override fun getValueString() = value.toString()
        override fun toSnbt() = "${value}b"
        override fun copy(): NbtTag = ByteTag(value).also { it.name = name }
    }

    /** TAG_Short */
    class ShortTag(var value: Short) : NbtTag() {
        override val type = TYPE_SHORT
        override val typeName = "Short"
        override fun getValueString() = value.toString()
        override fun toSnbt() = "${value}s"
        override fun copy(): NbtTag = ShortTag(value).also { it.name = name }
    }

    /** TAG_Int */
    class IntTag(var value: Int) : NbtTag() {
        override val type = TYPE_INT
        override val typeName = "Int"
        override fun getValueString() = value.toString()
        override fun copy(): NbtTag = IntTag(value).also { it.name = name }
    }

    /** TAG_Long */
    class LongTag(var value: Long) : NbtTag() {
        override val type = TYPE_LONG
        override val typeName = "Long"
        override fun getValueString() = "${value}L"
        override fun copy(): NbtTag = LongTag(value).also { it.name = name }
    }

    /** TAG_Float */
    class FloatTag(var value: Float) : NbtTag() {
        override val type = TYPE_FLOAT
        override val typeName = "Float"
        override fun getValueString() = "${value}F"
        override fun toSnbt() = "${value}f"
        override fun copy(): NbtTag = FloatTag(value).also { it.name = name }
    }

    /** TAG_Double */
    class DoubleTag(var value: Double) : NbtTag() {
        override val type = TYPE_DOUBLE
        override val typeName = "Double"
        override fun getValueString() = "${value}D"
        override fun toSnbt() = "${value}d"
        override fun copy(): NbtTag = DoubleTag(value).also { it.name = name }
    }

    /** TAG_Byte_Array */
    class ByteArrayTag(value: ByteArray) : NbtTag() {
        var value: ByteArray = value

        override val type = TYPE_BYTE_ARRAY
        override val typeName = "ByteArray"
        override fun getValueString() = "[${value.size} bytes]"
        override fun toSnbt() = "[B;" + value.joinToString(",") + "]"
        override fun copy(): NbtTag = ByteArrayTag(value.copyOf()).also { it.name = name }
    }

    /** TAG_String */
    class StringTag(value: String) : NbtTag() {
        var value: String = value

        override val type = TYPE_STRING
        override val typeName = "String"
        override fun getValueString() = "\"$value\""
        override fun copy(): NbtTag = StringTag(value).also { it.name = name }
    }

    /** TAG_Int_Array */
    class IntArrayTag(value: IntArray) : NbtTag() {
        var value: IntArray = value

        override val type = TYPE_INT_ARRAY
        override val typeName = "IntArray"
        override fun getValueString() = "[${value.size} ints]"
        override fun toSnbt() = "[I;" + value.joinToString(",") + "]"
        override fun copy(): NbtTag = IntArrayTag(value.copyOf()).also { it.name = name }
    }

    /** TAG_Long_Array */
    class LongArrayTag(value: LongArray) : NbtTag() {
        var value: LongArray = value

        override val type = TYPE_LONG_ARRAY
        override val typeName = "LongArray"
        override fun getValueString() = "[${value.size} longs]"
        override fun toSnbt() = "[L;" + value.joinToString(",") { "${it}L" } + "]"
        override fun copy(): NbtTag = LongArrayTag(value.copyOf()).also { it.name = name }
    }

    /**
     * TAG_List — 同类型标签的有序集合。
     * 元素类型由 listType 指定，元素无名称。
     */
    class ListTag : NbtTag() {
        val items: MutableList<NbtTag> = ArrayList()
        /** 元素类型 ID */
        var listType: Int = TYPE_END

        fun add(tag: NbtTag) {
            items.add(tag)
            if (listType == TYPE_END) listType = tag.type
        }

        /** 在指定位置插入元素 */
        fun add(index: Int, tag: NbtTag) {
            items.add(index, tag)
            if (listType == TYPE_END) listType = tag.type
        }

        fun remove(index: Int) {
            if (index >= 0 && index < items.size) items.removeAt(index)
        }

        val size: Int
            get() = items.size

        override val type = TYPE_LIST
        override val typeName = "List"
        override fun getValueString() = "[${items.size} items]"
        override fun toSnbt() = "[" + items.joinToString(",") { it.toSnbt() } + "]"
        override fun copy(): NbtTag {
            val t = ListTag()
            t.name = name
            t.listType = listType
            for (item in items) t.add(item.copy())
            return t
        }
    }

    /**
     * TAG_Compound — 命名标签的无序键值对集合（类似 Map）。
     * 保持插入顺序（LinkedHashMap）。
     */
    class CompoundTag : NbtTag() {
        val children: MutableMap<String, NbtTag> = LinkedHashMap()

        fun get(key: String): NbtTag? = children[key]

        fun put(key: String, tag: NbtTag) {
            tag.name = key
            children[key] = tag
        }

        fun remove(key: String) { children.remove(key) }

        fun contains(key: String): Boolean = children.containsKey(key)

        val size: Int
            get() = children.size

        override val type = TYPE_COMPOUND
        override val typeName = "Compound"
        override fun getValueString() = "{${children.size} entries}"
        override fun toSnbt() =
            "{" + children.entries.joinToString(",") { "${it.key}:${it.value.toSnbt()}" } + "}"
        override fun copy(): NbtTag {
            val t = CompoundTag()
            t.name = name
            for ((key, value) in children) t.put(key, value.copy())
            return t
        }
    }
}
