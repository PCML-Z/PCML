package com.pmcl.core.nativeclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.i18n.I18n;
import com.pmcl.core.util.SafeZipExtractor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 没有对应发行包时，用用户选定的编译器从 GitHub 源码构建。工具路径存在
 * {@code native-clients/compile-runtime.json}，不写入 preferences.json。
 */
public final class NativeClientCompiler {

    public static final class Built {
        final Path executable;
        final Path workDir;

        Built(Path executable, Path workDir) {
            this.executable = executable;
            this.workDir = workDir;
        }
    }

    /** 已保存的路径，以及当前 PATH 上检测到的路径。 */
    public static final class ToolPaths {
        private final JsonObject saved;
        private final JsonObject detected;

        ToolPaths(JsonObject saved, JsonObject detected) {
            this.saved = saved;
            this.detected = detected;
        }

        public String saved(String tool) {
            return text(saved, tool);
        }

        public String detected(String tool) {
            return text(detected, tool);
        }

        private static String text(JsonObject object, String key) {
            return NativeClientCompiler.text(object, key);
        }
    }

    /** 构建命令之外、编译时放到 PATH 上的配套工具。 */
    static final List<String> COMPANIONS = List.of("GCC", "CLANG", "PYTHON", "NODE", "JAVA", "GRADLE");

    /** 并行数、超时、发布或调试，以及每种构建工具的附加参数。 */
    public static final class Options {
        private final int jobs;
        private final int timeoutMinutes;
        private final String mode;
        private final JsonObject args;

        Options(int jobs, int timeoutMinutes, String mode, JsonObject args) {
            this.jobs = jobs;
            this.timeoutMinutes = timeoutMinutes;
            this.mode = mode;
            this.args = args == null ? new JsonObject() : args;
        }

        public int jobs() { return jobs; }
        public int timeoutMinutes() { return timeoutMinutes; }
        public String mode() { return mode; }
        public boolean release() { return !"debug".equals(mode); }
        public String args(String tool) { return text(args, tool); }

        static Options defaults() {
            return new Options(0, 45, "release", new JsonObject());
        }
    }

    private NativeClientCompiler() {}

    public static ToolPaths tools(Path pmclHome) {
        JsonObject saved = readStore(pmclHome);
        JsonObject detected = new JsonObject();
        for (NativeClientCatalog.BuildTool tool : NativeClientCatalog.BuildTool.values()) {
            detected.addProperty(tool.name(), detect(tool, System.getenv("PATH")));
        }
        for (String companion : COMPANIONS) {
            detected.addProperty(companion, detectKey(companion, System.getenv("PATH")));
        }
        return new ToolPaths(saved, detected);
    }

    public static Options options(Path pmclHome) {
        JsonObject store = readStore(pmclHome);
        if (!store.has("options") || !store.get("options").isJsonObject()) return Options.defaults();
        JsonObject object = store.getAsJsonObject("options");
        int jobs = clamped(object, "jobs", 0, 0, 64);
        int timeout = clamped(object, "timeoutMinutes", 45, 5, 180);
        String mode = "debug".equals(text(object, "mode")) ? "debug" : "release";
        JsonObject args = object.has("args") && object.get("args").isJsonObject()
                ? object.getAsJsonObject("args") : new JsonObject();
        return new Options(jobs, timeout, mode, args);
    }

