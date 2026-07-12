package com.pmcl.core.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 游戏崩溃日志分析：扫描 crash-reports/ 与 latest.log，识别常见崩溃模式并给出建议。
 * <p>
 * 识别规则基于关键词匹配，不依赖外部服务。
 */
public final class CrashAnalyzer {

    /** 恢复操作类型：UI 根据此类型调用对应的 ViewModel 方法 */
    public enum RecoveryType {
        /** 增大最大内存 */
        INCREASE_MEMORY,
        /** 切换/重新指定 Java 路径 */
        SWITCH_JAVA,
        /** 检查模组冲突 */
        CHECK_MOD_CONFLICTS,
        /** 禁用最近添加的模组（移到 disabled 子目录） */
        DISABLE_RECENT_MODS,
        /** 校验版本完整性并自动补全缺失文件 */
        CHECK_INTEGRITY,
        /** 重新安装当前版本 */
        REINSTALL_VERSION,
        /** 清理游戏配置文件（options.txt / servers.dat 等可能损坏的文件） */
        CLEAR_GAME_CONFIG,
        /** 导出/分享日志 */
        SHARE_LOGS,
        /** 打开模组管理页面 */
        OPEN_MODS_PAGE,
        /** 打开设置页面 */
        OPEN_SETTINGS
    }

    /** 可执行的恢复操作：带类型标识，UI 据此触发对应动作 */
    public static final class RecoveryAction {
        private final RecoveryType type;
        private final String title;
        private final String description;

        public RecoveryAction(RecoveryType type, String title, String description) {
            this.type = type;
            this.title = title;
            this.description = description;
        }
        public RecoveryType getType() { return type; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
    }

    public static final class CrashReport {
        private final Path file;
        private final String content;
        private final List<String> causes;
        private final List<String> suggestions;
        private final List<RecoveryAction> recoveryActions;

        public CrashReport(Path file, String content, List<String> causes,
                           List<String> suggestions, List<RecoveryAction> recoveryActions) {
            this.file = file; this.content = content;
            this.causes = causes; this.suggestions = suggestions;
            this.recoveryActions = recoveryActions;
        }
        public Path getFile() { return file; }
        public String getContent() { return content; }
        public List<String> getCauses() { return causes; }
        public List<String> getSuggestions() { return suggestions; }
        public List<RecoveryAction> getRecoveryActions() { return recoveryActions; }
    }

    /** 扫描崩溃报告目录 */
    public List<CrashReport> scanReports(Path workDir) throws IOException {
        Path crashDir = workDir.resolve("crash-reports");
        List<CrashReport> result = new ArrayList<>();
        if (!Files.isDirectory(crashDir)) return result;
        try (Stream<Path> stream = Files.list(crashDir)) {
            List<Path> txtFiles = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".txt"))
                  .forEach(txtFiles::add);
            // 预取 mtime，避免在排序比较器中重复 stat（O(n log n) → O(n)）
            Map<Path, Long> mtimeCache = new HashMap<>();
            for (Path p : txtFiles) {
                try {
                    mtimeCache.put(p, Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {}
            }
            txtFiles.sort((a, b) -> {
                Long ma = mtimeCache.get(a);
                Long mb = mtimeCache.get(b);
                if (ma == null || mb == null) return 0;
                return Long.compare(mb, ma);
            });
            for (Path p : txtFiles) {
                try {
                    String content = Files.readString(p);
                    result.add(analyze(content, p));
                } catch (IOException ignored) {}
            }
        }
        return result;
    }

