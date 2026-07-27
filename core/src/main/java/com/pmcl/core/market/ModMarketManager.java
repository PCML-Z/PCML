package com.pmcl.core.market;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.preferences.Preferences;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 模组市场聚合管理：同时支持 CurseForge 与 Modrinth。
 * <p>
 * 通过 {@link #search(String, String, String, int)} 聚合两个平台结果，
 * 通过 {@link #installMod(ModFile, String)} 下载到 mods 目录。
 */
public final class ModMarketManager {

    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final List<ModMarketClient> clients = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ModMarketManager(LauncherConfig config, DownloadManager downloads) {
        this.config = config;
        this.downloads = downloads;
        // Modrinth 不需要 key，直接接入
        this.clients.add(new ModrinthClient(downloads));
        // CurseForge 需要从环境变量读取 API Key；未配置则跳过
        String cfKey = System.getenv("CURSEFORGE_API_KEY");
        if (cfKey == null || cfKey.isEmpty()) {
            cfKey = System.getProperty("curseforge.api.key");
        }
        if (cfKey != null && !cfKey.isEmpty()) {
            this.clients.add(new CurseForgeClient(cfKey, downloads));
        }
    }

    /** 是否启用了 CurseForge（取决于是否配置 API Key） */
    public boolean hasCurseForge() {
        return clients.stream().anyMatch(c -> "curseforge".equals(c.source()));
    }

    /** 获取 Modrinth 客户端实例（用于整合包更新检查等高级 API） */
    public ModrinthClient getModrinthClient() {
        return (ModrinthClient) clients.stream()
                .filter(c -> "modrinth".equals(c.source()))
                .findFirst()
                .orElse(null);
    }

    /** 获取所有已注册的市场客户端列表（不可变视图） */
    public List<ModMarketClient> getClients() {
        return Collections.unmodifiableList(clients);
    }

    /**
     * 更新所有客户端的 OkHttpClient 引用（用户在设置中修改代理后调用）。
     * 让 mod 市场请求也能立即走代理。
     */
    public void updateHttpClients(OkHttpClient http) {
        for (ModMarketClient c : clients) {
            c.updateHttpClient(http);
        }
    }

    /**
     * 跨平台聚合搜索：并发查询所有客户端，合并结果。
     */
    public CompletableFuture<List<ModProject>> search(String query, String gameVersion,
                                                     String loader, int limit) {
        List<CompletableFuture<List<ModProject>>> futures = new ArrayList<>();
        for (ModMarketClient c : clients) {
            // S14: exceptionally 将异常转为空列表，避免 allOf 因单源失败而整体失败
            // 原 try-catch in thenApply 是死代码：allOf 异常完成时 thenApply 不执行
            futures.add(c.search(query, gameVersion, loader, limit)
                    .exceptionally(ex -> {
                        logMarketFailure(c, "search", ex);
                        return Collections.emptyList();
                    }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<ModProject> merged = new ArrayList<>();
                    for (CompletableFuture<List<ModProject>> f : futures) {
                        merged.addAll(f.join());
                    }
                    return merged;
                });
    }

    /**
     * 跨平台聚合搜索（带分类过滤）：关键字 + 分类 AND 关系。
     * 不支持分类的平台会忽略 category（仅按关键字搜索）。
     */
    public CompletableFuture<List<ModProject>> search(String query, String gameVersion,
                                                     String loader, String category, int limit) {
        if (category == null || category.isEmpty()) {
            return search(query, gameVersion, loader, limit);
        }
        List<CompletableFuture<List<ModProject>>> futures = new ArrayList<>();
        for (ModMarketClient c : clients) {
            // S14: exceptionally 将异常转为空列表，避免 allOf 因单源失败而整体失败
            futures.add(c.search(query, gameVersion, loader, category, limit)
                    .exceptionally(ex -> {
                        logMarketFailure(c, "search+category", ex);
                        return Collections.emptyList();
                    }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<ModProject> merged = new ArrayList<>();
                    for (CompletableFuture<List<ModProject>> f : futures) {
                        merged.addAll(f.join());
                    }
                    return merged;
                });
    }

    /**
     * 跨平台聚合获取热门项目：并发查询所有客户端，合并结果。
     * 用于「热门推荐」卡片网格展示。
     */
    public CompletableFuture<List<ModProject>> popular(String gameVersion, String loader, int limit) {
        List<CompletableFuture<List<ModProject>>> futures = new ArrayList<>();
        for (ModMarketClient c : clients) {
            // S14: exceptionally 将异常转为空列表，避免 allOf 因单源失败而整体失败
            futures.add(c.popular(gameVersion, loader, limit)
                    .exceptionally(ex -> {
                        logMarketFailure(c, "popular", ex);
                        return Collections.emptyList();
                    }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<ModProject> merged = new ArrayList<>();
                    for (CompletableFuture<List<ModProject>> f : futures) {
                        merged.addAll(f.join());
                    }
                    return merged;
                });
    }

    /**
     * 跨平台聚合按分类浏览：并发查询所有客户端，合并结果。
     * 用于「分类推荐」功能：用户点击分类标签后加载该分类下的热门项目。
     * 不支持分类浏览的平台（如 CurseForge）会返回空列表，不影响其他源。
     */
    public CompletableFuture<List<ModProject>> searchByCategory(String category, String gameVersion,
                                                                 String loader, int limit) {
        if (category == null || category.isEmpty()) {
            return popular(gameVersion, loader, limit);
        }
        List<CompletableFuture<List<ModProject>>> futures = new ArrayList<>();
        for (ModMarketClient c : clients) {
            // S14: exceptionally 将异常转为空列表，避免 allOf 因单源失败而整体失败
            futures.add(c.searchByCategory(category, gameVersion, loader, limit)
                    .exceptionally(ex -> {
                        logMarketFailure(c, "searchByCategory", ex);
                        return Collections.emptyList();
                    }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<ModProject> merged = new ArrayList<>();
                    for (CompletableFuture<List<ModProject>> f : futures) {
                        merged.addAll(f.join());
                    }
                    return merged;
                });
    }

    /**
     * 列出某项目所有文件（按来源分发到对应客户端）。
     */
    public CompletableFuture<List<ModFile>> listFiles(ModProject project) {
        for (ModMarketClient c : clients) {
            if (c.source().equals(project.getSource())) {
                return c.listFiles(project.getId());
            }
        }
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    /**
     * 安装模组：下载到 mods 目录。
     *
     * @param file         模组文件
     * @param gameVersion  目标 MC 版本（决定 mods 子目录，如 mods/1.20.4）
     */
    public CompletableFuture<Void> installMod(ModFile file, String gameVersion) {
        return installMod(file, gameVersion, null);
    }

    /**
     * 安装模组：下载到 mods 目录，带进度回调。
     *
     * @param file         模组文件
     * @param gameVersion  目标 MC 版本（决定 mods 子目录，如 mods/1.20.4）
     * @param onStatus     状态回调（如 "正在下载 xxx.jar..."），可为 null
     */
    public CompletableFuture<Void> installMod(ModFile file, String gameVersion,
                                              Consumer<String> onStatus) {
        return installMod(file, gameVersion, null, null, onStatus);
    }

    /**
     * 安装模组到 mods 目录。
     * <p>
     * 版本隔离开启时，模组安装到 {@code instances/<versionId>/mods/}；
     * 否则安装到 {@code mods/<gameVersion>/}。
     *
     * @param file         模组文件
     * @param gameVersion  目标 MC 版本（非隔离模式下决定 mods 子目录）
     * @param versionId    版本 ID（隔离模式下决定 instance 目录），可为 null
     * @param preferences  偏好设置（判断是否开启版本隔离），可为 null
     * @param onStatus     状态回调
     */
    public CompletableFuture<Void> installMod(ModFile file, String gameVersion,
                                              String versionId, Preferences preferences,
                                              Consumer<String> onStatus) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path modsDir;
                if (preferences != null && preferences.isVersionIsolation()
                        && versionId != null && !versionId.isEmpty()) {
                    // 版本隔离：安装到 instances/<versionId>/mods/
                    modsDir = config.getWorkDir().resolve("instances").resolve(versionId).resolve("mods");
                } else {
                    modsDir = config.getWorkDir().resolve("mods");
                    if (gameVersion != null && !gameVersion.isEmpty()) {
                        modsDir = modsDir.resolve(gameVersion);
                    }
                }
                String fileName = file.getFileName();
                if (fileName == null || fileName.isBlank()
                        || fileName.contains("..")
                        || fileName.contains("/")
                        || fileName.contains("\\")
                        || fileName.indexOf('\0') >= 0) {
                    throw new IOException("非法模组文件名: " + fileName);
                }
                Path modsAbs = modsDir.toAbsolutePath().normalize();
                Path target = modsAbs.resolve(fileName).normalize();
                if (!target.startsWith(modsAbs)) {
                    throw new IOException("模组路径越界: " + fileName);
                }
                // 重复安装检测：覆盖下载
                if (java.nio.file.Files.exists(target)) {
                    if (onStatus != null) onStatus.accept("覆盖已存在: " + fileName);
                }
                java.nio.file.Files.createDirectories(modsAbs);
                if (onStatus != null) {
                    onStatus.accept("正在下载: " + file.getFileName()
                            + " (" + (file.getFileSize() / 1024) + " KB)");
                }
                String sha1 = file.getSha1();
                String sha512 = file.getSha512();
                if ((sha1 == null || sha1.isBlank()) && (sha512 == null || sha512.isBlank())) {
                    throw new IOException("模组缺少 SHA-1/SHA-512，拒绝安装未校验文件: "
                            + file.getFileName());
                }
                downloads.downloadToVerified(
                        file.getDownloadUrl(), target, sha1, sha512);
                if (onStatus != null) onStatus.accept("完成: " + file.getFileName());
            } catch (Exception e) {
                throw new RuntimeException("模组下载失败: " + file.getFileName(), e);
            }
        });
    }

    /** 单源失败时记录日志，避免 UI 把“源故障”误当成“无结果”却无从排查 */
    private static void logMarketFailure(ModMarketClient client, String op, Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String src = client != null ? String.valueOf(client.source()) : "?";
        System.err.println("[ModMarket] " + src + " " + op + " 失败: "
                + (root.getMessage() != null ? root.getMessage() : root));
    }
}
