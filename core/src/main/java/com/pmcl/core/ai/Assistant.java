package com.pmcl.core.ai;

import dev.langchain4j.data.message.UserMessage;

/**
 * AI 助手接口：由 LangChain4j 的 AiServices 动态代理实现。
 * <p>
 * 接受 {@link UserMessage} 参数，支持纯文本和多模态（文本+图片）输入。
 * 系统提示词通过 {@code AiServices.builder().systemMessageProvider()} 动态注入，
 * 不再使用静态 @SystemMessage 注解，以便运行时切换智能体角色。
 * <p>
 * 工具调用（@Tool）由注册到 ToolRegistry 的工具对象提供。
 */
public interface Assistant {

    String chat(UserMessage message);
}
