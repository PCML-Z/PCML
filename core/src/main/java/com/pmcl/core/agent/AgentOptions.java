package com.pmcl.core.agent;

import java.util.Arrays;
import java.util.Locale;

/**
 * 用户点名的设置开关。对不上就交给页面路由，不猜。
 * 只识别选项和开关方向，不写 preferences.json。
 */
public final class AgentOptions {
    public record Change(
            String id,
            String route,
            String section,
            String label,
            String place,
            boolean confirm,
            Boolean enabled) {}

    private static final String[] FILLERS = {
            "请帮我", "帮我", "请你", "一下", "这个", "那个", "选项", "设置",
            "不要", "别用", "关掉", "关闭", "禁用", "取消", "打开", "开启", "启用", "勾选", "使用",
            "请", "的", "里", "中", "把", "将", "给", "我"
    };

    private static final Spec[] SPECS = {
            spec("macGameMode", "game", "游戏设置", "使用 macOS Game Mode 启动游戏", false,
                    "使用macosgamemode启动游戏", "macosgamemode", "macgamemode"),
            spec("versionIsolation", "game", "游戏设置", "版本隔离", false, "版本隔离"),
            spec("fullscreen", "game", "游戏设置", "全屏启动", false, "全屏启动", "全屏"),
            spec("demo", "game", "游戏设置", "演示模式", false, "演示模式"),
            spec("preferUtf8", "game", "游戏设置", "优先使用 UTF-8 编码", false, "优先使用utf8编码", "utf8编码", "utf8"),
            spec("preferIpv4", "game", "游戏设置", "优先使用 IPv4 网络堆栈", false, "优先使用ipv4", "ipv4"),
            spec("showOutput", "game", "游戏设置", "启动游戏时显示输出日志", false, "显示输出日志", "输出日志", "游戏输出"),
            spec("fastCheck", "game", "游戏设置", "使用快速资源检查", false, "快速资源检查"),
            spec("macMic", "game", "游戏设置", "启动游戏时自动请求系统的麦克风权限", false, "麦克风权限", "请求麦克风"),
            spec("linuxZink", "game", "游戏设置", "使用 Zink", false, "zink"),
            spec("forceG1", "java", "Java 设置", "强制使用 G1", false, "强制使用g1", "g1gc"),
            spec("aikar", "java", "Java 设置", "Aikar's Flags", false, "aikarsflags", "aikar"),
            spec("imeAgent", "java", "Java 设置", "附加 Java Agent 修复输入法", false, "修复输入法", "输入法agent"),
            spec("useProxy", "network", "网络设置", "使用代理", false, "使用代理", "http代理", "代理"),
            spec("resume", "network", "网络设置", "断点续传", false, "断点续传"),
            spec("material", "theme", "主题", "材质主题", false, "材质主题"),
            spec("glass", "theme", "主题", "玻璃主题", false, "玻璃主题"),
            spec("liveGlass", "theme", "主题", "壁纸毛玻璃", false, "壁纸毛玻璃"),
            spec("lockscreen", "theme", "主题", "锁屏启动页", false, "锁屏启动页", "锁屏启动"),
            spec("parallax", "theme", "主题", "视差背景", false, "视差背景"),
            spec("dark", "theme", "主题", "深色主题", false, "深色主题", "深色模式", "深色"),
            spec("followSystem", "theme", "主题", "跟随系统", false, "深浅色跟随系统", "跟随系统"),
            spec("mio", "mio", "澪模式", "澪模式", false, "澪模式"),
            spec("mioJvm", "mio", "澪模式", "L1 JVM 激进参数", false, "jvm激进"),
            spec("mioPages", "mio", "澪模式", "大页内存", false, "大页内存"),
            spec("mioZgc", "mio", "澪模式", "实验性 ZGC", false, "实验性zgc"),
            spec("mioRender", "mio", "澪模式", "LWJGL 渲染加速", false, "渲染加速", "lwjgl"),
            spec("mioJit", "mio", "澪模式", "JIT 编译器激进", false, "jit编译器", "jit激进"),
            spec("mioNet", "mio", "澪模式", "网络栈优化", false, "网络栈优化"),
            spec("mioMeta", "mio", "澪模式", "元空间管控", false, "元空间"),
            spec("mioPretouch", "mio", "澪模式", "预热堆内存", false, "预热堆"),
            spec("mioDedup", "mio", "澪模式", "字符串去重", false, "字符串去重"),
            spec("mioIdle", "mio", "澪模式", "空闲回收", false, "空闲回收"),
            spec("mioProcess", "mio", "澪模式", "进程级调优", false, "进程级调优"),
            spec("mioAwake", "mio", "澪模式", "保持亮屏", false, "保持亮屏"),
            spec("mioGpu", "mio", "澪模式", "独立显卡", false, "独立显卡"),
            spec("mioGameMode", "mio", "澪模式", "L2+ GameMode", false, "l2gamemode", "feralgamemode"),
            spec("mioCrazy", "mio", "澪模式", "疯狂调度优先级", true, "疯狂调度"),
            spec("mioPower", "mio", "澪模式", "系统电源策略", true, "系统电源策略", "低电量模式")
    };

