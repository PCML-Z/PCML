package com.pmcl.core.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.market.CurseForgeClient;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.modloader.ModLoader;
import com.pmcl.core.modloader.ModLoaderManager;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.util.Exceptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 整合包管理器：支持导入/导出 Modrinth (.mrpack) 和 CurseForge (.zip) 格式整合包。
 * <p>
 * 导入流程：
 * <ol>
 *   <li>解析 manifest（modrinth.index.json 或 manifest.json）</li>
 *   <li>安装原版 Minecraft（调用 VersionInstaller）</li>
 *   <li>安装模组加载器（如有，调用 ModLoaderManager）</li>
 *   <li>下载所有 mods 文件到 instances/&lt;name&gt;/mods/</li>
 *   <li>解压 overrides/ 到 instances/&lt;name&gt;/（config、resourcepacks 等）</li>
 * </ol>
 * <p>
 * 导出流程：
 * <ol>
 *   <li>收集 instances/&lt;versionId&gt;/mods/ 下的所有 jar</li>
 *   <li>生成 manifest（默认 Modrinth 格式）</li>
 *   <li>打包 overrides/（config、resourcepacks、shaderpacks 等）</li>
 *   <li>写入 zip 文件</li>
 * </ol>
 */
public final class ModpackManager {

    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final VersionInstaller versionInstaller;
    private final ModLoaderManager modLoaderManager;
    private final Preferences preferences;
    private final ModMarketManager modMarketManager;

    public ModpackManager(LauncherConfig config, DownloadManager downloads,
                          VersionInstaller versionInstaller,
                          ModLoaderManager modLoaderManager,
                          Preferences preferences,
                          ModMarketManager modMarketManager) {
        this.config = config;
        this.downloads = downloads;
        this.versionInstaller = versionInstaller;
        this.modLoaderManager = modLoaderManager;
        this.preferences = preferences;
        this.modMarketManager = modMarketManager;
    }

    // ===== 数据类 =====

    /** 整合包清单信息（从 manifest 解析） */
    public static final class ModpackInfo {
        public final String name;
        public final String gameVersion;
        public final String loader;        // "fabric" / "forge" / "quilt" / "neoforge" / null
        public final String loaderVersion; // 加载器版本，如 "0.15.7"
        public final String format;        // "modrinth" / "curseforge"
        public final String author;        // 作者（可选）

        public ModpackInfo(String name, String gameVersion, String loader,
                           String loaderVersion, String format, String author) {
            this.name = name;
            this.gameVersion = gameVersion;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
            this.format = format;
            this.author = author;
        }
    }

    /** 整合包中的单个模组文件信息 */
    public static final class ModpackFile {
        public final String path;         // 目标路径，如 "mods/foo.jar"
        public final String hash;         // SHA1 哈希
        public final long size;           // 文件大小
        public final String downloadUrl;  // 下载 URL（CurseForge 可能为空，需查询 API）
        public final String projectId;    // CurseForge project ID（可选）
        public final String fileId;       // CurseForge file ID（可选）

        public ModpackFile(String path, String hash, long size, String downloadUrl,
                           String projectId, String fileId) {
            this.path = path;
            this.hash = hash;
            this.size = size;
            this.downloadUrl = downloadUrl;
            this.projectId = projectId;
            this.fileId = fileId;
        }
    }

    /** 单个 mod 的更新信息 */
    public static final class ModUpdate {
        public final String fileName;
        public final String currentVersion;  // 当前版本号（可能为空）
        public final String latestVersion;   // 最新版本号
        public final String projectId;
        public final String downloadUrl;     // 最新版本下载 URL
        public final String loader;          // 加载器

        public ModUpdate(String fileName, String currentVersion, String latestVersion,
                         String projectId, String downloadUrl, String loader) {
            this.fileName = fileName;
            this.currentVersion = currentVersion != null ? currentVersion : "";
            this.latestVersion = latestVersion != null ? latestVersion : "";
            this.projectId = projectId;
            this.downloadUrl = downloadUrl;
            this.loader = loader;
        }
    }

    /** 整合包更新检查结果 */
    public static final class ModpackUpdateResult {
        public final String instanceName;
        public final List<ModUpdate> updates;  // 有更新的 mod 列表
        public final int totalChecked;         // 已检查的 mod 数
        public final String error;             // 错误信息（null 表示成功）

        public ModpackUpdateResult(String instanceName, List<ModUpdate> updates,
                                   int totalChecked, String error) {
            this.instanceName = instanceName;
            this.updates = updates != null ? updates : new ArrayList<>();
            this.totalChecked = totalChecked;
            this.error = error;
        }

        public boolean isSuccess() { return error == null; }
        public boolean hasUpdates() { return !updates.isEmpty(); }
    }

    /** 已安装的整合包实例 */
    public static final class InstalledModpack {
        public final String name;
        public final String gameVersion;
        public final String loader;
        public final String loaderVersion;
        public final Path instanceDir;
        public final long modCount;
        public final String source;         // 来源标签（"PMCL" / "外部" / 版本 ID）

        public InstalledModpack(String name, String gameVersion, String loader,
                                String loaderVersion, Path instanceDir, long modCount,
                                String source) {
            this.name = name;
            this.gameVersion = gameVersion;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
            this.instanceDir = instanceDir;
            this.modCount = modCount;
            this.source = source;
        }

        public String getSource() { return source; }
    }

    // ===== 导入 =====

