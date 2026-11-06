package com.pmcl.core.ecxp;

import com.google.gson.JsonNull;
import com.pmcl.core.plugin.PluginManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ECXP 应用：用 .NET 10 / .NET 11 写的独立窗口工具。
 * 安装包放在 workDir/apps/&lt;id&gt;，应用数据放在 workDir/app-data/&lt;id&gt;，重装时数据保留。
 * 启动时开一个子进程，通过 {@link EcxpBridge} 调用启动器 API。
 */
public final class EcxpAppManager {

    public static final String PACKAGE_EXTENSION = ".ecxp";

    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final int MAX_FILES = 20_000;

    private final Path root;
    private final Path dataRoot;
    private final EcxpBridge bridge;
    private final Map<String, Running> running = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile DotnetRuntime runtime;

    public EcxpAppManager(Path workDir, PluginManager plugins, String launcherVersion) {
        this.root = workDir.resolve("apps");
        this.dataRoot = workDir.resolve("app-data");
        this.bridge = new EcxpBridge(plugins, launcherVersion, workDir);
    }

    /** 本机 .NET 情况。第一次调用会跑一次 dotnet --list-runtimes。 */
    public DotnetRuntime runtime() {
        DotnetRuntime r = runtime;
        if (r == null) {
            r = DotnetRuntime.probe();
            runtime = r;
        }
        return r;
    }

    public DotnetRuntime refreshRuntime() {
        runtime = DotnetRuntime.probe();
        return runtime;
    }

