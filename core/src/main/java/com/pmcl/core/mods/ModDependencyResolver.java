package com.pmcl.core.mods;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.market.ModFile;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.market.ModProject;
import com.pmcl.core.preferences.Preferences;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 模组依赖自动解析与安装器。
 * <p>
 * 安装模组时自动解析 jar 内元数据（fabric.mod.json / mods.toml 等）中的 depends 列表，
 * 过滤掉系统依赖（minecraft、java、fabricloader、quilt_loader、forge、neoforge），
 * 对剩余的未安装依赖递归搜索并安装（Modrinth 优先，用 modId 作为 slug 直接查询）。
 * <p>
 * 递归安装带循环检测（{@code installing} 集合），避免 A→B→A 死循环。
 */
public final class ModDependencyResolver {

    /**
     * 依赖安装结果。
     */
    public static final class DependencyResult {
        private final String modName;
        private final List<String> installedDependencies;
        private final List<String> skippedInstalled;
        private final List<String> skippedSystem;
        private final List<String> failed;
        private final List<String> notFound;

        public DependencyResult(String modName, List<String> installedDependencies,
                                List<String> skippedInstalled, List<String> skippedSystem,
                                List<String> failed, List<String> notFound) {
            this.modName = modName;
            this.installedDependencies = installedDependencies != null ? installedDependencies : Collections.emptyList();
            this.skippedInstalled = skippedInstalled != null ? skippedInstalled : Collections.emptyList();
            this.skippedSystem = skippedSystem != null ? skippedSystem : Collections.emptyList();
            this.failed = failed != null ? failed : Collections.emptyList();
            this.notFound = notFound != null ? notFound : Collections.emptyList();
        }

        public String getModName() { return modName; }
        public List<String> getInstalledDependencies() { return installedDependencies; }
        public List<String> getSkippedInstalled() { return skippedInstalled; }
        public List<String> getSkippedSystem() { return skippedSystem; }
        public List<String> getFailed() { return failed; }
        public List<String> getNotFound() { return notFound; }

        /** 是否安装了任何依赖 */
        public boolean hasInstalled() { return !installedDependencies.isEmpty(); }

        /** 摘要信息 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (!installedDependencies.isEmpty()) {
                sb.append("已安装依赖: ").append(String.join(", ", installedDependencies));
            }
            if (!notFound.isEmpty()) {
                if (sb.length() > 0) sb.append("；");
                sb.append("未找到: ").append(String.join(", ", notFound));
            }
            if (!failed.isEmpty()) {
                if (sb.length() > 0) sb.append("；");
                sb.append("失败: ").append(String.join(", ", failed));
            }
            return sb.length() > 0 ? sb.toString() : "无额外依赖";
        }
    }

    private final LauncherConfig config;
    private final ModMarketManager marketManager;
    private final Preferences preferences;

    /** 依赖安装线程池（单线程顺序安装，避免并发下载冲突） */
    private final ExecutorService depPool;

    /** 系统依赖 modId 集合（这些不需要安装） */
    private static final Set<String> SYSTEM_DEPS = Set.of(
            "minecraft", "java", "fabricloader", "quilt_loader", "quiltloader",
            "forge", "neoforge", "fmlonly"
    );

