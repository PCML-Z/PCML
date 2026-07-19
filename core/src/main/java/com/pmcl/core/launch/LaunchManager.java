package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.plugin.PluginManager;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.plugin.GameLaunchedEvent;
import com.pmcl.plugin.GameExitedEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 启动管理：构造进程并启动 MC。
 */
public final class LaunchManager {

    private final LauncherConfig config;
    private final Preferences preferences;
    private PluginManager pluginManager;

    public LaunchManager(LauncherConfig config) {
        this(config, null);
    }

    public LaunchManager(LauncherConfig config, Preferences preferences) {
        this.config = config;
        this.preferences = preferences;
    }

    /** Inject plugin manager for launch hooks and events. Called by LauncherCore. */
    public void setPluginManager(PluginManager pm) {
        this.pluginManager = pm;
    }

    /**
     * 启动 MC（同步）。
     *
     * @param profile        启动配置
     * @param javaExecutable java 路径
     * @param onLog          日志回调
     */
    public Process launch(LaunchProfile profile, String javaExecutable,
                          Consumer<String> onLog) throws IOException {
        return launch(profile, javaExecutable, onLog, null);
    }

    /**
     * 启动 MC（同步），并把日志同步写入 GameLogger 持久化。
     *
     * @param logger 若非 null，所有日志行会同时写入 latest.log
     */
    public Process launch(LaunchProfile profile, String javaExecutable,
                          Consumer<String> onLog, GameLogger logger) throws IOException {
        return launch(profile, javaExecutable, onLog, logger, null);
    }

