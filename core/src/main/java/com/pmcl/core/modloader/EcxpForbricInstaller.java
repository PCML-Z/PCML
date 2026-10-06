package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.i18n.I18n;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.launch.JavaRuntimeFinder;
import com.pmcl.core.util.Exceptions;
import com.pmcl.core.util.SsrfChecker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * ECXP-Forbric+ 安装器。
 * <p>
 * PMCL 团队基于 Forbric 的增强版，安装包来自
 * <a href="https://github.com/PCML-Z/ECXP-Forbric-">PCML-Z/ECXP-Forbric-</a>。
 * 命令和 Forbric 一样：{@code java -jar --dir <游戏目录> --mc <版本> --release <tag>}。
 * 安装器直接写成 {@code <版本>-ecxp-forbric}。库坐标仍是 {@code net.forbric}，
 * 所以这里把它们复制到带 {@code -ecxp} 的路径上，再把原来的 Forbric 库放回去。
 * Minecraft、Forge、NeoForge 仍由安装器在用户机器上下载并构建，这里不携带这些文件。
 */
public final class EcxpForbricInstaller implements ModLoaderInstaller {

    /** 这个增强版目前能装的 Minecraft 版本，和 0.11 安装器里的 pin 一致。 */
    public static final List<String> MINECRAFT_VERSIONS = List.of("26.2");
    static final String RELEASES =
            "https://api.github.com/repos/PCML-Z/ECXP-Forbric-/releases?per_page=20";
    private static final String DISPLAY = "ECXP-Forbric+";
    private static final long MAX_JAR_BYTES = 64L * 1024 * 1024;

    private final LauncherConfig config;
    private final DownloadManager downloads;

