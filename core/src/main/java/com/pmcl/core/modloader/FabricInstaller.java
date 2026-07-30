package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.install.VersionStaging;
import com.pmcl.core.util.Exceptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Fabric 安装器。
 * <p>
 * Fabric Meta API：
 *   https://meta.fabricmc.net/v2/versions/loader/{game_version}
 *   https://meta.fabricmc.net/v2/versions/loader/{game_version}/{loader_version}/profile/json
 */
public final class FabricInstaller implements ModLoaderInstaller {

    private static final String META_BASE = "https://meta.fabricmc.net/v2/versions/loader/";
    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final VersionInstaller versionInstaller;

    public FabricInstaller(LauncherConfig config, DownloadManager downloads,
                           VersionInstaller versionInstaller) {
        this.config = config;
        this.downloads = downloads;
        this.versionInstaller = versionInstaller;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = downloads.downloadString(META_BASE + gameVersion);
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                List<ModLoaderVersion> result = new ArrayList<>();
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    JsonObject loader = o.getAsJsonObject("loader");
                    if (loader == null) continue;
                    result.add(new ModLoaderVersion(
                            ModLoader.FABRIC,
                            gameVersion,
                            loader.has("version") && !loader.get("version").isJsonNull()
                                    ? loader.get("version").getAsString() : "",
                            loader.has("stable") && !loader.get("stable").isJsonNull()
                                    ? loader.get("stable").getAsBoolean() : true
                    ));
                }
                return result;
            } catch (Throwable ex) {
                throw new RuntimeException("拉取 Fabric 版本失败", ex);
            }
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            String id = null;
            try {
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "下载 Fabric profile JSON"));
                String profileJsonUrl = META_BASE + gameVersion + "/" + loaderVersion + "/profile/json";
                String profileJson = downloads.downloadString(profileJsonUrl);

                JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
                id = profile.has("id") && !profile.get("id").isJsonNull()
                        ? profile.get("id").getAsString() : "";
                if (id == null || id.isBlank()) {
                    throw new IOException("Fabric profile 缺少有效 id");
                }

                String parentId = profile.has("inheritsFrom") && !profile.get("inheritsFrom").isJsonNull()
                        ? profile.get("inheritsFrom").getAsString() : gameVersion;
                ensureParentInstalled(parentId, onProgress);

                Path staging = VersionStaging.writeVersionJson(
                        config.getVersionsDir(), id, profileJson);
                int libCount = ModLoaderProfileLibraries.downloadMissing(
                        downloads, config.getLibrariesDir(), profileJson, "Fabric", onProgress);
                VersionStaging.promote(config.getVersionsDir(), id, staging);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        "Fabric 安装完成: " + id
                                + (libCount > 0 ? "（依赖库 " + libCount + "）" : "")));
            } catch (Exception e) {
                if (!InstallInterruptedException.isInterrupted(e) && id != null && !id.isBlank()) {
                    VersionStaging.discard(config.getVersionsDir(), id);
                }
                String detail = Exceptions.rootMessage(e);
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, detail));
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new InstallInterruptedException("Fabric 安装已中断", e);
                }
                throw new RuntimeException("Fabric 安装失败: " + detail, e);
            }
        });
    }

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
}
