package com.pmcl.core.install;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.download.DownloadTask;
import com.pmcl.core.util.Exceptions;
import com.pmcl.core.util.FileUtils;
import com.pmcl.core.version.McVersion;
import com.pmcl.core.version.VersionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 版本安装器：拉取版本 JSON → 解析 → 下载 client.jar + libraries + assets。
 * <p>
 * 支持继承版本（inheritsFrom）：会自动合并父版本信息。
 * <p>
 * 版本私有文件（json / client.jar / natives）写入 {@code versions/{id}.staging/}，
 * 全部成功后再原子提升为 {@code versions/{id}/}，避免半成品被扫描为可启动版本。
 * libraries / assets 仍写入共享目录（带 SHA 与 .part 续传）。
 */
public final class VersionInstaller {

    private static final String RESOURCE_BASE = "https://resources.download.minecraft.net/";
    private static final String LIBRARY_BASE = "https://libraries.minecraft.net/";
    private static final String ASSET_INDEX_BASE = "https://piston-meta.mojang.com/";
    /** 安装暂存目录后缀 */
    static final String STAGING_SUFFIX = VersionStaging.STAGING_SUFFIX;
    static final String BAK_SUFFIX = VersionStaging.BAK_SUFFIX;

    private final LauncherConfig config;
    private final VersionManager versionManager;
    private final DownloadManager downloadManager;

    public VersionInstaller(LauncherConfig config,
                            VersionManager versionManager,
                            DownloadManager downloadManager) {
        this.config = config;
        this.versionManager = versionManager;
        this.downloadManager = downloadManager;
    }

