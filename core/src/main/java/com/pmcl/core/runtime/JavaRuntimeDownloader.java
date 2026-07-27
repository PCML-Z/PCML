package com.pmcl.core.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 自动下载 Java 运行时（Mojang 官方 Java runtime 元数据）。
 * <p>
 * 数据源：piston-meta.mojang.com/v1/products/java-runtime/manifest.json
 * 镜像：BMCLAPI 自动重写（由 DownloadManager 完成）。
 * <p>
 * 下载后解压到 {workDir}/runtimes/{arch}/{name}，由 JavaRuntimeFinder 扫描使用。
 */
public final class JavaRuntimeDownloader {

    private static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/v1/products/java-runtime/manifest.json";

    private final LauncherConfig config;
    private final DownloadManager downloadManager;

    public JavaRuntimeDownloader(LauncherConfig config, DownloadManager downloadManager) {
        this.config = config;
        this.downloadManager = downloadManager;
    }

    /** Java 运行时类型：Mojang 提供 java-runtime-alpha (8) / gamma (17) / delta (21) */
    public enum RuntimeType {
        JAVA_8("java-runtime-alpha", "Java 8"),
        JAVA_17("java-runtime-gamma", "Java 17"),
        JAVA_21("java-runtime-delta", "Java 21");

        private final String mojangId;
        private final String displayName;
        RuntimeType(String id, String name) { this.mojangId = id; this.displayName = name; }
        public String getMojangId() { return mojangId; }
        public String getDisplayName() { return displayName; }
    }

    /** 运行时条目 */
    public static final class RuntimeEntry {
        private final String name;
        private final String version;
        private final String url;
        private final String sha1;
        private final long size;

        public RuntimeEntry(String name, String version, String url, String sha1, long size) {
            this.name = name; this.version = version; this.url = url;
            this.sha1 = sha1; this.size = size;
        }
        public String getName() { return name; }
        public String getVersion() { return version; }
        public String getUrl() { return url; }
        public String getSha1() { return sha1; }
        public long getSize() { return size; }
    }

