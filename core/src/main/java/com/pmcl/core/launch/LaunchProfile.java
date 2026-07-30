package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.auth.Account;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动参数构造。
 */
public final class LaunchProfile {

    private final LauncherConfig config;
    private final Account account;
    private final String versionId;

    private String mainClass = "net.minecraft.client.main.Main";
    private List<String> classpath = new ArrayList<>();
    private List<String> jvmArgs = new ArrayList<>();
    private List<String> gameArgs = new ArrayList<>();
    /** Java Agent 参数（-javaagent:jar=path），插入在 JVM 参数最前面 */
    private List<String> javaAgents = new ArrayList<>();
    /** 额外环境变量（由插件 LaunchHook 贡献） */
    private final Map<String, String> env = new LinkedHashMap<>();
    /** 实际 Minecraft 根目录（外部安装时为 ~/.minecraft，.pmcl 安装时为 config.getWorkDir()） */
    private java.nio.file.Path gameDir;

    public LaunchProfile(LauncherConfig config, Account account, String versionId) {
        this.config = config;
        this.account = account;
        this.versionId = versionId;
        this.gameDir = config.getWorkDir();  // 默认用 .pmcl 工作目录
    }

    public LaunchProfile setMainClass(String mainClass) {
        this.mainClass = mainClass;
        return this;
    }

    public String getMainClass() {
        return mainClass;
    }

    public LaunchProfile setGameDir(java.nio.file.Path gameDir) {
        this.gameDir = gameDir;
        return this;
    }

    public java.nio.file.Path getGameDir() {
        return gameDir;
    }

    public String getVersionId() {
        return versionId;
    }

    public String getPlayerName() {
        return account != null ? account.getUsername() : null;
    }

    public LaunchProfile addClasspath(Path p) {
        classpath.add(p.toString());
        return this;
    }

    public LaunchProfile addJvmArg(String arg) {
        jvmArgs.add(arg);
        return this;
    }

    public LaunchProfile addGameArg(String arg) {
        gameArgs.add(arg);
        return this;
    }

    /** 添加 Java Agent 参数（格式：jarPath[=options]） */
    public LaunchProfile addJavaAgent(String jarPath, String options) {
        if (options != null && !options.isEmpty()) {
            javaAgents.add("-javaagent:" + jarPath + "=" + options);
        } else {
            javaAgents.add("-javaagent:" + jarPath);
        }
        return this;
    }

    /**
     * Append a pre-formatted {@code -javaagent:…} argument.
     * <p>
     * <b>Host-only.</b> Must not be called from plugin LaunchHook contributions —
     * {@link com.pmcl.core.plugin.PluginManager#applyLaunchContributions} rejects
     * plugin javaagents to preserve the isolating class-loader sandbox.
     */
    public LaunchProfile addJavaAgentRaw(String agentArg) {
        if (agentArg != null && !agentArg.isBlank()) {
            String t = agentArg.trim();
            if (!t.startsWith("-javaagent:")) {
                t = "-javaagent:" + t;
            }
            javaAgents.add(t);
        }
        return this;
    }

    public LaunchProfile putEnv(String key, String value) {
        if (key != null && !key.isBlank()) {
            env.put(key.trim(), value != null ? value : "");
        }
        return this;
    }

    public Map<String, String> getEnv() {
        return Collections.unmodifiableMap(env);
    }

    /** Mutable classpath list (used by RetroWrapper translation layer). */
    List<String> getClasspathMutable() {
        return classpath;
    }

    /** Read-only view of game args for tweaker detection. */
    List<String> getGameArgsView() {
        return Collections.unmodifiableList(gameArgs);
    }

    /** Prepend a game argument (used to inject --tweakClass ahead of others). */
    LaunchProfile prependGameArg(String arg) {
        if (arg != null) gameArgs.add(0, arg);
        return this;
    }

    public List<String> buildCommand(String javaExecutable) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable);

        // Java Agent 参数必须紧跟 java 可执行文件（在其他 JVM 参数之前）
        cmd.addAll(javaAgents);

        // JVM 参数（内存/GC/Aikar 等已由 LaunchProfileBuilder 通过 addJvmArg 注入）
        // IgnoreUnrecognizedVMOptions 必须在所有 -XX 参数之前，确保不识别的选项只告警不中止 JVM。
        // 跨平台稳定性的基石：不同 JVM 构建（HotSpot/OpenJ9/不同 JDK 发行版）对 -XX 选项支持不同，
        // 不识别的 -XX:+ 选项默认会导致 JVM 拒绝启动（exit code 1）。
        cmd.add("-XX:+IgnoreUnrecognizedVMOptions");
        cmd.addAll(jvmArgs);

        // classpath
        if (!classpath.isEmpty()) {
            String cp = String.join(System.getProperty("path.separator"), classpath);
            // H2: Windows CreateProcess 命令行长度上限 32767 字符。
            // 大型整合包（500+ mods）classpath 轻松超 32K，导致 CreateProcess 失败（错误码 206）。
            // Java 9+ 支持 @argfile：将长 classpath 写入临时文件，用 @file 引用。
            // 阈值取 30000 留余量（cmd 还包含 java/jvmArgs/mainClass/gameArgs）。
            if (cp.length() > 30000) {
                Path argFile = writeClasspathArgFile(cp);
                if (argFile != null) {
                    cmd.add("-cp");
                    cmd.add("@" + argFile.toString());
                } else {
                    // 回退：argfile 写入失败时仍直接传 cp（极端情况）
                    cmd.add("-cp");
                    cmd.add(cp);
                }
            } else {
                cmd.add("-cp");
                cmd.add(cp);
            }
        }

        // 主类
        cmd.add(mainClass);

        // 游戏参数：MC 1.13+ 的版本 JSON（arguments.game）已包含
        // --username/--version/--gameDir/--assetsDir/--uuid/--accessToken 等全部参数，
        // 占位符已由 LaunchProfileBuilder.replacePlaceholders 替换为实际值，
        // 这里只需追加 gameArgs，避免重复注入导致 joptsimple 报 "multiple arguments" 错误。
        cmd.addAll(gameArgs);
        return Collections.unmodifiableList(cmd);
    }

    /**
     * H2: 将超长 classpath 写入临时 argfile。
     * <p>
     * 调用方已追加 {@code -cp}，再传 {@code @file}；因此文件内容只能是 classpath 本体。
     * 若文件再写 {@code -cp …}，展开后会变成双重 {@code -cp}，JVM 启动失败。
     * 路径含空白时按 JVM argfile 规则加双引号。
     */
    private Path writeClasspathArgFile(String classpath) {
        try {
            Path argDir = config.getWorkDir().resolve("argfiles");
            Files.createDirectories(argDir);
            String name = (versionId != null ? versionId : "mc") + "-" + System.currentTimeMillis() + ".cp";
            Path file = argDir.resolve(name);
            String body = classpath;
            if (body.indexOf(' ') >= 0 || body.indexOf('\t') >= 0) {
                body = "\"" + body.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
            Files.writeString(file, body, java.nio.charset.StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            System.err.println("[LaunchProfile] 写入 classpath argfile 失败，回退到直接传参: " + e.getMessage());
            return null;
        }
    }
}
