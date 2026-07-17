package com.pmcl.core.ai;

import com.pmcl.core.LauncherCore;
import com.pmcl.core.auth.Account;
import com.pmcl.core.auth.AuthService;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.IntegrityChecker;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.instance.InstanceInfo;
import com.pmcl.core.instance.InstanceManager;
import com.pmcl.core.launch.CrashAnalyzer;
import com.pmcl.core.launch.JavaRuntimeFinder;
import com.pmcl.core.launch.LaunchManager;
import com.pmcl.core.launch.LaunchProfile;
import com.pmcl.core.launch.LaunchProfileBuilder;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.runtime.JavaRuntimeDownloader;
import com.pmcl.core.runtime.RuntimeManager;
import com.pmcl.core.version.McVersion;
import com.pmcl.core.version.VersionManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 启动器操作工具集：让 AI 智能体直接介入启动器执行下载、安装、配置、启动等操作。
 * <p>
 * 涵盖能力：
 * <ul>
 *   <li>游戏版本管理：列出可用版本、安装版本、校验完整性</li>
 *   <li>实例管理：列出/创建/删除实例</li>
 *   <li>游戏启动：构建启动配置并异步启动游戏</li>
 *   <li>崩溃分析：扫描并分析崩溃日志，给出恢复建议</li>
 *   <li>JVM 配置：内存分配、GC 类型、Aikar 参数、自定义 JVM 参数</li>
 *   <li>Java 运行时：列出/安装 Java 运行时、查找系统 Java</li>
 *   <li>系统信息：CPU/内存/GPU 硬件信息</li>
 *   <li>下载配置：镜像源切换</li>
 * </ul>
 */
public class LauncherTools {

    private final LauncherCore core;
    private volatile Consumer<String> statusCallback;

    public LauncherTools(LauncherCore core) {
        this.core = core;
    }

    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    private void notifyStatus(String status) {
        Consumer<String> cb = statusCallback;
        if (cb != null) cb.accept(status);
    }

    // ============================================================
    // 游戏版本管理
    // ============================================================

    @Tool("列出可下载的 Minecraft 版本。返回版本号、类型（release/snapshot）和发布时间。")
    public String listGameVersions(
            @P("版本类型过滤：release / snapshot / all") String type) {
        notifyStatus("正在获取版本列表...");
        try {
            List<McVersion> versions = core.versions().fetchRemoteVersions().join();
            String filter = type == null ? "all" : type.toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (McVersion v : versions) {
                if (!filter.equals("all") && !v.getType().equalsIgnoreCase(filter)) continue;
                sb.append("- ").append(v.getId())
                        .append(" (").append(v.getType()).append(")")
                        .append("  ").append(v.getReleaseTime() != null ? v.getReleaseTime() : "")
                        .append("\n");
                if (++count >= 30) {
                    sb.append("... 共 ").append(versions.size()).append(" 个版本\n");
                    break;
                }
            }
            return sb.length() > 0 ? sb.toString() : "未找到匹配的版本";
        } catch (Exception e) {
            return "获取版本列表失败: " + e.getMessage();
        }
    }

