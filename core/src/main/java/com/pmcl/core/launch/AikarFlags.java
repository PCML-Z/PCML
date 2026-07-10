package com.pmcl.core.launch;

import java.util.List;

/**
 * Aikar's Flags — 社区公认的 Minecraft JVM 优化参数集。
 * https://docs.papermc.io/paper/aikars-flags
 * <p>
 * 已适配新版 Java（17+）：移除 Java 9+ 不支持的参数（G1RSetScanBlockSize 等），
 * 修复 G1HeapRegionSize 重复定义。
 */
final class AikarFlags {

    static final List<String> FLAGS = List.of(
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:G1HeapRegionSize=32M",         // 统一为 32M（原代码 8M 与 16M 冲突）
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32",
            "-XX:+PerfDisableSharedMem",
            "-XX:+AlwaysPreTouch",
            "-XX:+UseCompressedOops",
            "-XX:+UseStringDeduplication"
            // 已移除：-XX:ConcGCThreads=2 / -XX:ParallelGCThreads=4（让 JVM 自动选择）
            // 已移除：-XX:G1RSetScanBlockSize=32M（Java 9+ 不再支持）
            // 已移除：-XX:+UseNUMA（部分 JDK 不支持，且默认启用）
    );

    private AikarFlags() {}
}