    public ModDependencyResolver(LauncherConfig config,
                                 ModMarketManager marketManager,
                                 Preferences preferences) {
        this.config = config;
        this.marketManager = marketManager;
        this.preferences = preferences;
        this.depPool = Executors.newSingleThreadExecutor(
                r -> {
                    Thread t = new Thread(r, "pmcl-dep-resolver");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 安装模组并自动解析安装其依赖。
     * <p>
     * 流程：
     * <ol>
     *   <li>下载安装主模组 jar</li>
     *   <li>优先使用 ModFile.getDependencies()（来自 Modrinth API 的 dependencies 字段）获取依赖列表</li>
     *   <li>若 API 未提供依赖信息，则用 {@link ModScanner#parseJar} 解析 jar 内元数据获取 depends 列表</li>
     *   <li>过滤系统依赖（minecraft、java、fabricloader 等）</li>
     *   <li>对每个剩余依赖 modId/projectId，检查是否已安装</li>
     *   <li>未安装的，在 Modrinth 上搜索，获取兼容版本文件并安装</li>
     *   <li>递归处理依赖的依赖（带循环检测）</li>
     * </ol>
     *
     * @param modFile     要安装的模组文件
     * @param gameVersion 目标 MC 版本
     * @param versionId   版本 ID（版本隔离模式下决定 instance 目录），可为 null
     * @param onStatus    状态回调，可为 null
     * @return 依赖安装结果
     */
    public CompletableFuture<DependencyResult> installWithDependencies(
            ModFile modFile, String gameVersion, String versionId,
            Consumer<String> onStatus) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> installed = new ArrayList<>();
            List<String> skippedInstalled = new ArrayList<>();
            List<String> skippedSystem = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            List<String> notFound = new ArrayList<>();
            Set<String> processing = new HashSet<>();

            try {
                // 1. 安装主模组
                if (onStatus != null) onStatus.accept("正在下载: " + modFile.getFileName());
                marketManager.installMod(modFile, gameVersion, versionId, preferences, onStatus).join();

                // 2. 优先使用 API 提供的依赖信息（无需解析 jar）
                List<String> deps = modFile.getDependencies();
                String modName = modFile.getFileName();

                if (deps == null || deps.isEmpty()) {
                    // API 未提供依赖信息，回退到解析 jar 内元数据
                    Path jarPath = resolveModsDir(versionId, gameVersion).resolve(modFile.getFileName());
                    if (!Files.exists(jarPath)) {
                        return new DependencyResult(modName, installed, skippedInstalled,
                                skippedSystem, failed, notFound);
                    }

                    ModMeta meta = ModScanner.parseJar(jarPath);
                    if (meta == null) {
                        return new DependencyResult(modName, installed, skippedInstalled,
                                skippedSystem, failed, notFound);
                    }

                    modName = meta.getName() != null ? meta.getName() : meta.getModId();
                    deps = meta.getDepends();
                } else {
                    if (onStatus != null) onStatus.accept("从 API 获取到 " + deps.size() + " 个依赖");
                }

                if (deps == null || deps.isEmpty()) {
                    if (onStatus != null) onStatus.accept("无额外依赖");
                    return new DependencyResult(modName, installed, skippedInstalled,
                            skippedSystem, failed, notFound);
                }

                if (onStatus != null) onStatus.accept("检测到 " + deps.size() + " 个依赖，开始解析...");

                // 3. 递归处理依赖（仅在此处调用一次 getInstalledModIds，递归内增量更新集合）
                Set<String> installedModIds = getInstalledModIds(versionId, gameVersion);
                resolveDependencies(deps, gameVersion, versionId, processing,
                        installed, skippedInstalled, skippedSystem, failed, notFound,
                        onStatus, 0, installedModIds);

            } catch (Throwable e) {
                failed.add(modFile.getFileName() + ": " + e.getMessage());
            }
            return new DependencyResult(modFile.getFileName(), installed, skippedInstalled,
                    skippedSystem, failed, notFound);
        }, depPool);
    }

    /**
     * 递归解析并安装依赖。
     *
     * @param deps             依赖 modId 列表
     * @param gameVersion      目标 MC 版本
     * @param versionId        版本 ID
     * @param processing       当前处理链（循环检测）
     * @param installed        已安装列表（输出）
     * @param skippedInstalled 已安装跳过列表（输出）
     * @param skippedSystem    系统依赖跳过列表（输出）
     * @param failed           失败列表（输出）
     * @param notFound         未找到列表（输出）
     * @param onStatus         状态回调
     * @param depth            递归深度（限制最大深度 10）
     * @param installedModIds  已安装 mod 的 modId 集合（可变，递归过程中增量更新）
     */
    private void resolveDependencies(List<String> deps, String gameVersion, String versionId,
                                     Set<String> processing,
                                     List<String> installed, List<String> skippedInstalled,
                                     List<String> skippedSystem, List<String> failed,
                                     List<String> notFound, Consumer<String> onStatus,
                                     int depth, Set<String> installedModIds) {
        if (depth > 10) return; // 防止无限递归

        for (String dep : deps) {
            // 解析依赖名：可能是 "modId" 或 "modId@version" 或 {"modId": "versionRange"} 形式
            String depId = extractModId(dep);
            if (depId == null || depId.isEmpty()) continue;

            // 循环检测
            if (processing.contains(depId)) {
                continue;
            }

            // 系统依赖跳过
            if (isSystemDep(depId)) {
                skippedSystem.add(depId);
                continue;
            }

            // 已安装跳过
            if (installedModIds.contains(depId)) {
                skippedInstalled.add(depId);
                continue;
            }

            // 防止重复安装
            if (installed.contains(depId)) {
                continue;
            }

            processing.add(depId);
            try {
                if (onStatus != null) {
                    onStatus.accept("查找依赖: " + depId);
                }

                // 在 Modrinth 上搜索依赖（用 modId 作为 slug）
                ModFile depFile = findCompatibleMod(depId, gameVersion);
                if (depFile == null) {
                    notFound.add(depId);
                    processing.remove(depId);
                    continue;
                }

                // 下载安装依赖
                if (onStatus != null) {
                    onStatus.accept("安装依赖: " + depFile.getFileName());
                }
                marketManager.installMod(depFile, gameVersion, versionId, preferences, null).join();
                installed.add(depId);
                installedModIds.add(depId); // 更新已安装集合

                // 递归解析依赖的依赖
                Path depJarPath = resolveModsDir(versionId, gameVersion).resolve(depFile.getFileName());
                if (Files.exists(depJarPath)) {
                    ModMeta depMeta = ModScanner.parseJar(depJarPath);
                    if (depMeta != null && depMeta.getDepends() != null && !depMeta.getDepends().isEmpty()) {
                        resolveDependencies(depMeta.getDepends(), gameVersion, versionId,
                                processing, installed, skippedInstalled, skippedSystem,
                                failed, notFound, onStatus, depth + 1, installedModIds);
                    }
                }
            } catch (Throwable e) {
                failed.add(depId + ": " + e.getMessage());
            } finally {
                processing.remove(depId);
            }
        }
    }

    /**
     * 在 Modrinth 上查找兼容的模组文件。
     * 用 modId 作为 slug 直接调用 listFiles，按 gameVersion 过滤取首个。
     *
     * @param modId       依赖 modId
     * @param gameVersion 目标 MC 版本
     * @return 兼容的 ModFile，未找到返回 null
     */
    private ModFile findCompatibleMod(String modId, String gameVersion) {
        // 通过 ModMarketManager 的客户端列表查询
        try {
            List<com.pmcl.core.market.ModMarketClient> clients = marketManager.getClients();

            for (com.pmcl.core.market.ModMarketClient client : clients) {
                try {
                    // 尝试用 modId 作为 projectId/slug 直接获取版本列表
                    List<ModFile> files = client.listFiles(modId).join();
                    if (files == null || files.isEmpty()) continue;

                    // 按 gameVersion 过滤，取第一个兼容文件
                    for (ModFile file : files) {
                        if (gameVersion == null || gameVersion.isEmpty()
                                || file.getGameVersions().contains(gameVersion)) {
                            return file;
                        }
                    }
                } catch (Throwable ignored) {
                    // 该客户端查询失败，尝试下一个
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 获取已安装 mod 的 modId 集合。
     */
    private Set<String> getInstalledModIds(String versionId, String gameVersion) {
        Set<String> ids = new HashSet<>();
        Path modsDir = resolveModsDir(versionId, gameVersion);
        if (!Files.isDirectory(modsDir)) return ids;
        try {
            List<ModMeta> mods = ModScanner.scanDirectory(modsDir);
            for (ModMeta m : mods) {
                if (m.getModId() != null && !m.isDisabled()) {
                    ids.add(m.getModId());
                }
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    /**
     * 解析 mods 目录（与 ModMarketManager.installMod 逻辑一致）。
     */
    private Path resolveModsDir(String versionId, String gameVersion) {
        if (preferences.isVersionIsolation() && versionId != null && !versionId.isEmpty()) {
            return config.getWorkDir().resolve("instances").resolve(versionId).resolve("mods");
        }
        Path modsDir = config.getWorkDir().resolve("mods");
        if (gameVersion != null && !gameVersion.isEmpty()) {
            modsDir = modsDir.resolve(gameVersion);
        }
        return modsDir;
    }

    /**
     * 从依赖字符串中提取 modId。
     * Fabric 的 depends 可能是：
     * - 字符串数组 ["modId1", "modId2"]
     * - 对象数组 [{"modId1": ">=1.0"}, {"modId2": "*"}]
     * - 字符串 "modId@version"
     * Forge 的 depends 是 modId 字符串。
     */
    private String extractModId(String dep) {
        if (dep == null || dep.isEmpty()) return null;
        // 处理 "modId@version" 格式
        int atIdx = dep.indexOf('@');
        if (atIdx > 0) {
            return dep.substring(0, atIdx);
        }
        // 处理 JSON 对象形式（如 {"modId": "version"}），ModScanner 可能解析为 "modId" 或保留原始格式
        // 去除可能的引号和花括号
        String cleaned = dep.replaceAll("[\"{}]", "").trim();
        // 如果包含冒号，取冒号前部分
        int colonIdx = cleaned.indexOf(':');
        if (colonIdx > 0) {
            return cleaned.substring(0, colonIdx).trim();
        }
        return cleaned;
    }

    /**
     * 判断是否为系统依赖（不需要安装）。
     */
    private boolean isSystemDep(String modId) {
        if (modId == null) return false;
        return SYSTEM_DEPS.contains(modId.toLowerCase());
    }

    /** 关闭线程池 */
    public void shutdown() {
        depPool.shutdown();
    }
}
