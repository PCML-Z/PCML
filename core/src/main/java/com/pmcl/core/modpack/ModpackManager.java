package com.pmcl.core.modpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionInstaller;
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

    public ModpackManager(LauncherConfig config, DownloadManager downloads,
                          VersionInstaller versionInstaller,
                          ModLoaderManager modLoaderManager,
                          Preferences preferences) {
        this.config = config;
        this.downloads = downloads;
        this.versionInstaller = versionInstaller;
        this.modLoaderManager = modLoaderManager;
        this.preferences = preferences;
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

    /** 已安装的整合包实例 */
    public static final class InstalledModpack {
        public final String name;
        public final String gameVersion;
        public final String loader;
        public final String loaderVersion;
        public final Path instanceDir;
        public final long modCount;

        public InstalledModpack(String name, String gameVersion, String loader,
                                String loaderVersion, Path instanceDir, long modCount) {
            this.name = name;
            this.gameVersion = gameVersion;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
            this.instanceDir = instanceDir;
            this.modCount = modCount;
        }
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
                Path target = instanceDir.resolve(mf.path);
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
        Path instancesDir = config.getWorkDir().resolve("instances");
        if (!Files.isDirectory(instancesDir)) return result;

        try (var stream = Files.list(instancesDir)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path dir = it.next();
                if (!Files.isDirectory(dir)) continue;
                Path infoFile = dir.resolve("modpack.json");
                if (!Files.exists(infoFile)) continue;
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
                    result.add(new InstalledModpack(name, gameVersion, loader,
                            loaderVersion, dir, modCount));
                } catch (Throwable ignored) {
                    // 跳过损坏的实例
                }
            }
        } catch (IOException ignored) {
        }
        return result;
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
            throw new IOException("无法识别的整合包格式：缺少 modrinth.index.json 或 manifest.json");
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

    private void extractOverrides(Path file, Path instanceDir, String format) throws IOException {
        String prefix = format.equals("modrinth") ? "overrides/" : "overrides/";
        try (ZipFile zf = new ZipFile(file.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.startsWith(prefix)) continue;

                String relative = name.substring(prefix.length());
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
