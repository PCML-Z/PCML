package com.pmcl.core.ecxp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.util.SafeZipExtractor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 把 .NET 运行时装进用户目录的 ~/.dotnet。压缩包必须来自微软的发行源，并在下载队列里校验。
 */
public final class DotnetInstaller {

    static final String FEED_HOST = "builds.dotnet.microsoft.com";
    private static final String INDEX_URL =
            "https://builds.dotnet.microsoft.com/dotnet/release-metadata/releases-index.json";
    private static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;

    private DotnetInstaller() {}

    public record Package(String version, String url, String sha512, String fileName) {}

    public static Path installDir() {
        return Path.of(System.getProperty("user.home"), ".dotnet");
    }

    public static String rid(String osName, String osArch) throws IOException {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        String platform;
        if (os.contains("mac") || os.contains("darwin")) platform = "osx";
        else if (os.contains("win")) platform = "win";
        else if (os.contains("linux")) platform = "linux";
        else throw new IOException("这个系统不能在启动器里安装 .NET");
        String cpu;
        if (arch.equals("aarch64") || arch.equals("arm64")) cpu = "arm64";
        else if (arch.equals("amd64") || arch.equals("x86_64")) cpu = "x64";
        else throw new IOException("这个处理器架构不能在启动器里安装 .NET");
        return platform + "-" + cpu;
    }

    /**
     * 从发行索引和某个通道的 releases.json 里挑出当前 RID 的运行时压缩包。
     * 优先用索引宣布的 latest-runtime。
     */
    public static Package select(String indexJson, String releasesJson, int major, String rid) throws IOException {
        if (major < 1) throw new IOException("没有这个 .NET 版本");
        if (rid == null || rid.isBlank()) throw new IOException("没有这个 .NET 版本");
        String latest = null;
        JsonArray channels = array(parse(indexJson), "releases-index");
        String channel = major + ".0";
        for (JsonElement el : channels) {
            JsonObject obj = el.getAsJsonObject();
            if (!channel.equals(text(obj, "channel-version"))) continue;
            latest = text(obj, "latest-runtime");
            break;
        }
        JsonObject chosen = null;
        JsonObject fallback = null;
        for (JsonElement el : array(parse(releasesJson), "releases")) {
            if (!el.isJsonObject()) continue;
            JsonElement runtimeEl = el.getAsJsonObject().get("runtime");
            if (runtimeEl == null || !runtimeEl.isJsonObject()) continue;
            JsonObject runtime = runtimeEl.getAsJsonObject();
            String version = text(runtime, "version");
            if (!version.startsWith(major + ".")) continue;
            if (fileFor(runtime, rid) == null) continue;
            if (fallback == null) fallback = runtime;
            if (!latest.isBlank() && version.equals(latest)) {
                chosen = runtime;
                break;
            }
        }
        if (chosen == null) chosen = fallback;
        if (chosen == null) throw new IOException("没有适合这台电脑的 .NET " + major + " 运行时");
        JsonObject file = fileFor(chosen, rid);
        String url = text(file, "url");
        String hash = text(file, "hash");
        if (!official(url) || !hash.matches("(?i)[0-9a-f]{128}")) {
            throw new IOException("拒绝非官方的 .NET 安装包");
        }
        return new Package(text(chosen, "version"), url, hash.toLowerCase(Locale.ROOT), fileName(url));
    }