    /**
     * 列出某类型下所有可用运行时条目。
     */
    public CompletableFuture<List<RuntimeEntry>> listRuntimes(RuntimeType type) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String arch = resolveArch(type);
                // 龙芯等 Mojang 清单不支持的架构：返回空列表
                if (arch == null) return new ArrayList<>();
                String json = downloadManager.downloadString(MANIFEST_URL);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                // 结构: [arch][type][entry...]
                if (!root.has(arch)) return new ArrayList<>();
                JsonObject archObj = root.getAsJsonObject(arch);
                if (!archObj.has(type.getMojangId())) return new ArrayList<>();
                JsonArray arr = archObj.getAsJsonArray(type.getMojangId());
                List<RuntimeEntry> result = new ArrayList<>();
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    JsonObject man = o.getAsJsonObject("manifest");
                    if (man == null) continue;
                    RuntimeEntry entry = new RuntimeEntry(
                            o.has("version") ? o.get("version").getAsString() : type.name(),
                            o.has("version") ? o.get("version").getAsString() : "?",
                            man.has("url") ? man.get("url").getAsString() : "",
                            man.has("sha1") ? man.get("sha1").getAsString() : "",
                            man.has("size") ? man.get("size").getAsLong() : 0L);
                    result.add(entry);
                }
                return result;
            } catch (Throwable e) {
                throw new RuntimeException("拉取 Java 运行时清单失败", e);
            }
        });
    }

    private static final String READY_MARKER = ".pmcl-runtime-ok";

    /**
     * 下载并解压指定运行时到 runtimes 目录。
     * 写入 {@code *.staging} 再原子提升，避免半成品被扫描为可用 Java。
     */
    public CompletableFuture<Void> install(RuntimeType type, RuntimeEntry entry,
                                           Consumer<String> onStatus) {
        return CompletableFuture.runAsync(() -> {
            Path stagingDir = null;
            Path archive = null;
            try {
                String arch = resolveArch(type);
                if (arch == null) {
                    throw new RuntimeException("当前架构不支持自动下载 Java（Mojang 清单无对应包），请手动安装对应架构的 JDK");
                }
                Path runtimesDir = config.getRuntimesDir();
                Path archDir = runtimesDir.toAbsolutePath().normalize().resolve(arch).normalize();
                if (!archDir.startsWith(runtimesDir.toAbsolutePath().normalize())) {
                    throw new IOException("非法架构目录: " + arch);
                }
                String safeVersion = sanitizeRuntimeVersion(entry.getVersion());
                String dirName = type.name() + "-" + safeVersion;
                Path targetDir = assertUnder(archDir, archDir.resolve(dirName));
                if (isRuntimeReady(targetDir)) {
                    if (onStatus != null) onStatus.accept("已存在：" + targetDir);
                    return;
                }
                // 清理上次失败留下的半成品
                if (Files.exists(targetDir)) {
                    FileUtils.deleteRecursively(targetDir);
                }
                Files.createDirectories(archDir);
                stagingDir = assertUnder(archDir, archDir.resolve(dirName + ".staging"));
                FileUtils.deleteRecursively(stagingDir);

                String url = entry.getUrl();
                String ext = url.endsWith(".zip") ? ".zip" : ".tar.gz";
                archive = assertUnder(archDir, archDir.resolve(dirName + ext));
                if (onStatus != null) onStatus.accept("下载: " + url);
                String expectedSha1 = entry.getSha1();
                if (expectedSha1 == null || expectedSha1.isBlank()) {
                    throw new IOException("运行时清单未提供 SHA-1，拒绝安装未校验的 Java 归档");
                }
                downloadManager.downloadToVerified(url, archive, expectedSha1, null);
                if (onStatus != null) onStatus.accept("SHA-1 校验通过");

                Files.createDirectories(stagingDir);
                if (onStatus != null) onStatus.accept("解压到: " + stagingDir);
                extractArchive(archive, stagingDir);
                Files.deleteIfExists(archive);
                archive = null;

                if (!hasJavaBin(stagingDir)) {
                    throw new IOException("解压后未找到可用 java 可执行文件: " + stagingDir);
                }
                Files.writeString(stagingDir.resolve(READY_MARKER), "ok");

                Path bakDir = assertUnder(archDir, archDir.resolve(dirName + ".bak"));
                FileUtils.deleteRecursively(bakDir);
                try {
                    try {
                        Files.move(stagingDir, targetDir, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(stagingDir, targetDir);
                    }
                } catch (IOException e) {
                    throw new IOException("无法提升 Java 运行时目录: " + dirName, e);
                }
                stagingDir = null;
                if (onStatus != null) onStatus.accept("完成: " + targetDir);
            } catch (IOException e) {
                if (archive != null) {
                    try { Files.deleteIfExists(archive); } catch (IOException ignored) {}
                }
                if (stagingDir != null) {
                    FileUtils.deleteRecursively(stagingDir);
                }
                throw new RuntimeException("Java 运行时安装失败", e);
            }
        });
    }

    private static boolean isRuntimeReady(Path targetDir) {
        // 兼容旧安装（无 marker）：只要能解析到 java 可执行文件即视为可用
        return Files.isDirectory(targetDir) && hasJavaBin(targetDir);
    }

    private static boolean hasJavaBin(Path jvmDir) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        boolean win = os.contains("win");
        // Mojang 包可能多一层目录（如 jre / jdk-*）
        Path[] candidates = {
                jvmDir.resolve("bin").resolve(win ? "java.exe" : "java"),
                jvmDir.resolve("jre").resolve("bin").resolve(win ? "java.exe" : "java")
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) return true;
        }
        try (var stream = Files.list(jvmDir)) {
            return stream.filter(Files::isDirectory)
                    .anyMatch(child -> Files.isRegularFile(
                            child.resolve("bin").resolve(win ? "java.exe" : "java")));
        } catch (IOException e) {
            return false;
        }
    }

    private void extractArchive(Path archive, Path target) throws IOException {
        String name = archive.getFileName().toString();
        if (name.endsWith(".zip")) {
            // 纯 Java 解压 zip，避免依赖外部 unzip/powershell
            extractZip(archive, target);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            // tar.gz 无法用 Java 内置 API 处理，保留外部 tar 命令
            extractTarGz(archive, target);
        } else {
            throw new IOException("不支持的归档格式: " + name);
        }
    }

    private static void extractZip(Path archive, Path target) throws IOException {
        // ZipSlip / ZipBomb：失败即中止，不静默跳过穿越条目
        com.pmcl.core.util.SafeZipExtractor.extractSafely(archive, target);
    }

    private static void extractTarGz(Path archive, Path target) throws IOException {
        // 先检查 tar 是否可用
        Process check = null;
        try {
            check = new ProcessBuilder("tar", "--version").redirectErrorStream(true).start();
            if (!check.waitFor(3, TimeUnit.SECONDS) || check.exitValue() != 0) {
                throw new IOException("tar 不可用");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("tar 可用性检查被中断", ie);
        } catch (IOException ioe) {
            throw new IOException("tar.gz 解压需要系统 tar 命令，但当前环境不可用。"
                    + "请安装 tar（macOS/Linux 自带，Windows 需启用 WSL 或安装 bsdtar），"
                    + "或手动解压 " + archive + " 到 " + target, ioe);
        } finally {
            if (check != null) check.destroyForcibly();
        }
        // 解压前预检：路径穿越 + 符号链接/特殊文件类型
        assertTarMembersSafe(archive);

        // M82: 不用 pb.inheritIO()——子进程 stdout/stderr 直接继承会污染启动器日志。
        // 改为 redirectErrorStream + 丢弃输出，错误时仅记录摘要到 stderr。
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", target.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = null;
        try {
            p = pb.start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("解压超时（120s）: " + archive);
            }
            int code = p.exitValue();
            if (code != 0) {
                throw new IOException("tar 解压失败 code=" + code + "（archive=" + archive
                        + ", target=" + target + "）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解压被中断", e);
        } finally {
            if (p != null) p.destroyForcibly();
        }
        // 解压后二次校验：拒绝逃逸 staging 的符号链接 / 非常规文件
        assertExtractedTreeSafe(target);
    }

    /** 运行时版本号只允许安全文件名字符，防止路径穿越。 */
    static String sanitizeRuntimeVersion(String version) throws IOException {
        if (version == null || version.isBlank()) {
            throw new IOException("运行时版本号为空");
        }
        String v = version.trim();
        if (v.length() > 64 || !v.matches("[A-Za-z0-9._-]+") || v.contains("..")) {
            throw new IOException("非法运行时版本号: " + version);
        }
        return v;
    }

    private static Path assertUnder(Path base, Path child) throws IOException {
        Path b = base.toAbsolutePath().normalize();
        Path c = child.toAbsolutePath().normalize();
        if (!c.startsWith(b)) {
            throw new IOException("路径越界: " + child + " not under " + base);
        }
        return c;
    }

    /**
     * 用 {@code tar -tvzf} 流式预检：拒绝路径穿越、符号/硬链接与特殊设备节点；
     * 限制条目数与列表输出总字节，避免恶意归档撑爆内存或堵死管道。
     */
    private static void assertTarMembersSafe(Path archive) throws IOException {
        final int maxEntries = 200_000;
        final long maxListingBytes = 32L * 1024 * 1024;
        ProcessBuilder listPb = new ProcessBuilder("tar", "-tvzf", archive.toString());
        listPb.redirectErrorStream(true);
        Process list = null;
        try {
            list = listPb.start();
            long bytes = 0;
            int entries = 0;
            try (var in = list.getInputStream();
                 var reader = new java.io.BufferedReader(
                         new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    bytes += line.length() + 1L;
                    if (bytes > maxListingBytes) {
                        list.destroyForcibly();
                        throw new IOException("tar 列表过大（>" + maxListingBytes + "）: " + archive);
                    }
                    if (line.isBlank()) continue;
                    if (++entries > maxEntries) {
                        list.destroyForcibly();
                        throw new IOException("tar 条目数过多（>" + maxEntries + "）: " + archive);
                    }
                    char type = line.charAt(0);
                    // 常见 listing：- 普通文件，d 目录；拒绝 l/h/c/b/p/s 等
                    if (type != '-' && type != 'd' && !Character.isDigit(type)) {
                        // 某些 tar 首列不是模式（纯文件名列表回退场景极少）；含 " -> " 一律拒绝
                        if (type == 'l' || type == 'h' || type == 'c' || type == 'b'
                                || type == 'p' || type == 's' || line.contains(" -> ")) {
                            throw new IOException("tar 含链接或特殊文件（拒绝解压）: " + line.trim());
                        }
                    }
                    if (line.contains(" -> ")) {
                        throw new IOException("tar 含符号链接（拒绝解压）: " + line.trim());
                    }
                    String name = extractTarListName(line);
                    if (name.isEmpty()) continue;
                    if (name.startsWith("/") || name.startsWith("\\")
                            || name.contains("..")
                            || name.matches("^[A-Za-z]:[\\\\/].*")) {
                        throw new IOException("tar 含非法路径（拒绝解压）: " + name);
                    }
                }
            }
            if (!list.waitFor(60, TimeUnit.SECONDS)) {
                list.destroyForcibly();
                throw new IOException("tar 列表超时: " + archive);
            }
            if (list.exitValue() != 0) {
                throw new IOException("tar 列表失败 code=" + list.exitValue() + ": " + archive);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar 列表被中断", e);
        } finally {
            if (list != null) list.destroyForcibly();
        }
    }

    /** 从 {@code tar -tv} 行尽量取出成员名（取最后一个时间戳后的路径字段）。 */
    private static String extractTarListName(String line) {
        // GNU/BSD 典型：permissions links owner group size date time name
        // 简化：若含 " -> " 已在上层拒绝；否则取最后一个空白分隔的「看起来像路径」字段集合
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return "";
        // 若行以权限位开头，跳过前若干字段
        if (trimmed.length() > 10 && (trimmed.charAt(0) == '-' || trimmed.charAt(0) == 'd'
                || trimmed.charAt(0) == 'l')) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 6) {
                // 拼回可能含空格的文件名：从索引 5 或 6 起（date/time 后）
                int start = parts.length >= 8 ? 7 : 5;
                if (start < parts.length) {
                    StringBuilder sb = new StringBuilder(parts[start]);
                    for (int i = start + 1; i < parts.length; i++) {
                        sb.append(' ').append(parts[i]);
                    }
                    return sb.toString();
                }
            }
        }
        return trimmed;
    }

    /** 解压后拒绝逃逸目标目录的符号链接，并删除非常规特殊文件。 */
    private static void assertExtractedTreeSafe(Path target) throws IOException {
        Path base = target.toAbsolutePath().normalize();
        Path baseReal;
        try {
            baseReal = base.toRealPath();
        } catch (IOException e) {
            baseReal = base;
        }
        try (var walk = Files.walk(base)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(p)) {
                    Path linkTarget = Files.readSymbolicLink(p);
                    Path resolved = p.getParent() != null
                            ? p.getParent().resolve(linkTarget).normalize()
                            : linkTarget.toAbsolutePath().normalize();
                    if (!resolved.startsWith(base)) {
                        throw new IOException("解压产物含逃逸符号链接: " + p + " -> " + linkTarget);
                    }
                    try {
                        Path real = p.toRealPath();
                        if (!real.startsWith(baseReal)) {
                            throw new IOException("解压产物符号链接解析越界: " + p + " -> " + real);
                        }
                    } catch (IOException ignored) {
                        // 断链：删除以免后续误用
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
    }

    /**
     * 解析下载目标架构。
     * <p>
     * Apple Silicon Mac 上，老版本 Minecraft（1.12.2 及更早）的 LWJGL 2.x 原生库
     * 只有 x86_64 版本，必须通过 Rosetta 2 运行 x86_64 Java 8。
     * 因此 Java 8 在 Apple Silicon 上强制下载 macos-amd64 版本。
     * <p>
     * 龙芯（LoongArch64 / MIPS64el）与 RISC-V 64 架构在 Mojang 清单中不存在，
     * 返回 null 表示无法自动下载，调用方应提示用户手动安装对应架构的 JDK。
     */
    private static String resolveArch(RuntimeType type) {
        // 龙芯架构：Mojang 清单无对应包，返回 null
        if (com.pmcl.core.launch.JavaRuntimeFinder.isLoongson()) {
            return null;
        }
        // RISC-V 架构：Mojang 清单无对应包，返回 null
        if (com.pmcl.core.launch.JavaRuntimeFinder.isRiscV()) {
            return null;
        }
        String arch = currentArch();
        if (type == RuntimeType.JAVA_8 && "macos-arm64".equals(arch)) {
            return "macos-amd64"; // Rosetta 2
        }
        return arch;
    }

    /** Mojang Java runtime 清单使用的架构标识 */
    private static String currentArch() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm64")
                    ? "macos-arm64" : "macos-amd64";
        } else if (os.contains("win")) {
            return arch.contains("aarch64") || arch.contains("arm64")
                    ? "windows-arm64" : "windows-x64";
        } else {
            // 龙芯 LoongArch64
            if (arch.contains("loongarch64") || arch.contains("la64") || arch.contains("la464")) {
                return "linux-loongarch64";
            }
            // 龙芯旧版 MIPS64el
            if (arch.contains("mips64el") || arch.contains("mips64")) {
                return "linux-mips64el";
            }
            // RISC-V 64
            if (arch.contains("riscv64") || arch.contains("risc-v64") || arch.contains("rv64")) {
                return "linux-riscv64";
            }
            return arch.contains("aarch64") || arch.contains("arm64")
                    ? "linux-arm64" : "linux-x64";
        }
    }
}
