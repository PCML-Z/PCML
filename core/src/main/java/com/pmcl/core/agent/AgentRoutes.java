package com.pmcl.core.agent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把用户说的页面名落到启动器的一级路由和二级分区。
 * 分区对不上时不猜第一个分区。
 */
public final class AgentRoutes {
    public record Destination(String route, String section, String label) {}

    private static final Map<String, Destination> BY_ALIAS = new HashMap<>();

    private static final String[] PREFIXES = {
            "请帮我", "帮我", "请你", "请", "打开", "前往", "跳转到", "跳转", "进入", "看看", "查看", "去到", "去", "到"
    };

    private static final String HINT =
            "可以说：启动、新闻、提示、联机、联机设置、服务器、好友、聊天、邮件、"
                    + "下载、版本、模组市场、下载队列、百科、模组、整合包、光影、投影、资源包、数据包、配置、皮肤、内存、"
                    + "存档、备份、截图、录屏、性能、游玩统计、账号、离线登录、微软登录、"
                    + "设置、主题、Java、游戏设置、网络、内核、插件、实例、终端、音乐、歌单";

    static {
        page("launch", "", "启动", "launch", "home", "启动", "主页", "启动页", "游戏");
        page("news", "", "新闻", "news", "新闻", "资讯");
        page("tips", "", "提示", "tips", "提示", "技巧", "帮助提示");
        page("servers", "", "服务器", "servers", "服务器", "多人服务器");
        page("terminal", "", "终端", "terminal", "终端", "命令行");
        page("ecxp", "", "ECXP", "ecxp", "扩展运行时");
        page("instances", "", "实例", "instances", "实例", "实例列表");
        page("nbt", "", "NBT 编辑器", "nbt", "nbt编辑器", "nbt编辑");

        page("multiplayer", "room", "联机", "multiplayer", "mp", "联机", "联机房间", "房间");
        page("multiplayer", "settings", "联机 · 设置", "联机设置", "房间设置");
        page("multiplayer", "help", "联机 · 帮助", "联机帮助");

        page("friends", "chat", "好友", "friends", "好友", "聊天", "好友聊天");
        page("friends", "mail", "好友 · 邮件", "邮件", "邮箱", "收件箱");
        page("friends", "rooms", "好友 · 房间", "好友房间");

        page("download", "versions", "下载 · 版本", "download", "下载", "下载页", "versions", "版本", "本地版本", "游戏版本", "版本安装");
        page("download", "market", "下载 · 模组市场", "market", "模组市场", "市场", "资源市场");
        page("download", "queue", "下载 · 队列", "queue", "下载队列", "队列");
        page("download", "wiki", "下载 · 百科", "wiki", "百科", "维基");

        page("content", "mods", "内容 · 模组", "content", "内容", "mods", "模组", "mod", "模组列表");
        page("content", "modpacks", "内容 · 整合包", "modpacks", "整合包", "modpack");
        page("content", "shaders", "内容 · 光影", "shaders", "shader", "shaderpacks", "光影", "光影包");
        page("content", "projections", "内容 · 投影", "projections", "投影", "蓝图", "投影原理图");
        page("content", "resourcepacks", "内容 · 资源包", "resourcepacks", "resourcepack", "资源包", "材质包", "材质");
        page("content", "datapacks", "内容 · 数据包", "datapacks", "datapack", "数据包");
        page("content", "configs", "内容 · 配置", "configs", "游戏配置", "配置文件");
        page("content", "skins", "内容 · 皮肤", "skins", "皮肤", "离线皮肤");
        page("content", "memory", "内容 · 内存", "memory", "内存", "内存占用");

        page("saves", "worlds", "存档", "saves", "存档", "世界", "世界列表", "存档列表");
        page("saves", "timemachine", "存档 · 备份", "timemachine", "备份", "时光机", "快照");
        page("saves", "screenshots", "存档 · 截图", "screenshots", "截图", "截屏");
        page("saves", "recordings", "存档 · 录屏", "recordings", "录屏", "录像", "录制");

        page("statistics", "performance", "统计 · 性能", "performance", "性能", "性能统计");
        page("statistics", "overview", "统计", "statistics", "stats", "统计", "游玩统计", "总览", "概览");
        page("statistics", "sessions", "统计 · 会话", "sessions", "会话", "游玩记录");
        page("statistics", "breakdown", "统计 · 分布", "breakdown", "分布", "明细");

        page("accounts", "list", "账号", "accounts", "账号", "账号列表", "账户");
        page("accounts", "skin", "账号 · 皮肤", "账号皮肤", "正版皮肤");
        page("accounts", "offline", "账号 · 离线", "离线登录", "离线账号");
        page("accounts", "microsoft", "账号 · 微软", "微软登录", "正版登录", "微软账号");
        page("accounts", "github", "账号 · GitHub", "github登录", "github账号");
        page("accounts", "yggdrasil", "账号 · 外置登录", "外置登录", "yggdrasil", "验证服务器");

        page("settings", "launcher", "设置", "settings", "设置", "启动器设置", "偏好设置");
        page("settings", "theme", "设置 · 主题", "theme", "主题", "外观", "深色", "浅色");
        page("settings", "java", "设置 · Java", "java", "java路径", "运行环境", "jre", "jdk");
        page("settings", "game", "设置 · 游戏", "游戏设置", "游戏行为", "窗口大小");
        page("settings", "automation", "设置 · 自动化", "自动化", "自动操作");
        page("settings", "network", "设置 · 网络", "网络", "网络设置", "下载源", "镜像");
        page("settings", "kernels", "设置 · 内核", "kernels", "内核");
        page("settings", "updates", "设置 · 更新", "检查更新", "更新");
        page("settings", "about", "设置 · 关于", "关于", "关于启动器");
        page("settings", "compile", "设置 · 编译", "编译");
        page("settings", "device", "设置 · 设备", "设备");
        page("settings", "system", "设置 · 系统", "系统设置");
        page("settings", "feedback", "设置 · 反馈", "反馈");
        page("settings", "licenses", "设置 · 许可", "许可", "开源许可", "许可证");
        page("settings", "extensions", "设置 · 扩展", "扩展");
        page("settings", "mio", "设置 · Mio", "mio");
        page("settings", "cursor", "设置 · Cursor", "cursor");
        page("settings", "git-tree", "设置 · Git", "git", "gittree");

        page("plugins", "installed", "插件", "plugins", "插件", "已安装插件");
        page("plugins", "actions", "插件 · 动作", "插件动作");
        page("plugins", "install", "插件 · 安装", "安装插件");

        page("music", "player", "音乐", "music", "音乐", "播放器");
        page("music", "playlist", "音乐 · 歌单", "歌单", "播放列表");
        page("music", "favorites", "音乐 · 收藏", "音乐收藏", "收藏歌曲");
        page("music", "history", "音乐 · 历史", "播放历史", "最近播放");
    }

