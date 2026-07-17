package com.pmcl.core.ai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.TokenStream;

/**
 * 流式输出 AI 助手接口：由 LangChain4j AiServices 动态代理实现。
 * <p>
 * 返回 {@link TokenStream}，调用方通过链式回调接收逐 token 输出：
 * <pre>
 * TokenStream stream = assistant.chat(UserMessage.from("你好"));
 * stream.onPartialResponse(token -> appendToUi(token))
 *       .onCompleteResponse(response -> onFinished())
 *       .onError(error -> onError(error.getMessage()))
 *       .start();
 * </pre>
 * 系统提示词通过 {@code AiServices.builder().systemMessageProvider()} 动态注入。
 */
public interface StreamingAssistant {

    TokenStream chat(UserMessage message);
}
