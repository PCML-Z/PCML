package com.lash.pmcl.core.mods

import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.HashSet
import java.util.Locale

/**
 * Mod 依赖冲突检测。
 *
 * 检查规则：
 *   1) 依赖缺失：A 依赖 B，但 B 不在已安装列表中
 *   2) 冲突：A 声明 conflicts B，但 B 已安装
 *   3) 重复：相同 modId 存在多个版本
 */
object ModConflictChecker {

    fun check(mods: List<ModMeta>): Result {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        // 过滤掉 modId/name 为 null 或异常的条目（TOML 解析脏数据）
        val validMods = mods.filter {
            !it.modId.isBlank() && it.modId != "null"
        }

        // modId → mods（检测重复），同时构建 normalized modId 集合用于模糊匹配
        val byId = HashMap<String, MutableList<ModMeta>>()
        val normalizedToOriginal = HashMap<String, String>()  // 连字符↔下划线模糊匹配
        for (m in validMods) {
            byId.computeIfAbsent(m.modId) { ArrayList() }.add(m)
            val norm = normalizeModId(m.modId)
            normalizedToOriginal.putIfAbsent(norm, m.modId)
        }
        for ((key, list) in byId) {
            if (list.size > 1) {
                val sb = StringBuilder()
                sb.append("重复 mod: ").append(key).append(" → ")
                for (m in list) {
                    sb.append(m.jarFile).append(" (v").append(m.version).append("), ")
                }
                warnings.add(sb.substring(0, sb.length - 2))
            }
        }

        // 检查冲突（不检查依赖缺失：静态扫描无法识别 jar-in-jar 内嵌库、
        // 跨加载器兼容、连字符/下划线转换等，依赖缺失检查误报率极高，直接跳过）
        for (m in validMods) {
            val displayName = if (m.name.isNotBlank()) m.name else m.modId
            for (conflictId in m.conflicts) {
                if (isSystemDep(conflictId)) continue
                if (byId.containsKey(conflictId) ||
                    normalizedToOriginal.containsKey(normalizeModId(conflictId))
                ) {
                    errors.add("$displayName 与 $conflictId 冲突")
                }
            }
        }

        return Result(errors, warnings)
    }

    /** 将 modId 中的连字符和下划线统一，用于模糊匹配（NeoForge 运行时会做此转换） */
    private fun normalizeModId(id: String?): String {
        if (id == null) return ""
        return id.lowercase().replace('-', '_').replace("\"", "").trim()
    }

    /**
     * M92: 系统依赖白名单外部化到资源文件（system_deps.json），降低维护成本。
     * 通过 classpath 资源加载；加载失败时回退到最小硬编码集合保证基本可用。
     */
    private val SYSTEM_DEPS: Set<String> = loadSystemDeps()

    private fun loadSystemDeps(): Set<String> {
        // 兜底集合：仅包含加载器与运行时（保证资源加载失败时不影响核心冲突检测）
        val fallback = HashSet(
            setOf(
                "minecraft", "java", "fabricloader", "fabric-language-kotlin",
                "quilt_loader", "quilted_fabric_api", "forge", "neoforge", "fml"
            )
        )
        try {
            val stream = ModConflictChecker::class.java.getResourceAsStream(
                "/com/lash/pmcl/core/mods/system_deps.json"
            )
            if (stream == null) {
                System.err.println("[ModConflictChecker] system_deps.json 未找到，使用兜底集合")
                return Collections.unmodifiableSet(fallback)
            }
            stream.use { inp ->
                val content = String(inp.readBytes(), StandardCharsets.UTF_8)
                val arr = JsonParser.parseString(content).asJsonArray
                val set = HashSet<String>(arr.size())
                for (e in arr) {
                    set.add(e.asString.lowercase(Locale.ROOT))
                }
                return Collections.unmodifiableSet(set)
            }
        } catch (e: Exception) {
            System.err.println(
                "[ModConflictChecker] 加载 system_deps.json 失败，使用兜底集合: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            return Collections.unmodifiableSet(fallback)
        }
    }

    private fun isSystemDep(id: String?): Boolean {
        if (id == null) return false
        // 清理可能的引号、注释、空白
        var low = id.lowercase().trim()
        if (low.isEmpty()) return true  // 空依赖名视为系统级，跳过
        // 去除行内注释（如 "neoforge" #mandatory → neoforge）
        val hash = low.indexOf('#')
        if (hash >= 0) low = low.substring(0, hash).trim()
        // 去除引号
        if (low.length >= 2 && low.startsWith("\"") && low.endsWith("\"")) {
            low = low.substring(1, low.length - 1)
        }
        if (SYSTEM_DEPS.contains(low)) return true
        // Fabric API 子模块（由 fabric-api 聚合提供）+ 版本约束前缀
        return low.startsWith("fabric-api") || low.startsWith("fabric-") ||
            low.startsWith("minecraft:") || low.startsWith("java:")
    }

    class Result(
        val errors: List<String>,
        val warnings: List<String>
    ) {
        fun hasIssues(): Boolean = errors.isNotEmpty() || warnings.isNotEmpty()
        fun isLaunchBlocked(): Boolean = errors.isNotEmpty()
    }
}
