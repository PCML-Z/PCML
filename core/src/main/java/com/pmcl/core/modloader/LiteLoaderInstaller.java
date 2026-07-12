package com.pmcl.core.modloader;

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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LiteLoader 安装器。
 * <p>
 * LiteLoader 是旧版本（1.7.10 / 1.10.2 / 1.12.2 等）的轻量级模组加载器。
 * 官方元数据：https://dl.liteloader.com/versions/versions.json
 * 版本 JSON：https://dl.liteloader.com/versions/com/mumfrey/liteloader/{version}/liteloader-{version}.json
 * <p>
 * 安装流程：
 *   1) 拉取 versions.json 清单，提取目标游戏版本下可用的 LiteLoader 版本
 *   2) 下载对应版本 JSON（已含 liteloader 库声明与 inheritsFrom）
 *   3) 写入 versions/{id}/{id}.json，库文件由下载器统一拉取
 * <p>
 * LiteLoader 版本 JSON 继承自原版版本，使用 --tweakClass 注入 LiteLoaderTweaker。
 * 不需要执行 installer.jar，直接写入 JSON 即可运行。
 */
public final class LiteLoaderInstaller implements ModLoaderInstaller {

    private static final String MANIFEST_URL = "https://dl.liteloader.com/versions/versions.json";
    private static final String VERSION_JSON_BASE =
            "https://dl.liteloader.com/versions/com/mumfrey/liteloader/";

    private final LauncherConfig config;
    private final DownloadManager downloads;

    public LiteLoaderInstaller(LauncherConfig config, DownloadManager downloads) {
        this.config = config;
        this.downloads = downloads;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = downloads.downloadString(MANIFEST_URL);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                // versions.json 顶层可能直接以 MC 版本号为 key，也可能嵌套在 "versions" 字段下
                JsonObject byMc = root;
                if (root.has("versions") && root.get("versions").isJsonObject()) {
                    byMc = root.getAsJsonObject("versions");
                }
                List<ModLoaderVersion> result = new ArrayList<>();
                if (!byMc.has(gameVersion)) return result;
                JsonObject versions = byMc.getAsJsonObject(gameVersion);
                for (Map.Entry<String, JsonElement> entry : versions.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject v = entry.getValue().getAsJsonObject();
                    String version = v.has("version") && !v.get("version").isJsonNull()
                            ? v.get("version").getAsString() : entry.getKey();
                    String type = v.has("type") && !v.get("type").isJsonNull()
                            ? v.get("type").getAsString() : "RELEASE";
                    boolean stable = "RELEASE".equalsIgnoreCase(type);
                    result.add(new ModLoaderVersion(
                            ModLoader.LITELOADER,
                            gameVersion,
                            version,
                            stable
                    ));
                }
                return result;
            } catch (Throwable ex) {
                throw new RuntimeException("拉取 LiteLoader 版本失败", ex);
            }
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                            Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 1. 下载版本 JSON
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "下载 LiteLoader 版本 JSON"));
                String jsonUrl = VERSION_JSON_BASE + loaderVersion + "/liteloader-" + loaderVersion + ".json";
                String versionJsonStr = downloads.downloadString(jsonUrl);
                JsonObject versionJson = JsonParser.parseString(versionJsonStr).getAsJsonObject();

                String versionId = versionJson.has("id") && !versionJson.get("id").isJsonNull()
                        ? versionJson.get("id").getAsString()
                        : "LiteLoader-" + loaderVersion;

                // 2. 写入 versions/{id}/{id}.json
                Path versionDir = config.getVersionsDir().resolve(versionId);
                Files.createDirectories(versionDir);
                Files.writeString(versionDir.resolve(versionId + ".json"), versionJsonStr);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        "LiteLoader 安装完成: " + versionId));
            } catch (IOException e) {
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, e.getMessage()));
                throw new RuntimeException("LiteLoader 安装失败", e);
            }
        });
    }
}
