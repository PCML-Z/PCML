package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.InstallProgress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Quilt 安装器。
 * <p>
 * Quilt Meta API 与 Fabric 几乎一致：
 *   https://meta.quiltmc.org/v3/versions/loader/{game_version}
 *   https://meta.quiltmc.org/v3/versions/loader/{game_version}/{loader_version}/profile/json
 */
public final class QuiltInstaller implements ModLoaderInstaller {

    private static final String META_BASE = "https://meta.quiltmc.org/v3/versions/loader/";
    private final LauncherConfig config;
    private final DownloadManager downloads;

    public QuiltInstaller(LauncherConfig config, DownloadManager downloads) {
        this.config = config;
        this.downloads = downloads;
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
                    result.add(new ModLoaderVersion(
                            ModLoader.QUILT,
                            gameVersion,
                            loader.has("version") ? loader.get("version").getAsString() : "",
                            loader.has("stable") && !loader.get("stable").isJsonNull()
                                    ? loader.get("stable").getAsBoolean() : true
                    ));
                }
                return result;
            } catch (IOException ex) {
                throw new RuntimeException("拉取 Quilt 版本失败", ex);
            }
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "下载 Quilt profile JSON"));
                String url = META_BASE + gameVersion + "/" + loaderVersion + "/profile/json";
                String profileJson = downloads.downloadString(url);

                JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
                String id = profile.get("id").getAsString();

                Path versionDir = config.getVersionsDir().resolve(id);
                Files.createDirectories(versionDir);
                Files.writeString(versionDir.resolve(id + ".json"), profileJson);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        "Quilt 安装完成: " + id));
            } catch (IOException e) {
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, e.getMessage()));
                throw new RuntimeException("Quilt 安装失败", e);
            }
        });
    }
}