    /**
     * 启动 MC（同步），并把日志同步写入 GameLogger 持久化。
     *
     * @param logger 若非 null，所有日志行会同时写入 latest.log
     * @param readerHolder 若非 null，读取线程会存入 [0] 供调用方 join
     */
    Process launch(LaunchProfile profile, String javaExecutable,
                          Consumer<String> onLog, GameLogger logger,
                          Thread[] readerHolder) throws IOException {
        java.util.List<String> cmd = profile.buildCommand(javaExecutable);
        // 调试：打印完整启动命令
        if (logger != null) {
            logger.append("[PMCL DEBUG] 启动命令:");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cmd.size(); i++) {
                sb.append("[PMCL DEBUG] [").append(i).append("] ").append(cmd.get(i)).append("\n");
            }
            logger.append(sb.toString());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 用 profile 实际的 gameDir 作为进程工作目录（支持启动外部 Minecraft 安装）
        pb.directory(profile.getGameDir().toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (logger != null) logger.append(line);
                    if (onLog != null) onLog.accept(line);
                }
            } catch (IOException e) {
                // 进程输出流读取失败时记录日志，避免静默丢失崩溃信息
                System.err.println("[LaunchManager] 进程输出读取异常: " + e.getMessage());
                if (onLog != null) onLog.accept("[PMCL] 进程输出读取异常: " + e.getMessage());
            }
        }, "mc-process-reader");
        reader.setDaemon(true);
        reader.start();
        if (readerHolder != null) readerHolder[0] = reader;

        return process;
    }

    /**
     * 异步启动 MC，返回的 future 在进程退出时完成。
     */
    public CompletableFuture<Integer> launchAsync(LaunchProfile profile,
                                                  String javaExecutable,
                                                  Consumer<String> onLog) {
        return launchAsync(profile, javaExecutable, onLog, null);
    }

    /**
     * 异步启动 MC（带 GameLogger 持久化）。
     * 集成插件启动钩子（beforeLaunch/afterLaunch）和事件（GameLaunched/Exited）。
     */
    public CompletableFuture<Integer> launchAsync(LaunchProfile profile,
                                                  String javaExecutable,
                                                  Consumer<String> onLog,
                                                  GameLogger logger) {
        String versionId = profile.getVersionId();
        return CompletableFuture.supplyAsync(() -> {
            Process process = null;
            ProcessTuner tuner = null;
            try {
                // 澪模式 L3：系统电源策略（启动前，需 sudo 授权）
                if (preferences != null && preferences.isMioModeEnabled() && preferences.isMioModeSystemPower()) {
                    tuner = new ProcessTuner();
                    boolean ok = tuner.applySystemPowerPolicy();
                    if (ok && logger != null) logger.append("[PMCL] 澪模式 L3：已关闭系统低电量模式");
                }

                // Plugin beforeLaunch hooks (can cancel launch)
                if (pluginManager != null) {
                    String accountName = profile.getPlayerName() != null ? profile.getPlayerName() : "Player";
                    if (!pluginManager.beforeLaunch(versionId, accountName)) {
                        String cancelMsg = "[PMCL] Launch cancelled by plugin hook";
                        if (logger != null) logger.append(cancelMsg);
                        if (onLog != null) onLog.accept(cancelMsg);
                        return -1;
                    }
                }

                Thread[] readerHolder = new Thread[1];
                process = launch(profile, javaExecutable, onLog, logger, readerHolder);

                // 澪模式 L2：进程级调优（启动后，无需 sudo）
                if (preferences != null && preferences.isMioModeEnabled() && preferences.isMioModeProcess()) {
                    if (tuner == null) tuner = new ProcessTuner();
                    tuner.applyProcessTuning(process.pid());
                    if (logger != null) logger.append("[PMCL] 澪模式 L2：已应用进程级性能调优");
                }

                // 澪模式 L2+：疯狂优先级（拉到系统极限，macOS 需 sudo 授权，可能卡顿）
                if (preferences != null && preferences.isMioModeEnabled() && preferences.isMioModeCrazyPriority()) {
                    if (tuner == null) tuner = new ProcessTuner();
                    tuner.applyCrazyPriority(process.pid());
                    if (logger != null) logger.append("[PMCL] 澪模式 L2+：已应用疯狂调度优先级");
                }

                // Fire GameLaunchedEvent
                if (pluginManager != null) {
                    String accountName = profile.getPlayerName() != null ? profile.getPlayerName() : "Player";
                    pluginManager.fireEvent(new GameLaunchedEvent(versionId, accountName));
                }

                int code = process.waitFor();
                // 等待读取线程读完剩余输出，避免丢失进程退出前的最后几行日志
                if (readerHolder[0] != null) {
                    try { readerHolder[0].join(2000); } catch (InterruptedException ignored) {}
                }
                String exitMsg = "[PMCL] 进程退出 code=" + code;
                if (logger != null) logger.append(exitMsg);
                if (onLog != null) onLog.accept(exitMsg);

                // Plugin afterLaunch hooks + GameExitedEvent
                if (pluginManager != null) {
                    pluginManager.afterLaunch(versionId, code);
                    pluginManager.fireEvent(new GameExitedEvent(versionId, code));
                }

                return code;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // 异常路径：销毁可能已启动的进程，防止僵尸进程残留
                if (process != null && process.isAlive()) {
                    try { process.destroyForcibly(); } catch (Exception ignored) {}
                }
                // 提取根因消息，避免 UI 显示 "启动失败：启动失败"
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                String errMsg = "[PMCL] 启动失败: " + root.getMessage();
                if (logger != null) logger.append(errMsg);
                if (onLog != null) onLog.accept(errMsg);
                throw new RuntimeException("启动失败: " + root.getMessage(), e);
            } finally {
                // 澪模式 cleanup：终止 caffeinate、恢复系统电源状态（必须 finally 确保恢复）
                if (tuner != null) {
                    try { tuner.cleanup(); } catch (Exception e) {
                        System.err.println("[MioMode] cleanup 失败: " + e.getMessage());
                    }
                }
            }
        });
    }

    // ===== 预判启动支持 =====

    /**
     * 预热启动：与正常启动走相同的进程构造路径，但返回句柄供调用方决定 confirm / abort。
     * <p>
     * 用于"预判启动"功能：进入启动页时预测最可能的版本并后台启动进程，
     * 用户点击启动按钮时若版本一致则 adopt（秒开），不一致则 abort（杀掉）后走正常启动。
     * <p>
     * 注意：预启动进程同样会触发 plugin beforeLaunch 钩子和 GameLaunchedEvent，
     * 因为从 Minecraft 的角度看进程已经启动了。插件若依赖"用户主动启动"语义需自行判断。
     *
     * @param profile        启动配置（与 launchAsync 相同）
     * @param javaExecutable java 路径
     * @param onLog          日志回调（预启动期间也会输出日志，UI 可选择展示或忽略）
     * @return 预热句柄，调用 confirm() 转为正常等待，或 abort() 终止进程
     */
    public PreheatedLaunch preheat(LaunchProfile profile, String javaExecutable,
                                   Consumer<String> onLog, GameLogger logger) {
        String versionId = profile.getVersionId();
        Thread[] readerHolder = new Thread[1];

        try {
            // 与 launchAsync 相同的前置：澪模式电源策略 + plugin beforeLaunch
            ProcessTuner tuner = null;
            if (preferences != null && preferences.isMioModeEnabled() && preferences.isMioModeSystemPower()) {
                tuner = new ProcessTuner();
                tuner.applySystemPowerPolicy();
                if (logger != null) logger.append("[PMCL] 预启动：澪模式 L3 系统电源策略已应用");
            }
            if (pluginManager != null) {
                String accountName = profile.getPlayerName() != null ? profile.getPlayerName() : "Player";
                if (!pluginManager.beforeLaunch(versionId, accountName)) {
                    if (logger != null) logger.append("[PMCL] 预启动被插件钩子取消");
                    return PreheatedLaunch.cancelled(versionId);
                }
            }

            // 同步启动进程（预启动是阻塞调用，调用方应在 IO 调度器内执行）
            Process process = launch(profile, javaExecutable, onLog, logger, readerHolder);

            // 澪模式进程调优
            if (preferences != null && preferences.isMioModeEnabled() && preferences.isMioModeProcess()) {
                if (tuner == null) tuner = new ProcessTuner();
                tuner.applyProcessTuning(process.pid());
            }

            // 触发 GameLaunchedEvent
            if (pluginManager != null) {
                String accountName = profile.getPlayerName() != null ? profile.getPlayerName() : "Player";
                pluginManager.fireEvent(new GameLaunchedEvent(versionId, accountName));
            }

            return new PreheatedLaunch(versionId, process, readerHolder[0], tuner, logger, pluginManager);
        } catch (IOException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String errMsg = "[PMCL] 预启动失败: " + root.getMessage();
            if (logger != null) logger.append(errMsg);
            if (onLog != null) onLog.accept(errMsg);
            return PreheatedLaunch.failed(versionId, root.getMessage());
        }
    }

    /**
     * 预热启动句柄：持有已启动的进程，供调用方决定是否 adopt（转为正式运行）或 abort（终止）。
     * <p>
     * 状态机：PREHEATED → (confirm → ADOPTED) | (abort → ABORTED)
     * 一旦状态变迁完成，后续调用是 no-op。
     */
    public static final class PreheatedLaunch {
        public enum State { PREHEATED, ADOPTED, ABORTED, CANCELLED, FAILED }

        private final String versionId;
        private final Process process;          // null 当 cancelled/failed
        private final Thread readerThread;
        private final ProcessTuner tuner;
        private final GameLogger logger;
        private final PluginManager pluginManager;
        private final String errorMessage;      // 非 null 当 failed
        private volatile State state;

        private PreheatedLaunch(String versionId, Process process, Thread readerThread,
                                ProcessTuner tuner, GameLogger logger,
                                PluginManager pluginManager, String errorMessage, State state) {
            this.versionId = versionId;
            this.process = process;
            this.readerThread = readerThread;
            this.tuner = tuner;
            this.logger = logger;
            this.pluginManager = pluginManager;
            this.errorMessage = errorMessage;
            this.state = state;
        }

        PreheatedLaunch(String versionId, Process process, Thread readerThread,
                        ProcessTuner tuner, GameLogger logger, PluginManager pluginManager) {
            this(versionId, process, readerThread, tuner, logger, pluginManager, null, State.PREHEATED);
        }

        static PreheatedLaunch cancelled(String versionId) {
            return new PreheatedLaunch(versionId, null, null, null, null, null, "插件钩子取消", State.CANCELLED);
        }

        static PreheatedLaunch failed(String versionId, String errorMessage) {
            return new PreheatedLaunch(versionId, null, null, null, null, null, errorMessage, State.FAILED);
        }

        public String getVersionId() { return versionId; }
        public State getState() { return state; }
        public boolean isPreheated() { return state == State.PREHEATED; }
        public boolean isFailed() { return state == State.FAILED; }
        public String getErrorMessage() { return errorMessage; }

        /**
         * 确认采用：把预热进程转为正式运行，返回 future 在进程退出时完成。
         * 调用后 PreheatedLaunch 不再持有进程，状态变为 ADOPTED。
         */
        public synchronized CompletableFuture<Integer> confirm() {
            if (state != State.PREHEATED) {
                // 已经被 adopt 或 abort，返回已完成的 future
                return CompletableFuture.completedFuture(-1);
            }
            state = State.ADOPTED;
            final Process p = process;
            final Thread reader = readerThread;
            final ProcessTuner t = tuner;
            final GameLogger lg = logger;
            final PluginManager pm = pluginManager;
            final String vid = versionId;

            return CompletableFuture.supplyAsync(() -> {
                try {
                    int code = p.waitFor();
                    if (reader != null) {
                        try { reader.join(2000); } catch (InterruptedException ignored) {}
                    }
                    String exitMsg = "[PMCL] 进程退出 code=" + code;
                    if (lg != null) lg.append(exitMsg);

                    if (pm != null) {
                        pm.afterLaunch(vid, code);
                        pm.fireEvent(new GameExitedEvent(vid, code));
                    }
                    return code;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                } finally {
                    if (t != null) {
                        try { t.cleanup(); } catch (Exception e) {
                            System.err.println("[MioMode] cleanup 失败: " + e.getMessage());
                        }
                    }
                }
            });
        }

        /**
         * 中止预热：杀掉进程（用户选择了其他版本启动时调用）。
         * 使用 destroyForcibly 确保进程立即终止，避免僵尸进程。
         */
        public synchronized void abort() {
            if (state != State.PREHEATED) return;
            state = State.ABORTED;
            if (process != null && process.isAlive()) {
                try {
                    process.destroyForcibly();
                    if (logger != null) logger.append("[PMCL] 预启动进程已中止（用户启动了其他版本）");
                } catch (Exception e) {
                    System.err.println("[PreheatedLaunch] abort 失败: " + e.getMessage());
                }
            }
            // 中断读取线程
            if (readerThread != null) {
                readerThread.interrupt();
            }
            // 澪模式 cleanup
            if (tuner != null) {
                try { tuner.cleanup(); } catch (Exception ignored) {}
            }
            // 触发 GameExitedEvent（code=-2 表示被中止）
            if (pluginManager != null) {
                pluginManager.afterLaunch(versionId, -2);
                pluginManager.fireEvent(new GameExitedEvent(versionId, -2));
            }
        }
    }
}
