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
 * 数据源：{@code launchermeta.mojang.com/v1/products/java-runtime/.../all.json}
 * （包内是分文件清单，不是单一 zip）。镜像：BMCLAPI 自动重写（由 DownloadManager 完成）。
 * <p>
 * 龙芯 LoongArch64 架构 Mojang 清单不支持，回退到 Dragonwell (Alibaba) 官方
 * 维护的 LoongArch64 JDK 构建（GitHub Releases），覆盖 Java 8/11/17。
 * <p>
 * 下载后解压到 {workDir}/runtimes/{arch}/{name}，由 JavaRuntimeFinder 扫描使用。
 */
public final class JavaRuntimeDownloader {

    /** Mojang Java runtime 产品清单（hash 为当前稳定 all.json）。 */
    private static final String MANIFEST_URL =
            "https://launchermeta.mojang.com/v1/products/java-runtime/"
                    + "2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    /** 龙芯 LoongArch64 JDK 源：Dragonwell 官方维护，GitHub Releases API */
    private static final String LOONGSON_JDK_RELEASES_API =
            "https://api.github.com/repos/alibaba/dragonwell%s/releases/latest";
    /** Dragonwell 各 Java 版本对应的仓库版本后缀（8/11/17/21） */
    private static final java.util.Map<RuntimeType, String> LOONGSON_DRAGONWELL_REPO =
            java.util.Map.of(
                    RuntimeType.JAVA_8, "8",
                    RuntimeType.JAVA_17, "17",
                    RuntimeType.JAVA_21, "21");

    /** RISC-V 64 JDK 源：Adoptium Temurin 官方 API，支持 JDK 17/21（JDK 8 无 riscv64 构建） */
    private static final String ADOPTIUM_API_TEMPLATE =
            "https://api.adoptium.net/v3/binary/latest/%d/ga/linux/riscv64/jdk/hotspot/normal/eclipse";
    /** Adoptium 支持的 RISC-V 64 Java 版本（JDK 8 无 riscv64 构建） */
    private static final java.util.Set<RuntimeType> ADOPTIUM_RISCV_SUPPORTED =
            java.util.Set.of(RuntimeType.JAVA_17, RuntimeType.JAVA_21);

    /** 龙芯 MIPS64el JDK 源：龙芯开源社区（HTTPS）；无校验和则拒绝安装 */
    private static final String LOONGSON_MIPS_JDK8_URL =
            "https://ftp.loongnix.org/toolchain/java/openjdk8/loongson_openjdk8.1.4-jdk8u242b08-linux-loongson3a.tar.gz";

    private final LauncherConfig config;
    private final DownloadManager downloadManager;

    public JavaRuntimeDownloader(LauncherConfig config, DownloadManager downloadManager) {
        this.config = config;
        this.downloadManager = downloadManager;
    }

