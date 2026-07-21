package com.pmcl.core.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 澪模式进程级与系统级性能调优（L2 + L3）。
 * <p>
 * L2（进程级，无需 sudo）：
 * - macOS：taskpolicy -c user-active 提升 QoS + caffeinate -i -w 防休眠
 * - Windows：wmic 设置高优先级
 * - Linux：renice -n -5
 * <p>
 * L3（系统级，需 sudo，仅 macOS）：
 * - pmset -a lowpowermode 0 关闭低电量模式
 * - 游戏退出后恢复原始状态
 * <p>
 * 重要诚实声明：macOS 硬件级热降频无法通过软件禁用，本类只能抑制低功耗状态、
 * 提升调度优先级，不能真正突破物理热限制。
 */
public final class ProcessTuner {

    private final boolean isMac;
    private final boolean isWindows;
    private final boolean isLinux;

    private Process caffeinateProcess;   // L2：防休眠子进程
    private Integer originalLowPowerMode; // L3：原始低电量模式状态，用于恢复

    public ProcessTuner() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        this.isMac = os.contains("mac");
        this.isWindows = os.contains("win");
        this.isLinux = os.contains("nux") || os.contains("nix");
    }

    /**
     * 应用 L2 进程级调优（启动后调用）。
     * 失败静默降级，不阻塞游戏运行。
     */
    public void applyProcessTuning(long pid) {
        if (pid <= 0) return;
        try {
            if (isMac) {
                applyMacQos(pid);
                startCaffeinate(pid);
            } else if (isWindows) {
                applyWindowsPriority(pid);
            } else if (isLinux) {
                applyLinuxNice(pid);
            }
        } catch (Exception e) {
            System.err.println("[MioMode] L2 进程调优失败，降级: " + e.getMessage());
        }
    }

    /**
     * 疯狂优先级：跨平台拉到系统允许的调度优先级极限。
     * <p>
     * - macOS：taskpolicy -P high（进程 priority 提升至 high）+ renice -20（最高 nice，
     *   需 sudo，用 osascript 弹授权框）
     * - Windows：REALTIME_PRIORITY_CLASS (256)，抢占式实时优先级
     * - Linux：renice -20（需 sudo，用 pkexec 或 osascript 无关，直接 sudo 调用）
     * <p>
     * 风险：可能导致系统响应性下降（鼠标/键盘卡顿），用户主动选择。
     * 失败静默降级，不阻塞游戏运行。
     */
    public void applyCrazyPriority(long pid) {
        if (pid <= 0) return;
        try {
            if (isMac) {
                applyMacCrazy(pid);
            } else if (isWindows) {
                applyWindowsRealtime(pid);
            } else if (isLinux) {
                applyLinuxCrazy(pid);
            }
        } catch (Exception e) {
            System.err.println("[MioMode] 疯狂优先级应用失败，降级: " + e.getMessage());
        }
    }

    /**
     * 应用 L3 系统电源策略（启动前调用，需 sudo）。
     * 使用 osascript 弹原生授权框，用户拒绝则静默降级。
     *
     * @return true 表示成功应用，false 表示用户拒绝或失败
     */
    public boolean applySystemPowerPolicy() {
        if (!isMac) return false;
        try {
            // 先记录原始 lowpowermode 状态用于恢复
            originalLowPowerMode = queryLowPowerMode();
            // 用 osascript 弹原生授权框执行 sudo pmset
            String script = String.format(
                "do shell script \"pmset -a lowpowermode 0\" with administrator privileges");
            runCommand("osascript", "-e", script);
            System.out.println("[MioMode] L3 已关闭低电量模式（原值=" + originalLowPowerMode + "）");
            return true;
        } catch (Exception e) {
            System.err.println("[MioMode] L3 系统电源策略应用失败（用户拒绝或不可用）: " + e.getMessage());
            originalLowPowerMode = null;
            return false;
        }
    }

    /**
     * 清理所有调优状态（游戏退出后调用）。
     * 必须在 finally 块中调用以确保恢复。
     */
    public void cleanup() {
        // L2：终止 caffeinate 子进程
        if (caffeinateProcess != null && caffeinateProcess.isAlive()) {
            try {
                caffeinateProcess.destroy();
                if (!caffeinateProcess.waitFor(2, TimeUnit.SECONDS)) {
                    caffeinateProcess.destroyForcibly();
                }
            } catch (Exception e) {
                System.err.println("[MioMode] 终止 caffeinate 失败: " + e.getMessage());
            }
            caffeinateProcess = null;
        }
        // L3：恢复原始低电量模式
        if (originalLowPowerMode != null && isMac) {
            try {
                String script = String.format(
                    "do shell script \"pmset -a lowpowermode %d\" with administrator privileges",
                    originalLowPowerMode);
                runCommand("osascript", "-e", script);
                System.out.println("[MioMode] L3 已恢复低电量模式=" + originalLowPowerMode);
            } catch (Exception e) {
                System.err.println("[MioMode] L3 恢复低电量模式失败: " + e.getMessage());
                // 恢复失败时写入备份文件，下次启动可尝试恢复
                try {
                    Path backup = Paths.get(System.getProperty("user.home"), ".pmcl", "mio_pmset_backup.txt");
                    Files.writeString(backup, String.valueOf(originalLowPowerMode));
                } catch (Exception ignored) {}
            }
            originalLowPowerMode = null;
        }
    }

    // ===== L2 平台实现 =====

    private void applyMacQos(long pid) throws IOException {
        // taskpolicy -c user-active：标记为用户活跃，macOS 最高调度优先级 tier
        // Apple Silicon 上会让系统优先分配 P-core
        runCommand("taskpolicy", "-c", "user-active", "-p", String.valueOf(pid));
        System.out.println("[MioMode] L2 已提升 macOS QoS: pid=" + pid);
    }

    private void startCaffeinate(long pid) throws IOException {
        // caffeinate -i -w <pid>：抑制 idle sleep，attach 到游戏 PID
        // 游戏退出后 caffeinate 自动结束（-w 语义）
        ProcessBuilder pb = new ProcessBuilder(
            "caffeinate", "-i", "-w", String.valueOf(pid));
        pb.redirectErrorStream(true);
        caffeinateProcess = pb.start();
        // M79: transferTo 会阻塞调用线程直到 EOF（即 caffeinate 退出），
        // 与 -w（等待游戏退出）语义叠加后会一直阻塞主线程。
        // 改用守护线程异步读取输出，避免阻塞 UI/启动线程。
        Thread reader = new Thread(() -> {
            try (java.io.InputStream is = caffeinateProcess.getInputStream()) {
                is.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // 进程被销毁时读取会失败，可忽略
            }
        }, "mio-caffeinate-reader");
        reader.setDaemon(true);
        reader.start();
        System.out.println("[MioMode] L2 已启动 caffeinate 防休眠: pid=" + pid);
    }

    private void applyWindowsPriority(long pid) throws IOException {
        // 128 = HIGH_PRIORITY_CLASS（实时优先级 256 会影响系统响应，不使用）
        runCommand("wmic", "process", "where", "processid=" + pid,
            "call", "setpriority", "128");
        System.out.println("[MioMode] L2 已提升 Windows 进程优先级: pid=" + pid);
    }

    private void applyLinuxNice(long pid) throws IOException {
        // 普通用户最多 nice -n 到 0（从默认 0 无变化），负值需 root
        // 这里用 renice 尝试，失败静默降级
        runCommand("renice", "-n", "0", "-p", String.valueOf(pid));
        System.out.println("[MioMode] L2 已尝试 Linux renice: pid=" + pid);
    }

    // ===== 疯狂优先级平台实现 =====

    private void applyMacCrazy(long pid) throws IOException {
        // 1. taskpolicy -P high：进程 priority 提升至 high tier（与 -c user-active 互补）
        try {
            runCommand("taskpolicy", "-P", "high", "-p", String.valueOf(pid));
            System.out.println("[MioMode] 疯狂: macOS taskpolicy -P high 已应用 pid=" + pid);
        } catch (Exception e) {
            System.err.println("[MioMode] taskpolicy -P high 失败: " + e.getMessage());
        }
        // 2. renice -20：最高 nice 优先级，需 sudo（普通用户无法设负值）
        // 用 osascript 弹原生授权框执行 sudo renice
        try {
            String script = String.format(
                "do shell script \"renice -20 -p %d\" with administrator privileges", pid);
            runCommand("osascript", "-e", script);
            System.out.println("[MioMode] 疯狂: macOS renice -20 已应用 pid=" + pid);
        } catch (Exception e) {
            System.err.println("[MioMode] renice -20 失败（用户拒绝授权或不可用）: " + e.getMessage());
        }
    }

    private void applyWindowsRealtime(long pid) throws IOException {
        // 256 = REALTIME_PRIORITY_CLASS：抢占式实时优先级
        // 风险：可能导致鼠标/键盘卡顿，但用户主动选择疯狂模式
        runCommand("wmic", "process", "where", "processid=" + pid,
            "call", "setpriority", "256");
        System.out.println("[MioMode] 疯狂: Windows REALTIME 优先级已应用 pid=" + pid);
    }

    private void applyLinuxCrazy(long pid) throws IOException {
        // renice -20：最高 nice 优先级，需 root
        // 尝试用 sudo（会提示密码），失败则尝试 pkexec（GUI 授权）
        try {
            runCommand("sudo", "-n", "renice", "-20", "-p", String.valueOf(pid));
            System.out.println("[MioMode] 疯狂: Linux renice -20 (sudo) 已应用 pid=" + pid);
        } catch (Exception e) {
            // sudo -n 非交互模式失败，尝试 pkexec 弹 GUI 授权框
            try {
                runCommand("pkexec", "renice", "-20", "-p", String.valueOf(pid));
                System.out.println("[MioMode] 疯狂: Linux renice -20 (pkexec) 已应用 pid=" + pid);
            } catch (Exception e2) {
                System.err.println("[MioMode] Linux renice -20 失败（需 root）: " + e2.getMessage());
            }
        }
    }

    // ===== L3 辅助 =====

    /** 查询当前 lowpowermode 状态（0=关闭，1=开启），失败返回 null */
    private Integer queryLowPowerMode() {
        // M78: try-finally 确保 Process 被销毁（Process 未实现 AutoCloseable）
        Process p = null;
        try {
            p = new ProcessBuilder("pmset", "-g").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                return null;
            }
            // pmset -g 输出含 "lowpowermode     0" 或 "lowpowermode     1"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "lowpowermode\\s+(\\d)").matcher(out);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            System.err.println("[MioMode] 查询 lowpowermode 失败: " + e.getMessage());
        } finally {
            if (p != null) p.destroyForcibly();
        }
        return null;
    }

    private void runCommand(String... cmd) throws IOException {
        // M78: try-finally 确保 Process 被销毁
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                // 超时不抛异常：调用方依赖 runCommand 静默降级语义
                // finally 中 destroyForcibly 兜底销毁
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }
}
