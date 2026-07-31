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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NilLoader 安装器（Java Agent）。
 * <p>
 * 版本清单优先拉取 {@code https://repo.sleeping.town/.../maven-metadata.xml}，
 * 失败时回退内置版本列表。安装后写入带 {@code pmclAgents} 的继承版 JSON。
 */
public final class NilLoaderInstaller implements ModLoaderInstaller {

    static final String REPO = "https://repo.sleeping.town/";
    private static final String META =
            REPO + "com/unascribed/nilloader/maven-metadata.xml";
    private static final List<String> FALLBACK_VERSIONS = Collections.unmodifiableList(Arrays.asList(
            "1.3.6", "1.3.5", "1.3.4", "1.3.3", "1.3.2", "1.3.1", "1.3.0",
            "1.2.2", "1.2.1", "1.2.0",
            "1.1.6", "1.1.5", "1.1.4", "1.1.3", "1.1.2", "1.1.1", "1.1",
            "1.0.3", "1.0.2", "1.0.1", "1.0"
    ));
    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final VersionInstaller versionInstaller;

    public NilLoaderInstaller(LauncherConfig config, DownloadManager downloads,
                              VersionInstaller versionInstaller) {
        this.config = config;
        this.downloads = downloads;
        this.versionInstaller = versionInstaller;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> vers = fetchVersions();
            List<ModLoaderVersion> out = new ArrayList<>();
            for (int i = 0; i < vers.size(); i++) {
                out.add(new ModLoaderVersion(ModLoader.NILLOADER, gameVersion, vers.get(i), i == 0));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            String id = gameVersion + "-nilloader-" + loaderVersion;
            try {
                VersionStaging.assertSafeVersionId(id);
                ParentVersionSupport.ensureParentInstalled(
                        config, versionInstaller, gameVersion, onProgress);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                        "下载 NilLoader " + loaderVersion));
                Path jar = config.getLibrariesDir().resolve(
                        AgentLaunchSupport.mavenPath("com.unascribed", "nilloader", loaderVersion));
                ParentVersionSupport.downloadFirstOk(downloads, jar,
                        REPO + AgentLaunchSupport.mavenPath("com.unascribed", "nilloader", loaderVersion));

                JsonObject profile = new JsonObject();
                profile.addProperty("id", id);
                profile.addProperty("inheritsFrom", gameVersion);
                profile.addProperty("type", "release");
                profile.add("pmclAgents", AgentLaunchSupport.singleAgentArray(
                        "com.unascribed:nilloader:" + loaderVersion, REPO));
                // 空 libraries，保留继承
                profile.add("libraries", new JsonArray());

                Path staging = VersionStaging.writeVersionJson(
                        config.getVersionsDir(), id, profile.toString());
                Files.createDirectories(staging.resolve("agents"));
                Files.createDirectories(staging.resolve("nilmods"));
                VersionStaging.promote(config.getVersionsDir(), id, staging);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1, "NilLoader 安装完成: " + id));
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
                            : new InstallInterruptedException("NilLoader 安装已中断", e);
                }
                throw new RuntimeException("NilLoader 安装失败: " + detail, e);
            }
        });
    }

    private List<String> fetchVersions() {
        try {
            String xml = downloads.downloadString(META);
            List<String> found = new ArrayList<>();
            Matcher m = VERSION_TAG.matcher(xml);
            while (m.find()) {
                String v = m.group(1).trim();
                if (!v.isEmpty() && !found.contains(v)) found.add(v);
            }
            if (!found.isEmpty()) {
                // maven-metadata 通常旧→新；倒序使最新在前
                Collections.reverse(found);
                return found;
            }
        } catch (Throwable ignored) {}
        return new ArrayList<>(FALLBACK_VERSIONS);
    }
}
