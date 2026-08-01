package com.lash.pmcl.core.mods

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.util.SafeZipExtractor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * Mod 元数据解析器：扫描 mods 目录下所有 jar（含 .disabled 禁用文件），
 * 优先按以下顺序解析：
 *   1) fabric.mod.json     → Fabric mod
 *   2) quilt.mod.json      → Quilt mod
 *   3) META-INF/mods.toml  → Forge mod（1.13+）
 *   4) META-INF/neoforge.mods.toml → NeoForge mod
 *   5) META-INF/MANIFEST.MF → 通用兜底
 *
 * Forge/NeoForge 的 [[dependencies.<modId>]] 段做完整段解析，
 * 区分 mandatory（→ depends）与 optional/incompatible（→ conflicts 仅记录 incompatible）。
 */
object ModScanner {

    /** 模组元数据 entry 上限，防压缩炸弹式 OOM */
    private const val MAX_META_BYTES = 2L * 1024 * 1024

    @Throws(IOException::class)
    private fun readEntryLimited(jar: JarFile, entry: JarEntry): String {
        val declared = entry.size
        if (declared > MAX_META_BYTES) {
            throw IOException("Mod metadata entry too large: ${entry.name} ($declared bytes)")
        }
        jar.getInputStream(entry).use { inp ->
            return String(SafeZipExtractor.readLimited(inp, MAX_META_BYTES), StandardCharsets.UTF_8)
        }
    }

