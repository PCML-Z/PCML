package com.pmcl.core.launch;

import java.util.ArrayList;
import java.util.List;

/**
 * 澪模式 JVM 激进参数集（L1 层）。
 * <p>
 * 在 Aikar's Flags 基础上叠加更激进的 GC/分配器/JIT 优化参数，
 * 强制 GC 并行度 = CPU 物理核心数，启用分配预取与 final 字段信任以提升 JIT。
 * <p>
 * 风险：低。进程退出即失效，不影响系统状态。
 * 注意：参数注入在 AikarFlags 之后、customJvmArgs 之前，用户仍可通过自定义参数覆盖。
 */
final class MioFlags {

    /**
     * 构造澪模式 JVM 参数。
     *
     * @param cpuPhysicalCores CPU 物理核心数（用于强制 GC 线程数），<=0 时使用默认 4
     * @param maxMemoryMb      最大堆内存 MB（>=4096 时建议 Xms==Xmx，由调用方处理）
     * @return 澪模式 JVM 参数列表
     */
    static List<String> build(int cpuPhysicalCores, int maxMemoryMb) {
        int cores = cpuPhysicalCores > 0 ? cpuPhysicalCores : 4;
        // GC 并行线程数上限 = 物理核心数（过高会抢游戏线程）
        int parallelThreads = Math.min(cores, 16);
        // GC 并发线程数 = max(2, cores/4)
        int concThreads = Math.max(2, cores / 4);

        List<String> flags = new ArrayList<>(8);
        // 强制 GC 并行/并发线程数（Aikar 默认让 JVM 自动选择，澪模式强制拉满）
        flags.add("-XX:ParallelGCThreads=" + parallelThreads);
        flags.add("-XX:ConcGCThreads=" + concThreads);
        // 分配预取策略：2 = 在 TLB 旁路模式下预取，适合大堆分配密集场景（MC 区块加载）
        flags.add("-XX:AllocatePrefetchStyle=2");
        // 信任 final 字段，减少 JIT 守卫指令（极低风险，JIT 优化）
        flags.add("-XX:+TrustFinalNonStaticFields");
        // 禁用偏向锁（JDK 15+ 已默认禁用，显式声明避免旧版本回退）
        flags.add("-XX:-UseBiasedLocking");
        return flags;
    }

    private MioFlags() {}
}