    /**
     * 导入整合包文件。
     * 自动识别 Modrinth (.mrpack) 或 CurseForge (.zip) 格式。
     *
     * @param file      整合包文件路径
     * @param onProgress 进度回调
     */
    public CompletableFuture<Void> importModpack(Path file, Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                doImport(file, onProgress);
            } catch (Throwable e) {
                if (onProgress != null) {
                    onProgress.accept(new InstallProgress(InstallProgress.Stage.FAILED, 0, 0,
                            "整合包导入失败: " + e.getMessage()));
                }
                throw new RuntimeException("整合包导入失败", e);
            }
        });
    }

    private void doImport(Path file, Consumer<InstallProgress> progress) throws Exception {
        if (!Files.exists(file)) {
            throw new IOException("整合包文件不存在: " + file);
        }

        // 1. 解析 manifest
        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0, "正在解析整合包清单..."));

        ParsedManifest manifest = parseManifest(file);
        String instanceName = sanitizeName(manifest.name);
        Path instanceDir = config.getWorkDir().resolve("instances").resolve(instanceName);

        // 如果实例目录已存在，追加序号
        int suffix = 1;
        while (Files.exists(instanceDir)) {
            instanceDir = config.getWorkDir().resolve("instances")
                    .resolve(instanceName + "-" + suffix);
            suffix++;
        }

        Files.createDirectories(instanceDir);
        for (String sub : new String[]{"mods", "saves", "config", "resourcepacks",
                "shaderpacks", "screenshots", "logs"}) {
            Files.createDirectories(instanceDir.resolve(sub));
        }

        // 2. 安装原版 Minecraft
        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_CLIENT, 0, 0,
                "正在安装 Minecraft " + manifest.gameVersion + "..."));

        versionInstaller.install(manifest.gameVersion, p -> {
            if (progress != null) progress.accept(p);
        }).join();

        // 3. 安装模组加载器
        if (manifest.loader != null && !manifest.loader.isEmpty()
                && manifest.loaderVersion != null && !manifest.loaderVersion.isEmpty()) {
            if (progress != null) progress.accept(new InstallProgress(
                    InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 0,
                    "正在安装 " + manifest.loader + " " + manifest.loaderVersion + "..."));

            ModLoader ml = parseLoader(manifest.loader);
            if (ml != null && modLoaderManager.supports(ml)) {
                modLoaderManager.get(ml).install(manifest.gameVersion,
                        manifest.loaderVersion, p -> {
                            if (progress != null) progress.accept(p);
                        }).join();
            }
        }

        // 4. 下载 mods（任一下载失败则整体失败，避免「导入成功但零模组」）
        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_ASSETS, 0, manifest.files.size(),
                "正在下载模组 (0/" + manifest.files.size() + ")..."));

        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> failSamples = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(16, Math.max(2, Math.max(1, manifest.files.size()))));
        final Path instanceDirFinal = instanceDir.toAbsolutePath().normalize();
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ModpackFile mf : manifest.files) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        downloadModpackFile(mf, instanceDirFinal);
                    } catch (Throwable e) {
                        if (InstallInterruptedException.isInterrupted(e)) {
                            throw e instanceof RuntimeException
                                    ? (RuntimeException) e
                                    : new InstallInterruptedException("整合包模组下载已中断", e);
                        }
                        failCount.incrementAndGet();
                        String detail = mf.path + ": " + Exceptions.rootMessage(e);
                        if (failSamples.size() < 5) failSamples.add(detail);
                        System.err.println("[ModpackManager] 模组下载失败: " + detail);
                    }
                    int done = completed.incrementAndGet();
                    if (progress != null) progress.accept(new InstallProgress(
                            InstallProgress.Stage.DOWNLOAD_ASSETS, done, manifest.files.size(),
                            "正在下载模组 (" + done + "/" + manifest.files.size() + ")..."));
                }, pool));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (java.util.concurrent.CompletionException ce) {
                Throwable c = ce.getCause() != null ? ce.getCause() : ce;
                if (InstallInterruptedException.isInterrupted(c)) {
                    throw c instanceof RuntimeException
                            ? (RuntimeException) c
                            : new InstallInterruptedException("整合包导入已中断", c);
                }
                throw ce;
            }
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        if (!manifest.files.isEmpty() && failCount.get() > 0) {
            String preview = String.join("; ", failSamples);
            throw new IOException("整合包模组下载失败 " + failCount.get() + "/"
                    + manifest.files.size() + " 个（示例: " + preview + "）");
        }

        // 5. 解压 overrides
        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 0,
                "正在解压配置文件..."));

        extractOverrides(file, instanceDir, manifest.format);

        // 6. 保存实例信息
        saveInstanceInfo(instanceDir, manifest);

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DONE, 0, 0,
                "整合包 '" + manifest.name + "' 导入完成"));
    }

    /** 下载单个整合包模组文件（含 CF URL/SHA 解析）。 */
    private void downloadModpackFile(ModpackFile mf, Path instanceDirAbs) throws IOException {
        String url = mf.downloadUrl;
        String sha1 = mf.hash != null ? mf.hash : "";
        // S12: CF 整合包 manifest 不含 downloadUrl/hash，需通过 API 查询
        if ((url == null || url.isEmpty() || sha1.isBlank())
                && mf.projectId != null && !mf.projectId.isEmpty()
                && mf.fileId != null && !mf.fileId.isEmpty()) {
            CfResolved resolved = resolveCurseForgeFile(mf.projectId, mf.fileId);
            if (url == null || url.isEmpty()) url = resolved.url;
            if (sha1.isBlank()) sha1 = resolved.sha1;
        }
        if (url == null || url.isEmpty()) {
            throw new IOException("无下载 URL");
        }
        validateDownloadUrl(url);
        Path target = instanceDirAbs.resolve(mf.path).normalize();
        if (!target.startsWith(instanceDirAbs)) {
            throw new IOException("非法路径: " + mf.path);
        }
        Files.createDirectories(target.getParent());
        if (sha1 == null || sha1.isBlank()) {
            // 最后手段：无哈希则下载后拒绝过小文件
            downloads.downloadTo(url, target);
            if (Files.size(target) < 32) {
                Files.deleteIfExists(target);
                throw new IOException("下载文件过小且无 SHA-1");
            }
            return;
        }
        downloads.downloadToVerified(url, target, sha1, null);
    }

    private void validateDownloadUrl(String url) throws IOException {
        if (url == null || url.isEmpty()) throw new IOException("空下载 URL");
        java.net.URI uri;
        try { uri = java.net.URI.create(url); } catch (Exception e) {
            throw new IOException("非法下载 URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IOException("非法下载协议: " + scheme);
        }
        String host = uri.getHost();
        if (host == null) throw new IOException("下载 URL 缺少 host: " + url);
        // 内网主机校验
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            if (com.pmcl.core.util.SsrfChecker.isInternalAddress(addr)) {
                throw new IOException("非法下载主机（内网地址）: " + host);
            }
        } catch (java.net.UnknownHostException e) {
            throw new IOException("无法解析下载主机: " + host, e);
        }
    }

    /**
     * S12: 通过 CurseForge API 查询模组文件的下载 URL 与 SHA-1。
     * CF 整合包 manifest 只含 projectID/fileID。
     */
    private CfResolved resolveCurseForgeFile(String projectId, String fileId) {
        try {
            for (com.pmcl.core.market.ModMarketClient c : modMarketManager.getClients()) {
                if (!"curseforge".equals(c.source())) continue;
                var files = c.listFiles(projectId).join();
                for (var f : files) {
                    if (fileId.equals(f.getFileId())) {
                        String u = f.getDownloadUrl() != null ? f.getDownloadUrl() : "";
                        String s = f.getSha1() != null ? f.getSha1() : "";
                        return new CfResolved(u, s);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ModpackManager] CF 模组查询失败: "
                    + projectId + "/" + fileId + " - " + e.getMessage());
        }
        return new CfResolved("", "");
    }

    private static final class CfResolved {
        final String url;
        final String sha1;
        CfResolved(String url, String sha1) {
            this.url = url == null ? "" : url;
            this.sha1 = sha1 == null ? "" : sha1;
        }
    }

    // ===== 导出 =====

    /**
     * 导出整合包为 Modrinth .mrpack 格式。
     *
     * @param versionId   要导出的版本 ID（或实例名）
     * @param targetPath  目标文件路径（.mrpack）
     * @param onProgress  进度回调
     */
    public CompletableFuture<Void> exportModpack(String versionId, Path targetPath,
                                                  Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                doExport(versionId, targetPath, onProgress);
            } catch (Throwable e) {
                if (onProgress != null) {
                    onProgress.accept(new InstallProgress(InstallProgress.Stage.FAILED, 0, 0,
                            "整合包导出失败: " + e.getMessage()));
                }
                throw new RuntimeException("整合包导出失败", e);
            }
        });
    }

    private void doExport(String versionId, Path targetPath,
                          Consumer<InstallProgress> progress) throws Exception {
        // 确定 gameDir
        Path gameDir;
        if (preferences.isVersionIsolation()) {
            gameDir = config.getWorkDir().resolve("instances").resolve(versionId);
        } else {
            gameDir = config.getWorkDir();
        }

        if (!Files.isDirectory(gameDir)) {
            throw new IOException("版本目录不存在: " + gameDir);
        }

        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            throw new IOException("mods 目录不存在，无法导出整合包");
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0,
                "正在收集模组信息..."));

        // 收集 mods 列表
        List<Path> modFiles = new ArrayList<>();
        try (var stream = Files.list(modsDir)) {
            stream.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                    && !p.toString().endsWith(".disabled"))
                    .forEach(modFiles::add);
        }

        // S11: 从 modpack.json 读取真实游戏版本（versionId 是实例目录名，不是 MC 版本）
        // 否则导出的整合包导入时无法匹配 Mojang 版本，导入必然失败
        String gameVersion = versionId;
        Path modpackJson = gameDir.resolve("modpack.json");
        if (Files.isRegularFile(modpackJson)) {
            try {
                JsonObject info = JsonParser.parseString(Files.readString(modpackJson,
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                String gv = safeStr(info, "gameVersion", "");
                if (!gv.isEmpty()) gameVersion = gv;
            } catch (Exception ignored) {
            }
        }

        // 生成 modrinth.index.json
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", versionId);
        index.addProperty("name", versionId);

        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", gameVersion);
        index.add("dependencies", dependencies);

        var filesArray = new com.google.gson.JsonArray();
        for (int i = 0; i < modFiles.size(); i++) {
            Path mod = modFiles.get(i);
            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("path", "mods/" + mod.getFileName().toString());
            var hashes = new JsonObject();
            hashes.addProperty("sha1", sha1Hex(mod));
            fileObj.add("hashes", hashes);
            fileObj.addProperty("size", Files.size(mod));
            // 无下载 URL，标记为本地文件（导入方需手动处理）
            fileObj.addProperty("downloads", "");
            filesArray.add(fileObj);

            if (progress != null) progress.accept(new InstallProgress(
                    InstallProgress.Stage.DOWNLOAD_CLIENT, i + 1, modFiles.size(),
                    "正在处理模组 (" + (i + 1) + "/" + modFiles.size() + ")..."));
        }
        index.add("files", filesArray);

        // 打包 zip
        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 0,
                "正在打包整合包..."));

        Files.createDirectories(targetPath.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetPath))) {
            // 写入 modrinth.index.json
            zos.putNextEntry(new ZipEntry("modrinth.index.json"));
            zos.write(index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // 写入 overrides/mods/*.jar
            for (Path mod : modFiles) {
                String entryName = "overrides/mods/" + mod.getFileName().toString();
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(mod, zos);
                zos.closeEntry();
            }

            // 写入 overrides 中的其他目录（config, resourcepacks, shaderpacks, options.txt）
            addOverrideDir(zos, gameDir, "config");
            addOverrideDir(zos, gameDir, "resourcepacks");
            addOverrideDir(zos, gameDir, "shaderpacks");
            addOverrideFile(zos, gameDir, "options.txt");
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DONE, 0, 0,
                "整合包已导出: " + targetPath));
    }

    /**
     * 导出 CurseForge 格式整合包（manifest.json + overrides）。
     * <p>
     * 离线导出：files 数组留空（无 projectID/fileID），所有 mods 直接放入
     * overrides/mods/。此格式可被 HMCL / PCL2 / MultiMC / CurseForge 客户端导入。
     *
     * @param versionId  基础版本 ID
     * @param targetPath 目标 .zip 路径
     * @param onProgress 进度回调
     */
    public CompletableFuture<Void> exportCurseForge(String versionId, Path targetPath,
                                                    Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                doExportCurseForge(versionId, targetPath, onProgress);
            } catch (Throwable e) {
                if (onProgress != null) {
                    onProgress.accept(new InstallProgress(InstallProgress.Stage.FAILED, 0, 0,
                            "CurseForge 整合包导出失败: " + e.getMessage()));
                }
                throw new RuntimeException("CurseForge 整合包导出失败", e);
            }
        });
    }

    private void doExportCurseForge(String versionId, Path targetPath,
                                    Consumer<InstallProgress> progress) throws Exception {
        // 确定 gameDir（与 doExport 一致）
        Path gameDir;
        if (preferences.isVersionIsolation()) {
            gameDir = config.getWorkDir().resolve("instances").resolve(versionId);
        } else {
            gameDir = config.getWorkDir();
        }
        if (!Files.isDirectory(gameDir)) {
            throw new IOException("版本目录不存在: " + gameDir);
        }
        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            throw new IOException("mods 目录不存在，无法导出整合包");
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0,
                "正在收集模组信息..."));

        // 尝试从 modpack.json 读取 loader 信息
        String loader = "";
        String loaderVersion = "";
        String author = "PMCL";
        // S11: gameVersion 必须从 modpack.json 读取，versionId 是实例目录名而非 MC 版本
        String gameVersion = versionId;
        Path modpackJson = gameDir.resolve("modpack.json");
        if (Files.isRegularFile(modpackJson)) {
            try {
                JsonObject info = JsonParser.parseString(Files.readString(modpackJson, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (info.has("loader")) loader = safeStr(info, "loader", "");
                if (info.has("loaderVersion")) loaderVersion = safeStr(info, "loaderVersion", "");
                if (info.has("author")) author = safeStr(info, "author", "PMCL");
                String gv = safeStr(info, "gameVersion", "");
                if (!gv.isEmpty()) gameVersion = gv;
            } catch (Exception ignored) {
            }
        }

        // 收集 mods 列表
        List<Path> modFiles = new ArrayList<>();
        try (var stream = Files.list(modsDir)) {
            stream.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                    && !p.toString().endsWith(".disabled"))
                    .forEach(modFiles::add);
        }

        // 在线 fingerprint 查询：补全 projectID/fileID
        // CurseForge 整合包标准要求 manifest.files 包含 projectID/fileID，
        // 离线导出（无 API Key 或查询失败）时 files 留空，mods 直接打包到 overrides/。
        com.google.gson.JsonArray filesArray = new com.google.gson.JsonArray();
        boolean onlineMode = false;
        int matchedCount = 0;
        java.util.Set<Path> matchedMods = new java.util.HashSet<>();
        CurseForgeClient cfClient = null;
        if (modMarketManager != null && modMarketManager.hasCurseForge()) {
            for (com.pmcl.core.market.ModMarketClient c : modMarketManager.getClients()) {
                if (c instanceof CurseForgeClient) {
                    cfClient = (CurseForgeClient) c;
                    break;
                }
            }
        }
        if (cfClient != null) {
            try {
                if (progress != null) progress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0,
                        "正在在线查询模组 CurseForge 信息..."));
                // 计算 Murmur2 哈希并批量查询（只查一次，结果缓存到 modHashes）
                java.util.List<Long> fingerprints = new java.util.ArrayList<>();
                java.util.Map<Path, Long> modHashes = new java.util.HashMap<>();
                for (Path mod : modFiles) {
                    long hash = computeMurmur2(mod);
                    fingerprints.add(hash);
                    modHashes.put(mod, hash);
                }
                java.util.Map<Long, JsonObject> lookup = cfClient.fingerprintLookup(fingerprints);
                for (Path mod : modFiles) {
                    long hash = modHashes.get(mod);
                    JsonObject info = lookup.get(hash);
                    if (info != null) {
                        // 在线匹配成功：写入 files 数组，不打包到 overrides
                        long projId = info.get("projectID").getAsLong();
                        long fileId = info.get("fileID").getAsLong();
                        JsonObject f = new JsonObject();
                        f.addProperty("projectID", projId);
                        f.addProperty("fileID", fileId);
                        f.addProperty("required", true);
                        filesArray.add(f);
                        matchedMods.add(mod);
                        matchedCount++;
                    }
                }
                onlineMode = matchedCount > 0;
                if (progress != null) progress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, matchedCount, modFiles.size(),
                        "CurseForge 在线匹配: " + matchedCount + "/" + modFiles.size() + " 个模组"));
            } catch (Throwable e) {
                System.err.println("[ModpackManager] CurseForge fingerprint 查询失败，回退离线模式: "
                        + e.getMessage());
                onlineMode = false;
            }
        }

        // 构建 CurseForge manifest.json
        JsonObject manifest = new JsonObject();
        manifest.addProperty("manifestType", "minecraftModpack");
        manifest.addProperty("manifestVersion", 1);
        manifest.addProperty("name", versionId);
        manifest.addProperty("version", versionId);
        manifest.addProperty("author", author);
        if (onlineMode) {
            manifest.addProperty("author", author + " (CurseForge 在线导出)");
        }

        // minecraft.version + modLoaders
        JsonObject minecraft = new JsonObject();
        // S11: 使用真实 MC 版本而非实例目录名
        minecraft.addProperty("version", gameVersion);
        var modLoaders = new com.google.gson.JsonArray();
        if (!loader.isEmpty()) {
            // CF 格式: "fabric-<ver>" / "forge-<ver>" / "quilt-<ver>" / "neoforge-<ver>"
            String loaderId = loader.toLowerCase();
            if (!loaderVersion.isEmpty()) {
                loaderId = loaderId + "-" + loaderVersion;
            }
            JsonObject ml = new JsonObject();
            ml.addProperty("id", loaderId);
            ml.addProperty("primary", true);
            modLoaders.add(ml);
        }
        minecraft.add("modLoaders", modLoaders);
        manifest.add("minecraft", minecraft);

        // files 数组：在线模式含 projectID/fileID；离线模式留空
        manifest.add("files", filesArray);
        manifest.addProperty("overrides", "overrides");

        // 打包 zip
        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 0,
                "正在打包整合包..."));

        Files.createDirectories(targetPath.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetPath))) {
            // 写入 manifest.json
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // 写入 modlist.html（CF 标准可选文件，列出模组名）
            StringBuilder html = new StringBuilder();
            html.append("<ul>");
            for (int i = 0; i < modFiles.size(); i++) {
                String name = modFiles.get(i).getFileName().toString();
                html.append("<li>").append(name).append("</li>");
                if (progress != null) progress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_CLIENT, i + 1, modFiles.size(),
                        "正在处理模组 (" + (i + 1) + "/" + modFiles.size() + ")..."));
            }
            html.append("</ul>");
            zos.putNextEntry(new ZipEntry("modlist.html"));
            zos.write(html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // 写入 overrides/mods/*.jar
            // 在线模式下，已匹配的 mod 不打包（由导入时从 CurseForge API 下载）；
            // 未匹配的 mod 仍打包到 overrides（避免模组丢失）
            for (Path mod : modFiles) {
                if (matchedMods.contains(mod)) continue;
                String entryName = "overrides/mods/" + mod.getFileName().toString();
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(mod, zos);
                zos.closeEntry();
            }

            // 写入 overrides 中的其他目录
            addOverrideDir(zos, gameDir, "config");
            addOverrideDir(zos, gameDir, "resourcepacks");
            addOverrideDir(zos, gameDir, "shaderpacks");
            addOverrideFile(zos, gameDir, "options.txt");
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DONE, 0, 0,
                "CurseForge 整合包已导出: " + targetPath));
    }

    private void addOverrideDir(ZipOutputStream zos, Path gameDir, String dirName) throws IOException {
        Path dir = gameDir.resolve(dirName);
        if (!Files.isDirectory(dir)) return;
        // M88 / H26: Files.walk 深度上限 + 跳过符号链接
        try (var stream = Files.walk(dir, 32)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                // 跳过符号链接：避免链接到 gameDir 外部造成 zip 内容泄漏或循环
                if (Files.isSymbolicLink(p)) continue;
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = gameDir.relativize(p).toString().replace('\\', '/');
                String entryName = "overrides/" + relative;
                // ZIP SLIP 防护
                if (!entryName.startsWith("overrides/")) continue;
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(p, zos);
                zos.closeEntry();
            }
        }
    }

    private void addOverrideFile(ZipOutputStream zos, Path gameDir, String fileName) throws IOException {
        Path file = gameDir.resolve(fileName);
        if (!Files.isRegularFile(file)) return;
        zos.putNextEntry(new ZipEntry("overrides/" + fileName));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    // ===== CurseForge Murmur2 哈希 =====

    /**
     * 计算 mod 文件的 Murmur2_32 哈希（CurseForge fingerprint 算法）。
     * <p>
     * CurseForge 使用变体 Murmur2：读取文件字节，UTF-8 解码后计算 Murmur2_32。
     * 算法参考：<a href="https://minecraft.wiki/w/CurseForge_fingerprint">Minecraft Wiki</a>
     */
    /** H24: 流式计算 Murmur2，避免整 jar 读入内存。 */
    static long computeMurmur2(Path file) throws IOException {
        long fileSize = Files.size(file);
        if (fileSize > Integer.MAX_VALUE) {
            throw new IOException("file too large for Murmur2: " + file);
        }
        int length = (int) fileSize;
        int h = length != 0 ? length : 0;
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            byte[] buf = new byte[8192];
            byte[] hold = new byte[4];
            int holdLen = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                int off = 0;
                if (holdLen > 0) {
                    while (holdLen < 4 && off < n) {
                        hold[holdLen++] = buf[off++];
                    }
                    if (holdLen == 4) {
                        int k = (hold[0] & 0xFF)
                                | ((hold[1] & 0xFF) << 8)
                                | ((hold[2] & 0xFF) << 16)
                                | ((hold[3] & 0xFF) << 24);
                        k *= 0x5bd1e995;
                        k ^= (k >>> 24);
                        k *= 0x5bd1e995;
                        h *= 0x5bd1e995;
                        h ^= k;
                        holdLen = 0;
                    }
                }
                while (off + 4 <= n) {
                    int k = (buf[off] & 0xFF)
                            | ((buf[off + 1] & 0xFF) << 8)
                            | ((buf[off + 2] & 0xFF) << 16)
                            | ((buf[off + 3] & 0xFF) << 24);
                    k *= 0x5bd1e995;
                    k ^= (k >>> 24);
                    k *= 0x5bd1e995;
                    h *= 0x5bd1e995;
                    h ^= k;
                    off += 4;
                }
                while (off < n) {
                    hold[holdLen++] = buf[off++];
                }
            }
            switch (holdLen) {
                case 3:
                    h ^= (hold[2] & 0xFF) << 16;
                case 2:
                    h ^= (hold[1] & 0xFF) << 8;
                case 1:
                    h ^= (hold[0] & 0xFF);
                    h *= 0x5bd1e995;
            }
        }
        h ^= (h >>> 13);
        h *= 0x5bd1e995;
        h ^= (h >>> 15);
        return h & 0xFFFFFFFFL;
    }

    /**
     * CurseForge 使用的 Murmur2_32 变体实现。
     * <p>
     * 与标准 Murmur2 区别：输入为字节数组，按小端 uint32 读取处理。
     */
    static long murmur2(byte[] data) {
        // CurseForge Murmur2 实现（参考 OpenEye/Glyph 工具）
        int length = data.length;
        int h = (length & 0xFFFFFFFF) != 0 ? length : 0;
        int i = 0;

        while (length >= 4) {
            int k = (data[i] & 0xFF)
                    | ((data[i + 1] & 0xFF) << 8)
                    | ((data[i + 2] & 0xFF) << 16)
                    | ((data[i + 3] & 0xFF) << 24);
            k *= 0x5bd1e995;
            k ^= (k >>> 24);
            k *= 0x5bd1e995;

            h *= 0x5bd1e995;
            h ^= k;

            i += 4;
            length -= 4;
        }

        switch (length) {
            case 3:
                h ^= (data[i + 2] & 0xFF) << 16;
            case 2:
                h ^= (data[i + 1] & 0xFF) << 8;
            case 1:
                h ^= (data[i] & 0xFF);
                h *= 0x5bd1e995;
        }

        h ^= (h >>> 13);
        h *= 0x5bd1e995;
        h ^= (h >>> 15);

        // 转为无符号 long
        return h & 0xFFFFFFFFL;
    }

    // ===== MultiMC 格式导出 =====

    /**
     * 导出 MultiMC 格式整合包（mmc-pack.json + instance.cfg）。
     * <p>
     * MultiMC 格式特点：
     * - instance.cfg: 实例配置（键值对，类似 INI）
     * - mmc-pack.json: 组件清单（components 数组，含 MC 版本和加载器）
     * - .minecraft/: 实际游戏文件（mods/configs 等）
     */
    public CompletableFuture<Void> exportMultiMC(String versionId, Path targetPath,
                                                  Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                doExportMultiMC(versionId, targetPath, onProgress);
            } catch (Throwable e) {
                if (onProgress != null) {
                    onProgress.accept(new InstallProgress(InstallProgress.Stage.FAILED, 0, 0,
                            "MultiMC 整合包导出失败: " + e.getMessage()));
                }
                throw new RuntimeException("MultiMC 整合包导出失败", e);
            }
        });
    }

    private void doExportMultiMC(String versionId, Path targetPath,
                                  Consumer<InstallProgress> progress) throws Exception {
        Path gameDir = resolveGameDir(versionId);
        if (!Files.isDirectory(gameDir)) {
            throw new IOException("版本目录不存在: " + gameDir);
        }

        // 读取 modpack.json 元数据
        String loader = "";
        String loaderVersion = "";
        String gameVersion = versionId;
        Path modpackJson = gameDir.resolve("modpack.json");
        if (Files.isRegularFile(modpackJson)) {
            try {
                JsonObject info = JsonParser.parseString(Files.readString(modpackJson,
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                loader = safeStr(info, "loader", "");
                loaderVersion = safeStr(info, "loaderVersion", "");
                String gv = safeStr(info, "gameVersion", "");
                if (!gv.isEmpty()) gameVersion = gv;
            } catch (Exception ignored) {}
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 0,
                "正在打包 MultiMC 整合包..."));

        Files.createDirectories(targetPath.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetPath))) {
            // instance.cfg（INI 格式）
            StringBuilder cfg = new StringBuilder();
            cfg.append("InstanceType=OneSix\n");
            cfg.append("name=").append(versionId).append("\n");
            cfg.append("ManagedPack=false\n");
            cfg.append("lastLaunchTime=0\n");
            zos.putNextEntry(new ZipEntry("instance.cfg"));
            zos.write(cfg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // mmc-pack.json
            JsonObject pack = new JsonObject();
            pack.addProperty("formatVersion", 1);
            com.google.gson.JsonArray components = new com.google.gson.JsonArray();

            // Minecraft 组件
            JsonObject mc = new JsonObject();
            mc.addProperty("uid", "net.minecraft");
            mc.addProperty("version", gameVersion);
            mc.addProperty("important", true);
            components.add(mc);

            // 加载器组件
            if (!loader.isEmpty()) {
                JsonObject ld = new JsonObject();
                String uid = switch (loader.toLowerCase()) {
                    case "fabric" -> "net.fabricmc.fabric-loader";
                    case "forge" -> "net.minecraftforge";
                    case "quilt" -> "org.quiltmc.quilt-loader";
                    case "neoforge" -> "net.neoforged";
                    default -> loader;
                };
                ld.addProperty("uid", uid);
                if (!loaderVersion.isEmpty()) {
                    ld.addProperty("version", loaderVersion);
                }
                ld.addProperty("important", true);
                components.add(ld);
            }
            pack.add("components", components);
            zos.putNextEntry(new ZipEntry("mmc-pack.json"));
            zos.write(pack.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // .minecraft/mods/*.jar
            Path modsDir = gameDir.resolve("mods");
            if (Files.isDirectory(modsDir)) {
                try (var stream = Files.list(modsDir)) {
                    stream.filter(p -> p.toString().toLowerCase().endsWith(".jar")
                            && !p.toString().endsWith(".disabled"))
                            .forEach(p -> {
                                try {
                                    String entryName = ".minecraft/mods/" + p.getFileName();
                                    zos.putNextEntry(new ZipEntry(entryName));
                                    Files.copy(p, zos);
                                    zos.closeEntry();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                }
            }

            // 其他 override 目录
            addMmcOverrideDir(zos, gameDir, "config");
            addMmcOverrideDir(zos, gameDir, "resourcepacks");
            addMmcOverrideDir(zos, gameDir, "shaderpacks");
            addMmcOverrideFile(zos, gameDir, "options.txt");
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DONE, 0, 0,
                "MultiMC 整合包已导出: " + targetPath));
    }

    private void addMmcOverrideDir(ZipOutputStream zos, Path gameDir, String dirName) throws IOException {
        Path dir = gameDir.resolve(dirName);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        // H26: 深度上限 + 不跟随符号链接（与 addOverrideDir / InstanceExporter 一致）
        try (var stream = Files.walk(dir, 32)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path f = it.next();
                if (Files.isSymbolicLink(f)) continue;
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) continue;
                String rel = dir.getParent().relativize(f).toString().replace('\\', '/');
                String entryName = ".minecraft/" + rel;
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
    }

    private void addMmcOverrideFile(ZipOutputStream zos, Path gameDir, String fileName) throws IOException {
        Path file = gameDir.resolve(fileName);
        if (!Files.isRegularFile(file)) return;
        zos.putNextEntry(new ZipEntry(".minecraft/" + fileName));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    // ===== 纯 zip/服务器包导出 =====

    /**
     * 导出纯 zip/服务器包（无 manifest，直接打包完整游戏目录）。
     * <p>
     * 适用于：
     * - 服务器分发给玩家客户端文件
     * - 纯备份用途
     * - 不需要整合包标准格式的场景
     * <p>
     * 打包内容：mods/configs/resourcepacks/shaderpacks/options.txt + 可选 world 目录。
     */
    public CompletableFuture<Void> exportServerPack(String versionId, Path targetPath,
                                                     boolean includeWorld,
                                                     Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                doExportServerPack(versionId, targetPath, includeWorld, onProgress);
            } catch (Throwable e) {
                if (onProgress != null) {
                    onProgress.accept(new InstallProgress(InstallProgress.Stage.FAILED, 0, 0,
                            "服务器包导出失败: " + e.getMessage()));
                }
                throw new RuntimeException("服务器包导出失败", e);
            }
        });
    }

    private void doExportServerPack(String versionId, Path targetPath,
                                     boolean includeWorld,
                                     Consumer<InstallProgress> progress) throws Exception {
        Path gameDir = resolveGameDir(versionId);
        if (!Files.isDirectory(gameDir)) {
            throw new IOException("版本目录不存在: " + gameDir);
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 0,
                "正在打包服务器包..."));

        Files.createDirectories(targetPath.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetPath))) {
            // 打包 mods/
            addServerPackDir(zos, gameDir, "mods");
            addServerPackDir(zos, gameDir, "config");
            addServerPackDir(zos, gameDir, "resourcepacks");
            addServerPackDir(zos, gameDir, "shaderpacks");
            addServerPackFile(zos, gameDir, "options.txt");
            // 可选：打包存档
            if (includeWorld) {
                addServerPackDir(zos, gameDir, "saves");
                addServerPackDir(zos, gameDir, "world");
            }
            // 打包 modpack.json（如果有，用于版本信息）
            addServerPackFile(zos, gameDir, "modpack.json");
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DONE, 0, 0,
                "服务器包已导出: " + targetPath));
    }

    private void addServerPackDir(ZipOutputStream zos, Path gameDir, String dirName) throws IOException {
        Path dir = gameDir.resolve(dirName);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        // H26: 深度上限 + 不跟随符号链接
        try (var stream = Files.walk(dir, 32)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path f = it.next();
                if (Files.isSymbolicLink(f)) continue;
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) continue;
                String rel = dir.getParent().relativize(f).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
    }

    private void addServerPackFile(ZipOutputStream zos, Path gameDir, String fileName) throws IOException {
        Path file = gameDir.resolve(fileName);
        if (!Files.isRegularFile(file)) return;
        zos.putNextEntry(new ZipEntry(fileName));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    // ===== PMCL 私有格式 .lsl3 导出 =====

    /**
     * 导出 PMCL 私有格式 .lsl3。
     * <p>
     * .lsl3 格式设计：
     * - pmcl.json: 元数据（版本信息、加载器、PMCL 版本、导出时间）
     * - mods.json: mod 清单（文件名 + SHA1 + 大小，用于增量更新校验）
     * - files/: 所有游戏文件（mods/configs/resourcepacks 等）
     * <p>
     * 优势：含完整 SHA1 校验，支持增量更新；比 CF 格式更紧凑（无 projectID 依赖）。
     */
    public CompletableFuture<Void> exportLsl3(String versionId, Path targetPath,
                                               Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                doExportLsl3(versionId, targetPath, onProgress);
            } catch (Throwable e) {
                if (onProgress != null) {
                    onProgress.accept(new InstallProgress(InstallProgress.Stage.FAILED, 0, 0,
                            "LSL3 整合包导出失败: " + e.getMessage()));
                }
                throw new RuntimeException("LSL3 整合包导出失败", e);
            }
        });
    }

    private void doExportLsl3(String versionId, Path targetPath,
                              Consumer<InstallProgress> progress) throws Exception {
        Path gameDir = resolveGameDir(versionId);
        if (!Files.isDirectory(gameDir)) {
            throw new IOException("版本目录不存在: " + gameDir);
        }

        // 读取 modpack.json 元数据
        String loader = "";
        String loaderVersion = "";
        String gameVersion = versionId;
        String author = "PMCL";
        Path modpackJson = gameDir.resolve("modpack.json");
        if (Files.isRegularFile(modpackJson)) {
            try {
                JsonObject info = JsonParser.parseString(Files.readString(modpackJson,
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                loader = safeStr(info, "loader", "");
                loaderVersion = safeStr(info, "loaderVersion", "");
                String gv = safeStr(info, "gameVersion", "");
                if (!gv.isEmpty()) gameVersion = gv;
                author = safeStr(info, "author", "PMCL");
            } catch (Exception ignored) {}
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0,
                "正在收集模组信息..."));

        // 收集 mods 列表并计算 SHA1
        Path modsDir = gameDir.resolve("mods");
        com.google.gson.JsonArray modsList = new com.google.gson.JsonArray();
        if (Files.isDirectory(modsDir)) {
            try (var stream = Files.list(modsDir)) {
                java.util.List<Path> modFiles = new java.util.ArrayList<>();
                stream.filter(p -> p.toString().toLowerCase().endsWith(".jar")
                        && !p.toString().endsWith(".disabled"))
                        .forEach(modFiles::add);
                int idx = 0;
                for (Path mod : modFiles) {
                    String sha1 = sha1Hex(mod);
                    long size = Files.size(mod);
                    JsonObject entry = new JsonObject();
                    entry.addProperty("file", "mods/" + mod.getFileName());
                    entry.addProperty("sha1", sha1);
                    entry.addProperty("size", size);
                    modsList.add(entry);
                    idx++;
                    if (progress != null) progress.accept(new InstallProgress(
                            InstallProgress.Stage.DOWNLOAD_CLIENT, idx, modFiles.size(),
                            "正在计算模组哈希 (" + idx + "/" + modFiles.size() + ")..."));
                }
            }
        }

        // 构建 pmcl.json 元数据
        JsonObject pmclMeta = new JsonObject();
        pmclMeta.addProperty("format", "lsl3");
        pmclMeta.addProperty("formatVersion", 1);
        pmclMeta.addProperty("name", versionId);
        pmclMeta.addProperty("gameVersion", gameVersion);
        pmclMeta.addProperty("loader", loader);
        pmclMeta.addProperty("loaderVersion", loaderVersion);
        pmclMeta.addProperty("author", author);
        pmclMeta.addProperty("pmclVersion", "1.0.0");
        pmclMeta.addProperty("exportTime", java.time.Instant.now().toString());
        pmclMeta.add("mods", modsList);

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 0,
                "正在打包 LSL3 整合包..."));

        Files.createDirectories(targetPath.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetPath))) {
            // 写入 pmcl.json
            zos.putNextEntry(new ZipEntry("pmcl.json"));
            zos.write(pmclMeta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // 写入 files/mods/*.jar
            if (Files.isDirectory(modsDir)) {
                try (var stream = Files.list(modsDir)) {
                    stream.filter(p -> p.toString().toLowerCase().endsWith(".jar")
                            && !p.toString().endsWith(".disabled"))
                            .forEach(p -> {
                                try {
                                    zos.putNextEntry(new ZipEntry("files/mods/" + p.getFileName()));
                                    Files.copy(p, zos);
                                    zos.closeEntry();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                }
            }

            // 其他 override 目录
            addLsl3OverrideDir(zos, gameDir, "config");
            addLsl3OverrideDir(zos, gameDir, "resourcepacks");
            addLsl3OverrideDir(zos, gameDir, "shaderpacks");
            addLsl3OverrideFile(zos, gameDir, "options.txt");
        }

        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DONE, 0, 0,
                "LSL3 整合包已导出: " + targetPath));
    }

    private void addLsl3OverrideDir(ZipOutputStream zos, Path gameDir, String dirName) throws IOException {
        Path dir = gameDir.resolve(dirName);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        // H26: 深度上限 + 不跟随符号链接
        try (var stream = Files.walk(dir, 32)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path f = it.next();
                if (Files.isSymbolicLink(f)) continue;
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) continue;
                String rel = dir.getParent().relativize(f).toString().replace('\\', '/');
                String entryName = "files/" + rel;
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
    }

    private void addLsl3OverrideFile(ZipOutputStream zos, Path gameDir, String fileName) throws IOException {
        Path file = gameDir.resolve(fileName);
        if (!Files.isRegularFile(file)) return;
        zos.putNextEntry(new ZipEntry("files/" + fileName));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    /** 计算 SHA-1 十六进制哈希 */
    private static String sha1Hex(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-1");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("SHA-1 不可用", e);
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        }
    }

    /** 解析版本目录（版本隔离或共享） */
    private Path resolveGameDir(String versionId) {
        if (preferences.isVersionIsolation()) {
            return config.getWorkDir().resolve("instances").resolve(versionId);
        }
        return config.getWorkDir();
    }

    // ===== 列出已安装整合包 =====

    public List<InstalledModpack> listInstalledModpacks() {
        List<InstalledModpack> result = new ArrayList<>();

        // 1. PMCL 工作目录的 instances
        Path instancesDir = config.getWorkDir().resolve("instances");
        scanInstances(instancesDir, "PMCL", result);

        // 2. 外部启动器（系统所有 Minecraft 根目录的 instances）+ 各版本目录直接检查
        for (Path versionsDir : com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
            if (!Files.isDirectory(versionsDir)) continue;
            Path mcRoot = versionsDir.getParent();
            // 2a. 检查外部启动器的 instances 目录
            if (mcRoot != null) {
                Path externalInstances = mcRoot.resolve("instances");
                // 跳过 PMCL 自身（避免重复扫描）
                if (!externalInstances.equals(instancesDir)) {
                    scanInstances(externalInstances, "外部", result);
                }
            }
            // 2b. 每个版本目录直接检查 modpack.json（source = 版本 ID）
            try (var stream = Files.list(versionsDir)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    Path versionDir = it.next();
                    if (!Files.isDirectory(versionDir)) continue;
                    InstalledModpack mp = parseInstance(versionDir,
                            versionDir.getFileName().toString());
                    if (mp != null) result.add(mp);
                }
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    /** 扫描指定 instances 目录下的所有整合包实例 */
    private void scanInstances(Path instancesDir, String source, List<InstalledModpack> result) {
        if (!Files.isDirectory(instancesDir)) return;
        try (var stream = Files.list(instancesDir)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path dir = it.next();
                if (!Files.isDirectory(dir)) continue;
                InstalledModpack mp = parseInstance(dir, source);
                if (mp != null) result.add(mp);
            }
        } catch (IOException ignored) {
        }
    }

    /** 解析单个实例目录的 modpack.json，失败返回 null */
    private InstalledModpack parseInstance(Path dir, String source) {
        Path infoFile = dir.resolve("modpack.json");
        if (!Files.exists(infoFile)) return null;
        try {
            String json = Files.readString(infoFile,
                    java.nio.charset.StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String name = safeStr(o, "name", dir.getFileName().toString());
            String gameVersion = safeStr(o, "gameVersion", "");
            String loader = safeStr(o, "loader", "");
            String loaderVersion = safeStr(o, "loaderVersion", "");

            long modCount = 0;
            Path modsDir = dir.resolve("mods");
            if (Files.isDirectory(modsDir)) {
                try (var s = Files.list(modsDir)) {
                    modCount = s.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")).count();
                }
            }
            return new InstalledModpack(name, gameVersion, loader,
                    loaderVersion, dir, modCount, source);
        } catch (Throwable ignored) {
            // 跳过损坏的实例
            return null;
        }
    }

    // ===== 删除整合包实例 =====

    public void deleteModpack(String name) throws IOException {
        if (name == null || name.contains("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new IOException("非法整合包名称: " + name);
        }
        Path instancesRoot = config.getWorkDir().resolve("instances").toAbsolutePath().normalize();
        Path dir = instancesRoot.resolve(name).normalize();
        if (!dir.startsWith(instancesRoot)) {
            throw new IOException("路径越界: " + name);
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException("整合包实例不存在: " + name);
        }
        deleteRecursive(dir);
    }

    private void deleteRecursive(Path path) throws IOException {
        // M87: 符号链接循环防护。直接对符号链接调用 Files.isDirectory 会跟随，
        // 可能进入循环导致 StackOverflowError；对符号链接本身只删除链接不递归。
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    deleteRecursive(it.next());
                }
            }
        }
        Files.deleteIfExists(path);
    }

    // ===== 更新检查 =====

    /**
     * 检查已安装整合包的 mod 是否有更新。
     * <p>
     * 流程：
     * <ol>
     *   <li>读取实例目录下的 source.json（导入时保存的原始 manifest）</li>
     *   <li>提取每个 mod 的 SHA1 哈希</li>
     *   <li>调用 Modrinth API {@code POST /version_files} 批量查询当前版本</li>
     *   <li>对每个 mod 的 project_id 调用 {@code GET /project/{id}/version} 获取最新版本</li>
     *   <li>对比 version_id，有差异的加入更新列表</li>
     * </ol>
     * 仅对 Modrinth 格式的整合包有效（CF 格式需要 CF API，FTB 格式 mods 已打包无哈希）。
     *
     * @param instanceName 实例名称（目录名）
     * @return 更新检查结果
     */
    public CompletableFuture<ModpackUpdateResult> checkForUpdates(String instanceName) {
        return CompletableFuture.supplyAsync(() -> {
            // H19: 拒绝 path traversal；与 InstanceManager / deleteModpack 一致
            if (instanceName == null || instanceName.isBlank()
                    || instanceName.contains("..") || instanceName.contains("/")
                    || instanceName.contains("\\") || instanceName.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("illegal instanceName: " + instanceName);
            }
            Path instancesRoot = config.getWorkDir().resolve("instances").toAbsolutePath().normalize();
            Path instanceDir = instancesRoot.resolve(instanceName).normalize();
            if (!instanceDir.startsWith(instancesRoot)) {
                throw new IllegalArgumentException("instance path escapes instances dir: " + instanceName);
            }
            Path sourceFile = instanceDir.resolve("source.json");
            if (!Files.isRegularFile(sourceFile)) {
                return new ModpackUpdateResult(instanceName, new ArrayList<>(), 0,
                        "缺少 source.json，无法检查更新（仅 Modrinth 格式支持）");
            }
            try {
                JsonObject source = JsonParser.parseString(Files.readString(sourceFile,
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                String gameVersion = safeStr(source, "gameVersion", "");
                String loader = safeStr(source, "loader", "");

                if (!source.has("files") || !source.get("files").isJsonArray()) {
                    return new ModpackUpdateResult(instanceName, new ArrayList<>(), 0, null);
                }

                JsonArray filesArr = source.getAsJsonArray("files");
                if (filesArr.isEmpty()) {
                    return new ModpackUpdateResult(instanceName, new ArrayList<>(), 0, null);
                }

                // 收集有 SHA1 哈希的 mod 文件
                List<String> hashes = new ArrayList<>();
                java.util.Map<String, String> hashToFile = new java.util.LinkedHashMap<>();
                for (JsonElement e : filesArr) {
                    JsonObject fo = e.getAsJsonObject();
                    String path = safeStr(fo, "path", "");
                    String hash = safeStr(fo, "hash", "");
                    if (!hash.isEmpty() && !path.isEmpty()) {
                        hashes.add(hash);
                        hashToFile.put(hash, path);
                    }
                }

                if (hashes.isEmpty()) {
                    return new ModpackUpdateResult(instanceName, new ArrayList<>(), 0, null);
                }

                // 批量查询当前哈希对应的版本信息
                com.pmcl.core.market.ModrinthClient modrinth = modMarketManager != null
                        ? modMarketManager.getModrinthClient() : null;
                if (modrinth == null) {
                    return new ModpackUpdateResult(instanceName, new ArrayList<>(), 0,
                            "Modrinth 客户端不可用");
                }
                java.util.Map<String, JsonObject> currentVersions = modrinth.batchCheckBySha1(hashes);

                // 收集需要查询最新版本的 project_id（去重）
                // hash -> { projectId, currentVersionId, currentVersionNumber, fileName }
                java.util.Map<String, String> hashToProjectId = new java.util.HashMap<>();
                java.util.Set<String> projectIds = new java.util.LinkedHashSet<>();
                java.util.Map<String, String> hashToCurrentVersionId = new java.util.HashMap<>();
                java.util.Map<String, String> hashToCurrentVersionNumber = new java.util.HashMap<>();

                for (String hash : hashes) {
                    JsonObject verInfo = currentVersions.get(hash);
                    if (verInfo == null) continue;
                    String pid = safeStr(verInfo, "project_id", "");
                    String vid = safeStr(verInfo, "id", "");
                    String vnum = safeStr(verInfo, "version_number", "");
                    if (!pid.isEmpty()) {
                        hashToProjectId.put(hash, pid);
                        hashToCurrentVersionId.put(hash, vid);
                        hashToCurrentVersionNumber.put(hash, vnum);
                        projectIds.add(pid);
                    }
                }

                // 查询每个 project 的最新版本（并行化，避免 100-200 个 mod 串行 HTTP 请求）
                List<ModUpdate> updates = new ArrayList<>();
                int checkedCount = hashToProjectId.size();

                ExecutorService pool = Executors.newFixedThreadPool(
                        Math.min(8, Math.max(2, hashToProjectId.size())));
                try {
                    ConcurrentHashMap<String, ModUpdate> resultMap = new ConcurrentHashMap<>();
                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    for (String hash : hashToProjectId.keySet()) {
                        final String pid = hashToProjectId.get(hash);
                        final String currentVid = hashToCurrentVersionId.get(hash);
                        final String currentVnum = hashToCurrentVersionNumber.get(hash);
                        String fileName = hashToFile.get(hash);
                        if (fileName.startsWith("mods/")) {
                            fileName = fileName.substring("mods/".length());
                        }
                        final String fn = fileName;
                        futures.add(CompletableFuture.runAsync(() -> {
                            try {
                                JsonObject latest = modrinth.getLatestVersion(pid, gameVersion, loader);
                                if (latest == null) return;

                                String latestVid = safeStr(latest, "id", "");
                                String latestVnum = safeStr(latest, "version_number", "");

                                // 对比 version_id，不同则有更新
                                if (!latestVid.isEmpty() && !latestVid.equals(currentVid)) {
                                    // 提取下载 URL
                                    String downloadUrl = "";
                                    if (latest.has("files") && latest.get("files").isJsonArray()) {
                                        for (JsonElement fe : latest.getAsJsonArray("files")) {
                                            JsonObject fobj = fe.getAsJsonObject();
                                            boolean primary = !fobj.has("primary") || fobj.get("primary").getAsBoolean();
                                            if (primary) {
                                                downloadUrl = safeStr(fobj, "url", "");
                                                break;
                                            }
                                        }
                                        // 如果没有 primary 文件，取第一个
                                        if (downloadUrl.isEmpty() && latest.getAsJsonArray("files").size() > 0) {
                                            downloadUrl = safeStr(latest.getAsJsonArray("files").get(0).getAsJsonObject(), "url", "");
                                        }
                                    }

                                    resultMap.put(hash, new ModUpdate(fn, currentVnum, latestVnum,
                                            pid, downloadUrl, loader));
                                }
                            } catch (Exception e) {
                                // 单个 mod 查询失败不中断整体检查
                                System.err.println("[ModpackManager] 查询 " + pid + " 最新版本失败: " + e.getMessage());
                            }
                        }, pool));
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    // 按原 hashToProjectId.keySet() 顺序收集结果，保持顺序稳定
                    for (String hash : hashToProjectId.keySet()) {
                        ModUpdate mu = resultMap.get(hash);
                        if (mu != null) updates.add(mu);
                    }
                } finally {
                    pool.shutdown();
                }

                return new ModpackUpdateResult(instanceName, updates, checkedCount, null);
            } catch (Exception e) {
                return new ModpackUpdateResult(instanceName, new ArrayList<>(), 0,
                        "检查更新失败: " + e.getMessage());
            }
        });
    }

    // ===== 内部方法 =====

    private ParsedManifest parseManifest(Path file) throws IOException {
        try (ZipFile zf = new ZipFile(file.toFile())) {
            // 尝试 Modrinth 格式
            ZipEntry modrinthEntry = zf.getEntry("modrinth.index.json");
            if (modrinthEntry != null) {
                return parseModrinthManifest(zf, modrinthEntry);
            }
            // 尝试 CurseForge 格式
            ZipEntry cfEntry = zf.getEntry("manifest.json");
            if (cfEntry != null) {
                return parseCurseForgeManifest(zf, cfEntry);
            }
            // 尝试 PMCL LSL3 格式（pmcl.json）
            ZipEntry lsl3Entry = zf.getEntry("pmcl.json");
            if (lsl3Entry != null) {
                return parseLsl3Manifest(zf, lsl3Entry);
            }
            // 尝试 MultiMC 格式（mmc-pack.json + instance.cfg）
            ZipEntry mmcEntry = zf.getEntry("mmc-pack.json");
            if (mmcEntry != null) {
                return parseMultiMCManifest(zf, mmcEntry);
            }
            // 尝试 FTB 格式（modpack.json + minecraft/ 目录）
            ZipEntry ftbEntry = zf.getEntry("modpack.json");
            if (ftbEntry != null) {
                // 确认是 FTB 格式而非其他工具的 modpack.json：检查是否有 minecraft/ 目录
                if (zf.getEntry("minecraft/") != null || zf.getEntry("minecraft/mods/") != null) {
                    return parseFtbManifest(zf, ftbEntry);
                }
                // 即使没有 minecraft/ 前缀，也尝试按 FTB 解析（某些 FTB 包用 overrides/）
                return parseFtbManifest(zf, ftbEntry);
            }
            // 尝试纯 zip/服务器包（无 manifest，检测 mods/ 目录）
            if (zf.getEntry("mods/") != null || zf.stream()
                    .anyMatch(e -> e.getName().startsWith("mods/") && e.getName().endsWith(".jar"))) {
                return parseServerPackManifest(zf);
            }
            throw new IOException("无法识别的整合包格式：缺少 modrinth.index.json、manifest.json、"
                    + "pmcl.json、mmc-pack.json、modpack.json 或 mods/ 目录");
        }
    }

    /** 解析 PMCL LSL3 格式清单 */
    private ParsedManifest parseLsl3Manifest(ZipFile zf, ZipEntry entry) throws IOException {
        String json;
        try (InputStream in = zf.getInputStream(entry)) {
            json = new String(com.pmcl.core.util.SafeZipExtractor.readLimited(in, MAX_MANIFEST_BYTES),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String name = safeStr(root, "name", "未命名 LSL3 整合包");
        String gameVersion = safeStr(root, "gameVersion", "");
        String loader = safeStr(root, "loader", "");
        String loaderVersion = safeStr(root, "loaderVersion", "");
        String author = safeStr(root, "author", "PMCL");

        List<ModpackFile> files = new ArrayList<>();
        if (root.has("mods") && root.get("mods").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("mods")) {
                JsonObject m = e.getAsJsonObject();
                String filePath = safeStr(m, "file", "");
                String sha1 = safeStr(m, "sha1", "");
                long size = m.has("size") ? m.get("size").getAsLong() : 0L;
                if (!filePath.isEmpty()) {
                    // LSL3 文件在 files/ 前缀下
                    files.add(new ModpackFile(
                            "files/" + filePath, sha1, size,
                            "", null, null));
                }
            }
        }

        return new ParsedManifest(name, gameVersion, loader, loaderVersion,
                "lsl3", files, author);
    }

    /** 解析 MultiMC 格式清单（mmc-pack.json + instance.cfg） */
    private ParsedManifest parseMultiMCManifest(ZipFile zf, ZipEntry entry) throws IOException {
        String json;
        try (InputStream in = zf.getInputStream(entry)) {
            json = new String(com.pmcl.core.util.SafeZipExtractor.readLimited(in, MAX_MANIFEST_BYTES),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String gameVersion = "";
        String loader = "";
        String loaderVersion = "";

        if (root.has("components") && root.get("components").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("components")) {
                JsonObject c = e.getAsJsonObject();
                String uid = safeStr(c, "uid", "");
                String ver = safeStr(c, "version", "");
                if ("net.minecraft".equals(uid)) {
                    gameVersion = ver;
                } else if ("net.fabricmc.fabric-loader".equals(uid)) {
                    loader = "fabric";
                    loaderVersion = ver;
                } else if ("net.minecraftforge".equals(uid)) {
                    loader = "forge";
                    loaderVersion = ver;
                } else if ("org.quiltmc.quilt-loader".equals(uid)) {
                    loader = "quilt";
                    loaderVersion = ver;
                } else if ("net.neoforged".equals(uid)) {
                    loader = "neoforge";
                    loaderVersion = ver;
                }
            }
        }

        // 从 instance.cfg 读取实例名
        String name = "MultiMC 整合包";
        ZipEntry cfgEntry = zf.getEntry("instance.cfg");
        if (cfgEntry != null) {
            try (InputStream in = zf.getInputStream(cfgEntry)) {
                String cfg = new String(com.pmcl.core.util.SafeZipExtractor.readLimited(in, MAX_MANIFEST_BYTES),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String line : cfg.split("\n")) {
                    if (line.startsWith("name=")) {
                        name = line.substring(5).trim();
                        break;
                    }
                }
            }
        }

        // 收集 .minecraft/mods/*.jar 作为文件列表
        List<ModpackFile> files = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String entryName = e.getName();
            if (entryName.startsWith(".minecraft/mods/") && entryName.endsWith(".jar")) {
                String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
                files.add(new ModpackFile("mods/" + fileName, "", 0L, "", null, null));
            }
        }

        return new ParsedManifest(name, gameVersion, loader, loaderVersion,
                "multimc", files, "MultiMC");
    }

    /** 解析纯 zip/服务器包（无 manifest，直接扫描 mods/ 目录） */
    private ParsedManifest parseServerPackManifest(ZipFile zf) throws IOException {
        List<ModpackFile> files = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();
            if (name.startsWith("mods/") && name.endsWith(".jar") && !e.isDirectory()) {
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                files.add(new ModpackFile("mods/" + fileName, "", 0L, "", null, null));
            }
        }
        // 服务器包无版本信息，需要用户手动指定
        return new ParsedManifest("服务器包", "", "", "",
                "serverpack", files, "Server");
    }

    private static final long MAX_MANIFEST_BYTES = 8L * 1024 * 1024;

    private ParsedManifest parseModrinthManifest(ZipFile zf, ZipEntry entry) throws IOException {
        String json;
        try (InputStream in = zf.getInputStream(entry)) {
            json = new String(com.pmcl.core.util.SafeZipExtractor.readLimited(in, MAX_MANIFEST_BYTES),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String name = safeStr(root, "name", "未命名整合包");
        String versionId = safeStr(root, "versionId", "");

        JsonObject deps = root.has("dependencies") && !root.get("dependencies").isJsonNull()
                ? root.getAsJsonObject("dependencies") : new JsonObject();

        String gameVersion = safeStr(deps, "minecraft", "");
        String loader = null;
        String loaderVersion = null;

        if (deps.has("fabric-loader") && !deps.get("fabric-loader").isJsonNull()) {
            loader = "fabric";
            loaderVersion = deps.get("fabric-loader").getAsString();
        } else if (deps.has("quilt-loader") && !deps.get("quilt-loader").isJsonNull()) {
            loader = "quilt";
            loaderVersion = deps.get("quilt-loader").getAsString();
        } else if (deps.has("forge") && !deps.get("forge").isJsonNull()) {
            loader = "forge";
            loaderVersion = deps.get("forge").getAsString();
        } else if (deps.has("neoforge") && !deps.get("neoforge").isJsonNull()) {
            loader = "neoforge";
            loaderVersion = deps.get("neoforge").getAsString();
        }

        List<ModpackFile> files = new ArrayList<>();
        if (root.has("files") && root.get("files").isJsonArray()) {
            for (var e : root.getAsJsonArray("files")) {
                JsonObject f = e.getAsJsonObject();
                String path = safeStr(f, "path", "");
                if (path.isEmpty()) continue;
                String hash = "";
                if (f.has("hashes") && f.get("hashes").isJsonObject()) {
                    JsonObject h = f.getAsJsonObject("hashes");
                    hash = safeStr(h, "sha1", "");
                }
                long size = f.has("size") && !f.get("size").isJsonNull()
                        ? f.get("size").getAsLong() : 0;
                String downloadUrl = "";
                if (f.has("downloads") && f.get("downloads").isJsonArray()
                        && f.getAsJsonArray("downloads").size() > 0) {
                    downloadUrl = f.getAsJsonArray("downloads").get(0).getAsString();
                }
                files.add(new ModpackFile(path, hash, size, downloadUrl, null, null));
            }
        }

        return new ParsedManifest(name, gameVersion, loader, loaderVersion,
                "modrinth", files, null);
    }

    private ParsedManifest parseCurseForgeManifest(ZipFile zf, ZipEntry entry) throws IOException {
        String json;
        try (InputStream in = zf.getInputStream(entry)) {
            json = new String(com.pmcl.core.util.SafeZipExtractor.readLimited(in, MAX_MANIFEST_BYTES),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String name = safeStr(root, "name", "未命名整合包");
        String author = safeStr(root, "author", "");

        JsonObject minecraft = root.has("minecraft") && !root.get("minecraft").isJsonNull()
                ? root.getAsJsonObject("minecraft") : new JsonObject();

        String gameVersion = safeStr(minecraft, "version", "");

        String loader = null;
        String loaderVersion = null;
        if (minecraft.has("modLoaders") && minecraft.get("modLoaders").isJsonArray()) {
            for (var ml : minecraft.getAsJsonArray("modLoaders")) {
                JsonObject mlObj = ml.getAsJsonObject();
                String id = safeStr(mlObj, "id", "");
                if (id.startsWith("fabric-")) {
                    loader = "fabric";
                    loaderVersion = id.substring("fabric-".length());
                    break;
                } else if (id.startsWith("forge-")) {
                    loader = "forge";
                    loaderVersion = id.substring("forge-".length());
                    break;
                } else if (id.startsWith("quilt-")) {
                    loader = "quilt";
                    loaderVersion = id.substring("quilt-".length());
                    break;
                } else if (id.startsWith("neoforge-")) {
                    loader = "neoforge";
                    loaderVersion = id.substring("neoforge-".length());
                    break;
                }
            }
        }

        List<ModpackFile> files = new ArrayList<>();
        if (root.has("files") && root.get("files").isJsonArray()) {
            for (var f : root.getAsJsonArray("files")) {
                JsonObject fObj = f.getAsJsonObject();
                String projectId = fObj.has("projectID") && !fObj.get("projectID").isJsonNull()
                        ? fObj.get("projectID").getAsString() : "";
                String fileId = fObj.has("fileID") && !fObj.get("fileID").isJsonNull()
                        ? fObj.get("fileID").getAsString() : "";
                // CurseForge manifest 不含下载 URL；URL 在安装阶段由 resolveCurseForgeUrls() 补全
                files.add(new ModpackFile(
                        "mods/" + projectId + "_" + fileId + ".jar",
                        "", 0, "", projectId, fileId));
            }
        }

        return new ParsedManifest(name, gameVersion, loader, loaderVersion,
                "curseforge", files, author);
    }

    /**
     * 解析 FTB 整合包清单（modpack.json）。
     * <p>
     * FTB 格式有两种常见变体：
     * <ul>
     *   <li>扁平结构：顶层字段 minecraftVersion / modLoader / modLoaderVersion</li>
     *   <li>嵌套结构：minecraft.version / minecraft.modLoaders[].id（与 CF 类似）</li>
     * </ul>
     * 内容目录前缀为 {@code minecraft/}（而非 overrides/），但也可能使用 overrides/。
     * FTB 包通常将 mods 直接打包在 minecraft/mods/ 中，无需通过 API 下载。
     */
    private ParsedManifest parseFtbManifest(ZipFile zf, ZipEntry entry) throws IOException {
        String json;
        try (InputStream in = zf.getInputStream(entry)) {
            json = new String(com.pmcl.core.util.SafeZipExtractor.readLimited(in, MAX_MANIFEST_BYTES),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String name = safeStr(root, "name", "未命名整合包");
        String author = safeStr(root, "author", "");
        String gameVersion;
        String loader = null;
        String loaderVersion = null;

        // 变体1：嵌套 minecraft 对象（与 CF 类似）
        if (root.has("minecraft") && root.get("minecraft").isJsonObject()) {
            JsonObject mc = root.getAsJsonObject("minecraft");
            gameVersion = safeStr(mc, "version", "");
            if (mc.has("modLoaders") && mc.get("modLoaders").isJsonArray()) {
                for (var ml : mc.getAsJsonArray("modLoaders")) {
                    JsonObject mlObj = ml.getAsJsonObject();
                    String id = safeStr(mlObj, "id", "");
                    if (id.startsWith("fabric-")) {
                        loader = "fabric";
                        loaderVersion = id.substring("fabric-".length());
                        break;
                    } else if (id.startsWith("forge-")) {
                        loader = "forge";
                        loaderVersion = id.substring("forge-".length());
                        break;
                    } else if (id.startsWith("quilt-")) {
                        loader = "quilt";
                        loaderVersion = id.substring("quilt-".length());
                        break;
                    } else if (id.startsWith("neoforge-")) {
                        loader = "neoforge";
                        loaderVersion = id.substring("neoforge-".length());
                        break;
                    }
                }
            }
        } else {
            // 变体2：扁平字段
            gameVersion = safeStr(root, "minecraftVersion", safeStr(root, "version", ""));
            String ml = safeStr(root, "modLoader", safeStr(root, "loader", ""));
            String mlv = safeStr(root, "modLoaderVersion", safeStr(root, "loaderVersion", ""));
            if (!ml.isEmpty()) {
                loader = ml.toLowerCase();
                loaderVersion = mlv;
            }
        }

        // FTB 包通常将 mods 直接打包在 minecraft/mods/ 中，files 数组为空
        // mods 通过 extractOverrides 从 minecraft/mods/ 解压到实例目录
        List<ModpackFile> files = new ArrayList<>();

        return new ParsedManifest(name, gameVersion, loader, loaderVersion,
                "ftb", files, author);
    }

    private void extractOverrides(Path file, Path instanceDir, String format) throws IOException {
        // modrinth/curseforge 用 "overrides/" 前缀
        // FTB 用 "minecraft/" 前缀，但某些 FTB 包也可能用 "overrides/"，所以两者都尝试
        // MultiMC 用 ".minecraft/" 前缀
        // LSL3 用 "files/" 前缀
        // serverpack 无前缀，直接是 mods/ config/ 等
        List<String> prefixes;
        if (format.equals("ftb")) {
            prefixes = List.of("minecraft/", "overrides/");
        } else if (format.equals("multimc")) {
            prefixes = List.of(".minecraft/");
        } else if (format.equals("lsl3")) {
            prefixes = List.of("files/");
        } else if (format.equals("serverpack")) {
            prefixes = List.of("");  // 无前缀，直接解压到根目录
        } else {
            prefixes = List.of("overrides/");
        }

        // S22 安全修复：ZipBomb 防护阈值（含单 entry + 压缩比）
        final long MAX_TOTAL = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_TOTAL_SIZE;
        final long MAX_ENTRY = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_ENTRY_SIZE;
        final int MAX_ENTRIES = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_ENTRIES;
        final int MAX_RATIO = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_RATIO;
        long totalSize = 0;
        int entryCount = 0;

        try (ZipFile zf = new ZipFile(file.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++entryCount > MAX_ENTRIES) {
                    throw new IOException("ZipBomb detected: entry count exceeds limit " + MAX_ENTRIES);
                }
                if (entry.isDirectory()) continue;
                String name = entry.getName();

                String relative = null;
                for (String prefix : prefixes) {
                    if (name.startsWith(prefix)) {
                        relative = name.substring(prefix.length());
                        break;
                    }
                }
                if (relative == null || relative.isEmpty()) continue;

                // H27: ZipSlip 失败即中止（与 InstanceImporter 一致，禁止静默跳过）
                if (relative.contains("..") || relative.startsWith("/") || relative.startsWith("\\")
                        || relative.matches("^[A-Za-z]:[\\\\/].*")) {
                    throw new IOException("ZipSlip: overrides 包含非法路径条目: " + name);
                }
                Path instanceDirAbs = instanceDir.toAbsolutePath().normalize();
                Path target = instanceDirAbs.resolve(relative).normalize();
                if (!target.startsWith(instanceDirAbs)) {
                    throw new IOException("ZipSlip: overrides 路径越界: " + name);
                }

                Files.createDirectories(target.getParent());
                long compressed = entry.getCompressedSize();
                try (InputStream in = zf.getInputStream(entry)) {
                    long entrySize = com.pmcl.core.util.SafeZipExtractor.copyLimited(in, target, MAX_ENTRY);
                    totalSize += entrySize;
                    if (totalSize > MAX_TOTAL) {
                        throw new IOException("ZipBomb detected: total extracted size exceeds "
                                + MAX_TOTAL + " bytes in " + file);
                    }
                    if (compressed > 0 && entrySize > compressed * (long) MAX_RATIO) {
                        try { Files.deleteIfExists(target); } catch (IOException ignored) {}
                        throw new IOException("ZipBomb detected: compression ratio exceeds "
                                + MAX_RATIO + ":1 for " + name);
                    }
                }
            }
        }
    }

    private void saveInstanceInfo(Path instanceDir, ParsedManifest manifest) throws IOException {
        JsonObject info = new JsonObject();
        info.addProperty("name", manifest.name);
        info.addProperty("gameVersion", manifest.gameVersion);
        info.addProperty("loader", manifest.loader != null ? manifest.loader : "");
        info.addProperty("loaderVersion", manifest.loaderVersion != null ? manifest.loaderVersion : "");
        info.addProperty("format", manifest.format);
        if (manifest.author != null) {
            info.addProperty("author", manifest.author);
        }
        info.addProperty("installedAt", System.currentTimeMillis());

        Files.writeString(instanceDir.resolve("modpack.json"),
                info.toString(), java.nio.charset.StandardCharsets.UTF_8);

        // 保存完整 source manifest（含 files 数组及 SHA1 哈希），用于更新检查
        JsonObject source = info.deepCopy();
        JsonArray filesArr = new JsonArray();
        for (ModpackFile mf : manifest.files) {
            JsonObject fo = new JsonObject();
            fo.addProperty("path", mf.path);
            fo.addProperty("hash", mf.hash != null ? mf.hash : "");
            fo.addProperty("size", mf.size);
            fo.addProperty("downloadUrl", mf.downloadUrl != null ? mf.downloadUrl : "");
            if (mf.projectId != null) fo.addProperty("projectId", mf.projectId);
            if (mf.fileId != null) fo.addProperty("fileId", mf.fileId);
            filesArr.add(fo);
        }
        source.add("files", filesArr);
        Files.writeString(instanceDir.resolve("source.json"),
                source.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private ModLoader parseLoader(String loader) {
        switch (loader.toLowerCase()) {
            case "fabric": return ModLoader.FABRIC;
            case "forge": return ModLoader.FORGE;
            case "quilt": return ModLoader.QUILT;
            case "neoforge": return ModLoader.NEOFORGE;
            default: return null;
        }
    }

    private String sanitizeName(String name) {
        if (name == null) return "unnamed";
        // M91: 仅过滤文件系统非法字符（Windows/Linux/macOS 通用），
        // 保留中文/日文/韩文等 Unicode 字符，避免整合包名 "我的整合包" 变成 "_"。
        // 同时过滤控制字符、路径分隔符、通配符等。
        // 空白字符替换为下划线（整合包名通常不含空格更友好）。
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*'
                    || c == '?' || c == '"' || c == '<' || c == '>'
                    || c == '|' || c < 0x20) {
                sb.append('_');
            } else if (c == ' ' || c == '\t') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString();
        // 去除尾部点和空格（Windows 不允许文件名以 . 结尾）
        while (result.endsWith(".") || result.endsWith(" ")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty()) return "unnamed";
        return result;
    }

    private String safeStr(JsonObject obj, String key, String def) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsString();
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    /** 内部解析结果容器 */
    private static final class ParsedManifest {
        final String name;
        final String gameVersion;
        final String loader;
        final String loaderVersion;
        final String format;
        final List<ModpackFile> files;
        final String author;

        ParsedManifest(String name, String gameVersion, String loader,
                       String loaderVersion, String format,
                       List<ModpackFile> files, String author) {
            this.name = name;
            this.gameVersion = gameVersion;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
            this.format = format;
            this.files = files;
            this.author = author;
        }
    }
}


