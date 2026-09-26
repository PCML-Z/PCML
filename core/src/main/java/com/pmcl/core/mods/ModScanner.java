package com.pmcl.core.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Mod 元数据解析器：扫描 mods 目录下所有 jar（含 .disabled 禁用文件），
 * 优先按以下顺序解析：
 *   1) quilt.mod.json      → Quilt mod（常同时带 fabric.mod.json）
 *   2) fabric.mod.json     → Fabric mod
 *   3) META-INF/neoforge.mods.toml → NeoForge mod
 *   4) META-INF/mods.toml  → Forge mod（1.13+）
 *   5) META-INF/MANIFEST.MF → 通用兜底
 * <p>
 * Forge/NeoForge 的 [[dependencies.<modId>]] 段做完整段解析，
 * 区分 mandatory（→ depends）与 optional/incompatible（→ conflicts 仅记录 incompatible）。
 */
public final class ModScanner {

    /** 模组元数据 entry 上限，防压缩炸弹式 OOM */
    private static final long MAX_META_BYTES = 2L * 1024 * 1024;

    private static String readEntryLimited(JarFile jar, JarEntry entry) throws IOException {
        long declared = entry.getSize();
        if (declared > MAX_META_BYTES) {
            throw new IOException("Mod metadata entry too large: " + entry.getName()
                    + " (" + declared + " bytes)");
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(com.pmcl.core.util.SafeZipExtractor.readLimited(in, MAX_META_BYTES),
                    StandardCharsets.UTF_8);
        }
    }


    private ModScanner() {}

    /** 与 LaunchProfileBuilder 一致：覆盖 Forge 的 mods/&lt;version&gt;/ 子目录。 */
    public static final int SCAN_MAX_DEPTH = 4;
    public static final int SCAN_MAX_JARS = 2000;

    /**
     * 扫描某目录下所有 .jar 文件（含一层到四层子目录），返回解析后的 mod 元数据列表。
     * 同时识别 .disabled 后缀的禁用 mod（disabled=true）。跳过符号链接。
     */
    public static List<ModMeta> scanDirectory(Path modsDir) throws IOException {
        List<ModMeta> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return result;
        Path base = modsDir.toAbsolutePath().normalize();
        try (Stream<Path> stream = Files.walk(modsDir, SCAN_MAX_DEPTH)) {
            stream.forEach(p -> {
                if (result.size() >= SCAN_MAX_JARS) return;
                try {
                    if (Files.isSymbolicLink(p) || !Files.isRegularFile(p)) return;
                    Path abs = p.toAbsolutePath().normalize();
                    if (!abs.startsWith(base)) return;
                    String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (name.endsWith(".pmcl-bak")) return;
                    if (!(name.endsWith(".jar") || name.endsWith(".jar.disabled"))) return;
                    ModMeta meta = parseJar(p);
                    if (meta != null) {
                        meta.setJarPath(abs.toString());
                        result.add(meta);
                    }
                } catch (Throwable t) {
                    System.err.println("[ModScanner] 解析 jar 失败: " + p + " - " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
            });
        }
        if (result.size() >= SCAN_MAX_JARS) {
            System.err.println("[ModScanner] 已达 " + SCAN_MAX_JARS + " 上限，部分 jar 未列入: " + modsDir);
        }
        return result;
    }

    /** 目录树最大 mtime，用于扫描缓存；子目录增删 jar 也能失效。 */
    public static long directoryFingerprint(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) return 0L;
        long[] max = {0L};
        try (Stream<Path> stream = Files.walk(modsDir, SCAN_MAX_DEPTH)) {
            stream.forEach(p -> {
                try {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > max[0]) max[0] = t;
                } catch (Throwable ignored) {
                }
            });
        } catch (IOException e) {
            return 0L;
        }
        return max[0];
    }

