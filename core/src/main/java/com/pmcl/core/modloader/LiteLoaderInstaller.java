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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LiteLoader 安装器。
 * <p>
 * LiteLoader 是旧版本（1.7.10 / 1.10.2 / 1.12.2 等）的轻量级模组加载器。
 * 官方元数据：https://dl.liteloader.com/versions/versions.json
 * <p>
 * 与 Forge/Fabric 不同，LiteLoader **没有预构建的版本 JSON 文件**。
 * dl.liteloader.com 上的版本 JSON 路径全部返回 404。
 * 因此安装流程为：
 *   1) 拉取 versions.json 清单，提取目标游戏版本下可用的 LiteLoader 版本
 *   2) 从清单元数据（tweakClass / libraries / file / version）**本地构造**版本 JSON
 *   3) 写入 versions/{id}/{id}.json，库文件由下载器统一拉取
 * <p>
 * LiteLoader 版本 JSON 继承自原版版本（inheritsFrom），使用 --tweakClass 注入
 * LiteLoaderTweaker。不需要执行 installer.jar，直接写入 JSON 即可运行。
 * <p>
 * 库下载 URL：
 *   - ivy 类型（RELEASE，1.5.2-1.8）：http://dl.liteloader.com/versions/ + maven path
 *   - m2 类型（SNAPSHOT，1.8.9-1.12.2）：https://bmclapi2.bangbang93.com/maven/ + maven path
 *     （repo.mumfrey.com 已下线，BMCLAPI maven 提供 302 重定向到教育网镜像）
 */
public final class LiteLoaderInstaller implements ModLoaderInstaller {

    private static final String MANIFEST_URL = "https://dl.liteloader.com/versions/versions.json";
    private static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";

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
                JsonObject root = parseJsonObject(json, "LiteLoader manifest");
                // versions.json 顶层可能直接以 MC 版本号为 key，也可能嵌套在 "versions" 字段下
                JsonObject byMc = root;
                if (root.has("versions") && root.get("versions").isJsonObject()) {
                    byMc = root.getAsJsonObject("versions");
                }
                List<ModLoaderVersion> result = new ArrayList<>();
                if (!byMc.has(gameVersion)) return result;
                JsonObject versionNode = byMc.getAsJsonObject(gameVersion);

                // 优先从 artefacts（RELEASE）提取，再从 snapshots（SNAPSHOT）提取
                result.addAll(extractVersions(versionNode, "artefacts", gameVersion, true));
                result.addAll(extractVersions(versionNode, "snapshots", gameVersion, false));

