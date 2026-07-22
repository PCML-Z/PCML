package com.pmcl.core.mods;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组标签持久化：将用户自定义标签（如「性能」「科技」「魔法」）保存到 {@code ~/.pmcl/mod_tags.json}。
 * <p>
 * 格式：{@code { "jarFileName": ["标签1", "标签2"], ... }}
 * <p>
 * 线程安全：所有方法 synchronized。
 */
public final class ModTagStore {

    private final Path dataFile;
    private final Gson gson = new Gson();
    private final Map<String, List<String>> tagMap = new LinkedHashMap<>();

    public ModTagStore(Path dataFile) {
        this.dataFile = dataFile;
        load();
    }

    /** 获取指定 jar 文件的标签列表（返回副本） */
    public synchronized List<String> getTags(String jarFile) {
        if (jarFile == null || jarFile.isEmpty()) return Collections.emptyList();
        List<String> tags = tagMap.get(jarFile);
        return tags != null ? new ArrayList<>(tags) : Collections.emptyList();
    }

    /** 设置指定 jar 文件的标签列表（覆盖） */
    public synchronized void setTags(String jarFile, List<String> tags) {
        if (jarFile == null || jarFile.isEmpty()) return;
        if (tags == null || tags.isEmpty()) {
            tagMap.remove(jarFile);
        } else {
            List<String> clean = new ArrayList<>();
            for (String t : tags) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty() && !clean.contains(trimmed)) clean.add(trimmed);
            }
            tagMap.put(jarFile, clean);
        }
        save();
    }

    /** 获取所有已使用的标签（去重排序） */
    public synchronized List<String> getAllTags() {
        java.util.Set<String> all = new java.util.TreeSet<>();
        for (List<String> tags : tagMap.values()) {
            all.addAll(tags);
        }
        return new ArrayList<>(all);
    }

    /** 将标签应用到已扫描的模组列表（原地修改 ModMeta） */
    public synchronized void applyTags(List<ModMeta> mods) {
        if (mods == null) return;
        for (ModMeta mod : mods) {
            mod.setTags(getTags(mod.getJarFile()));
        }
    }

    // ===== 持久化 =====

    private void load() {
        try {
            if (!Files.exists(dataFile)) return;
            String content = new String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            tagMap.clear();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                String jar = e.getKey();
                List<String> tags = new ArrayList<>();
                if (e.getValue().isJsonArray()) {
                    for (com.google.gson.JsonElement elem : e.getValue().getAsJsonArray()) {
                        if (elem.isJsonPrimitive()) tags.add(elem.getAsString());
                    }
                }
                if (!tags.isEmpty()) tagMap.put(jar, tags);
            }
        } catch (Throwable ignored) {
            // 加载失败不阻断启动
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<String>> e : tagMap.entrySet()) {
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (String t : e.getValue()) arr.add(t);
                root.add(e.getKey(), arr);
            }
            Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            Files.write(tmp, gson.toJson(root).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, dataFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, dataFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[ModTagStore] 保存失败: " + e.getMessage());
        }
    }
}
