package com.pmcl.core.launch;

import java.util.List;
import java.util.Set;

/**
 * 把已经拼好的 JVM 参数收成只用 G1。
 * HotSpot 同时看到两个回收器会直接拒绝启动，所以不能只在末尾再加一条 {@code -XX:+UseG1GC}。
 */
final class G1Force {

    private static final Set<String> COLLECTORS = Set.of(
            "UseG1GC",
            "UseZGC",
            "UseShenandoahGC",
            "UseParallelGC",
            "UseSerialGC",
            "UseConcMarkSweepGC",
            "UseParNewGC",
            "UseEpsilonGC",
            "UseParallelOldGC"
    );

    private G1Force() {}

    static void apply(List<String> args) {
        args.removeIf(G1Force::conflicts);
        args.add("-XX:+UseG1GC");
    }

    static boolean conflicts(String arg) {
        if (arg == null || !arg.startsWith("-XX:")) return false;
        String body = arg.substring(4);
        if (body.startsWith("+") || body.startsWith("-")) body = body.substring(1);
        int eq = body.indexOf('=');
        String name = eq >= 0 ? body.substring(0, eq) : body;
        if (COLLECTORS.contains(name)) return true;
        if (name.startsWith("Shenandoah")) return true;
        if ("SoftMaxHeapSize".equals(name)) return true;
        return name.length() > 1 && name.charAt(0) == 'Z' && Character.isUpperCase(name.charAt(1));
    }
}
