package com.lash.pmcl.core.gamecontent

import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * 统一的 options.txt 读写工具。
 *
 * Minecraft 的 options.txt 行格式为 `key:value`，但有两种特殊情况：
 * - 普通字段：`lang:zh_cn`、`shaderPack:` 等，value 可为任意字符串
 * - JSON 数组字段：`resourcePacks:["vanilla","file/MyPack.zip"]`，
 *   value 是 JSON 数组字符串，含逗号、引号、方括号
 *
 * 所有方法对 IO 异常静默忽略（不阻塞启动），与现有启动期写入保持一致。
 *
 * Android 版本：从 Java 移植。Files.readString/writeString（Java 11）在 Android API 26
 * 不可用，改用 [FileUtils]；readAllLines（Java 8 NIO.2）在 API 26+ 可用。
 */
object OptionsTxtWriter {

    /**
     * 写入/更新普通 key:value 字段，保留其它行。
     * 若文件不存在则新建；若键已存在则更新，否则追加。
     * 若现有值与目标值相同则不重写，避免改动 mtime 触发 MC 重新加载。
     */
    fun writeOption(optionsFile: Path?, key: String?, value: String?) {
        if (optionsFile == null || key.isNullOrEmpty()) return
        val line = "$key:${value ?: ""}"
        try {
            if (!Files.exists(optionsFile)) {
                optionsFile.parent?.let { Files.createDirectories(it) }
                FileUtils.writeString(optionsFile, "$line\n")
                return
            }
            val lines = ArrayList(Files.readAllLines(optionsFile, StandardCharsets.UTF_8))
            var found = false
            for (i in lines.indices) {
                if (lines[i].startsWith("$key:")) {
                    if (lines[i] == line) return  // 已是目标值
                    lines[i] = line
                    found = true
                    break
                }
            }
            if (!found) lines.add(line)
            FileUtils.writeString(optionsFile, lines.joinToString("\n") + "\n")
        } catch (e: IOException) {
            // 写入失败不应阻塞启动，但必须可观测
            System.err.println("[OptionsTxtWriter] 写入失败 $optionsFile key=$key: ${e.message}")
        }
    }

    /**
     * 修复旧版 MC（alpha 等）会崩溃的空值行：`lastServer:` 经
     * `String.split(":")` 后长度为 1，触发 ArrayIndexOutOfBoundsException。
     * 将「仅有 key:」的行改为 `key: `（尾部空格），保证 split 得到 value。
     */
    fun sanitizeEmptyValues(optionsFile: Path?) {
        if (optionsFile == null || !Files.isRegularFile(optionsFile)) return
        try {
            val lines = ArrayList(Files.readAllLines(optionsFile, StandardCharsets.UTF_8))
            var changed = false
            for (i in lines.indices) {
                val line = lines[i] ?: continue
                val trimmed = line.trim()
                val colon = trimmed.indexOf(':')
                if (colon > 0 && colon == trimmed.length - 1) {
                    lines[i] = "$trimmed "
                    changed = true
                }
            }
            if (changed) {
                FileUtils.writeString(optionsFile, lines.joinToString("\n") + "\n")
            }
        } catch (e: IOException) {
            System.err.println("[OptionsTxtWriter] sanitizeEmptyValues 失败: ${e.message}")
        }
    }

    /** 读取普通字段值，不存在返回 null。 */
    fun readOption(optionsFile: Path?, key: String?): String? {
        if (optionsFile == null || !Files.exists(optionsFile) || key == null) return null
        try {
            val lines = Files.readAllLines(optionsFile, StandardCharsets.UTF_8)
            val prefix = "$key:"
            for (line in lines) {
                if (line.startsWith(prefix)) {
                    return line.substring(prefix.length)
                }
            }
        } catch (e: IOException) {
            // 静默忽略
        }
        return null
    }

    // ===== resourcePacks JSON 数组字段专用 =====
    // options.txt 中格式：resourcePacks:["vanilla","file/Foo.zip"]
    // 必须保持 "vanilla" 在首位（MC 默认资源包），自定义包用 "file/<文件名>" 引用

    private val PACK_ENTRY: Pattern = Pattern.compile("\"([^\"]*)\"")

    /**
     * 读取 resourcePacks 列表。返回 ["vanilla","file/Foo.zip"] 等。
     * 字段不存在或解析失败返回仅含 "vanilla" 的列表（MC 默认行为）。
     */
    fun getResourcePacks(optionsFile: Path?): List<String> {
        val result = ArrayList<String>()
        result.add("vanilla")
        if (optionsFile == null || !Files.exists(optionsFile)) return result
        val value = readOption(optionsFile, "resourcePacks")
        if (value.isNullOrEmpty()) return result
        // 去除首尾方括号
        var inner = value.trim()
        if (inner.startsWith("[")) inner = inner.substring(1)
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length - 1)
        val m = PACK_ENTRY.matcher(inner)
        var foundAny = false
        while (m.find()) {
            val entry = m.group(1)
            if (!result.contains(entry)) {
                result.add(entry)
                foundAny = true
            }
        }
        if (!foundAny) {
            // 数组为空或解析失败，保持 vanilla
            return result
        }
        // 确保 vanilla 在首位
        if (result[0] != "vanilla") {
            result.remove("vanilla")
            result.add(0, "vanilla")
        }
        return result
    }

    /**
     * 启用一个资源包：若未在列表中则追加到末尾，并写入 options.txt。
     * packRef 格式为 "file/MyPack.zip"（含 file/ 前缀）。
     * 已存在则不重复添加。
     */
    fun enableResourcePack(optionsFile: Path?, packRef: String?) {
        if (packRef.isNullOrEmpty()) return
        val packs = getResourcePacks(optionsFile).toMutableList()
        if (packs.contains(packRef)) return
        packs.add(packRef)
        writeResourcePacks(optionsFile, packs)
    }

    /**
     * 禁用一个资源包：从列表中移除并写入 options.txt。
     */
    fun disableResourcePack(optionsFile: Path?, packRef: String?) {
        if (packRef.isNullOrEmpty()) return
        val packs = getResourcePacks(optionsFile).toMutableList()
        if (!packs.remove(packRef)) return
        writeResourcePacks(optionsFile, packs)
    }

    /**
     * 写入完整的 resourcePacks 列表。
     * 自动保持 "vanilla" 在首位，去重。
     */
    fun writeResourcePacks(optionsFile: Path?, packs: List<String>?) {
        if (optionsFile == null || packs == null) return
        val dedup = ArrayList<String>()
        for (p in packs) {
            if (p.isNotEmpty() && !dedup.contains(p)) dedup.add(p)
        }
        // vanilla 必须在首位
        dedup.remove("vanilla")
        dedup.add(0, "vanilla")
        val sb = StringBuilder("[")
        for (i in dedup.indices) {
            if (i > 0) sb.append(",")
            sb.append("\"").append(dedup[i]).append("\"")
        }
        sb.append("]")
        writeOption(optionsFile, "resourcePacks", sb.toString())
    }
}
