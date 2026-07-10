package com.pmcl.core.runtime;

import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;

/**
 * Java 运行时与硬件信息管理。
 */
public final class RuntimeManager {

    private final SystemInfo systemInfo;

    public RuntimeManager() {
        this.systemInfo = new SystemInfo();
    }

    /**
     * 获取系统可用内存（MB）。
     */
    public long getAvailableMemoryMb() {
        GlobalMemory mem = systemInfo.getHardware().getMemory();
        return mem.getAvailable() / (1024 * 1024);
    }

    /**
     * 获取系统总内存（MB）。
     */
    public long getTotalMemoryMb() {
        GlobalMemory mem = systemInfo.getHardware().getMemory();
        return mem.getTotal() / (1024 * 1024);
    }

    /**
     * 获取操作系统名称。
     */
    public String getOsName() {
        return systemInfo.getOperatingSystem().toString();
    }

    /**
     * 推荐最大内存分配（MB）：总内存的一半，最大 8GB。
     */
    public long getRecommendedMaxMemoryMb() {
        long total = getTotalMemoryMb();
        long half = total / 2;
        return Math.min(half, 8192);
    }
}
