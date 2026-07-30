package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.download.DownloadTask;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.install.VersionStaging;
import com.pmcl.core.launch.JavaRuntimeFinder;
import com.pmcl.core.util.Exceptions;
import com.pmcl.core.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Forge 安装器（含 NeoForge）。
 * <p>
 * 流程：
 *   1) 拉取 BMCLAPI 版本列表
 *   2) 下载 installer.jar
 *   3) 从 installer.jar 提取 install_profile.json 与 version JSON
 *   4) 写入 versions/{id}.staging/
 *   5) 下载 profile + version 声明的 libraries（含 downloads.artifact）
 *   6) 执行 client-side processors（生成 *-client.jar 等）
 *   7) 校验产物后原子提升为正式版本
 */
public final class ForgeInstaller implements ModLoaderInstaller {

    private static final String BMCLAPI_BASE = "https://bmclapi2.bangbang93.com/forge/minecraft/";
    private static final String NEOFORGE_LIST_URL = "https://bmclapi2.bangbang93.com/neoforge/list/";
    private static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";
    private static final String MOJANG_MAVEN = "https://libraries.minecraft.net/";

    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final boolean neoForge;
    private final VersionInstaller versionInstaller;

    public ForgeInstaller(LauncherConfig config, DownloadManager downloads, boolean neoForge) {
        this(config, downloads, neoForge, null);
    }

