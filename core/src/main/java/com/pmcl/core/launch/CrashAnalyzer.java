package com.pmcl.core.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 游戏崩溃日志分析：扫描 crash-reports/ 与 latest.log，识别常见崩溃模式并给出建议。
 * <p>
 * 识别规则基于关键词匹配，不依赖外部服务。
 */
public final class CrashAnalyzer {

    public static final class CrashReport {
        private final Path file;
        private final String content;
        private final List<String> causes;
        private final List<String> suggestions;

        public CrashReport(Path file, String content, List<String> causes, List<String> suggestions) {
            this.file = file; this.content = content;
            this.causes = causes; this.suggestions = suggestions;
        }
        public Path getFile() { return file; }
        public String getContent() { return content; }
        public List<String> getCauses() { return causes; }
        public List<String> getSuggestions() { return suggestions; }
    }

    /** 扫描崩溃报告目录 */
    public List<CrashReport> scanReports(Path workDir) throws IOException {
        Path crashDir = workDir.resolve("crash-reports");
        List<CrashReport> result = new ArrayList<>();
        if (!Files.isDirectory(crashDir)) return result;
        try (Stream<Path> stream = Files.list(crashDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted((a, b) -> {
                        try {
                            return Long.compare(
                                    Files.getLastModifiedTime(b).toMillis(),
                                    Files.getLastModifiedTime(a).toMillis());
                        } catch (IOException e) { return 0; }
                    })
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            result.add(analyze(content, p));
                        } catch (IOException ignored) {}
                    });
        }
        return result;
    }

    public CrashReport analyze(String content, Path file) {
        List<String> causes = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        String lower = content.toLowerCase();

        // 内存不足
        if (lower.contains("outofmemoryerror") || lower.contains("out of memory")) {
            causes.add("内存不足 (OutOfMemoryError)");
            suggestions.add("在设置中增大 -Xmx 最大内存");
            suggestions.add("检查模组数量是否过多");
        }

        // OpenGL / 显卡驱动
        if (lower.contains("opengl") && (lower.contains("error") || lower.contains("exception"))) {
            causes.add("OpenGL 错误（通常为显卡驱动问题）");
            suggestions.add("更新显卡驱动到最新版");
            suggestions.add("使用 Java 8u51+ 或安装 OptiFine 以兼容旧显卡");
        }

        // 模组加载失败
        if (lower.contains("failed to load mod") || lower.contains("exception in thread \"main\"")) {
            causes.add("模组加载失败");
            suggestions.add("检查 mods 文件夹中模组与游戏版本的兼容性");
            suggestions.add("查看 Mod 列表页面的冲突检测");
        }

        // 模组冲突：NoSuchMethodError / NoClassDefFoundError / ClassCastException
        if (lower.contains("nosuchmethoderror")) {
            causes.add("方法不存在 (NoSuchMethodError) - 模组版本不匹配");
            suggestions.add("更新模组到当前游戏版本对应的版本");
        }
        if (lower.contains("noclassdeffounderror")) {
            causes.add("类未找到 (NoClassDefFoundError) - 缺少前置模组或版本不匹配");
            suggestions.add("检查模组依赖是否齐全");
        }

        // Forge 加载器问题
        if (lower.contains("failed to load datapacks") || lower.contains("datapack")) {
            causes.add("数据包加载失败");
            suggestions.add("尝试禁用最近添加的数据包");
        }

        // Java 版本不匹配
        if (lower.contains("unsupportedclassversionerror")) {
            causes.add("Java 版本不匹配 (UnsupportedClassVersionError)");
            suggestions.add("MC 1.17+ 需要 Java 17，1.16.5 及以下用 Java 8");
        }

        // LWJGL / native 库问题
        if (lower.contains("lwjgl") && lower.contains("exception")) {
            causes.add("LWJGL / native 库问题");
            suggestions.add("尝试在设置中切换 Java 版本");
            suggestions.add("检查 natives 目录是否完整");
        }

        // 文件未找到
        if (lower.contains("filenotfoundexception") && lower.contains(".jar")) {
            causes.add("库文件缺失 (FileNotFoundException)");
            suggestions.add("重新安装当前版本（删除 versions/{id} 目录后重试）");
        }

        // 默认：未识别
        if (causes.isEmpty()) {
            causes.add("未识别的崩溃模式");
            suggestions.add("查看崩溃日志详细内容寻找异常堆栈");
            suggestions.add("尝试在 mods 列表中禁用最近添加的 mod");
        }

        return new CrashReport(file, content, causes, suggestions);
    }
}
