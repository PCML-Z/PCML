package com.pmcl.core.nativeclient;

import com.google.gson.JsonObject;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.i18n.I18n;
import com.pmcl.core.util.SafeZipExtractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 下载社区客户端自己的 GitHub 发行包，接上本机 Minecraft 资源，再用 Vulkan 启动。
 */
public final class NativeClientInstaller {

    public static final class Card {
        private final String id;
        private final String name;
        private final String author;
        private final String summary;
        private final String minecraftVersion;
        private final boolean releaseAvailable;
        private final boolean vulkan;
        private final String buildTool;
        private final boolean installed;
        private final String installedTag;

        Card(String id, String name, String author, String summary, String minecraftVersion,
             boolean releaseAvailable, boolean vulkan, String buildTool,
             boolean installed, String installedTag) {
            this.id = id;
            this.name = name;
            this.author = author == null ? "" : author;
            this.summary = summary;
            this.minecraftVersion = minecraftVersion;
            this.releaseAvailable = releaseAvailable;
            this.vulkan = vulkan;
            this.buildTool = buildTool == null ? "" : buildTool;
            this.installed = installed;
            this.installedTag = installedTag == null ? "" : installedTag;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getAuthor() { return author; }
        public String getSummary() { return summary; }
        public String getMinecraftVersion() { return minecraftVersion; }
        public boolean isReleaseAvailable() { return releaseAvailable; }
        public boolean isVulkan() { return vulkan; }
        public String getBuildTool() { return buildTool; }
        public boolean isInstalled() { return installed; }
        public String getInstalledTag() { return installedTag; }
    }

    private NativeClientInstaller() {}

    public static List<Card> status(Path pmclHome) {
        String os = NativeClientCatalog.osToken();
        String arch = NativeClientCatalog.archToken();
        List<Card> cards = new ArrayList<>();
        for (NativeClientCatalog.Spec spec : NativeClientCatalog.all()) {
            String tag = "";
            boolean installed = false;
            Path marker = marker(pmclHome, spec.id);
            if (Files.isRegularFile(marker)) {
                try {
                    JsonObject saved = com.google.gson.JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
                    String relative = saved.has("executable") ? saved.get("executable").getAsString() : "";
                    tag = saved.has("tag") ? saved.get("tag").getAsString() : "";
                    Path executable = clientRoot(pmclHome, spec.id).resolve(relative).normalize();
                    installed = !relative.isEmpty()
                            && executable.startsWith(clientRoot(pmclHome, spec.id).normalize())
                            && Files.isRegularFile(executable);
                } catch (Exception ignored) {
                    installed = false;
                }
            }
            cards.add(new Card(
                    spec.id,
                    I18n.t(spec.nameKey),
                    spec.owner,
                    I18n.t(spec.summaryKey),
                    spec.minecraftVersion,
                    spec.publishesHere(os, arch),
                    spec.vulkan,
                    spec.buildTool.name(),
                    installed,
                    tag));
        }
        return cards;
    }

    public static void install(Path pmclHome, DownloadManager downloads, String id, String compileRuntime,
                               Consumer<String> status) throws IOException {
        install(pmclHome, downloads, id, compileRuntime, status, null);
    }

    public static void install(Path pmclHome, DownloadManager downloads, String id, String compileRuntime,
                               Consumer<String> status, BiConsumer<Long, Long> bytes) throws IOException {
        NativeClientCatalog.Spec spec = NativeClientCatalog.find(id);
        if (spec == null) throw new IOException(I18n.t("native.unknown"));
        if (status != null) status.accept(I18n.t("native.finding_release", I18n.t(spec.nameKey)));
        String json = readReleases(spec);
        String os = NativeClientCatalog.osToken();
        String arch = NativeClientCatalog.archToken();
        NativeClientCatalog.AssetChoice asset = NativeClientCatalog.selectPublished(spec, json, os, arch);
        if (asset != null) {
            installRelease(pmclHome, downloads, spec, asset, status, bytes);
            return;
        }
        String runtime = compileRuntime == null || compileRuntime.isBlank()
                ? NativeClientCompiler.saved(pmclHome, spec.buildTool)
                : compileRuntime;
        if (runtime.isBlank()) throw new IOException(I18n.t("native.unsupported"));
        NativeClientAssets.Located game = null;
        if (needsAssets(spec)) {
            game = NativeClientAssets.locate(pmclHome, spec.minecraftVersion);
            if (game == null) throw new IOException(I18n.t("native.need_assets", spec.minecraftVersion));
        }
        NativeClientCompiler.Built built = NativeClientCompiler.compile(pmclHome, downloads, spec, runtime, status, bytes);
        finish(pmclHome, spec, built.executable, built.workDir, "source", game, status);
    }

