package com.pmcl.core.ai.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板：支持 ${variable} 变量替换。
 * <p>
 * 示例：
 * <pre>
 * PromptTemplate tpl = new PromptTemplate("你好 ${name}，欢迎使用 ${product}");
 * String result = tpl.render(Map.of("name", "玩家", "product", "PMCL"));
 * // → "你好 玩家，欢迎使用 PMCL"
 * </pre>
 */
public class PromptTemplate {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)}");

    private final String template;

    public PromptTemplate(String template) {
        this.template = template;
    }

    /**
     * 用给定变量渲染模板。未提供的变量保持原样。
     */
    public String render(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) return template;
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = variables.getOrDefault(key, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public String getTemplate() { return template; }

    @Override
    public String toString() { return template; }
}
