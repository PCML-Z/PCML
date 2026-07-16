package com.pmcl.core.launch;

import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.Optional;

/**
 * 游戏进程监控：通过 oshi 获取子进程的 CPU、内存使用情况。
 * <p>
 * 用法：进程启动后，UI 定期调用 {@link #sample(Process)} 拿到当前快照。
 */
public final class ProcessMonitor {

    public static final class Sample {
        private final int pid;
        private final double cpuPercent;       // 0-100
        private final long rssBytes;           // 物理内存
        private final long virtualBytes;       // 虚拟内存
        private final long uptimeMs;
        private final boolean alive;

        public Sample(int pid, double cpuPercent, long rssBytes, long virtualBytes,
                      long uptimeMs, boolean alive) {
            this.pid = pid; this.cpuPercent = cpuPercent;
            this.rssBytes = rssBytes; this.virtualBytes = virtualBytes;
            this.uptimeMs = uptimeMs; this.alive = alive;
        }
        public int getPid() { return pid; }
        public double getCpuPercent() { return cpuPercent; }
        public long getRssBytes() { return rssBytes; }
        public long getVirtualBytes() { return virtualBytes; }
        public long getUptimeMs() { return uptimeMs; }
        public boolean isAlive() { return alive; }

        public long getRssMb() { return rssBytes / (1024 * 1024); }
    }

    private final OperatingSystem os;
    private final GlobalMemory memory;
    private volatile long startTime = 0L;
    private volatile long lastPid = -1;

    public ProcessMonitor() {
        SystemInfo si = new SystemInfo();
        this.os = si.getOperatingSystem();
        this.memory = si.getHardware().getMemory();
    }

    /** 标记进程启动时刻 */
    public void startTracking(Process process) {
        this.startTime = System.currentTimeMillis();
        this.lastPid = process.pid();
    }

    /** 采样当前进程状态 */
    public Sample sample(Process process) {
        long pid = process.isAlive() ? process.pid() : lastPid;
        boolean alive = process.isAlive();
        if (pid < 0) {
            return new Sample(-1, 0, 0, 0, 0, false);
        }
        OSProcess p = os.getProcess((int) pid);
        long rss = p != null ? p.getResidentSetSize() : 0;
        long vsize = p != null ? p.getVirtualSize() : 0;
        double cpu = p != null ? p.getProcessCpuLoadCumulative() * 100.0 : 0.0;
        long uptime = startTime > 0 ? System.currentTimeMillis() - startTime : 0;
        return new Sample((int) pid, cpu, rss, vsize, uptime, alive);
    }

    /** 强制销毁进程树（包括所有子进程） */
    public boolean forceKill(Process process) {
        if (!process.isAlive()) return true;
        try {
            // 先杀所有子进程（Minecraft 会 fork LWJGL native 线程等），防止孤儿进程
            try {
                process.descendants().forEach(ph -> {
                    try { ph.destroyForcibly(); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
            // 再杀主进程
            process.destroyForcibly();
            boolean exited = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                // 通过操作系统 kill -9
                long pid = process.pid();
                Runtime rt = Runtime.getRuntime();
                String osName = System.getProperty("os.name").toLowerCase();
                Process killer;
                if (osName.contains("win")) {
                    killer = rt.exec(new String[]{"taskkill", "/F", "/T", "/PID", String.valueOf(pid)});
                } else {
                    killer = rt.exec(new String[]{"kill", "-9", String.valueOf(pid)});
                }
                killer.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            return !process.isAlive();
        } catch (Exception e) {
            System.err.println("[ProcessMonitor] forceKill 失败: " + e.getMessage());
            return false;
        }
    }

    public long getTotalSystemMemoryMb() {
        return memory.getTotal() / (1024 * 1024);
    }
}
