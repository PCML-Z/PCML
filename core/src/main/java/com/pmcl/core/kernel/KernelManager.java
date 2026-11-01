package com.pmcl.core.kernel;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.plugin.kernel.KernelHost;
import com.pmcl.plugin.kernel.LauncherKernel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 安装、启用和停用启动器内核。清单、JAR、ZIP 或文件夹都放在 workDir/kernels/&lt;id&gt;。
 * Java 内核在本进程加载；C# 用 dotnet 启动 dll，或直接运行 exe；Rust 运行可执行文件。
 */
public final class KernelManager {

    public static final String HEALTH_READY = "ready";
    public static final String HEALTH_DISABLED = "disabled";
    public static final String HEALTH_MISSING_RUNTIME = "missing_runtime";
    public static final String HEALTH_FAILED = "failed";
    public static final String HEALTH_INSTALLED = "installed";

    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAX_FILES = 4000;
    private static final Gson GSON = new Gson();

    private final Path root;
    private final Path stateFile;
    private final Set<String> enabled = new HashSet<>();
    private final Map<String, Running> running = new LinkedHashMap<>();
    private final Map<String, Health> health = new LinkedHashMap<>();

    public KernelManager(Path root) {
        this.root = root;
        this.stateFile = root.resolve("state.json");
        loadState();
    }

