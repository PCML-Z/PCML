package com.pmcl.core.ai.role;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色管理器：管理内置角色和用户自定义角色。
 * 自定义角色持久化到 ~/.pmcl/ai-roles.json
 */
public class RoleManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path ROLES_FILE = Paths.get(
            System.getProperty("user.home"), ".pmcl", "ai-roles.json");

    private final List<AgentRole> roles = new ArrayList<>();
    private AgentRole currentRole;

    public RoleManager() {
        loadBuiltinRoles();
        loadCustomRoles();
        if (roles.isEmpty()) {
            roles.add(createDefaultRole());
        }
        currentRole = roles.get(0);
    }

    private void loadBuiltinRoles() {
        roles.add(new AgentRole(
                "assistant", "智能助手",
                "通用 AI 助手，支持模组搜索安装、技术问题排查",
                """
                你是 PMCL Minecraft 启动器的 AI 智能助手。
                你可以帮助用户搜索、下载、安装 Minecraft 模组和 Mod 加载器。

                工作流程：
                1. 当用户请求安装某个 Mod 时，先用 searchMods 搜索，获取模组 ID，再用 installMod 安装
                2. 当用户请求安装加载器时，先用 listModLoaderVersions 查询可用版本，再 installModLoader
                3. 复杂任务请先拆解为子步骤，逐步执行并汇报进度

                注意事项：
                - 用中文回复
                - 执行操作前简要说明你要做什么
                - 操作完成后报告结果
                - 如果信息不足（如游戏版本），主动询问用户
                - 如果用户上传了图片（如崩溃截图），请分析图片内容并给出建议
                """,
                "smart_toy", true));

        roles.add(new AgentRole(
                "mod_expert", "模组专家",
                "精通 Minecraft 模组兼容性与搭配推荐",
                """
                你是一位 Minecraft 模组专家，对各类模组的功能、兼容性、版本要求了如指掌。
                你可以推荐模组搭配方案，分析模组冲突，提供优化建议。

                回答要点：
                - 推荐模组时说明功能特点、性能影响和兼容性
                - 提醒可能的模组冲突
                - 根据用户的游戏版本和加载器给出精确建议
                - 可以使用 searchMods 工具查找具体模组
                """,
                "inventory_2", true));

        roles.add(new AgentRole(
                "tech_support", "技术支持",
                "诊断启动问题、崩溃日志分析、Java 兼容性",
                """
                你是 PMCL 启动器的技术支持专家。
                你擅长诊断 Minecraft 启动问题、崩溃日志分析、Java 版本兼容性、内存优化等。

                诊断流程：
                1. 询问或分析崩溃日志/截图
                2. 定位问题根因（Java 版本不匹配、模组冲突、内存不足等）
                3. 给出具体解决步骤
                4. 如需安装/更新加载器，使用相应工具

                回答要结构化：问题分析 → 解决方案 → 操作步骤
                """,
                "build", true));

        roles.add(new AgentRole(
                "creative", "创意顾问",
                "建筑设计、红石电路、生存策略创意指导",
                """
                你是一位 Minecraft 创意顾问，擅长建筑设计、红石电路设计、生存模式策略等。
                你可以提供建筑灵感、红石教程、生存技巧等内容。

                回答风格：
                - 生动有趣，富有创造力
                - 提供具体的方块材料和尺寸建议
                - 必要时可以用文字绘制简单示意图
                - 鼓励用户尝试新想法
                """,
                "palette", true));
    }

    private AgentRole createDefaultRole() {
        return new AgentRole(
                "default", "助手", "默认助手",
                "你是一个乐于助人的 AI 助手。", "smart_toy", true);
    }

    private void loadCustomRoles() {
        if (!Files.exists(ROLES_FILE)) return;
        try {
            String json = Files.readString(ROLES_FILE, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<AgentRole>>() {}.getType();
            List<AgentRole> custom = GSON.fromJson(json, listType);
            if (custom != null) {
                for (AgentRole r : custom) {
                    r.setBuiltIn(false);
                    roles.add(r);
                }
            }
        } catch (Exception e) {
            System.err.println("[RoleManager] 加载自定义角色失败: " + e.getMessage());
        }
    }

    public void saveCustomRoles() {
        try {
            List<AgentRole> custom = new ArrayList<>();
            for (AgentRole r : roles) {
                if (!r.isBuiltIn()) custom.add(r);
            }
            Files.createDirectories(ROLES_FILE.getParent());
            Files.writeString(ROLES_FILE, GSON.toJson(custom), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[RoleManager] 保存自定义角色失败: " + e.getMessage());
        }
    }

    public List<AgentRole> getRoles() { return roles; }

    public AgentRole getCurrentRole() { return currentRole; }

    public void setCurrentRole(AgentRole role) {
        if (role != null) this.currentRole = role;
    }

    public AgentRole getRoleById(String id) {
        for (AgentRole r : roles) {
            if (r.getId().equals(id)) return r;
        }
        return null;
    }

    public void addCustomRole(AgentRole role) {
        role.setBuiltIn(false);
        roles.add(role);
        saveCustomRoles();
    }

    public void removeCustomRole(String id) {
        roles.removeIf(r -> r.getId().equals(id) && !r.isBuiltIn());
        saveCustomRoles();
        if (currentRole != null && currentRole.getId().equals(id) && !roles.isEmpty()) {
            currentRole = roles.get(0);
        }
    }
}
