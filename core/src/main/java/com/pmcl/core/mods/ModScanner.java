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
 *   1) fabric.mod.json     → Fabric mod
 *   2) quilt.mod.json      → Quilt mod
 *   3) META-INF/mods.toml  → Forge mod（1.13+）
 *   4) META-INF/neoforge.mods.toml → NeoForge mod
 *   5) META-INF/MANIFEST.MF → 通用兜底
 * <p>
 * Forge/NeoForge 的 [[dependencies.<modId>]] 段做完整段解析，
 * 区分 mandatory（→ depends）与 optional/incompatible（→ conflicts 仅记录 incompatible）。
 */
public final class ModScanner {

    private ModScanner() {}

    /**
     * 扫描某目录下所有 .jar 文件，返回解析后的 mod 元数据列表。
     * 同时识别 .disabled 后缀的禁用 mod（disabled=true）。
     */
    public static List<ModMeta> scanDirectory(Path modsDir) throws IOException {
        List<ModMeta> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return result;
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.forEach(p -> {
                try {
                    String name = p.getFileName().toString().toLowerCase();
                    if (name.endsWith(".jar")) {
                        ModMeta meta = parseJar(p);
                        if (meta != null) result.add(meta);
                    }
                    // .disabled 后缀的文件也扫描，标记为禁用
                    else if (name.endsWith(".jar.disabled")) {
                        ModMeta meta = parseJar(p);
                        if (meta != null) result.add(meta);
                    }
                } catch (Throwable t) {
                    // 单个 jar 解析异常不能中断整个目录扫描
                    // （Gson 解析可能抛 RuntimeException，如 getAsString() 作用于非字符串）
                }
            });
        }
        return result;
    }

    /**
     * 解析单个 mod jar（路径名以 .disabled 结尾时识别为禁用）。
     */
    public static ModMeta parseJar(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        boolean disabled = fileName.toLowerCase().endsWith(".disabled");
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // 1) fabric.mod.json
            JarEntry fabric = jar.getJarEntry("fabric.mod.json");
            if (fabric != null) {
                return parseFabric(jar, fabric, fileName);
            }
            // 2) quilt.mod.json
            JarEntry quilt = jar.getJarEntry("quilt.mod.json");
            if (quilt != null) {
                return parseQuilt(jar, quilt, fileName);
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
        try (InputStream in = jar.getInputStream(entry)) {
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String id = safeStr(o, "id", fileName);
            String version = safeStr(o, "version", "unknown");
            String name = safeStr(o, "name", id);
            String desc = safeStr(o, "description", "");
            String authors = extractAuthors(o);
            List<String> deps = jsonArrToStrings(o, "depends");
            List<String> conflicts = jsonArrToStrings(o, "conflicts");
            return new ModMeta(id, version, name, desc, authors, "fabric",
                    deps, conflicts, fileName);
        }
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
        try (InputStream in = jar.getInputStream(entry)) {
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject ql = o.has("quilt_loader") ? o.getAsJsonObject("quilt_loader") : o;
            String id = safeStr(ql, "id", fileName);
            String version = safeStr(ql, "version", "unknown");
            String name = safeStr(ql, "name", id);
            String desc = safeStr(ql, "description", "");
            String authors = extractAuthors(ql);
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
            if (ql.has("breaks")) {
                JsonElement b = ql.get("breaks");
                if (b.isJsonArray()) {
                    for (JsonElement e : b.getAsJsonArray()) {
                        if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                            conflicts.add(e.getAsJsonObject().get("id").getAsString());
                        } else if (e.isJsonPrimitive()) {
                            conflicts.add(e.getAsString());
                        }
                    }
                } else if (b.isJsonObject()) {
                    conflicts.addAll(b.getAsJsonObject().keySet());
                }
            }
            return new ModMeta(id, version, name, desc, authors, "quilt",
                    deps, conflicts, fileName);
        }
    }

    /**
     * 解析 mods.toml / neoforge.mods.toml。
     * 完整段解析 [[mods]] 与 [[dependencies.<modId>]]，区分 mandatory / optional / incompatible。
     */
    private static ModMeta parseForge(JarFile jar, JarEntry entry, String fileName, String loader) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // === 提取 [[mods]] 段内的字段 ===
            String modId = tomlValueInSection(content, "modId", "mods");
            String version = tomlValueInSection(content, "version", "mods");
            String name = tomlValueInSection(content, "displayName", "mods");
            if (name == null) name = tomlValueInSection(content, "name", "mods");
            String desc = tomlValueInSection(content, "description", "mods");
            String authors = tomlValueInSection(content, "authors", "mods");

            // === 解析所有 [[dependencies.<modId>]] 段 ===
            // 每个段含：modId, mandatory=true/false, type=required/optional/incompatible
            List<String> deps = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            for (TomlDepBlock dep : parseTomlDepBlocks(content)) {
                if (dep.incompatible) {
                    conflicts.add(dep.modId);
                } else if (dep.mandatory) {
                    deps.add(dep.modId);
                }
                // optional 不加入（不会阻塞启动）
            }
            // 去重（同一 modId 可能在多个段中）
            deps = dedup(deps);
            conflicts = dedup(conflicts);

            return new ModMeta(modId != null ? modId : fileName,
                    version != null ? version : "unknown",
                    name != null ? name : modId,
                    desc != null ? desc : "",
                    authors != null ? authors : "",
                    loader, deps, conflicts, fileName);
        }
    }

    private static ModMeta parseManifest(JarFile jar, JarEntry entry, String fileName) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String name = manifestAttr(content, "Implementation-Title");
            String version = manifestAttr(content, "Implementation-Version");
            return new ModMeta(name != null ? name : fileName,
                    version != null ? version : "unknown",
                    name != null ? name : fileName,
                    "通过 MANIFEST.MF 识别", "", "unknown",
                    Collections.emptyList(), Collections.emptyList(), fileName);
        }
    }

    // ==================== TOML 解析辅助 ====================

    /** TOML 依赖段块：记录 modId / mandatory / incompatible */
    private static final class TomlDepBlock {
        String modId;
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
        String sectionHeader = "[[" + sectionName + "]]";
        String[] lines = content.split("\n");
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
        return a.getAsString();
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
            for (JsonElement x : e.getAsJsonArray()) list.add(x.getAsString());
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
