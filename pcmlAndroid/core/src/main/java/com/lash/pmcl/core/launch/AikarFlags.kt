package com.lash.pmcl.core.launch

/**
 * Aikar's Flags — 社区公认的 Minecraft JVM 优化参数集。
 * https://docs.papermc.io/paper/aikars-flags
 *
 * 已适配新版 Java（17+）：移除 Java 9+ 不支持的参数（G1RSetScanBlockSize 等），
 * 修复 G1HeapRegionSize 重复定义。
 */
internal object AikarFlags {

    val FLAGS: List<String> = listOf(
        "-XX:+UseG1GC",
        "-XX:+ParallelRefProcEnabled",
        "-XX:MaxGCPauseMillis=200",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+DisableExplicitGC",
        "-XX:G1HeapRegionSize=32M",
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
    )
}
