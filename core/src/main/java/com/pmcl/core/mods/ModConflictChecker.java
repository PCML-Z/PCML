package com.pmcl.core.mods;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mod 依赖冲突检测。
 * <p>
 * 检查规则：
 *   1) 依赖缺失：A 依赖 B，但 B 不在已安装列表中
 *   2) 冲突：A 声明 conflicts B，但 B 已安装
 *   3) 重复：相同 modId 存在多个版本
 */
public final class ModConflictChecker {

    private ModConflictChecker() {}

    public static Result check(List<ModMeta> mods) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 过滤掉 modId/name 为 null 或异常的条目（TOML 解析脏数据）
        List<ModMeta> validMods = new ArrayList<>();
        for (ModMeta m : mods) {
            if (m.getModId() == null || m.getModId().isBlank() || "null".equals(m.getModId())) continue;
            validMods.add(m);
        }

        // modId → mods（检测重复），同时构建 normalized modId 集合用于模糊匹配
        Map<String, List<ModMeta>> byId = new HashMap<>();
        Map<String, String> normalizedToOriginal = new HashMap<>(); // 连字符↔下划线模糊匹配
        for (ModMeta m : validMods) {
            byId.computeIfAbsent(m.getModId(), k -> new ArrayList<>()).add(m);
            String norm = normalizeModId(m.getModId());
            normalizedToOriginal.putIfAbsent(norm, m.getModId());
        }
        for (Map.Entry<String, List<ModMeta>> e : byId.entrySet()) {
            if (e.getValue().size() > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("重复 mod: ").append(e.getKey()).append(" → ");
                for (ModMeta m : e.getValue()) {
                    sb.append(m.getJarFile()).append(" (v").append(m.getVersion()).append("), ");
                }
                warnings.add(sb.substring(0, sb.length() - 2));
            }
        }

        // 检查冲突（不检查依赖缺失：静态扫描无法识别 jar-in-jar 内嵌库、
        // 跨加载器兼容、连字符/下划线转换等，依赖缺失检查误报率极高，直接跳过）
        for (ModMeta m : validMods) {
            String displayName = (m.getName() != null && !m.getName().isBlank()) ? m.getName() : m.getModId();
            for (String conflictId : m.getConflicts()) {
                if (isSystemDep(conflictId)) continue;
                if (byId.containsKey(conflictId) || normalizedToOriginal.containsKey(normalizeModId(conflictId))) {
                    errors.add(displayName + " 与 " + conflictId + " 冲突");
                }
            }
        }

        return new Result(errors, warnings);
    }

    /** 将 modId 中的连字符和下划线统一，用于模糊匹配（NeoForge 运行时会做此转换） */
    private static String normalizeModId(String id) {
        if (id == null) return "";
        return id.toLowerCase().replace('-', '_').replace("\"", "").trim();
    }

    /**
     * M92: 系统依赖白名单外部化到资源文件（system_deps.json），降低维护成本。
     * 通过 classpath 资源加载；加载失败时回退到最小硬编码集合保证基本可用。
     */
    private static final Set<String> SYSTEM_DEPS = loadSystemDeps();

    private static Set<String> loadSystemDeps() {
        // 兜底集合：仅包含加载器与运行时（保证资源加载失败时不影响核心冲突检测）
        Set<String> fallback = new HashSet<>(Set.of(
                "minecraft", "java", "fabricloader", "fabric-language-kotlin",
                "quilt_loader", "quilted_fabric_api", "forge", "neoforge", "fml"
        ));
        try (var in = ModConflictChecker.class.getResourceAsStream(
                "/com/pmcl/core/mods/system_deps.json")) {
            if (in == null) {
                System.err.println("[ModConflictChecker] system_deps.json 未找到，使用兜底集合");
                return Collections.unmodifiableSet(fallback);
            }
            String content = new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            var arr = JsonParser.parseString(content).getAsJsonArray();
            Set<String> set = new HashSet<>(arr.size());
            for (var e : arr) {
                String s = e.getAsString();
                if (s != null) {
                    set.add(s.toLowerCase(java.util.Locale.ROOT));
                }
            }
            return Collections.unmodifiableSet(set);
        } catch (Exception e) {
            System.err.println("[ModConflictChecker] 加载 system_deps.json 失败，使用兜底集合: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Collections.unmodifiableSet(fallback);
        }
    }

    private static boolean isSystemDep(String id) {
        if (id == null) return false;
        // 清理可能的引号、注释、空白
        String low = id.toLowerCase().trim();
        if (low.isEmpty()) return true;  // 空依赖名视为系统级，跳过
        // 去除行内注释（如 "neoforge" #mandatory → neoforge）
        int hash = low.indexOf('#');
        if (hash >= 0) low = low.substring(0, hash).trim();
        // 去除引号
        if (low.length() >= 2 && low.startsWith("\"") && low.endsWith("\"")) {
            low = low.substring(1, low.length() - 1);
        }
        if (SYSTEM_DEPS.contains(low)) return true;
        // Fabric API 子模块（由 fabric-api 聚合提供）+ 版本约束前缀
        return low.startsWith("fabric-api") || low.startsWith("fabric-")
                || low.startsWith("minecraft:") || low.startsWith("java:");
    }

    public static final class Result {
        private final List<String> errors;
        private final List<String> warnings;

        public Result(List<String> errors, List<String> warnings) {
            this.errors = errors;
            this.warnings = warnings;
        }

        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public boolean hasIssues() { return !errors.isEmpty() || !warnings.isEmpty(); }
        public boolean isLaunchBlocked() { return !errors.isEmpty(); }
    }
}
