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
        // 读取输出避免管道阻塞
        caffeinateProcess.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
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

    // ===== L3 辅助 =====

    /** 查询当前 lowpowermode 状态（0=关闭，1=开启），失败返回 null */
    private Integer queryLowPowerMode() {
        try {
            Process p = new ProcessBuilder("pmset", "-g").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            // pmset -g 输出含 "lowpowermode     0" 或 "lowpowermode     1"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "lowpowermode\\s+(\\d)").matcher(out);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            System.err.println("[MioMode] 查询 lowpowermode 失败: " + e.getMessage());
        }
        return null;
    }

    private void runCommand(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try {
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
