package com.pmcl.core.launch;

import java.util.List;

/**
 * Java 27 启动兼容。
 * <p>
 * JDK 27 默认使用压缩对象头，并删除了 {@code -Xverify:none} 等旧参数。
 * 模组和库仍会通过反射改 final 字段，需要显式放行。
 * 这些参数在 Java 26 及更早版本上可能无法识别，只应在游戏 Java 为 27 及以上时调用。
 */
final class Java27Compat {

    static final String FINAL_FIELD = "--enable-final-field-mutation=ALL-UNNAMED";
    static final String OLD_HEADERS = "-XX:-UseCompactObjectHeaders";

    private Java27Compat() {}

    static void apply(List<String> args) {
        args.removeIf(Java27Compat::removedOnJava27);
        args.removeIf("-XX:+UseCompactObjectHeaders"::equals);
        if (!args.contains(FINAL_FIELD)) args.add(FINAL_FIELD);
        if (!args.contains(OLD_HEADERS)) args.add(OLD_HEADERS);
    }

    static boolean removedOnJava27(String arg) {
        if (arg == null) return false;
        return "-Xverify:none".equals(arg)
                || "-noverify".equals(arg)
                || "-verifyremote".equals(arg)
                || "-Xverify:remote".equals(arg)
                || "-noclassgc".equals(arg);
    }
}
