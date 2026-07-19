package com.pmcl.core.gamecontent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的 options.txt 读写工具。
 * <p>
 * Minecraft 的 options.txt 行格式为 {@code key:value}，但有两种特殊情况：
 * <ul>
 *   <li>普通字段：{@code lang:zh_cn}、{@code shaderPack:} 等，value 可为任意字符串</li>
 *   <li>JSON 数组字段：{@code resourcePacks:["vanilla","file/MyPack.zip"]}，
 *       value 是 JSON 数组字符串，含逗号、引号、方括号</li>
 * </ul>
 * 之前 ShaderPackManager.writeOption 和 LaunchProfileBuilder.syncGameLanguage
 * 各自实现了普通字段的写入，本工具统一两者并新增 JSON 数组字段支持。
 * <p>
 * 所有方法对 IO 异常静默忽略（不阻塞启动），与现有 injectWindowIcon 等启动期写入保持一致。
 */
public final class OptionsTxtWriter {

    private OptionsTxtWriter() {}

    /**
     * 写入/更新普通 key:value 字段，保留其它行。
     * 若文件不存在则新建；若键已存在则更新，否则追加。
     * 若现有值与目标值相同则不重写，避免改动 mtime 触发 MC 重新加载。
     */
    public static void writeOption(Path optionsFile, String key, String value) {
        if (optionsFile == null || key == null || key.isEmpty()) return;
        String line = key + ":" + (value == null ? "" : value);
        try {
            if (!Files.exists(optionsFile)) {
                if (optionsFile.getParent() != null) Files.createDirectories(optionsFile.getParent());
                Files.writeString(optionsFile, line + "\n", StandardCharsets.UTF_8);
                return;
            }
            List<String> lines = new ArrayList<>(
                    Files.readAllLines(optionsFile, StandardCharsets.UTF_8));
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(key + ":")) {
                    if (lines.get(i).equals(line)) return;  // 已是目标值
                    lines.set(i, line);
                    found = true;
                    break;
                }
            }
            if (!found) lines.add(line);
            Files.writeString(optionsFile, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 写入失败不应阻塞启动
        }
    }

    /** 读取普通字段值，不存在返回 null。 */
    public static String readOption(Path optionsFile, String key) {
        if (optionsFile == null || !Files.exists(optionsFile) || key == null) return null;
        try {
            List<String> lines = Files.readAllLines(optionsFile, StandardCharsets.UTF_8);
            String prefix = key + ":";
            for (String line : lines) {
                if (line.startsWith(prefix)) {
                    return line.substring(prefix.length());
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    // ===== resourcePacks JSON 数组字段专用 =====
    // options.txt 中格式：resourcePacks:["vanilla","file/Foo.zip"]
    // 必须保持 "vanilla" 在首位（MC 默认资源包），自定义包用 "file/<文件名>" 引用

    private static final Pattern PACK_ENTRY = Pattern.compile("\"([^\"]*)\"");

    /**
     * 读取 resourcePacks 列表。返回 ["vanilla","file/Foo.zip"] 等。
     * 字段不存在或解析失败返回仅含 "vanilla" 的列表（MC 默认行为）。
     */
    public static List<String> getResourcePacks(Path optionsFile) {
        List<String> result = new ArrayList<>();
        result.add("vanilla");
        if (optionsFile == null || !Files.exists(optionsFile)) return result;
        String value = readOption(optionsFile, "resourcePacks");
        if (value == null || value.isEmpty()) return result;
        // 去除首尾方括号
        String inner = value.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        Matcher m = PACK_ENTRY.matcher(inner);
        boolean foundAny = false;
        while (m.find()) {
            String entry = m.group(1);
            if (!result.contains(entry)) {
                result.add(entry);
                foundAny = true;
            }
        }
        if (!foundAny) {
            // 数组为空或解析失败，保持 vanilla
            return result;
        }
        // 确保 vanilla 在首位
        if (!result.get(0).equals("vanilla")) {
            result.remove("vanilla");
            result.add(0, "vanilla");
        }
        return result;
    }

    /**
     * 启用一个资源包：若未在列表中则追加到末尾，并写入 options.txt。
     * packRef 格式为 "file/MyPack.zip"（含 file/ 前缀）。
     * 已存在则不重复添加。
     */
    public static void enableResourcePack(Path optionsFile, String packRef) {
        if (packRef == null || packRef.isEmpty()) return;
        List<String> packs = getResourcePacks(optionsFile);
        if (packs.contains(packRef)) return;
        packs.add(packRef);
        writeResourcePacks(optionsFile, packs);
    }

    /**
     * 禁用一个资源包：从列表中移除并写入 options.txt。
     */
    public static void disableResourcePack(Path optionsFile, String packRef) {
        if (packRef == null || packRef.isEmpty()) return;
        List<String> packs = getResourcePacks(optionsFile);
        if (!packs.remove(packRef)) return;
        writeResourcePacks(optionsFile, packs);
    }

    /**
     * 写入完整的 resourcePacks 列表。
     * 自动保持 "vanilla" 在首位，去重。
     */
    public static void writeResourcePacks(Path optionsFile, List<String> packs) {
        if (optionsFile == null || packs == null) return;
        List<String> dedup = new ArrayList<>();
        for (String p : packs) {
            if (p != null && !p.isEmpty() && !dedup.contains(p)) dedup.add(p);
        }
        // vanilla 必须在首位
        dedup.remove("vanilla");
        dedup.add(0, "vanilla");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dedup.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(dedup.get(i)).append("\"");
        }
        sb.append("]");
        writeOption(optionsFile, "resourcePacks", sb.toString());
    }
}
