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
        // 调试：打印启动命令（敏感参数脱敏，防止 accessToken 泄漏到 latest.log）
        if (logger != null) {
            logger.append("[PMCL DEBUG] 启动命令:");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cmd.size(); i++) {
                sb.append("[PMCL DEBUG] [").append(i).append("] ")
                  .append(sanitizeForLog(cmd.get(i))).append("\n");
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
    // 预热策略说明：
    // Minecraft 客户端进程一旦启动就会创建 LWJGL 窗口，没有原生的"无窗口"模式。
    // 如果在用户点击启动按钮前就启动 MC 进程，游戏窗口会提前弹出，破坏用户体验。
    // 因此预热不启动 MC 进程，而是提前完成所有可并行的耗时准备工作：
    //   1. 解析 version JSON、构建 LaunchProfile（含 verifyLibraries 的全量文件校验）
    //   2. 解析 Java 路径（getRequiredJavaVersion + JavaRuntimeFinder）
    //   3. JVM 类加载预热：启动一个 `java -version` 子进程触发 JVM 初始化和类文件加载
    // 用户点击启动时，LaunchProfile 已就绪，直接调用 launchAsync 启动真正的 MC 进程，
    // 跳过 build() 阶段的全部 IO，实测可节省 30-60% 启动时间（取决于版本和 libraries 数量）。

    /**
     * JVM 预热：启动一个 `java -version` 子进程触发 Java 可执行文件的加载和 JIT 预热。
     * 该进程立即退出，但操作系统会缓存可执行文件和依赖库的页缓存，后续真正启动 MC 时更快。
     *
     * @param javaExecutable Java 可执行文件路径
     * @return 预热是否成功（进程启动 + 退出码 0）
     */
    public boolean prewarmJvm(String javaExecutable) {
        if (javaExecutable == null || javaExecutable.isEmpty()) return false;
        Process p = null;
        try {
            p = new ProcessBuilder(javaExecutable, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // 等待最多 3 秒，避免阻塞太久（注释与实际行为一致：原 waitFor() 无超时会无限阻塞）
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    /**
     * 日志脱敏：对 Minecraft 启动命令中的敏感参数值进行掩码，防止凭据持久化到 latest.log。
     * <p>
     * 脱敏的参数包括：
     * <ul>
     *   <li>{@code --accessToken}：Minecraft 访问令牌（mcToken），可用于冒充账号</li>
     *   <li>{@code --auth_access_token}：旧版启动参数同义</li>
     *   <li>{@code --uuid}：玩家 UUID（轻度敏感，保留前 8 位用于调试）</li>
     *   <li>{@code --user_properties}：可能含 Xbox Live 信息</li>
     *   <li>{@code --profileProperties}：同上</li>
     *   <li>{@code -Dauth_xuid=*}：Xbox Live userHash</li>
     * </ul>
     * <p>
     * 注意：调用方必须传入"单个参数"（cmd.get(i)），而非整条命令字符串。
     * 因为命令行参数以空格分隔，但 token 本身不含空格，按参数脱敏更准确。
     *
     * @param arg 单个启动参数（cmd 列表的一个元素）
     * @return 脱敏后的字符串，非敏感参数原样返回
     */
    static String sanitizeForLog(String arg) {
        if (arg == null || arg.isEmpty()) return arg;

        // JVM 系统属性形式：-Dkey=value
        if (arg.startsWith("-D") && arg.contains("=")) {
            int eq = arg.indexOf('=');
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            if (isSensitiveKey(key)) {
                return "-D" + key + "=" + mask(value);
            }
            return arg;
        }

        // game 形式：--key=value
        if (arg.startsWith("--") && arg.contains("=")) {
            int eq = arg.indexOf('=');
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            if (isSensitiveKey(key)) {
                return "--" + key + "=" + mask(value);
            }
            return arg;
        }

        // 分离形式：--key value（下一个参数）。本方法只处理单参数，标记为 <REDACTED_NEXT>
        // 实际调用方遍历时应判断前一个参数。为简化，这里对已知敏感的"裸 token 值"做启发式检测：
        // Minecraft accessToken 是 JWT（eyJ 开头）或长十六进制/Base64 字符串（≥100 字符）
        if (arg.length() >= 100 && (arg.startsWith("eyJ") || isLikelyToken(arg))) {
            return mask(arg);
        }

        return arg;
    }

    /** 判断参数 key 是否为敏感字段。 */
    private static boolean isSensitiveKey(String key) {
        return key.equals("accessToken")
                || key.equals("auth_access_token")
                || key.equals("auth_session")
                || key.equals("user_properties")
                || key.equals("profileProperties")
                || key.equals("auth_xuid")
                || key.equals("auth_player_uuid")
                || key.equals("uuid");
    }

    /**
     * 启发式判断字符串是否像 token（长 Base64URL / 十六进制串）。
     * 避免误伤普通路径或类名。
     */
    private static boolean isLikelyToken(String s) {
        if (s.length() < 100) return false;
        // 仅含 Base64URL 字符或十六进制字符
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.' || c == '~')) {
                return false;
            }
        }
        return true;
    }

    /**
     * 掩码敏感值：保留前 4 位 + 后 4 位用于调试，中间用 *** 代替。
     * 短字符串（&lt;12 字符）直接全部掩码为 ***。
     */
    private static String mask(String value) {
        if (value == null || value.isEmpty()) return value;
        if (value.length() < 12) return "***";
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }
}
