package com.pmcl.core.runtime;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.util.List;

/**
 * Java 运行时与硬件信息管理。
 */
public final class RuntimeManager {

    private final SystemInfo systemInfo;
    private final HardwareAbstractionLayer hardware;
    private final OperatingSystem os;

    // CPU 负载计算所需的上一次采样
    private long[] prevCpuTicks;
    private long prevCpuTimeNs;

    public RuntimeManager() {
        this.systemInfo = new SystemInfo();
        this.hardware = systemInfo.getHardware();
        this.os = systemInfo.getOperatingSystem();
        // 初始化 CPU ticks
        this.prevCpuTicks = hardware.getProcessor().getSystemCpuLoadTicks();
        this.prevCpuTimeNs = System.nanoTime();
    }

    /**
     * 获取系统可用内存（MB）。
     */
    public long getAvailableMemoryMb() {
        GlobalMemory mem = hardware.getMemory();
        return mem.getAvailable() / (1024 * 1024);
    }

    /**
     * 获取系统总内存（MB）。
     */
    public long getTotalMemoryMb() {
        GlobalMemory mem = hardware.getMemory();
        return mem.getTotal() / (1024 * 1024);
    }

    /**
     * 获取操作系统名称。
     */
    public String getOsName() {
        return os.toString();
    }

    /**
     * 推荐最大内存分配（MB）：总内存的一半，最大 8GB。
     */
    public long getRecommendedMaxMemoryMb() {
        long total = getTotalMemoryMb();
        long half = total / 2;
        return Math.min(half, 8192);
    }

    // ===== 实时性能监控 =====

    /**
     * 获取系统 CPU 使用率（0.0 ~ 1.0）。
     * 基于两次调用间的 tick 差异计算，首次调用返回 0。
     */
    public synchronized double getCpuLoad() {
        CentralProcessor cpu = hardware.getProcessor();
        long[] curTicks = cpu.getSystemCpuLoadTicks();
        double load = cpu.getSystemCpuLoadBetweenTicks(prevCpuTicks);
        prevCpuTicks = curTicks;
        return Math.max(0.0, Math.min(1.0, load));
    }

    /**
     * 获取 CPU 逻辑核心数。
     */
    public int getCpuLogicalCores() {
        return hardware.getProcessor().getLogicalProcessorCount();
    }

    /**
     * 获取 CPU 物理核心数。
     */
    public int getCpuPhysicalCores() {
        return hardware.getProcessor().getPhysicalProcessorCount();
    }

    /**
     * 获取 CPU 型号名称。
     */
    public String getCpuName() {
        return hardware.getProcessor().getProcessorIdentifier().getName();
    }

    /**
     * 获取系统内存使用率（0.0 ~ 1.0）。
     */
    public double getMemoryLoad() {
        GlobalMemory mem = hardware.getMemory();
        long total = mem.getTotal();
        long available = mem.getAvailable();
        if (total <= 0) return 0.0;
        return (double) (total - available) / (double) total;
    }

    /**
     * 获取 JVM 堆内存使用率（0.0 ~ 1.0）。
     */
    public double getJvmHeapLoad() {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        if (total <= 0) return 0.0;
        return (double) (total - free) / (double) total;
    }

    /**
     * 获取 JVM 已使用堆内存（MB）。
     */
    public long getJvmHeapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    /**
     * 获取 JVM 已分配堆内存（MB）。
     */
    public long getJvmHeapAllocatedMb() {
        return Runtime.getRuntime().totalMemory() / (1024 * 1024);
    }

    /**
     * 获取 JVM 最大可用堆内存（MB）。
     */
    public long getJvmHeapMaxMb() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    /**
     * 获取 JVM 线程数。
     */
    public int getJvmThreadCount() {
        return java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
    }

    /**
     * 获取系统主磁盘（PMCL 所在分区）的磁盘使用率（0.0 ~ 1.0）。
     */
    public double getMainDiskLoad() {
        try {
            List<OSFileStore> stores = os.getFileSystem().getFileStores();
            if (stores.isEmpty()) return 0.0;
            // 取第一个（通常是系统盘）
            OSFileStore store = stores.get(0);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total <= 0) return 0.0;
            return (double) (total - usable) / (double) total;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * 获取 PMCL 数据目录（~/.pmcl）所在磁盘的使用情况。
     * @return [已使用GB, 总容量GB, 使用率0~1]，失败返回 null
     */
    public double[] getPmclDiskUsage() {
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path pmclPath = java.nio.file.Paths.get(home, ".pmcl");
            java.io.File pmclDir = pmclPath.toFile();
            if (!pmclDir.exists()) pmclDir.mkdirs();
            long usable = pmclDir.getUsableSpace();
            long total = pmclDir.getTotalSpace();
            if (total <= 0) return null;
            long used = total - usable;
            double usedGb = used / (1024.0 * 1024 * 1024);
            double totalGb = total / (1024.0 * 1024 * 1024);
            double ratio = (double) used / (double) total;
            return new double[]{usedGb, totalGb, ratio};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 获取系统运行时长（秒）。
     */
    public long getSystemUptimeSeconds() {
        return os.getSystemUptime();
    }
}

