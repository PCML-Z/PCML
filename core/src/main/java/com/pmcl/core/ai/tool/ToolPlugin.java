package com.pmcl.core.ai.tool;

/**
 * 工具插件接口：第三方可通过实现此接口扩展 AI 智能体的工具能力。
 * <p>
 * 实现类返回一个包含 {@code @Tool} 注解方法的对象，
 * 该对象会被注册到 LangChain4j 的 AiServices 中供 AI 调用。
 * <p>
 * 示例：
 * <pre>
 * public class WorldEditTools implements ToolPlugin {
 *     public String getName() { return "WorldEdit 工具"; }
 *     public String getDescription() { return "提供 WorldEdit 命令生成与解释"; }
 *     public Object getToolInstance() { return new WorldEditToolMethods(); }
 * }
 * </pre>
 */
public interface ToolPlugin {

    /** 插件名称 */
    String getName();

    /** 插件描述 */
    String getDescription();

    /**
     * 获取包含 @Tool 注解方法的对象实例。
     * 该实例会被传递给 {@code AiServices.builder().tools(Object...)}。
     */
    Object getToolInstance();
}
