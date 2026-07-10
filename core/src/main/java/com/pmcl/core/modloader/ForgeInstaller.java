package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.download.DownloadTask;
import com.pmcl.core.install.InstallProgress;

import java.io.IOException;
import java.io.InputStream;
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
 *   3) 从 installer.jar 中提取 install_profile.json（包含完整版本 JSON 与 processors）
 *   4) 解析 install_profile.json，写入 versions/{id}/{id}.json
 *   5) 提取 installer.jar 内嵌的 maven 库到 libraries 目录
 *   6) 解析并下载剩余的远端库（用 BMCLAPI 镜像）
 * <p>
 * 完整的 processors 执行（SpecialSource 等再处理二进制）不在该实现范围内。
 * 多数 Forge 版本 BMCLAPI 已提供处理好的 JSON，可直接运行；遇到需 processor
 * 处理的版本，建议提示用户使用官方启动器或 HMCL 完成。
 */
public final class ForgeInstaller implements ModLoaderInstaller {

    private static final String BMCLAPI_BASE = "https://bmclapi2.bangbang93.com/forge/minecraft/";
    private static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";
    private static final String MOJANG_MAVEN = "https://libraries.minecraft.net/";

    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final boolean neoForge;

    public ForgeInstaller(LauncherConfig config, DownloadManager downloads, boolean neoForge) {
        this.config = config;
        this.downloads = downloads;
        this.neoForge = neoForge;
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
                    result.add(new ModLoaderVersion(
                            neoForge ? ModLoader.NEOFORGE : ModLoader.FORGE,
                            gameVersion,
                            o.get("version").getAsString(),
                            !o.has("branch") || "null".equals(o.get("branch").getAsString())
                    ));
                }
                return result;
            } catch (IOException ex) {
                throw new RuntimeException("拉取 Forge 版本失败", ex);
            }
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            Path installerJar = null;
            try {
                // 1. 下载 installer.jar
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "下载 Forge installer.jar"));
                String installerUrl = BMCLAPI_BASE + gameVersion + "/" + loaderVersion + "/jar";
                installerJar = Files.createTempFile("forge-installer-", ".jar");
                downloads.downloadTo(installerUrl, installerJar);

                // 2. 提取 install_profile.json
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "解析 install_profile.json"));
                JsonObject profile = extractInstallProfile(installerJar);
                // 优先用 profile.json 字段（Forge 1.13+），否则用顶层
                JsonObject versionJson;
                String versionId;
                if (profile.has("versionJson")) {
                    versionJson = profile.getAsJsonObject("versionJson");
                } else {
                    versionJson = profile;
                }
                versionId = versionJson.get("id").getAsString();

                // 3. 写入 versions/{id}/{id}.json
                Path versionDir = config.getVersionsDir().resolve(versionId);
                Files.createDirectories(versionDir);
                Files.writeString(versionDir.resolve(versionId + ".json"),
                        versionJson.toString());

                // 4. 提取 installer.jar 内嵌的 maven 库到 libraries
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                        "提取内嵌库"));
                List<DownloadTask> remoteLibs = new ArrayList<>();
                int embedded = extractEmbeddedLibraries(installerJar,
                        config.getLibrariesDir(), remoteLibs);

                // 5. 下载远端库
                if (!remoteLibs.isEmpty()) {
                    if (onProgress != null) onProgress.accept(new InstallProgress(
                            InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, remoteLibs.size(),
                            "下载 Forge 依赖库 (" + remoteLibs.size() + " 个)"));
                    downloads.downloadAll(remoteLibs, f -> {}, b -> {}).join();
                }

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        "Forge 安装完成: " + versionId +
                        "（内嵌库 " + embedded + "，远端库 " + remoteLibs.size() + "）"));
            } catch (IOException e) {
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, e.getMessage()));
                throw new RuntimeException("Forge 安装失败", e);
            } finally {
                // 清理临时文件
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
                throw new IOException("installer.jar 中找不到 install_profile.json");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return JsonParser.parseString(new String(in.readAllBytes(), "UTF-8"))
                        .getAsJsonObject();
            }
        }
    }

    /**
     * 解压 installer.jar 中 maven/ 目录下的库到 libraries 目录，
     * 同时解析版本 JSON 的 libraries 列表，未内嵌的库加入 remoteLibs。
     *
     * @return 内嵌库数量
     */
    private int extractEmbeddedLibraries(Path installerJar, Path librariesDir,
                                          List<DownloadTask> remoteLibs) throws IOException {
        // 1. 收集 installer.jar 内嵌的 maven 路径
        java.util.Set<String> embeddedPaths = new java.util.HashSet<>();
        int count = 0;
        try (ZipFile zip = new ZipFile(installerJar.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!e.isDirectory() && (name.startsWith("maven/") || name.startsWith("libraries/"))) {
                    String relPath = name.startsWith("maven/") ? name.substring("maven/".length())
                                                                : name.substring("libraries/".length());
                    if (relPath.isEmpty()) continue;
                    Path target = librariesDir.resolve(relPath);
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    embeddedPaths.add(relPath);
                    count++;
                }
            }
        }

        // 2. 解析版本 JSON 中的 libraries，未内嵌的添加到 remoteLibs
        //    （从 install_profile.json 重新读取 versionJson）
        try (ZipFile zip = new ZipFile(installerJar.toFile())) {
            ZipEntry entry = zip.getEntry("install_profile.json");
            if (entry == null) entry = zip.getEntry("install_profile");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    JsonObject profile = JsonParser.parseString(new String(in.readAllBytes(), "UTF-8"))
                            .getAsJsonObject();
                    JsonObject versionJson = profile.has("versionInfo")
                            ? profile.getAsJsonObject("versionInfo")
                            : (profile.has("versionJson") ? profile.getAsJsonObject("versionJson") : profile);
                    if (versionJson.has("libraries")) {
                        for (JsonElement e : versionJson.getAsJsonArray("libraries")) {
                            JsonObject lib = e.getAsJsonObject();
                            String name = lib.get("name").getAsString();
                            String path = mavenToPath(name);
                            if (!embeddedPaths.contains(path)) {
                                // 优先 BMCLAPI 镜像
                                String url = BMCLAPI_MAVEN + path;
                                // 若 lib 自带 url 字段，用其原 url
                                if (lib.has("url")) {
                                    url = lib.get("url").getAsString() + path;
                                }
                                remoteLibs.add(new DownloadTask(url, "", 0, "libraries/" + path));
                            }
                        }
                    }
                }
            }
        }
        return count;
    }

    /** maven 坐标转路径 */
    private static String mavenToPath(String coords) {
        String[] parts = coords.split(":");
        if (parts.length < 3) return coords;
        String groupPath = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        if (parts.length >= 4) {
            String classifier = parts[3];
            return groupPath + "/" + artifact + "/" + version + "/" +
                    artifact + "-" + version + "-" + classifier + ".jar";
        }
        return groupPath + "/" + artifact + "/" + version + "/" +
                artifact + "-" + version + ".jar";
    }
}