    public static void install(DownloadManager downloads, int major,
                               Consumer<String> message, BiConsumer<Long, Long> progress) throws IOException {
        String rid = rid(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        say(message, "正在查找 .NET " + major);
        String index = httpGet(downloads.httpClient(), INDEX_URL);
        String releasesUrl = releasesUrl(index, major);
        String releases = httpGet(downloads.httpClient(), releasesUrl);
        Package pkg = select(index, releases, major, rid);
        Path dir = installDir();
        Files.createDirectories(dir.resolve(".download"));
        Path archive = dir.resolve(".download").resolve(pkg.fileName());
        long size = headSize(downloads.httpClient(), pkg.url());
        say(message, "正在下载 .NET " + pkg.version());
        if (progress != null) progress.accept(0L, size);
        downloads.downloadToVerified(pkg.url(), archive, null, pkg.sha512(), bytes -> {
            if (progress != null) progress.accept(bytes, size);
        });
        say(message, "正在安装 .NET " + pkg.version());
        extract(archive, dir);
        markExecutable(dir.resolve(hostName()));
        Files.deleteIfExists(archive);
    }

    static String releasesUrl(String indexJson, int major) throws IOException {
        String channel = major + ".0";
        for (JsonElement el : array(parse(indexJson), "releases-index")) {
            JsonObject obj = el.getAsJsonObject();
            if (!channel.equals(text(obj, "channel-version"))) continue;
            String url = text(obj, "releases.json");
            if (!official(url) || !url.startsWith("https://" + FEED_HOST + "/dotnet/release-metadata/")) {
                throw new IOException("拒绝非官方的 .NET 地址");
            }
            return url;
        }
        throw new IOException("没有 .NET " + major + " 的发行信息");
    }

    private static JsonObject fileFor(JsonObject runtime, String rid) {
        JsonElement filesEl = runtime.get("files");
        if (filesEl == null || !filesEl.isJsonArray()) return null;
        for (JsonElement el : filesEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject file = el.getAsJsonObject();
            String url = text(file, "url").toLowerCase(Locale.ROOT);
            if (!url.endsWith(".tar.gz") && !url.endsWith(".zip")) continue;
            String name = text(file, "name");
            if (rid.equals(text(file, "rid")) || name.contains(rid)) return file;
        }
        return null;
    }

    private static void extract(Path archive, Path dest) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            SafeZipExtractor.extractSafely(archive, dest);
            return;
        }
        if (name.endsWith(".tar.gz")) {
            extractTarGz(archive, dest);
            return;
        }
        throw new IOException("不支持的 .NET 安装包: " + archive.getFileName());
    }

    private static void extractTarGz(Path archive, Path dest) throws IOException {
        assertTarMembersSafe(archive);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", dest.toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            if (!process.waitFor(300, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("解压 .NET 超时");
            }
            if (process.exitValue() != 0) throw new IOException("解压 .NET 失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解压 .NET 被中断", e);
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    private static void assertTarMembersSafe(Path archive) throws IOException {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("tar", "-tvzf", archive.toString());
            builder.redirectErrorStream(true);
            process = builder.start();
            int entries = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    if (++entries > 20_000) throw new IOException("拒绝条目过多的 .NET 安装包");
                    int arrow = line.indexOf(" -> ");
                    String member = arrow < 0 ? tarName(line) : tarName(line.substring(0, arrow));
                    if (!staysInside(member)) throw new IOException("拒绝路径越界的 .NET 安装包");
                    if (arrow >= 0 && !linkStaysInside(member, line.substring(arrow + 4).trim())) {
                        throw new IOException("拒绝路径越界的 .NET 安装包");
                    }
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("无法检查 .NET 安装包");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("检查 .NET 安装包被中断", e);
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    private static String tarName(String line) {
        int space = line.lastIndexOf(' ');
        return space < 0 ? line.trim() : line.substring(space + 1).trim();
    }

    private static boolean staysInside(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")) return false;
        for (Path part : Path.of(name.replace('\\', '/')).normalize()) {
            if ("..".equals(part.toString())) return false;
        }
        return true;
    }

    private static boolean linkStaysInside(String name, String target) {
        if (target == null || target.isBlank() || target.startsWith("/") || target.startsWith("\\")) return false;
        Path link = Path.of(name.replace('\\', '/'));
        Path parent = link.getParent();
        Path resolved = (parent == null ? Path.of(target) : parent.resolve(target)).normalize();
        return staysInside(resolved.toString());
    }

    private static void markExecutable(Path file) {
        if (!Files.isRegularFile(file)) return;
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static String hostName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "dotnet.exe" : "dotnet";
    }

    private static String httpGet(OkHttpClient http, String url) throws IOException {
        if (!official(url)) throw new IOException("拒绝非官方的 .NET 地址");
        Request request = new Request.Builder().url(url).header("User-Agent", "PMCL").get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("读取 .NET 发行信息失败");
            }
            if (!FEED_HOST.equalsIgnoreCase(response.request().url().host())) {
                throw new IOException("拒绝非官方的 .NET 地址");
            }
            try (InputStream in = response.body().byteStream()) {
                byte[] bytes = in.readNBytes(MAX_METADATA_BYTES + 1);
                if (bytes.length > MAX_METADATA_BYTES) throw new IOException(".NET 发行信息过大");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    private static long headSize(OkHttpClient http, String url) {
        try (Response response = http.newCall(new Request.Builder().url(url).head().build()).execute()) {
            String length = response.header("Content-Length");
            if (length == null || length.isBlank()) return 0L;
            long size = Long.parseLong(length.trim());
            return size > 0 ? size : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean official(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (!FEED_HOST.equalsIgnoreCase(uri.getHost())) return false;
            String path = uri.getPath();
            return path != null && !path.contains("..");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String fileName(String url) throws IOException {
        String path = URI.create(url).getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        if (!name.matches("[A-Za-z0-9._-]{1,180}")) throw new IOException("拒绝非官方的 .NET 安装包");
        return name;
    }

    private static JsonObject parse(String json) throws IOException {
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) throw new IOException(".NET 发行信息无法读取");
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException(".NET 发行信息无法读取");
        }
    }

    private static JsonArray array(JsonObject obj, String name) throws IOException {
        JsonElement el = obj.get(name);
        if (el == null || !el.isJsonArray()) throw new IOException(".NET 发行信息无法读取");
        return el.getAsJsonArray();
    }

    private static String text(JsonObject obj, String name) {
        JsonElement el = obj.get(name);
        if (el == null || el.isJsonNull()) return "";
        return el.getAsString().trim();
    }

    private static void say(Consumer<String> message, String text) {
        if (message != null) message.accept(text);
    }
}
