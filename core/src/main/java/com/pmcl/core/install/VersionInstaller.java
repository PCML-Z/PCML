package com.pmcl.core.install;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.download.DownloadTask;
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
 */
public final class VersionInstaller {

    private static final String RESOURCE_BASE = "https://resources.download.minecraft.net/";
    private static final String LIBRARY_BASE = "https://libraries.minecraft.net/";
    private static final String ASSET_INDEX_BASE = "https://piston-meta.mojang.com/";

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
            try {
                doInstall(versionId, onProgress);
            } catch (Throwable e) {
                // 安装失败：清理半成品版本目录，避免残留损坏状态
                try {
                    Path versionDir = config.getVersionsDir().resolve(versionId);
                    if (Files.exists(versionDir)) {
                        try (var stream = Files.walk(versionDir)) {
                            stream.sorted(java.util.Comparator.reverseOrder())
                                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                        }
                    }
                } catch (Throwable cleanupErr) {
                    System.err.println("[VersionInstaller] 清理失败版本目录异常: " + cleanupErr.getMessage());
                }
                if (onProgress != null)
                    onProgress.accept(new InstallProgress(
                            InstallProgress.Stage.FAILED, 0, 0, e.getMessage()));
                throw new RuntimeException("安装失败: " + versionId, e);
            }
        });
    }

    private void doInstall(String versionId, Consumer<InstallProgress> onProgress) throws IOException {
        // 1. 找到版本元信息
        McVersion target = findVersion(versionId);

        // 2. 下载版本 JSON
        if (onProgress != null) onProgress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1, "下载版本清单"));
        Path versionJsonPath = config.getVersionsDir().resolve(versionId).resolve(versionId + ".json");
        Files.createDirectories(versionJsonPath.getParent());
        String versionJsonStr = downloadManager.downloadString(target.getUrl());
        Files.writeString(versionJsonPath, versionJsonStr, java.nio.charset.StandardCharsets.UTF_8);

        VersionJson vj = VersionJson.parse(versionJsonStr);

        // 处理继承：合并父版本 JSON
        if (vj.getInheritsFrom() != null && !vj.getInheritsFrom().equals(versionId)) {
            vj = mergeInherited(vj, vj.getInheritsFrom());
        }

        List<DownloadTask> tasks = new ArrayList<>();

        // 3. client.jar
        if (vj.getClientArtifact() != null) {
            VersionJson.Artifact c = vj.getClientArtifact();
            tasks.add(new DownloadTask(
                    c.getUrl(), c.getSha1(), c.getSize(),
                    "versions/" + vj.getId() + "/" + vj.getId() + ".jar"));
        }

        // 4. libraries（含 native classifier）
        if (onProgress != null) onProgress.accept(new InstallProgress(
                InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, vj.getLibraries().size(),
                "扫描依赖库"));
        for (Library lib : vj.getLibraries()) {
            if (!lib.appliesToCurrentOs()) continue;
            // 主 artifact
            if (lib.getArtifact() != null) {
                VersionJson.Artifact a = lib.getArtifact();
                tasks.add(new DownloadTask(
                        a.getUrl(), a.getSha1(), a.getSize(),
                        "libraries/" + lib.getPath()));
            }
            // native classifier（按当前 OS 选择）
            if (lib.isNativeLib()) {
                VersionJson.Artifact n = lib.getNativeArtifact();
                if (n != null) {
                    tasks.add(new DownloadTask(
                            n.getUrl(), n.getSha1(), n.getSize(),
                            "libraries/" + lib.getPathForClassifier(lib.getNativeClassifier())));
                }
            }
        }

        // 5. 资产索引
        if (vj.getAssets() != null && !vj.getAssets().isEmpty()) {
            if (onProgress != null) onProgress.accept(new InstallProgress(
                    InstallProgress.Stage.DOWNLOAD_ASSET_INDEX, 0, 1, "下载资产索引"));
            String assetIndexUrl = resolveAssetIndexUrl(vj);
            if (assetIndexUrl != null) {
                String idxJson = downloadManager.downloadString(assetIndexUrl);
                Path idxPath = config.getAssetsDir().resolve("indexes").resolve(vj.getAssets() + ".json");
                Files.createDirectories(idxPath.getParent());
                Files.writeString(idxPath, idxJson, java.nio.charset.StandardCharsets.UTF_8);

                AssetIndex idx = AssetIndex.parse(idxJson);
                for (AssetIndex.Asset a : idx.getAssets().values()) {
                    tasks.add(new DownloadTask(
                            RESOURCE_BASE + a.getPath(),
                            a.getHash(), a.getSize(),
                            "assets/objects/" + a.getPath()));
                }
            }
        }

        // 6. 执行批量下载
        final long total = tasks.stream().mapToLong(DownloadTask::getSize).sum();
        downloadManager.downloadAll(tasks,
                file -> {},
                bytes -> {
                    if (onProgress != null) {
                        onProgress.accept(new InstallProgress(
                                InstallProgress.Stage.DOWNLOAD_LIBRARIES, bytes, total,
                                String.format("下载中 %d / %d bytes", bytes, total)));
                    }
                }).join();

        // 7. 解压 native 库到 versions/{id}/natives
        extractNatives(vj);

        if (onProgress != null) onProgress.accept(new InstallProgress(
                InstallProgress.Stage.DONE, total, total,
                "安装完成: " + versionId));
    }

    /**
     * 解压所有 native jar 到 versions/{id}/natives 目录。
     * 排除 META-INF（避免签名文件冲突）。
     */
    private void extractNatives(VersionJson vj) throws IOException {
        Path nativesDir = config.getVersionsDir().resolve(vj.getId()).resolve("natives");
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
            if (!Files.exists(nativeJar)) continue;
            // S22 安全修复：ZipBomb 防护
            final long MAX_TOTAL = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_TOTAL_SIZE;
            final int MAX_ENTRIES = com.pmcl.core.util.SafeZipExtractor.DEFAULT_MAX_ENTRIES;
            long totalSize = 0;
            int entryCount = 0;
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
                    // M73: ZipSlip 纵深防御 — 第一层：entry name 早期过滤
                    // 拒绝绝对路径（Unix/Windows）、空名、包含路径穿越段（..）的 entry
                    if (name.isEmpty()) continue;
                    if (name.startsWith("/") || name.startsWith("\\")
                            || name.matches("^[A-Za-z]:[\\\\/].*")) continue;
                    boolean hasDotDot = false;
                    for (String seg : name.replace('\\', '/').split("/")) {
                        if ("..".equals(seg)) { hasDotDot = true; break; }
                    }
                    if (hasDotDot) continue;
                    // M73: 第二层 — 最终路径归属校验（即使 entry name 伪装也得过这一关）
                    Path target = nativesDir.resolve(name).toAbsolutePath().normalize();
                    if (!target.startsWith(nativesDirAbs)) continue;
                    // 第三层 — 父目录也必须落在 nativesDir 内（防止 createDirectories 创建外部目录）
                    Path parent = target.getParent();
                    if (parent == null || !parent.startsWith(nativesDirAbs)) continue;
                    Files.createDirectories(parent);
                    // S22 安全修复：流式写入并累计字节数
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
                }
            } catch (java.util.zip.ZipException ignored) {
                // 非 zip 文件，跳过
            }
        }
    }

    private McVersion findVersion(String versionId) throws IOException {
        List<McVersion> versions = versionManager.fetchRemoteVersions().join();
        for (McVersion v : versions) {
            if (v.getId().equals(versionId)) return v;
        }
        throw new IOException("版本不存在: " + versionId);
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

    /**
     * 合并继承版本的 JSON：父版本为主，子版本覆盖 mainClass 等。
     * 简化实现：直接重新下载父版本 JSON 并合并 libraries。
     */
    private VersionJson mergeInherited(VersionJson child, String parentId) throws IOException {
        List<McVersion> versions = versionManager.fetchRemoteVersions().join();
        McVersion parent = null;
        for (McVersion v : versions) {
            if (v.getId().equals(parentId)) { parent = v; break; }
        }
        if (parent == null) return child;
        if (parent.getUrl() == null) return child;

        String parentJson = downloadManager.downloadString(parent.getUrl());
        // 简单合并：将父版本的 libraries 与子版本合并（去重）
        JsonObject parentObj = JsonParser.parseString(parentJson).getAsJsonObject();
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
    }
}