    public CrashReport analyze(String content, Path file) {
        List<String> causes = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        List<RecoveryAction> actions = new ArrayList<>();

        String lower = content.toLowerCase();

        // 内存不足
        if (lower.contains("outofmemoryerror") || lower.contains("out of memory")) {
            causes.add("内存不足 (OutOfMemoryError)");
            suggestions.add("在设置中增大 -Xmx 最大内存");
            suggestions.add("检查模组数量是否过多");
            actions.add(new RecoveryAction(RecoveryType.INCREASE_MEMORY,
                    "自动增大内存", "将 -Xmx 调高 1024MB 并重新启动"));
            actions.add(new RecoveryAction(RecoveryType.DISABLE_RECENT_MODS,
                    "禁用最近模组", "将最近添加的模组移到 disabled 子目录以减少内存占用"));
        }

        // OpenGL / 显卡驱动
        if (lower.contains("opengl") && (lower.contains("error") || lower.contains("exception"))) {
            causes.add("OpenGL 错误（通常为显卡驱动问题）");
            suggestions.add("更新显卡驱动到最新版");
            suggestions.add("使用 Java 8u51+ 或安装 OptiFine 以兼容旧显卡");
            actions.add(new RecoveryAction(RecoveryType.OPEN_SETTINGS,
                    "打开设置", "在设置中检查图形相关选项"));
        }

        // 模组加载失败
        if (lower.contains("failed to load mod") || lower.contains("exception in thread \"main\"")) {
            causes.add("模组加载失败");
            suggestions.add("检查 mods 文件夹中模组与游戏版本的兼容性");
            suggestions.add("查看 Mod 列表页面的冲突检测");
            actions.add(new RecoveryAction(RecoveryType.CHECK_MOD_CONFLICTS,
                    "检查模组冲突", "扫描已安装模组并列出冲突项"));
            actions.add(new RecoveryAction(RecoveryType.DISABLE_RECENT_MODS,
                    "禁用最近模组", "将最近添加的模组暂时移出 mods 目录"));
            actions.add(new RecoveryAction(RecoveryType.OPEN_MODS_PAGE,
                    "打开模组管理", "跳转到模组页面查看详情"));
        }

        // 模组冲突：NoSuchMethodError / NoClassDefFoundError / ClassCastException
        if (lower.contains("nosuchmethoderror")) {
            causes.add("方法不存在 (NoSuchMethodError) - 模组版本不匹配");
            suggestions.add("更新模组到当前游戏版本对应的版本");
            actions.add(new RecoveryAction(RecoveryType.CHECK_MOD_CONFLICTS,
                    "检查模组冲突", "检测重复或版本不匹配的模组"));
            actions.add(new RecoveryAction(RecoveryType.OPEN_MODS_PAGE,
                    "打开模组管理", "手动更新或移除问题模组"));
        }
        if (lower.contains("noclassdeffounderror")) {
            causes.add("类未找到 (NoClassDefFoundError) - 缺少前置模组或版本不匹配");
            suggestions.add("检查模组依赖是否齐全");
            actions.add(new RecoveryAction(RecoveryType.CHECK_MOD_CONFLICTS,
                    "检查模组冲突", "检测缺失的前置模组"));
            actions.add(new RecoveryAction(RecoveryType.OPEN_MODS_PAGE,
                    "打开模组管理", "安装缺失的前置模组"));
        }

        // Forge 加载器问题
        if (lower.contains("failed to load datapacks") || lower.contains("datapack")) {
            causes.add("数据包加载失败");
            suggestions.add("尝试禁用最近添加的数据包");
            actions.add(new RecoveryAction(RecoveryType.CLEAR_GAME_CONFIG,
                    "清理游戏配置", "备份并重置可能损坏的数据包/配置文件"));
        }

        // Java 版本不匹配
        if (lower.contains("unsupportedclassversionerror")) {
            causes.add("Java 版本不匹配 (UnsupportedClassVersionError)");
            suggestions.add("MC 1.17+ 需要 Java 17，1.16.5 及以下用 Java 8");
            actions.add(new RecoveryAction(RecoveryType.SWITCH_JAVA,
                    "切换 Java 版本", "为当前版本指定正确版本的 Java 路径"));
        }

        // LWJGL / native 库问题
        if (lower.contains("lwjgl") && lower.contains("exception")) {
            causes.add("LWJGL / native 库问题");
            suggestions.add("尝试在设置中切换 Java 版本");
            suggestions.add("检查 natives 目录是否完整");
            actions.add(new RecoveryAction(RecoveryType.SWITCH_JAVA,
                    "切换 Java 版本", "尝试使用 x86_64 Java（Apple Silicon 需 Rosetta）"));
            actions.add(new RecoveryAction(RecoveryType.CHECK_INTEGRITY,
                    "校验版本完整性", "重新下载缺失或损坏的 native 库文件"));
        }

        // 文件未找到
        if (lower.contains("filenotfoundexception") && lower.contains(".jar")) {
            causes.add("库文件缺失 (FileNotFoundException)");
            suggestions.add("重新安装当前版本（删除 versions/{id} 目录后重试）");
            actions.add(new RecoveryAction(RecoveryType.CHECK_INTEGRITY,
                    "校验并修复版本", "自动下载缺失的库文件"));
            actions.add(new RecoveryAction(RecoveryType.REINSTALL_VERSION,
                    "重新安装版本", "删除当前版本目录后重新下载安装"));
        }

        // 崩溃报告未生成但进程异常退出：始终提供日志分享
        if (actions.isEmpty() && causes.isEmpty()) {
            causes.add("未识别的崩溃模式");
            suggestions.add("查看崩溃日志详细内容寻找异常堆栈");
            suggestions.add("尝试在 mods 列表中禁用最近添加的 mod");
            actions.add(new RecoveryAction(RecoveryType.SHARE_LOGS,
                    "分享日志", "上传日志到 paste.gg 以便寻求帮助"));
            actions.add(new RecoveryAction(RecoveryType.DISABLE_RECENT_MODS,
                    "禁用最近模组", "将最近添加的模组暂时移出 mods 目录"));
        }

        // 所有崩溃都适用的通用操作（去重后追加）
        if (!actions.stream().anyMatch(a -> a.getType() == RecoveryType.SHARE_LOGS)) {
            actions.add(new RecoveryAction(RecoveryType.SHARE_LOGS,
                    "分享日志", "上传日志到 paste.gg 以便寻求帮助"));
        }
        if (!actions.stream().anyMatch(a -> a.getType() == RecoveryType.CHECK_INTEGRITY)) {
            actions.add(new RecoveryAction(RecoveryType.CHECK_INTEGRITY,
                    "校验版本完整性", "检查并修复缺失或损坏的游戏文件"));
        }

        return new CrashReport(file, content, causes, suggestions, actions);
    }
}
