package com.pmcl.core.launch;

import java.util.ArrayList;
import java.util.List;

/**
 * 澪模式 JVM 激进参数集（L1 层）。
 * <p>
 * 在 Aikar's Flags 基础上叠加多维度优化：
 * - JIT 内联更激进（更深方法内联 + 类型推测 + 循环谓词）
 * - CPU 指令集显式启用（AES/AVX/FMA/BMI2/向量化）
 * - G1 GC 更激进（更低停顿目标 + 更大新生代 + 更快老年代回收）
 * - CodeCache 扩大避免编译饱和
 * - 大页内存 + NUMA（可选，JVM 不支持自动降级）
 * - 实验性 ZGC 切换（可选，JDK 21 生成式，亚毫秒级停顿）
 * <p>
 * 风险：低（大页/NUMA 不支持时 JVM 自动降级；ZGC 为独立开关）。
 * 进程退出即失效，不影响系统状态。
 */
final class MioFlags {

    /**
     * 构造澪模式 L1 基础激进参数（JIT + CPU 指令集 + G1 + CodeCache）。
     *
     * @param cpuPhysicalCores CPU 物理核心数（用于 GC 线程数），<=0 时用默认 4
     * @param maxMemoryMb      最大堆内存 MB
     * @return 澪模式 JVM 参数列表
     */
    static List<String> build(int cpuPhysicalCores, int maxMemoryMb) {
        int cores = cpuPhysicalCores > 0 ? cpuPhysicalCores : 4;
        int parallelThreads = Math.min(cores, 16);
        int concThreads = Math.max(2, cores / 4);

        List<String> flags = new ArrayList<>(24);

        // ===== GC 并行/并发线程数强制 =====
        flags.add("-XX:ParallelGCThreads=" + parallelThreads);
        flags.add("-XX:ConcGCThreads=" + concThreads);

        // ===== JIT 内联更激进（MC 大量小方法调用 + 热点循环，深内联收益明显）=====
        flags.add("-XX:MaxInlineLevel=15");              // 默认 9，热点方法更深内联
        flags.add("-XX:MaxInlineSize=45");               // 默认 35，小方法更易内联
        flags.add("-XX:FreqInlineSize=325");             // 频繁调用方法内联大小
        flags.add("-XX:+UseTypeSpeculation");            // 类型推测（默认开，显式声明）
        flags.add("-XX:+UseProfiledLoopPredicate");      // JDK 16+ 性能更好的循环谓词

        // ===== CPU 指令集显式启用（MC 区块渲染/物理/噪声生成是向量化热点）=====
        flags.add("-XX:+UseAES");                        // AES 加速（区块加密）
        flags.add("-XX:+UseAESIntrinsics");              // AES 内联
        flags.add("-XX:+UseFMA");                        // 融合乘加（物理计算）
        flags.add("-XX:+UseSuperWord");                  // 自动向量化（默认开，显式声明）
        // AVX 等级按 CPU 自动选，不强制避免不支持 CPU 启动失败
        // BMI2 在 x86 上启用，ARM 自动忽略
        flags.add("-XX:+UseBMI2");                       // 位操作指令集

        // ===== G1 GC 更激进（与 AikarFlags 叠加，后注入的覆盖前者）=====
        flags.add("-XX:MaxGCPauseMillis=50");            // Aikar=200，澪模式降到 50ms
        flags.add("-XX:G1NewSizePercent=30");            // 新生代最小 30%（默认 5）
        flags.add("-XX:G1ReservePercent=10");            // Aikar=20，降到 10 让更多堆给应用
        flags.add("-XX:G1MixedGCCountTarget=2");         // Aikar=4，降到 2 更快回收老年代
        flags.add("-XX:G1HeapWastePercent=3");           // Aikar=5，降到 3
        flags.add("-XX:TargetSurvivorRatio=90");         // 默认 50，提到 90 让 survivor 更满
        flags.add("-XX:MaxTenuringThreshold=15");        // 默认 15（最大），让对象更久留新生代

        // ===== CodeCache 扩大避免编译饱和 =====
        flags.add("-XX:ReservedCodeCacheSize=256M");     // 默认 240M，提到 256M
        flags.add("-XX:InitialCodeCacheSize=32M");       // 初始代码缓存

        // ===== 分配器 + JIT 优化 =====
        flags.add("-XX:AllocatePrefetchStyle=2");        // TLB 旁路预取
        flags.add("-XX:+TrustFinalNonStaticFields");     // 信任 final 字段
        flags.add("-XX:-UseBiasedLocking");              // JDK 15+ 已默认禁用，显式声明

        return flags;
    }