    private static void installRelease(Path pmclHome, DownloadManager downloads, NativeClientCatalog.Spec spec,
                                       NativeClientCatalog.AssetChoice asset, Consumer<String> status,
                                       BiConsumer<Long, Long> bytes) throws IOException {
        NativeClientAssets.Located game = null;
        if (needsAssets(spec)) {
            game = NativeClientAssets.locate(pmclHome, spec.minecraftVersion);
            if (game == null) throw new IOException(I18n.t("native.need_assets", spec.minecraftVersion));
        }
        Path root = clientRoot(pmclHome, spec.id);
        Path app = root.resolve("app");
        deleteTree(app);
        Files.createDirectories(app);
        Path downloaded = root.resolve("download").resolve(safeFileName(asset.name));
        if (status != null) status.accept(I18n.t("native.downloading", asset.name));
        download(downloads, asset.url, downloaded, asset.size, bytes);

        Path executable;
        String lower = asset.name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            if (status != null) status.accept(I18n.t("native.extracting"));
            SafeZipExtractor.extractSafely(downloaded, app);
            executable = findExecutable(app, spec.executableNames);
        } else {
            executable = app.resolve(safeFileName(asset.name));
            Files.createDirectories(executable.getParent());
            Files.move(downloaded, executable, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (executable == null) throw new IOException(I18n.t("native.no_executable"));
        finish(pmclHome, spec, executable, executable.getParent(), asset.tag, game, status);
    }

    private static void finish(Path pmclHome, NativeClientCatalog.Spec spec, Path executable, Path workDir,
                               String tag, NativeClientAssets.Located game, Consumer<String> status) throws IOException {
        markExecutable(executable);
        if (needsAssets(spec)) {
            if (status != null) status.accept(I18n.t("native.preparing_assets", spec.minecraftVersion));
            NativeClientAssets.stage(spec, workDir, game);
        }
        if (spec.optionsKey != null) writeOptions(workDir, spec.optionsKey, "vulkan");
        Path root = clientRoot(pmclHome, spec.id);
        JsonObject markerJson = new JsonObject();
        markerJson.addProperty("tag", tag);
        markerJson.addProperty("executable", root.relativize(executable).toString().replace('\\', '/'));
        if (workDir != null && !workDir.equals(executable.getParent())) {
            markerJson.addProperty("workDir", root.relativize(workDir).toString().replace('\\', '/'));
        }
        Path marker = marker(pmclHome, spec.id);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, markerJson.toString(), StandardCharsets.UTF_8);
    }

    private static boolean needsAssets(NativeClientCatalog.Spec spec) {
        return spec.layout != NativeClientCatalog.AssetLayout.NONE
                && spec.minecraftVersion != null
                && !spec.minecraftVersion.isBlank();
    }

    public static Path launch(Path pmclHome, String id) throws IOException {
        return launch(pmclHome, id, "", "", "");
    }

    public static Path launch(Path pmclHome, String id, String username, String uuid, String accessToken)
            throws IOException {
        NativeClientCatalog.Spec spec = NativeClientCatalog.find(id);
        if (spec == null) throw new IOException(I18n.t("native.unknown"));
        Path root = clientRoot(pmclHome, spec.id);
        Path marker = marker(pmclHome, spec.id);
        if (!Files.isRegularFile(marker)) throw new IOException(I18n.t("native.not_installed"));
        JsonObject saved = com.google.gson.JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
        String relative = saved.has("executable") ? saved.get("executable").getAsString() : "";
        Path executable = root.resolve(relative).normalize();
        if (!executable.startsWith(root.normalize()) || !Files.isRegularFile(executable)) {
            throw new IOException(I18n.t("native.not_installed"));
        }
        Path workDir = executable.getParent();
        if (saved.has("workDir")) {
            Path configured = root.resolve(saved.get("workDir").getAsString()).normalize();
            if (configured.startsWith(root.normalize()) && Files.isDirectory(configured)) workDir = configured;
        }
        if (spec.optionsKey != null) writeOptions(workDir, spec.optionsKey, "vulkan");
        List<String> command = new ArrayList<>();
        if (executable.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            command.add(javaBin());
            command.add("-jar");
        }
        command.add(executable.toAbsolutePath().toString());
        for (String arg : spec.launchArgs) command.add(arg);
        java.util.Map<String, String> extraEnv = new java.util.LinkedHashMap<>();
        if ("pomme".equals(spec.id)) {
            preparePomme(pmclHome, root, executable, command, extraEnv, username, uuid, accessToken);
        }
        Path log = root.resolve("latest.log");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        if (spec.vulkanEnvKey != null && spec.vulkanEnvValue != null) {
            builder.environment().put(spec.vulkanEnvKey, spec.vulkanEnvValue);
        }
        builder.environment().putAll(extraEnv);
        Process process = builder.start();
        try {
            if (process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                String detail = redact(tail(log), accessToken);
                throw new IOException(I18n.t("native.exited", process.exitValue(), detail));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(I18n.t("native.exited", 1, ""));
        }
        return log;
    }

    private static void preparePomme(Path pmclHome, Path root, Path executable, List<String> command,
                                     java.util.Map<String, String> env, String username, String uuid,
                                     String accessToken) throws IOException {
        NativeClientCatalog.Spec spec = NativeClientCatalog.find("pomme");
        String version = spec == null || spec.minecraftVersion.isBlank() ? "26.2" : spec.minecraftVersion;
        Path runtime = root.resolve("runtime");
        Path marker = runtime.resolve(".assets-ready");
        if (!Files.isRegularFile(marker) || !version.equals(Files.readString(marker).trim())) {
            NativeClientAssets.Located game = NativeClientAssets.locate(pmclHome, version);
            if (game == null) throw new IOException(I18n.t("native.need_assets", version));
            NativeClientAssets.stagePomme(runtime, version, game);
        }
        Path icd = executable.getParent().resolve("MoltenVK_icd.json");
        if (Files.isRegularFile(icd)) {
            env.put("VK_ICD_FILENAMES", icd.toAbsolutePath().toString());
        }
        Path token = Files.createTempFile("pmcl-pomme-launch-", ".token");
        Files.writeString(token, "pmcl");
        String player = cleanPlayer(username);
        command.add("--version");
        command.add(version);
        command.add("--username");
        command.add(player);
        command.add("--assets-dir");
        command.add(runtime.resolve("assets").toAbsolutePath().toString());
        command.add("--versions-dir");
        command.add(runtime.resolve("versions").toAbsolutePath().toString());
        command.add("--game-dir");
        command.add(runtime.resolve("game").toAbsolutePath().toString());
        command.add("--launch-token");
        command.add(token.toAbsolutePath().toString());
        if (uuid != null && !uuid.isBlank() && accessToken != null && !accessToken.isBlank()) {
            command.add("--uuid");
            command.add(uuid.trim());
            command.add("--access-token");
            command.add(accessToken);
        }
        Files.createDirectories(runtime.resolve("game"));
    }

    private static String cleanPlayer(String username) {
        if (username == null) return "Player";
        String trimmed = username.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\0') >= 0) {
            return "Player";
        }
        return trimmed;
    }