                return result;
            } catch (Throwable ex) {
                throw new RuntimeException("拉取 LiteLoader 版本失败", ex);
            }
        });
    }

    /** 从 manifest 的 artefacts 或 snapshots 节点提取版本列表 */
    private List<ModLoaderVersion> extractVersions(JsonObject versionNode, String section,
                                                     String gameVersion, boolean stable) {
        List<ModLoaderVersion> result = new ArrayList<>();
        if (!versionNode.has(section) || !versionNode.get(section).isJsonObject()) return result;
        JsonObject sectionNode = versionNode.getAsJsonObject(section);
        if (!sectionNode.has("com.mumfrey:liteloader")) return result;
        JsonObject loaderNode = sectionNode.getAsJsonObject("com.mumfrey:liteloader");
        for (Map.Entry<String, JsonElement> entry : loaderNode.entrySet()) {
            if ("latest".equals(entry.getKey())) continue; // 跳过 latest 别名
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject v = entry.getValue().getAsJsonObject();
            String version = v.has("version") && !v.get("version").isJsonNull()
                    ? v.get("version").getAsString() : entry.getKey();
            if (version.isEmpty()) continue;
            result.add(new ModLoaderVersion(
                    ModLoader.LITELOADER,
                    gameVersion,
                    version,
                    stable
            ));
        }
        return result;
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                            Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 1. 重新拉取 manifest，找到对应版本的元数据
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "拉取 LiteLoader 清单"));
                String json = downloads.downloadString(MANIFEST_URL);
                JsonObject root = parseJsonObject(json, "LiteLoader manifest");
                JsonObject byMc = root.has("versions") && root.get("versions").isJsonObject()
                        ? root.getAsJsonObject("versions") : root;
                if (!byMc.has(gameVersion)) {
                    throw new IOException("LiteLoader 清单中找不到游戏版本: " + gameVersion);
                }
                JsonObject versionNode = byMc.getAsJsonObject(gameVersion);

                // 在 artefacts 和 snapshots 中查找匹配的版本
                JsonObject artefact = findVersionEntry(versionNode, "artefacts", loaderVersion);
                boolean isSnapshot = false;
                if (artefact == null) {
                    artefact = findVersionEntry(versionNode, "snapshots", loaderVersion);
                    isSnapshot = true;
                }
                if (artefact == null) {
                    throw new IOException("LiteLoader 清单中找不到版本: " + loaderVersion);
                }

                // 2. 构造版本 JSON
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "构造 LiteLoader 版本 JSON"));
                String versionId = "LiteLoader-" + loaderVersion;
                JsonObject versionJson = buildVersionJson(gameVersion, loaderVersion,
                        versionId, artefact, versionNode, isSnapshot);

                // 3. 写入 versions/{id}/{id}.json
                Path versionDir = config.getVersionsDir().resolve(versionId);
                Files.createDirectories(versionDir);
                Files.writeString(versionDir.resolve(versionId + ".json"),
                        versionJson.toString(), java.nio.charset.StandardCharsets.UTF_8);

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

    /** 在 manifest 的 artefacts/snapshots 节点中查找指定版本 */
    private JsonObject findVersionEntry(JsonObject versionNode, String section, String loaderVersion) {
        if (!versionNode.has(section) || !versionNode.get(section).isJsonObject()) return null;
        JsonObject sectionNode = versionNode.getAsJsonObject(section);
        if (!sectionNode.has("com.mumfrey:liteloader")) return null;
        JsonObject loaderNode = sectionNode.getAsJsonObject("com.mumfrey:liteloader");
        // 先精确匹配 version 字段
        for (Map.Entry<String, JsonElement> entry : loaderNode.entrySet()) {
            if ("latest".equals(entry.getKey())) continue;
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject v = entry.getValue().getAsJsonObject();
            String ver = v.has("version") && !v.get("version").isJsonNull()
                    ? v.get("version").getAsString() : "";
            if (loaderVersion.equals(ver)) return v;
        }
        // 再匹配 key（md5 hash）
        if (loaderNode.has(loaderVersion) && loaderNode.get(loaderVersion).isJsonObject()) {
            return loaderNode.getAsJsonObject(loaderVersion);
        }
        return null;
    }

    /**
     * 本地构造 LiteLoader 版本 JSON。
 * <p>
 * 结构：
 * <pre>
 * {
 *   "id": "LiteLoader-{version}",
 *   "inheritsFrom": "{gameVersion}",
 *   "mainClass": "net.minecraft.launchwrapper.Launcher",
 *   "minecraftArguments": "{标准参数} --tweakClass {tweakClass}",
 *   "libraries": [
 *     { "name": "com.mumfrey:liteloader:{version}", "url": "{repoUrl}" },
 *     ...依赖库
 *   ],
 *   "type": "release" or "snapshot"
 * }
 * </pre>
 */
    private JsonObject buildVersionJson(String gameVersion, String loaderVersion,
                                          String versionId, JsonObject artefact,
                                          JsonObject versionNode, boolean isSnapshot) throws IOException {
        JsonObject versionJson = new JsonObject();

        versionJson.addProperty("id", versionId);
        versionJson.addProperty("inheritsFrom", gameVersion);
        versionJson.addProperty("mainClass", "net.minecraft.launchwrapper.Launcher");
        versionJson.addProperty("type", isSnapshot ? "snapshot" : "release");

        // tweakClass
        String tweakClass = "com.mumfrey.liteloader.launch.LiteLoaderTweaker";
        if (artefact.has("tweakClass") && !artefact.get("tweakClass").isJsonNull()) {
            tweakClass = artefact.get("tweakClass").getAsString();
        }

        // minecraftArguments：尝试从父版本继承，追加 --tweakClass
        String mcArgs = resolveMinecraftArguments(gameVersion);
        mcArgs = mcArgs + " --tweakClass " + tweakClass;
        versionJson.addProperty("minecraftArguments", mcArgs);

        // libraries
        JsonArray libraries = new JsonArray();

        // 1. LiteLoader 自身库
        JsonObject liteloaderLib = new JsonObject();
        liteloaderLib.addProperty("name", "com.mumfrey:liteloader:" + loaderVersion);
        // 确定下载 URL：ivy 用 dl.liteloader.com，m2 用 BMCLAPI maven
        String repoUrl = resolveRepoUrl(versionNode, isSnapshot);
        liteloaderLib.addProperty("url", repoUrl);
        libraries.add(liteloaderLib);

        // 2. 依赖库（launchwrapper, asm 等）
        if (artefact.has("libraries") && artefact.get("libraries").isJsonArray()) {
            for (JsonElement e : artefact.getAsJsonArray("libraries")) {
                if (e.isJsonObject()) {
                    JsonObject lib = e.getAsJsonObject();
                    // 保留原有的 url 字段（如 asm-all 的 url）
                    libraries.add(lib);
                }
            }
        }

        // snapshots 节点可能有额外的公共 libraries
        if (isSnapshot && versionNode.has("snapshots")
                && versionNode.getAsJsonObject("snapshots").has("libraries")
                && versionNode.getAsJsonObject("snapshots").get("libraries").isJsonArray()) {
            for (JsonElement e : versionNode.getAsJsonObject("snapshots").getAsJsonArray("libraries")) {
                if (e.isJsonObject()) libraries.add(e.getAsJsonObject());
            }
        }

        versionJson.add("libraries", libraries);
        return versionJson;
    }

    /**
     * 解析库下载 URL。
 * - ivy 类型（RELEASE）：http://dl.liteloader.com/versions/（Cloudflare CDN，可用）
 * - m2 类型（SNAPSHOT）：https://bmclapi2.bangbang93.com/maven/（repo.mumfrey.com 已下线）
 */
    private String resolveRepoUrl(JsonObject versionNode, boolean isSnapshot) {
        JsonObject repo = versionNode.has("repo") && versionNode.get("repo").isJsonObject()
                ? versionNode.getAsJsonObject("repo") : null;
        String type = repo != null && repo.has("type") && !repo.get("type").isJsonNull()
                ? repo.get("type").getAsString() : "ivy";
        if ("m2".equals(type) || isSnapshot) {
            // repo.mumfrey.com 已下线，用 BMCLAPI maven 镜像
            return BMCLAPI_MAVEN;
        }
        // ivy: dl.liteloader.com/versions/（Cloudflare CDN）
        return "http://dl.liteloader.com/versions/";
    }

    /**
     * 获取 minecraftArguments。
 * 优先从已安装的父版本 JSON 读取，找不到则用 1.7.x-1.12.x 标准格式。
     */
    private String resolveMinecraftArguments(String gameVersion) {
        // 尝试读取已安装的父版本 JSON
        Path parentJson = config.getVersionsDir().resolve(gameVersion).resolve(gameVersion + ".json");
        if (Files.exists(parentJson)) {
            try {
                String content = Files.readString(parentJson, java.nio.charset.StandardCharsets.UTF_8);
                JsonObject parent = parseJsonObject(content, "父版本 " + gameVersion);
                if (parent.has("minecraftArguments") && !parent.get("minecraftArguments").isJsonNull()) {
                    return parent.get("minecraftArguments").getAsString();
                }
            } catch (IOException ignored) {
                // 读取失败，用默认值
            }
        }
        // 1.7.x-1.12.x 标准参数
        return "--username ${auth_name} --version ${version_name} --gameDir ${game_directory} "
                + "--assetsDir ${assets_root} --assetIndex ${assets_index_name} "
                + "--uuid ${auth_uuid} --accessToken ${auth_access_token} "
                + "--userProperties ${user_properties} --userType ${user_type}";
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
