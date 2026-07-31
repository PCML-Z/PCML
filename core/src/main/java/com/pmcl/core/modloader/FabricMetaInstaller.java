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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 兼容 Fabric Meta 协议的加载器安装器（Fabric / Quilt / Legacy Fabric / Babric / Ornithe 等）。
 * <p>
 * 约定端点：
 *   {metaBase}{gameVersion}
 *   {metaBase}{gameVersion}/{loaderVersion}/profile/json
 * 其中 {@code metaBase} 形如 {@code https://meta.fabricmc.net/v2/versions/loader/}
 */
public final class FabricMetaInstaller implements ModLoaderInstaller {

    private final ModLoader loader;
    private final String metaBase;
    private final String displayName;
    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final VersionInstaller versionInstaller;

    public FabricMetaInstaller(ModLoader loader, String metaBase, String displayName,
                               LauncherConfig config, DownloadManager downloads,
                               VersionInstaller versionInstaller) {
        this.loader = loader;
        this.metaBase = metaBase.endsWith("/") ? metaBase : metaBase + "/";
        this.displayName = displayName;
        this.config = config;
        this.downloads = downloads;
        this.versionInstaller = versionInstaller;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = downloads.downloadString(metaBase + encode(gameVersion));
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                List<ModLoaderVersion> result = new ArrayList<>();
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    JsonObject loaderObj = o.getAsJsonObject("loader");
                    if (loaderObj == null) continue;
                    result.add(new ModLoaderVersion(
                            loader,
                            gameVersion,
                            loaderObj.has("version") && !loaderObj.get("version").isJsonNull()
                                    ? loaderObj.get("version").getAsString() : "",
                            !loaderObj.has("stable") || loaderObj.get("stable").isJsonNull()
                                    || loaderObj.get("stable").getAsBoolean()
                    ));
                }
                return result;
            } catch (Throwable ex) {
                throw new RuntimeException("拉取 " + displayName + " 版本失败", ex);
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
                        "下载 " + displayName + " profile JSON"));
                String profileJsonUrl = metaBase + encode(gameVersion) + "/"
                        + encode(loaderVersion) + "/profile/json";
                String profileJson = downloads.downloadString(profileJsonUrl);

                JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
                id = profile.has("id") && !profile.get("id").isJsonNull()
                        ? profile.get("id").getAsString() : "";
                if (id == null || id.isBlank()) {
                    throw new IOException(displayName + " profile 缺少有效 id");
                }

                String parentId = profile.has("inheritsFrom") && !profile.get("inheritsFrom").isJsonNull()
                        ? profile.get("inheritsFrom").getAsString() : gameVersion;
                ensureParentInstalled(parentId, onProgress);

                Path staging = VersionStaging.writeVersionJson(
                        config.getVersionsDir(), id, profileJson);
                int libCount = ModLoaderProfileLibraries.downloadMissing(
                        downloads, config.getLibrariesDir(), profileJson, displayName, onProgress);
                VersionStaging.promote(config.getVersionsDir(), id, staging);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        displayName + " 安装完成: " + id
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
                            : new InstallInterruptedException(displayName + " 安装已中断", e);
                }
                throw new RuntimeException(displayName + " 安装失败: " + detail, e);
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
