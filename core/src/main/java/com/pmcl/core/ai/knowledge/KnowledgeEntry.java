package com.pmcl.core.ai.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文档条目：包含标题、内容、标签。
 */
public class KnowledgeEntry {

    private String id;
    private String title;
    private String content;
    private List<String> tags;
    private long createdAt;

    public KnowledgeEntry() {
        this.tags = new ArrayList<>();
    }

    public KnowledgeEntry(String id, String title, String content) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.tags = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