    /**
     * 解析单个 mod jar（路径名以 .disabled 结尾时识别为禁用）。
     */
    public static ModMeta parseJar(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Quilt 包常同时带 fabric.mod.json，必须先认 quilt，否则会被标成 fabric
            JarEntry quilt = jar.getJarEntry("quilt.mod.json");
            if (quilt != null) {
                return parseQuilt(jar, quilt, fileName);
            }
            JarEntry fabric = jar.getJarEntry("fabric.mod.json");
            if (fabric != null) {
                return parseFabric(jar, fabric, fileName);
            }
            // 3) NeoForge neoforge.mods.toml（优先于 mods.toml，NeoForge 1.20.2+）
            JarEntry neoforge = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (neoforge != null) {
                return parseForge(jar, neoforge, fileName, "neoforge");
            }
            // 4) Forge mods.toml
            JarEntry forge = jar.getJarEntry("META-INF/mods.toml");
            if (forge != null) {
                return parseForge(jar, forge, fileName, "forge");
            }
            // 5) MANIFEST.MF 兜底
            JarEntry manifest = jar.getJarEntry("META-INF/MANIFEST.MF");
            if (manifest != null) {
                return parseManifest(jar, manifest, fileName);
            }
            // 无法识别
            return new ModMeta(fileName, "unknown", fileName, "", "", "unknown",
                    Collections.emptyList(), Collections.emptyList(), fileName);
        } catch (Throwable e) {
            // 捕获 IOException + Gson RuntimeException 等，避免单个 jar 中断扫描。
            // 返回兜底 ModMeta（而非 null）以保证该 jar 仍出现在列表中。
            return new ModMeta(fileName, "unknown", fileName, "", "", "unknown",
                    Collections.emptyList(), Collections.emptyList(), fileName);
        }
    }

    private static ModMeta parseFabric(JarFile jar, JarEntry entry, String fileName) throws IOException {
        JsonObject o = JsonParser.parseString(readEntryLimited(jar, entry)).getAsJsonObject();
        String id = safeStr(o, "id", fileName);
        String version = resolvePlaceholderVersion(safeStr(o, "version", "unknown"), jar, fileName);
        String name = safeStr(o, "name", id);
        String desc = safeStr(o, "description", "");
        String authors = extractAuthors(o);
        List<String> deps = jsonArrToStrings(o, "depends");
        List<String> rules = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        addJsonConflictRules(o, "conflicts", true, rules, conflicts);
        addJsonConflictRules(o, "breaks", false, rules, null);
        ModMeta meta = new ModMeta(id, version, name, desc, authors, "fabric",
                deps, conflicts, fileName);
        meta.setConflictRules(rules);
        meta.setIconEntry(extractFabricIcon(o));
        return meta;
    }

    /** fabric.mod.json 的 icon 可为字符串或 { "64": "path", ... } */
    private static String extractFabricIcon(JsonObject o) {
        try {
            if (o == null || !o.has("icon") || o.get("icon").isJsonNull()) return "";
            JsonElement el = o.get("icon");
            if (el.isJsonPrimitive()) return el.getAsString();
            if (el.isJsonObject()) {
                JsonObject icons = el.getAsJsonObject();
                // 优先较大尺寸
                for (String key : new String[]{"256", "128", "64", "32"}) {
                    if (icons.has(key) && icons.get(key).isJsonPrimitive()) {
                        return icons.get(key).getAsString();
                    }
                }
                for (var e : icons.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) return e.getValue().getAsString();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** 安全地从 JsonObject 取字符串字段，字段缺失或类型不符时返回默认值（不抛异常）。 */
    private static String safeStr(JsonObject o, String key, String def) {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            JsonElement el = o.get(key);
            if (el.isJsonPrimitive()) return el.getAsString();
            // 非原始类型（对象/数组）时返回其 toString，避免丢失但也不抛
            return el.toString();
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 解析 quilt.mod.json（兼容 Quilt 加载器，Quilt 兼容 Fabric API）。
     * 结构：{ "schema_version": 1, "quilt_loader": { "id", "version", "name", ... , "depends": [...] } }
     */
    private static ModMeta parseQuilt(JarFile jar, JarEntry entry, String fileName) throws IOException {
        JsonObject o = JsonParser.parseString(readEntryLimited(jar, entry)).getAsJsonObject();
        JsonObject ql = o.has("quilt_loader") ? o.getAsJsonObject("quilt_loader") : o;
        String id = safeStr(ql, "id", fileName);
        String version = resolvePlaceholderVersion(safeStr(ql, "version", "unknown"), jar, fileName);
        String name = safeStr(ql, "name", id);
        String desc = safeStr(ql, "description", "");
        String authors = extractAuthors(ql);
        String icon = extractFabricIcon(ql);
        if (icon.isEmpty()) icon = extractFabricIcon(o);
        List<String> deps = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        // depends 可以是数组 [{id, optional}] 或对象 {id: {...}}
        if (ql.has("depends")) {
            JsonElement d = ql.get("depends");
            if (d.isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray()) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                        deps.add(e.getAsJsonObject().get("id").getAsString());
                    } else if (e.isJsonPrimitive()) {
                        deps.add(e.getAsString());
                    }
                }
            } else if (d.isJsonObject()) {
                deps.addAll(d.getAsJsonObject().keySet());
            }
        }
        List<String> rules = new ArrayList<>();
        if (ql.has("breaks")) {
            addJsonConflictRules(ql, "breaks", false, rules, null);
        }
        ModMeta meta = new ModMeta(id, version, name, desc, authors, "quilt",
                deps, conflicts, fileName);
        meta.setConflictRules(rules);
        meta.setIconEntry(icon);
        return meta;
    }

    /**
     * 解析 mods.toml / neoforge.mods.toml。
     * 完整段解析 [[mods]] 与 [[dependencies.<modId>]]，区分 mandatory / optional / incompatible。
     */
    private static ModMeta parseForge(JarFile jar, JarEntry entry, String fileName, String loader) throws IOException {
        String content = readEntryLimited(jar, entry);
        // 预先按行拆分一次，避免 tomlValueInSection 每次都重新 split
        String[] lines = content.split("\n");
        // === 提取 [[mods]] 段内的字段 ===
        String modId = tomlValueInSection(lines, "modId", "mods");
        String version = resolvePlaceholderVersion(
                tomlValueInSection(lines, "version", "mods"), jar, fileName);
        String name = tomlValueInSection(lines, "displayName", "mods");
        if (name == null) name = tomlValueInSection(lines, "name", "mods");
        String desc = tomlValueInSection(lines, "description", "mods");
        String authors = tomlValueInSection(lines, "authors", "mods");

        // === 解析所有 [[dependencies.<modId>]] 段 ===
        // 每个段含：modId, mandatory=true/false, type=required/optional/incompatible
        List<String> deps = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> rules = new ArrayList<>();
        for (TomlDepBlock dep : parseTomlDepBlocks(content)) {
            if (dep.incompatible) {
                if (!conflicts.contains(dep.modId)) conflicts.add(dep.modId);
                rules.add(conflictRule(dep.modId, dep.versionRange, true));
            } else if (dep.mandatory) {
                deps.add(dep.modId);
            }
            // optional 不加入（不会阻塞启动）
        }
        deps = dedup(deps);

        ModMeta meta = new ModMeta(modId != null ? modId : fileName,
                version != null ? version : "unknown",
                name != null ? name : modId,
                desc != null ? desc : "",
                authors != null ? authors : "",
                loader, deps, conflicts, fileName);
        meta.setConflictRules(rules);
        String logo = tomlValueInSection(lines, "logoFile", "mods");
        if (logo == null || logo.isEmpty()) logo = tomlValueInSection(lines, "logoFile", "");
        if (logo != null && !logo.isEmpty()) meta.setIconEntry(logo);
        return meta;
    }

    private static ModMeta parseManifest(JarFile jar, JarEntry entry, String fileName) throws IOException {
        String content = readEntryLimited(jar, entry);
        String name = manifestAttr(content, "Implementation-Title");
        String version = manifestAttr(content, "Implementation-Version");
        return new ModMeta(name != null ? name : fileName,
                version != null ? version : "unknown",
                name != null ? name : fileName,
                "通过 MANIFEST.MF 识别", "", "unknown",
                Collections.emptyList(), Collections.emptyList(), fileName);
    }

    // ==================== TOML 解析辅助 ====================

    /** TOML 依赖段块：记录 modId / mandatory / incompatible */
    private static final class TomlDepBlock {
        String modId;
        String versionRange;
        boolean mandatory = true;
        boolean incompatible = false;
    }

    /**
     * 解析所有 [[dependencies.xxx]] 段。
     * 段内字段：modId="...", type="required|optional|incompatible", mandatory=true|false
     * NeoForge 用 type 字段，Forge 用 mandatory 字段。
     */
    private static List<TomlDepBlock> parseTomlDepBlocks(String content) {
        List<TomlDepBlock> blocks = new ArrayList<>();
        String[] lines = content.split("\n");
        boolean inDepSection = false;
        TomlDepBlock current = null;
        for (String raw : lines) {
            String line = raw.trim();
            // 进入新的 [[dependencies.xxx]] 段
            if (line.startsWith("[[dependencies.")) {
                // 提交上一个段
                if (current != null && current.modId != null) blocks.add(current);
                current = new TomlDepBlock();
                inDepSection = true;
                continue;
            }
            // 任何非 [[dependencies 段都结束当前段
            if (line.startsWith("[[") && inDepSection) {
                if (current != null && current.modId != null) blocks.add(current);
                current = null;
                inDepSection = false;
            }
            if (current == null) continue;
            // 在依赖段内解析字段
            if (line.startsWith("modId=") || line.startsWith("modId =")) {
                current.modId = stripQuotes(afterEq(line));
            } else if (line.startsWith("mandatory=") || line.startsWith("mandatory =")) {
                current.mandatory = Boolean.parseBoolean(afterEq(line));
            } else if (line.startsWith("type=") || line.startsWith("type =")) {
                String type = stripQuotes(afterEq(line));
                if ("incompatible".equalsIgnoreCase(type)) {
                    current.incompatible = true;
                    current.mandatory = false;
                } else if ("optional".equalsIgnoreCase(type)) {
                    current.mandatory = false;
                }
            } else if (line.startsWith("versionRange=") || line.startsWith("versionRange =")) {
                current.versionRange = stripQuotes(afterEq(line));
            } else if (line.startsWith("side=") || line.startsWith("side =")) {
                // side=BOTH/CLIENT/SERVER，不影响依赖
            }
        }
        // 提交最后一个段
        if (current != null && current.modId != null) blocks.add(current);
        return blocks;
    }

    /** 在指定 [[sectionName]] 段内提取 key 的值（仅在该段内，避免跨段干扰） */
    private static String tomlValueInSection(String content, String key, String sectionName) {
        return tomlValueInSection(content.split("\n"), key, sectionName);
    }

    /** 在指定 [[sectionName]] 段内提取 key 的值（接收预先拆分好的行数组，避免重复 split） */
    private static String tomlValueInSection(String[] lines, String key, String sectionName) {
        String sectionHeader = "[[" + sectionName + "]]";
        boolean inSection = false;
        for (String raw : lines) {
            String line = raw.trim();
            // 进入 [[sectionName]] 段
            if (line.equalsIgnoreCase(sectionHeader)) {
                inSection = true;
                continue;
            }
            // 任何其他段头（[xxx] 或 [[xxx]]）都结束当前段
            if ((line.startsWith("[[") || line.startsWith("[")) && inSection) {
                inSection = false;
                continue;
            }
            if (!inSection) continue;
            if (line.startsWith(key + "=") || line.startsWith(key + " =")) {
                return stripQuotes(afterEq(line));
            }
        }
        return null;
    }

    /** 取等号后的内容 */
    private static String afterEq(String line) {
        int eq = line.indexOf('=');
        return eq >= 0 ? line.substring(eq + 1).trim() : "";
    }

    private static List<String> dedup(List<String> list) {
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    /** 展开 ${version} / ${file.jarVersion}：MANIFEST Implementation-Version，再文件名。 */
    private static String resolvePlaceholderVersion(String version, JarFile jar, String fileName) {
        if (version == null || version.isBlank()) return "unknown";
        if (!version.contains("${")) return version;
        try {
            JarEntry mf = jar.getJarEntry("META-INF/MANIFEST.MF");
            if (mf != null) {
                String impl = manifestAttr(readEntryLimited(jar, mf), "Implementation-Version");
                if (impl != null && !impl.isBlank() && !impl.contains("${")) return impl;
            }
        } catch (Exception ignored) {
        }
        String hint = versionHintFromFileName(fileName);
        return hint != null ? hint : version;
    }

    private static String versionHintFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        String base = fileName;
        int dot = base.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".jar");
        if (dot > 0) base = base.substring(0, dot);
        String[] parts = base.split("[-_]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            char c = p.charAt(0);
            if (c >= '0' && c <= '9') return p;
        }
        return null;
    }

    // ==================== 通用解析辅助 ====================

    private static String extractAuthors(JsonObject o) {
        if (!o.has("authors")) return "";
        JsonElement a = o.get("authors");
        if (a.isJsonArray()) {
            List<String> names = new ArrayList<>();
            for (JsonElement e : a.getAsJsonArray()) {
                if (e.isJsonPrimitive()) names.add(e.getAsString());
                else if (e.isJsonObject() && e.getAsJsonObject().has("name"))
                    names.add(e.getAsJsonObject().get("name").getAsString());
            }
            return String.join(", ", names);
        }
        return a.isJsonPrimitive() ? a.getAsString() : "";
    }

    /** {@code modId\tversionRange\tH|S}，空 range 表示任意版本。 */
    static String conflictRule(String modId, String range, boolean hard) {
        String id = modId == null ? "" : modId.trim();
        String r = range == null ? "" : range.trim();
        if ("*".equals(r) || "any".equalsIgnoreCase(r)) r = "";
        return id + "\t" + r + "\t" + (hard ? "H" : "S");
    }

    private static void addJsonConflictRules(JsonObject o, String key, boolean hard,
                                             List<String> rules, List<String> hardIds) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return;
        JsonElement e = o.get(key);
        if (e.isJsonObject()) {
            for (var entry : e.getAsJsonObject().entrySet()) {
                String id = entry.getKey();
                String range = jsonVersionRange(entry.getValue());
                rules.add(conflictRule(id, range, hard));
                if (hard && hardIds != null && !hardIds.contains(id)) hardIds.add(id);
            }
            return;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x == null || x.isJsonNull()) continue;
                String id = null;
                String range = "";
                if (x.isJsonPrimitive()) {
                    id = x.getAsString();
                } else if (x.isJsonObject()) {
                    JsonObject obj = x.getAsJsonObject();
                    if (obj.has("id") && obj.get("id").isJsonPrimitive()) id = obj.get("id").getAsString();
                    if (obj.has("versions")) range = jsonVersionRange(obj.get("versions"));
                    else if (obj.has("version")) range = jsonVersionRange(obj.get("version"));
                }
                if (id == null || id.isBlank()) continue;
                rules.add(conflictRule(id, range, hard));
                if (hard && hardIds != null && !hardIds.contains(id)) hardIds.add(id);
            }
        }
    }

    private static String jsonVersionRange(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (value.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement x : value.getAsJsonArray()) {
                if (x != null && x.isJsonPrimitive()) parts.add(x.getAsString());
            }
            return String.join(" || ", parts);
        }
        return "";
    }

    private static List<String> jsonArrToStrings(JsonObject o, String key) {
        if (!o.has(key)) return Collections.emptyList();
        JsonElement e = o.get(key);
        if (e.isJsonObject()) {
            // fabric depends 是对象：{"modid": "any"} → 取 key
            return new ArrayList<>(e.getAsJsonObject().keySet());
        }
        if (e.isJsonArray()) {
            List<String> list = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) {
                if (x != null && !x.isJsonNull() && x.isJsonPrimitive()) list.add(x.getAsString());
                else if (x != null && x.isJsonObject() && x.getAsJsonObject().has("id")) {
                    list.add(x.getAsJsonObject().get("id").getAsString());
                }
            }
            return list;
        }
        return Collections.emptyList();
    }

    /** 极简 TOML 单行 value 提取：key="value" 或 key = "value" */
    private static String tomlValue(String content, String key) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + "=") || line.startsWith(key + " =")) {
                int eq = line.indexOf('=');
                return stripQuotes(line.substring(eq + 1).trim());
            }
        }
        return null;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        // 去除行内注释（如 "neoforge" #mandatory → "neoforge"）
        int hash = t.indexOf('#');
        if (hash >= 0) t = t.substring(0, hash).trim();
        // 去除引号
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.trim();
    }

    private static String manifestAttr(String content, String key) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }
}