    private AgentOptions() {}

    /** 句子里点到了哪一项。没有明确开关时 enabled 为 null，只表示要看到它。 */
    public static Change match(String text) {
        String norm = norm(text);
        if (norm.isEmpty()) return null;
        Spec best = null;
        int bestLen = 0;
        for (Spec spec : SPECS) {
            for (String alias : spec.aliases) {
                if (alias.length() > bestLen && norm.contains(alias)) {
                    best = spec;
                    bestLen = alias.length();
                }
            }
        }
        if (best == null) return null;
        return best.change(polarity(norm));
    }

    /** 整句就是在开关这一项，没有后半句要接着做。 */
    public static boolean direct(String text) {
        String norm = norm(text);
        Change change = match(text);
        if (change == null || change.enabled == null) return false;
        Spec spec = find(change.id);
        return spec != null && leftover(norm, spec).isEmpty();
    }

    /** macOS、Linux 上才出现的开关。其它系统只打开页面。 */
    public static boolean available(Change change, String osName) {
        if (change == null) return false;
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if ("macGameMode".equals(change.id) || "macMic".equals(change.id)) return os.contains("mac");
        if ("linuxZink".equals(change.id)) return os.contains("linux");
        return true;
    }

    private static Spec find(String id) {
        for (Spec spec : SPECS) {
            if (spec.id.equals(id)) return spec;
        }
        return null;
    }

    private static Boolean polarity(String norm) {
        if (containsAny(norm, "不要", "别用", "关掉", "关闭", "禁用", "取消")) return Boolean.FALSE;
        if (containsAny(norm, "打开", "开启", "启用", "勾选", "使用")) return Boolean.TRUE;
        return null;
    }

    private static boolean containsAny(String text, String... parts) {
        for (String part : parts) {
            if (text.contains(part)) return true;
        }
        return false;
    }

    private static String leftover(String norm, Spec spec) {
        String rest = norm;
        String[] aliases = spec.aliases.clone();
        Arrays.sort(aliases, (a, b) -> Integer.compare(b.length(), a.length()));
        for (String alias : aliases) rest = rest.replace(alias, "");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String filler : FILLERS) {
                if (rest.contains(filler)) {
                    rest = rest.replace(filler, "");
                    changed = true;
                }
            }
        }
        return rest;
    }

    static String norm(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '　' || c == '_' || c == '-' || c == '—' || c == '·'
                    || c == '\'' || c == '’' || c == '+' || c == '＋') continue;
            out.append(c);
        }
        return out.toString();
    }

    private static Spec spec(String id, String section, String place, String label, boolean confirm, String... aliases) {
        String[] keys = new String[aliases.length];
        for (int i = 0; i < aliases.length; i++) keys[i] = norm(aliases[i]);
        return new Spec(id, section, place, label, confirm, keys);
    }

    private static final class Spec {
        final String id;
        final String section;
        final String place;
        final String label;
        final boolean confirm;
        final String[] aliases;

        Spec(String id, String section, String place, String label, boolean confirm, String[] aliases) {
            this.id = id;
            this.section = section;
            this.place = place;
            this.label = label;
            this.confirm = confirm;
            this.aliases = aliases;
        }

        Change change(Boolean enabled) {
            return new Change(id, "settings", section, label, place, confirm, enabled);
        }
    }
}