    /**
     * 安装指定版本。
     *
     * @param versionId 要安装的版本 id（如 "1.20.4"）
     * @param onProgress 进度回调
     */
    public CompletableFuture<Void> install(String versionId,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            Path stagingDir = config.getVersionsDir().resolve(versionId + STAGING_SUFFIX);
            try {
                VersionStaging.assertSafeVersionId(versionId);
                doInstall(versionId, onProgress);
            } catch (Throwable e) {
                if (InstallInterruptedException.isInterrupted(e)) {
                    // 暂停/取消：保留 staging 与 .part，供断点续传
                    throw e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new InstallInterruptedException("安装已中断", e);
                }
                FileUtils.deleteRecursively(stagingDir);
                String detail = Exceptions.rootMessage(e);
                if (onProgress != null)
                    onProgress.accept(new InstallProgress(
                            InstallProgress.Stage.FAILED, 0, 0, detail));
                throw new RuntimeException("安装失败: " + versionId + " — " + detail, e);
            }
        });
    }

    private void doInstall(String versionId, Consumer<InstallProgress> onProgress) throws IOException {
        // 1. 找到版本元信息
        McVersion target = findVersion(versionId);

        String stagingName = versionId + STAGING_SUFFIX;
        Path stagingDir = config.getVersionsDir().resolve(stagingName);
        Files.createDirectories(stagingDir);

        // 2. 下载版本 JSON → staging（最终提升前不可见为正式版本）
        if (onProgress != null) onProgress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1, "下载版本清单"));
        Path versionJsonPath = stagingDir.resolve(versionId + ".json");
        String versionSha1 = target.getSha1();
        if (versionSha1 == null || versionSha1.isBlank()) {
            throw new IOException("版本清单缺少 SHA-1，拒绝下载: " + versionId);
        }
        String versionJsonStr = downloadManager.downloadStringVerified(target.getUrl(), versionSha1);
        Files.writeString(versionJsonPath, versionJsonStr, java.nio.charset.StandardCharsets.UTF_8);
        // P1-5: 持久化版本清单的 SHA-1，供启动时校验本地 JSON 完整性，
        // 防止本地篡改/磁盘损坏导致恶意 library 注入或解析错误。
        Path versionSha1Path = stagingDir.resolve(versionId + ".json.sha1");
        Files.writeString(versionSha1Path, versionSha1, java.nio.charset.StandardCharsets.UTF_8);

        VersionJson vj = VersionJson.parse(versionJsonStr);

        // 处理继承：合并父版本 JSON
        if (vj.getInheritsFrom() != null && !vj.getInheritsFrom().equals(versionId)) {
            vj = mergeInherited(vj, vj.getInheritsFrom());
        }

        List<DownloadTask> tasks = new ArrayList<>();
        // 按相对路径去重：MC 1.12 等旧版本会把同一 jar 列两次（如 text2speech、
        // java-objc-bridge），并发下载会抢写同一 .part 并在重命名时 NoSuchFileException。
        java.util.Set<String> seenPaths = new java.util.HashSet<>();

        // 3. client.jar → staging
        if (vj.getClientArtifact() != null) {
            VersionJson.Artifact c = vj.getClientArtifact();
            addTask(tasks, seenPaths, new DownloadTask(
                    c.getUrl(), c.getSha1(), c.getSize(),
                    "versions/" + stagingName + "/" + versionId + ".jar"));
        }

        // 4. libraries（含 native classifier）→ 共享 libraries/
        if (onProgress != null) onProgress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, vj.getLibraries().size(),
                "扫描依赖库"));
        // P2-2: Apple Silicon 上安装时同时下载 arm64 + x86_64 两套 natives，
        // 确保离线环境下无论用 arm64 Java（新版本）还是 x86_64 Java（老版本 via Rosetta 2）
        // 都有对应架构的 natives 可用，避免首次启动联网补下载。
        boolean appleSilicon = isAppleSilicon();
        boolean loongArch64 = isLoongArch64();
        for (Library lib : vj.getLibraries()) {
            if (!lib.appliesToCurrentOs()) continue;
            // 主 artifact
            if (lib.getArtifact() != null) {
                VersionJson.Artifact a = lib.getArtifact();
                addTask(tasks, seenPaths, new DownloadTask(
                        a.getUrl(), a.getSha1(), a.getSize(),
                        "libraries/" + lib.getPath()));
            }
            // native classifier（按当前 OS + 架构选择）
            if (lib.isNativeLib()) {
                VersionJson.Artifact n = lib.getNativeArtifact();
                if (n != null) {
                    addTask(tasks, seenPaths, new DownloadTask(
                            n.getUrl(), n.getSha1(), n.getSize(),
                            "libraries/" + lib.getPathForClassifier(lib.getNativeClassifier())));
                }
                // P2-2: Apple Silicon 额外下载 x86_64 natives（供 Rosetta 2 模式下的老版本使用）
                if (appleSilicon) {
                    // 1. 获取 arm64 视角的 native classifier（默认架构）
                    String armClassifier = lib.getNativeClassifier();
                    // 2. 切换到 x86_64 视角
                    Library.setArchOverride("x86_64");
                    try {
                        String x86Classifier = lib.getNativeClassifier();
                        // 3. 如果两者不同，额外下载 x86_64 版本
                        if (x86Classifier != null && !x86Classifier.equals(armClassifier)) {
                            VersionJson.Artifact x86Native = lib.getClassifiers().get(x86Classifier);
                            if (x86Native != null) {
                                addTask(tasks, seenPaths, new DownloadTask(
                                        x86Native.getUrl(), x86Native.getSha1(), x86Native.getSize(),
                                        "libraries/" + lib.getPathForClassifier(x86Classifier)));
                            }
                        }
                    } finally {
                        Library.clearArchOverride();
                    }
                }
                // 龙芯 LoongArch64：尝试从社区 maven 源下载 loongarch64 native（仅 LWJGL）。
                // 若社区源无对应版本，下载会失败但不阻塞整体安装（LaunchProfileBuilder 在
                // 启动时会回退到 x86_64 native + LATX 二进制翻译）。
                if (loongArch64) {
                    DownloadTask loongTask = buildLoongArch64NativeTask(lib);
                    if (loongTask != null) {
                        addTask(tasks, seenPaths, loongTask);
                    }
                }
            }
        }

        // 5. 资产索引（声明了 assets 则必须成功下载，禁止静默跳过）
        if (vj.getAssets() != null && !vj.getAssets().isEmpty()) {
            if (onProgress != null) onProgress.accept(new InstallProgress(
                    InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 1, "下载资产索引"));
            String assetIndexUrl = resolveAssetIndexUrl(vj);
            String assetIndexSha1 = resolveAssetIndexSha1(vj);
            if (assetIndexUrl == null || assetIndexUrl.isBlank()) {
                throw new IOException("版本声明了 assets=" + vj.getAssets()
                        + " 但缺少 assetIndex.url，拒绝安装");
            }
            if (assetIndexSha1 == null || assetIndexSha1.isBlank()) {
                throw new IOException("assetIndex 缺少 sha1，拒绝无完整性校验的索引下载");
            }
            Path idxPath = config.getAssetsDir().resolve("indexes").resolve(vj.getAssets() + ".json");
            Files.createDirectories(idxPath.getParent());
            downloadManager.downloadToVerified(assetIndexUrl, idxPath, assetIndexSha1, null);
            String idxJson = Files.readString(idxPath, java.nio.charset.StandardCharsets.UTF_8);

            AssetIndex idx = AssetIndex.parse(idxJson);
            for (AssetIndex.Asset a : idx.getAssets().values()) {
                addTask(tasks, seenPaths, new DownloadTask(
                        RESOURCE_BASE + a.getPath(),
                        a.getHash(), a.getSize(),
                        "assets/objects/" + a.getPath()));
            }
        }

        // 6. 执行批量下载
        final long total = tasks.stream().mapToLong(DownloadTask::getSize).sum();
        // H4: 磁盘空间预检。下载到一半磁盘满会留下大量 .part 残留（共享目录，不在 staging 内），
        // 恶化磁盘空间且重试仍失败。预留 10% 余量，空间不足时直接抛出明确错误。
        checkDiskSpace(config.getWorkDir(), total);
        downloadManager.downloadAll(tasks,
                file -> {},
                bytes -> {
                    if (onProgress != null) {
                        onProgress.accept(new InstallProgress(
                                InstallProgress.Stage.DOWNLOAD_LIBRARIES, bytes, total,
                                String.format("下载中 %d / %d bytes", bytes, total)));
                    }
                }).join();

        // 7. 解压 native 库到 staging/natives
        extractNatives(vj, stagingDir.resolve("natives"));

        // 8. 原子提升 staging → versions/{id}
        VersionStaging.promote(config.getVersionsDir(), versionId, stagingDir);

        if (onProgress != null) onProgress.accept(new InstallProgress(
                InstallProgress.Stage.DONE, total, total,
                "安装完成: " + versionId));
    }

    /**
     * 解压所有 native jar 到指定 natives 目录。
     * 排除 META-INF（避免签名文件冲突）。
     */
    private void extractNatives(VersionJson vj, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        // M73: 预先规范化基目录，作为 ZipSlip 最终路径归属校验基准
        final Path nativesDirAbs = nativesDir.toAbsolutePath().normalize();
        // 清空旧 natives
        try (var stream = Files.list(nativesDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
        for (Library lib : vj.getLibraries()) {
            if (!lib.appliesToCurrentOs() || !lib.isNativeLib()) continue;
            String classifier = lib.getNativeClassifier();
            if (classifier == null) continue;
            Path nativeJar = config.getLibrariesDir().resolve(lib.getPathForClassifier(classifier));
            if (!Files.exists(nativeJar)) {
                throw new IOException("缺少 native 库，无法解压: " + nativeJar);
            }
            // S22 安全修复：ZipBomb 防护
            final long MAX_TOTAL = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_TOTAL_SIZE;
            final int MAX_ENTRIES = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_ENTRIES;
            long totalSize = 0;
            int entryCount = 0;
            int extracted = 0;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(nativeJar.toFile())) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = en.nextElement();
                    if (++entryCount > MAX_ENTRIES) {
                        throw new IOException("ZipBomb detected: entry count exceeds limit " + MAX_ENTRIES
                                + " in " + nativeJar);
                    }
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    // 跳过签名文件与元数据
                    if (name.startsWith("META-INF/")) continue;
                    // ZipSlip：非法路径一律失败，禁止静默跳过导致缺 native
                    if (name.isEmpty()) {
                        throw new IOException("native zip 含空路径条目: " + nativeJar);
                    }
                    if (name.startsWith("/") || name.startsWith("\\")
                            || name.matches("^[A-Za-z]:[\\\\/].*")) {
                        throw new IOException("ZipSlip: native 绝对路径条目 '" + name + "' in " + nativeJar);
                    }
                    boolean hasDotDot = false;
                    for (String seg : name.replace('\\', '/').split("/")) {
                        if ("..".equals(seg)) { hasDotDot = true; break; }
                    }
                    if (hasDotDot) {
                        throw new IOException("ZipSlip: native 路径含 .. '" + name + "' in " + nativeJar);
                    }
                    Path target = nativesDir.resolve(name).toAbsolutePath().normalize();
                    if (!target.startsWith(nativesDirAbs)) {
                        throw new IOException("ZipSlip: native 路径越界 '" + name + "' in " + nativeJar);
                    }
                    Path parent = target.getParent();
                    if (parent == null || !parent.startsWith(nativesDirAbs)) {
                        throw new IOException("ZipSlip: native 父目录越界 '" + name + "' in " + nativeJar);
                    }
                    Files.createDirectories(parent);
                    try (var in = zip.getInputStream(entry);
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
                                        + MAX_TOTAL + " bytes in " + nativeJar);
                            }
                            out.write(buf, 0, n);
                        }
                    }
                    extracted++;
                }
            } catch (java.util.zip.ZipException e) {
                throw new IOException("native 库不是有效 zip，安装中止: " + nativeJar, e);
            }
            if (extracted == 0) {
                throw new IOException("native 库解压结果为空: " + nativeJar);
            }
        }
    }

    /**
     * H4: 磁盘空间预检。
     * 对工作目录所在分区检查可用空间，预留 10% 余量（含 native 解压、日志、索引等额外开销）。
     * 空间不足时抛出明确异常，避免下载到一半磁盘满留下大量 .part 残留恶化空间。
     */
    private static void checkDiskSpace(Path workDir, long requiredBytes) throws IOException {
        if (requiredBytes <= 0) return;
        try {
            java.nio.file.FileStore store = java.nio.file.Files.getFileStore(workDir);
            long usable = store.getUsableSpace();
            if (usable < 0) return; // 某些 FS 无法获取，跳过检查
            // 预留 10% 余量（至少 50MB，应对 native 解压 + 日志 + 索引）
            long requiredWithMargin = (long) (requiredBytes * 1.1) + (50L * 1024 * 1024);
            if (usable < requiredWithMargin) {
                long needMb = requiredWithMargin / (1024 * 1024);
                long haveMb = usable / (1024 * 1024);
                throw new IOException("磁盘空间不足: 需要 " + needMb + " MB（含 10% 余量），"
                        + "可用 " + haveMb + " MB。请清理磁盘后重试。"
                        + "（目标分区: " + store.name() + "）");
            }
        } catch (java.nio.file.FileSystemException e) {
            // 文件系统不支持 getFileStore，跳过预检（不阻塞安装）
            System.err.println("[VersionInstaller] 磁盘空间预检跳过: " + e.getMessage());
        }
    }

    /** P2-2: 检测当前是否为 Apple Silicon（arm64 macOS） */
    private static boolean isAppleSilicon() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        return osName.contains("mac") && (osArch.equals("aarch64") || osArch.equals("arm64"));
    }

    /**
     * 检测当前是否为龙芯 LoongArch64（linux-la64/la464/loongarch64）。
     * 龙芯旧版 MIPS64el（3A 旧型号）不在内，因其无可用 native 源。
     */
    private static boolean isLoongArch64() {
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        return osArch.contains("loongarch64") || osArch.contains("la64") || osArch.contains("la464");
    }

    /**
     * 龙芯 LoongArch64 native 库社区源：Glavo 维护的 loongarch64 LWJGL maven 仓库。
     * 仅对 LWJGL（org.lwjgl:*）相关库有效，其他库无 loongarch64 移植版。
     */
    private static final String LOONGARCH64_NATIVE_MAVEN =
            "https://repo1.maven.org/maven2/";

    /**
     * 龙芯 LoongArch64：为 LWJGL 库构造社区源 loongarch64 native 下载任务。
     * <p>
     * Mojang 版本 JSON 的 classifiers 中无 natives-linux-loongarch64，
     * 但 Maven Central 上 Glavo 等社区维护者发布了部分 LWJGL 3.x 的 loongarch64 移植版。
     * 此处尝试用 maven 坐标构造下载 URL，下载失败由 DownloadManager 跳过（不影响整体安装）。
     *
     * @param lib 当前库（必须 isNativeLib 且 name 以 org.lwjgl: 开头）
     * @return 下载任务，或 null 表示无可用 loongarch64 native
     */
    private DownloadTask buildLoongArch64NativeTask(Library lib) {
        String name = lib.getName();
        if (!name.startsWith("org.lwjgl:")) return null;
        // 解析 maven 坐标：org.lwjgl:lwjgl:3.3.3 → group=org.lwjgl, artifact=lwjgl, version=3.3.3
        String[] parts = name.split(":");
        if (parts.length < 3) return null;
        String group = parts[0];
        String artifactId = parts[1];
        String version = parts[2];
        String classifier = "natives-linux-loongarch64";
        String groupPath = group.replace('.', '/');
        // Maven Central 路径：org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux-loongarch64.jar
        String relPath = groupPath + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + "-" + classifier + ".jar";
        String url = LOONGARCH64_NATIVE_MAVEN + relPath;
        String targetPath = "libraries/" + lib.getPathForClassifier(classifier);
        // SHA-1 可选：Maven Central 提供 .sha1 文件，但此处不强制校验以保持容错
        return new DownloadTask(url, null, 0L, targetPath);
    }

    private McVersion findVersion(String versionId) throws IOException {
        List<McVersion> versions = versionManager.fetchRemoteVersions().join();
        for (McVersion v : versions) {
            if (v.getId().equals(versionId)) return v;
        }
        throw new IOException("版本不存在: " + versionId);
    }

    /** 按相对路径去重后加入下载队列；同路径只保留首次出现的任务。 */
    private static void addTask(List<DownloadTask> tasks, java.util.Set<String> seenPaths,
                                DownloadTask task) {
        if (task.getRelativePath() == null || task.getRelativePath().isEmpty()) return;
        if (seenPaths.add(task.getRelativePath())) {
            tasks.add(task);
        }
    }

    /**
     * 从版本 JSON 的 assetIndex 字段获取下载地址。
     */
    private String resolveAssetIndexUrl(VersionJson vj) {
        JsonObject root = vj.getRawJson();
        if (root.has("assetIndex")) {
            JsonObject ai = root.getAsJsonObject("assetIndex");
            if (ai.has("url")) return ai.get("url").getAsString();
        }
        return null;
    }

    private String resolveAssetIndexSha1(VersionJson vj) {
        JsonObject root = vj.getRawJson();
        if (root.has("assetIndex")) {
            JsonObject ai = root.getAsJsonObject("assetIndex");
            if (ai.has("sha1") && !ai.get("sha1").isJsonNull()) {
                return ai.get("sha1").getAsString();
            }
        }
        return null;
    }

    /**
     * 合并继承版本的 JSON：父版本为主，子版本覆盖 mainClass 等。
     * <p>
     * P2-3: 改为递归合并，带循环检测和深度限制（与 LaunchProfileBuilder.loadVersionJson 一致）。
     * 旧实现只合并一层直接父版本，若父版本自身有 inheritsFrom（多层 Forge 链），
     * 祖父版本的 libraries/client.jar 不会被加入下载任务，安装声称成功但文件不全。
     */
    private VersionJson mergeInherited(VersionJson child, String parentId) throws IOException {
        return mergeInheritedRecursive(child, parentId, new java.util.HashSet<>(), 0);
    }

    private VersionJson mergeInheritedRecursive(VersionJson child, String parentId,
                                                  java.util.Set<String> visiting, int depth) throws IOException {
        if (depth > 16) {
            throw new IOException("版本继承链过深（>" + depth + "）: " + visiting
                    + "，可能存在异常 inheritsFrom 链");
        }
        if (!visiting.add(parentId)) {
            throw new IOException("检测到循环版本继承: " + visiting + " -> " + parentId);
        }
        try {
            List<McVersion> versions = versionManager.fetchRemoteVersions().join();
            McVersion parent = null;
            for (McVersion v : versions) {
                if (v.getId().equals(parentId)) { parent = v; break; }
            }
            if (parent == null) {
                throw new IOException("找不到 inheritsFrom 父版本: " + parentId);
            }
            if (parent.getUrl() == null) {
                throw new IOException("父版本缺少下载 URL: " + parentId);
            }
            String parentSha1 = parent.getSha1();
            if (parentSha1 == null || parentSha1.isBlank()) {
                throw new IOException("父版本清单缺少 SHA-1，拒绝下载: " + parentId);
            }

            String parentJson = downloadManager.downloadStringVerified(parent.getUrl(), parentSha1);
            JsonObject parentObj = JsonParser.parseString(parentJson).getAsJsonObject();

            // P2-3: 递归处理父版本的 inheritsFrom（多层 Forge 链）
            VersionJson parentVj = VersionJson.parse(parentJson);
            if (parentVj.getInheritsFrom() != null && !parentVj.getInheritsFrom().equals(parentId)) {
                parentVj = mergeInheritedRecursive(parentVj, parentVj.getInheritsFrom(), visiting, depth + 1);
                parentObj = parentVj.getRawJson();
            }

            JsonObject childObj = child.getRawJson();

        // 子版本若没有 mainClass/assetIndex，则用父版本
        if (!childObj.has("mainClass") && parentObj.has("mainClass")) {
            childObj.add("mainClass", parentObj.get("mainClass"));
        }
        if (!childObj.has("assets") && parentObj.has("assets")) {
            childObj.add("assets", parentObj.get("assets"));
        }
        if (!childObj.has("assetIndex") && parentObj.has("assetIndex")) {
            childObj.add("assetIndex", parentObj.get("assetIndex"));
        }
        if (!childObj.has("downloads") && parentObj.has("downloads")) {
            childObj.add("downloads", parentObj.get("downloads"));
        }
        // M83: 合并 arguments.game/jvm（与 LaunchProfileBuilder.loadVersionJson 逻辑对齐）
        // 子版本的参数在前，父版本的在后（保证子版本自定义参数优先级）
        if (parentObj.has("arguments")) {
            JsonObject parentArgs = parentObj.getAsJsonObject("arguments");
            if (!childObj.has("arguments")) {
                // 子版本完全没有 arguments，直接用父版本的整体
                childObj.add("arguments", parentArgs);
            } else {
                JsonObject childArgs = childObj.getAsJsonObject("arguments");
                // 合并 game 数组
                if (parentArgs.has("game")) {
                    com.google.gson.JsonArray mergedGame = new com.google.gson.JsonArray();
                    if (childArgs.has("game")) {
                        for (var e : childArgs.getAsJsonArray("game")) mergedGame.add(e);
                    }
                    for (var e : parentArgs.getAsJsonArray("game")) mergedGame.add(e);
                    childArgs.add("game", mergedGame);
                }
                // 合并 jvm 数组
                if (parentArgs.has("jvm")) {
                    com.google.gson.JsonArray mergedJvm = new com.google.gson.JsonArray();
                    if (childArgs.has("jvm")) {
                        for (var e : childArgs.getAsJsonArray("jvm")) mergedJvm.add(e);
                    }
                    for (var e : parentArgs.getAsJsonArray("jvm")) mergedJvm.add(e);
                    childArgs.add("jvm", mergedJvm);
                }
            }
        }
        // 合并旧格式 minecraftArguments（子版本没有时用父版本）
        if (!childObj.has("minecraftArguments") && parentObj.has("minecraftArguments")) {
            childObj.add("minecraftArguments", parentObj.get("minecraftArguments"));
        }
        // 继承 javaVersion（子版本未指定时用父版本）
        if (!childObj.has("javaVersion") && parentObj.has("javaVersion")) {
            childObj.add("javaVersion", parentObj.get("javaVersion"));
        }
        // 合并 libraries（子的覆盖父的同名库）
        if (parentObj.has("libraries")) {
            com.google.gson.JsonArray merged = new com.google.gson.JsonArray();
            java.util.Set<String> childNames = new java.util.HashSet<>();
            if (childObj.has("libraries")) {
                for (var e : childObj.getAsJsonArray("libraries")) {
                    merged.add(e);
                    JsonObject libObj = e.getAsJsonObject();
                    if (libObj.has("name") && !libObj.get("name").isJsonNull()) {
                        childNames.add(libObj.get("name").getAsString());
                    }
                }
            }
            for (var e : parentObj.getAsJsonArray("libraries")) {
                JsonObject libObj = e.getAsJsonObject();
                if (!libObj.has("name") || libObj.get("name").isJsonNull()) continue;
                String name = libObj.get("name").getAsString();
                if (!childNames.contains(name)) merged.add(e);
            }
            childObj.add("libraries", merged);
        }
        return VersionJson.parse(childObj.toString());
        } finally {
            visiting.remove(parentId);
        }
    }
}
