package com.pmcl.core.i18n;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 启动器国际化。
 * <p>
 * 当前支持：zh_CN（默认）、en_US。
 * 翻译以代码内 Map 形式存储，避免外部资源加载复杂度。
 * <p>
 * 用法：{@code I18n.setLocale(Locale.US); I18n.t("launch.start")}
 */
public final class I18n {

    public static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;
    public static final Locale EN_US = Locale.US;

    private static volatile Locale current = ZH_CN;

    private static final Map<String, String> ZH = new LinkedHashMap<>();
    private static final Map<String, String> EN = new LinkedHashMap<>();

    static {
        // ===== 通用 =====
        ZH.put("app.title", "PMCL 启动器");
        ZH.put("common.refresh", "刷新");
        ZH.put("common.delete", "删除");
        ZH.put("common.backup", "备份");
        ZH.put("common.cancel", "取消");
        ZH.put("common.confirm", "确认");
        ZH.put("common.ok", "确定");
        ZH.put("common.status", "状态");
        ZH.put("common.loading", "加载中…");

        // ===== 导航 =====
        ZH.put("nav.launch", "启动");
        ZH.put("nav.download", "下载");
        ZH.put("nav.mods", "模组");
        ZH.put("nav.market", "市场");
        ZH.put("nav.worlds", "世界");
        ZH.put("nav.screenshots", "截图");
        ZH.put("nav.accounts", "账号");
        ZH.put("nav.settings", "设置");

        // ===== 启动页 =====
        ZH.put("launch.title", "启动游戏");
        ZH.put("launch.select_version", "选择版本");
        ZH.put("launch.start", "启动");
        ZH.put("launch.no_version", "请先选择版本");
        ZH.put("launch.no_account", "请先登录账号");
        ZH.put("launch.running", "游戏已在运行中");
        ZH.put("launch.starting", "启动中…");
        ZH.put("launch.failed", "启动失败");
        ZH.put("launch.system_info", "系统信息");

        // ===== 下载页 =====
        ZH.put("download.refresh", "刷新版本列表");
        ZH.put("download.install", "安装");
        ZH.put("download.installing", "安装中…");
        ZH.put("download.install_done", "安装完成");
        ZH.put("download.install_failed", "安装失败");
        ZH.put("download.local_versions", "已安装版本");

        // ===== 模组页 =====
        ZH.put("mods.installed", "已安装模组");
        ZH.put("mods.conflicts", "冲突检测");
        ZH.put("mods.no_mods", "暂无模组。安装的模组会显示在这里。");
        ZH.put("mods.scan_done", "已扫描 {0} 个模组");

        // ===== 市场页 =====
        ZH.put("market.search", "搜索");
        ZH.put("market.query_placeholder", "搜索模组…");
        ZH.put("market.game_version", "游戏版本");
        ZH.put("market.loader", "加载器");
        ZH.put("market.any", "任意");
        ZH.put("market.files", "文件列表");
        ZH.put("market.download", "下载");
        ZH.put("market.curseforge_disabled", "CurseForge 未启用（未设置 API Key）");

        // ===== 世界页 =====
        ZH.put("worlds.title", "世界管理");
        ZH.put("worlds.empty", "暂无世界。开始游戏后会自动在 saves 目录创建。");
        ZH.put("worlds.size", "大小");
        ZH.put("worlds.modified", "最后修改");

        // ===== 截图页 =====
        ZH.put("screenshots.title", "截图");
        ZH.put("screenshots.empty", "暂无截图。游戏内按 F2 截图后会自动保存。");

        // ===== 账号页 =====
        ZH.put("accounts.title", "账号");
        ZH.put("accounts.current", "当前账号");
        ZH.put("accounts.not_logged_in", "未登录");
        ZH.put("accounts.logout", "退出登录");
        ZH.put("accounts.offline", "离线账号");
        ZH.put("accounts.microsoft", "微软账号");
        ZH.put("accounts.username", "用户名");
        ZH.put("accounts.login", "登录");
        ZH.put("accounts.start_ms_login", "开始微软登录");
        ZH.put("accounts.logging_in", "登录中…");
        ZH.put("accounts.device_code_hint", "请打开浏览器访问：");

        // ===== 设置页 =====
        ZH.put("settings.title", "设置");
        ZH.put("settings.memory", "内存");
        ZH.put("settings.min_memory", "最小 (MB)");
        ZH.put("settings.max_memory", "最大 (MB)");
        ZH.put("settings.jvm_advanced", "JVM 高级配置");
        ZH.put("settings.gc_type", "GC 类型");
        ZH.put("settings.aikar", "Aikar's Flags");
        ZH.put("settings.aikar_desc", "社区公认的 MC 优化参数集（推荐开启）");
        ZH.put("settings.custom_args", "自定义 JVM 参数（空格分隔）");
        ZH.put("settings.appearance", "外观");
        ZH.put("settings.dark_theme", "深色主题");
        ZH.put("settings.light_theme", "浅色主题");
        ZH.put("settings.network", "网络配置");
        ZH.put("settings.mirror", "下载镜像源");
        ZH.put("settings.mirror_official", "官方");
        ZH.put("settings.mirror_bmclapi", "BMCLAPI");
        ZH.put("settings.mirror_custom", "自定义");
        ZH.put("settings.custom_mirror", "自定义镜像基址");
        ZH.put("settings.http_proxy", "HTTP 代理");
        ZH.put("settings.proxy_host", "主机");
        ZH.put("settings.proxy_port", "端口");
        ZH.put("settings.proxy_auth", "代理认证");
        ZH.put("settings.speed_limit", "限速 (KB/s, 0=不限)");
        ZH.put("settings.retry_count", "重试次数");
        ZH.put("settings.chunked_threads", "分片下载连接数");
        ZH.put("settings.enable_resume", "断点续传（.part 文件）");
        ZH.put("settings.system_info", "系统信息");
        ZH.put("settings.work_dir", "工作目录");
        ZH.put("settings.language", "语言");

        // ===== 完整性校验 =====
        ZH.put("integrity.check", "校验完整性");
        ZH.put("integrity.checking", "正在校验…");
        ZH.put("integrity.ok", "完整性校验通过");
        ZH.put("integrity.issues", "发现 {0} 个问题");
        ZH.put("integrity.missing", "缺失 {0}");
        ZH.put("integrity.mismatch", "哈希不匹配 {0}");

        // ===== 崩溃分析 =====
        ZH.put("crash.title", "崩溃报告");
        ZH.put("crash.causes", "可能原因");
        ZH.put("crash.suggestions", "建议");
        ZH.put("crash.empty", "暂无崩溃报告");

        // EN 翻译（节选关键条目）
        EN.put("app.title", "PMCL Launcher");
        EN.put("common.refresh", "Refresh");
        EN.put("common.delete", "Delete");
        EN.put("common.backup", "Backup");
        EN.put("common.cancel", "Cancel");
        EN.put("common.confirm", "Confirm");
        EN.put("common.ok", "OK");
        EN.put("common.status", "Status");
        EN.put("common.loading", "Loading…");

        EN.put("nav.launch", "Launch");
        EN.put("nav.download", "Download");
        EN.put("nav.mods", "Mods");
        EN.put("nav.market", "Market");
        EN.put("nav.worlds", "Worlds");
        EN.put("nav.screenshots", "Shots");
        EN.put("nav.accounts", "Accounts");
        EN.put("nav.settings", "Settings");

        EN.put("launch.title", "Launch Game");
        EN.put("launch.select_version", "Select version");
        EN.put("launch.start", "Launch");
        EN.put("launch.no_version", "Please select a version first");
        EN.put("launch.no_account", "Please log in first");
        EN.put("launch.running", "Game is already running");
        EN.put("launch.starting", "Starting…");
        EN.put("launch.failed", "Launch failed");
        EN.put("launch.system_info", "System Info");

        EN.put("download.refresh", "Refresh versions");
        EN.put("download.install", "Install");
        EN.put("download.installing", "Installing…");
        EN.put("download.install_done", "Install complete");
        EN.put("download.install_failed", "Install failed");
        EN.put("download.local_versions", "Installed versions");

        EN.put("mods.installed", "Installed mods");
        EN.put("mods.conflicts", "Conflict check");
        EN.put("mods.no_mods", "No mods installed. Installed mods will appear here.");
        EN.put("mods.scan_done", "Scanned {0} mods");

        EN.put("market.search", "Search");
        EN.put("market.query_placeholder", "Search mods…");
        EN.put("market.game_version", "Game version");
        EN.put("market.loader", "Loader");
        EN.put("market.any", "Any");
        EN.put("market.files", "Files");
        EN.put("market.download", "Download");
        EN.put("market.curseforge_disabled", "CurseForge disabled (no API Key)");

        EN.put("worlds.title", "Worlds");
        EN.put("worlds.empty", "No worlds. Worlds will be created when you start the game.");
        EN.put("worlds.size", "Size");
        EN.put("worlds.modified", "Modified");

        EN.put("screenshots.title", "Screenshots");
        EN.put("screenshots.empty", "No screenshots. Press F2 in-game to capture.");

        EN.put("accounts.title", "Accounts");
        EN.put("accounts.current", "Current account");
        EN.put("accounts.not_logged_in", "Not logged in");
        EN.put("accounts.logout", "Log out");
        EN.put("accounts.offline", "Offline account");
        EN.put("accounts.microsoft", "Microsoft account");
        EN.put("accounts.username", "Username");
        EN.put("accounts.login", "Log in");
        EN.put("accounts.start_ms_login", "Start Microsoft login");
        EN.put("accounts.logging_in", "Logging in…");
        EN.put("accounts.device_code_hint", "Open this URL in browser:");

        EN.put("settings.title", "Settings");
        EN.put("settings.memory", "Memory");
        EN.put("settings.min_memory", "Min (MB)");
        EN.put("settings.max_memory", "Max (MB)");
        EN.put("settings.jvm_advanced", "JVM Advanced");
        EN.put("settings.gc_type", "GC type");
        EN.put("settings.aikar", "Aikar's Flags");
        EN.put("settings.aikar_desc", "Community-recognized MC optimization flags");
        EN.put("settings.custom_args", "Custom JVM args (space separated)");
        EN.put("settings.appearance", "Appearance");
        EN.put("settings.dark_theme", "Dark theme");
        EN.put("settings.light_theme", "Light theme");
        EN.put("settings.network", "Network");
        EN.put("settings.mirror", "Download mirror");
        EN.put("settings.mirror_official", "Official");
        EN.put("settings.mirror_bmclapi", "BMCLAPI");
        EN.put("settings.mirror_custom", "Custom");
        EN.put("settings.custom_mirror", "Custom mirror base URL");
        EN.put("settings.http_proxy", "HTTP proxy");
        EN.put("settings.proxy_host", "Host");
        EN.put("settings.proxy_port", "Port");
        EN.put("settings.proxy_auth", "Proxy auth");
        EN.put("settings.speed_limit", "Speed limit (KB/s, 0=unlimited)");
        EN.put("settings.retry_count", "Retry count");
        EN.put("settings.chunked_threads", "Chunked download threads");
        EN.put("settings.enable_resume", "Resume (.part file)");
        EN.put("settings.system_info", "System Info");
        EN.put("settings.work_dir", "Working directory");
        EN.put("settings.language", "Language");

        EN.put("integrity.check", "Check integrity");
        EN.put("integrity.checking", "Checking…");
        EN.put("integrity.ok", "Integrity check passed");
        EN.put("integrity.issues", "{0} issues found");
        EN.put("integrity.missing", "Missing {0}");
        EN.put("integrity.mismatch", "Hash mismatch {0}");

        EN.put("crash.title", "Crash reports");
        EN.put("crash.causes", "Possible causes");
        EN.put("crash.suggestions", "Suggestions");
        EN.put("crash.empty", "No crash reports");
    }

    public static Locale getCurrentLocale() { return current; }

    public static void setLocale(Locale locale) {
        current = locale;
    }

    /** 翻译键，支持 {0} {1} 等参数占位 */
    public static String t(String key, Object... args) {
        Map<String, String> map = (current == EN_US) ? EN : ZH;
        String val = map.getOrDefault(key, key);
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                val = val.replace("{" + i + "}", String.valueOf(args[i]));
            }
        }
        return val;
    }
}
