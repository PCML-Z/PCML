package com.pmcl.core.ai;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import java.util.function.Consumer;

/**
 * AI 智能体管理器：管理 LLM 模型实例、AiServices 代理、会话记忆。
 * <p>
 * 支持通过 OpenAI 兼容 API 接入 DeepSeek 和 OpenAI。
 * 配置变更后调用 {@link #configure(AiConfig)} 重建模型实例。
 */
public final class AiManager {

    private final PmclTools tools;
    private volatile AiConfig config;
    private volatile ChatModel model;
    private volatile Assistant assistant;
    private volatile ChatMemory chatMemory;

    public AiManager(PmclTools tools) {
        this.tools = tools;
        this.config = AiConfig.load();
        if (!config.getApiKey().isEmpty()) {
            try {
                rebuild();
            } catch (Exception e) {
                System.err.println("[AiManager] 初始化模型失败: " + e.getMessage());
            }
        }
    }

    /**
     * 应用新配置并重建模型实例。
     * 如果 apiKey 为空则卸载模型（AI 功能不可用）。
     */
    public synchronized void configure(AiConfig newConfig) {
        this.config = newConfig;
        newConfig.save();
        if (!newConfig.getApiKey().isEmpty()) {
            rebuild();
        } else {
            model = null;
            assistant = null;
            chatMemory = null;
        }
    }

    /** 重建模型和 AiServices 代理 */
    private void rebuild() {
        chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
        model = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .build();
        assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(chatMemory)
                .tools(tools)
                .build();
    }

    /**
     * 同步发送消息给 AI 助手。
     * @return AI 回复文本；未配置时返回提示信息
     */
    public String chat(String message) {
        Assistant a = assistant;
        if (a == null) return "AI 未配置，请先在设置中填写 API Key";
        return a.chat(message);
    }

    /**
     * 异步发送消息。
     * @param onResponse 收到 AI 回复时回调（在后台线程）
     * @param onError    发生异常时回调
     */
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

    /** 清空会话记忆（开始新对话） */
    public void clearMemory() {
        ChatMemory mem = chatMemory;
        if (mem != null) mem.clear();
    }

    /** 设置工具执行状态回调 */
    public void setStatusCallback(Consumer<String> callback) {
        tools.setStatusCallback(callback);
    }
}
