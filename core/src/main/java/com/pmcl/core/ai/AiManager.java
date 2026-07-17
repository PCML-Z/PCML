package com.pmcl.core.ai;

import com.pmcl.core.ai.knowledge.KnowledgeBase;
import com.pmcl.core.ai.role.AgentRole;
import com.pmcl.core.ai.role.RoleManager;
import com.pmcl.core.ai.tool.ToolRegistry;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 智能体管理器 v2：集成角色系统、知识库 RAG、流式输出、多模态输入、工具插件。
 * <p>
 * 核心能力：
 * <ul>
 *   <li>多轮上下文记忆（每会话独立 ChatMemory，最大 50 条）</li>
 *   <li>可配置智能体角色（RoleManager + 动态 SystemMessageProvider）</li>
 *   <li>知识库检索增强（KnowledgeBase + 关键词 RAG）</li>
 *   <li>流式输出（StreamingChatModel + TokenStream 逐 token 回调）</li>
 *   <li>多模态输入（文本 + 图片，需 vision 模型支持）</li>
 *   <li>可扩展工具插件（ToolRegistry + @Tool 注解）</li>
 *   <li>任务规划与执行（系统提示词内置规划指令）</li>
 * </ul>
 */
public final class AiManager {

    private final ToolRegistry toolRegistry;
    private final RoleManager roleManager;
    private final KnowledgeBase knowledgeBase;

    private volatile AiConfig config;
    private volatile ChatModel model;
    private volatile StreamingChatModel streamingModel;
    private volatile Assistant assistant;
    private volatile StreamingAssistant streamingAssistant;

    /** 所有会话（id -> ChatMemory），按创建顺序保留 */
    private final Map<String, ChatMemory> sessions = new LinkedHashMap<>();
    /** 当前会话 id */
    private volatile String currentSessionId;
    /** 会话标题（id -> 标题） */
    private final Map<String, String> sessionTitles = new LinkedHashMap<>();

    private final PmclTools tools;

    public AiManager(PmclTools tools) {
        this.tools = tools;
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.registerObject(tools);
        this.roleManager = new RoleManager();
        this.knowledgeBase = new KnowledgeBase();
        this.config = AiConfig.load();

        if (!config.getApiKey().isEmpty()) {
            try {
                rebuildModels();
                createSession();
            } catch (Exception e) {
                System.err.println("[AiManager] 初始化模型失败: " + e.getMessage());
            }
        }
    }

    // ============================================================
    // 配置与模型重建
    // ============================================================

    /** 应用新配置并重建模型实例 */
    public synchronized void configure(AiConfig newConfig) {
        this.config = newConfig;
        newConfig.save();
        if (!newConfig.getApiKey().isEmpty()) {
            rebuildModels();
            if (currentSessionId != null) {
                bindAssistant(currentSessionId);
            }
        } else {
            model = null;
            streamingModel = null;
            assistant = null;
            streamingAssistant = null;
        }
    }

    /** 重建同步和流式模型实例 */
    private void rebuildModels() {
        model = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .build();

        if (config.isStreamingEnabled()) {
            try {
                streamingModel = OpenAiStreamingChatModel.builder()
                        .baseUrl(config.getBaseUrl())
                        .apiKey(config.getApiKey())
                        .modelName(config.getModelName())
                        .temperature(config.getTemperature())
                        .maxTokens(config.getMaxTokens())
                        .build();
            } catch (Exception e) {
                System.err.println("[AiManager] 流式模型初始化失败，回退到非流式: " + e.getMessage());
                streamingModel = null;
            }
        } else {
            streamingModel = null;
        }
    }

