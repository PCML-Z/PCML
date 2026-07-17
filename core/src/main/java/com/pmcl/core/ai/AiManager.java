package com.pmcl.core.ai;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 智能体管理器：管理 LLM 模型实例、AiServices 代理、多会话记忆。
 * <p>
 * 支持通过 OpenAI 兼容 API 接入 DeepSeek 和 OpenAI。
 * 配置变更后调用 {@link #configure(AiConfig)} 重建模型实例。
 * <p>
 * 会话模型：每个会话有独立的 ChatMemory，切换会话时重建 AiServices 绑定到对应 memory。
 */
public final class AiManager {

    private final PmclTools tools;
    private volatile AiConfig config;
    private volatile ChatModel model;
    private volatile Assistant assistant;

    /** 所有会话（id -> ChatMemory），按创建顺序保留 */
    private final Map<String, ChatMemory> sessions = new LinkedHashMap<>();
    /** 当前会话 id */
    private volatile String currentSessionId;
    /** 会话标题（id -> 标题，通常取首条用户消息前 30 字） */
    private final Map<String, String> sessionTitles = new LinkedHashMap<>();

    public AiManager(PmclTools tools) {
        this.tools = tools;
        this.config = AiConfig.load();
        if (!config.getApiKey().isEmpty()) {
            try {
                rebuildModel();
                createSession(); // 默认创建一个会话
            } catch (Exception e) {
                System.err.println("[AiManager] 初始化模型失败: " + e.getMessage());
            }
        }
    }

    /** 应用新配置并重建模型实例 */
    public synchronized void configure(AiConfig newConfig) {
        this.config = newConfig;
        newConfig.save();
        if (!newConfig.getApiKey().isEmpty()) {
            rebuildModel();
            // 重建后需要重新绑定当前会话的 assistant
            if (currentSessionId != null) {
                bindAssistant(currentSessionId);
            }
        } else {
            model = null;
            assistant = null;
        }
    }

    /** 重建模型实例（不触及会话内存） */
    private void rebuildModel() {
        model = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .build();
    }

    /** 为指定会话绑定 AiServices 代理 */
    private void bindAssistant(String sessionId) {
        ChatMemory mem = sessions.get(sessionId);
        if (mem == null) {
            mem = MessageWindowChatMemory.builder().maxMessages(20).build();
            sessions.put(sessionId, mem);
        }
        assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(mem)
                .tools(tools)
                .build();
    }

    /** 创建新会话，返回会话 id */
    public synchronized String createSession() {
        String id = "sess_" + System.currentTimeMillis();
        ChatMemory mem = MessageWindowChatMemory.builder().maxMessages(20).build();
        sessions.put(id, mem);
        sessionTitles.put(id, "新对话");
        currentSessionId = id;
        if (model != null) {
            bindAssistant(id);
        }
        return id;
    }

    /** 切换到指定会话 */
    public synchronized void switchSession(String sessionId) {
        if (!sessions.containsKey(sessionId)) return;
        currentSessionId = sessionId;
        if (model != null) {
            bindAssistant(sessionId);
        }
    }

    /** 删除指定会话 */
    public synchronized void deleteSession(String sessionId) {
        sessions.remove(sessionId);
        sessionTitles.remove(sessionId);
        if (currentSessionId != null && currentSessionId.equals(sessionId)) {
            if (sessions.isEmpty()) {
                createSession();
            } else {
                // 切换到第一个会话
                currentSessionId = sessions.keySet().iterator().next();
                if (model != null) bindAssistant(currentSessionId);
            }
        }
    }

    /** 获取所有会话列表（id -> 标题），按创建顺序 */
    public List<SessionInfo> listSessions() {
        List<SessionInfo> list = new ArrayList<>();
        sessions.forEach((id, mem) -> {
            String title = sessionTitles.getOrDefault(id, "新对话");
            int msgCount = mem.messages().size();
            list.add(new SessionInfo(id, title, msgCount));
        });
        return list;
    }

    /** 当前会话 id */
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    /** 设置会话标题（通常在首条消息后调用） */
    public void setSessionTitle(String sessionId, String title) {
        if (title != null && title.length() > 30) {
            title = title.substring(0, 30) + "…";
        }
        sessionTitles.put(sessionId, title);
    }

    /** 同步发送消息 */
    public String chat(String message) {
        Assistant a = assistant;
        if (a == null) return "AI 未配置，请先在设置中填写 API Key";

        // 如果是当前会话首条消息，用消息内容作为标题
        if (currentSessionId != null) {
            ChatMemory mem = sessions.get(currentSessionId);
            if (mem != null && mem.messages().isEmpty()) {
                setSessionTitle(currentSessionId, message);
            }
        }

        return a.chat(message);
    }

    /** 异步发送消息 */
    public void chatAsync(String message, Consumer<String> onResponse, Consumer<String> onError) {
        Thread t = new Thread(() -> {
            try {
                String response = chat(message);
                onResponse.accept(response);
            } catch (Exception e) {
                onError.accept(e.getMessage());
            }
        }, "PMCL-AI-Chat");
        t.setDaemon(true);
        t.start();
    }

    /** AI 是否已配置可用 */
    public boolean isConfigured() {
        return assistant != null;
    }

    /** 获取当前配置 */
    public AiConfig getConfig() {
        return config;
    }

    /** 清空当前会话记忆 */
    public void clearMemory() {
        if (currentSessionId != null) {
            ChatMemory mem = sessions.get(currentSessionId);
            if (mem != null) mem.clear();
        }
    }

    /** 设置工具执行状态回调 */
    public void setStatusCallback(Consumer<String> callback) {
        tools.setStatusCallback(callback);
    }

    /** 会话信息 */
    public static final class SessionInfo {
        public final String id;
        public final String title;
        public final int messageCount;
        public SessionInfo(String id, String title, int messageCount) {
            this.id = id;
            this.title = title;
            this.messageCount = messageCount;
        }
    }
}
