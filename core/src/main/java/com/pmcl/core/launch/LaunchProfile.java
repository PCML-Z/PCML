package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.auth.Account;

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

    /** 直接追加已格式化的 -javaagent:… 参数（插件贡献用） */
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
        cmd.addAll(jvmArgs);

        // classpath
        if (!classpath.isEmpty()) {
            cmd.add("-cp");
            cmd.add(String.join(System.getProperty("path.separator"), classpath));
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
}
