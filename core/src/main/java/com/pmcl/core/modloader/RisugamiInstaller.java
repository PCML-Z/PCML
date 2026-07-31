package com.pmcl.core.modloader;

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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Risugami's ModLoader 安装器：从 MCArchive 下载 ModLoader zip，合并进 client jar，并去掉 META-INF。
 */
public final class RisugamiInstaller implements ModLoaderInstaller {

    private static final String MCARCHIVE =
            "https://b2.mcarchive.net/file/mcarchive/";

    /** gameVersion → sha256（MCArchive） */
    private static final Map<String, String> SHA_BY_GAME = new LinkedHashMap<>();
    static {
        SHA_BY_GAME.put("1.6.2", "0b14f5e261c9862989aa74313b59188cce10bea6724bae31130ce1e8e6a1c060");
        SHA_BY_GAME.put("1.6.1", "95fc5afdd9cc14d85cb41225fb689d7994f5994287ed9595e192026c06e7b536");
        SHA_BY_GAME.put("1.5.2", "0c355696c2f3ba405bb1f0f845dc51a6613c121eac25a6c7bc9d8046f2c941df");
        SHA_BY_GAME.put("1.5.1", "af7d7bca70b8bc08c75e96ec90a25432682dfc825aa4fe35485dcb390b1f7014");
        SHA_BY_GAME.put("1.5", "597d4d437a250986da84a9c7aee3ea653739608caf1d4a208f2006d8cbdfbc3d");
        SHA_BY_GAME.put("1.4.7", "685ead73c19531cf24062c7536737663421ed4170cfa582baddbbf6cba1544d2");
        SHA_BY_GAME.put("1.4.6", "f69b1f99b76c23cc1e076197375996e3b79feb369952ac692630f7b063709d5f");
        SHA_BY_GAME.put("1.4.5", "885b62bde6231b04d0189a06b082edfa48ea1474f22a5502ab40288563036b42");
        SHA_BY_GAME.put("1.4.4", "7d39b6d5e41bcd77edabd0aca3b43a10861a65ee9c2f9b358cedf8382d69c14e");
        SHA_BY_GAME.put("1.4.2", "861324b55c40e4af622e2a987c3c20ed4eb869ea89a004c93222058e394baec4");
        SHA_BY_GAME.put("1.3.2", "01a28a0a3d05634ce8745d34738b0617ddb285ad1584fb668874892c61e489eb");
        SHA_BY_GAME.put("1.3.1", "511881d7432cf740b753180a645ca6abb7cd63d09813e0089485c125d52c09a0");
        SHA_BY_GAME.put("1.2.5", "219370a86a15bfef8ff91f51fdd151e99391b771759183b19f72197452a28b79");
        SHA_BY_GAME.put("1.0.0", "0abd012bcfd536522d50ac642080d6164cd6cdc22629386a0e8e1fafa2e7cd99");
        SHA_BY_GAME.put("1.1", "b56d925adc210773e4b2390f8189e16456910da4bcd4276b492bb382ea04f079");
        SHA_BY_GAME.put("b1.7.3", "78bc1107a2ae78334d1086c7f372601c141b53345f23ce73931ef318df5cf83e");
        SHA_BY_GAME.put("b1.8.1", "4135de0b0fddf6f9b39761a5261b82dae278b311237ec1cd936911b0b133919e");
        SHA_BY_GAME.put("b1.7.2", "2b4e0e19b817a464ef32042a12f3ba1d8e4db25a01a1bb19efe8a5d9713a003c");
        SHA_BY_GAME.put("b1.6.6", "15262e652abcf8b925909e867821575cf25a17bc8217dbc281a20ce166a3f6b9");
    }

    private final LauncherConfig config;
    private final DownloadManager downloads;
    private final VersionInstaller versionInstaller;

