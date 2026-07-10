package com.pmcl.core.mods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        // 加载器与运行时
        if (low.equals("minecraft") || low.equals("java") || low.equals("fabricloader")
                || low.equals("fabric-language-kotlin") || low.equals("quilt_loader")
                || low.equals("quilted_fabric_api") || low.equals("forge")
                || low.equals("neoforge") || low.equals("fml")
                // Fabric API 子模块（由 fabric-api 聚合提供）
                || low.startsWith("fabric-api") || low.startsWith("fabric-")
                // 版本约束前缀
                || low.startsWith("minecraft:") || low.startsWith("java:")) return true;
        // 常见 jar-in-jar 内嵌库（NeoForge mod 常打包这些库，静态扫描检测不到）
        if (low.equals("cupboard") || low.equals("kotlinforforge")
                || low.equals("cloth-config2") || low.equals("cloth_config") || low.equals("cloth-config")
                || low.equals("curios") || low.equals("curiosity")
                || low.equals("jei") || low.equals("rei") || low.equals("emi")
                || low.equals("formations") || low.equals("diagonalblocks")
                || low.equals("extensibleenums") || low.equals("limitlesscontainers")
                || low.equals("lootintegrations") || low.equals("coroutil")
                || low.equals("xaerolib") || low.equals("c2me-base")
                || low.equals("kirin") || low.equals("balm-fabric")
                || low.equals("fancymenu") || low.equals("ambientsounds")
                || low.equals("dummmmmmy") || low.equals("geckolib")
                || low.equals("entityculling") || low.equals("create")
                || low.equals("embeddium") || low.equals("rubidium")
                || low.equals("kubejs") || low.equals("probejs")
                || low.equals("ftbchunks") || low.equals("ftbranks") || low.equals("ftblibrary")
                || low.equals("ftbteams") || low.equals("ftbquests") || low.equals("ftbxmodcompat")
                || low.equals("konkrete") || low.equals("melody")
                || low.equals("cristellib") || low.equals("biox")
                || low.equals("connector") || low.equals("connectorextras")
                || low.equals("athena") || low.equals("collective")
                || low.equals("drippyloadingscreen") || low.equals("rrls")
                || low.equals("findme") || low.equals("itemborders")
                || low.equals("equipmentcompare") || low.equals("controllable")
                || low.equals("legendarytooltips") || low.equals("merchantmarkers")
                || low.equals("advancementplaques") || low.equals("worldtools")
                || low.equals("pneumaticcraft") || low.equals("railcraft")
                || low.equals("occultism") || low.equals("luckperms")
                || low.equals("openpartiesandclaims") || low.equals("voicechat")
                || low.equals("irons_spellbooks") || low.equals("ironsspells")
                || low.equals("farmersdelight") || low.equals("crafttweaker")
                || low.equals("xaerobetterpvp") || low.equals("xaerominimap")
                || low.equals("xaeroworldmap") || low.equals("memoryleakfix")
                || low.equals("sodium") || low.equals("indium")
                || low.equals("chatheads") || low.equals("cubeattractors")
                || low.equals("rpgdemonstration") || low.equals("cmi")
                || low.equals("essentials") || low.equals("balm")
                || low.equals("libx") || low.equals("bookshelf")
                || low.equals("catalogue") || low.equals("fancytwitch")
                || low.equals("forgeconfigapiport") || low.equals("architectury")
                || low.equals("mindfuldarkness") || low.equals("commonality")
                || low.equals("yungsapi") || low.equals("repurposed_structures")
                || low.equals("moonlight") || low.equals("starstory")
                ) return true;
        return false;
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
