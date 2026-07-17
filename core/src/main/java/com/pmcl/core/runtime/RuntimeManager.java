package com.pmcl.core.runtime;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.util.List;

/**
 * Java 运行时与硬件信息管理。
 */
public final class RuntimeManager {

    private SystemInfo systemInfo;
    private HardwareAbstractionLayer hardware;
    private OperatingSystem os;
    private volatile boolean available = false;

    // CPU 负载计算所需的上一次采样
    private long[] prevCpuTicks;
    private long prevCpuTimeNs;

    // 网络流量计算所需的上一次采样
    private long prevNetBytesSent;
    private long prevNetBytesRecv;
    private long prevNetTimeNs;
    private boolean netInitialized = false;

    public RuntimeManager() {
        SystemInfo si = null;
        HardwareAbstractionLayer hw = null;
        OperatingSystem op = null;
        try {
            si = new SystemInfo();
            hw = si.getHardware();
            op = si.getOperatingSystem();
            // 初始化 CPU ticks
            this.prevCpuTicks = hw.getProcessor().getSystemCpuLoadTicks();
            this.prevCpuTimeNs = System.nanoTime();
            this.available = true;
        } catch (Throwable t) {
            // oshi/JNA 在非主流架构或原生库缺失时可能抛 UnsatisfiedLinkError，降级为空实现
            System.err.println("[RuntimeManager] oshi 初始化失败，降级为空实现: " + t);
        }
        this.systemInfo = si;
        this.hardware = hw;
        this.os = op;
    }

    /**
     * 获取系统可用内存（MB）。
     */
    public long getAvailableMemoryMb() {
        if (!available) return 0;
        GlobalMemory mem = hardware.getMemory();
        return mem.getAvailable() / (1024 * 1024);
    }

    /**
     * 获取系统总内存（MB）。
     */
    public long getTotalMemoryMb() {
        if (!available) return 0;
        GlobalMemory mem = hardware.getMemory();
        return mem.getTotal() / (1024 * 1024);
    }

    /**
     * 获取操作系统名称。
     */
    public String getOsName() {
        if (!available) return "未知";
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
        if (!available) return 0.0;
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
        if (!available) return 0;
        return hardware.getProcessor().getLogicalProcessorCount();
    }

    /**
     * 获取 CPU 物理核心数。
     */
    public int getCpuPhysicalCores() {
        if (!available) return 0;
        return hardware.getProcessor().getPhysicalProcessorCount();
    }

    /**
     * 获取 CPU 型号名称。
     */
    public String getCpuName() {
        if (!available) return "未知";
        return hardware.getProcessor().getProcessorIdentifier().getName();
    }

    /**
     * 获取系统内存使用率（0.0 ~ 1.0）。
     */
    public double getMemoryLoad() {
        if (!available) return 0.0;
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
        if (!available) return 0.0;
        try {
            List<OSFileStore> stores = os.getFileSystem().getFileStores();
            if (stores.isEmpty()) return 0.0;
            // 找到包含工作目录所在盘的 store；找不到则回退第一个
            OSFileStore matched = null;
            try {
                java.nio.file.Path cwdRoot = java.nio.file.Paths.get(".").toAbsolutePath().getRoot();
                String cwdRootStr = cwdRoot != null ? cwdRoot.toString() : null;
                for (OSFileStore s : stores) {
                    String mount = s.getMount();
                    if (mount == null) continue;
                    if (cwdRootStr != null && cwdRootStr.equalsIgnoreCase(mount)) {
                        matched = s;
                        break;
                    }
                }
            } catch (Throwable ignored) {}
            if (matched == null) matched = stores.get(0);
            long total = matched.getTotalSpace();
            long usable = matched.getUsableSpace();
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
        if (!available) return 0;
        return os.getSystemUptime();
    }

    // ===== 网络流量监控 =====

    /**
     * 获取当前网络速率（KB/s）。
     * 基于两次调用间的字节差异计算，首次调用返回 [0,0]。
     * @return [上传KB/s, 下载KB/s]
     */
    public synchronized double[] getNetworkSpeedKbS() {
        if (!available) return new double[]{0, 0};
        long curSent = 0, curRecv = 0;
        try {
            List<NetworkIF> nifs = hardware.getNetworkIFs();
            for (NetworkIF nif : nifs) {
                String name = nif.getName();
                if (name == null) continue;
                // 过滤虚拟接口（docker/veth/桥接/隧道等），避免 Windows 上负值或异常流量
                if (isVirtualInterfaceName(name)) continue;
                try {
                    java.net.NetworkInterface ni = java.net.NetworkInterface.getByName(name);
                    if (ni != null && (ni.isLoopback() || ni.isVirtual() || !ni.isUp())) continue;
                } catch (Throwable ignored) {}
                nif.updateAttributes();
                curSent += nif.getBytesSent();
                curRecv += nif.getBytesRecv();
            }
        } catch (Throwable t) {
            return new double[]{0, 0};
        }

        long nowNs = System.nanoTime();
        if (!netInitialized) {
            prevNetBytesSent = curSent;
            prevNetBytesRecv = curRecv;
            prevNetTimeNs = nowNs;
            netInitialized = true;
            return new double[]{0, 0};
        }

        long elapsedNs = nowNs - prevNetTimeNs;
        if (elapsedNs <= 0) return new double[]{0, 0};

        double elapsedSec = elapsedNs / 1_000_000_000.0;
        double upKbS = (curSent - prevNetBytesSent) / 1024.0 / elapsedSec;
        double downKbS = (curRecv - prevNetBytesRecv) / 1024.0 / elapsedSec;

        prevNetBytesSent = curSent;
        prevNetBytesRecv = curRecv;
        prevNetTimeNs = nowNs;

        return new double[]{Math.max(0, upKbS), Math.max(0, downKbS)};
    }

    private static boolean isVirtualInterfaceName(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("docker") || n.startsWith("veth") || n.startsWith("br-")
                || n.startsWith("virbr") || n.startsWith("tun") || n.startsWith("utun")
                || n.startsWith("tap") || n.startsWith("vnic") || n.startsWith("vmnet")
                || n.equals("lo");
    }

    // ===== 显卡信息 =====

    /**
     * 获取显卡信息列表。
     * @return 显卡名称数组，无则返回空数组
     */
    public List<GraphicsCard> getGraphicsCards() {
        if (!available) return java.util.Collections.emptyList();
        try {
            return hardware.getGraphicsCards();
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 获取主显卡名称（第一个非虚拟显卡）。
     */
    public String getPrimaryGpuName() {
        if (!available) return "未知";
        try {
            List<GraphicsCard> cards = hardware.getGraphicsCards();
            for (GraphicsCard c : cards) {
                String name = c.getName();
                if (name != null && !name.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).contains("virtual")) {
                    return name;
                }
            }
            if (!cards.isEmpty()) return cards.get(0).getName();
        } catch (Throwable ignored) {}
        return "未知";
    }

    /**
     * 获取主显卡显存（MB），不可用时返回 -1。
     */
    public long getPrimaryGpuVramMb() {
        if (!available) return -1;
        try {
            List<GraphicsCard> cards = hardware.getGraphicsCards();
            for (GraphicsCard c : cards) {
                long vram = c.getVRam();
                if (vram > 0) return vram / (1024 * 1024);
            }
        } catch (Throwable ignored) {}
        return -1;
    }
}