    /**
     * 构造大页内存 + NUMA 参数（L1+ 可选）。
     * JVM 不支持时自动降级为普通页/单节点，不会启动失败。
     */
    static List<String> buildLargePages() {
        List<String> flags = new ArrayList<>(3);
        flags.add("-XX:+UseLargePages");                 // 减少 TLB miss
        flags.add("-XX:+UseLargePagesInMetaspace");      // 元空间也用大页
        flags.add("-XX:+UseNUMA");                       // NUMA 感知（多路 CPU）
        return flags;
    }

    /**
     * 构造实验性 ZGC 参数（L1+ 可选，JDK 21 生成式 ZGC）。
     * 注意：开启 ZGC 时应跳过 AikarFlags（G1GC 参数会冲突）。
     * ZGC 提供亚毫秒级停顿，但染色指针可能增加 MC 大量区块分配的开销。
     */
    static List<String> buildZgc(int maxMemoryMb) {
        List<String> flags = new ArrayList<>(8);
        flags.add("-XX:+UseZGC");
        flags.add("-XX:+ZGenerational");                 // JDK 21 生成式 ZGC
        flags.add("-XX:SoftMaxHeapSize=" + maxMemoryMb + "m"); // 软上限
        flags.add("-XX:ZAllocationSpikeTolerance=2.0");  // 分配尖峰容忍度
        flags.add("-XX:+ZProactive");                    // 主动 GC
        flags.add("-XX:ConcGCThreads=" + Math.max(2, Runtime.getRuntime().availableProcessors() / 4));
        flags.add("-XX:ParallelGCThreads=" + Math.min(Runtime.getRuntime().availableProcessors(), 16));
        return flags;
    }

    /**
     * 构造 LWJGL/OpenGL 渲染加速参数（L1+ 可选）。
     * - 高 DPI 启用（Retina 屏清晰渲染）
     * - 系统 malloc 替代 JVM 内置分配器（避免分配器竞争）
     * - 关闭 LWJGL debug 输出（减少 IO）
     */
    static List<String> buildRenderOpt() {
        List<String> flags = new ArrayList<>(4);
        flags.add("-Dorg.lwjgl.opengl.Display.enableHighDPI=true");
        flags.add("-Dorg.lwjgl.system.allocator=system");
        flags.add("-Dorg.lwjgl.util.Debug=false");
        flags.add("-Dorg.lwjgl.util.DebugLoader=false");
        return flags;
    }

    /**
     * 构造 JIT 编译器激进参数（L1+ 可选）。
     * - CompileThreshold 降低让热点方法更早触发编译
     * - 循环安全点减少长循环导致的 GC 停顿
     * - 增加 JIT 编译器线程数加速编译
     */
    static List<String> buildJitAggressive(int cpuCores) {
        List<String> flags = new ArrayList<>(5);
        flags.add("-XX:CompileThreshold=5000");            // 默认 10000，热点更早编译
        flags.add("-XX:+UseCountedLoopSafepoints");         // 长循环安全点，减少 GC 停顿
        // JIT 编译器线程数：默认动态，澪模式按 CPU 核心数显式设置
        int ciCount = Math.max(2, Math.min(cpuCores / 2, 8));
        flags.add("-XX:CICompilerCount=" + ciCount);
        flags.add("-XX:+TieredCompilation");                // 分层编译（默认开，显式声明）
        flags.add("-XX:+UseLoopCounter");                   // 循环计数（默认开，显式声明）
        return flags;
    }

    /**
     * 构造网络栈优化参数（L1+ 可选，MC 联机场景）。
     * - IPv4 优先（避免 IPv6 双栈抖动）
     * - 快速路径网络（减少 socket 创建开销）
     * - DNS 缓存 30 秒（减少重复解析）
     */
    static List<String> buildNetworkOpt() {
        List<String> flags = new ArrayList<>(4);
        flags.add("-Djava.net.preferIPv4Stack=true");
        flags.add("-Djdk.net.useFastPathNetworking=true");
        flags.add("-Dnetworkaddress.cache.ttl=30");         // DNS 成功缓存 30 秒
        flags.add("-Dnetworkaddress.cache.negative.ttl=0"); // DNS 失败不缓存
        return flags;
    }

    /**
     * 构造元空间管控参数（L1+ 可选，防 OOM）。
     * - 限制元空间上限避免无限增长
     * - 类数据共享加速启动
     * - 自由比率控制避免频繁扩容
     */
    static List<String> buildMetaspace() {
        List<String> flags = new ArrayList<>(5);
        flags.add("-XX:MaxMetaspaceSize=512M");             // 上限 512M，防 OOM
        flags.add("-XX:MetaspaceSize=128M");                // 初始 128M
        flags.add("-XX:MinMetaspaceFreeRatio=20");          // 最小空闲 20%
        flags.add("-XX:MaxMetaspaceFreeRatio=80");          // 最大空闲 80%
        flags.add("-Xshare:auto");                          // 类数据共享，加速启动
        return flags;
    }

    private MioFlags() {}
}
