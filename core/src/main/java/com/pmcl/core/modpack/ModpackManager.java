package com.pmcl.core.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.modloader.ModLoader;
import com.pmcl.core.modloader.ModLoaderManager;
import com.pmcl.core.preferences.Preferences;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

        // 4. 下载 mods
        if (progress != null) progress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_ASSETS, 0, manifest.files.size(),
                "正在下载模组 (0/" + manifest.files.size() + ")..."));

        int[] completed = {0};
        for (ModpackFile mf : manifest.files) {
            if (mf.downloadUrl != null && !mf.downloadUrl.isEmpty()) {
                Path target = instanceDir.resolve(mf.path).normalize();
                if (!target.startsWith(instanceDir)) {
                    System.err.println("[ModpackManager] 跳过非法路径: " + mf.path);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try {
                    downloads.downloadTo(mf.downloadUrl, target);
                } catch (Exception e) {
                    // 单个 mod 下载失败不中断整体导入
                    System.err.println("[ModpackManager] 模组下载失败: " + mf.path + " - " + e.getMessage());
                }
            }
            completed[0]++;
            if (progress != null) progress.accept(new InstallProgress(
                    InstallProgress.Stage.DOWNLOAD_ASSETS, completed[0], manifest.files.size(),
                    "正在下载模组 (" + completed[0] + "/" + manifest.files.size() + ")..."));
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
            stream.filter(p -> p.toString().endsWith(".jar")
                    && !p.toString().endsWith(".disabled"))
                    .forEach(modFiles::add);
        }

        // 生成 modrinth.index.json
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", versionId);
        index.addProperty("name", versionId);

        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", versionId);
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
        Path modpackJson = gameDir.resolve("modpack.json");
        if (Files.isRegularFile(modpackJson)) {
            try {
                JsonObject info = JsonParser.parseString(Files.readString(modpackJson)).getAsJsonObject();
                if (info.has("loader")) loader = safeStr(info, "loader", "");
                if (info.has("loaderVersion")) loaderVersion = safeStr(info, "loaderVersion", "");
                if (info.has("author")) author = safeStr(info, "author", "PMCL");
            } catch (Exception ignored) {
            }
        }

        // 收集 mods 列表
        List<Path> modFiles = new ArrayList<>();
        try (var stream = Files.list(modsDir)) {
            stream.filter(p -> p.toString().endsWith(".jar")
                    && !p.toString().endsWith(".disabled"))
                    .forEach(modFiles::add);
        }

        // 构建 CurseForge manifest.json
        JsonObject manifest = new JsonObject();
        manifest.addProperty("manifestType", "minecraftModpack");
        manifest.addProperty("manifestVersion", 1);
        manifest.addProperty("name", versionId);
        manifest.addProperty("version", versionId);
        manifest.addProperty("author", author);

        // minecraft.version + modLoaders
        JsonObject minecraft = new JsonObject();
        minecraft.addProperty("version", versionId);
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

        // files 数组留空（离线导出无 projectID/fileID）
        manifest.add("files", new com.google.gson.JsonArray());
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
            for (Path mod : modFiles) {
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
        try (var stream = Files.walk(dir)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (Files.isDirectory(p)) continue;
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
                    modCount = s.filter(p -> p.toString().endsWith(".jar")).count();
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
        Path dir = config.getWorkDir().resolve("instances").resolve(name);
        if (!Files.isDirectory(dir)) {
            throw new IOException("整合包实例不存在: " + name);
        }
        deleteRecursive(dir);
    }

    private void deleteRecursive(Path path) throws IOException {
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
            Path instanceDir = config.getWorkDir().resolve("instances").resolve(instanceName);
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

                // 查询每个 project 的最新版本
                List<ModUpdate> updates = new ArrayList<>();
                int checkedCount = hashToProjectId.size();

                for (String hash : hashToProjectId.keySet()) {
                    String pid = hashToProjectId.get(hash);
                    String currentVid = hashToCurrentVersionId.get(hash);
                    String currentVnum = hashToCurrentVersionNumber.get(hash);
                    String fileName = hashToFile.get(hash);
                    if (fileName.startsWith("mods/")) {
                        fileName = fileName.substring("mods/".length());
                    }

                    try {
                        JsonObject latest = modrinth.getLatestVersion(pid, gameVersion, loader);
                        if (latest == null) continue;

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

                            updates.add(new ModUpdate(fileName, currentVnum, latestVnum,
                                    pid, downloadUrl, loader));
                        }
                    } catch (Exception e) {
                        // 单个 mod 查询失败不中断整体检查
                        System.err.println("[ModpackManager] 查询 " + pid + " 最新版本失败: " + e.getMessage());
                    }
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
            throw new IOException("无法识别的整合包格式：缺少 modrinth.index.json、manifest.json 或 modpack.json");
        }
    }

    private ParsedManifest parseModrinthManifest(ZipFile zf, ZipEntry entry) throws IOException {
        String json;
        try (InputStream in = zf.getInputStream(entry)) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
                // CurseForge manifest 不包含下载 URL，需要通过 API 查询
                // 这里先占位，实际下载时需要查询 CurseForge API
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
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
        List<String> prefixes;
        if (format.equals("ftb")) {
            prefixes = List.of("minecraft/", "overrides/");
        } else {
            prefixes = List.of("overrides/");
        }
        try (ZipFile zf = new ZipFile(file.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
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

                // ZIP SLIP 防护
                Path target = instanceDir.resolve(relative).normalize();
                if (!target.startsWith(instanceDir)) continue;

                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
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
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String sha1Hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-1");
            } catch (Exception e) {
                return "";
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
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