    /** 应用启动、退出、安装、卸载后回调，可能在任意线程。 */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public synchronized List<Installed> list() {
        List<Installed> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).sorted().toList()) {
                Path manifestFile = dir.resolve(EcxpManifest.FILE_NAME);
                if (!Files.isRegularFile(manifestFile)) continue;
                try {
                    out.add(snapshot(EcxpManifest.read(manifestFile)));
                } catch (IOException | EcxpException ignored) {
                    // 坏清单不挡其他应用
                }
            }
        } catch (IOException e) {
            System.err.println("[ECXP] 读取应用目录失败: " + e.getMessage());
        }
        out.sort(Comparator.comparing(i -> i.manifest().getName().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** 读出安装包里的清单，不安装。安装前给用户看权限用。 */
    public EcxpManifest inspect(Path source) throws EcxpException, IOException {
        if (source == null || !Files.exists(source)) throw new EcxpException("invalid_package");
        if (Files.isDirectory(source)) {
            Path file = source.resolve(EcxpManifest.FILE_NAME);
            if (!Files.isRegularFile(file)) throw new EcxpException("missing_manifest");
            return EcxpManifest.read(file);
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry(EcxpManifest.FILE_NAME);
            if (entry == null) throw new EcxpException("missing_manifest");
            try (InputStream in = zip.getInputStream(entry)) {
                return EcxpManifest.parse(new String(in.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (java.util.zip.ZipException e) {
            throw new EcxpException("invalid_package");
        }
    }

    /** 装 .ecxp / .zip 包或一个含 pmcl-app.json 的文件夹。同 id 覆盖旧版本。 */
    public Installed install(Path source) throws EcxpException, IOException {
        EcxpManifest manifest = inspect(source);
        Path absolute = source.toAbsolutePath().normalize();
        synchronized (this) {
            stopLocked(manifest.getId());
            Files.createDirectories(root);
            Path staging = Files.createTempDirectory(root, ".install-");
            try {
                if (Files.isDirectory(absolute)) copyTree(absolute, staging);
                else extractZip(absolute, staging);
                Path entry = resolveInside(staging, manifest.getEntry());
                if (!Files.isRegularFile(entry)) throw new EcxpException("missing_entry", manifest.getEntry());
                Files.writeString(staging.resolve(EcxpManifest.FILE_NAME), manifest.toJson());
                Path dest = appDir(manifest.getId());
                deleteTree(dest);
                Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
                Files.createDirectories(dataDir(manifest.getId()));
            } finally {
                deleteTree(staging);
            }
        }
        fire();
        return snapshot(manifest);
    }

    public void remove(String id) throws EcxpException, IOException {
        synchronized (this) {
            EcxpManifest manifest = require(id);
            stopLocked(manifest.getId());
            deleteTree(appDir(manifest.getId()));
            deleteTree(dataDir(manifest.getId()));
        }
        fire();
    }

    /**
     * 打开应用窗口。已经在运行就让它把窗口提到前面，返回 false。
     *
     * @param env 额外环境变量，例如界面主题和强调色
     */
    public boolean launch(String id, Map<String, String> env) throws EcxpException, IOException {
        synchronized (this) {
            Running current = running.get(id);
            if (current != null && current.process.isAlive()) {
                bridge.push(id, "activate", JsonNull.INSTANCE);
                return false;
            }
            EcxpManifest manifest = require(id);
            Path dir = appDir(manifest.getId());
            Path entry = resolveInside(dir, manifest.getEntry());
            if (!Files.isRegularFile(entry)) throw new EcxpException("missing_entry", manifest.getEntry());

            List<String> command = new ArrayList<>();
            DotnetRuntime dotnet = runtime();
            if (manifest.needsDotnetHost()) {
                if (!dotnet.found()) dotnet = refreshRuntime();
                if (!dotnet.found()) throw new EcxpException("missing_dotnet");
                if (!dotnet.hasMajor(manifest.frameworkMajor())) {
                    dotnet = refreshRuntime();
                    if (!dotnet.hasMajor(manifest.frameworkMajor())) {
                        throw new EcxpException("missing_runtime", String.valueOf(manifest.frameworkMajor()));
                    }
                }
                command.add(dotnet.host().toString());
                command.add(entry.toString());
            } else {
                markExecutable(entry);
                command.add(entry.toString());
            }

            Path data = dataDir(manifest.getId());
            Files.createDirectories(data);
            Path log = data.resolve("latest.log");
            int port = bridge.ensureStarted();
            String token = bridge.issue(manifest, data);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
            Map<String, String> childEnv = builder.environment();
            if (env != null) env.forEach((k, v) -> { if (k != null && v != null) childEnv.put(k, v); });
            childEnv.put("PMCL_ECXP_ENDPOINT", "127.0.0.1:" + port);
            childEnv.put("PMCL_ECXP_TOKEN", token);
            childEnv.put("PMCL_ECXP_APP", manifest.getId());
            childEnv.put("PMCL_ECXP_DATA", data.toString());
            childEnv.put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
            Path dotnetRoot = dotnet.root();
            if (dotnetRoot != null && !childEnv.containsKey("DOTNET_ROOT")) childEnv.put("DOTNET_ROOT", dotnetRoot.toString());

            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                bridge.revoke(token);
                throw new EcxpException("launch_failed", shortText(e.getMessage()));
            }
            Running started = new Running(process, token, System.currentTimeMillis());
            running.put(manifest.getId(), started);
            process.onExit().thenRun(() -> exited(manifest.getId(), started));
        }
        fire();
        return true;
    }

    public void stop(String id) {
        synchronized (this) {
            stopLocked(id);
        }
        fire();
    }

    public synchronized void stopAll() {
        for (String id : new ArrayList<>(running.keySet())) stopLocked(id);
        bridge.close();
    }

    public synchronized boolean isRunning(String id) {
        Running r = running.get(id);
        return r != null && r.process.isAlive();
    }

    public Path appDir(String id) {
        return root.resolve(id);
    }

    public Path dataDir(String id) {
        return dataRoot.resolve(id);
    }

    public Path logFile(String id) {
        return dataDir(id).resolve("latest.log");
    }

    public Path appsRoot() {
        return root;
    }

    private void exited(String id, Running which) {
        synchronized (this) {
            if (running.get(id) == which) running.remove(id);
            bridge.revoke(which.token);
        }
        fire();
    }

    private void stopLocked(String id) {
        Running r = running.remove(id);
        if (r == null) return;
        if (r.process.isAlive()) {
            bridge.push(id, "shutdown", JsonNull.INSTANCE);
            try {
                if (!r.process.waitFor(3, TimeUnit.SECONDS)) {
                    r.process.destroy();
                    if (!r.process.waitFor(2, TimeUnit.SECONDS)) r.process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                r.process.destroyForcibly();
            }
        }
        bridge.revoke(r.token);
    }

    private Installed snapshot(EcxpManifest manifest) {
        Running r = running.get(manifest.getId());
        boolean alive = r != null && r.process.isAlive();
        Path icon = null;
        if (!manifest.getIcon().isEmpty()) {
            try {
                Path p = resolveInside(appDir(manifest.getId()), manifest.getIcon());
                if (Files.isRegularFile(p)) icon = p;
            } catch (EcxpException ignored) {
            }
        }
        return new Installed(manifest, alive, alive ? r.startedAt : 0L, icon);
    }

    private EcxpManifest require(String id) throws EcxpException {
        if (id == null || id.isBlank()) throw new EcxpException("unknown_app");
        Path file = appDir(id).resolve(EcxpManifest.FILE_NAME);
        if (!Files.isRegularFile(file)) throw new EcxpException("unknown_app");
        try {
            return EcxpManifest.read(file);
        } catch (IOException e) {
            throw new EcxpException("invalid_package", shortText(e.getMessage()));
        }
    }

    private void fire() {
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                System.err.println("[ECXP] listener: " + e.getMessage());
            }
        }
    }

    private static Path resolveInside(Path base, String relative) throws EcxpException {
        if (EcxpManifest.isUnsafeRelative(relative)) throw new EcxpException("invalid_entry");
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base.normalize())) throw new EcxpException("invalid_entry");
        return resolved;
    }

    private static void markExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void copyTree(Path source, Path dest) throws IOException, EcxpException {
        int count = 0;
        long bytes = 0;
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                if (Files.isSymbolicLink(path)) continue;
                String rel = source.relativize(path).toString().replace('\\', '/');
                if (rel.isEmpty()) continue;
                if (EcxpManifest.isUnsafeRelative(rel)) throw new EcxpException("invalid_entry");
                Path target = dest.resolve(rel).normalize();
                if (!target.startsWith(dest.normalize())) throw new EcxpException("invalid_entry");
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!Files.isRegularFile(path)) continue;
                count++;
                bytes += Files.size(path);
                if (count > MAX_FILES || bytes > MAX_BYTES) throw new EcxpException("too_large");
                Files.createDirectories(target.getParent());
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void extractZip(Path zip, Path dest) throws IOException, EcxpException {
        int count = 0;
        long bytes = 0;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank()) continue;
                if (EcxpManifest.isUnsafeRelative(name)) throw new EcxpException("invalid_entry");
                Path target = dest.resolve(name).normalize();
                if (!target.startsWith(dest.normalize())) throw new EcxpException("invalid_entry");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                count++;
                if (count > MAX_FILES) throw new EcxpException("too_large");
                Files.createDirectories(target.getParent());
                try (InputStream in = file.getInputStream(entry)) {
                    long written = copyLimited(in, target, MAX_BYTES - bytes);
                    bytes += written;
                }
            }
        }
    }

    private static long copyLimited(InputStream in, Path target, long budget) throws IOException, EcxpException {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (var out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > budget) throw new EcxpException("too_large");
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static String shortText(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").strip();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    /** 已安装应用和它此刻是否开着窗口。 */
    public record Installed(EcxpManifest manifest, boolean running, long startedAt, Path icon) { }

    private record Running(Process process, String token, long startedAt) { }
}
