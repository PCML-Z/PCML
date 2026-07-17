package com.pmcl.core.ai;

import dev.langchain4j.service.SystemMessage;

/**
 * AI 助手接口：由 LangChain4j 的 AiServices 动态代理实现。
 * 工具调用（@Tool）由 {@link PmclTools} 提供。
 */
public interface Assistant {

    @SystemMessage("""
            你是 PMCL Minecraft 启动器的 AI 智能助手。
            你可以帮助用户搜索、下载、安装 Minecraft 模组和 Mod 加载器。

            工作流程：
            1. 当用户请求安装某个 Mod 时，先用 searchMods 搜索，获取模组 ID，再用 installMod 安装
            2. 当用户请求安装加载器时，先用 listModLoaderVersions 查询可用版本，再 installModLoader

            注意事项：
            - 用中文回复
            - 执行操作前简要说明你要做什么
            - 操作完成后报告结果
            - 如果信息不足（如游戏版本），主动询问用户
            """)
    String chat(String userMessage);
}