    /** 为指定会话绑定 AiServices 代理（同步 + 流式） */
    private void bindAssistant(String sessionId) {
        ChatMemory mem = sessions.get(sessionId);
        if (mem == null) {
            mem = MessageWindowChatMemory.builder().maxMessages(50).build();
            sessions.put(sessionId, mem);
        }

        String systemPrompt = buildSystemPrompt();
        Object[] toolObjects = toolRegistry.getToolObjects();

        assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(mem)
                .tools(toolObjects)
                .systemMessageProvider(memoryId -> systemPrompt)
                .build();

        if (streamingModel != null) {
            try {
                streamingAssistant = AiServices.builder(StreamingAssistant.class)
                        .streamingChatModel(streamingModel)
                        .chatMemory(mem)
                        .tools(toolObjects)
                        .systemMessageProvider(memoryId -> systemPrompt)
                        .build();
            } catch (Exception e) {
                System.err.println("[AiManager] 流式助手绑定失败: " + e.getMessage());
                streamingAssistant = null;
            }
        }
    }

    /** 构建系统提示词：角色提示词 + 任务规划指令 */
    private String buildSystemPrompt() {
        AgentRole role = roleManager.getCurrentRole();
        String prompt = role.getSystemPrompt();

        // 追加任务规划能力指令
        prompt += "\n\n--- 任务规划 ---\n"
                + "处理复杂任务时，请先拆解为子步骤，逐步执行并汇报进度。\n"
                + "每次只执行一个子任务，等待结果后再继续下一步。";

        return prompt;
    }

    /** 切换角色后重建助手代理 */
    public synchronized void switchRole(AgentRole role) {
        roleManager.setCurrentRole(role);
        if (currentSessionId != null && model != null) {
            bindAssistant(currentSessionId);
        }
    }

    // ============================================================
    // 消息构建（支持多模态）
    // ============================================================

