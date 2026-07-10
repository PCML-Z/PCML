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
            } catch (IOException e) {
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
        Files.writeString(versionJsonPath, versionJsonStr);

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
                Files.writeString(idxPath, idxJson);

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
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(nativeJar.toFile())) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = en.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    // 跳过签名文件与元数据
                    if (name.startsWith("META-INF/")) continue;
                    Path target = nativesDir.resolve(name);
                    Files.createDirectories(target.getParent());
                    try (var in = zip.getInputStream(entry)) {
                        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
        // 合并 libraries（子的覆盖父的同名库）
        if (parentObj.has("libraries")) {
            com.google.gson.JsonArray merged = new com.google.gson.JsonArray();
            java.util.Set<String> childNames = new java.util.HashSet<>();
            if (childObj.has("libraries")) {
                for (var e : childObj.getAsJsonArray("libraries")) {
                    merged.add(e);
                    childNames.add(e.getAsJsonObject().get("name").getAsString());
                }
            }
            for (var e : parentObj.getAsJsonArray("libraries")) {
                String name = e.getAsJsonObject().get("name").getAsString();
                if (!childNames.contains(name)) merged.add(e);
            }
            childObj.add("libraries", merged);
        }
        return VersionJson.parse(childObj.toString());
    }
}