    public EcxpForbricInstaller(LauncherConfig config, DownloadManager downloads) {
        this.config = config;
        this.downloads = downloads;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            String mc = canonicalGame(gameVersion);
            if (mc == null) return List.of();
            try {
                List<ForbricInstaller.InstallerAsset> assets =
                        ForbricInstaller.selectInstallers(downloads.downloadString(RELEASES));
                List<ModLoaderVersion> out = new ArrayList<>();
                for (ForbricInstaller.InstallerAsset asset : assets) {
                    out.add(new ModLoaderVersion(
                            ModLoader.ECXP_FORBRIC, mc, asset.version, asset.stable));
                }
                return out;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> install(String gameVersion, String loaderVersion,
                                           Consumer<InstallProgress> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                installSync(gameVersion, loaderVersion, onProgress);
            } catch (Exception e) {
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new InstallInterruptedException(DISPLAY + " 安装已中断", e);
                }
                String detail = Exceptions.rootMessage(e);
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, detail));
                throw new RuntimeException(I18n.t("download.ecxp_forbric_failed", detail), e);
            }
        });
    }

    private void installSync(String gameVersion, String loaderVersion,
                             Consumer<InstallProgress> onProgress) throws IOException {
        String mc = canonicalGame(gameVersion);
        if (mc == null) {
            throw new IOException(I18n.t("download.ecxp_forbric_unsupported",
                    loaderVersion == null ? "" : loaderVersion));
        }
        String wanted = loaderVersion == null ? "" : loaderVersion.trim();
        if (wanted.startsWith("v") || wanted.startsWith("V")) wanted = wanted.substring(1);
        if (!ForbricInstaller.safeToken(wanted)) {
            throw new IOException(I18n.t("download.ecxp_forbric_no_jar"));
        }
        ForbricInstaller.InstallerAsset asset = null;
        for (ForbricInstaller.InstallerAsset candidate :
                ForbricInstaller.selectInstallers(downloads.downloadString(RELEASES))) {
            if (wanted.equals(candidate.version)) {
                asset = candidate;
                break;
            }
        }
        if (asset == null) throw new IOException(I18n.t("download.ecxp_forbric_no_jar"));

        String blocked = SsrfChecker.validate(asset.url);
        if (blocked != null) throw new IOException(blocked);

        Path cache = config.getWorkDir().resolve("cache").resolve("ecxp-forbric");
        Files.createDirectories(cache);
        Path jar = cache.resolve("forbric-kernel-installer-" + asset.version + ".jar");
        boolean reused = Files.isRegularFile(jar) && asset.sha256 != null
                && asset.sha256.equalsIgnoreCase(ForbricInstaller.sha256(jar));
        if (!reused) {
            long total = asset.size > 0 ? asset.size : 0;
            ForbricInstaller.InstallerAsset downloading = asset;
            downloads.downloadTo(asset.url, jar, completed -> {
                if (completed > MAX_JAR_BYTES) {
                    throw new UncheckedIOException(new IOException(I18n.t(
                            "download.ecxp_forbric_failed", downloading.version)));
                }
                long shownTotal = total > 0 ? total : Math.max(completed, 1);
                report(onProgress, InstallProgress.Stage.DOWNLOAD_LIBRARIES, completed, shownTotal,
                        I18n.t("download.ecxp_forbric_download", downloading.version));
            });
        }
        if (asset.sha256 != null && !asset.sha256.equalsIgnoreCase(ForbricInstaller.sha256(jar))) {
            Files.deleteIfExists(jar);
            throw new IOException(I18n.t("download.ecxp_forbric_bad_hash"));
        }
        if (!ForbricInstaller.looksLikeJar(jar)) {
            Files.deleteIfExists(jar);
            throw new IOException(I18n.t("download.ecxp_forbric_no_jar"));
        }

        String java = JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir(), 21);
        if (java == null || java.isBlank()) {
            throw new IOException(I18n.t("download.ecxp_forbric_java"));
        }
        if (ForbricInstaller.installerAlreadyRunning()) {
            throw new IOException(I18n.t("download.ecxp_forbric_busy"));
        }

        Path gameDir = config.getWorkDir();
        Path versions = config.getVersionsDir();
        Path libraries = config.getLibrariesDir();
        Files.createDirectories(gameDir);
        String id = versionId(mc);
        Path snapshot = cache.resolve("lib-snapshot");
        boolean isolated = false;
        try {
            snapshotForbricLibraries(libraries.resolve("net").resolve("forbric"), snapshot);
            List<String> command = ForbricInstaller.command(java, jar, gameDir, mc, asset.tag);
            report(onProgress, InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 0,
                    I18n.t("download.ecxp_forbric_install", asset.version));
            Path log = gameDir.resolve("logs").resolve("ecxp-forbric-" + asset.version + ".log");
            ForbricInstaller.runProcess(command, gameDir, log, "ecxp-forbric-installer", DISPLAY,
                    I18n.t("download.ecxp_forbric_timeout"),
                    I18n.t("download.ecxp_forbric_install", asset.version),
                    Files.size(jar), onProgress);
            Path written = versions.resolve(id).resolve(id + ".json");
            if (Files.isRegularFile(written)) {
                isolateForbricLibraries(libraries, written);
            } else if (Files.isRegularFile(versions.resolve(ForbricInstaller.versionId(mc))
                    .resolve(ForbricInstaller.versionId(mc) + ".json"))) {
                // 安装器改后缀之前，会先写成 <mc>-forbric。
                isolateForbricLibraries(libraries,
                        versions.resolve(ForbricInstaller.versionId(mc))
                                .resolve(ForbricInstaller.versionId(mc) + ".json"));
                adoptProfile(versions, mc);
            } else {
                throw new IOException(I18n.t("download.ecxp_forbric_missing_version", id));
            }
            isolated = true;
        } finally {
            try {
                restoreSnapshot(snapshot, libraries.resolve("net").resolve("forbric"));
            } catch (IOException restoreError) {
                if (isolated) throw restoreError;
            }
        }
        Path json = versions.resolve(id).resolve(id + ".json");
        if (!Files.isRegularFile(json)) {
            throw new IOException(I18n.t("download.ecxp_forbric_missing_version", id));
        }
        report(onProgress, InstallProgress.Stage.DONE, 1, 1,
                I18n.t("download.ecxp_forbric_done", id));
    }

    /**
     * 空版本按 26.2 列出。不在支持列表里时返回 null。
     */
    public static String canonicalGame(String gameVersion) {
        if (gameVersion == null || gameVersion.isBlank()) return MINECRAFT_VERSIONS.get(0);
        String mc = gameVersion.trim();
        return MINECRAFT_VERSIONS.contains(mc) ? mc : null;
    }

    public static boolean supportsGame(String gameVersion) {
        return canonicalGame(gameVersion) != null;
    }

    public static String versionId(String minecraft) {
        return minecraft + "-ecxp-forbric";
    }

    /**
     * 把安装器刚写下的 {@code <mc>-forbric} 改名为 {@code <mc>-ecxp-forbric}。
     * 调用前，原来的 Forbric 目录必须已经挪走。
     */
    static void adoptProfile(Path versionsDir, String minecraft) throws IOException {
        String stockId = ForbricInstaller.versionId(minecraft);
        String id = versionId(minecraft);
        Path stock = versionsDir.resolve(stockId);
        Path json = stock.resolve(stockId + ".json");
        if (!Files.isRegularFile(json)) {
            throw new IOException(I18n.t("download.ecxp_forbric_missing_version", id));
        }
        JsonObject root = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
        root.addProperty("id", id);
        Path renamed = stock.resolve(id + ".json");
        Files.writeString(renamed, root.toString(), StandardCharsets.UTF_8);
        Files.deleteIfExists(json);
        Path target = versionsDir.resolve(id);
        deleteTree(target);
        Files.move(stock, target);
    }

    /**
     * 安装器把游戏库写进和 Forbric 相同的 {@code net.forbric} 坐标。
     * 复制到版本号带 {@code -ecxp} 的路径，并改版本 JSON 里的库和启动参数。
     */
    static void isolateForbricLibraries(Path librariesDir, Path profileJson) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(profileJson, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> pathRewrites = new LinkedHashMap<>();
        if (root.has("libraries") && root.get("libraries").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("libraries")) {
                if (!element.isJsonObject()) continue;
                JsonObject lib = element.getAsJsonObject();
                String name = text(lib, "name");
                if (!name.startsWith("net.forbric:")) continue;
                JsonObject artifact = artifact(lib);
                if (artifact == null) continue;
                String path = text(artifact, "path");
                String newPath = suffixLibraryPath(path);
                String newName = suffixCoordinate(name);
                if (newPath == null || newName == null || newPath.equals(path)) continue;
                Path src = librariesDir.resolve(path);
                if (!Files.isRegularFile(src)) {
                    throw new IOException(path);
                }
                Path dest = librariesDir.resolve(newPath);
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                lib.addProperty("name", newName);
                artifact.addProperty("path", newPath);
                pathRewrites.put(path, newPath);
            }
        }
        if (root.has("arguments") && root.get("arguments").isJsonObject()) {
            JsonObject arguments = root.getAsJsonObject("arguments");
            rewriteArray(arguments.get("game"), pathRewrites);
            rewriteArray(arguments.get("jvm"), pathRewrites);
        }
        Files.writeString(profileJson, root.toString(), StandardCharsets.UTF_8);
    }

    static String suffixCoordinate(String name) {
        if (name == null || name.isBlank()) return null;
        String[] parts = name.split(":");
        if (parts.length < 3 || parts[2].isEmpty()) return null;
        if (parts[2].endsWith("-ecxp")) return name;
        parts[2] = parts[2] + "-ecxp";
        return String.join(":", parts);
    }

    static String suffixLibraryPath(String path) {
        if (path == null || path.isBlank()) return null;
        int slash = path.lastIndexOf('/');
        if (slash <= 0 || slash == path.length() - 1) return null;
        String file = path.substring(slash + 1);
        String dir = path.substring(0, slash);
        int versionSlash = dir.lastIndexOf('/');
        if (versionSlash < 0) return null;
        String version = dir.substring(versionSlash + 1);
        if (version.isEmpty() || version.endsWith("-ecxp")) return path;
        String newVersion = version + "-ecxp";
        String newFile = file.contains(version) ? file.replace(version, newVersion) : file;
        return dir.substring(0, versionSlash + 1) + newVersion + "/" + newFile;
    }

    static void snapshotForbricLibraries(Path source, Path snapshotDir) throws IOException {
        deleteTree(snapshotDir);
        if (source == null || !Files.isDirectory(source)) return;
        copyTree(source, snapshotDir);
    }

    static void restoreSnapshot(Path snapshotDir, Path dest) throws IOException {
        if (snapshotDir == null || !Files.isDirectory(snapshotDir)) return;
        copyTree(snapshotDir, dest);
    }

    private static void rewriteArray(JsonElement element, Map<String, String> pathRewrites) {
        if (element == null || !element.isJsonArray() || pathRewrites.isEmpty()) return;
        List<Map.Entry<String, String>> ordered = new ArrayList<>(pathRewrites.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) continue;
            String value = item.getAsString();
            String updated = value;
            for (Map.Entry<String, String> rewrite : ordered) {
                updated = updated.replace(rewrite.getKey(), rewrite.getValue());
            }
            if (!updated.equals(value)) array.set(i, new JsonPrimitive(updated));
        }
    }

    private static JsonObject artifact(JsonObject lib) {
        if (!lib.has("downloads") || !lib.get("downloads").isJsonObject()) return null;
        JsonObject downloads = lib.getAsJsonObject("downloads");
        if (!downloads.has("artifact") || !downloads.get("artifact").isJsonObject()) return null;
        return downloads.getAsJsonObject("artifact");
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static void copyTree(Path source, Path dest) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path target = dest.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void report(Consumer<InstallProgress> onProgress, InstallProgress.Stage stage,
                               long completed, long total, String message) {
        if (onProgress != null) onProgress.accept(new InstallProgress(stage, completed, total, message));
    }
}