    private static String redact(String text, String secret) {
        if (text == null) return "";
        if (secret == null || secret.isBlank()) return text;
        return text.replace(secret, "***");
    }

    private static String tail(Path log) {
        if (!Files.isRegularFile(log)) return "";
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(log)) {
            long size = channel.size();
            int count = (int) Math.min(size, 400);
            if (count <= 0) return "";
            channel.position(size - count);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(count);
            channel.read(buffer);
            return new String(buffer.array(), StandardCharsets.UTF_8).replace('\r', ' ').replace('\n', ' ').trim();
        } catch (IOException e) {
            return "";
        }
    }

    static String readReleases(NativeClientCatalog.Spec spec) throws IOException {
        String url = spec.releaseApi();
        String err = com.pmcl.core.util.SsrfChecker.validate(url);
        if (err != null) throw new IOException(err);
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("User-Agent", "PMCL")
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        try {
            java.net.http.HttpResponse<String> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            String body = response.body();
            if (body.length() > 1_000_000) throw new IOException("release list too large");
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static void writeOptions(Path workDir, String key, String value) throws IOException {
        Path options = workDir.resolve("options.txt");
        String existing = Files.isRegularFile(options) ? Files.readString(options) : "";
        Files.writeString(options, NativeClientCatalog.withBackend(existing, key, value), StandardCharsets.UTF_8);
    }

    static void download(DownloadManager downloads, String url, Path target, long total,
                         BiConsumer<Long, Long> bytes) throws IOException {
        long cap = 512L * 1024 * 1024;
        if (total > cap) throw new IOException(I18n.t("native.download_too_large"));
        downloads.downloadTo(url, target, completed -> {
            if (completed > cap) {
                throw new java.io.UncheckedIOException(new IOException(I18n.t("native.download_too_large")));
            }
            if (bytes != null) bytes.accept(completed, total);
        });
    }

    static Path findExecutable(Path root, String[] names) throws IOException {
        if (!Files.isDirectory(root)) return null;
        try (var walk = Files.walk(root, 8)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> !isBuildJunk(root, path))
                    .filter(path -> matchesName(path.getFileName().toString(), names))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean isBuildJunk(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return relative.contains("/deps/") || relative.contains("/incremental/");
    }

    private static String javaBin() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path bin = Path.of(System.getProperty("java.home", ""), "bin", name);
        return Files.isRegularFile(bin) ? bin.toString() : name;
    }

    private static boolean matchesName(String fileName, String[] names) {
        for (String name : names) {
            if (fileName.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static void markExecutable(Path executable) {
        executable.toFile().setExecutable(true, false);
    }

    private static String safeFileName(String name) {
        String file = name.replace('\\', '/');
        int slash = file.lastIndexOf('/');
        if (slash >= 0) file = file.substring(slash + 1);
        if (file.isBlank() || file.contains("..")) return "package.bin";
        return file;
    }

    private static Path clientRoot(Path pmclHome, String id) {
        return pmclHome.resolve("native-clients").resolve(id);
    }

    private static Path marker(Path pmclHome, String id) {
        return clientRoot(pmclHome, id).resolve("install.json");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root) && !Files.isSymbolicLink(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 残留文件会在解压时被覆盖
                }
            });
        }
    }
}