    /** 构建用户消息（纯文本或多模态） */
    private UserMessage buildUserMessage(String text, List<String> imageBase64List) {
        if (imageBase64List == null || imageBase64List.isEmpty()) {
            return UserMessage.from(text);
        }
        // 多模态：文本 + 图片
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(text));
        for (String base64 : imageBase64List) {
            contents.add(ImageContent.from(base64, "image/png"));
        }
        return UserMessage.from(contents.toArray(new Content[0]));
    }

    /** RAG 检索：将知识库相关内容注入用户消息 */
    private String enrichWithKnowledge(String text) {
        String context = knowledgeBase.buildContext(text);
        if (context.isEmpty()) return text;
        return text + context;
    }

    // ============================================================
    // 对话（同步 / 异步 / 流式）
    // ============================================================

    /** 同步对话（纯文本） */
    public String chat(String message) {
        return chat(message, null);
    }

    /** 同步对话（支持图片） */
    public String chat(String message, List<String> images) {
        Assistant a = assistant;
        if (a == null) return "AI 未配置，请先在设置中填写 API Key";

        if (currentSessionId != null) {
            ChatMemory mem = sessions.get(currentSessionId);
            if (mem != null && mem.messages().isEmpty()) {
                setSessionTitle(currentSessionId, message);
            }
        }

        String enrichedText = enrichWithKnowledge(message);
        UserMessage msg = buildUserMessage(enrichedText, images);
        return a.chat(msg);
    }

    /** 异步对话（纯文本） */
    public void chatAsync(String message, Consumer<String> onResponse, Consumer<String> onError) {
        chatAsync(message, null, onResponse, onError);
    }

    /** 异步对话（支持图片） */
    public void chatAsync(String message, List<String> images,
                          Consumer<String> onResponse, Consumer<String> onError) {
        Thread t = new Thread(() -> {
            try {
                String response = chat(message, images);
                onResponse.accept(response);
            } catch (Exception e) {
                onError.accept(e.getMessage());
            }
        }, "PMCL-AI-Chat");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 流式对话：逐 token 回调，支持多模态输入。
     * <p>
     * 如果流式模型不可用，自动回退到非流式异步对话。
     *
     * @param message     用户文本消息
     * @param images      Base64 编码的图片列表（可为 null 或空）
     * @param onToken     每收到一个 token 时回调
     * @param onComplete  对话完成时回调（传完整文本）
     * @param onError     出错时回调
     */
    public void chatStream(String message, List<String> images,
                           Consumer<String> onToken, Consumer<String> onComplete,
                           Consumer<String> onError) {
        StreamingAssistant sa = streamingAssistant;
        if (sa == null) {
            // 回退到非流式
            chatAsync(message, images, onComplete, onError);
            return;
        }

        if (currentSessionId != null) {
            ChatMemory mem = sessions.get(currentSessionId);
            if (mem != null && mem.messages().isEmpty()) {
                setSessionTitle(currentSessionId, message);
            }
        }

        String enrichedText = enrichWithKnowledge(message);
        UserMessage msg = buildUserMessage(enrichedText, images);

        StringBuilder fullResponse = new StringBuilder();
        try {
            TokenStream stream = sa.chat(msg);
            stream.onPartialResponse(token -> {
                    fullResponse.append(token);
                    onToken.accept(token);
                })
                .onCompleteResponse(response -> onComplete.accept(fullResponse.toString()))
                .onError(error -> onError.accept(error.getMessage()))
                .start();
        } catch (Exception e) {
            onError.accept(e.getMessage());
        }
    }

    // ============================================================
    // 会话管理
    // ============================================================

    public synchronized String createSession() {
        String id = "sess_" + System.currentTimeMillis();
        ChatMemory mem = MessageWindowChatMemory.builder().maxMessages(50).build();
        sessions.put(id, mem);
        sessionTitles.put(id, "新对话");
        currentSessionId = id;
        if (model != null) {
            bindAssistant(id);
        }
        return id;
    }

    public synchronized void switchSession(String sessionId) {
        if (!sessions.containsKey(sessionId)) return;
        currentSessionId = sessionId;
        if (model != null) {
            bindAssistant(sessionId);
        }
    }

    public synchronized void deleteSession(String sessionId) {
        sessions.remove(sessionId);
        sessionTitles.remove(sessionId);
        if (currentSessionId != null && currentSessionId.equals(sessionId)) {
            if (sessions.isEmpty()) {
                createSession();
            } else {
                currentSessionId = sessions.keySet().iterator().next();
                if (model != null) bindAssistant(currentSessionId);
            }
        }
    }

    public List<SessionInfo> listSessions() {
        List<SessionInfo> list = new ArrayList<>();
        sessions.forEach((id, mem) -> {
            String title = sessionTitles.getOrDefault(id, "新对话");
            int msgCount = mem.messages().size();
            list.add(new SessionInfo(id, title, msgCount));
        });
        return list;
    }

    public String getCurrentSessionId() { return currentSessionId; }

    public void setSessionTitle(String sessionId, String title) {
        if (title != null && title.length() > 30) {
            title = title.substring(0, 30) + "…";
        }
        sessionTitles.put(sessionId, title);
    }

    // ============================================================
    // 状态查询
    // ============================================================

    public boolean isConfigured() {
        return assistant != null;
    }

    public boolean isStreamingAvailable() {
        return streamingAssistant != null;
    }

    public boolean isVisionSupported() {
        return config != null && config.isVisionEnabled();
    }

    public AiConfig getConfig() { return config; }

    // ============================================================
    // 子系统访问
    // ============================================================

    public RoleManager getRoleManager() { return roleManager; }
    public KnowledgeBase getKnowledgeBase() { return knowledgeBase; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }

    /** 获取当前角色 */
    public AgentRole getCurrentRole() { return roleManager.getCurrentRole(); }

    // ============================================================
    // 操作
    // ============================================================

    public void clearMemory() {
        if (currentSessionId != null) {
            ChatMemory mem = sessions.get(currentSessionId);
            if (mem != null) mem.clear();
        }
    }

    public void setStatusCallback(Consumer<String> callback) {
        tools.setStatusCallback(callback);
    }

    // ============================================================
    // 会话信息
    // ============================================================

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