    public static void saveOptions(Path pmclHome, String jobsText, String timeoutText, String mode,
                                   Map<String, String> extraArgs) throws IOException {
        int jobs = parseJobs(jobsText);
        int timeout = parseTimeout(timeoutText);
        String selected = mode == null || mode.isBlank() ? "release" : mode.trim();
        if (!"release".equals(selected) && !"debug".equals(selected)) {
            throw new IOException(I18n.t("native.options_invalid"));
        }
        JsonObject args = new JsonObject();
        if (extraArgs != null) {
            for (Map.Entry<String, String> entry : extraArgs.entrySet()) {
                String tool = canonicalTool(entry.getKey());
                if (tool == null || COMPANIONS.contains(tool)) throw new IOException(I18n.t("native.unknown_tool"));
                String cleaned = entry.getValue() == null ? "" : entry.getValue().trim();
                if (cleaned.isEmpty()) continue;
                splitArgs(cleaned);
                args.addProperty(tool, cleaned);
            }
        }
        JsonObject options = new JsonObject();
        options.addProperty("jobs", jobs);
        options.addProperty("timeoutMinutes", timeout);
        options.addProperty("mode", selected);
        options.add("args", args);
        JsonObject store = readStore(pmclHome);
        store.add("options", options);
        writeStore(pmclHome, store);
    }

    public static void save(Path pmclHome, String tool, String path) throws IOException {
        String key = canonicalTool(tool);
        if (key == null) throw new IOException(I18n.t("native.unknown_tool"));
        JsonObject store = readStore(pmclHome);
        String cleaned = path == null ? "" : path.trim();
        if (cleaned.isEmpty()) {
            store.remove(key);
        } else {
            if (cleaned.indexOf('\0') >= 0 || cleaned.indexOf('\n') >= 0 || cleaned.indexOf('\r') >= 0) {
                throw new IOException(I18n.t("native.toolchain_rejected"));
            }
            store.addProperty(key, Path.of(cleaned).toAbsolutePath().normalize().toString());
        }
        writeStore(pmclHome, store);
    }

    public static String saved(Path pmclHome, NativeClientCatalog.BuildTool tool) {
        return text(readStore(pmclHome), tool.name());
    }

    static String detect(NativeClientCatalog.BuildTool tool, String pathEnv) {
        if (tool == null) return "";
        return detectKey(tool.name(), pathEnv);
    }

    static String detectKey(String key, String pathEnv) {
        if (key == null || pathEnv == null || pathEnv.isBlank()) return "";
        String[] names = namesFor(key);
        if (names.length == 0) return "";
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : names) {
                Path candidate = Path.of(dir).resolve(name);
                if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return "";
    }

    static List<List<String>> commands(NativeClientCatalog.BuildTool tool, Path toolchain, Path project, String outputName)
            throws IOException {
        return commands(tool, toolchain, project, outputName, Options.defaults());
    }

    static List<List<String>> commands(NativeClientCatalog.BuildTool tool, Path toolchain, Path project,
                                       String outputName, Options options) throws IOException {
        Options selected = options == null ? Options.defaults() : options;
        String bin = toolchain.toAbsolutePath().normalize().toString();
        String out = project.resolve(outputName).toAbsolutePath().normalize().toString();
        List<String> extra = splitArgs(selected.args(tool.name()));
        boolean release = selected.release();
        String config = release ? "Release" : "Debug";
        return switch (tool) {
            case CARGO -> List.of(withExtra(List.of(bin, "build"), release ? List.of("--release") : List.of(), extra));
            case GO -> List.of(withExtra(List.of(bin, "build", "-o", out, "."), List.of(), extra));
            case CMAKE -> List.of(
                    withExtra(List.of(bin, "-S", ".", "-B", "build", "-DCMAKE_BUILD_TYPE=" + config), List.of(), extra),
                    withExtra(List.of(bin, "--build", "build", "--config", config),
                            selected.jobs() > 0 ? List.of("--parallel", Integer.toString(selected.jobs())) : List.of(),
                            List.of()));
            case MAKE -> List.of(withExtra(List.of(bin),
                    selected.jobs() > 0 ? List.of("-j" + selected.jobs()) : List.of(), extra));
            case DOTNET -> List.of(withExtra(List.of(bin, "build", "-c", config), List.of(), extra));
            case ZIG -> List.of(withExtra(
                    List.of(bin, "build"),
                    selected.jobs() > 0 ? List.of("-j" + selected.jobs()) : List.of(),
                    List.of("-Doptimize=" + (release ? "ReleaseFast" : "Debug")),
                    extra));
        };
    }