    /**
     * Java 运行时类型 → Mojang 产品 ID。
     * <p>
     * 注意：{@code java-runtime-alpha} 是 Java 16，不是 8；Java 8 对应 {@code jre-legacy}。
     */
    public enum RuntimeType {
        JAVA_8("jre-legacy", "Java 8"),
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
     * <p>
     * 龙芯 LoongArch64 架构改走 Dragonwell GitHub Releases，不查 Mojang 清单。
     */
    public CompletableFuture<List<RuntimeEntry>> listRuntimes(RuntimeType type) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String arch = resolveArch(type);
                // 无可用 JDK 源的架构：返回空列表
                if (arch == null) return new ArrayList<>();
                // 龙芯 LoongArch64：改走 Dragonwell GitHub Releases
                if ("linux-loongarch64".equals(arch)) {
                    return listLoongsonDragonwellRuntimes(type);
                }
                // 龙芯 MIPS64el：改走龙芯开源社区 FTP（仅 JDK 8）
                if ("linux-mips64el".equals(arch)) {
                    return listLoongsonMipsRuntimes(type);
                }
                // RISC-V 64：改走 Adoptium Temurin API（JDK 17/21）
                if ("linux-riscv64".equals(arch)) {
                    return listRiscVAdoptiumRuntimes(type);
                }
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
                    String versionName = parseRuntimeVersionName(o, type);
                    RuntimeEntry entry = new RuntimeEntry(
                            versionName,
                            versionName,
                            man.has("url") ? man.get("url").getAsString() : "",
                            man.has("sha1") ? man.get("sha1").getAsString() : "",
                            man.has("size") ? man.get("size").getAsLong() : 0L);
                    result.add(entry);
                }
                return result;
            } catch (Throwable e) {
                String detail = e.getMessage();
                if (e.getCause() != null && e.getCause().getMessage() != null) {
                    detail = detail + ": " + e.getCause().getMessage();
                }
                throw new RuntimeException("拉取 Java 运行时清单失败" + (detail != null ? "：" + detail : ""), e);
            }
        });
    }

    /** Mojang all.json 中 version 多为对象 {@code {name, released}}，少数为字符串。 */
    private static String parseRuntimeVersionName(JsonObject o, RuntimeType type) {
        if (!o.has("version") || o.get("version").isJsonNull()) {
            return type.name();
        }
        JsonElement v = o.get("version");
        if (v.isJsonPrimitive()) {
            return v.getAsString();
        }
        if (v.isJsonObject()) {
            JsonObject vo = v.getAsJsonObject();
            if (vo.has("name") && !vo.get("name").isJsonNull()) {
                return vo.get("name").getAsString();
            }
        }
        return type.name();
    }

    /**
     * 龙芯 LoongArch64：从 Dragonwell GitHub Releases 拉取 JDK 列表。
     * <p>
     * Dragonwell 是阿里巴巴维护的 OpenJDK 发行版，官方提供 LoongArch64 构建。
     * 通过 GitHub Releases API 获取最新 release，匹配 linux-loongarch64 tar.gz asset。
     * SHA-1 校验改为可选（GitHub asset 不提供 sha1，但 asset 内置下载完整性由 HTTPS 保证）。
     */
    private List<RuntimeEntry> listLoongsonDragonwellRuntimes(RuntimeType type) {
        String repoSuffix = LOONGSON_DRAGONWELL_REPO.get(type);
        if (repoSuffix == null) return new ArrayList<>();
        String apiUrl = String.format(LOONGSON_JDK_RELEASES_API, repoSuffix);
        try {
            String json = downloadManager.downloadString(apiUrl);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String version = root.has("tag_name") && !root.get("tag_name").isJsonNull()
                    ? root.get("tag_name").getAsString() : type.name();
            if (!root.has("assets")) return new ArrayList<>();
            List<RuntimeEntry> result = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("assets")) {
                JsonObject asset = e.getAsJsonObject();
                String name = asset.has("name") && !asset.get("name").isJsonNull()
                        ? asset.get("name").getAsString() : "";
                String lower = name.toLowerCase();
                // 匹配 loongarch64 的 tar.gz，排除 sources/javadoc/debug
                if (!lower.contains("loongarch64") || !lower.endsWith(".tar.gz")) continue;
                if (lower.contains("sources") || lower.contains("javadoc")
                        || lower.contains("debug") || lower.contains("static")) continue;
                String url = asset.has("browser_download_url")
                        ? asset.get("browser_download_url").getAsString() : "";
                long size = asset.has("size") ? asset.get("size").getAsLong() : 0L;
                if (url.isEmpty()) continue;
                // GitHub Releases 不提供 SHA-1，传空串表示跳过校验（install 中特殊处理）
                result.add(new RuntimeEntry(
                        "Dragonwell-" + version + "-loongarch64",
                        version, url, "", size));
            }
            return result;
        } catch (Throwable e) {
            System.err.println("[JavaRuntimeDownloader] 拉取 Dragonwell 龙芯 JDK 清单失败: "
                    + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * RISC-V 64：从 Adoptium Temurin API 获取 JDK 列表。
     * <p>
     * Adoptium 从 2024 年起官方提供 RISC-V 64 构建（JDK 17/21）。
     * API 返回重定向到实际下载 URL，SHA-256 校验文件在 {url}.sha256.txt。
     * 此处不预取下载 URL（让 install 时由 DownloadManager 跟随重定向），
     * 只返回一个伪条目，url 字段存储 API 端点。
     */
    private List<RuntimeEntry> listRiscVAdoptiumRuntimes(RuntimeType type) {
        if (!ADOPTIUM_RISCV_SUPPORTED.contains(type)) {
            // JDK 8 无 riscv64 构建
            return new ArrayList<>();
        }
        List<RuntimeEntry> result = new ArrayList<>();
        int majorVersion = type == RuntimeType.JAVA_17 ? 17 : 21;
        String apiUrl = String.format(ADOPTIUM_API_TEMPLATE, majorVersion);
        // Adoptium API 返回 302 重定向到 GitHub Releases 下载 URL，
        // DownloadManager 会自动跟随重定向。SHA-1 传空（Adoptium 提供 SHA-256，非 SHA-1）。
        result.add(new RuntimeEntry(
                "Temurin-" + majorVersion + "-riscv64",
                String.valueOf(majorVersion), apiUrl, "", 0L));
        return result;
    }

    /**
     * 龙芯 MIPS64el：从龙芯开源社区 FTP 获取 JDK 8。
     * <p>
     * 龙芯 3A3000/3A4000 等旧型号使用 MIPS64el 架构，龙芯官方仅提供 JDK 8 移植版
     * （JDK 11+ 已不再支持 MIPS64）。FTP 源为固定 URL，无 API。
     * 仅返回 JDK 8 条目，其他类型返回空列表。
     */
    private List<RuntimeEntry> listLoongsonMipsRuntimes(RuntimeType type) {
        if (type != RuntimeType.JAVA_8) {
            // 龙芯 MIPS64el 仅 JDK 8，JDK 17/21 不支持
            return new ArrayList<>();
        }
        List<RuntimeEntry> result = new ArrayList<>();
        // H38: HTTPS 优先；SHA-1 在安装时从旁路 .sha1 拉取，缺失则拒绝
        result.add(new RuntimeEntry(
                "Loongson-JDK8-mips64el",
                "8u242", LOONGSON_MIPS_JDK8_URL, "", 0L));
        return result;
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
            try {
                String arch = resolveArch(type);
                if (arch == null) {
                    throw new IOException("当前架构不支持自动下载 Java（Mojang 清单无对应包），请手动安装对应架构的 JDK");
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
                Files.createDirectories(stagingDir);

                if (isArchiveUrl(url)) {
                    installFromArchive(type, entry, arch, stagingDir, archDir, dirName, onStatus);
                } else {
                    installFromMojangPackage(entry, stagingDir, onStatus);
                }

                if (!hasJavaBin(stagingDir)) {
                    throw new IOException("安装后未找到可用 java 可执行文件: " + stagingDir);
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
                if (stagingDir != null) {
                    FileUtils.deleteRecursively(stagingDir);
                }
                throw new RuntimeException("Java 运行时安装失败: " + e.getMessage(), e);
            }
        });
    }

    private static boolean isArchiveUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        return lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
    }

    /**
     * Mojang 包：manifest.url 指向含 {@code files} 的 JSON，需逐文件下载 raw 对象。
     */
    private void installFromMojangPackage(RuntimeEntry entry, Path stagingDir,
                                          Consumer<String> onStatus) throws IOException {
        String url = entry.getUrl();
        if (url == null || url.isBlank()) {
            throw new IOException("运行时包清单 URL 为空");
        }
        if (onStatus != null) onStatus.accept("拉取包清单: " + url);
        String packageJson;
        String expectedSha1 = entry.getSha1();
        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            packageJson = downloadManager.downloadStringVerified(url, expectedSha1);
        } else {
            throw new IOException("运行时包清单未提供 SHA-1，拒绝安装");
        }
        JsonObject root = JsonParser.parseString(packageJson).getAsJsonObject();
        if (!root.has("files") || !root.get("files").isJsonObject()) {
            throw new IOException("无效的 Mojang Java 运行时包清单（缺少 files）");
        }
        JsonObject files = root.getAsJsonObject("files");
        int total = 0;
        for (String ignored : files.keySet()) total++;
        int done = 0;
        boolean win = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        for (java.util.Map.Entry<String, JsonElement> fe : files.entrySet()) {
            String rel = fe.getKey();
            if (!(fe.getValue() instanceof JsonObject)) continue;
            JsonObject meta = fe.getValue().getAsJsonObject();
            String type = meta.has("type") && !meta.get("type").isJsonNull()
                    ? meta.get("type").getAsString() : "file";
            Path dest = assertUnder(stagingDir, stagingDir.resolve(rel));
            if ("directory".equals(type)) {
                Files.createDirectories(dest);
                done++;
                continue;
            }
            if ("link".equals(type)) {
                // 安全起见跳过符号链接条目（部分平台清单会带 target）
                done++;
                continue;
            }
            if (!"file".equals(type)) {
                done++;
                continue;
            }
            JsonObject downloads = meta.has("downloads") ? meta.getAsJsonObject("downloads") : null;
            if (downloads == null || !downloads.has("raw")) {
                throw new IOException("运行时文件缺少 raw 下载项: " + rel);
            }
            JsonObject raw = downloads.getAsJsonObject("raw");
            String fileUrl = raw.has("url") ? raw.get("url").getAsString() : "";
            String fileSha1 = raw.has("sha1") ? raw.get("sha1").getAsString() : "";
            if (fileUrl.isBlank() || fileSha1.isBlank()) {
                throw new IOException("运行时文件缺少 url/sha1: " + rel);
            }
            Files.createDirectories(dest.getParent());
            done++;
            if (onStatus != null && (done == 1 || done == total || done % 20 == 0)) {
                onStatus.accept("下载运行时文件 " + done + "/" + total);
            }
            downloadManager.downloadToVerified(fileUrl, dest, fileSha1, null);
            boolean executable = meta.has("executable") && meta.get("executable").getAsBoolean();
            if (executable && !win) {
                try {
                    java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                            new java.util.HashSet<>(Files.getPosixFilePermissions(dest));
                    perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                    perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
                    perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(dest, perms);
                } catch (UnsupportedOperationException ignored) {
                    // 非 POSIX 文件系统
                }
            }
        }
        if (onStatus != null) onStatus.accept("运行时文件下载完成（" + total + "）");
    }

    /** Dragonwell / Adoptium / 龙芯 FTP：单一归档解压。 */
    private void installFromArchive(RuntimeType type, RuntimeEntry entry, String arch,
                                    Path stagingDir, Path archDir, String dirName,
                                    Consumer<String> onStatus) throws IOException {
        String url = entry.getUrl();
        String ext = url.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? ".zip" : ".tar.gz";
        Path archive = assertUnder(archDir, archDir.resolve(dirName + ext));
        try {
            if (onStatus != null) onStatus.accept("下载: " + url);
            String expectedSha1 = entry.getSha1();
            if (expectedSha1 != null && !expectedSha1.isBlank()) {
                downloadManager.downloadToVerified(url, archive, expectedSha1, null);
                if (onStatus != null) onStatus.accept("SHA-1 校验通过");
            } else {
                // H38: 无清单 SHA-1 时尝试旁路；明文 HTTP 必须有校验和
                String sidecar = tryFetchSidecarSha1(url);
                if (sidecar == null && url.startsWith("https://")) {
                    sidecar = tryFetchSidecarSha1(url.replaceFirst("^https://", "http://"));
                }
                if (sidecar != null && !sidecar.isBlank()) {
                    downloadManager.downloadToVerified(url, archive, sidecar, null);
                    if (onStatus != null) onStatus.accept("SHA-1 旁路校验通过");
                } else if (("linux-loongarch64".equals(arch) || "linux-riscv64".equals(arch))
                        && url.startsWith("https://")) {
                    if (onStatus != null) onStatus.accept("国产架构 JDK：HTTPS 源无 SHA-1，跳过校验");
                    downloadManager.downloadTo(url, archive);
                } else {
                    throw new IOException("运行时未提供 SHA-1（HTTP 源必须校验）: " + url);
                }
            }
            if (onStatus != null) onStatus.accept("解压到: " + stagingDir);
            extractArchive(archive, stagingDir);
        } finally {
            try { Files.deleteIfExists(archive); } catch (IOException ignored) {}
        }
    }

    private static boolean isRuntimeReady(Path targetDir) {
        // 兼容旧安装（无 marker）：只要能解析到 java 可执行文件即视为可用
        return Files.isDirectory(targetDir) && hasJavaBin(targetDir);
    }

    private static boolean hasJavaBin(Path jvmDir) {
        return findJavaBinary(jvmDir) != null;
    }

    /** 在运行时根目录下定位 java（含 macOS jre.bundle/Contents/Home）。 */
    public static Path findJavaBinary(Path jvmDir) {
        if (jvmDir == null || !Files.isDirectory(jvmDir)) return null;
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        boolean win = os.contains("win");
        String javaName = win ? "java.exe" : "java";
        Path[] candidates = {
                jvmDir.resolve("bin").resolve(javaName),
                jvmDir.resolve("jre").resolve("bin").resolve(javaName),
                jvmDir.resolve("Contents").resolve("Home").resolve("bin").resolve(javaName),
                jvmDir.resolve("jre.bundle").resolve("Contents").resolve("Home").resolve("bin").resolve(javaName)
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) return c;
        }
        try (var walk = Files.walk(jvmDir, 6)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        if (!javaName.equals(name)) return false;
                        Path parent = p.getParent();
                        return parent != null && "bin".equals(parent.getFileName().toString());
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
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
        String v = version.trim()
                .replace(' ', '-')
                .replace('/', '-')
                .replace('\\', '-');
        v = v.replaceAll("[^A-Za-z0-9._-]+", "-");
        while (v.contains("--")) v = v.replace("--", "-");
        if (v.startsWith("-")) v = v.substring(1);
        if (v.endsWith("-")) v = v.substring(0, v.length() - 1);
        if (v.isEmpty() || v.length() > 64 || v.contains("..")) {
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
     * 解析下载目标架构（与 Mojang all.json 顶层 key 对齐）。
     * <p>
     * Apple Silicon Mac 上，老版本 Minecraft（1.12.2 及更早）的 LWJGL 2.x 原生库
     * 只有 x86_64 版本，必须通过 Rosetta 2 运行 x86_64 Java 8。
     * Mojang 在 {@code mac-os-arm64} 下不提供 {@code jre-legacy}，因此 Java 8
     * 在 Apple Silicon 上强制使用 {@code mac-os}（x86_64）。
     * <p>
     * 龙芯 LoongArch64：Mojang 清单无对应包，但仍返回 "linux-loongarch64"，
     * 由 {@link #listRuntimes} 和 {@link #install} 改走 Dragonwell 源。
     * 龙芯旧版 MIPS64el：龙芯开源社区 FTP 提供 JDK 8，返回 "linux-mips64el"。
     * RISC-V 64：Adoptium Temurin 提供 JDK 17/21，返回 "linux-riscv64"。
     * Linux aarch64：Mojang 无包，返回 null。
     */
    private static String resolveArch(RuntimeType type) {
        // 龙芯 LoongArch64：返回架构标识，由 listRuntimes/install 改走 Dragonwell
        if (com.pmcl.core.launch.JavaRuntimeFinder.isLoongArch64()) {
            return "linux-loongarch64";
        }
        // 龙芯旧版 MIPS64el（3A 旧型号）：龙芯 FTP 提供 JDK 8
        if (com.pmcl.core.launch.JavaRuntimeFinder.isMips64el()) {
            return "linux-mips64el";
        }
        // RISC-V 64：Adoptium Temurin 提供 JDK 17/21
        if (com.pmcl.core.launch.JavaRuntimeFinder.isRiscV()) {
            return "linux-riscv64";
        }
        String arch = currentArch();
        if (arch == null) return null;
        // Apple Silicon：Java 8 仅有 mac-os（x86）的 jre-legacy，走 Rosetta 2
        if (type == RuntimeType.JAVA_8 && "mac-os-arm64".equals(arch)) {
            return "mac-os";
        }
        return arch;
    }

    /**
     * Fetch a {@code .sha1} sidecar next to an archive URL (first whitespace-delimited token).
     * Returns null if unavailable.
     */
    private String tryFetchSidecarSha1(String archiveUrl) {
        if (archiveUrl == null || archiveUrl.isBlank()) return null;
        String shaUrl = archiveUrl.endsWith(".sha1") ? archiveUrl : archiveUrl + ".sha1";
        try {
            String body = downloadManager.downloadString(shaUrl);
            if (body == null || body.isBlank()) return null;
            String token = body.trim().split("\\s+")[0].trim();
            if (token.length() < 40) return null;
            // Accept hex only
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    return null;
                }
            }
            return token.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Mojang Java runtime 清单使用的架构标识。
     * @return 平台 key，或 {@code null} 表示当前架构无 Mojang/第三方自动源
     */
    private static String currentArch() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm64")
                    ? "mac-os-arm64" : "mac-os";
        } else if (os.contains("win")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                return "windows-arm64";
            }
            if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686")) {
                return "windows-x86";
            }
            return "windows-x64";
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
            // Mojang 仅提供 linux（x86_64）与 linux-i386，无 aarch64
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                return null;
            }
            if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686")) {
                return "linux-i386";
            }
            return "linux";
        }
    }
}
