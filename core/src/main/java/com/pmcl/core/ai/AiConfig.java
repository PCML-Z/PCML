package com.pmcl.core.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * AI 模型配置：支持 DeepSeek 和 OpenAI（均使用 OpenAI 兼容 API）。
 * 配置持久化到 ~/.pmcl/ai-config.json。
 */
public final class AiConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = Paths.get(
            System.getProperty("user.home"), ".pmcl", "ai-config.json");

    public enum Provider { DEEPSEEK, OPENAI, CUSTOM }

    private Provider provider = Provider.DEEPSEEK;
    private String apiKey = "";
    private String modelName = "deepseek-chat";
    private String baseUrl = "https://api.deepseek.com/v1";

    public static AiConfig deepseekDefault() {
        AiConfig c = new AiConfig();
        c.provider = Provider.DEEPSEEK;
        c.modelName = "deepseek-chat";
        c.baseUrl = "https://api.deepseek.com/v1";
        return c;
    }

    public static AiConfig openaiDefault() {
        AiConfig c = new AiConfig();
        c.provider = Provider.OPENAI;
        c.modelName = "gpt-4o-mini";
        c.baseUrl = "https://api.openai.com/v1";
        return c;
    }

    /** 从磁盘加载配置，文件不存在则返回 DeepSeek 默认配置 */
    public static AiConfig load() {
        if (!Files.exists(CONFIG_FILE)) return deepseekDefault();
        try {
            String json = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            AiConfig loaded = GSON.fromJson(json, AiConfig.class);
            return loaded != null ? loaded : deepseekDefault();
        } catch (Exception e) {
            System.err.println("[AiConfig] 加载配置失败: " + e.getMessage());
            return deepseekDefault();
        }
    }

    /** 保存配置到磁盘（原子写入） */
    public void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Path tmp = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, CONFIG_FILE, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[AiConfig] 保存配置失败: " + e.getMessage());
        }
    }

    // --- getters / setters ---

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey != null ? apiKey : ""; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /** 切换到 DeepSeek 默认配置（保留 apiKey） */
    public void applyDeepseekDefaults() {
        this.provider = Provider.DEEPSEEK;
        this.modelName = "deepseek-chat";
        this.baseUrl = "https://api.deepseek.com/v1";
    }

    /** 切换到 OpenAI 默认配置（保留 apiKey） */
    public void applyOpenaiDefaults() {
        this.provider = Provider.OPENAI;
        this.modelName = "gpt-4o-mini";
        this.baseUrl = "https://api.openai.com/v1";
    }
}