    private AgentRoutes() {}

    public static Destination resolve(String page, String section) {
        String rawPage = page == null ? "" : page.trim();
        String rawSection = section == null ? "" : section.trim();
        int slash = rawPage.indexOf('/');
        if (slash > 0 && rawSection.isEmpty()) {
            rawSection = rawPage.substring(slash + 1).trim();
            rawPage = rawPage.substring(0, slash).trim();
        }
        String p = norm(rawPage);
        String s = norm(rawSection);
        if (p.isEmpty() && s.isEmpty()) return null;
        if (!p.isEmpty() && !s.isEmpty()) {
            Destination exact = BY_ALIAS.get(p + "/" + s);
            if (exact != null) return exact;
            Destination pageDest = BY_ALIAS.get(p);
            Destination sectionDest = BY_ALIAS.get(s);
            if (pageDest != null && sectionDest != null && !pageDest.route.equals(sectionDest.route)) return null;
            if (pageDest != null && sectionDest != null) {
                return sectionDest.section.isEmpty() ? pageDest : sectionDest;
            }
            if (pageDest != null && !pageDest.section.isEmpty() && sectionDest == null) return pageDest;
            if (pageDest == null && sectionDest != null) return sectionDest;
            Destination fuzzy = fuzzy(p + s);
            if (fuzzy != null) return fuzzy;
            return null;
        }
        Destination direct = BY_ALIAS.get(p.isEmpty() ? s : p);
        if (direct != null) return direct;
        return fuzzy(p.isEmpty() ? s : p);
    }