    public RisugamiInstaller(LauncherConfig config, DownloadManager downloads,
                             VersionInstaller versionInstaller) {
        this.config = config;
        this.downloads = downloads;
        this.versionInstaller = versionInstaller;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            List<ModLoaderVersion> out = new ArrayList<>();
            if (SHA_BY_GAME.containsKey(gameVersion)) {
                out.add(new ModLoaderVersion(ModLoader.RISUGAMI, gameVersion, gameVersion, true));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            String id = gameVersion + "-ModLoader";
            try {
                String sha = SHA_BY_GAME.get(gameVersion);
                if (sha == null) {
                    throw new IllegalArgumentException(
                            "Risugami's ModLoader 无此游戏版本的归档: " + gameVersion);
                }
                VersionStaging.assertSafeVersionId(id);
                ParentVersionSupport.ensureParentInstalled(
                        config, versionInstaller, gameVersion, onProgress);

                Path parentDir = config.getVersionsDir().resolve(gameVersion);
                Path parentJar = parentDir.resolve(gameVersion + ".jar");
                Path parentJson = parentDir.resolve(gameVersion + ".json");
                if (!Files.isRegularFile(parentJar) || !Files.isRegularFile(parentJson)) {
                    throw new IOException("原版文件缺失: " + gameVersion);
                }

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                        "下载 ModLoader " + gameVersion));
                Path zip = config.getWorkDir().resolve("cache")
                        .resolve("modloader-" + gameVersion + ".zip");
                String fileName = "ModLoader%20" + gameVersion.replace(" ", "%20") + ".zip";
                // b1.7.3 等文件名带大写 B
                String altName = "ModLoader%20" + capitalizeBeta(gameVersion) + ".zip";
                ParentVersionSupport.downloadFirstOk(downloads, zip,
                        MCARCHIVE + sha + "/" + fileName,
                        MCARCHIVE + sha + "/" + altName,
                        MCARCHIVE + sha + "/ModLoader%20" + gameVersion + ".zip");

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "合并 ModLoader 到 client jar"));

                Path staging = VersionStaging.stagingDir(config.getVersionsDir(), id);
                Files.createDirectories(staging);
                Path outJar = staging.resolve(id + ".jar");
                mergeJarmod(parentJar, zip, outJar);

                String jsonText = Files.readString(parentJson, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
                root.addProperty("id", id);
                // 独立 jar，去掉继承以免再用原版 jar
                root.remove("inheritsFrom");
                if (root.has("downloads")) {
                    root.getAsJsonObject("downloads").remove("client");
                }
                Files.writeString(staging.resolve(id + ".json"), root.toString(), StandardCharsets.UTF_8);
                VersionStaging.promote(config.getVersionsDir(), id, staging);

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1, "ModLoader 安装完成: " + id));
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
                            : new InstallInterruptedException("ModLoader 安装已中断", e);
                }
                throw new RuntimeException("ModLoader 安装失败: " + detail, e);
            }
        });
    }

    private static String capitalizeBeta(String gameVersion) {
        if (gameVersion.startsWith("b") || gameVersion.startsWith("a")) {
            return gameVersion.substring(0, 1).toUpperCase(Locale.ROOT) + gameVersion.substring(1);
        }
        return gameVersion;
    }

    /**
     * 将 ModLoader zip 中的 class 合并进 client jar，并删除 META-INF 签名文件。
     */
    static void mergeJarmod(Path clientJar, Path modloaderZip, Path outJar) throws IOException {
        Path tmp = outJar.resolveSibling(outJar.getFileName() + ".tmp");
        Files.createDirectories(outJar.getParent());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile base = new ZipFile(clientJar.toFile())) {
            Enumeration<? extends ZipEntry> en = base.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.startsWith("META-INF/") || name.equals("META-INF")) continue;
                try (InputStream in = base.getInputStream(e)) {
                    entries.put(name, in.readAllBytes());
                }
            }
        }
        try (InputStream fin = Files.newInputStream(modloaderZip);
             ZipInputStream zis = new ZipInputStream(fin)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.startsWith("META-INF/") || name.equals("META-INF")) continue;
                entries.put(name, zis.readAllBytes());
                zis.closeEntry();
            }
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Files.move(tmp, outJar, StandardCopyOption.REPLACE_EXISTING);
    }
}