    static Path requireToolchain(String toolchain, Path clientRoot) throws IOException {
        if (toolchain == null || toolchain.isBlank()) throw new IOException(I18n.t("native.need_runtime"));
        if (toolchain.indexOf('\0') >= 0) throw new IOException(I18n.t("native.toolchain_rejected"));
        Path tool = Path.of(toolchain).toAbsolutePath().normalize();
        if (!Files.isRegularFile(tool)) throw new IOException(I18n.t("native.toolchain_missing", tool.toString()));
        if (clientRoot != null && tool.startsWith(clientRoot.toAbsolutePath().normalize())) {
            throw new IOException(I18n.t("native.toolchain_rejected"));
        }
        return tool;
    }

    static Built compile(Path pmclHome, DownloadManager downloads, NativeClientCatalog.Spec spec,
                         String toolchain, Consumer<String> status, BiConsumer<Long, Long> bytes) throws IOException {
        Path root = pmclHome.resolve("native-clients").resolve(spec.id);
        Path tool = requireToolchain(toolchain, root);
        if (status != null) status.accept(I18n.t("native.downloading_source"));
        Path zip = root.resolve("download").resolve("source.zip");
        Files.createDirectories(zip.getParent());
        downloadSource(downloads, spec, zip, bytes);
        Path src = root.resolve("src");
        deleteTree(src);
        Files.createDirectories(src);
        if (status != null) status.accept(I18n.t("native.extracting"));
        SafeZipExtractor.extractSafely(zip, src);
        Path project = findProject(src, spec.buildTool);
        if (project == null) throw new IOException(I18n.t("native.no_build_file"));
        Path log = root.resolve("compile.log");
        Files.writeString(log, "", StandardCharsets.UTF_8);
        String outputName = outputName(spec);
        Options options = options(pmclHome);
        for (List<String> command : commands(spec.buildTool, tool, project, outputName, options)) {
            if (status != null) status.accept(I18n.t("native.compiling", I18n.t(spec.nameKey)));
            run(command, project, tool.getParent(), log, options, pmclHome);
        }
        Path executable = NativeClientInstaller.findExecutable(project, spec.executableNames);
        if (executable == null) throw new IOException(I18n.t("native.no_executable"));
        return new Built(executable, project);
    }

    private static void downloadSource(DownloadManager downloads, NativeClientCatalog.Spec spec, Path zip,
                                      BiConsumer<Long, Long> bytes) throws IOException {
        IOException last = null;
        for (String branch : new String[]{"main", "master"}) {
            String url = "https://github.com/" + spec.owner + "/" + spec.repo + "/archive/refs/heads/" + branch + ".zip";
            try {
                NativeClientInstaller.download(downloads, url, zip, 0L, bytes);
                return;
            } catch (IOException e) {
                last = e;
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (!message.contains("404")) throw e;
            }
        }
        throw last == null ? new IOException(I18n.t("native.no_source")) : last;
    }

