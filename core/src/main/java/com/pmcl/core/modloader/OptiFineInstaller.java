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
 * OptiFine 安装器。
 * <p>
 * OptiFine 不是传统模组加载器，但通过 BMCLAPI 可获取预构建的版本 JSON：
 *   版本列表：https://bmclapi2.bangbang93.com/optifine/{gameVersion}
 *   版本 JSON：https://bmclapi2.bangbang93.com/optifine/{gameVersion}/{type}/{patch}/json
 * <p>
 * 安装流程：
 *   1) 拉取 BMCLAPI 版本列表（返回 type/patch 组合）
 *   2) 下载对应版本 JSON（已包含 OptiFine 库与原版继承关系）
 *   3) 写入 versions/{id}/{id}.json
 *   4) 下载 OptiFine 容器库（launchWrapper 等已由 JSON 声明，由下载器统一拉取）
 * <p>
 * 注意：OptiFine 1.14+ 改用官方 Installer，但 BMCLAPI 的 JSON 已处理此情况，
 * 直接写入即可运行；部分版本仍需 Forge（由用户选择时自行判断）。
 */
public final class OptiFineInstaller implements ModLoaderInstaller {

    private static final String BMCLAPI_BASE = "https://bmclapi2.bangbang93.com/optifine/";

    private final LauncherConfig config;
    private final DownloadManager downloads;

    public OptiFineInstaller(LauncherConfig config, DownloadManager downloads) {
        this.config = config;
        this.downloads = downloads;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = downloads.downloadString(BMCLAPI_BASE + gameVersion);
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                List<ModLoaderVersion> result = new ArrayList<>();
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    String type = o.has("type") && !o.get("type").isJsonNull()
                            ? o.get("type").getAsString() : "";
                    String patch = o.has("patch") && !o.get("patch").isJsonNull()
                            ? o.get("patch").getAsString() : "";
                    // BMCLAPI 的 _games 字段标记是否有 Forge 依赖
                    boolean needsForge = o.has("_forge") && !o.get("_forge").isJsonNull()
                            && o.get("_forge").getAsBoolean();
                    if (type.isEmpty() || patch.isEmpty()) continue;
                    // 将 type/patch 编码到 loaderVersion，用 "|" 分隔
                    String encoded = type + "|" + patch + (needsForge ? "|forge" : "");
                    result.add(new ModLoaderVersion(
                            ModLoader.OPTIFINE,
                            gameVersion,
                            encoded,
                            !needsForge  // 非 Forge 依赖的视为稳定版
                    ));
                }
                return result;
            } catch (Throwable ex) {
                throw new RuntimeException("拉取 OptiFine 版本失败", ex);
            }
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 解码 loaderVersion: "type|patch[|forge]"
                String[] parts = loaderVersion.split("\\|");
                if (parts.length < 2) {
                    throw new IOException("无效的 OptiFine 版本标识: " + loaderVersion);
                }
                String type = parts[0];
                String patch = parts[1];

                // 1. 下载版本 JSON
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "下载 OptiFine 版本 JSON"));
                String jsonUrl = BMCLAPI_BASE + gameVersion + "/" + type + "/" + patch + "/json";
                String versionJsonStr = downloads.downloadString(jsonUrl);
                JsonObject versionJson = JsonParser.parseString(versionJsonStr).getAsJsonObject();

                String versionId = versionJson.has("id") && !versionJson.get("id").isJsonNull()
                        ? versionJson.get("id").getAsString()
                        : "OptiFine_" + gameVersion + "_" + type + "_" + patch;

                // 2. 写入 versions/{id}/{id}.json
                Path versionDir = config.getVersionsDir().resolve(versionId);
                Files.createDirectories(versionDir);
                Files.writeString(versionDir.resolve(versionId + ".json"),
                        versionJsonStr, java.nio.charset.StandardCharsets.UTF_8);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        "OptiFine 安装完成: " + versionId));
            } catch (IOException e) {
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, e.getMessage()));
                throw new RuntimeException("OptiFine 安装失败", e);
            }
        });
    }
}
