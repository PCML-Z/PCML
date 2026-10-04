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
 * - Linux：renice -n 0；可选 GameMode、systemd-inhibit
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

    private volatile Process caffeinateProcess;   // L2：防休眠子进程
    private volatile Process keepAwakeProcess;    // L2+：Windows / Linux 保持亮屏
    private boolean caffeinateDisplay;            // 当前 caffeinate 是否已经挡住熄屏
    private long gameModePid;                     // L2+：已向 GameMode 注册的进程
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
                startCaffeinate(pid, false);
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
     *
     * @return true 表示关键步骤成功（或本平台无需提权）；false 表示用户拒绝管理员授权或提权失败
     */
    public boolean applyCrazyPriority(long pid) {
        if (pid <= 0) return false;
        try {
            if (isMac) {
                return applyMacCrazy(pid);
            } else if (isWindows) {
                applyWindowsRealtime(pid);
                return true;
            } else if (isLinux) {
                return applyLinuxCrazy(pid);
            }
        } catch (Exception e) {
            System.err.println("[MioMode] 疯狂优先级应用失败，降级: " + e.getMessage());
        }
        return false;
    }

    /**
     * 游戏运行期间不让屏幕和系统休眠。退出后由 {@link #cleanup()} 恢复。
     * 失败只记日志，不让启动失败。
     */
    public void applyKeepAwake(long pid) {
        if (pid <= 0) return;
        try {
            if (isMac) {
                startCaffeinate(pid, true);
            } else if (isWindows) {
                startWindowsAwake();
            } else if (isLinux) {
                startLinuxInhibit();
            }
        } catch (Exception e) {
            System.err.println("[MioMode] 保持亮屏失败，降级: " + e.getMessage());
        }
    }

    /**
     * Linux：向 Feral GameMode 注册这个进程。没安装或不是 Linux 时直接返回。
     */
    public void applyGameMode(long pid) {
        if (!isLinux || pid <= 0 || pid > Integer.MAX_VALUE) return;
        try {
            runCommand("busctl", "--user", "call",
                    "com.feralinteractive.GameMode",
                    "/com/feralinteractive/GameMode",
                    "com.feralinteractive.GameMode",
                    "RegisterGame", "i", String.valueOf(pid));
            gameModePid = pid;
            System.out.println("[MioMode] L2+ 已注册 GameMode: pid=" + pid);
        } catch (Exception e) {
            System.err.println("[MioMode] GameMode 未注册（未安装或服务没开）: " + e.getMessage());
        }
    }

    /**
     * 应用 L3 系统电源策略（需 sudo）。
     * 使用 osascript 弹原生授权框，用户拒绝则静默降级。
     * <p>
     * 应在游戏进程已启动之后调用，避免启动前卡住。
     *
     * @return true 表示成功应用，false 表示用户拒绝或失败
     */
    public boolean applySystemPowerPolicy() {
        if (!isMac) return false;
        try {
            // 先记录原始 lowpowermode 状态用于恢复
            originalLowPowerMode = queryLowPowerMode();
            // 用 osascript 弹原生授权框执行 sudo pmset（用户可能需要较长时间输入密码）
            String script =
                "do shell script \"pmset -a lowpowermode 0\" with administrator privileges";
            runPrivilegedOsascript(script);
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
     * <p>
     * L3 恢复若再次弹授权框且用户拒绝，只写入备份文件，不再阻塞。
     */
    public void cleanup() {
        stopHelper(caffeinateProcess, "caffeinate");
        caffeinateProcess = null;
        caffeinateDisplay = false;
        stopHelper(keepAwakeProcess, "keep-awake");
        keepAwakeProcess = null;
        unregisterGameMode();
        // L3：恢复原始低电量模式（可能再次弹授权；失败则写备份，避免反复打扰）
        if (originalLowPowerMode != null && isMac) {
            Integer restore = originalLowPowerMode;
            originalLowPowerMode = null;
            try {
                String script = String.format(
                    "do shell script \"pmset -a lowpowermode %d\" with administrator privileges",
                    restore);
                runPrivilegedOsascript(script);
                System.out.println("[MioMode] L3 已恢复低电量模式=" + restore);
            } catch (Exception e) {
                System.err.println("[MioMode] L3 恢复低电量模式失败: " + e.getMessage());
                try {
                    Path backup = com.pmcl.core.LauncherConfig.pmclHome().resolve("mio_pmset_backup.txt");
                    Files.createDirectories(backup.getParent());
                    Files.writeString(backup, String.valueOf(restore));
                } catch (Exception ignored) {}
            }
        }
    }

    // ===== L2 平台实现 =====

    private void applyMacQos(long pid) throws IOException {
        // taskpolicy -c user-active：标记为用户活跃，macOS 最高调度优先级 tier
        // Apple Silicon 上会让系统优先分配 P-core
        runCommand("taskpolicy", "-c", "user-active", "-p", String.valueOf(pid));
        System.out.println("[MioMode] L2 已提升 macOS QoS: pid=" + pid);
    }

    private void startCaffeinate(long pid, boolean display) throws IOException {
        // 已经挡住熄屏时，普通防休眠不用再降回去。
        if (caffeinateProcess != null && caffeinateProcess.isAlive()) {
            if (!display || caffeinateDisplay) return;
            stopHelper(caffeinateProcess, "caffeinate");
            caffeinateProcess = null;
        }
        // -w：游戏退出后 caffeinate 自己结束。display 时再挡住熄屏、磁盘和系统休眠。
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("caffeinate");
        if (display) {
            cmd.add("-d");
            cmd.add("-i");
            cmd.add("-m");
            cmd.add("-s");
        } else {
            cmd.add("-i");
        }
        cmd.add("-w");
        cmd.add(String.valueOf(pid));
        caffeinateProcess = startBackground(cmd, "mio-caffeinate-reader");
        caffeinateDisplay = display;
        System.out.println("[MioMode] L2 已启动 caffeinate"
                + (display ? "（含亮屏）" : "") + ": pid=" + pid);
    }

    private void startWindowsAwake() throws IOException {
        if (keepAwakeProcess != null && keepAwakeProcess.isAlive()) return;
        // 0x80000003 = ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED
        // 这个 powershell 活着期间生效，cleanup 结束它就恢复。
        String script = "$es = @'\n"
                + "using System;\n"
                + "using System.Runtime.InteropServices;\n"
                + "public class MioStay {\n"
                + "  [DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);\n"
                + "}\n"
                + "'@\n"
                + "Add-Type -TypeDefinition $es -Language CSharp\n"
                + "[MioStay]::SetThreadExecutionState([uint32]2147483651) | Out-Null\n"
                + "while ($true) { Start-Sleep -Seconds 20 }\n";
        keepAwakeProcess = startBackground(java.util.List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-Command", script), "mio-awake-reader");
        System.out.println("[MioMode] L2+ Windows 已保持亮屏");
    }

    private void startLinuxInhibit() throws IOException {
        if (keepAwakeProcess != null && keepAwakeProcess.isAlive()) return;
        keepAwakeProcess = startBackground(java.util.List.of(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=PMCL",
                "--why=Mio",
                "--mode=block",
                "sleep", "infinity"), "mio-inhibit-reader");
        System.out.println("[MioMode] L2+ Linux 已抑制休眠");
    }

    private void unregisterGameMode() {
        long pid = gameModePid;
        gameModePid = 0;
        if (pid <= 0) return;
        try {
            runCommand("busctl", "--user", "call",
                    "com.feralinteractive.GameMode",
                    "/com/feralinteractive/GameMode",
                    "com.feralinteractive.GameMode",
                    "UnregisterGame", "i", String.valueOf(pid));
            System.out.println("[MioMode] L2+ 已注销 GameMode: pid=" + pid);
        } catch (Exception e) {
            System.err.println("[MioMode] 注销 GameMode 失败: " + e.getMessage());
        }
    }

    private Process startBackground(java.util.List<String> cmd, String threadName) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        Thread reader = new Thread(() -> {
            try (java.io.InputStream is = process.getInputStream()) {
                is.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // 进程被销毁时读取会失败，可忽略
            }
        }, threadName);
        reader.setDaemon(true);
        reader.start();
        return process;
    }

    private void stopHelper(Process process, String name) {
        if (process == null || !process.isAlive()) return;
        try {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            System.err.println("[MioMode] 终止 " + name + " 失败: " + e.getMessage());
        }
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

    private boolean applyMacCrazy(long pid) throws IOException {
        // 1. taskpolicy -P high：进程 priority 提升至 high tier（与 -c user-active 互补）
        try {
            runCommand("taskpolicy", "-P", "high", "-p", String.valueOf(pid));
            System.out.println("[MioMode] 疯狂: macOS taskpolicy -P high 已应用 pid=" + pid);
        } catch (Exception e) {
            System.err.println("[MioMode] taskpolicy -P high 失败: " + e.getMessage());
        }
        // 2. renice -20：最高 nice 优先级，需 sudo（普通用户无法设负值）
        try {
            String script = String.format(
                "do shell script \"renice -20 -p %d\" with administrator privileges", pid);
            runPrivilegedOsascript(script);
            System.out.println("[MioMode] 疯狂: macOS renice -20 已应用 pid=" + pid);
            return true;
        } catch (Exception e) {
            System.err.println("[MioMode] renice -20 失败（用户拒绝授权或不可用）: " + e.getMessage());
            // 提权被拒时返回 false，便于自动关闭该选项，避免下次启动再弹密码框
            return false;
        }
    }

    private void applyWindowsRealtime(long pid) throws IOException {
        // 256 = REALTIME_PRIORITY_CLASS：抢占式实时优先级
        // 风险：可能导致鼠标/键盘卡顿，但用户主动选择疯狂模式
        runCommand("wmic", "process", "where", "processid=" + pid,
            "call", "setpriority", "256");
        System.out.println("[MioMode] 疯狂: Windows REALTIME 优先级已应用 pid=" + pid);
    }

    /** @return true 提权 renice 成功 */
    private boolean applyLinuxCrazy(long pid) throws IOException {
        // renice -20：最高 nice 优先级，需 root
        try {
            runCommand("sudo", "-n", "renice", "-20", "-p", String.valueOf(pid));
            System.out.println("[MioMode] 疯狂: Linux renice -20 (sudo) 已应用 pid=" + pid);
            return true;
        } catch (Exception e) {
            try {
                runCommandLong(120, "pkexec", "renice", "-20", "-p", String.valueOf(pid));
                System.out.println("[MioMode] 疯狂: Linux renice -20 (pkexec) 已应用 pid=" + pid);
                return true;
            } catch (Exception e2) {
                System.err.println("[MioMode] Linux renice -20 失败（需 root）: " + e2.getMessage());
                return false;
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
            final Process fp = p;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            Thread drainer = new Thread(() -> {
                try (java.io.InputStream is = fp.getInputStream()) { is.transferTo(bos); }
                catch (IOException ignored) {}
            });
            drainer.setDaemon(true);
            drainer.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                try { drainer.join(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return null;
            }
            try { drainer.join(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            String out = bos.toString(java.nio.charset.StandardCharsets.UTF_8);
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

    private void runPrivilegedOsascript(String appleScript) throws IOException {
        runCommandInternal(120, "osascript", "-e", appleScript);
    }

    private void runCommand(String... cmd) throws IOException {
        runCommandInternal(5, cmd);
    }

    private void runCommandLong(int timeoutSec, String... cmd) throws IOException {
        runCommandInternal(timeoutSec, cmd);
    }

    private void runCommandInternal(int timeoutSec, String... cmd) throws IOException {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean exited = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                try { p.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("命令超时(" + timeoutSec + "s): " + String.join(" ", cmd));
            }
            int code = p.exitValue();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (code != 0) {
                throw new IOException("命令失败 exit=" + code + ": " + String.join(" ", cmd));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断: " + String.join(" ", cmd), e);
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }
}
