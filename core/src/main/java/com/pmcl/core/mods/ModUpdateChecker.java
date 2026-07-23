package com.pmcl.core.mods;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.market.ModFile;
import com.pmcl.core.market.ModMarketClient;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.market.ModProject;
import com.pmcl.core.preferences.Preferences;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 模组更新检测器：扫描已安装模组，在 Modrinth/CurseForge 上检测是否有新版本。
 * <p>
 * 检测策略：
 * <ol>
 *   <li>用 modId 作为 slug 直接调用 Modrinth {@code /project/{slug}/version}（多数 fabric mod 的 modId 即 slug）</li>
 *   <li>失败则用 {@code search(modId)} 搜索，取 slug 完全匹配或首个结果</li>
 *   <li>按 gameVersion + loader 过滤版本列表，取首个（API 默认按日期倒序）</li>
 *   <li>比较市场最新文件 fileName 与本地 jar 文件名，不同则认为有更新</li>
 * </ol>
 * <p>
 * 一键更新：删除旧 jar 文件 + 下载新 jar（复用 {@link ModMarketManager#installMod}）。
 */
public final class ModUpdateChecker {

    /**
     * 更新检测结果。
     */
    public static final class UpdateInfo {
        private final ModMeta installed;
        private final ModProject project;     // 匹配的市场项目（null 表示未找到）
        private final ModFile latestFile;     // 最新兼容文件（null 表示无兼容版本）
        private final String source;          // "modrinth" / "curseforge"
        private final boolean hasUpdate;      // 是否有更新
        private final String reason;          // 状态说明（如 "未找到项目"/"已是最新"/"有新版本"）

        public UpdateInfo(ModMeta installed, ModProject project, ModFile latestFile,
                          String source, boolean hasUpdate, String reason) {
            this.installed = installed;
            this.project = project;
            this.latestFile = latestFile;
            this.source = source;
            this.hasUpdate = hasUpdate;
            this.reason = reason;
        }

        public ModMeta getInstalled() { return installed; }
        public ModProject getProject() { return project; }
        public ModFile getLatestFile() { return latestFile; }
        public String getSource() { return source; }
        public boolean hasUpdate() { return hasUpdate; }
        public String getReason() { return reason; }

        /** 显示名（优先用市场项目名，fallback 到 mod 元数据名） */
        public String displayName() {
            if (project != null && project.getName() != null && !project.getName().isEmpty()) {
                return project.getName();
            }
            String n = installed.getName();
            return (n != null && !n.isEmpty()) ? n : installed.getModId();
        }
    }

    private final LauncherConfig config;
    private final ModMarketManager marketManager;
    private final Preferences preferences;

    /** 检测线程池：限制并发避免 API 限流（Modrinth 限流 10 req/s） */
    private final ExecutorService checkPool;

    public ModUpdateChecker(LauncherConfig config,
                            ModMarketManager marketManager,
                            Preferences preferences) {
        this.config = config;
        this.marketManager = marketManager;
        this.preferences = preferences;
        this.checkPool = Executors.newFixedThreadPool(5,
                r -> {
                    Thread t = new Thread(r, "pmcl-update-checker");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 批量检测模组更新。
     *
     * @param mods        已安装模组列表
     * @param gameVersion 目标 MC 版本（如 "1.20.4"），用于过滤兼容文件
     * @param onProgress  进度回调（已完成数 / 总数），可为 null
     * @return 更新检测结果列表
     */
    public CompletableFuture<List<UpdateInfo>> checkUpdates(List<ModMeta> mods,
                                                            String gameVersion,
                                                            Consumer<int[]> onProgress) {
        if (mods == null || mods.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        final String gv = gameVersion;
        final List<ModMeta> snapshot = new ArrayList<>(mods);
        final int total = snapshot.size();
        final AtomicInteger completed = new AtomicInteger(0);

        List<CompletableFuture<UpdateInfo>> futures = new ArrayList<>();
        for (ModMeta mod : snapshot) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                UpdateInfo info = checkOne(mod, gv);
                int done = completed.incrementAndGet();
                if (onProgress != null) {
                    onProgress.accept(new int[]{done, total});
                }
                return info;
            }, checkPool));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<UpdateInfo> results = new ArrayList<>();
                    for (CompletableFuture<UpdateInfo> f : futures) {
                        try {
                            results.add(f.join());
                        } catch (Throwable e) {
                            // 单个检测失败不影响整体
                        }
                    }
                    return results;
                });
    }

    /**
     * 检测单个模组是否有更新。
     */
    private UpdateInfo checkOne(ModMeta mod, String gameVersion) {
        if (mod == null || mod.getModId() == null || mod.getModId().isEmpty()) {
            return new UpdateInfo(mod, null, null, null, false, "无 modId");
        }
        if (mod.isDisabled()) {
            return new UpdateInfo(mod, null, null, null, false, "已禁用，跳过");
        }

        // 尝试每个市场客户端
        for (ModMarketClient client : getMarketClients()) {
            try {
                UpdateInfo info = checkOnClient(mod, gameVersion, client);
                if (info != null) return info;
            } catch (Throwable e) {
                // 该客户端检测失败，尝试下一个
            }
        }
        return new UpdateInfo(mod, null, null, null, false, "未找到项目");
    }

    /** 获取市场客户端列表 */
    private List<ModMarketClient> getMarketClients() {
        return marketManager.getClients();
    }

    /**
     * 在单个市场客户端上检测更新。
     *
     * @return UpdateInfo，若无法匹配则返回 null
     */
    private UpdateInfo checkOnClient(ModMeta mod, String gameVersion,
                                     ModMarketClient client) throws Exception {
        String source = client.source();
        String modId = mod.getModId();

        // 步骤1：尝试用 modId 作为 projectId/slug 直接 listFiles
        ModProject project = null;
        List<ModFile> files = null;
        try {
            files = client.listFiles(modId).join();
            // 构造一个虚拟的 ModProject（listFiles 成功说明 modId 即 slug/id）
            project = new ModProject(source, modId, modId,
                    mod.getName(), mod.getDescription(), mod.getAuthors(),
                    0, null, null);
        } catch (Throwable direct) {
            // 直接查询失败，走 search
        }

        // 步骤2：search 查找项目
        if (project == null) {
            List<ModProject> results = client.search(modId, gameVersion,
                    normalizeLoader(mod.getLoader()), 5).join();
            if (results == null || results.isEmpty()) return null;

            // 优先找 slug == modId 的项目
            for (ModProject p : results) {
                if (modId.equalsIgnoreCase(p.getSlug()) || modId.equalsIgnoreCase(p.getId())) {
                    project = p;
                    break;
                }
            }
            // fallback 取第一个
            if (project == null) {
                project = results.get(0);
            }
            files = client.listFiles(project.getId()).join();
        }

        if (files == null || files.isEmpty()) {
            return new UpdateInfo(mod, project, null, source, false, "无可用版本");
        }

        // 步骤3：按 gameVersion + loader 过滤
        String loader = normalizeLoader(mod.getLoader());
        List<ModFile> compatible = new ArrayList<>();
        for (ModFile f : files) {
            boolean gvMatch = gameVersion == null || gameVersion.isEmpty()
                    || f.getGameVersions().contains(gameVersion);
            boolean loaderMatch = loader == null || loader.isEmpty()
                    || f.getLoaders().contains(loader)
                    || f.getLoaders().isEmpty(); // 部分文件无 loader 标记
            if (gvMatch && loaderMatch) {
                compatible.add(f);
            }
        }

        if (compatible.isEmpty()) {
            return new UpdateInfo(mod, project, null, source, false,
                    "无 " + gameVersion + "/" + loader + " 兼容版本");
        }

        // 步骤4：取最新文件（列表通常按日期倒序，取第一个）
        ModFile latest = compatible.get(0);

        // 步骤5：比较文件名判断是否有更新
        String localJar = mod.getJarFile();
        String remoteName = latest.getFileName();
        boolean hasUpdate = !localJar.equalsIgnoreCase(remoteName);

        String reason = hasUpdate
                ? "新版本: " + remoteName
                : "已是最新";

        return new UpdateInfo(mod, project, latest, source, hasUpdate, reason);
    }

    /**
     * 规范化加载器名称：fabric→fabric, forge→forge, neoforge→neoforge, quilt→quilt。
     * unknown 返回 null（不过滤 loader）。
     */
    private String normalizeLoader(String loader) {
        if (loader == null || loader.isEmpty() || "unknown".equalsIgnoreCase(loader)) {
            return null;
        }
        return loader.toLowerCase();
    }

    /**
     * 更新单个模组：删除旧 jar + 下载新 jar。
     *
     * @param info       更新信息
     * @param gameVersion 目标 MC 版本
     * @param versionId  版本 ID（版本隔离模式下决定 instance 目录），可为 null
     * @param onStatus   状态回调，可为 null
     */
    public CompletableFuture<Void> updateMod(UpdateInfo info, String gameVersion,
                                             String versionId, Consumer<String> onStatus) {
        if (info == null || !info.hasUpdate() || info.getLatestFile() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("无可用更新"));
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // 1. 删除旧 jar 文件
                ModMeta mod = info.getInstalled();
                Path modsDir = resolveModsDir(versionId, gameVersion);
                Path oldJar = modsDir.resolve(mod.getJarFile());
                if (Files.exists(oldJar)) {
                    Files.delete(oldJar);
                    if (onStatus != null) onStatus.accept("已删除旧版本: " + mod.getJarFile());
                }

                // 2. 下载新 jar
                if (onStatus != null) onStatus.accept("正在下载: " + info.getLatestFile().getFileName());
                marketManager.installMod(info.getLatestFile(), gameVersion, versionId,
                        preferences, onStatus).join();
                if (onStatus != null) onStatus.accept("更新完成: " + info.displayName());
            } catch (Exception e) {
                throw new RuntimeException("更新失败: " + info.displayName(), e);
            }
        }, checkPool);
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
     * 批量更新所有有更新的模组。
     *
     * @param updates    更新信息列表（仅 hasUpdate=true 的会被更新）
     * @param gameVersion 目标 MC 版本
     * @param versionId  版本 ID
     * @param onProgress 进度回调（已完成数 / 总数），可为 null
     */
    public CompletableFuture<Void> updateAll(List<UpdateInfo> updates, String gameVersion,
                                             String versionId, Consumer<int[]> onProgress) {
        if (updates == null || updates.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<UpdateInfo> toUpdate = new ArrayList<>();
        for (UpdateInfo info : updates) {
            if (info.hasUpdate()) toUpdate.add(info);
        }
        if (toUpdate.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        final int total = toUpdate.size();
        final AtomicInteger completed = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (UpdateInfo info : toUpdate) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    updateMod(info, gameVersion, versionId, null).join();
                } catch (Throwable ignored) {
                    // 单个更新失败不影响整体
                } finally {
                    int done = completed.incrementAndGet();
                    if (onProgress != null) {
                        onProgress.accept(new int[]{done, total});
                    }
                }
            }, checkPool));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /** 关闭线程池：先停止接受新任务，再等待已提交任务完成（最多 5 秒） */
    public void shutdown() {
        checkPool.shutdown();
        try {
            if (!checkPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                // 仍有任务未完成，强制中断
                checkPool.shutdownNow();
                checkPool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            checkPool.shutdownNow();
        }
    }
}