    static Path findProject(Path extracted, NativeClientCatalog.BuildTool tool) throws IOException {
        if (!Files.isDirectory(extracted)) return null;
        Path best = null;
        int bestDepth = Integer.MAX_VALUE;
        boolean bestSln = false;
        try (var walk = Files.walk(extracted, 5)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                boolean sln = name.endsWith(".sln");
                if (!matchesProjectFile(tool, name)) continue;
                int depth = extracted.relativize(path).getNameCount();
                boolean better = depth < bestDepth || (tool == NativeClientCatalog.BuildTool.DOTNET && depth == bestDepth && sln && !bestSln);
                if (better) {
                    bestDepth = depth;
                    best = path.getParent();
                    bestSln = sln;
                }
            }
        }
        return best;
    }

    private static boolean matchesProjectFile(NativeClientCatalog.BuildTool tool, String name) {
        return switch (tool) {
            case CARGO -> name.equals("Cargo.toml");
            case GO -> name.equals("go.mod");
            case CMAKE -> name.equals("CMakeLists.txt");
            case MAKE -> name.equals("Makefile") || name.equals("makefile");
            case DOTNET -> name.endsWith(".sln") || name.endsWith(".csproj");
            case ZIG -> name.equals("build.zig");
        };
    }

    static String outputName(NativeClientCatalog.Spec spec) {
        boolean windows = "windows".equals(NativeClientCatalog.osToken());
        for (String name : spec.executableNames) {
            boolean exe = name.toLowerCase(Locale.ROOT).endsWith(".exe");
            boolean jar = name.toLowerCase(Locale.ROOT).endsWith(".jar");
            if (jar) continue;
            if (windows == exe) return name;
        }
        return spec.executableNames[0];
    }

    private static void run(List<String> command, Path project, Path toolchainDir, Path log,
                            Options options, Path pmclHome) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        applyEnvironment(builder.environment(), toolchainDir, options, pmclHome);
        Process process;
        try {
            process = builder.start();
            boolean finished = process.waitFor(options.timeoutMinutes(), TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException(I18n.t("native.build_timeout"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(I18n.t("native.build_timeout"));
        }
        if (process.exitValue() != 0) {
            throw new IOException(I18n.t("native.compile_failed", tail(log)));
        }
    }

    private static String tail(Path log) {
        if (!Files.isRegularFile(log)) return "";
        try (SeekableByteChannel channel = Files.newByteChannel(log)) {
            long size = channel.size();
            int count = (int) Math.min(size, 800);
            if (count <= 0) return "";
            channel.position(size - count);
            ByteBuffer buffer = ByteBuffer.allocate(count);
            channel.read(buffer);
            return new String(buffer.array(), StandardCharsets.UTF_8).replace('\r', ' ').replace('\n', ' ').trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static String[] binaryNames(NativeClientCatalog.BuildTool tool) {
        return namesFor(tool.name());
    }

    private static String[] namesFor(String key) {
        return switch (key) {
            case "CARGO" -> new String[]{"cargo", "cargo.exe"};
            case "GO" -> new String[]{"go", "go.exe"};
            case "CMAKE" -> new String[]{"cmake", "cmake.exe"};
            case "MAKE" -> new String[]{"make", "mingw32-make.exe", "make.exe"};
            case "DOTNET" -> new String[]{"dotnet", "dotnet.exe"};
            case "ZIG" -> new String[]{"zig", "zig.exe"};
            case "GCC" -> new String[]{"gcc", "gcc.exe"};
            case "CLANG" -> new String[]{"clang", "clang.exe"};
            case "PYTHON" -> new String[]{"python3", "python", "python.exe"};
            case "NODE" -> new String[]{"node", "node.exe"};
            case "JAVA" -> new String[]{"java", "java.exe"};
            case "GRADLE" -> new String[]{"gradle", "gradle.bat", "gradle.exe"};
            default -> new String[0];
        };
    }

    static List<String> splitArgs(String raw) throws IOException {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.trim().split("\\s+");
        if (parts.length > 12) throw new IOException(I18n.t("native.options_invalid"));
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.length() > 80 || !part.matches("[\\p{L}\\p{N}_.+=,@:/-]+")) {
                throw new IOException(I18n.t("native.options_invalid"));
            }
            out.add(part);
        }
        return List.copyOf(out);
    }

    private static List<String> withExtra(List<String> head, List<String>... more) {
        List<String> out = new ArrayList<>(head);
        for (List<String> part : more) out.addAll(part);
        return List.copyOf(out);
    }

    private static void applyEnvironment(Map<String, String> env, Path toolchainDir, Options options, Path pmclHome) {
        String pathKey = "PATH";
        for (String key : env.keySet()) {
            if (key.equalsIgnoreCase("PATH")) {
                pathKey = key;
                break;
            }
        }
        String current = env.getOrDefault(pathKey, "");
        StringBuilder prefix = new StringBuilder();
        prefix.append(toolchainDir.toAbsolutePath().normalize());
        JsonObject store = readStore(pmclHome);
        for (String companion : COMPANIONS) {
            String saved = text(store, companion);
            if (saved.isEmpty()) continue;
            Path file = Path.of(saved);
            if (!Files.isRegularFile(file)) continue;
            prefix.append(java.io.File.pathSeparator).append(file.getParent().toAbsolutePath().normalize());
            if ("GCC".equals(companion) || ("CLANG".equals(companion) && text(store, "GCC").isEmpty())) {
                env.put("CC", file.toAbsolutePath().normalize().toString());
                Path cxx = cxxBeside(file);
                if (cxx != null) env.put("CXX", cxx.toAbsolutePath().normalize().toString());
            }
        }
        env.put(pathKey, prefix + java.io.File.pathSeparator + current);
        env.put("CARGO_TERM_COLOR", "never");
        if (options.jobs() > 0) {
            env.put("CARGO_BUILD_JOBS", Integer.toString(options.jobs()));
            env.put("GOMAXPROCS", Integer.toString(options.jobs()));
        }
    }

    static Path cxxBeside(Path cc) {
        String name = cc.getFileName().toString();
        String next = switch (name) {
            case "gcc" -> "g++";
            case "gcc.exe" -> "g++.exe";
            case "clang" -> "clang++";
            case "clang.exe" -> "clang++.exe";
            default -> "";
        };
        if (next.isEmpty()) return null;
        Path sibling = cc.resolveSibling(next);
        return Files.isRegularFile(sibling) ? sibling : null;
    }

    private static String canonicalTool(String tool) {
        NativeClientCatalog.BuildTool parsed = parseTool(tool);
        if (parsed != null) return parsed.name();
        if (tool == null) return null;
        String upper = tool.trim().toUpperCase(Locale.ROOT);
        for (String companion : COMPANIONS) {
            if (companion.equals(upper)) return companion;
        }
        return null;
    }

    private static int parseJobs(String text) throws IOException {
        if (text == null || text.isBlank()) return 0;
        try {
            int jobs = Integer.parseInt(text.trim());
            if (jobs < 0 || jobs > 64) throw new NumberFormatException();
            return jobs;
        } catch (NumberFormatException e) {
            throw new IOException(I18n.t("native.options_invalid"));
        }
    }

    private static int parseTimeout(String text) throws IOException {
        if (text == null || text.isBlank()) return 45;
        try {
            int minutes = Integer.parseInt(text.trim());
            if (minutes < 5 || minutes > 180) throw new NumberFormatException();
            return minutes;
        } catch (NumberFormatException e) {
            throw new IOException(I18n.t("native.options_invalid"));
        }
    }

    private static int clamped(JsonObject object, String key, int fallback, int min, int max) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return fallback;
        try {
            int value = object.get(key).getAsInt();
            if (value < min || value > max) return fallback;
            return value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void writeStore(Path pmclHome, JsonObject store) throws IOException {
        Path file = storeFile(pmclHome);
        Files.createDirectories(file.getParent());
        Files.writeString(file, store.toString(), StandardCharsets.UTF_8);
    }

    private static NativeClientCatalog.BuildTool parseTool(String tool) {
        if (tool == null) return null;
        try {
            return NativeClientCatalog.BuildTool.valueOf(tool.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonObject readStore(Path pmclHome) {
        Path file = storeFile(pmclHome);
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            var parsed = com.google.gson.JsonParser.parseString(Files.readString(file));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static Path storeFile(Path pmclHome) {
        return pmclHome.resolve("native-clients").resolve("compile-runtime.json");
    }

    private static String text(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return "";
        return element.getAsString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root) && !Files.isSymbolicLink(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 下一次解压会覆盖同名文件
                }
            });
        }
    }
}
