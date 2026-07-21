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
        // M77: pid 是 long，oshi.getProcess 接收 int 参数；现代系统 pid 可能超过 Integer.MAX_VALUE
        // （极罕见，但 Linux 用户态 pid_max 上限是 4194304，macOS 是 999999，均在 int 范围内）。
        // 仍做范围校验防止异常大值导致强转溢出为负数。
        if (pid > Integer.MAX_VALUE) {
            return new Sample(-1, 0, 0, 0, 0, alive);
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
        long pid = process.pid();
        try {
            // 先杀所有子进程（Minecraft 会 fork LWJGL native 线程等），防止孤儿进程
            try {
                process.descendants().forEach(ph -> {
                    try { ph.destroyForcibly(); } catch (Exception ignored) {}
                    // 等待子进程退出，避免父进程先死导致孤儿（ProcessHandle 无 waitFor(timeout)，
                    // 用 onExit().get(timeout) 等待退出 future）
                    try {
                        ph.onExit().get(1, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
            // M77: macOS 上 Process.descendants() 可能不完整（JDK 实现依赖操作系统特定 API）。
            // 用 pgrep -P 兜底查询直接子进程并 kill -9，递归处理孙进程。
            String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (osName.contains("mac") || osName.contains("nux") || osName.contains("nix")) {
                killDescendantsUnix(pid);
            }
            // 再杀主进程
            process.destroyForcibly();
            boolean exited = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                // 通过操作系统 kill -9
                Runtime rt = Runtime.getRuntime();
                Process killer;
                if (osName.contains("win")) {
                    killer = rt.exec(new String[]{"taskkill", "/F", "/T", "/PID", String.valueOf(pid)});
                } else {
                    killer = rt.exec(new String[]{"kill", "-9", String.valueOf(pid)});
                }
                try {
                    killer.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {} finally {
                    killer.destroyForcibly();
                }
            }
            return !process.isAlive();
        } catch (Exception e) {
            System.err.println("[ProcessMonitor] forceKill 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * M77: Unix 下递归 kill 所有后代进程。
     * Process.descendants() 在 macOS 上可能不完整，用 pgrep -P 兜底。
     * 递归深度限制 8 层（足够覆盖 MC + LWJGL + JVM 子进程层级）。
     */
    private void killDescendantsUnix(long parentPid) {
        killDescendantsUnix(parentPid, 0);
    }

    private void killDescendantsUnix(long parentPid, int depth) {
        if (depth > 8) return; // 防御性深度限制
        if (parentPid <= 0 || parentPid > Integer.MAX_VALUE) return;
        // pgrep -P <pid> 列出指定父进程的所有子进程 pid
        Process p = null;
        try {
            p = new ProcessBuilder("pgrep", "-P", String.valueOf(parentPid))
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) return;
            java.util.StringTokenizer st = new java.util.StringTokenizer(out.trim());
            while (st.hasMoreTokens()) {
                try {
                    long childPid = Long.parseLong(st.nextToken());
                    if (childPid <= 0) continue;
                    // 递归先杀孙进程，再杀子进程
                    killDescendantsUnix(childPid, depth + 1);
                    Process kp = null;
                    try {
                        kp = new ProcessBuilder("kill", "-9", String.valueOf(childPid)).start();
                        kp.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception ignored) {} finally {
                        if (kp != null) kp.destroyForcibly();
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {
            // pgrep 不可用或失败，依赖 Process.descendants() 已处理
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    public long getTotalSystemMemoryMb() {
        return memory.getTotal() / (1024 * 1024);
    }
}
