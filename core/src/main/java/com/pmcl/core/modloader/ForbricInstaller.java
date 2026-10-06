package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.i18n.I18n;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.launch.JavaRuntimeFinder;
import com.pmcl.core.util.Exceptions;
import com.pmcl.core.util.SsrfChecker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Forbric 安装器。
 * <p>
 * 下载官方 {@code forbric-kernel-installer-*.jar}，再用
 * {@code java -jar --dir <游戏目录> --mc 26.2 --release <tag>} 无界面安装。
 * Minecraft、Forge、NeoForge 由该安装器在用户机器上下载并构建，这里不携带这些文件。
 * 装完后的版本号是 {@code 26.2-forbric}，走原来的 Java 版本启动。
 */
public final class ForbricInstaller implements ModLoaderInstaller {

    /** 这一代 Forbric 唯一支持的 Minecraft 版本。 */
    public static final String MINECRAFT = "26.2";
    static final String RELEASES =
            "https://api.github.com/repos/Ray-T-r/Minecraft-Forbric-mod-loader/releases?per_page=20";
    private static final long MAX_JAR_BYTES = 64L * 1024 * 1024;
    private static final long INSTALL_TIMEOUT_MINUTES = 45;
    private static final Pattern FILE_PROGRESS = Pattern.compile(
            "(\\S+)\\s+(\\d+)%\\s+([0-9.]+)\\s*([KMGT]?B)\\s*/\\s*([0-9.]+)\\s*([KMGT]?B)",
            Pattern.CASE_INSENSITIVE);

    private final LauncherConfig config;
    private final DownloadManager downloads;

    public ForbricInstaller(LauncherConfig config, DownloadManager downloads) {
        this.config = config;
        this.downloads = downloads;
    }

