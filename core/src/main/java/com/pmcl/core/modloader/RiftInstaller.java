package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.install.VersionStaging;
import com.pmcl.core.util.Exceptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Rift 安装器（当前支持 Minecraft 1.13.2）。
 * <p>
 * 使用 Chocohead 维护的 JitPack 构件 + Sponge Mixin 0.7.11-SNAPSHOT + LaunchWrapper。
 */
public final class RiftInstaller implements ModLoaderInstaller {

    private static final String JITPACK = "https://jitpack.io/";
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    private static final String SPONGE = "https://repo.spongepowered.org/repository/maven-public/";
    private static final String MIXIN_JAR =
            SPONGE + "org/spongepowered/mixin/0.7.11-SNAPSHOT/mixin-0.7.11-20180703.121122-1.jar";

    /** gameVersion → (loaderVersion → Chocohead/JitPack commit) */
    private static final Map<String, Map<String, String>> VERSIONS = new LinkedHashMap<>();
    static {
        // Chocohead/Rift newerest 分支（1.13.2）
        Map<String, String> v1132 = new LinkedHashMap<>();
        v1132.put("1.0.4-2d8bb9bd56", "2d8bb9bd56");
        v1132.put("1.0.4", "2d8bb9bd56");
        VERSIONS.put("1.13.2", v1132);
    }

    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final VersionInstaller versionInstaller;

    public RiftInstaller(LauncherConfig config, DownloadManager downloads,
                         VersionInstaller versionInstaller) {
        this.config = config;
        this.downloads = downloads;
        this.versionInstaller = versionInstaller;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            List<ModLoaderVersion> out = new ArrayList<>();
            Map<String, String> map = VERSIONS.get(gameVersion);
            if (map == null) return out;
            boolean first = true;
            for (String ver : map.keySet()) {
                out.add(new ModLoaderVersion(ModLoader.RIFT, gameVersion, ver, first));
                first = false;
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            String id = gameVersion + "-rift-" + loaderVersion;
            try {
                Map<String, String> map = VERSIONS.get(gameVersion);
                if (map == null || !map.containsKey(loaderVersion)) {
                    throw new IllegalArgumentException(
                            "Rift 不支持 MC " + gameVersion + " / " + loaderVersion
                                    + "（当前支持 1.13.2）");
                }
                String riftCoordVer = map.get(loaderVersion);
                VersionStaging.assertSafeVersionId(id);
                ParentVersionSupport.ensureParentInstalled(
                        config, versionInstaller, gameVersion, onProgress);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1, "构造 Rift 版本 JSON"));

                JsonObject profile = buildProfile(id, gameVersion, riftCoordVer);
                PathStagingResult staging = writeAndDownload(id, profile, onProgress);
                VersionStaging.promote(config.getVersionsDir(), id, staging.stagingDir);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1, "Rift 安装完成: " + id));
            } catch (Exception e) {
                if (!InstallInterruptedException.isInterrupted(e)) {
                    VersionStaging.discard(config.getVersionsDir(), id);
                }
                String detail = Exceptions.rootMessage(e);
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, detail));
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new InstallInterruptedException("Rift 安装已中断", e);
                }
                throw new RuntimeException("Rift 安装失败: " + detail, e);
            }
        });
    }

    private JsonObject buildProfile(String id, String gameVersion, String riftCoordVer) {
        JsonObject profile = new JsonObject();
        profile.addProperty("id", id);
        profile.addProperty("inheritsFrom", gameVersion);
        profile.addProperty("type", "release");
        profile.addProperty("mainClass", "net.minecraft.launchwrapper.Launch");

        JsonObject args = new JsonObject();
        JsonArray game = new JsonArray();
        game.add("--tweakClass");
        game.add("org.dimdev.riftloader.launch.RiftLoaderClientTweaker");
        args.add("game", game);
        profile.add("arguments", args);

        JsonArray libs = new JsonArray();
        libs.add(lib("com.github.Chocohead:rift:" + riftCoordVer, JITPACK));
        libs.add(lib("org.ow2.asm:asm:6.2", MAVEN_CENTRAL));
        libs.add(lib("org.ow2.asm:asm-commons:6.2", MAVEN_CENTRAL));
        libs.add(lib("org.ow2.asm:asm-tree:6.2", MAVEN_CENTRAL));
        libs.add(lib("net.minecraft:launchwrapper:1.12", null));

        // Mixin：带精确 downloads URL（SNAPSHOT 文件名含时间戳）
        JsonObject mixin = new JsonObject();
        mixin.addProperty("name", "org.spongepowered:mixin:0.7.11-SNAPSHOT");
        JsonObject downloadsObj = new JsonObject();
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path",
                "org/spongepowered/mixin/0.7.11-SNAPSHOT/mixin-0.7.11-SNAPSHOT.jar");
        artifact.addProperty("url", MIXIN_JAR);
        downloadsObj.add("artifact", artifact);
        mixin.add("downloads", downloadsObj);
        libs.add(mixin);

        profile.add("libraries", libs);
        return profile;
    }

    private static JsonObject lib(String name, String url) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        if (url != null) o.addProperty("url", url);
        return o;
    }

    private PathStagingResult writeAndDownload(String id, JsonObject profile,
                                               Consumer<InstallProgress> onProgress)
            throws Exception {
        String json = profile.toString();
        var staging = VersionStaging.writeVersionJson(config.getVersionsDir(), id, json);
        int n = ModLoaderProfileLibraries.downloadMissing(
                downloads, config.getLibrariesDir(), json, "Rift", onProgress);
        return new PathStagingResult(staging, n);
    }

    private record PathStagingResult(java.nio.file.Path stagingDir, int libCount) {}
}
