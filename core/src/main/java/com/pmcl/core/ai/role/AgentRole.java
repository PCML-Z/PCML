package com.pmcl.core.ai.role;

/**
 * 智能体角色定义：包含角色名称、描述、系统提示词和图标。
 * 内置角色不可删除，用户可自定义角色。
 */
public class AgentRole {

    private String id;
    private String name;
    private String description;
    private String systemPrompt;
    private String icon;
    private boolean builtIn;

    public AgentRole() {}

    public AgentRole(String id, String name, String description, String systemPrompt, String icon, boolean builtIn) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.icon = icon;
        this.builtIn = builtIn;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }
}