    @Override
    public CompletableFuture<List<ModLoaderVersion>> listVersions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            if (!supportsGame(gameVersion)) return List.of();
            try {
                List<InstallerAsset> assets = selectInstallers(downloads.downloadString(RELEASES));
                List<ModLoaderVersion> out = new ArrayList<>();
                for (InstallerAsset asset : assets) {
                    out.add(new ModLoaderVersion(
                            ModLoader.FORBRIC, MINECRAFT, asset.version, asset.stable));
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
                            : new InstallInterruptedException("Forbric 安装已中断", e);
                }
                String detail = Exceptions.rootMessage(e);
                if (onProgress != null) onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.FAILED, 0, 0, detail));
                throw new RuntimeException(I18n.t("download.forbric_failed", detail), e);
            }
        });
    }

    private void installSync(String gameVersion, String loaderVersion,
                             Consumer<InstallProgress> onProgress) throws IOException {
        String mc = gameVersion == null ? "" : gameVersion.trim();
        if (!MINECRAFT.equals(mc)) {
            throw new IOException(I18n.t("download.forbric_unsupported",
                    loaderVersion == null ? "" : loaderVersion, MINECRAFT));
        }
        String wanted = loaderVersion == null ? "" : loaderVersion.trim();
        if (wanted.startsWith("v") || wanted.startsWith("V")) wanted = wanted.substring(1);
        if (!safeToken(wanted)) {
            throw new IOException(I18n.t("download.forbric_no_jar"));
        }
        InstallerAsset asset = null;
        for (InstallerAsset candidate : selectInstallers(downloads.downloadString(RELEASES))) {
            if (wanted.equals(candidate.version)) {
                asset = candidate;
                break;
            }
        }
        if (asset == null) throw new IOException(I18n.t("download.forbric_no_jar"));

        String blocked = SsrfChecker.validate(asset.url);
        if (blocked != null) throw new IOException(blocked);

        Path cache = config.getWorkDir().resolve("cache").resolve("forbric");
        Files.createDirectories(cache);
        Path jar = cache.resolve("forbric-kernel-installer-" + asset.version + ".jar");
        boolean reused = Files.isRegularFile(jar) && asset.sha256 != null
                && asset.sha256.equalsIgnoreCase(sha256(jar));
        if (!reused) {
            long total = asset.size > 0 ? asset.size : 0;
            InstallerAsset downloading = asset;
            downloads.downloadTo(asset.url, jar, completed -> {
                if (completed > MAX_JAR_BYTES) {
                    throw new UncheckedIOException(new IOException(I18n.t("download.forbric_failed",
                            downloading.version)));
                }
                long shownTotal = total > 0 ? total : Math.max(completed, 1);
                report(onProgress, InstallProgress.Stage.DOWNLOAD_LIBRARIES, completed, shownTotal,
                        I18n.t("download.forbric_download", downloading.version));
            });
        }
        if (asset.sha256 != null && !asset.sha256.equalsIgnoreCase(sha256(jar))) {
            Files.deleteIfExists(jar);
            throw new IOException(I18n.t("download.forbric_bad_hash"));
        }
        if (!looksLikeJar(jar)) {
            Files.deleteIfExists(jar);
            throw new IOException(I18n.t("download.forbric_no_jar"));
        }

        String java = JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir(), 21);
        if (java == null || java.isBlank()) {
            throw new IOException(I18n.t("download.forbric_java"));
        }
        if (installerAlreadyRunning()) {
            throw new IOException(I18n.t("download.forbric_busy"));
        }
        Path gameDir = config.getWorkDir();
        Files.createDirectories(gameDir);
        List<String> command = command(java, jar, gameDir, MINECRAFT, asset.tag);
        report(onProgress, InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 0,
                I18n.t("download.forbric_install", asset.version));
        runInstaller(command, gameDir, asset.version, Files.size(jar), onProgress);

        String id = versionId(MINECRAFT);
        Path json = config.getVersionsDir().resolve(id).resolve(id + ".json");
        if (!Files.isRegularFile(json)) {
            throw new IOException(I18n.t("download.forbric_missing_version", id));
        }
        report(onProgress, InstallProgress.Stage.DONE, 1, 1,
                I18n.t("download.forbric_done", id));
    }

    private void runInstaller(List<String> command, Path gameDir, String version,
                              long alreadyDownloaded, Consumer<InstallProgress> onProgress) throws IOException {
        Path log = config.getWorkDir().resolve("logs").resolve("forbric-" + version + ".log");
        runProcess(command, gameDir, log, "forbric-installer", "Forbric",
                I18n.t("download.forbric_timeout"),
                I18n.t("download.forbric_install", version),
                alreadyDownloaded, onProgress);
    }

    /**
     * 跑内核安装器并读它的进度。ECXP-Forbric+ 用同一套安装器，只是仓库和版本号不同。
     */
    static void runProcess(List<String> command, Path gameDir, Path log, String threadName,
                           String displayName, String timeoutMessage, String idleMessage,
                           long alreadyDownloaded, Consumer<InstallProgress> onProgress) throws IOException {
        Files.createDirectories(log.getParent());
        Files.writeString(log, "", StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        AtomicReference<String> lastLine = new AtomicReference<>("");
        StringBuilder tail = new StringBuilder();
        Thread drainer = new Thread(() -> drain(process, log, tail, lastLine), threadName);
        drainer.setDaemon(true);
        drainer.start();
        BuildProgress build = new BuildProgress(alreadyDownloaded);
        Thread hook = new Thread(() -> {
            if (process.isAlive()) process.destroyForcibly();
        }, threadName + "-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(INSTALL_TIMEOUT_MINUTES);
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new InstallInterruptedException(displayName + " 安装已中断");
                }
                boolean finished;
                try {
                    finished = process.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new InstallInterruptedException(displayName + " 安装已中断", e);
                }
                if (finished) break;
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    throw new IOException(timeoutMessage);
                }
                String line = lastLine.get();
                String message = line == null || line.isBlank() ? idleMessage : line;
                build.observe(message);
                if (build.total() > 0) {
                    report(onProgress, InstallProgress.Stage.DOWNLOAD_LIBRARIES,
                            build.completed(), build.total(), message);
                } else {
                    report(onProgress, InstallProgress.Stage.DOWNLOAD_LIBRARIES, 0, 0, message);
                }
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // 正在退出时不能再摘掉钩子
            }
            if (process.isAlive()) process.destroyForcibly();
            try {
                drainer.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (process.exitValue() != 0) {
            String preview;
            synchronized (tail) {
                preview = tail.toString().trim();
            }
            if (preview.length() > 500) preview = preview.substring(preview.length() - 500);
            throw new IOException(preview.isEmpty()
                    ? "exit=" + process.exitValue()
                    : "exit=" + process.exitValue() + ": " + preview);
        }
    }

    private static void drain(Process process, Path log, StringBuilder tail,
                              AtomicReference<String> lastLine) {
        // 安装器用 \r 刷新同一行。按行读会把进度堵在管道里，下载看起来停住。
        try (java.io.Reader reader = new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = reader.read()) >= 0) {
                if (ch == '\r' || ch == '\n') {
                    if (line.length() == 0) continue;
                    String text = line.toString();
                    line.setLength(0);
                    lastLine.set(trimLine(text));
                    synchronized (tail) {
                        tail.append(text).append('\n');
                        if (tail.length() > 8000) tail.delete(0, tail.length() - 4000);
                    }
                    try {
                        Files.writeString(log, text + "\n", StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException ignored) {
                        // 日志写失败不能停掉读取，否则安装器会被撑满的管道卡住
                    }
                } else {
                    line.append((char) ch);
                    if (line.length() > 2000) {
                        lastLine.set(trimLine(line.toString()));
                        line.setLength(0);
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程结束时管道关闭是正常的
        }
    }

    private static String trimLine(String line) {
        String text = line == null ? "" : line.trim();
        if (text.length() > 160) return text.substring(text.length() - 160);
        return text;
    }

    private static void report(Consumer<InstallProgress> onProgress, InstallProgress.Stage stage,
                               long completed, long total, String message) {
        if (onProgress != null) onProgress.accept(new InstallProgress(stage, completed, total, message));
    }

    static boolean installerAlreadyRunning() {
        try {
            return ProcessHandle.allProcesses().anyMatch(ForbricInstaller::commandLooksLikeInstaller);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean commandLooksLikeInstaller(ProcessHandle handle) {
        ProcessHandle.Info info = handle.info();
        if (info.commandLine().orElse("").contains("forbric-kernel-installer")) return true;
        return info.arguments().map(args -> String.join(" ", args)).orElse("")
                .contains("forbric-kernel-installer");
    }

    /**
     * 把安装器打印的「当前文件 16 KB / 3.4 MB」累加起来。
     * 队列按累计字节画进度，换文件时不会掉回 0%。
     */
    static final class BuildProgress {
        private long finished;
        private long done;
        private long fileTotal;
        private String file = "";

        BuildProgress(long alreadyDownloaded) {
            this.finished = Math.max(0, alreadyDownloaded);
        }

        void observe(String line) {
            Matcher matcher = FILE_PROGRESS.matcher(line == null ? "" : line);
            if (!matcher.find()) return;
            String name = matcher.group(1);
            long fileDone = sizeToBytes(matcher.group(3), matcher.group(4));
            long fileSize = sizeToBytes(matcher.group(5), matcher.group(6));
            if (fileSize < fileDone) fileSize = fileDone;
            if (!name.equals(file)) {
                finished += done;
                file = name;
                done = 0;
                fileTotal = 0;
            }
            done = fileDone;
            fileTotal = fileSize;
        }

        long completed() {
            return finished + done;
        }

        long total() {
            return finished + Math.max(fileTotal, done);
        }
    }

    static long sizeToBytes(String number, String unit) {
        double value;
        try {
            value = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            return 0;
        }
        if (value < 0) return 0;
        long scale = switch (unit == null ? "" : unit.toUpperCase(Locale.ROOT)) {
            case "KB" -> 1024L;
            case "MB" -> 1024L * 1024L;
            case "GB" -> 1024L * 1024L * 1024L;
            case "TB" -> 1024L * 1024L * 1024L * 1024L;
            default -> 1L;
        };
        return (long) (value * scale);
    }

    static boolean supportsGame(String gameVersion) {
        if (gameVersion == null || gameVersion.isBlank()) return true;
        return MINECRAFT.equals(gameVersion.trim());
    }

    public static String versionId(String minecraft) {
        return minecraft + "-forbric";
    }

    /**
     * 已安装的 Forbric 版本号，例如 {@code 0.3.1-beta2}。
     * 版本 JSON 不存在时返回 null，避免整合包安装再跑一遍安装器。
     */
    public static String installedVersion(Path versionsDir) {
        if (versionsDir == null) return null;
        String id = versionId(MINECRAFT);
        Path json = versionsDir.resolve(id).resolve(id + ".json");
        if (!Files.isRegularFile(json)) return null;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (root.has("libraries") && root.get("libraries").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("libraries")) {
                    if (!element.isJsonObject()) continue;
                    String name = text(element.getAsJsonObject(), "name");
                    String prefix = "net.forbric:forbric-kernel:";
                    if (name.startsWith(prefix)) return name.substring(prefix.length());
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 无界面安装命令。参数是列表，不经过 shell。 */
    static List<String> command(String java, Path jar, Path gameDir, String minecraft, String tag) {
        return List.of(
                java,
                "-jar",
                jar.toAbsolutePath().toString(),
                "--dir",
                gameDir.toAbsolutePath().toString(),
                "--mc",
                minecraft,
                "--release",
                tag);
    }

    static List<InstallerAsset> selectInstallers(String json) {
        if (json == null || json.isBlank()) return List.of();
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!root.isJsonArray()) return List.of();
        List<InstallerAsset> out = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject release = element.getAsJsonObject();
            if (bool(release, "draft")) continue;
            String tag = text(release, "tag_name");
            if (!safeToken(tag)) continue;
            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            if (!safeToken(version)) continue;
            JsonObject asset = chooseJar(release.get("assets"), version);
            if (asset == null) continue;
            String url = text(asset, "browser_download_url");
            if (!allowedDownloadUrl(url)) continue;
            long size = asset.has("size") && asset.get("size").isJsonPrimitive()
                    ? asset.get("size").getAsLong() : 0L;
            if (size > MAX_JAR_BYTES) continue;
            out.add(new InstallerAsset(version, tag, url, sha256Digest(text(asset, "digest")),
                    size, !bool(release, "prerelease")));
        }
        return out;
    }

    private static JsonObject chooseJar(JsonElement assets, String version) {
        if (assets == null || !assets.isJsonArray()) return null;
        JsonArray list = assets.getAsJsonArray();
        JsonObject fallback = null;
        String exact = "forbric-kernel-installer-" + version + ".jar";
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject asset = element.getAsJsonObject();
            String name = text(asset, "name");
            if (!isInstallerJar(name)) continue;
            if (exact.equals(name)) return asset;
            if (fallback == null) fallback = asset;
        }
        return fallback;
    }

    static boolean isInstallerJar(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains(".bat") || lower.contains(".command")) return false;
        return lower.startsWith("forbric-kernel-installer-") && lower.endsWith(".jar");
    }

    static boolean allowedDownloadUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("github.com")
                    || host.equals("objects.githubusercontent.com")
                    || host.equals("release-assets.githubusercontent.com")
                    || host.equals("github-releases.githubusercontent.com");
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean safeToken(String value) {
        if (value == null || value.isEmpty() || value.length() > 80) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '+' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private static String sha256Digest(String digest) {
        if (digest == null) return null;
        String value = digest.trim();
        if (value.regionMatches(true, 0, "sha256:", 0, 7)) value = value.substring(7).trim();
        if (value.length() != 64) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SHA-256 计算失败", e);
        }
    }

    static boolean looksLikeJar(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.entries().hasMoreElements();
        } catch (IOException e) {
            return false;
        }
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }

    static final class InstallerAsset {
        final String version;
        final String tag;
        final String url;
        final String sha256;
        final long size;
        final boolean stable;

        InstallerAsset(String version, String tag, String url, String sha256, long size, boolean stable) {
            this.version = version;
            this.tag = tag;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
            this.stable = stable;
        }
    }
}
