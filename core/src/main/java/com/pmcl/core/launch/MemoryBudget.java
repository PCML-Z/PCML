package com.pmcl.core.launch;

/**
 * 启动时把堆收进本机物理内存以内。
 * 设置里的数字不动；只改这一次进程实际拿到的 -Xms/-Xmx。
 */
public final class MemoryBudget {

    /** 留给系统和启动器自己。 */
    public static final int RESERVE_MB = 1536;
    static final int FLOOR_MB = 512;

    private MemoryBudget() {}

    /**
     * @param physicalBytes 0 表示不知道本机内存，此时不收紧
     */
    public static int capMax(int requestedMb, long physicalBytes) {
        if (requestedMb <= 0) return requestedMb;
        if (physicalBytes <= 0) return requestedMb;
        long physicalMb = physicalBytes / (1024L * 1024L);
        long ceiling;
        if (physicalMb <= FLOOR_MB) {
            ceiling = Math.max(256L, physicalMb / 2L);
        } else {
            ceiling = physicalMb - RESERVE_MB;
            if (ceiling < FLOOR_MB) ceiling = Math.max(256L, physicalMb / 2L);
        }
        if (ceiling > Integer.MAX_VALUE) ceiling = Integer.MAX_VALUE;
        return (int) Math.min(requestedMb, ceiling);
    }

    public static int capMin(int requestedMinMb, int cappedMaxMb) {
        if (cappedMaxMb <= 0) return requestedMinMb;
        int min = Math.max(0, requestedMinMb);
        return Math.min(min, cappedMaxMb);
    }

    /** 把超出上限的 -Xmx/-Xms 收进来。认不出的写法原样返回。 */
    public static String clampHeapFlag(String arg, int capMb) {
        if (arg == null || capMb <= 0) return arg;
        if (!arg.startsWith("-Xmx") && !arg.startsWith("-Xms")) return arg;
        long mb = parseMb(arg.substring(4));
        if (mb < 0 || mb <= capMb) return arg;
        return arg.substring(0, 4) + capMb + "m";
    }

    static long parseMb(String text) {
        if (text == null) return -1;
        String s = text.trim();
        if (s.isEmpty()) return -1;
        char unit = s.charAt(s.length() - 1);
        String number = s;
        long mul = 1;
        if (unit == 'm' || unit == 'M') {
            number = s.substring(0, s.length() - 1);
        } else if (unit == 'g' || unit == 'G') {
            number = s.substring(0, s.length() - 1);
            mul = 1024;
        } else if (unit == 'k' || unit == 'K') {
            number = s.substring(0, s.length() - 1);
            try {
                double n = Double.parseDouble(number);
                if (n < 0 || Double.isInfinite(n)) return -1;
                return (long) (n / 1024.0);
            } catch (NumberFormatException e) {
                return -1;
            }
        } else if (unit >= '0' && unit <= '9') {
            try {
                long bytes = Long.parseLong(s);
                if (bytes < 0) return -1;
                return bytes / (1024L * 1024L);
            } catch (NumberFormatException e) {
                return -1;
            }
        } else {
            return -1;
        }
        try {
            double n = Double.parseDouble(number);
            if (n < 0 || Double.isInfinite(n)) return -1;
            return (long) (n * mul);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static long physicalBytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                return Math.max(0L, sun.getTotalMemorySize());
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }
}
