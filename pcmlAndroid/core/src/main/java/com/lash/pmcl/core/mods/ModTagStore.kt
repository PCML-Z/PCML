package com.lash.pmcl.core.mods

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.TreeSet

/**
 * 模组标签持久化：将用户自定义标签（如「性能」「科技」「魔法」）保存到指定文件。
 *
 * 格式：`{ "jarFileName": ["标签1", "标签2"], ... }`
 *
 * 线程安全：所有方法 synchronized。
 */
class ModTagStore(private val dataFile: Path) {

    private val gson = Gson()
    private val tagMap: MutableMap<String, List<String>> = LinkedHashMap()

    init {
        load()
    }

    /** 获取指定 jar 文件的标签列表（返回副本） */
    @Synchronized
    fun getTags(jarFile: String?): List<String> {
        if (jarFile.isNullOrEmpty()) return Collections.emptyList()
        val tags = tagMap[jarFile]
        return if (tags != null) ArrayList(tags) else Collections.emptyList()
    }

    /** 设置指定 jar 文件的标签列表（覆盖） */
    @Synchronized
    fun setTags(jarFile: String?, tags: List<String>?) {
        if (jarFile.isNullOrEmpty()) return
        if (tags.isNullOrEmpty()) {
            tagMap.remove(jarFile)
        } else {
            val clean = ArrayList<String>()
            for (t in tags) {
                val trimmed = t.trim()
                if (trimmed.isNotEmpty() && !clean.contains(trimmed)) clean.add(trimmed)
            }
            tagMap[jarFile] = clean
        }
        save()
    }

    /** 获取所有已使用的标签（去重排序） */
    @Synchronized
    fun getAllTags(): List<String> {
        val all = TreeSet<String>()
        for (tags in tagMap.values) {
            all.addAll(tags)
        }
        return ArrayList(all)
    }

    /** 将标签应用到已扫描的模组列表（原地修改 ModMeta） */
    @Synchronized
    fun applyTags(mods: List<ModMeta>?) {
        if (mods == null) return
        for (mod in mods) {
            mod.tags = getTags(mod.jarFile)
        }
    }

    // ===== 持久化 =====

    private fun load() {
        try {
            if (!Files.exists(dataFile)) return
            val content = String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8)
            val root = JsonParser.parseString(content).asJsonObject
            tagMap.clear()
            for ((jar, value) in root.entrySet()) {
                val tags = ArrayList<String>()
                if (value.isJsonArray) {
                    for (elem in value.asJsonArray) {
                        if (elem.isJsonPrimitive) tags.add(elem.asString)
                    }
                }
                if (tags.isNotEmpty()) tagMap[jar] = tags
            }
        } catch (_: Throwable) {
            // 加载失败不阻断启动
        }
    }

    @Synchronized
    private fun save() {
        try {
            dataFile.parent?.let { Files.createDirectories(it) }
            val root = JsonObject()
            for ((key, value) in tagMap) {
                val arr = JsonArray()
                for (t in value) arr.add(t)
                root.add(key, arr)
            }
            val tmp = dataFile.resolveSibling(dataFile.fileName.toString() + ".tmp")
            Files.write(tmp, gson.toJson(root).toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(tmp, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            System.err.println("[ModTagStore] 保存失败: ${e.message}")
        }
    }
}
