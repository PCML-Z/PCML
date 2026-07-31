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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Java Agent「加载器」：为任意 MC 版本创建可注入 {@code -javaagent} 的配置。
 * <p>
 * 版本列表：
 * <ul>
 *   <li>{@code blank} — 仅创建 {@code agents/} 目录，自行放入 agent jar</li>
 *   <li>{@code nilloader-&lt;ver&gt;} — 预装 NilLoader 作为默认 agent</li>
 * </ul>
 */
public final class JavaAgentInstaller implements ModLoaderInstaller {

    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final VersionInstaller versionInstaller;
    private final NilLoaderInstaller nilLoader;

    public JavaAgentInstaller(LauncherConfig config, DownloadManager downloads,
                              VersionInstaller versionInstaller) {
        this.config = config;
        this.downloads = downloads;
        this.versionInstaller = versionInstaller;
        this.nilLoader = new NilLoaderInstaller(config, downloads, versionInstaller);
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return nilLoader.listVersions(gameVersion).thenApply(nilVers -> {
            List<ModLoaderVersion> out = new ArrayList<>();
            out.add(new ModLoaderVersion(ModLoader.JAVA_AGENT, gameVersion, "blank", true));
            for (ModLoaderVersion nv : nilVers) {
                out.add(new ModLoaderVersion(
                        ModLoader.JAVA_AGENT, gameVersion,
                        "nilloader-" + nv.getLoaderVersion(), nv.isStable()));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            String id = gameVersion + "-javaagent"
                    + (loaderVersion == null || loaderVersion.isBlank() || "blank".equals(loaderVersion)
                    ? "" : "-" + loaderVersion.replace(':', '_'));
            try {
                VersionStaging.assertSafeVersionId(id);
                ParentVersionSupport.ensureParentInstalled(
                        config, versionInstaller, gameVersion, onProgress);

                JsonObject profile = new JsonObject();
                profile.addProperty("id", id);
                profile.addProperty("inheritsFrom", gameVersion);
                profile.addProperty("type", "release");
                profile.add("libraries", new JsonArray());

                String nilVer = null;
                if (loaderVersion != null && loaderVersion.startsWith("nilloader-")) {
                    nilVer = loaderVersion.substring("nilloader-".length());
                }
                if (nilVer != null && !nilVer.isBlank()) {
                    if (onProgress != null) onProgress.accept(new InstallProgress(
                            InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                            "下载 NilLoader " + nilVer));
                    Path jar = config.getLibrariesDir().resolve(
                            AgentLaunchSupport.mavenPath("com.unascribed", "nilloader", nilVer));
                    ParentVersionSupport.downloadFirstOk(downloads, jar,
                            NilLoaderInstaller.REPO + AgentLaunchSupport.mavenPath(
                                    "com.unascribed", "nilloader", nilVer));
                    profile.add("pmclAgents", AgentLaunchSupport.singleAgentArray(
                            "com.unascribed:nilloader:" + nilVer, NilLoaderInstaller.REPO));
                }

                Path staging = VersionStaging.writeVersionJson(
                        config.getVersionsDir(), id, profile.toString());
                Files.createDirectories(staging.resolve("agents"));
                VersionStaging.promote(config.getVersionsDir(), id, staging);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1, "Java Agent 配置完成: " + id));
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
                            : new InstallInterruptedException("Java Agent 安装已中断", e);
                }
                throw new RuntimeException("Java Agent 安装失败: " + detail, e);
            }
        });
    }
}
