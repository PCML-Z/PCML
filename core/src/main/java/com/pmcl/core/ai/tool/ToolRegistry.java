package com.pmcl.core.ai.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具注册表：管理所有已注册的工具插件和工具对象。
 * <p>
 * 使用方式：
 * <pre>
 * ToolRegistry registry = new ToolRegistry();
 * registry.register(new PmclToolPlugin(...));       // 注册插件
 * registry.registerObject(new MyCustomTools());      // 直接注册工具对象
 *
 * // 传递给 AiServices
 * AiServices.builder(Assistant.class)
 *     .tools(registry.getToolObjects())
 *     ...
 * </pre>
 */
public class ToolRegistry {

    private final List<ToolPlugin> plugins = new ArrayList<>();
    private final List<Object> toolObjects = new ArrayList<>();

    /** 注册工具插件 */
    public void register(ToolPlugin plugin) {
        plugins.add(plugin);
        if (plugin.getToolInstance() != null) {
            toolObjects.add(plugin.getToolInstance());
        }
    }

    /** 直接注册工具对象（包含 @Tool 方法） */
    public void registerObject(Object toolObject) {
        if (toolObject != null) {
            toolObjects.add(toolObject);
        }
    }

    /** 获取所有工具对象数组（用于传递给 AiServices.builder().tools()） */
    public Object[] getToolObjects() {
        return toolObjects.toArray();
    }

    /** 获取所有已注册插件信息 */
    public List<ToolPlugin> getPlugins() {
        return Collections.unmodifiableList(plugins);
    }

    /** 已注册工具数量 */
    public int size() {
        return toolObjects.size();
    }

    /** 清除所有注册 */
    public void clear() {
        plugins.clear();
        toolObjects.clear();
    }
}