    public ForgeInstaller(LauncherConfig config, DownloadManager downloads, boolean neoForge,
                          VersionInstaller versionInstaller) {
        this.config = config;
        this.downloads = downloads;
        this.neoForge = neoForge;
        this.versionInstaller = versionInstaller;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = neoForge ? NEOFORGE_LIST_URL + gameVersion : BMCLAPI_BASE + gameVersion;
                String json = downloads.downloadString(url);
                JsonArray arr = parseJsonArray(json, "加载器版本列表 " + url);
                List<ModLoaderVersion> result = new ArrayList<>();
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    if (neoForge) {
                        // NeoForge: 编码 installerPath 或 rawVersion 到 loaderVersion 中
                        String version = o.has("version") && !o.get("version").isJsonNull()
                                ? o.get("version").getAsString() : "";
                        if (version.isEmpty()) continue;
                        String encoded;
                        if (o.has("installerPath") && !o.get("installerPath").isJsonNull()) {
                            // 新格式（1.21+）：直接有 installerPath
                            encoded = version + "|" + o.get("installerPath").getAsString();
                        } else if (o.has("rawVersion") && !o.get("rawVersion").isJsonNull()) {
                            // 旧格式（1.20.x）：用 rawVersion 构造 maven 路径
                            encoded = version + "|" + o.get("rawVersion").getAsString();
                        } else {
                            encoded = version;
                        }
                        result.add(new ModLoaderVersion(ModLoader.NEOFORGE, gameVersion, encoded, true));
                    } else {
                        result.add(new ModLoaderVersion(
                                ModLoader.FORGE,
                                gameVersion,
                                o.has("version") && !o.get("version").isJsonNull()
                                        ? o.get("version").getAsString() : "",
                                !o.has("branch") || o.get("branch").isJsonNull()
                                        || "null".equals(o.get("branch").getAsString())
                        ));
                    }
                }
                return result;
            } catch (Throwable ex) {
                throw new RuntimeException("拉取" + (neoForge ? " NeoForge" : " Forge") + " 版本失败", ex);
            }
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            Path installerJar = null;
            String loaderName = neoForge ? "NeoForge" : "Forge";
            String versionId = null;
            Path stagingDir = null;
            ForgeProcessorRunner runner = null;
            try {
                // 1. 下载 installer.jar
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "下载 " + loaderName + " installer.jar"));
                installerJar = Files.createTempFile("forge-installer-", ".jar");
                downloadInstallerJar(gameVersion, loaderVersion, installerJar, loaderName);

                // 2. 提取 install_profile.json / version JSON
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "解析 install_profile.json"));
                JsonObject profile = extractInstallProfile(installerJar);
                JsonObject versionJson = resolveVersionJson(installerJar, profile);
                versionId = versionJson.has("id") && !versionJson.get("id").isJsonNull()
                        ? versionJson.get("id").getAsString() : "";
                if (versionId.isEmpty()) {
                    throw new IOException(loaderName + " installer 未包含有效的版本 id");
                }

                // 3. 写入 staging（依赖与 processors 就绪后再 promote）
                stagingDir = VersionStaging.writeVersionJson(
                        config.getVersionsDir(), versionId, versionJson.toString());

                // 4. 提取内嵌库 + 收集远端库（profile + version.json，含 downloads.artifact）
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                        "提取/下载 " + loaderName + " 依赖库"));
                java.util.Set<String> embeddedPaths = new java.util.HashSet<>();
                int embedded = extractEmbeddedMaven(installerJar, config.getLibrariesDir(), embeddedPaths);
                List<DownloadTask> remoteLibs = new ArrayList<>();
                collectLibraryDownloads(profile, remoteLibs, embeddedPaths);
                collectLibraryDownloads(versionJson, remoteLibs, embeddedPaths);

                if (!remoteLibs.isEmpty()) {
                    if (onProgress != null) onProgress.accept(new InstallProgress(
                            InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, remoteLibs.size(),
                            "下载 " + loaderName + " 依赖库 (" + remoteLibs.size() + " 个)"));
                    downloadRemoteLibraries(remoteLibs);
                }

                // 5. 执行 client processors（1.13+ 必需；旧版无 processors 则跳过）
                boolean processorsRan = false;
                if (profile.has("processors") && profile.get("processors").isJsonArray()
                        && profile.getAsJsonArray("processors").size() > 0) {
                    String mcVer = profile.has("minecraft") && !profile.get("minecraft").isJsonNull()
                            ? profile.get("minecraft").getAsString() : gameVersion;
                    if (mcVer == null || mcVer.isBlank()) mcVer = gameVersion;
                    ensureParentInstalled(mcVer, onProgress);

                    Path clientJar = config.getVersionsDir()
                            .resolve(gameVersion).resolve(gameVersion + ".jar");
                    if (!Files.isRegularFile(clientJar)) {
                        // 兼容 profile.minecraft 与请求的 gameVersion 不一致
                        clientJar = config.getVersionsDir().resolve(mcVer).resolve(mcVer + ".jar");
                    }
                    if (!Files.isRegularFile(clientJar) || Files.size(clientJar) < 1024) {
                        throw new IOException(loaderName + " processors 需要原版 client.jar，"
                                + "但未找到可用文件（已尝试 " + gameVersion + " / " + mcVer + "）");
                    }
                    String java = JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir());
                    if (java == null || java.isBlank()) {
                        throw new IOException("找不到可用的 Java，无法执行 " + loaderName + " processors");
                    }
                    runner = new ForgeProcessorRunner(
                            config.getWorkDir(), config.getLibrariesDir(), config.getVersionsDir(),
                            installerJar, clientJar, java);
                    runner.runClient(profile, onProgress);
                    processorsRan = true;
                }

                // 6. 校验版本 JSON 库 + processor 产物
                assertVersionLibrariesPresent(versionJson);
                assertProcessorOutputs(profile, loaderName, processorsRan);

                // 7. 原子提升
                VersionStaging.promote(config.getVersionsDir(), versionId, stagingDir);
                stagingDir = null;

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        loaderName + " 安装完成: " + versionId +
                        "（内嵌库 " + embedded + "，远端库 " + remoteLibs.size() + "）"));
            } catch (Exception e) {
                if (!InstallInterruptedException.isInterrupted(e)) {
                    if (versionId != null && !versionId.isBlank()) {
                        VersionStaging.discard(config.getVersionsDir(), versionId);
                    }
                    if (stagingDir != null) {
                        FileUtils.deleteRecursively(stagingDir);
                    }
                }
                String detail = Exceptions.rootMessage(e);
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, detail));
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new InstallInterruptedException(loaderName + " 安装已中断", e);
                }
                throw new RuntimeException(loaderName + " 安装失败: " + detail, e);
            } finally {
                if (runner != null) runner.cleanup();
                if (installerJar != null) {
                    try { Files.deleteIfExists(installerJar); } catch (IOException ignored) {}
                }
            }
        });
    }

    /** 从 installer.jar 读取 install_profile.json */
    private JsonObject extractInstallProfile(Path installerJar) throws IOException {
        try (ZipFile zip = new ZipFile(installerJar.toFile())) {
            // Forge 1.13+ 优先
            ZipEntry entry = zip.getEntry("install_profile.json");
            if (entry == null) entry = zip.getEntry("install_profile");
            if (entry == null) {
                throw new IOException("installer.jar 中找不到 install_profile.json（可能下载了错误的文件）");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return parseJsonObject(json, "install_profile.json");
            }
        }
    }

    /**
     * 解压 installer.jar 中 maven/ 目录下的库到 libraries 目录。
     *
     * @return 内嵌库数量
     */
    private int extractEmbeddedMaven(Path installerJar, Path librariesDir,
                                     java.util.Set<String> embeddedPaths) throws IOException {
        int count = 0;
        final long MAX_TOTAL = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_TOTAL_SIZE;
        final int MAX_ENTRIES = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_ENTRIES;
        long totalSize = 0;
        int entryCount = 0;
        Path librariesAbs = librariesDir.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(installerJar.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++entryCount > MAX_ENTRIES) {
                    throw new IOException("ZipBomb detected: entry count exceeds limit " + MAX_ENTRIES
                            + " in " + installerJar);
                }
                String name = e.getName();
                if (!e.isDirectory() && (name.startsWith("maven/") || name.startsWith("libraries/"))) {
                    String relPath = name.startsWith("maven/") ? name.substring("maven/".length())
                                                                : name.substring("libraries/".length());
                    if (relPath.isEmpty()) continue;
                    if (relPath.contains("..") || relPath.startsWith("/") || relPath.startsWith("\\")) {
                        throw new IOException("ZipSlip: installer maven 路径非法: " + name);
                    }
                    Path target = librariesDir.resolve(relPath).toAbsolutePath().normalize();
                    if (!target.startsWith(librariesAbs)) {
                        throw new IOException("ZipSlip: installer 解压越界: " + name);
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(e);
                         java.io.OutputStream out = Files.newOutputStream(target,
                                 java.nio.file.StandardOpenOption.CREATE,
                                 java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                                 java.nio.file.StandardOpenOption.WRITE)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            totalSize += n;
                            if (totalSize > MAX_TOTAL) {
                                throw new IOException("ZipBomb detected: total extracted size exceeds "
                                        + MAX_TOTAL + " bytes in " + installerJar);
                            }
                            out.write(buf, 0, n);
                        }
                    }
                    embeddedPaths.add(relPath);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 从 profile / version JSON 的 libraries 数组收集下载任务。
     * 优先使用 {@code downloads.artifact}（现代 Forge）；否则按 name + url 拼 Maven 路径。
     */
    private void collectLibraryDownloads(JsonObject root, List<DownloadTask> remoteLibs,
                                         java.util.Set<String> embeddedPaths) {
        if (root == null || !root.has("libraries") || !root.get("libraries").isJsonArray()) return;
        for (JsonElement e : root.getAsJsonArray("libraries")) {
            if (!e.isJsonObject()) continue;
            JsonObject lib = e.getAsJsonObject();
            String path = null;
            String url = null;
            String sha1 = "";
            long size = 0;

            if (lib.has("downloads") && lib.get("downloads").isJsonObject()) {
                JsonObject dl = lib.getAsJsonObject("downloads");
                if (dl.has("artifact") && dl.get("artifact").isJsonObject()) {
                    JsonObject art = dl.getAsJsonObject("artifact");
                    if (art.has("path") && !art.get("path").isJsonNull()) {
                        path = art.get("path").getAsString();
                    }
                    if (art.has("url") && !art.get("url").isJsonNull()) {
                        url = art.get("url").getAsString();
                    }
                    if (art.has("sha1") && !art.get("sha1").isJsonNull()) {
                        sha1 = art.get("sha1").getAsString();
                    }
                    if (art.has("size") && !art.get("size").isJsonNull()) {
                        size = art.get("size").getAsLong();
                    }
                }
            }

            if (path == null || path.isBlank()) {
                if (!lib.has("name") || lib.get("name").isJsonNull()) continue;
                String name = lib.get("name").getAsString();
                // 落盘路径；旧 forge:VER 无 classifier
                path = ForgeMavenCoords.toPath(name);
            }
            if (embeddedPaths.contains(path)) continue;
            // 去重：已在 remoteLibs
            String rel = "libraries/" + path;
            boolean exists = false;
            for (DownloadTask t : remoteLibs) {
                if (rel.equals(t.getRelativePath())) { exists = true; break; }
            }
            if (exists) continue;

            if (url == null || url.isBlank()) {
                String downloadPath = path;
                if (lib.has("name") && !lib.get("name").isJsonNull()) {
                    downloadPath = mavenDownloadPath(lib.get("name").getAsString());
                }
                url = BMCLAPI_MAVEN + downloadPath;
                if (lib.has("url") && !lib.get("url").isJsonNull()) {
                    String base = lib.get("url").getAsString();
                    if (!base.endsWith("/")) base = base + "/";
                    url = base + downloadPath;
                }
            } else if (url.contains("maven.minecraftforge.net/")) {
                // 优先走 BMCLAPI 镜像
                url = url.replace("https://maven.minecraftforge.net/", BMCLAPI_MAVEN)
                        .replace("http://maven.minecraftforge.net/", BMCLAPI_MAVEN);
            } else if (url.contains("maven.neoforged.net/")) {
                url = url.replace("https://maven.neoforged.net/", BMCLAPI_MAVEN)
                        .replace("http://maven.neoforged.net/", BMCLAPI_MAVEN);
            }

            remoteLibs.add(new DownloadTask(url, sha1 == null ? "" : sha1, size, rel));
            embeddedPaths.add(path); // 防止 profile/version 重复
        }
    }

    /** maven 坐标转落盘路径（支持 {@code @ext}）。 */
    private static String mavenToPath(String coords) {
        return ForgeMavenCoords.toPath(coords);
    }

    /**
     * maven 坐标对应的实际下载路径。
     * 旧版 Forge（≤1.12）在 versionInfo 里写 {@code net.minecraftforge:forge:VER}（无 classifier），
     * 但 Maven 上只有 {@code forge-VER-universal.jar}；落盘仍用无 classifier 文件名以便启动。
     */
    private static String mavenDownloadPath(String coords) {
        String c = ForgeMavenCoords.stripBrackets(coords);
        String[] parts = c.split(":");
        if (parts.length == 3
                && "net.minecraftforge".equals(parts[0])
                && "forge".equals(parts[1])
                && !c.contains("@")) {
            String groupPath = parts[0].replace('.', '/');
            String artifact = parts[1];
            String version = parts[2];
            return groupPath + "/" + artifact + "/" + version + "/"
                    + artifact + "-" + version + "-universal.jar";
        }
        return ForgeMavenCoords.toPath(coords);
    }

    private static final String FORGE_MAVEN =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/";

    /**
     * 下载 Forge/NeoForge installer.jar（多源 URL + 多源 SHA-1）。
     * <p>
     * BMCLAPI 的 {@code /forge/minecraft/{mc}/{ver}/jar} 对许多旧构建返回 404，
     * 且该路径通常没有 {@code .sha1} 旁路。优先使用 Maven 布局：
     * {@code .../maven/net/minecraftforge/forge/{mc}-{ver}/forge-{mc}-{ver}-installer.jar}。
     * SHA-1 优先取版本列表里的 installer hash，再回退官方 Maven {@code .sha1}。
     */
    private void downloadInstallerJar(String gameVersion, String loaderVersion,
                                      Path target, String loaderName) throws IOException {
        List<String> urls = buildInstallerUrls(gameVersion, loaderVersion);
        String expectedSha1 = resolveInstallerSha1(gameVersion, loaderVersion, urls);
        if (expectedSha1 == null || expectedSha1.length() < 40) {
            throw new IOException(loaderName + " installer 缺少可校验的 SHA-1（已尝试版本列表与 Maven .sha1）");
        }
        IOException last = null;
        for (String url : urls) {
            try {
                downloads.downloadToVerified(url, target, expectedSha1, null);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException(loaderName + " installer 下载失败，已尝试 "
                + urls.size() + " 个源: " + (last != null ? last.getMessage() : ""), last);
    }

    /** Forge/NeoForge installer 候选下载地址（按优先级）。 */
    private List<String> buildInstallerUrls(String gameVersion, String loaderVersion) {
        List<String> urls = new ArrayList<>();
        if (!neoForge) {
            // loaderVersion 可能已是 "1.12.2-14.23.0.2486" 或仅 "14.23.0.2486"
            String artifact = loaderVersion.startsWith(gameVersion + "-")
                    ? loaderVersion : gameVersion + "-" + loaderVersion;
            urls.add(BMCLAPI_MAVEN + "net/minecraftforge/forge/" + artifact
                    + "/forge-" + artifact + "-installer.jar");
            urls.add(FORGE_MAVEN + artifact + "/forge-" + artifact + "-installer.jar");
            // 旧 BMCLAPI 快捷路径（新版本偶发可用，旧构建常 404）
            urls.add(BMCLAPI_BASE + gameVersion + "/" + loaderVersion + "/jar");
            return urls;
        }
        String[] parts = loaderVersion.split("\\|", 2);
        String version = parts[0];
        if (parts.length == 2) {
            String encoded = parts[1];
            if (encoded.startsWith("/")) {
                urls.add("https://bmclapi2.bangbang93.com" + encoded);
            } else {
                urls.add(BMCLAPI_MAVEN + "net/neoforged/forge/" + encoded
                        + "/forge-" + encoded + "-installer.jar");
            }
        }
        urls.add(BMCLAPI_MAVEN + "net/neoforged/neoforge/" + version
                + "/neoforge-" + version + "-installer.jar");
        return urls;
    }

    /**
     * 解析 installer SHA-1：BMCLAPI 版本列表 files[].hash → 官方 Maven .sha1 → 各候选 URL+.sha1。
     */
    private String resolveInstallerSha1(String gameVersion, String loaderVersion,
                                        List<String> installerUrls) {
        if (!neoForge) {
            String fromList = fetchForgeInstallerHashFromList(gameVersion, loaderVersion);
            if (fromList != null) return fromList;
            String artifact = loaderVersion.startsWith(gameVersion + "-")
                    ? loaderVersion : gameVersion + "-" + loaderVersion;
            String officialSha1 = tryDownloadSha1(FORGE_MAVEN + artifact
                    + "/forge-" + artifact + "-installer.jar.sha1");
            if (officialSha1 != null) return officialSha1;
        }
        for (String url : installerUrls) {
            String s = tryDownloadSha1(url + ".sha1");
            if (s != null) return s;
        }
        return null;
    }

    /** 从 BMCLAPI Forge 版本列表读取 installer 文件的 hash。 */
    private String fetchForgeInstallerHashFromList(String gameVersion, String loaderVersion) {
        try {
            String json = downloads.downloadString(BMCLAPI_BASE + gameVersion);
            JsonArray arr = parseJsonArray(json, "Forge 版本列表 " + gameVersion);
            String want = loaderVersion.startsWith(gameVersion + "-")
                    ? loaderVersion.substring(gameVersion.length() + 1) : loaderVersion;
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String ver = o.has("version") && !o.get("version").isJsonNull()
                        ? o.get("version").getAsString() : "";
                if (!want.equals(ver) && !loaderVersion.equals(ver)) continue;
                if (!o.has("files") || !o.get("files").isJsonArray()) continue;
                for (JsonElement fe : o.getAsJsonArray("files")) {
                    if (!fe.isJsonObject()) continue;
                    JsonObject f = fe.getAsJsonObject();
                    String cat = f.has("category") && !f.get("category").isJsonNull()
                            ? f.get("category").getAsString() : "";
                    String fmt = f.has("format") && !f.get("format").isJsonNull()
                            ? f.get("format").getAsString() : "";
                    if ("installer".equals(cat) && "jar".equals(fmt)
                            && f.has("hash") && !f.get("hash").isJsonNull()) {
                        String hash = f.get("hash").getAsString().trim();
                        if (hash.length() >= 40) return hash;
                    }
                }
            }
        } catch (Exception ignored) {
            // 列表拉取失败时由 Maven .sha1 回退
        }
        return null;
    }

    private String tryDownloadSha1(String sha1Url) {
        try {
            String body = downloads.downloadString(sha1Url).trim();
            String hash = body.split("\\s+")[0];
            return (hash.length() >= 40) ? hash : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 install_profile 解析真正的版本 JSON。
     * <ul>
     *   <li>Forge ≤1.12：{@code versionInfo} 对象</li>
     *   <li>Forge 1.13+：{@code json} 指向 jar 内相对路径</li>
     *   <li>少数：{@code versionJson} 内嵌对象</li>
     * </ul>
     */
    private JsonObject resolveVersionJson(Path installerJar, JsonObject profile) throws IOException {
        if (profile.has("versionInfo") && profile.get("versionInfo").isJsonObject()) {
            return profile.getAsJsonObject("versionInfo");
        }
        if (profile.has("versionJson") && profile.get("versionJson").isJsonObject()) {
            return profile.getAsJsonObject("versionJson");
        }
        if (profile.has("json") && !profile.get("json").isJsonNull()) {
            String rel = profile.get("json").getAsString();
            try (ZipFile zip = new ZipFile(installerJar.toFile())) {
                ZipEntry entry = zip.getEntry(rel.startsWith("/") ? rel.substring(1) : rel);
                if (entry == null) {
                    throw new IOException("installer.jar 中找不到版本 JSON: " + rel);
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    return parseJsonObject(json, rel);
                }
            }
        }
        throw new IOException("install_profile.json 中找不到 versionInfo / json / versionJson");
    }

    /** 下载远端库：多源回退 + 优先 URL+.sha1；已存在文件也做 SHA 校验。 */
    private void downloadRemoteLibraries(List<DownloadTask> remoteLibs) throws IOException {
        for (DownloadTask t : remoteLibs) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InstallInterruptedException(
                        (neoForge ? "NeoForge" : "Forge") + " 依赖库下载已中断");
            }
            Path target = config.getWorkDir().resolve(t.getRelativePath());
            String sha1 = t.getSha1() != null ? t.getSha1() : "";
            if (Files.isRegularFile(target) && Files.size(target) > 32 && looksLikeZip(target)) {
                if (sha1.isBlank()) {
                    sha1 = tryDownloadSha1(t.getUrl() + ".sha1");
                    if (sha1 == null) sha1 = "";
                }
                if (!sha1.isBlank()) {
                    try {
                        if (sha1.equalsIgnoreCase(sha1Hex(target))) {
                            continue;
                        }
                    } catch (IOException ignored) {
                        // 无法读哈希则重新下载
                    }
                    Files.deleteIfExists(target);
                } else {
                    // 无哈希：不信任已有文件，落入下方下载并强制拿到 SHA
                    Files.deleteIfExists(target);
                }
            }
            Files.createDirectories(target.getParent());
            List<String> urls = new ArrayList<>();
            urls.add(t.getUrl());
            // 多源回退：Forge Maven ↔ BMCLAPI；其余库再试 Mojang libraries
            if (t.getUrl().contains("maven.minecraftforge.net/")) {
                String mirrored = t.getUrl().replace(
                        "https://maven.minecraftforge.net/", BMCLAPI_MAVEN);
                if (!urls.contains(mirrored)) urls.add(0, mirrored);
            } else if (t.getUrl().startsWith(BMCLAPI_MAVEN)) {
                // 只有 forge 制品才回退到 maven.minecraftforge.net，避免对其它坐标空耗重试
                if (t.getRelativePath().contains("/net/minecraftforge/forge/")) {
                    String official = t.getUrl().replace(BMCLAPI_MAVEN,
                            "https://maven.minecraftforge.net/");
                    if (!urls.contains(official)) urls.add(official);
                }
                String mojang = t.getUrl().replace(BMCLAPI_MAVEN, MOJANG_MAVEN);
                if (!urls.contains(mojang)) urls.add(mojang);
            }
            Exception last = null;
            boolean ok = false;
            for (String url : urls) {
                try {
                    String useSha = sha1;
                    if (useSha == null || useSha.isBlank()) {
                        useSha = tryDownloadSha1(url + ".sha1");
                    }
                    if (useSha == null || useSha.isBlank()) {
                        throw new IOException("依赖库无 SHA-1（含旁路 .sha1），拒绝下载: "
                                + t.getRelativePath());
                    }
                    downloads.downloadToVerified(url, target, useSha, null);
                    sha1 = useSha;
                    ok = true;
                    break;
                } catch (Exception e) {
                    if (InstallInterruptedException.isInterrupted(e)) {
                        throw e instanceof RuntimeException
                                ? (RuntimeException) e
                                : new InstallInterruptedException(
                                        (neoForge ? "NeoForge" : "Forge") + " 依赖库下载已中断", e);
                    }
                    last = e;
                }
            }
            if (!ok) {
                throw new IOException("依赖库下载失败: " + t.getRelativePath()
                        + (last != null ? (" — " + Exceptions.rootMessage(last)) : ""), last);
            }
        }
    }

    private static String sha1Hex(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    /** 确认版本 JSON 中声明的库文件均存在。 */
    private void assertVersionLibrariesPresent(JsonObject versionJson) throws IOException {
        if (!versionJson.has("libraries") || !versionJson.get("libraries").isJsonArray()) return;
        List<String> missing = new ArrayList<>();
        for (JsonElement e : versionJson.getAsJsonArray("libraries")) {
            if (!e.isJsonObject()) continue;
            JsonObject lib = e.getAsJsonObject();
            if (!lib.has("name") || lib.get("name").isJsonNull()) continue;
            String name = lib.get("name").getAsString();
            Path path = config.getLibrariesDir().resolve(mavenToPath(name));
            Path alt = config.getLibrariesDir().resolve(mavenDownloadPath(name));
            if (!Files.isRegularFile(path) && !Files.isRegularFile(alt)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            String preview = String.join(", ", missing.subList(0, Math.min(5, missing.size())));
            throw new IOException("安装后仍缺少 " + missing.size() + " 个库（示例: " + preview + "）");
        }
    }

    /**
     * 确保原版父版本（client.jar + json）存在；缺失时自动安装。
     * processors 依赖原版 jar，与 Fabric ensureParentInstalled 对齐。
     */
    private void ensureParentInstalled(String parentId, Consumer<InstallProgress> onProgress)
            throws IOException {
        VersionStaging.assertSafeVersionId(parentId);
        Path parentDir = config.getVersionsDir().resolve(parentId);
        Path parentJson = parentDir.resolve(parentId + ".json");
        Path parentJar = parentDir.resolve(parentId + ".jar");
        if (Files.isRegularFile(parentJson) && Files.isRegularFile(parentJar)
                && Files.size(parentJar) > 1024) {
            return;
        }
        if (versionInstaller == null) {
            throw new IOException("缺少原版父版本 " + parentId + "，请先安装 Minecraft " + parentId);
        }
        if (onProgress != null) onProgress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                "安装原版父版本 " + parentId));
        try {
            versionInstaller.install(parentId, onProgress).join();
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable c = ce.getCause() != null ? ce.getCause() : ce;
            if (c instanceof IOException) throw (IOException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new IOException("安装原版父版本失败: " + parentId, c);
        }
    }

    /**
     * 校验 processors 声明的产物路径是否存在。
     * processors 已执行时：缺失 → 明确报生成失败；齐全 → 校验通过日志。
     * 无法解析任何产物路径时仅警告（镜像可能已提供预构建 jar）。
     */
    private void assertProcessorOutputs(JsonObject profile, String loaderName,
                                        boolean processorsRan) throws IOException {
        if (!profile.has("processors") || !profile.get("processors").isJsonArray()) return;
        JsonArray processors = profile.getAsJsonArray("processors");
        if (processors.size() == 0) return;

        JsonObject data = profile.has("data") && profile.get("data").isJsonObject()
                ? profile.getAsJsonObject("data") : null;
        List<String> missing = new ArrayList<>();
        int resolved = 0;

        for (JsonElement pe : processors) {
            if (!pe.isJsonObject()) continue;
            JsonObject proc = pe.getAsJsonObject();
            if (!proc.has("outputs") || !proc.get("outputs").isJsonObject()) continue;
            JsonObject outputs = proc.getAsJsonObject("outputs");
            for (java.util.Map.Entry<String, JsonElement> entry : outputs.entrySet()) {
                String key = entry.getKey();
                Path outPath = resolveProcessorOutputPath(key, data);
                if (outPath == null) continue;
                resolved++;
                if (!Files.isRegularFile(outPath) || Files.size(outPath) < 32) {
                    missing.add(outPath.toString());
                }
            }
        }

        if (!missing.isEmpty()) {
            String preview = String.join(", ", missing.subList(0, Math.min(3, missing.size())));
            if (processorsRan) {
                throw new IOException(loaderName + " processors 执行后产物仍缺失: "
                        + preview + "。请重试安装或改用官方 installer / HMCL。");
            }
            throw new IOException(loaderName + " installer 需要 processors 产物，但以下文件缺失: "
                    + preview + "。请先确保原版游戏已安装，或换用已预构建版本。");
        }
        if (resolved == 0) {
            System.err.println("[ForgeInstaller] " + loaderName
                    + " install_profile 含 processors，但无法解析产物路径；已跳过产物校验");
        } else if (processorsRan) {
            System.err.println("[ForgeInstaller] " + loaderName
                    + " processors 产物校验通过（" + resolved + " 项）");
        } else {
            System.err.println("[ForgeInstaller] " + loaderName
                    + " processors 产物已就绪（解析 " + resolved + " 项）");
        }
    }

    /**
     * 将 processor output 键解析为库路径。
     * 支持 {@code {VAR}}（查 data.VAR.client）、{@code [g:a:v]} / {@code [g:a:v:c]}、
     * 以及相对 libraries 的路径。
     */
    private Path resolveProcessorOutputPath(String key, JsonObject data) {
        if (key == null || key.isBlank()) return null;
        String token = ForgeMavenCoords.stripQuotes(key.trim());
        // {VAR} → data.VAR.client
        if (token.startsWith("{") && token.endsWith("}") && data != null) {
            String var = token.substring(1, token.length() - 1);
            if (!data.has(var) || !data.get(var).isJsonObject()) return null;
            JsonObject side = data.getAsJsonObject(var);
            String client = side.has("client") && !side.get("client").isJsonNull()
                    ? side.get("client").getAsString() : null;
            if (client == null || client.isBlank()) return null;
            token = ForgeMavenCoords.stripQuotes(client.trim());
        }
        if (token.startsWith("[") && token.endsWith("]")) {
            return config.getLibrariesDir().resolve(ForgeMavenCoords.toPath(token));
        }
        if (token.startsWith("libraries/")) {
            return config.getWorkDir().resolve(token);
        }
        if (token.endsWith(".jar") || token.endsWith(".lzma") || token.contains("/")) {
            String rel = token.startsWith("/") ? token.substring(1) : token;
            return config.getLibrariesDir().resolve(rel);
        }
        return null;
    }

    private static boolean looksLikeZip(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            // PK\x03\x04 or PK\x05\x06 (empty) or PK\x07\x08
            return magic.length >= 2 && magic[0] == 'P' && magic[1] == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    /** 解析 JSON 数组，非 JSON 响应给出有意义的错误信息 */
    private static JsonArray parseJsonArray(String json, String context) throws IOException {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.isEmpty()) {
            throw new IOException("服务器返回空响应: " + context);
        }
        char first = trimmed.charAt(0);
        if (first != '[' && first != '{') {
            String preview = trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            throw new IOException("服务器返回非 JSON 内容（可能为错误页面）: " + context + "\n响应内容: " + preview);
        }
        try {
            return JsonParser.parseString(trimmed).getAsJsonArray();
        } catch (Exception e) {
            String preview = trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            throw new IOException("JSON 解析失败: " + context + "\n错误: " + e.getMessage() + "\n响应内容: " + preview);
        }
    }

    /** 解析 JSON 对象，非 JSON 响应给出有意义的错误信息 */
    private static JsonObject parseJsonObject(String json, String context) throws IOException {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.isEmpty()) {
            throw new IOException("服务器返回空响应: " + context);
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            String preview = trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            throw new IOException("服务器返回非 JSON 内容（可能为错误页面）: " + context + "\n响应内容: " + preview);
        }
        try {
            return JsonParser.parseString(trimmed).getAsJsonObject();
        } catch (Exception e) {
            String preview = trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            throw new IOException("JSON 解析失败: " + context + "\n错误: " + e.getMessage() + "\n响应内容: " + preview);
        }
    }
}
