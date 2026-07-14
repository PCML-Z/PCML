package com.pmcl.core.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.download.DownloadManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /**
     * 下载并解压指定运行时到 runtimes 目录。
     * 简化实现：直接下载 .tar.gz / .zip 到本地后调用系统 tar/unzip 解压。
     */
    public CompletableFuture<Void> install(RuntimeType type, RuntimeEntry entry,
                                           Consumer<String> onStatus) {
        return CompletableFuture.runAsync(() -> {
            try {
                String arch = resolveArch(type);
                if (arch == null) {
                    throw new RuntimeException("当前架构不支持自动下载 Java（Mojang 清单无对应包），请手动安装对应架构的 JDK");
                }
                Path runtimesDir = config.getRuntimesDir();
                Path archDir = runtimesDir.resolve(arch);
                Path targetDir = archDir.resolve(type.name() + "-" + entry.getVersion());
                if (Files.exists(targetDir) && Files.exists(targetDir.resolve("bin"))) {
                    if (onStatus != null) onStatus.accept("已存在：" + targetDir);
                    return;
                }
                Files.createDirectories(archDir);

                // 下载归档
                String url = entry.getUrl();
                String ext = url.endsWith(".zip") ? ".zip" : ".tar.gz";
                Path archive = archDir.resolve(type.name() + "-" + entry.getVersion() + ext);
                if (onStatus != null) onStatus.accept("下载: " + url);
                downloadManager.downloadTo(url, archive);

                // 解压
                Files.createDirectories(targetDir);
                if (onStatus != null) onStatus.accept("解压到: " + targetDir);
                extractArchive(archive, targetDir);

                // 清理归档
                Files.deleteIfExists(archive);
                if (onStatus != null) onStatus.accept("完成: " + targetDir);
            } catch (IOException e) {
                throw new RuntimeException("Java 运行时安装失败", e);
            }
        });
    }

    private void extractArchive(Path archive, Path target) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String name = archive.getFileName().toString();
        ProcessBuilder pb;
        if (name.endsWith(".zip")) {
            if (os.contains("win")) {
                pb = new ProcessBuilder("powershell", "-Command",
                        "Expand-Archive", "-Path", archive.toString(), "-DestinationPath", target.toString());
            } else {
                pb = new ProcessBuilder("unzip", "-q", "-o", archive.toString(), "-d", target.toString());
            }
        } else {
            // .tar.gz
            pb = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", target.toString());
        }
        pb.inheritIO();
        Process p = null;
        try {
            p = pb.start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                throw new IOException("解压超时");
            }
            int code = p.exitValue();
            if (code != 0) throw new IOException("解压失败 code=" + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解压被中断", e);
        } finally {
            if (p != null) p.destroyForcibly();
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
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
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