    /** 这个接口对应的页面。没有固定页面时返回 null。 */
    public static Destination homeFor(String method) {
        if (method == null || method.isBlank()) return null;
        String m = method.trim();
        if (m.equals("ui.navigate") || m.startsWith("ui.") || m.startsWith("i18n.")) return null;
        if (m.startsWith("gameContent.listShader") || m.startsWith("gameContent.enableShader") || m.startsWith("gameContent.disableShader")) {
            return BY_ALIAS.get(norm("光影"));
        }
        if (m.startsWith("gameContent.listResource") || m.startsWith("gameContent.enableResource") || m.startsWith("gameContent.disableResource")) {
            return BY_ALIAS.get(norm("资源包"));
        }
        if (m.startsWith("gameContent.listDatapack") || m.startsWith("gameContent.enableDatapack") || m.startsWith("gameContent.disableDatapack")) {
            return BY_ALIAS.get(norm("数据包"));
        }
        if (m.startsWith("gameContent.listScreenshot")) return BY_ALIAS.get(norm("截图"));
        if (m.startsWith("gameContent.")) return BY_ALIAS.get(norm("存档"));
        if (m.startsWith("mods.")) return BY_ALIAS.get(norm("模组"));
        if (m.startsWith("modMarket.")) return BY_ALIAS.get(norm("模组市场"));
        if (m.startsWith("modpacks.")) return BY_ALIAS.get(norm("整合包"));
        if (m.startsWith("downloadQueue.")) return BY_ALIAS.get(norm("下载队列"));
        if (m.startsWith("versions.") || m.startsWith("loaders.") || m.startsWith("integrity.") || m.startsWith("versionJava.")) {
            return BY_ALIAS.get(norm("版本"));
        }
        if (m.startsWith("pins.") || m.startsWith("launch.")) return BY_ALIAS.get(norm("启动"));
        if (m.startsWith("javaRuntimes.")) return BY_ALIAS.get(norm("java"));
        if (m.startsWith("settings.")) {
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.contains("theme") || lower.contains("dark")) return BY_ALIAS.get(norm("主题"));
            if (lower.contains("java")) return BY_ALIAS.get(norm("java"));
            if (lower.contains("memory") || lower.contains("window") || lower.contains("fullscreen") || lower.contains("isolation")) {
                return BY_ALIAS.get(norm("游戏设置"));
            }
            if (lower.contains("mirror") || lower.contains("downloadthread")) return BY_ALIAS.get(norm("网络"));
            return BY_ALIAS.get(norm("设置"));
        }
        if (m.startsWith("accounts.")) return BY_ALIAS.get(norm("账号"));
        if (m.startsWith("instances.")) return BY_ALIAS.get(norm("实例"));
        if (m.startsWith("servers.")) return BY_ALIAS.get(norm("服务器"));
        if (m.startsWith("stats.") || m.startsWith("play.")) return BY_ALIAS.get(norm("游玩统计"));
        if (m.startsWith("music.")) return BY_ALIAS.get(norm("音乐"));
        if (m.startsWith("plugins.")) return BY_ALIAS.get(norm("插件"));
        if (m.startsWith("kernels.")) return BY_ALIAS.get(norm("内核"));
        if (m.startsWith("rooms.")) return BY_ALIAS.get(norm("联机"));
        if (m.startsWith("backups.")) return BY_ALIAS.get(norm("备份"));
        if (m.startsWith("skins.")) return BY_ALIAS.get(norm("皮肤"));
        if (m.startsWith("recordings.")) return BY_ALIAS.get(norm("录屏"));
        if (m.startsWith("wiki.")) return BY_ALIAS.get(norm("百科"));
        if (m.startsWith("presets.") || m.startsWith("migration.")) return BY_ALIAS.get(norm("游戏设置"));
        if (m.startsWith("news.")) return BY_ALIAS.get(norm("新闻"));
        return null;
    }

    public static String opened(Destination dest) {
        return "已打开 " + dest.label;
    }

    public static String unknown() {
        return "没有这个页面。" + HINT;
    }

    private static void page(String route, String section, String label, String... aliases) {
        Destination dest = new Destination(route, section, label);
        for (String alias : aliases) {
            String key = norm(alias);
            if (!key.isEmpty()) BY_ALIAS.put(key, dest);
        }
        if (!section.isEmpty()) BY_ALIAS.put(norm(route + "/" + section), dest);
        if (section.isEmpty()) BY_ALIAS.put(norm(route), dest);
        else BY_ALIAS.putIfAbsent(norm(section), dest);
    }

    private static Destination fuzzy(String text) {
        if (text == null || text.isEmpty()) return null;
        Destination best = null;
        int bestLen = 0;
        for (Map.Entry<String, Destination> entry : BY_ALIAS.entrySet()) {
            String key = entry.getKey();
            if (key.indexOf('/') >= 0) continue;
            boolean ascii = key.chars().allMatch(c -> c < 128);
            if (ascii && key.length() < 4) continue;
            if (!ascii && key.length() < 2) continue;
            if (text.contains(key) && key.length() > bestLen) {
                best = entry.getValue();
                bestLen = key.length();
            }
        }
        return best;
    }

    private static String norm(String raw) {
        String s = stripDecor(raw).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '　' || c == '_') continue;
            out.append(c);
        }
        return out.toString();
    }

    private static String stripDecor(String raw) {
        String s = raw == null ? "" : raw.trim();
        boolean changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            for (String prefix : PREFIXES) {
                if (s.startsWith(prefix) && s.length() > prefix.length()) {
                    s = s.substring(prefix.length()).trim();
                    changed = true;
                }
            }
            for (String suffix : new String[]{"页面", "界面"}) {
                if (s.endsWith(suffix) && s.length() > suffix.length()) {
                    s = s.substring(0, s.length() - suffix.length()).trim();
                    changed = true;
                }
            }
        }
        return s;
    }
}
