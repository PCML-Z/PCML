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

    /** H14: 毒丸对象，用于结束 log dispatcher 线程 */
    private static final String POISON_PILL = new String("__PMCL_LOG_POISON__");

    /**
     * 设备保护拒绝 / 插件 beforeLaunch 取消时的伪退出码。
     * UI 不得将其当作游戏崩溃弹窗。
     */
    public static final int EXIT_CANCELLED = -100;

    private final LauncherConfig config;
    private final Preferences preferences;
    private PluginManager pluginManager;
    /** 活跃 MC 进程（应用退出时强制清理） */
    private final java.util.Set<Process> activeProcesses =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** 专用线程池：避免 process.waitFor() 长时间占用 ForkJoinPool.commonPool */
    private final java.util.concurrent.ExecutorService launchExecutor =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "pmcl-launch");
                t.setDaemon(true);
                return t;
            });
    /** 日志队列上限：满时丢弃新行，优先保证 reader 不阻塞 */
    private static final int LOG_QUEUE_CAPACITY = 8000;

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
     * 启动前门禁：设备绑定保护 + 插件 beforeLaunch。
     * 桌面 {@link #launchAsync} 与同步 {@link #launch}（含 Companion）必须共用此校验，
     * 避免未授权设备或插件策略被旁路。
     *
     * @return {@code null} 表示允许启动；非 null 为拒绝原因（可对用户展示）
     */
    public String verifyBeforeLaunch(LaunchProfile profile) {
        return verifyBeforeLaunch(profile, null, null);
    }

    /**
     * @param onLog  可选 UI 日志回调
     * @param logger 可选 GameLogger
     * @return {@code null} 表示允许；非 null 为拒绝原因
     */
    public String verifyBeforeLaunch(LaunchProfile profile,
                                     Consumer<String> onLog,
                                     GameLogger logger) {
        if (profile == null) {
            return "[PMCL] 启动配置为空，已取消";
        }
        // 设备绑定保护：开启时设备不匹配则拒绝
        if (preferences != null && preferences.isDeviceProtectionEnabled()) {
            boolean allowed = com.pmcl.core.auth.DeviceBinder.verifyOnLaunch(
                    preferences.getDeviceProtectionLicense(),
                    preferences.getDeviceProtectionPublicKey());
            if (!allowed) {
                String denyMsg = "[PMCL] 设备未授权：当前设备与绑定设备不匹配，启动已取消";
                if (logger != null) logger.append(denyMsg);
                if (onLog != null) onLog.accept(denyMsg);
                return denyMsg;
            }
        }
        // 插件 beforeLaunch 可取消启动
        if (pluginManager != null) {
            String versionId = profile.getVersionId();
            String accountName = profile.getPlayerName() != null ? profile.getPlayerName() : "Player";
            if (!pluginManager.beforeLaunch(versionId, accountName)) {
                String cancelMsg = pluginManager.getLastLaunchCancelReason();
                if (cancelMsg == null || cancelMsg.isBlank()) {
                    cancelMsg = "[PMCL] Launch cancelled by plugin hook";
                } else if (!cancelMsg.startsWith("[")) {
                    cancelMsg = "[PMCL] " + cancelMsg;
                }
                if (logger != null) logger.append(cancelMsg);
                if (onLog != null) onLog.accept(cancelMsg);
                return cancelMsg;
            }
        }
        return null;
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
     * @param readerHolder 若非 null，[0]=reader、[1]=dispatcher，供调用方 join
     */
    Process launch(LaunchProfile profile, String javaExecutable,
                          Consumer<String> onLog, GameLogger logger,
                          Thread[] readerHolder) throws IOException {
        // 同步路径（含 Companion）与 launchAsync 共用门禁，禁止旁路
        String deny = verifyBeforeLaunch(profile, onLog, logger);
        if (deny != null) {
            throw new IOException(deny);
        }
        if (pluginManager != null) {
            pluginManager.applyLaunchContributions(profile);
        }
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
        // 插件贡献的环境变量（已在 applyLaunchContributions 中过滤危险键）
        if (profile.getEnv() != null && !profile.getEnv().isEmpty()) {
            pb.environment().putAll(profile.getEnv());
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        activeProcesses.add(process);
        // 关闭 stdin：Redirect.DISCARD 只能用于 stdout/stderr（type=WRITE），
        // 用于 redirectInput 会抛 “Redirect invalid for reading: WRITE”。
        // 关闭管道写端等于向子进程送 EOF，避免无人写入的 PIPE 被误读阻塞。
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }

        java.util.concurrent.BlockingQueue<String> logQueue =
                new java.util.concurrent.ArrayBlockingQueue<>(LOG_QUEUE_CAPACITY);
        java.util.concurrent.atomic.AtomicInteger droppedUiLogs =
                new java.util.concurrent.atomic.AtomicInteger();
        final Process p = process;
        Thread dispatcher = new Thread(() -> {
            try {
                while (true) {
                    String line = logQueue.take();
                    if (line == POISON_PILL) break;
                    try {
                        if (onLog != null) onLog.accept(line);
                    } catch (Throwable t) {
                        System.err.println("[LaunchManager] onLog 回调异常: " + t.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "mc-log-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();

        Thread reader = new Thread(() -> {
            // H14: reader 只读管道入队，dispatcher 消费 onLog，避免管道死锁
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (logger != null) logger.append(line);
                    // 队列满时丢弃最旧 UI 行，优先保证 reader 不阻塞；文件日志仍完整
                    if (!logQueue.offer(line)) {
                        logQueue.poll();
                        logQueue.offer(line);
                        int n = droppedUiLogs.incrementAndGet();
                        if (n == 1 || n % 500 == 0) {
                            String warn = "[PMCL] UI 日志队列拥塞，已丢弃 " + n + " 行（文件日志仍完整）";
                            if (logger != null) logger.append(warn);
                            System.err.println(warn);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[LaunchManager] 进程输出读取异常: " + e.getMessage());
                try {
                    logQueue.put("[PMCL] 进程输出读取异常: " + e.getMessage());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                // P2-4(H4): put 在 dispatcher 阻塞(onLog 回调卡住)时会永久死锁。
                // 改为 offer 带超时：2 秒内入队成功则正常退出；超时则清空队列再 offer，
                // 确保毒丸一定能入队让 dispatcher 退出。
                try {
                    if (!logQueue.offer(POISON_PILL, 2, java.util.concurrent.TimeUnit.SECONDS)) {
                        logQueue.clear();
                        logQueue.offer(POISON_PILL);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logQueue.clear();
                    logQueue.offer(POISON_PILL);
                }
            }
        }, "mc-process-reader");
        reader.setDaemon(true);
        reader.start();
        if (readerHolder != null) {
            readerHolder[0] = reader;
            if (readerHolder.length > 1) readerHolder[1] = dispatcher;
        }

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
        return launchAsync(profile, javaExecutable, onLog, logger, null);
    }

    /**
     * 异步启动 MC（带 GameLogger 持久化 + 启动流程计时）。
     * LaunchTracer 在进程启动/退出时记录里程碑，并从 MC 日志识别内部阶段。
     * 进程退出时自动输出完整时间线到 GameLogger。
     */
    public CompletableFuture<Integer> launchAsync(LaunchProfile profile,
                                                  String javaExecutable,
                                                  Consumer<String> onLog,
                                                  GameLogger logger,
                                                  LaunchTracer tracer) {
        String versionId = profile.getVersionId();
        return CompletableFuture.supplyAsync(() -> {
            Process process = null;
            ProcessTuner tuner = null;
            // P2-4: readerHolder 声明在 try 块之前，确保 catch 块也能访问以 join 线程
            Thread[] readerHolder = new Thread[2];
            try {
                // 与同步 launch() 共用门禁（设备绑定 + 插件 beforeLaunch）
                String deny = verifyBeforeLaunch(profile, onLog, logger);
                if (deny != null) {
                    return EXIT_CANCELLED;
                }

                // 包装 onLog：在原有回调基础上注入 LaunchTracer 的 MC 阶段识别
                // 这样无论日志走到 UI 还是 GameLogger，都会被检测里程碑
                Consumer<String> tracedOnLog = tracer != null
                        ? line -> { tracer.detectMcMilestone(line); if (onLog != null) onLog.accept(line); }
                        : onLog;
                process = launch(profile, javaExecutable, tracedOnLog, logger, readerHolder);
                if (tracer != null) tracer.mark("process_started");

                // 澪模式：游戏进程已启动后再做提权调优，避免启动前卡在管理员密码框
                if (preferences != null && preferences.isMioModeEnabled()) {
                    tuner = new ProcessTuner();

                    // L3：系统电源策略（需管理员密码）
                    if (preferences.isMioModeSystemPower()) {
                        boolean ok = tuner.applySystemPowerPolicy();
                        if (ok) {
                            if (logger != null) logger.append("[PMCL] 澪模式 L3：已关闭系统低电量模式");
                        } else {
                            // 用户拒绝授权 → 自动关闭，避免每次启动都弹密码框
                            preferences.setMioModeSystemPower(false);
                            if (logger != null) {
                                logger.append("[PMCL] 澪模式 L3：未获得管理员授权，已自动关闭「系统电源策略」");
                            }
                            if (onLog != null) {
                                onLog.accept("[PMCL] 澪模式 L3 已自动关闭（未授权）");
                            }
                        }
                    }

                    // L2：进程级调优（无需 sudo）
                    if (preferences.isMioModeProcess()) {
                        tuner.applyProcessTuning(process.pid());
                        if (logger != null) logger.append("[PMCL] 澪模式 L2：已应用进程级性能调优");
                    }

                    // L2+：疯狂优先级（macOS 需管理员密码）
                    if (preferences.isMioModeCrazyPriority()) {
                        boolean ok = tuner.applyCrazyPriority(process.pid());
                        if (ok) {
                            if (logger != null) logger.append("[PMCL] 澪模式 L2+：已应用疯狂调度优先级");
                        } else {
                            preferences.setMioModeCrazyPriority(false);
                            if (logger != null) {
                                logger.append("[PMCL] 澪模式 L2+：未获得管理员授权，已自动关闭「疯狂优先级」");
                            }
                            if (onLog != null) {
                                onLog.accept("[PMCL] 澪模式「疯狂优先级」已自动关闭（未授权）");
                            }
                        }
                    }
                }

                // Fire GameLaunchedEvent
                if (pluginManager != null) {
                    try {
                        String accountName = profile.getPlayerName() != null ? profile.getPlayerName() : "Player";
                        pluginManager.fireEvent(new GameLaunchedEvent(versionId, accountName));
                    } catch (RuntimeException pe) {
                        // P1-1: 插件异常不得导致游戏进程泄漏，记录后继续
                        if (logger != null) logger.append("[PMCL] GameLaunchedEvent 插件异常: " + pe.getMessage());
                        System.err.println("[LaunchManager] GameLaunchedEvent plugin error: " + pe.getMessage());
                    }
                }

                int code = process.waitFor();
                activeProcesses.remove(process);
                // 安全修复：进程退出后关闭其 stdout/stderr 流，强制 reader 线程收到 EOF，
                // 防止子进程继承管道的 descendants 持有读端导致 readLine() 永久阻塞
                try { process.getInputStream().close(); } catch (Exception ignored) {}
                try { process.getErrorStream().close(); } catch (Exception ignored) {}
                // 等待读取线程读完剩余输出，避免丢失进程退出前的最后几行日志
                if (readerHolder[0] != null) {
                    try {
                        readerHolder[0].join(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (readerHolder[0].isAlive()) {
                        readerHolder[0].interrupt();
                    }
                }
                if (readerHolder[1] != null) {
                    try {
                        readerHolder[1].join(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (readerHolder[1].isAlive()) {
                        readerHolder[1].interrupt();
                    }
                }
                String exitMsg = "[PMCL] 进程退出 code=" + code;
                if (logger != null) logger.append(exitMsg);
                if (onLog != null) onLog.accept(exitMsg);
                // 输出启动时间线（进程退出后，所有 MC 阶段已识别完毕）
                if (tracer != null) tracer.outputTo(logger);

                // Plugin afterLaunch hooks + GameExitedEvent
                if (pluginManager != null) {
                    try {
                        pluginManager.afterLaunch(versionId, code);
                        pluginManager.fireEvent(new GameExitedEvent(versionId, code));
                    } catch (RuntimeException pe) {
                        // P1-1: 插件异常不得让 future 异常退出（游戏已正常退出），记录后返回真实退出码
                        if (logger != null) logger.append("[PMCL] afterLaunch/GameExited 插件异常: " + pe.getMessage());
                        System.err.println("[LaunchManager] afterLaunch/GameExited plugin error: " + pe.getMessage());
                    }
                }

                return code;
            } catch (Throwable e) {
                // P1-1: 捕获 Throwable 而非仅 IOException|InterruptedException，
                // 确保插件 RuntimeException 也能触发进程清理，防止僵尸进程残留。
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // 异常路径：销毁可能已启动的进程，防止僵尸进程残留
                if (process != null) {
                    activeProcesses.remove(process);
                    if (process.isAlive()) {
                        try { process.destroyForcibly(); } catch (Exception ignored) {}
                    }
                }
                // P2-4(H3): 异常路径也需 join reader/dispatcher 线程，防止线程泄漏。
                // reader 在进程销毁后会收到 EOF 并投递毒丸，dispatcher 消费毒丸后退出。
                if (readerHolder[0] != null) {
                    try { readerHolder[0].join(2000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    if (readerHolder[0].isAlive()) readerHolder[0].interrupt();
                }
                if (readerHolder[1] != null) {
                    try { readerHolder[1].join(2000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    if (readerHolder[1].isAlive()) readerHolder[1].interrupt();
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
        }, launchExecutor);
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

    /**
     * 跟踪外部启动的进程（如 HMCL/LauncherX），纳入应用退出时的强制清理。
     * 进程结束后应调用 {@link #untrackExternalProcess(Process)}。
     */
    public void trackExternalProcess(Process process) {
        if (process != null) activeProcesses.add(process);
    }

    /** 取消跟踪外部进程（进程已退出时调用，避免集合无限增长） */
    public void untrackExternalProcess(Process process) {
        if (process != null) activeProcesses.remove(process);
    }

    /** 是否仍有由本启动器跟踪且存活的游戏进程。 */
    public boolean hasActiveProcesses() {
        return activeProcessCount() > 0;
    }

    /** 当前仍存活的受跟踪游戏进程数。 */
    public int activeProcessCount() {
        int n = 0;
        for (Process p : activeProcesses) {
            if (p != null && p.isAlive()) n++;
        }
        return n;
    }

    /** 强制结束所有由本启动器拉起且仍存活的 MC 进程树（应用退出时调用） */
    public void killAllProcesses() {
        ProcessMonitor monitor = new ProcessMonitor();
        for (Process p : activeProcesses) {
            if (p != null && p.isAlive()) {
                try {
                    monitor.forceKill(p);
                } catch (Exception e) {
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                }
            }
        }
        activeProcesses.clear();
    }

    /** 关闭启动专用线程池（应用退出时调用） */
    public void shutdown() {
        killAllProcesses();
        launchExecutor.shutdownNow();
    }
}
