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

        // 禁用的 jar 不会被加载，不参与重复和冲突。
        List<ModMeta> validMods = new ArrayList<>();
        for (ModMeta m : mods) {
            if (m == null || m.isDisabled()) continue;
            if (m.getModId() == null || m.getModId().isBlank() || "null".equals(m.getModId())) continue;
            validMods.add(m);
        }

        Map<String, List<ModMeta>> byId = new HashMap<>();
        Map<String, ModMeta> byNorm = new HashMap<>();
        for (ModMeta m : validMods) {
            byId.computeIfAbsent(m.getModId(), k -> new ArrayList<>()).add(m);
            byNorm.putIfAbsent(normalizeModId(m.getModId()), m);
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

        // 只报告「对方已启用，且版本落在声明范围内」的冲突。
        // breaks 是警告；conflicts / incompatible 才是错误。版本对不上不报。
        Set<String> reported = new HashSet<>();
        for (ModMeta m : validMods) {
            String displayName = displayName(m);
            for (Rule rule : rulesOf(m)) {
                if (rule.modId.isEmpty() || isSystemDep(rule.modId)) continue;
                if (normalizeModId(rule.modId).equals(normalizeModId(m.getModId()))) continue;
                ModMeta other = byId.containsKey(rule.modId)
                        ? byId.get(rule.modId).get(0)
                        : byNorm.get(normalizeModId(rule.modId));
                if (other == null) continue;
                if (!versionMatches(other.getVersion(), rule.range)) continue;
                String a = normalizeModId(m.getModId());
                String b = normalizeModId(other.getModId());
                String pair = (a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a)
                        + (rule.hard ? "|H" : "|S");
                if (!reported.add(pair)) continue;
                String otherName = displayName(other);
                String line = displayName + " 与 " + otherName
                        + (rule.hard ? " 冲突" : " 不兼容");
                if (!rule.range.isEmpty()) {
                    line += "（" + other.getVersion() + " 在 " + rule.range + "）";
                }
                if (rule.hard) errors.add(line);
                else warnings.add(line);
            }
        }

        return new Result(errors, warnings);
    }

    private static String displayName(ModMeta m) {
        if (m.getName() != null && !m.getName().isBlank()) return m.getName();
        return m.getModId();
    }

    private static List<Rule> rulesOf(ModMeta m) {
        List<String> encoded = m.getConflictRules();
        if (encoded != null && !encoded.isEmpty()) {
            List<Rule> rules = new ArrayList<>();
            for (String raw : encoded) {
                Rule rule = Rule.parse(raw);
                if (rule != null) rules.add(rule);
            }
            return rules;
        }
        List<Rule> legacy = new ArrayList<>();
        for (String id : m.getConflicts()) {
            if (id != null && !id.isBlank()) legacy.add(new Rule(id.trim(), "", true));
        }
        return legacy;
    }

    /** 已安装版本是否落在声明范围内。空范围、*、any 表示任意版本。解析不了则不报。 */
    static boolean versionMatches(String installed, String range) {
        if (range == null) return true;
        String r = range.trim();
        if (r.isEmpty() || "*".equals(r) || "any".equalsIgnoreCase(r)) return true;
        if (installed == null || installed.isBlank() || "unknown".equalsIgnoreCase(installed)
                || installed.contains("${")) {
            return false;
        }
        if (r.contains("||")) {
            for (String part : r.split("\\|\\|")) {
                if (versionMatches(installed, part.trim())) return true;
            }
            return false;
        }
        if ((r.startsWith("[") || r.startsWith("(")) && r.contains(",")) {
            return mavenInterval(installed, r);
        }
        String[] parts = r.split("\\s+");
        if (parts.length > 1) {
            for (String p : parts) {
                if (!versionMatches(installed, p)) return false;
            }
            return true;
        }
        return fabricAtom(installed, r);
    }

    private static boolean mavenInterval(String installed, String range) {
        int comma = range.indexOf(',');
        if (comma < 0 || range.length() < 3) return false;
        boolean lowInc = range.charAt(0) == '[';
        boolean highInc = range.charAt(range.length() - 1) == ']';
        String low = range.substring(1, comma).trim();
        String high = range.substring(comma + 1, range.length() - 1).trim();
        if (!low.isEmpty()) {
            int cmp = ModUpdateChecker.compareVersions(installed, low);
            if (lowInc ? cmp < 0 : cmp <= 0) return false;
        }
        if (!high.isEmpty()) {
            int cmp = ModUpdateChecker.compareVersions(installed, high);
            if (highInc ? cmp > 0 : cmp >= 0) return false;
        }
        return true;
    }

    private static boolean fabricAtom(String installed, String atom) {
        String a = atom.trim();
        if (a.isEmpty() || "*".equals(a)) return true;
        if (a.startsWith(">=")) return ModUpdateChecker.compareVersions(installed, a.substring(2).trim()) >= 0;
        if (a.startsWith("<=")) return ModUpdateChecker.compareVersions(installed, a.substring(2).trim()) <= 0;
        if (a.startsWith(">")) return ModUpdateChecker.compareVersions(installed, a.substring(1).trim()) > 0;
        if (a.startsWith("<")) return ModUpdateChecker.compareVersions(installed, a.substring(1).trim()) < 0;
        if (a.startsWith("=")) return ModUpdateChecker.compareVersions(installed, a.substring(1).trim()) == 0;
        if (a.startsWith("~")) return tilde(installed, a.substring(1).trim());
        if (a.startsWith("^")) return caret(installed, a.substring(1).trim());
        if (a.endsWith(".x") || a.endsWith(".*")) {
            String prefix = a.substring(0, a.length() - 2);
            return installed.equals(prefix) || installed.startsWith(prefix + ".");
        }
        return ModUpdateChecker.compareVersions(installed, a) == 0;
    }

    private static boolean tilde(String installed, String base) {
        if (ModUpdateChecker.compareVersions(installed, base) < 0) return false;
        String[] bits = base.split("\\.");
        if (bits.length < 2) return true;
        String upper = bits[0] + "." + (parseInt(bits[1]) + 1) + ".0";
        return ModUpdateChecker.compareVersions(installed, upper) < 0;
    }

    private static boolean caret(String installed, String base) {
        if (ModUpdateChecker.compareVersions(installed, base) < 0) return false;
        String[] bits = base.split("\\.");
        String upper;
        if (bits.length >= 1 && !"0".equals(bits[0])) {
            upper = (parseInt(bits[0]) + 1) + ".0.0";
        } else if (bits.length >= 2 && !"0".equals(bits[1])) {
            upper = "0." + (parseInt(bits[1]) + 1) + ".0";
        } else if (bits.length >= 3) {
            upper = "0.0." + (parseInt(bits[2]) + 1);
        } else {
            return true;
        }
        return ModUpdateChecker.compareVersions(installed, upper) < 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class Rule {
        final String modId;
        final String range;
        final boolean hard;

        Rule(String modId, String range, boolean hard) {
            this.modId = modId;
            this.range = range == null ? "" : range;
            this.hard = hard;
        }

        static Rule parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String[] p = raw.split("\t", 3);
            String id = p[0].trim();
            if (id.isEmpty()) return null;
            String range = p.length > 1 ? p[1].trim() : "";
            boolean hard = p.length < 3 || !"S".equalsIgnoreCase(p[2].trim());
            return new Rule(id, range, hard);
        }
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