    @Tool("列出本地已安装的 Minecraft 版本")
    public String listInstalledVersions() {
        notifyStatus("正在扫描本地版本...");
        try {
            List<String> local = core.versions().listLocalVersions();
            if (local.isEmpty()) return "本地未安装任何版本";
            StringBuilder sb = new StringBuilder("已安装版本:\n");
            for (String v : local) {
                sb.append("- ").append(v).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "扫描本地版本失败: " + e.getMessage();
        }
    }

    @Tool("下载并安装指定 Minecraft 版本。安装过程包括版本JSON、客户端jar、依赖库、资源文件。")
    public String installGameVersion(
            @P("要安装的游戏版本号，例如 1.20.4") String versionId) {
        notifyStatus("正在安装 Minecraft " + versionId + "...");
        try {
            VersionInstaller installer = core.install();
            installer.install(versionId, progress -> {
                if (progress != null) {
                    notifyStatus(progress.getStage() + " - " + progress.getMessage());
                }
            }).join();
            return "Minecraft " + versionId + " 安装成功";
        } catch (Exception e) {
            return "安装失败: " + e.getMessage();
        }
    }

    @Tool("校验已安装版本的文件完整性，检查是否有缺失或损坏的文件")
    public String checkIntegrity(
            @P("要校验的版本号") String versionId) {
        notifyStatus("正在校验 " + versionId + " 的完整性...");
        try {
            IntegrityChecker.Result result = core.integrity().check(versionId);
            if (result.isOk()) {
                return versionId + " 完整性校验通过，所有文件正常";
            }
            StringBuilder sb = new StringBuilder("发现 ").append(result.getIssueCount()).append(" 个问题:\n");
            if (result.getMissing() != null && !result.getMissing().isEmpty()) {
                sb.append("缺失文件:\n");
                for (String f : result.getMissing()) {
                    sb.append("  - ").append(f).append("\n");
                }
            }
            if (result.getHashMismatch() != null && !result.getHashMismatch().isEmpty()) {
                sb.append("哈希不匹配:\n");
                for (String f : result.getHashMismatch()) {
                    sb.append("  - ").append(f).append("\n");
                }
            }
            sb.append("\n建议：重新安装该版本以修复问题");
            return sb.toString();
        } catch (Exception e) {
            return "校验失败: " + e.getMessage();
        }
    }

    // ============================================================
    // 实例管理
    // ============================================================

    @Tool("列出所有游戏实例。每个实例有独立的mods、存档和配置。")
    public String listInstances() {
        try {
            List<InstanceInfo> instances = core.instances().listInstances();
            if (instances.isEmpty()) return "当前没有游戏实例";
            StringBuilder sb = new StringBuilder("游戏实例:\n");
            for (InstanceInfo inst : instances) {
                sb.append("- 名称: ").append(inst.getName())
                        .append(" | 版本: ").append(inst.getBaseVersionId())
                        .append(" | 加载器: ").append(inst.getLoader() != null ? inst.getLoader() : "原版")
                        .append(" | 可启动: ").append(inst.isLaunchable() ? "是" : "否")
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "获取实例列表失败: " + e.getMessage();
        }
    }

    @Tool("创建新的游戏实例。实例有独立的mods目录和配置，不影响其他实例。")
    public String createInstance(
            @P("实例名称") String name,
            @P("基础游戏版本号，例如 1.20.4") String baseVersionId,
            @P("Mod加载器类型：fabric / forge / quilt / neoforge / vanilla（留空表示原版）") String loader,
            @P("加载器版本号（从 listModLoaderVersions 获取，原版留空）") String loaderVersion) {
        notifyStatus("正在创建实例: " + name);
        try {
            String actualLoader = (loader == null || loader.trim().isEmpty() || "vanilla".equalsIgnoreCase(loader))
                    ? null : loader;
            String actualLoaderVer = (loaderVersion == null || loaderVersion.trim().isEmpty())
                    ? null : loaderVersion;
            InstanceInfo inst = core.instances().createInstance(name, baseVersionId, actualLoader, actualLoaderVer);
            return "实例创建成功: " + inst.getName() + " (ID: " + inst.getInstanceId() + ")";
        } catch (Exception e) {
            return "创建实例失败: " + e.getMessage();
        }
    }

    @Tool("删除指定游戏实例。会删除该实例的所有文件（mods、存档、配置），不可恢复。")
    public String deleteInstance(
            @P("实例名称") String instanceName) {
        notifyStatus("正在删除实例: " + instanceName);
        try {
            List<InstanceInfo> instances = core.instances().listInstances();
            for (InstanceInfo inst : instances) {
                if (inst.getName().equals(instanceName)) {
                    core.instances().deleteInstance(inst.getInstanceId());
                    return "实例 '" + instanceName + "' 已删除";
                }
            }
            return "未找到名为 '" + instanceName + "' 的实例";
        } catch (Exception e) {
            return "删除实例失败: " + e.getMessage();
        }
    }

    // ============================================================
    // 游戏启动
    // ============================================================

    @Tool("启动指定版本的 Minecraft 游戏。需要该版本已安装。")
    public String launchGame(
            @P("要启动的版本号") String versionId,
            @P("玩家名称（用于离线模式）") String playerName) {
        notifyStatus("正在启动 Minecraft " + versionId + "...");
        try {
            Preferences prefs = core.getPreferences();
            AuthService auth = core.auth();

            // 获取或创建账号
            Account account;
            if (playerName != null && !playerName.trim().isEmpty()) {
                account = auth.offline(playerName.trim());
            } else {
                account = auth.offline("Player");
            }

            // 查找 Java
            String javaPath = prefs.getJavaPath();
            if (javaPath == null || javaPath.trim().isEmpty()) {
                javaPath = JavaRuntimeFinder.findJavaExecutable();
            }
            if (javaPath == null) {
                return "未找到可用的 Java 运行时，请先安装 Java 或在设置中配置 Java 路径";
            }

            // 构建启动配置
            LaunchProfileBuilder builder = core.profileBuilder();
            LaunchProfile profile = builder.build(versionId, account);

            // 异步启动
            LaunchManager launchManager = core.launch();
            launchManager.launchAsync(profile, javaPath, log -> {
                // 日志输出（可选）
            });

            return "Minecraft " + versionId + " 正在启动（玩家: " + account.getUsername() + "）";
        } catch (Exception e) {
            return "启动失败: " + e.getMessage();
        }
    }

    // ============================================================
    // 崩溃分析
    // ============================================================

    @Tool("扫描并分析崩溃日志。返回崩溃原因、建议和恢复操作。")
    public String analyzeCrashLog() {
        notifyStatus("正在扫描崩溃日志...");
        try {
            Path workDir = core.getConfig().getWorkDir();
            CrashAnalyzer analyzer = core.crashAnalyzer();
            List<CrashAnalyzer.CrashReport> reports = analyzer.scanReports(workDir);

            if (reports.isEmpty()) return "未找到崩溃日志";

            StringBuilder sb = new StringBuilder("找到 ").append(reports.size()).append(" 份崩溃报告:\n\n");
            for (int i = 0; i < Math.min(3, reports.size()); i++) {
                CrashAnalyzer.CrashReport report = reports.get(i);
                sb.append("=== 崩溃报告 #").append(i + 1).append(" ===\n");
                if (report.getFile() != null) {
                    sb.append("文件: ").append(report.getFile().getFileName()).append("\n");
                }
                if (report.getCauses() != null && !report.getCauses().isEmpty()) {
                    sb.append("原因:\n");
                    for (String cause : report.getCauses()) {
                        sb.append("  - ").append(cause).append("\n");
                    }
                }
                if (report.getSuggestions() != null && !report.getSuggestions().isEmpty()) {
                    sb.append("建议:\n");
                    for (String sugg : report.getSuggestions()) {
                        sb.append("  - ").append(sugg).append("\n");
                    }
                }
                if (report.getRecoveryActions() != null && !report.getRecoveryActions().isEmpty()) {
                    sb.append("恢复操作:\n");
                    for (Object action : report.getRecoveryActions()) {
                        sb.append("  - ").append(action.toString()).append("\n");
                    }
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "分析崩溃日志失败: " + e.getMessage();
        }
    }

    // ============================================================
    // JVM 配置
    // ============================================================

    @Tool("设置游戏内存分配。建议最小内存为最大内存的一半。")
    public String setMemory(
            @P("最小内存（MB），例如 1024") int minMemoryMb,
            @P("最大内存（MB），例如 4096") int maxMemoryMb) {
        try {
            Preferences prefs = core.getPreferences();
            prefs.setMinMemoryMb(minMemoryMb);
            prefs.setMaxMemoryMb(maxMemoryMb);
            prefs.save();
            return "内存配置已更新: 最小 " + minMemoryMb + "MB / 最大 " + maxMemoryMb + "MB";
        } catch (Exception e) {
            return "设置内存失败: " + e.getMessage();
        }
    }

    @Tool("配置 JVM 参数：GC 类型、Aikar 优化参数和自定义 JVM 参数。")
    public String setJvmArgs(
            @P("GC 类型，例如 G1GC / ZGC / ShenandoahGC") String gcType,
            @P("是否启用 Aikar 优化参数") boolean useAikarFlags,
            @P("自定义 JVM 参数（可为空）") String customArgs) {
        try {
            Preferences prefs = core.getPreferences();
            if (gcType != null && !gcType.trim().isEmpty()) {
                prefs.setGcType(gcType.trim());
            }
            prefs.setUseAikarFlags(useAikarFlags);
            if (customArgs != null) {
                prefs.setCustomJvmArgs(customArgs.trim());
            }
            prefs.save();
            return "JVM 参数已更新: GC=" + gcType + ", Aikar=" + useAikarFlags
                    + (customArgs != null && !customArgs.isEmpty() ? ", 自定义参数=" + customArgs : "");
        } catch (Exception e) {
            return "设置 JVM 参数失败: " + e.getMessage();
        }
    }

    @Tool("设置 Java 运行时路径。可设置全局路径或指定版本的独立路径。")
    public String setJavaPath(
            @P("Java 可执行文件路径，例如 /usr/bin/java") String javaPath,
            @P("版本号（为特定版本设置独立 Java 路径，留空则设置全局路径）") String versionId) {
        try {
            Preferences prefs = core.getPreferences();
            if (versionId != null && !versionId.trim().isEmpty()) {
                prefs.setVersionJavaPath(versionId.trim(), javaPath.trim());
                return "已为版本 " + versionId + " 设置 Java 路径: " + javaPath;
            } else {
                prefs.setJavaPath(javaPath.trim());
                return "已设置全局 Java 路径: " + javaPath;
            }
        } catch (Exception e) {
            return "设置 Java 路径失败: " + e.getMessage();
        }
    }

    // ============================================================
    // Java 运行时管理
    // ============================================================

    @Tool("列出可下载的 Java 运行时。Mojang 提供 Java 8/17/21 三个版本。")
    public String listJavaRuntimes(
            @P("Java 版本类型：8 / 17 / 21") int javaVersion) {
        notifyStatus("正在查询 Java " + javaVersion + " 运行时列表...");
        try {
            JavaRuntimeDownloader.RuntimeType type;
            switch (javaVersion) {
                case 8: type = JavaRuntimeDownloader.RuntimeType.JAVA_8; break;
                case 17: type = JavaRuntimeDownloader.RuntimeType.JAVA_17; break;
                case 21: type = JavaRuntimeDownloader.RuntimeType.JAVA_21; break;
                default: return "不支持的 Java 版本，请选择 8、17 或 21";
            }
            List<JavaRuntimeDownloader.RuntimeEntry> runtimes =
                    core.javaDownloader().listRuntimes(type).join();
            if (runtimes.isEmpty()) return "未找到可用的 Java " + javaVersion + " 运行时";
            StringBuilder sb = new StringBuilder("可用的 Java ").append(javaVersion).append(" 运行时:\n");
            for (JavaRuntimeDownloader.RuntimeEntry e : runtimes) {
                sb.append("- ").append(e.getName())
                        .append(" (").append(e.getVersion()).append(")")
                        .append("  大小: ").append(e.getSize() / 1024 / 1024).append("MB")
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询 Java 运行时失败: " + e.getMessage();
        }
    }

    @Tool("下载并安装指定 Java 运行时。安装后可通过 setJavaPath 配置使用。")
    public String installJava(
            @P("Java 版本类型：8 / 17 / 21") int javaVersion,
            @P("运行时名称（从 listJavaRuntimes 获取）") String runtimeName) {
        notifyStatus("正在安装 Java " + javaVersion + "...");
        try {
            JavaRuntimeDownloader.RuntimeType type;
            switch (javaVersion) {
                case 8: type = JavaRuntimeDownloader.RuntimeType.JAVA_8; break;
                case 17: type = JavaRuntimeDownloader.RuntimeType.JAVA_17; break;
                case 21: type = JavaRuntimeDownloader.RuntimeType.JAVA_21; break;
                default: return "不支持的 Java 版本";
            }
            List<JavaRuntimeDownloader.RuntimeEntry> runtimes =
                    core.javaDownloader().listRuntimes(type).join();
            JavaRuntimeDownloader.RuntimeEntry target = null;
            for (JavaRuntimeDownloader.RuntimeEntry e : runtimes) {
                if (e.getName().contains(runtimeName)) {
                    target = e;
                    break;
                }
            }
            if (target == null) return "未找到名称包含 '" + runtimeName + "' 的运行时";
            core.javaDownloader().install(type, target, status -> notifyStatus(status)).join();
            return "Java " + javaVersion + " (" + target.getName() + ") 安装成功";
        } catch (Exception e) {
            return "安装 Java 失败: " + e.getMessage();
        }
    }

    // ============================================================
    // 系统信息
    // ============================================================

    @Tool("获取当前系统硬件信息：CPU、内存、GPU。用于推荐合适的游戏配置。")
    public String getSystemInfo() {
        try {
            RuntimeManager rm = core.runtime();
            StringBuilder sb = new StringBuilder("系统信息:\n");
            sb.append("- 操作系统: ").append(rm.getOsName()).append("\n");
            sb.append("- CPU: ").append(rm.getCpuName())
                    .append(" (").append(rm.getCpuLogicalCores()).append(" 核)\n");
            sb.append("- 总内存: ").append(rm.getTotalMemoryMb()).append("MB\n");
            sb.append("- 可用内存: ").append(rm.getAvailableMemoryMb()).append("MB\n");
            sb.append("- 推荐最大游戏内存: ").append(rm.getRecommendedMaxMemoryMb()).append("MB\n");
            sb.append("- GPU: ").append(rm.getPrimaryGpuName()).append("\n");
            if (rm.getPrimaryGpuVramMb() > 0) {
                sb.append("- GPU 显存: ").append(rm.getPrimaryGpuVramMb()).append("MB\n");
            }
            // 查找系统 Java
            String javaExe = JavaRuntimeFinder.findJavaExecutable();
            if (javaExe != null) {
                Integer ver = JavaRuntimeFinder.getMajorVersion(javaExe);
                sb.append("- 系统 Java: ").append(javaExe);
                if (ver != null) sb.append(" (Java ").append(ver).append(")");
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "获取系统信息失败: " + e.getMessage();
        }
    }

    // ============================================================
    // 下载配置
    // ============================================================

    @Tool("设置下载镜像源。中国大陆用户建议使用 BMCLAPI 加速下载。")
    public String setDownloadMirror(
            @P("镜像源类型：OFFICIAL（官方）/ BMCLAPI（国内镜像）") String mirrorType) {
        try {
            Preferences prefs = core.getPreferences();
            String mirror = mirrorType.toUpperCase(Locale.ROOT);
            if (!mirror.equals("OFFICIAL") && !mirror.equals("BMCLAPI")) {
                return "不支持的镜像类型，请选择 OFFICIAL 或 BMCLAPI";
            }
            prefs.setMirrorType(mirror);
            prefs.save();
            core.applyNetworkPreferences();
            String name = mirror.equals("BMCLAPI") ? "BMCLAPI（国内镜像）" : "官方源";
            return "下载镜像已切换为: " + name;
        } catch (Exception e) {
            return "设置镜像源失败: " + e.getMessage();
        }
    }
}
