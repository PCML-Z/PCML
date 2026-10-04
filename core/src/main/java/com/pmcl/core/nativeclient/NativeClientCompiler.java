package com.pmcl.core.nativeclient;

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
            if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) return "";
            return object.get(key).getAsString();
        }
    }

    private NativeClientCompiler() {}

    public static ToolPaths tools(Path pmclHome) {
        JsonObject saved = readStore(pmclHome);
        JsonObject detected = new JsonObject();
        for (NativeClientCatalog.BuildTool tool : NativeClientCatalog.BuildTool.values()) {
            detected.addProperty(tool.name(), detect(tool, System.getenv("PATH")));
        }
        return new ToolPaths(saved, detected);
    }

    public static void save(Path pmclHome, String tool, String path) throws IOException {
        NativeClientCatalog.BuildTool parsed = parseTool(tool);
        if (parsed == null) throw new IOException(I18n.t("native.unknown_tool"));
        JsonObject store = readStore(pmclHome);
        String cleaned = path == null ? "" : path.trim();
        if (cleaned.isEmpty()) {
            store.remove(parsed.name());
        } else {
            if (cleaned.indexOf('\0') >= 0 || cleaned.indexOf('\n') >= 0 || cleaned.indexOf('\r') >= 0) {
                throw new IOException(I18n.t("native.toolchain_rejected"));
            }
            store.addProperty(parsed.name(), Path.of(cleaned).toAbsolutePath().normalize().toString());
        }
        Path file = storeFile(pmclHome);
        Files.createDirectories(file.getParent());
        Files.writeString(file, store.toString(), StandardCharsets.UTF_8);
    }

    public static String saved(Path pmclHome, NativeClientCatalog.BuildTool tool) {
        return text(readStore(pmclHome), tool.name());
    }

    static String detect(NativeClientCatalog.BuildTool tool, String pathEnv) {
        if (tool == null || pathEnv == null || pathEnv.isBlank()) return "";
        String[] names = binaryNames(tool);
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : names) {
                Path candidate = Path.of(dir).resolve(name);
                if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return "";
    }

    static List<List<String>> commands(NativeClientCatalog.BuildTool tool, Path toolchain, Path project, String outputName) {
        String bin = toolchain.toAbsolutePath().normalize().toString();
        String out = project.resolve(outputName).toAbsolutePath().normalize().toString();
        return switch (tool) {
            case CARGO -> List.of(List.of(bin, "build", "--release"));
            case GO -> List.of(List.of(bin, "build", "-o", out, "."));
            case CMAKE -> List.of(
                    List.of(bin, "-S", ".", "-B", "build", "-DCMAKE_BUILD_TYPE=Release"),
                    List.of(bin, "--build", "build", "--config", "Release"));
            case MAKE -> List.of(List.of(bin));
            case DOTNET -> List.of(List.of(bin, "build", "-c", "Release"));
            case ZIG -> List.of(List.of(bin, "build", "-Doptimize=ReleaseFast"));
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
        for (List<String> command : commands(spec.buildTool, tool, project, outputName)) {
            if (status != null) status.accept(I18n.t("native.compiling", I18n.t(spec.nameKey)));
            run(command, project, tool.getParent(), log);
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

    private static void run(List<String> command, Path project, Path toolchainDir, Path log) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Map<String, String> env = builder.environment();
        String pathKey = "PATH";
        for (String key : env.keySet()) {
            if (key.equalsIgnoreCase("PATH")) {
                pathKey = key;
                break;
            }
        }
        String current = env.getOrDefault(pathKey, "");
        env.put(pathKey, toolchainDir.toAbsolutePath().normalize() + java.io.File.pathSeparator + current);
        env.put("CARGO_TERM_COLOR", "never");
        Process process;
        try {
            process = builder.start();
            boolean finished = process.waitFor(45, TimeUnit.MINUTES);
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
        return switch (tool) {
            case CARGO -> new String[]{"cargo", "cargo.exe"};
            case GO -> new String[]{"go", "go.exe"};
            case CMAKE -> new String[]{"cmake", "cmake.exe"};
            case MAKE -> new String[]{"make", "mingw32-make.exe", "make.exe"};
            case DOTNET -> new String[]{"dotnet", "dotnet.exe"};
            case ZIG -> new String[]{"zig", "zig.exe"};
        };
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
        if (!object.has(key) || object.get(key).isJsonNull()) return "";
        return object.get(key).getAsString();
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