    public synchronized List<Installed> list() {
        List<Installed> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (var stream = Files.list(root)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path dir : dirs) {
                Path manifestFile = dir.resolve(KernelManifest.FILE_NAME);
                if (!Files.isRegularFile(manifestFile)) continue;
                try {
                    KernelManifest manifest = KernelManifest.read(manifestFile);
                    boolean on = enabled.contains(manifest.getId());
                    Health known = health.get(manifest.getId());
                    String code = known != null ? known.code : (on ? HEALTH_INSTALLED : HEALTH_DISABLED);
                    String detail = known != null ? known.detail : "";
                    out.add(new Installed(manifest, on, code, detail));
                } catch (IOException | KernelException ignored) {
                    // 坏清单留在磁盘上，但不挡其他内核
                }
            }
        } catch (IOException e) {
            System.err.println("[Kernel] 读取内核目录失败: " + e.getMessage());
        }
        return out;
    }

    public synchronized Installed install(Path source) throws KernelException, IOException {
        if (source == null || !Files.exists(source)) throw new KernelException("invalid_package");
        Files.createDirectories(root);
        Path absolute = source.toAbsolutePath().normalize();
        Installed installed;
        if (Files.isDirectory(absolute)) {
            installed = installDirectory(absolute);
        } else {
            String name = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".jar")) installed = installJar(absolute);
            else if (name.endsWith(".zip")) installed = installZip(absolute);
            else throw new KernelException("invalid_package");
        }
        return installed;
    }

    public synchronized Installed setEnabled(String id, boolean on) throws KernelException {
        KernelManifest manifest = require(id);
        if (on) {
            enabled.add(manifest.getId());
            saveState();
            activate(manifest);
        } else {
            enabled.remove(manifest.getId());
            saveState();
            stopLocked(manifest.getId());
            health.put(manifest.getId(), new Health(HEALTH_DISABLED, ""));
        }
        return find(manifest.getId());
    }

    public synchronized void remove(String id) throws KernelException, IOException {
        KernelManifest manifest = require(id);
        stopLocked(manifest.getId());
        enabled.remove(manifest.getId());
        health.remove(manifest.getId());
        saveState();
        deleteTree(kernelDir(manifest.getId()));
    }

    /** 启动已启用的内核。单个失败不影响其余内核。 */
    public synchronized void activateEnabled() {
        for (Installed installed : list()) {
            if (!installed.isEnabled()) continue;
            try {
                activate(installed.getManifest());
            } catch (RuntimeException e) {
                health.put(installed.getManifest().getId(),
                        new Health(HEALTH_FAILED, shortText(e.getMessage())));
            }
        }
    }

    public synchronized void stopAll() {
        for (String id : new ArrayList<>(running.keySet())) {
            stopLocked(id);
        }
    }

    /**
     * 把这一次游戏交给指定内核启动。不改变设置里的启用开关。
     * Java 内核在本进程里执行 {@link LauncherKernel#launch}。
     * C# / Rust 另起一个进程，写入同一份 launch 请求，确认标准错误上的 {@code {"ok":true}} 后返回该进程。
     * 标准输出上的 hello 应答会立刻失败。返回之后，进程的标准输出才是游戏日志。
     *
     * @param appendLog 非空时内核标准输出追加到这个文件
     * @param discardOutput 为真时丢弃内核标准输出
     */
    public synchronized Process launchGame(String id, List<String> command, Path directory,
                                           Map<String, String> env, Path appendLog,
                                           boolean discardOutput) throws KernelException, IOException {
        if (command == null || command.isEmpty() || command.get(0) == null || command.get(0).isBlank()) {
            throw new KernelException("launch_failed", "empty command");
        }
        KernelManifest manifest = require(id);
        Path work = directory == null ? kernelDir(manifest.getId()) : directory;
        return switch (manifest.getRuntime()) {
            case "java" -> launchJavaGame(manifest, command, work, env, appendLog, discardOutput);
            case "csharp", "rust" -> launchNativeGame(manifest, command, work, env, appendLog, discardOutput);
            default -> throw new KernelException("invalid_runtime");
        };
    }

    /** 测试用来核对 C# / Rust 的启动命令，不真正执行。 */
    public static List<String> launchCommand(String runtime, Path artifact) {
        String kind = runtime == null ? "" : runtime.toLowerCase(Locale.ROOT);
        if ("csharp".equals(kind) && artifact.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dll")) {
            return List.of("dotnet", artifact.toAbsolutePath().normalize().toString());
        }
        return List.of(artifact.toAbsolutePath().normalize().toString());
    }

    private Installed installDirectory(Path source) throws KernelException, IOException {
        Files.createDirectories(root);
        KernelManifest manifest = KernelManifest.read(source.resolve(KernelManifest.FILE_NAME));
        if (manifest.getArtifact().isEmpty()) throw new KernelException("missing_artifact");
        if (!Files.isRegularFile(resolveInside(source, manifest.getArtifact()))) {
            throw new KernelException("missing_artifact");
        }
        Path dest = kernelDir(manifest.getId());
        if (dest.normalize().startsWith(source) || source.startsWith(dest.normalize())) {
            throw new KernelException("invalid_package");
        }
        Path keptData = backupData(dest);
        deleteTree(dest);
        copyRegularFiles(source, dest);
        restoreData(dest, keptData);
        ensureArtifact(dest, manifest);
        markExecutable(dest, manifest);
        if (enabled.contains(manifest.getId())) activate(manifest);
        return snapshot(manifest);
    }

    private Installed installJar(Path jar) throws KernelException, IOException {
        String json;
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(KernelManifest.FILE_NAME);
            if (entry == null) entry = file.getJarEntry("META-INF/" + KernelManifest.FILE_NAME);
            if (entry == null) throw new KernelException("missing_manifest");
            json = new String(file.getInputStream(entry).readNBytes(64 * 1024), StandardCharsets.UTF_8);
        }
        KernelManifest manifest = KernelManifest.parse(json);
        manifest.bindJarName(jar.getFileName().toString());
        Path dest = kernelDir(manifest.getId());
        Path keptData = backupData(dest);
        deleteTree(dest);
        Files.createDirectories(dest);
        Files.copy(jar, dest.resolve(manifest.getArtifact()), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(dest.resolve(KernelManifest.FILE_NAME), manifest.toJson(), StandardCharsets.UTF_8);
        restoreData(dest, keptData);
        if (enabled.contains(manifest.getId())) activate(manifest);
        return snapshot(manifest);
    }

    private Installed installZip(Path zip) throws KernelException, IOException {
        Path temp = Files.createTempDirectory(root, "import-");
        try {
            KernelManifest manifest = readZipManifest(zip);
            if (manifest.getArtifact().isEmpty()) throw new KernelException("missing_artifact");
            extractZip(zip, temp);
            if (!Files.isRegularFile(temp.resolve(KernelManifest.FILE_NAME))) {
                Files.writeString(temp.resolve(KernelManifest.FILE_NAME), manifest.toJson(), StandardCharsets.UTF_8);
            }
            return installDirectory(temp);
        } finally {
            deleteTree(temp);
        }
    }

    private void activate(KernelManifest manifest) {
        stopLocked(manifest.getId());
        try {
            switch (manifest.getRuntime()) {
                case "java" -> startJava(manifest);
                case "csharp", "rust" -> startProcess(manifest);
                default -> throw new KernelException("invalid_runtime");
            }
            health.put(manifest.getId(), new Health(HEALTH_READY, ""));
        } catch (KernelException e) {
            String code = HEALTH_MISSING_RUNTIME.equals(e.code()) ? HEALTH_MISSING_RUNTIME : HEALTH_FAILED;
            health.put(manifest.getId(), new Health(code, e.detail().isEmpty() ? e.code() : e.detail()));
        }
    }

    private void startJava(KernelManifest manifest) throws KernelException {
        Path dir = kernelDir(manifest.getId());
        Path artifact = resolveInside(dir, manifest.getArtifact());
        if (!Files.isRegularFile(artifact)) throw new KernelException("missing_artifact");
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(artifact.toUri().toURL());
            Path lib = dir.resolve("lib");
            if (Files.isDirectory(lib)) {
                try (var stream = Files.list(lib)) {
                    for (Path jar : stream.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                        urls.add(jar.toUri().toURL());
                    }
                }
            }
            URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), LauncherKernel.class.getClassLoader());
            try {
                Class<?> type = Class.forName(manifest.getEntry(), true, loader);
                if (!LauncherKernel.class.isAssignableFrom(type)) {
                    throw new KernelException("not_a_kernel");
                }
                LauncherKernel kernel = (LauncherKernel) type.getConstructor().newInstance();
                Path data = dir.resolve("data");
                Files.createDirectories(data);
                kernel.start(new DefaultHost(manifest.getId(), data));
                running.put(manifest.getId(), Running.java(kernel, loader));
                loader = null;
            } catch (KernelException e) {
                throw e;
            } catch (ClassNotFoundException e) {
                throw new KernelException("load_failed", e.toString());
            } catch (ReflectiveOperationException e) {
                throw new KernelException("not_a_kernel", e.toString());
            } catch (Exception e) {
                throw new KernelException("load_failed", e.toString());
            } finally {
                if (loader != null) loader.close();
            }
        } catch (KernelException e) {
            throw e;
        } catch (Exception e) {
            throw new KernelException("load_failed", e.toString());
        }
    }

    private void startProcess(KernelManifest manifest) throws KernelException {
        Path dir = kernelDir(manifest.getId());
        Path artifact = resolveInside(dir, manifest.getArtifact());
        if (!Files.isRegularFile(artifact)) throw new KernelException("missing_artifact");
        markExecutable(dir, manifest);
        List<String> command = launchCommand(manifest.getRuntime(), artifact);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir.toFile());
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            String request = "{\"op\":\"hello\",\"id\":" + quote(manifest.getId()) + "}\n";
            OutputStream out = process.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line = readLine(process.getInputStream(), process, 5);
            if (!helloOk(line)) {
                throw new KernelException("probe_failed", shortText(line));
            }
            running.put(manifest.getId(), Running.process(process));
            process = null;
        } catch (KernelException e) {
            throw e;
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (command.get(0).equals("dotnet") && (message.contains("error=2")
                    || message.contains("No such file") || message.contains("Cannot run program"))) {
                throw new KernelException("missing_runtime");
            }
            throw new KernelException("probe_failed", shortText(message));
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    private Process launchJavaGame(KernelManifest manifest, List<String> command, Path directory,
                                   Map<String, String> env, Path appendLog,
                                   boolean discardOutput) throws KernelException {
        Running current = running.get(manifest.getId());
        if (current != null && current.kernel != null) {
            return invokeLaunch(current.kernel, manifest, command, directory, env, appendLog, discardOutput);
        }
        Path dir = kernelDir(manifest.getId());
        Path artifact = resolveInside(dir, manifest.getArtifact());
        if (!Files.isRegularFile(artifact)) throw new KernelException("missing_artifact");
        URLClassLoader loader = null;
        try {
            loader = javaLoader(dir, artifact);
            Class<?> type = Class.forName(manifest.getEntry(), true, loader);
            if (!LauncherKernel.class.isAssignableFrom(type)) throw new KernelException("not_a_kernel");
            LauncherKernel kernel = (LauncherKernel) type.getConstructor().newInstance();
            Process process = invokeLaunch(kernel, manifest, command, directory, env, appendLog, discardOutput);
            URLClassLoader owned = loader;
            loader = null;
            Thread waiter = new Thread(() -> {
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    try {
                        owned.close();
                    } catch (IOException ignored) {
                    }
                }
            }, "pmcl-kernel-launch-" + manifest.getId());
            waiter.setDaemon(true);
            waiter.start();
            return process;
        } catch (KernelException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            throw new KernelException("load_failed", shortText(e.toString()));
        } catch (ReflectiveOperationException e) {
            throw new KernelException("not_a_kernel", shortText(e.toString()));
        } catch (Exception e) {
            throw new KernelException("launch_failed", shortText(e.getMessage()));
        } finally {
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Process launchNativeGame(KernelManifest manifest, List<String> command, Path directory,
                                     Map<String, String> env, Path appendLog,
                                     boolean discardOutput) throws KernelException, IOException {
        Path dir = kernelDir(manifest.getId());
        Path artifact = resolveInside(dir, manifest.getArtifact());
        if (!Files.isRegularFile(artifact)) throw new KernelException("missing_artifact");
        markExecutable(dir, manifest);
        List<String> host = launchCommand(manifest.getRuntime(), artifact);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(host);
            builder.directory(dir.toFile());
            if (discardOutput) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            else if (appendLog != null) builder.redirectOutput(ProcessBuilder.Redirect.appendTo(appendLog.toFile()));
            process = builder.start();
            byte[] request = launchRequest(manifest.getId(), command, directory, env);
            Process writing = process;
            Thread writer = new Thread(() -> {
                try {
                    OutputStream out = writing.getOutputStream();
                    out.write(request);
                    out.flush();
                } catch (IOException ignored) {
                    // 内核可以先拒绝再退出，此时写入会碰到已关闭的管道
                }
            }, "pmcl-kernel-launch-write");
            writer.setDaemon(true);
            writer.start();
            String ack = readLaunchAck(process, 30);
            try {
                writer.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!helloOk(ack)) {
                throw new KernelException("launch_failed", ackDetail(ack));
            }
            drainLater(process.getErrorStream());
            Process started = process;
            process = null;
            return started;
        } catch (KernelException e) {
            throw e;
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (host.get(0).equals("dotnet") && (message.contains("error=2")
                    || message.contains("No such file") || message.contains("Cannot run program"))) {
                throw new KernelException("missing_runtime");
            }
            throw new KernelException("launch_failed", shortText(message));
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    private Process invokeLaunch(LauncherKernel kernel, KernelManifest manifest, List<String> command,
                                 Path directory, Map<String, String> env, Path appendLog,
                                 boolean discardOutput) throws KernelException {
        try {
            return kernel.launch(hostForLaunch(manifest), command, directory, env, appendLog, discardOutput);
        } catch (KernelException e) {
            throw e;
        } catch (Exception e) {
            throw new KernelException("launch_failed", shortText(e.getMessage()));
        }
    }

    private URLClassLoader javaLoader(Path dir, Path artifact) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(artifact.toUri().toURL());
        Path lib = dir.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var stream = Files.list(lib)) {
                for (Path jar : stream.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                    urls.add(jar.toUri().toURL());
                }
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), LauncherKernel.class.getClassLoader());
    }

    private KernelHost hostForLaunch(KernelManifest manifest) throws IOException {
        Path data = kernelDir(manifest.getId()).resolve("data");
        Files.createDirectories(data);
        return new DefaultHost(manifest.getId(), data);
    }

    private static byte[] launchRequest(String id, List<String> command, Path directory,
                                        Map<String, String> env) {
        JsonObject req = new JsonObject();
        req.addProperty("op", "launch");
        req.addProperty("id", id);
        req.addProperty("directory", directory.toAbsolutePath().normalize().toString());
        JsonArray cmd = new JsonArray();
        for (String part : command) cmd.add(part);
        req.add("command", cmd);
        JsonObject envJson = new JsonObject();
        if (env != null) {
            for (Map.Entry<String, String> entry : env.entrySet()) {
                if (entry.getKey() == null) continue;
                envJson.addProperty(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        req.add("env", envJson);
        return (GSON.toJson(req) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String ackDetail(String line) {
        if (line == null || line.isBlank()) return "";
        try {
            JsonObject obj = JsonParser.parseString(line.strip()).getAsJsonObject();
            if (obj.has("error") && obj.get("error").isJsonPrimitive()) {
                return shortText(obj.get("error").getAsString());
            }
        } catch (RuntimeException ignored) {
        }
        return shortText(line);
    }

    private static void drainLater(InputStream stream) {
        Thread drain = new Thread(() -> {
            try (stream) {
                stream.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }, "pmcl-kernel-err");
        drain.setDaemon(true);
        drain.start();
    }

    private static boolean helloOk(String line) {
        if (line == null || line.isBlank() || !line.strip().startsWith("{")) return false;
        try {
            JsonObject obj = JsonParser.parseString(line.strip()).getAsJsonObject();
            return obj.has("ok") && obj.get("ok").getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 等启动应答。应答只认标准错误上的一行。
     * 若内核把 hello 的 {@code {"ok":true}} 写到标准输出，说明它没有实现启动标准，立即失败。
     */
    private static String readLaunchAck(Process process, int seconds) throws IOException, KernelException {
        InputStream err = process.getErrorStream();
        InputStream out = process.getInputStream();
        StringBuilder errBuf = new StringBuilder();
        StringBuilder outBuf = new StringBuilder();
        boolean stdoutClosed = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline && errBuf.length() < 4096) {
            if (err.available() > 0) {
                int value = err.read();
                if (value < 0 || value == '\n') return errBuf.toString();
                if (value != '\r') errBuf.append((char) value);
                continue;
            }
            if (!stdoutClosed && out.available() > 0 && outBuf.length() < 4096) {
                int value = out.read();
                if (value < 0) {
                    stdoutClosed = true;
                } else if (value == '\n') {
                    if (helloOk(outBuf.toString())) {
                        throw new KernelException("launch_unsupported");
                    }
                    outBuf.setLength(0);
                } else if (value != '\r') {
                    outBuf.append((char) value);
                }
                continue;
            }
            if (!process.isAlive()) {
                while (err.available() > 0 && errBuf.length() < 4096) {
                    int value = err.read();
                    if (value < 0 || value == '\n') return errBuf.toString();
                    if (value != '\r') errBuf.append((char) value);
                }
                break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return errBuf.toString();
    }

    private static String readLine(InputStream in, Process process, int seconds) throws IOException {
        StringBuilder builder = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline && builder.length() < 4096) {
            if (in.available() > 0) {
                int value = in.read();
                if (value < 0 || value == '\n') break;
                if (value != '\r') builder.append((char) value);
            } else if (!process.isAlive()) {
                while (in.available() > 0 && builder.length() < 4096) {
                    int value = in.read();
                    if (value < 0 || value == '\n') return builder.toString();
                    if (value != '\r') builder.append((char) value);
                }
                break;
            } else {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return builder.toString();
    }

    private void stopLocked(String id) {
        Running current = running.remove(id);
        if (current == null) return;
        try {
            if (current.kernel != null) current.kernel.stop();
        } catch (RuntimeException e) {
            System.err.println("[Kernel] 停止 " + id + " 失败: " + e.getMessage());
        }
        if (current.process != null) current.process.destroyForcibly();
        if (current.loader != null) {
            try {
                current.loader.close();
            } catch (IOException ignored) {
            }
        }
    }

    private KernelManifest require(String id) throws KernelException {
        if (id == null || id.isBlank()) throw new KernelException("unknown_kernel");
        Path manifestFile = kernelDir(id).resolve(KernelManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) throw new KernelException("unknown_kernel");
        try {
            return KernelManifest.read(manifestFile);
        } catch (IOException e) {
            throw new KernelException("invalid_package", e);
        }
    }

    private Installed find(String id) throws KernelException {
        for (Installed installed : list()) {
            if (installed.getManifest().getId().equals(id)) return installed;
        }
        throw new KernelException("unknown_kernel");
    }

    private Installed snapshot(KernelManifest manifest) {
        boolean on = enabled.contains(manifest.getId());
        Health known = health.get(manifest.getId());
        String code = known != null ? known.code : (on ? HEALTH_INSTALLED : HEALTH_DISABLED);
        String detail = known != null ? known.detail : "";
        return new Installed(manifest, on, code, detail);
    }

    private Path kernelDir(String id) {
        return root.resolve(id);
    }

    private void ensureArtifact(Path dest, KernelManifest manifest) throws KernelException {
        Path artifact = resolveInside(dest, manifest.getArtifact());
        if (!Files.isRegularFile(artifact)) throw new KernelException("missing_artifact");
    }

    private static void markExecutable(Path dest, KernelManifest manifest) {
        if (!"rust".equals(manifest.getRuntime())
                && !manifest.getArtifact().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            return;
        }
        try {
            Path artifact = resolveInside(dest, manifest.getArtifact());
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(artifact);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(artifact, permissions);
        } catch (UnsupportedOperationException | IOException | KernelException ignored) {
        }
    }

    private static Path resolveInside(Path root, String relative) throws KernelException {
        if (KernelManifest.isUnsafeRelative(relative)) throw new KernelException("invalid_entry");
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.normalize())) throw new KernelException("invalid_entry");
        return resolved;
    }

    private void copyRegularFiles(Path source, Path dest) throws IOException, KernelException {
        Files.createDirectories(dest);
        int[] count = {0};
        long[] bytes = {0};
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                if (Files.isSymbolicLink(path)) continue;
                Path rel = source.relativize(path);
                if (rel.toString().isEmpty()) continue;
                if (KernelManifest.isUnsafeRelative(rel.toString().replace('\\', '/'))) {
                    throw new KernelException("invalid_entry");
                }
                Path target = dest.resolve(rel).normalize();
                if (!target.startsWith(dest.normalize())) throw new KernelException("invalid_entry");
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!Files.isRegularFile(path)) continue;
                count[0]++;
                bytes[0] += Files.size(path);
                if (count[0] > MAX_FILES || bytes[0] > MAX_BYTES) throw new KernelException("too_large");
                Files.createDirectories(target.getParent());
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static KernelManifest readZipManifest(Path zip) throws KernelException, IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry entry = file.getEntry(KernelManifest.FILE_NAME);
            if (entry == null) throw new KernelException("missing_manifest");
            String json = new String(file.getInputStream(entry).readNBytes(64 * 1024), StandardCharsets.UTF_8);
            return KernelManifest.parse(json);
        }
    }

    private static void extractZip(Path zip, Path dest) throws KernelException, IOException {
        Files.createDirectories(dest);
        int count = 0;
        long bytes = 0;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank() || KernelManifest.isUnsafeRelative(name)) {
                    throw new KernelException("invalid_entry");
                }
                Path target = dest.resolve(name).normalize();
                if (!target.startsWith(dest.normalize())) throw new KernelException("invalid_entry");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                count++;
                long size = entry.getSize();
                if (size > MAX_BYTES) throw new KernelException("too_large");
                bytes += Math.max(0, size);
                if (count > MAX_FILES || bytes > MAX_BYTES) throw new KernelException("too_large");
                Files.createDirectories(target.getParent());
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path backupData(Path dest) throws IOException {
        Path data = dest.resolve("data");
        if (!Files.isDirectory(data)) return null;
        Path backup = Files.createTempDirectory("pmcl-kernel-data-");
        copyQuiet(data, backup);
        return backup;
    }

    private void restoreData(Path dest, Path backup) throws IOException {
        if (backup == null) return;
        try {
            copyQuiet(backup, dest.resolve("data"));
        } finally {
            deleteTree(backup);
        }
    }

    private static void copyQuiet(Path source, Path dest) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path rel = source.relativize(path);
                Path target = dest.resolve(rel);
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else if (Files.isRegularFile(path)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void loadState() {
        enabled.clear();
        if (!Files.isRegularFile(stateFile)) return;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (obj.has("enabled") && obj.get("enabled").isJsonArray()) {
                obj.getAsJsonArray("enabled").forEach(item -> {
                    if (item.isJsonPrimitive()) enabled.add(item.getAsString());
                });
            }
        } catch (RuntimeException | IOException e) {
            System.err.println("[Kernel] 读取启用状态失败: " + e.getMessage());
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(root);
            JsonObject obj = new JsonObject();
            var array = new com.google.gson.JsonArray();
            for (String id : enabled) array.add(id);
            obj.add("enabled", array);
            Path tmp = stateFile.resolveSibling("state.json.tmp");
            Files.writeString(tmp, GSON.toJson(obj), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Kernel] 写入启用状态失败: " + e.getMessage());
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String shortText(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\n]]", " ").strip();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    private static final class Health {
        final String code;
        final String detail;

        Health(String code, String detail) {
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final class Running {
        final LauncherKernel kernel;
        final URLClassLoader loader;
        final Process process;

        private Running(LauncherKernel kernel, URLClassLoader loader, Process process) {
            this.kernel = kernel;
            this.loader = loader;
            this.process = process;
        }

        static Running java(LauncherKernel kernel, URLClassLoader loader) {
            return new Running(kernel, loader, null);
        }

        static Running process(Process process) {
            return new Running(null, null, process);
        }
    }

    private static final class DefaultHost implements KernelHost {
        private final String id;
        private final Path data;

        DefaultHost(String id, Path data) {
            this.id = id;
            this.data = data;
        }

        @Override public Path dataDirectory() { return data; }

        @Override public void info(String message) {
            System.err.println("[kernel:" + id + "] " + (message == null ? "" : message));
        }
    }

    /** 已安装内核的清单、开关和最近一次启动结果。 */
    public static final class Installed {
        private final KernelManifest manifest;
        private final boolean active;
        private final String health;
        private final String detail;

        Installed(KernelManifest manifest, boolean active, String health, String detail) {
            this.manifest = manifest;
            this.active = active;
            this.health = health;
            this.detail = detail == null ? "" : detail;
        }

        public KernelManifest getManifest() { return manifest; }
        public boolean isEnabled() { return active; }
        public String getHealth() { return health; }
        public String getDetail() { return detail; }
    }
}
