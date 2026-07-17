package com.pmcl.core.ai.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库：本地文档存储与关键词检索。
 * <p>
 * 文档持久化到 ~/.pmcl/knowledge/knowledge.json。
 * 检索使用 TF-IDF 简化版：按关键词在文档中的出现频率和文档长度归一化评分。
 * 可通过 {@link KnowledgeBaseRetriever} 接入 LangChain4j 的 RAG 流程。
 */
public class KnowledgeBase {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path KB_FILE = Paths.get(
            System.getProperty("user.home"), ".pmcl", "knowledge", "knowledge.json");

    private final Map<String, KnowledgeEntry> entries = new ConcurrentHashMap<>();

    public KnowledgeBase() {
        loadAll();
    }

    private void loadAll() {
        if (!Files.exists(KB_FILE)) return;
        try {
            String json = Files.readString(KB_FILE, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<KnowledgeEntry>>() {}.getType();
            List<KnowledgeEntry> list = GSON.fromJson(json, listType);
            if (list != null) {
                for (KnowledgeEntry e : list) {
                    if (e.getId() == null) e.setId("kb_" + System.nanoTime());
                    entries.put(e.getId(), e);
                }
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeBase] 加载失败: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(KB_FILE.getParent());
            List<KnowledgeEntry> list = new ArrayList<>(entries.values());
            Files.writeString(KB_FILE, GSON.toJson(list), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[KnowledgeBase] 保存失败: " + e.getMessage());
        }
    }

    /** 添加文档，返回生成的 ID */
    public String addDocument(String title, String content, List<String> tags) {
        String id = "kb_" + System.currentTimeMillis();
        KnowledgeEntry entry = new KnowledgeEntry(id, title, content);
        entry.setTags(tags);
        entries.put(id, entry);
        save();
        return id;
    }

    /** 更新已有文档 */
    public void updateDocument(String id, String title, String content, List<String> tags) {
        KnowledgeEntry entry = entries.get(id);
        if (entry != null) {
            entry.setTitle(title);
            entry.setContent(content);
            entry.setTags(tags);
            save();
        }
    }

    /** 删除文档 */
    public void removeDocument(String id) {
        entries.remove(id);
        save();
    }

    /** 获取所有文档 */
    public List<KnowledgeEntry> listAll() {
        return new ArrayList<>(entries.values());
    }

    /** 获取单个文档 */
    public KnowledgeEntry get(String id) {
        return entries.get(id);
    }

    /** 文档数量 */
    public int size() {
        return entries.size();
    }

    /**
     * 关键词检索：对查询进行分词，按 TF 评分排序返回 topK 文档。
     *
     * @param query      用户查询文本
     * @param maxResults 最多返回条数
     * @return 按相关度降序排列的文档列表
     */
    public List<KnowledgeEntry> search(String query, int maxResults) {
        if (query == null || query.isBlank() || entries.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return Collections.emptyList();

        List<Map.Entry<KnowledgeEntry, Double>> scored = new ArrayList<>();
        for (KnowledgeEntry entry : entries.values()) {
            double score = scoreDocument(entry, queryTerms);
            if (score > 0) {
                scored.add(new AbstractMap.SimpleEntry<>(entry, score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<KnowledgeEntry> result = new ArrayList<>();
        for (int i = 0; i < Math.min(maxResults, scored.size()); i++) {
            result.add(scored.get(i).getKey());
        }
        return result;
    }

    /**
     * 构建知识库上下文文本（用于注入到系统提示词或作为检索结果）。
     */
    public String buildContext(String query) {
        List<KnowledgeEntry> results = search(query, 3);
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n=== 知识库参考 ===\n");
        for (KnowledgeEntry e : results) {
            sb.append("[").append(e.getTitle()).append("]\n");
            // 截断过长的内容
            String content = e.getContent();
            if (content.length() > 800) {
                content = content.substring(0, 800) + "...";
            }
            sb.append(content).append("\n\n");
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // 内部方法
    // -----------------------------------------------------------------------

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) return tokens;
        // 按非字母数字字符分割（兼容中英文）
        String[] parts = text.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fa5]+");
        for (String p : parts) {
            if (p.length() >= 2) tokens.add(p);
        }
        return tokens;
    }

    private double scoreDocument(KnowledgeEntry entry, Set<String> queryTerms) {
        String text = (entry.getTitle() + " " + entry.getContent()).toLowerCase();
        if (text.isEmpty()) return 0;

        Set<String> docTerms = tokenize(text);
        int matches = 0;
        for (String term : queryTerms) {
            if (docTerms.contains(term)) matches++;
            // 部分匹配（子串包含）
            else if (text.contains(term)) matches += 0.5;
        }

        if (matches == 0) return 0;

        // TF 评分：匹配词数 / 查询词数，按文档长度归一化
        double tf = matches / (double) queryTerms.size();
        double lengthNorm = 1.0 / Math.sqrt(text.length()); // 短文档加权
        return tf * (1 + lengthNorm * 10);
    }
}
