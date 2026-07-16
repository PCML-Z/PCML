package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.plugin.PluginManager;
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
    private PluginManager pluginManager;

    public LaunchManager(LauncherConfig config) {
        this.config = config;
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
            try {
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
            }
        });
    }
}
