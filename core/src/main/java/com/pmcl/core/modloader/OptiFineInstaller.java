package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionStaging;
import com.pmcl.core.launch.JavaRuntimeFinder;
import com.pmcl.core.util.Exceptions;
import com.pmcl.core.util.FileUtils;
import com.pmcl.core.util.SafeZipExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * OptiFine 安装器。
 * <p>
 * BMCLAPI 曾提供预构建版本 JSON（{@code /optifine/{mc}/{type}/{patch}/json}），
 * 该端点现已普遍 404。正确流程与 HMCL / xmcl 一致：
 * <ol>
 *   <li>拉取版本列表：{@code /optifine/{gameVersion}}</li>
 *   <li>下载 OptiFine 安装包（jar）</li>
 *   <li>执行 {@code optifine.Patcher}，把原版 client.jar 与安装包合成库 jar</li>
 *   <li>本地构造 inheritsFrom 版本 JSON（LaunchWrapper + tweakClass）</li>
 * </ol>
 * loaderVersion 编码：{@code type|patch[|forge]}（列表接口写入）。
 */
public final class OptiFineInstaller implements ModLoaderInstaller {

    private static final String BMCLAPI_OPTIFINE = "https://bmclapi2.bangbang93.com/optifine/";
    private static final String BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/";
    private static final String MOJANG_MAVEN = "https://libraries.minecraft.net/";

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
                String json = downloads.downloadString(BMCLAPI_OPTIFINE + gameVersion);
                JsonArray arr = parseJsonArray(json, "OptiFine list " + gameVersion);
                List<ModLoaderVersion> result = new ArrayList<>();
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String type = o.has("type") && !o.get("type").isJsonNull()
                            ? o.get("type").getAsString() : "";
                    String patch = o.has("patch") && !o.get("patch").isJsonNull()
                            ? o.get("patch").getAsString() : "";
                    boolean needsForge = o.has("_forge") && !o.get("_forge").isJsonNull()
                            && o.get("_forge").getAsBoolean();
                    if (type.isEmpty() || patch.isEmpty()) continue;
                    String encoded = type + "|" + patch + (needsForge ? "|forge" : "");
                    result.add(new ModLoaderVersion(
                            ModLoader.OPTIFINE,
                            gameVersion,
                            encoded,
                            !needsForge
                    ));
                }
                return result;
            } catch (Throwable ex) {
                throw new RuntimeException("拉取 OptiFine 版本失败: " + Exceptions.rootMessage(ex), ex);
            }
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            Path installerJar = null;
            Path optifineLib = null;
            Path launchWrapperOfTarget = null;
            String versionId = null;
            boolean promoted = false;
            try {
                String[] parts = loaderVersion.split("\\|");
                if (parts.length < 2) {
                    throw new IOException("无效的 OptiFine 版本标识: " + loaderVersion);
                }
                String type = parts[0];
                String patch = parts[1];
                boolean useForgeTweaker = parts.length >= 3 && "forge".equalsIgnoreCase(parts[2]);
                String editionRelease = type + "_" + patch;
                String optifineCoords = "optifine:Optifine:" + gameVersion + "_" + editionRelease;
                versionId = gameVersion + "-OptiFine_" + editionRelease;
                String filename = "OptiFine_" + gameVersion + "_" + type + "_" + patch + ".jar";

                // 1. 下载 OptiFine 安装包
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "下载 OptiFine 安装包"));
                installerJar = Files.createTempFile("optifine-installer-", ".jar");
                downloadInstaller(gameVersion, type, patch, filename, installerJar);

                // 2. 确认原版 client.jar（Patcher 输入）
                Path clientJar = config.getVersionsDir()
                        .resolve(gameVersion).resolve(gameVersion + ".jar");
                if (!Files.isRegularFile(clientJar)) {
                    throw new IOException("找不到原版客户端 jar，请先安装 Minecraft "
                            + gameVersion + "（期望路径: " + clientJar + "）");
                }

                // 3. 运行 Patcher，生成 libraries 下的 OptiFine 库
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                        "运行 OptiFine Patcher"));
                optifineLib = config.getLibrariesDir().resolve(
                        "optifine/Optifine/" + gameVersion + "_" + editionRelease
                                + "/Optifine-" + gameVersion + "_" + editionRelease + ".jar");
                Files.createDirectories(optifineLib.getParent());
                runPatcher(installerJar, clientJar, optifineLib);

                // 4. 处理 LaunchWrapper（内嵌 launchwrapper-of 或官方 1.12）
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 1,
                        "准备 LaunchWrapper"));
                String launchWrapperOf = readZipText(installerJar, "launchwrapper-of.txt");
                String launchWrapperName;
                if (launchWrapperOf != null && !launchWrapperOf.isBlank()) {
                    launchWrapperOf = launchWrapperOf.trim();
                    launchWrapperName = "optifine:launchwrapper-of:" + launchWrapperOf;
                    launchWrapperOfTarget = config.getLibrariesDir().resolve(
                            "optifine/launchwrapper-of/" + launchWrapperOf
                                    + "/launchwrapper-of-" + launchWrapperOf + ".jar");
                    extractEmbeddedJar(installerJar,
                            "launchwrapper-of-" + launchWrapperOf + ".jar",
                            launchWrapperOfTarget);
                } else {
                    launchWrapperName = "net.minecraft:launchwrapper:1.12";
                    ensureLaunchWrapper112();
                }

                // 5. 构造并写入 staging，再原子提升
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "写入 OptiFine 版本 JSON"));
                JsonObject versionJson = buildVersionJson(
                        versionId, gameVersion, optifineCoords, launchWrapperName, useForgeTweaker);
                Path staging = VersionStaging.writeVersionJson(
                        config.getVersionsDir(), versionId, versionJson.toString());
                VersionStaging.promote(config.getVersionsDir(), versionId, staging);
                promoted = true;

                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        "OptiFine 安装完成: " + versionId));
            } catch (Exception e) {
                if (!InstallInterruptedException.isInterrupted(e)) {
                    if (versionId != null) {
                        VersionStaging.discard(config.getVersionsDir(), versionId);
                        if (promoted) {
                            FileUtils.deleteRecursively(
                                    config.getVersionsDir().resolve(versionId));
                        }
                    }
                    if (optifineLib != null) FileUtils.deleteRecursively(optifineLib);
                    if (launchWrapperOfTarget != null) {
                        FileUtils.deleteRecursively(launchWrapperOfTarget);
                    }
                }
                String detail = Exceptions.rootMessage(e);
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, detail));
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new InstallInterruptedException("OptiFine 安装已中断", e);
                }
                throw new RuntimeException("OptiFine 安装失败: " + detail, e);
            } finally {
                if (installerJar != null) {
                    try { Files.deleteIfExists(installerJar); } catch (IOException ignored) {}
                }
            }
        });
    }

    /** 多源下载 OptiFine 安装包（优先 SHA-1 旁路校验）。 */
    private void downloadInstaller(String gameVersion, String type, String patch,
                                   String filename, Path target) throws IOException {
        List<String> urls = new ArrayList<>();
        urls.add(BMCLAPI_OPTIFINE + gameVersion + "/" + type + "/" + patch);
        urls.add(BMCLAPI_MAVEN + "com/optifine/" + gameVersion + "/" + filename);
        urls.add(BMCLAPI_OPTIFINE + "download/" + filename);

        Exception last = null;
        for (String url : urls) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InstallInterruptedException("OptiFine 安装包下载已中断");
            }
            try {
                String sha1 = tryDownloadSha1(url + ".sha1");
                if (sha1 != null && !sha1.isBlank()) {
                    downloads.downloadToVerified(url, target, sha1, null);
                    return;
                }
                downloads.downloadTo(url, target);
                if (Files.size(target) > 1024 && looksLikeZip(target)) return;
                Files.deleteIfExists(target);
                throw new IOException("下载内容无效（过小或非 zip）");
            } catch (Exception e) {
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new InstallInterruptedException("OptiFine 安装包下载已中断", e);
                }
                last = e;
            }
        }
        throw new IOException("OptiFine 安装包下载失败，已尝试 " + urls.size() + " 个源"
                + (last != null ? (" — " + Exceptions.rootMessage(last)) : ""), last);
    }

    private String tryDownloadSha1(String sha1Url) {
        try {
            String body = downloads.downloadString(sha1Url).trim();
            if (body.isEmpty()) return null;
            String hash = body.split("\\s+")[0].trim();
            return hash.matches("[0-9a-fA-F]{40}") ? hash : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 执行 {@code java -cp installer optifine.Patcher <mcJar> <installer> <out>}。 */
    private void runPatcher(Path installerJar, Path clientJar, Path outJar) throws IOException {
        String java = JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir());
        if (java == null || java.isBlank()) {
            throw new IOException("找不到可用的 Java，无法运行 OptiFine Patcher");
        }
        Path tmpOut = outJar.resolveSibling(outJar.getFileName() + ".patching");
        Files.deleteIfExists(tmpOut);
        ProcessBuilder pb = new ProcessBuilder(
                java, "-cp", installerJar.toAbsolutePath().toString(),
                "optifine.Patcher",
                clientJar.toAbsolutePath().toString(),
                installerJar.toAbsolutePath().toString(),
                tmpOut.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        Thread drainer = new Thread(() -> {
            try (InputStream in = p.getInputStream()) { in.transferTo(bos); }
            catch (IOException ignored) {}
        });
        drainer.setDaemon(true);
        drainer.start();
        boolean finished;
        try {
            finished = p.waitFor(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            try { drainer.join(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            throw new InstallInterruptedException("OptiFine Patcher 被中断", e);
        }
        if (!finished) {
            p.destroyForcibly();
            try { drainer.join(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            throw new IOException("OptiFine Patcher 超时");
        }
        try { drainer.join(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String output = bos.toString(StandardCharsets.UTF_8);
        if (p.exitValue() != 0 || !Files.isRegularFile(tmpOut) || Files.size(tmpOut) < 1024) {
            String preview = output == null ? "" : output.trim();
            if (preview.length() > 500) preview = preview.substring(preview.length() - 500);
            throw new IOException("OptiFine Patcher 失败 (exit=" + p.exitValue() + ")"
                    + (preview.isEmpty() ? "" : (": " + preview)));
        }
        try {
            Files.move(tmpOut, outJar, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmpOut, outJar, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 下载官方 LaunchWrapper 1.12（旧版 OptiFine 依赖）。 */
    private void ensureLaunchWrapper112() throws IOException {
        Path target = config.getLibrariesDir().resolve(
                "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar");
        if (Files.isRegularFile(target) && Files.size(target) > 1024 && looksLikeZip(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        List<String> urls = List.of(
                BMCLAPI_MAVEN + "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
                MOJANG_MAVEN + "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"
        );
        Exception last = null;
        for (String url : urls) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InstallInterruptedException("LaunchWrapper 下载已中断");
            }
            try {
                String sha1 = tryDownloadSha1(url + ".sha1");
                if (sha1 != null && !sha1.isBlank()) {
                    downloads.downloadToVerified(url, target, sha1, null);
                    return;
                }
                downloads.downloadTo(url, target);
                if (Files.size(target) > 1024 && looksLikeZip(target)) return;
                Files.deleteIfExists(target);
                throw new IOException("下载内容无效");
            } catch (Exception e) {
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new InstallInterruptedException("LaunchWrapper 下载已中断", e);
                }
                last = e;
            }
        }
        throw new IOException("LaunchWrapper 1.12 下载失败"
                + (last != null ? (" — " + Exceptions.rootMessage(last)) : ""), last);
    }

    private JsonObject buildVersionJson(String versionId, String gameVersion,
                                        String optifineCoords, String launchWrapperName,
                                        boolean useForgeTweaker) {
        JsonObject versionJson = new JsonObject();
        versionJson.addProperty("id", versionId);
        versionJson.addProperty("inheritsFrom", gameVersion);
        versionJson.addProperty("mainClass", "net.minecraft.launchwrapper.Launch");
        versionJson.addProperty("type", "release");
        versionJson.addProperty("time", java.time.Instant.now().toString());
        versionJson.addProperty("releaseTime", java.time.Instant.now().toString());
        versionJson.addProperty("minimumLauncherVersion", 21);

        String tweakClass = useForgeTweaker
                ? "optifine.OptiFineForgeTweaker"
                : "optifine.OptiFineTweaker";

        JsonObject parent = readParentVersionJson(gameVersion);
        boolean parentUsesArguments = parent != null && parent.has("arguments");
        if (parentUsesArguments || looksLikeModernArgumentsVersion(gameVersion)) {
            JsonObject arguments = new JsonObject();
            JsonArray game = new JsonArray();
            game.add("--tweakClass");
            game.add(tweakClass);
            arguments.add("game", game);
            versionJson.add("arguments", arguments);
        } else {
            String mcArgs = resolveMinecraftArguments(parent);
            versionJson.addProperty("minecraftArguments", mcArgs + " --tweakClass " + tweakClass);
        }

        JsonArray libraries = new JsonArray();
        JsonObject lw = new JsonObject();
        lw.addProperty("name", launchWrapperName);
        if (launchWrapperName.startsWith("net.minecraft:launchwrapper:")) {
            lw.addProperty("url", BMCLAPI_MAVEN);
        }
        libraries.add(lw);

        JsonObject of = new JsonObject();
        of.addProperty("name", optifineCoords);
        libraries.add(of);

        versionJson.add("libraries", libraries);
        return versionJson;
    }

    private static boolean looksLikeModernArgumentsVersion(String gameVersion) {
        try {
            String[] segs = gameVersion.split("\\.");
            if (segs.length >= 2) {
                int major = Integer.parseInt(segs[0]);
                int minor = Integer.parseInt(segs[1].replaceAll("[^0-9].*$", ""));
                return major > 1 || (major == 1 && minor >= 13);
            }
        } catch (Exception ignored) {}
        return true;
    }

    private JsonObject readParentVersionJson(String gameVersion) {
        Path parentJson = config.getVersionsDir().resolve(gameVersion).resolve(gameVersion + ".json");
        if (!Files.isRegularFile(parentJson)) return null;
        try {
            String content = Files.readString(parentJson, StandardCharsets.UTF_8);
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveMinecraftArguments(JsonObject parent) {
        if (parent != null && parent.has("minecraftArguments")
                && !parent.get("minecraftArguments").isJsonNull()) {
            return parent.get("minecraftArguments").getAsString();
        }
        return "--username ${auth_name} --version ${version_name} --gameDir ${game_directory} "
                + "--assetsDir ${assets_root} --assetIndex ${assets_index_name} "
                + "--uuid ${auth_uuid} --accessToken ${auth_access_token} "
                + "--userProperties ${user_properties} --userType ${user_type}";
    }

    private static String readZipText(Path zipPath, String entryName) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(SafeZipExtractor.readLimited(in, 64 * 1024), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void extractEmbeddedJar(Path zipPath, String entryName, Path target)
            throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("OptiFine 安装包内缺少 " + entryName);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                SafeZipExtractor.copyLimited(in, target, SafeZipExtractor.DEFAULT_MAX_ENTRY_SIZE);
            }
        }
    }

    private static boolean looksLikeZip(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(2);
            return magic.length >= 2 && magic[0] == 'P' && magic[1] == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private static JsonArray parseJsonArray(String json, String context) throws IOException {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.isEmpty()) {
            throw new IOException("服务器返回空响应: " + context);
        }
        char first = trimmed.charAt(0);
        if (first != '[' && first != '{') {
            String preview = trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            throw new IOException("服务器返回非 JSON 内容（可能为错误页面）: " + context
                    + "\n响应内容: " + preview);
        }
        try {
            return JsonParser.parseString(trimmed).getAsJsonArray();
        } catch (Exception e) {
            String preview = trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            throw new IOException("JSON 解析失败: " + context + "\n错误: " + e.getMessage()
                    + "\n响应内容: " + preview);
        }
    }
}