    /**
     * 扫描某目录下所有 .jar 文件，返回解析后的 mod 元数据列表。
     * 同时识别 .disabled 后缀的禁用 mod（disabled=true）。
     */
    @Throws(IOException::class)
    fun scanDirectory(modsDir: Path): List<ModMeta> {
        val result = ArrayList<ModMeta>()
        if (!Files.isDirectory(modsDir)) return result
        Files.list(modsDir).use { stream ->
            stream.forEach { p ->
                try {
                    val name = p.fileName.toString().lowercase()
                    if (name.endsWith(".jar") || name.endsWith(".jar.disabled")) {
                        val meta = parseJar(p)
                        meta.jarPath = p.toAbsolutePath().toString()
                        result.add(meta)
                    }
                } catch (t: Throwable) {
                    System.err.println("[ModScanner] 解析 jar 失败: $p - ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        return result
    }

    /**
     * 解析单个 mod jar（路径名以 .disabled 结尾时识别为禁用）。
     */
    fun parseJar(jarPath: Path): ModMeta {
        val fileName = jarPath.fileName.toString()
        try {
            JarFile(jarPath.toFile()).use { jar ->
                // 1) fabric.mod.json
                jar.getJarEntry("fabric.mod.json")?.let { return parseFabric(jar, it, fileName) }
                // 2) quilt.mod.json
                jar.getJarEntry("quilt.mod.json")?.let { return parseQuilt(jar, it, fileName) }
                // 3) NeoForge neoforge.mods.toml（优先于 mods.toml，NeoForge 1.20.2+）
                jar.getJarEntry("META-INF/neoforge.mods.toml")?.let { return parseForge(jar, it, fileName, "neoforge") }
                // 4) Forge mods.toml
                jar.getJarEntry("META-INF/mods.toml")?.let { return parseForge(jar, it, fileName, "forge") }
                // 5) MANIFEST.MF 兜底
                jar.getJarEntry("META-INF/MANIFEST.MF")?.let { return parseManifest(jar, it, fileName) }
                // 无法识别
                return ModMeta(fileName, "unknown", fileName, "", "", "unknown",
                    emptyList(), emptyList(), fileName)
            }
        } catch (e: Throwable) {
            return ModMeta(fileName, "unknown", fileName, "", "", "unknown",
                emptyList(), emptyList(), fileName)
        }
    }

    @Throws(IOException::class)
    private fun parseFabric(jar: JarFile, entry: JarEntry, fileName: String): ModMeta {
        val o = JsonParser.parseString(readEntryLimited(jar, entry)).asJsonObject
        val id = safeStr(o, "id", fileName)
        val version = safeStr(o, "version", "unknown")
        val name = safeStr(o, "name", id)
        val desc = safeStr(o, "description", "")
        val authors = extractAuthors(o)
        val deps = jsonArrToStrings(o, "depends")
        val conflicts = jsonArrToStrings(o, "conflicts")
        val meta = ModMeta(id, version, name, desc, authors, "fabric",
            deps, conflicts, fileName)
        meta.iconEntry = extractFabricIcon(o)
        return meta
    }

    /** fabric.mod.json 的 icon 可为字符串或 { "64": "path", ... } */
    private fun extractFabricIcon(o: JsonObject?): String {
        try {
            if (o == null || !o.has("icon") || o.get("icon").isJsonNull) return ""
            val el = o.get("icon")
            if (el.isJsonPrimitive) return el.asString
            if (el.isJsonObject) {
                val icons = el.asJsonObject
                // 优先较大尺寸
                for (key in arrayOf("256", "128", "64", "32")) {
                    if (icons.has(key) && icons.get(key).isJsonPrimitive) {
                        return icons.get(key).asString
                    }
                }
                for (e in icons.entrySet()) {
                    if (e.value.isJsonPrimitive) return e.value.asString
                }
            }
        } catch (_: Throwable) {}
        return ""
    }

    /** 安全地从 JsonObject 取字符串字段，字段缺失或类型不符时返回默认值（不抛异常）。 */
    private fun safeStr(o: JsonObject?, key: String, def: String): String {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull) return def
            val el = o.get(key)
            if (el.isJsonPrimitive) return el.asString
            return el.toString()
        } catch (_: Throwable) {
            return def
        }
    }

    /**
     * 解析 quilt.mod.json（兼容 Quilt 加载器，Quilt 兼容 Fabric API）。
     * 结构：{ "schema_version": 1, "quilt_loader": { "id", "version", "name", ... , "depends": [...] } }
     */
    @Throws(IOException::class)
    private fun parseQuilt(jar: JarFile, entry: JarEntry, fileName: String): ModMeta {
        val o = JsonParser.parseString(readEntryLimited(jar, entry)).asJsonObject
        val ql = if (o.has("quilt_loader")) o.getAsJsonObject("quilt_loader") else o
        val id = safeStr(ql, "id", fileName)
        val version = safeStr(ql, "version", "unknown")
        val name = safeStr(ql, "name", id)
        val desc = safeStr(ql, "description", "")
        val authors = extractAuthors(ql)
        var icon = extractFabricIcon(ql)
        if (icon.isEmpty()) icon = extractFabricIcon(o)
        val deps = ArrayList<String>()
        val conflicts = ArrayList<String>()
        // depends 可以是数组 [{id, optional}] 或对象 {id: {...}}
        if (ql.has("depends")) {
            val d = ql.get("depends")
            if (d.isJsonArray) {
                for (e in d.asJsonArray) {
                    if (e.isJsonObject && e.asJsonObject.has("id")) {
                        deps.add(e.asJsonObject.get("id").asString)
                    } else if (e.isJsonPrimitive) {
                        deps.add(e.asString)
                    }
                }
            } else if (d.isJsonObject) {
                deps.addAll(d.asJsonObject.keySet())
            }
        }
        if (ql.has("breaks")) {
            val b = ql.get("breaks")
            if (b.isJsonArray) {
                for (e in b.asJsonArray) {
                    if (e.isJsonObject && e.asJsonObject.has("id")) {
                        conflicts.add(e.asJsonObject.get("id").asString)
                    } else if (e.isJsonPrimitive) {
                        conflicts.add(e.asString)
                    }
                }
            } else if (b.isJsonObject) {
                conflicts.addAll(b.asJsonObject.keySet())
            }
        }
        val meta = ModMeta(id, version, name, desc, authors, "quilt",
            deps, conflicts, fileName)
        meta.iconEntry = icon
        return meta
    }

    /**
     * 解析 mods.toml / neoforge.mods.toml。
     * 完整段解析 [[mods]] 与 [[dependencies.<modId>]]，区分 mandatory / optional / incompatible。
     */
    @Throws(IOException::class)
    private fun parseForge(jar: JarFile, entry: JarEntry, fileName: String, loader: String): ModMeta {
        val content = readEntryLimited(jar, entry)
        // 预先按行拆分一次，避免 tomlValueInSection 每次都重新 split
        val lines = content.split("\n").toTypedArray()
        // === 提取 [[mods]] 段内的字段 ===
        var modId = tomlValueInSection(lines, "modId", "mods")
        var version = tomlValueInSection(lines, "version", "mods")
        var name = tomlValueInSection(lines, "displayName", "mods")
        if (name == null) name = tomlValueInSection(lines, "name", "mods")
        var desc = tomlValueInSection(lines, "description", "mods")
        var authors = tomlValueInSection(lines, "authors", "mods")

        // === 解析所有 [[dependencies.<modId>]] 段 ===
        var deps = ArrayList<String>()
        var conflicts = ArrayList<String>()
        for (dep in parseTomlDepBlocks(content)) {
            if (dep.incompatible) {
                conflicts.add(dep.modId!!)
            } else if (dep.mandatory) {
                deps.add(dep.modId!!)
            }
            // optional 不加入（不会阻塞启动）
        }
        // 去重（同一 modId 可能在多个段中）
        deps = ArrayList(dedup(deps))
        conflicts = ArrayList(dedup(conflicts))

        val meta = ModMeta(
            modId ?: fileName,
            version ?: "unknown",
            name ?: (modId ?: fileName),
            desc ?: "",
            authors ?: "",
            loader, deps, conflicts, fileName
        )
        var logo = tomlValueInSection(lines, "logoFile", "mods")
        if (logo == null || logo.isEmpty()) logo = tomlValueInSection(lines, "logoFile", "")
        if (logo != null && logo.isNotEmpty()) meta.iconEntry = logo
        return meta
    }

    @Throws(IOException::class)
    private fun parseManifest(jar: JarFile, entry: JarEntry, fileName: String): ModMeta {
        val content = readEntryLimited(jar, entry)
        val name = manifestAttr(content, "Implementation-Title")
        val version = manifestAttr(content, "Implementation-Version")
        return ModMeta(
            name ?: fileName,
            version ?: "unknown",
            name ?: fileName,
            "通过 MANIFEST.MF 识别", "", "unknown",
            emptyList(), emptyList(), fileName
        )
    }

    // ==================== TOML 解析辅助 ====================

    /** TOML 依赖段块：记录 modId / mandatory / incompatible */
    private class TomlDepBlock {
        var modId: String? = null
        var mandatory: Boolean = true
        var incompatible: Boolean = false
    }

    /**
     * 解析所有 [[dependencies.xxx]] 段。
     * 段内字段：modId="...", type="required|optional|incompatible", mandatory=true|false
     * NeoForge 用 type 字段，Forge 用 mandatory 字段。
     */
    private fun parseTomlDepBlocks(content: String): List<TomlDepBlock> {
        val blocks = ArrayList<TomlDepBlock>()
        val lines = content.split("\n")
        var inDepSection = false
        var current: TomlDepBlock? = null
        for (raw in lines) {
            val line = raw.trim()
            // 进入新的 [[dependencies.xxx]] 段
            if (line.startsWith("[[dependencies.")) {
                // 提交上一个段
                if (current != null && current.modId != null) blocks.add(current)
                current = TomlDepBlock()
                inDepSection = true
                continue
            }
            // 任何非 [[dependencies 段都结束当前段
            if (line.startsWith("[[") && inDepSection) {
                if (current != null && current.modId != null) blocks.add(current)
                current = null
                inDepSection = false
            }
            if (current == null) continue
            // 在依赖段内解析字段
            when {
                line.startsWith("modId=") || line.startsWith("modId =") -> {
                    current.modId = stripQuotes(afterEq(line))
                }
                line.startsWith("mandatory=") || line.startsWith("mandatory =") -> {
                    current.mandatory = afterEq(line).toBoolean()
                }
                line.startsWith("type=") || line.startsWith("type =") -> {
                    val type = stripQuotes(afterEq(line))
                    if (type.equals("incompatible", ignoreCase = true)) {
                        current.incompatible = true
                        current.mandatory = false
                    } else if (type.equals("optional", ignoreCase = true)) {
                        current.mandatory = false
                    }
                }
                // side=BOTH/CLIENT/SERVER，不影响依赖
            }
        }
        // 提交最后一个段
        if (current != null && current.modId != null) blocks.add(current)
        return blocks
    }

    /** 在指定 [[sectionName]] 段内提取 key 的值（接收预先拆分好的行数组，避免重复 split） */
    private fun tomlValueInSection(lines: Array<String>, key: String, sectionName: String): String? {
        val sectionHeader = "[[$sectionName]]"
        var inSection = false
        for (raw in lines) {
            val line = raw.trim()
            // 进入 [[sectionName]] 段
            if (line.equals(sectionHeader, ignoreCase = true)) {
                inSection = true
                continue
            }
            // 任何其他段头（[xxx] 或 [[xxx]]）都结束当前段
            if ((line.startsWith("[[") || line.startsWith("[")) && inSection) {
                inSection = false
                continue
            }
            if (!inSection) continue
            if (line.startsWith("$key=") || line.startsWith("$key =")) {
                return stripQuotes(afterEq(line))
            }
        }
        return null
    }

    /** 取等号后的内容 */
    private fun afterEq(line: String): String {
        val eq = line.indexOf('=')
        return if (eq >= 0) line.substring(eq + 1).trim() else ""
    }

    private fun dedup(list: List<String>): List<String> {
        val out = ArrayList<String>()
        for (s in list) {
            if (!out.contains(s)) out.add(s)
        }
        return out
    }

    // ==================== 通用解析辅助 ====================

    private fun extractAuthors(o: JsonObject): String {
        if (!o.has("authors")) return ""
        val a = o.get("authors")
        if (a.isJsonArray) {
            val names = ArrayList<String>()
            for (e in a.asJsonArray) {
                if (e.isJsonPrimitive) names.add(e.asString)
                else if (e.isJsonObject && e.asJsonObject.has("name"))
                    names.add(e.asJsonObject.get("name").asString)
            }
            return names.joinToString(", ")
        }
        return if (a.isJsonPrimitive) a.asString else ""
    }

    private fun jsonArrToStrings(o: JsonObject, key: String): List<String> {
        if (!o.has(key)) return emptyList()
        val e = o.get(key)
        if (e.isJsonObject) {
            // fabric depends 是对象：{"modid": "any"} → 取 key
            return ArrayList(e.asJsonObject.keySet())
        }
        if (e.isJsonArray) {
            val list = ArrayList<String>()
            for (x in e.asJsonArray) list.add(x.asString)
            return list
        }
        return emptyList()
    }

    /** 极简 TOML 单行 value 提取：key="value" 或 key = "value" */
    private fun tomlValue(content: String, key: String): String? {
        for (raw in content.split("\n")) {
            val line = raw.trim()
            if (line.startsWith("$key=") || line.startsWith("$key =")) {
                val eq = line.indexOf('=')
                return stripQuotes(line.substring(eq + 1).trim())
            }
        }
        return null
    }

    private fun stripQuotes(s: String?): String? {
        if (s == null) return null
        var t = s.trim()
        // 去除行内注释（如 "neoforge" #mandatory → "neoforge"）
        val hash = t.indexOf('#')
        if (hash >= 0) t = t.substring(0, hash).trim()
        // 去除引号
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length - 1)
        }
        return t.trim()
    }

    private fun manifestAttr(content: String, key: String): String? {
        for (raw in content.split("\n")) {
            val line = raw.trim()
            if (line.startsWith("$key:")) {
                return line.substring(key.length + 1).trim()
            }
        }
        return null
    }
}
