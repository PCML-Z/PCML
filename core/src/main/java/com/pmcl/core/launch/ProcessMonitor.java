package com.pmcl.core.launch;

import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.io.IOException;
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

    private OperatingSystem os;
    private GlobalMemory memory;
    private volatile long startTime = 0L;
    private volatile long lastPid = -1;

    public ProcessMonitor() {
    }

    /** 采样才需要 oshi。结束进程时构造它会扫硬件，退出会被拖住。 */
    private boolean ensureSystemInfo() {
        if (os != null && memory != null) return true;
        synchronized (this) {
            if (os != null && memory != null) return true;
            try {
                SystemInfo si = new SystemInfo();
                os = si.getOperatingSystem();
                memory = si.getHardware().getMemory();
                return true;
            } catch (Throwable t) {
                System.err.println("[ProcessMonitor] 系统信息不可用: " + t.getMessage());
                return false;
            }
        }
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
        if (pid > Integer.MAX_VALUE || !ensureSystemInfo()) {
            return new Sample(-1, 0, 0, 0, 0, alive);
        }
        OSProcess p = os.getProcess((int) pid);
        long rss = p != null ? p.getResidentSetSize() : 0;
        long vsize = p != null ? p.getVirtualSize() : 0;
        double cpu = p != null ? p.getProcessCpuLoadCumulative() * 100.0 : 0.0;
        long uptime = startTime > 0 ? System.currentTimeMillis() - startTime : 0;
        return new Sample((int) pid, cpu, rss, vsize, uptime, alive);
    }

    /** 强制销毁进程树（包括所有子进程）。单次最长约 12 秒，避免退出一直等。 */
    public boolean forceKill(Process process) {
        return forceKillUntil(process, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12));
    }

    /**
     * @param deadlineNanos 到点后只再 {@code destroyForcibly}，不再逐个等子进程
     */
    public boolean forceKillUntil(Process process, long deadlineNanos) {
        if (process == null) return true;
        if (!process.isAlive()) return true;
        if (System.nanoTime() >= deadlineNanos) {
            process.destroyForcibly();
            return !process.isAlive();
        }
        long pid = process.pid();
        try {
            String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            // 先杀所有子进程，防止父进程先死后留下孤儿。到点就停。
            try {
                process.descendants().forEach(ph -> {
                    if (System.nanoTime() >= deadlineNanos) return;
                    try { ph.destroyForcibly(); } catch (Exception ignored) {}
                    long left = deadlineNanos - System.nanoTime();
                    if (left <= 0) return;
                    long wait = Math.min(left, java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
                    try {
                        ph.onExit().get(wait, java.util.concurrent.TimeUnit.NANOSECONDS);
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
            if (System.nanoTime() < deadlineNanos
                    && (osName.contains("mac") || osName.contains("nux") || osName.contains("nix"))) {
                killDescendantsUnix(pid, 0, deadlineNanos);
            }
            process.destroyForcibly();
            long left = deadlineNanos - System.nanoTime();
            boolean exited = left <= 0 || process.waitFor(left, java.util.concurrent.TimeUnit.NANOSECONDS);
            if (!exited && System.nanoTime() < deadlineNanos) {
                Process killer = null;
                try {
                    if (osName.contains("win")) {
                        killer = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid)).start();
                    } else {
                        killer = new ProcessBuilder("kill", "-9", String.valueOf(pid)).start();
                    }
                    long killWait = Math.min(
                            deadlineNanos - System.nanoTime(),
                            java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
                    if (killWait > 0) killer.waitFor(killWait, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (Exception ignored) {
                } finally {
                    if (killer != null) killer.destroyForcibly();
                }
            }
            return !process.isAlive();
        } catch (Exception e) {
            System.err.println("[ProcessMonitor] forceKill 失败: " + e.getMessage());
            try { process.destroyForcibly(); } catch (Exception ignored) {}
            return !process.isAlive();
        }
    }

    /**
     * M77: Unix 下递归 kill 所有后代进程。
     * Process.descendants() 在 macOS 上可能不完整，用 pgrep -P 兜底。
     * 递归深度限制 8 层（足够覆盖 MC + LWJGL + JVM 子进程层级）。
     */
    private void killDescendantsUnix(long parentPid, int depth, long deadlineNanos) {
        if (depth > 8) return;
        if (parentPid <= 0 || parentPid > Integer.MAX_VALUE) return;
        if (System.nanoTime() >= deadlineNanos) return;
        // pgrep -P <pid> 列出指定父进程的所有子进程 pid
        // 关键：必须先 waitFor 再读取输出。若 readAllBytes 在 waitFor 之前，
        // 当 pgrep 因 /proc 异常或僵尸进程挂起时，readAllBytes 永不 EOF，应用退出被永久阻塞。
        Process p = null;
        try {
            p = new ProcessBuilder("pgrep", "-P", String.valueOf(parentPid))
                    .redirectErrorStream(true).start();
            // 异步排空 stdout，防止管道写满导致 pgrep 阻塞 write
            final Process pp = p;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread drainer = new Thread(() -> {
                try (java.io.InputStream is = pp.getInputStream()) {
                    is.transferTo(buf);
                } catch (IOException ignored) {}
            }, "pgrep-drainer");
            drainer.setDaemon(true);
            drainer.start();
            long leftMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (leftMs <= 0 || !p.waitFor(Math.min(2000, leftMs), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                try { drainer.join(200); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            try { drainer.join(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            String out = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            java.util.StringTokenizer st = new java.util.StringTokenizer(out.trim());
            while (st.hasMoreTokens()) {
                try {
                    long childPid = Long.parseLong(st.nextToken());
                    if (childPid <= 0) continue;
                    // 递归先杀孙进程，再杀子进程
                    killDescendantsUnix(childPid, depth + 1, deadlineNanos);
                    if (System.nanoTime() >= deadlineNanos) continue;
                    Process kp = null;
                    try {
                        kp = new ProcessBuilder("kill", "-9", String.valueOf(childPid)).start();
                        long killWait = Math.min(
                                deadlineNanos - System.nanoTime(),
                                java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
                        if (killWait > 0) kp.waitFor(killWait, java.util.concurrent.TimeUnit.NANOSECONDS);
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
        if (!ensureSystemInfo()) return 0L;
        return memory.getTotal() / (1024 * 1024);
    }

    public long totalSystemMemoryBytes() {
        try {
            if (!ensureSystemInfo()) return 0L;
            return Math.max(0L, memory.getTotal());
        } catch (Throwable t) {
            return 0L;
        }
    }

    public long availableSystemMemoryBytes() {
        try {
            if (!ensureSystemInfo()) return 0L;
            return Math.max(0L, memory.getAvailable());
        } catch (Throwable t) {
            return 0L;
        }
    }

    public long currentPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * 一次查出这几个进程的物理内存。查不到的 pid 不会出现在结果里。
     */
    public java.util.Map<Long, Usage> usageOf(java.util.Collection<Long> pids) {
        java.util.Map<Long, Usage> out = new java.util.LinkedHashMap<>();
        if (pids == null || pids.isEmpty()) return out;
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (Long pid : pids) {
            if (pid == null || pid <= 0 || pid > Integer.MAX_VALUE) continue;
            ids.add(pid.intValue());
        }
        if (ids.isEmpty() || !ensureSystemInfo()) return out;
        try {
            java.util.List<OSProcess> list = os.getProcesses(ids);
            if (list == null) return out;
            for (OSProcess p : list) {
                if (p == null) continue;
                java.util.List<String> args;
                try {
                    args = p.getArguments();
                } catch (Throwable ignored) {
                    args = java.util.List.of();
                }
                out.put((long) p.getProcessID(), new Usage(Math.max(0L, p.getResidentSetSize()), args));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 某个进程当前占用的物理内存，以及启动参数（用来读 -Xmx）。 */
    public static final class Usage {
        private final long rssBytes;
        private final java.util.List<String> arguments;

        public Usage(long rssBytes, java.util.List<String> arguments) {
            this.rssBytes = rssBytes;
            this.arguments = arguments == null ? java.util.List.of() : arguments;
        }

        public long getRssBytes() { return rssBytes; }
        public java.util.List<String> getArguments() { return arguments; }
    }
}
