package com.pmcl.core.version;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 版本管理：拉取官方版本清单、本地版本扫描。
 */
public final class VersionManager {

    private static final String VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final LauncherConfig config;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private final Gson gson = new Gson();

    public VersionManager(LauncherConfig config) {
        this.config = config;
    }

    /**
     * 远程获取所有可用版本。SSL 失败时自动 fallback 到 curl。
     */
    public CompletableFuture<List<McVersion>> fetchRemoteVersions() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Request req = new Request.Builder().url(VERSION_MANIFEST_URL).get().build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        throw new IOException("Unexpected code " + resp.code());
                    }
                    if (resp.body() == null) throw new IOException("响应体为空");
                    String body = resp.body().string();
                    return parseManifest(body);
                }
            } catch (Throwable e) {
                // SSL 握手失败：fallback 到 curl
                if (e instanceof IOException
                        && com.pmcl.core.download.CurlFallback.isSslHandshakeFailure((IOException) e)
                        && com.pmcl.core.download.CurlFallback.isAvailable()) {
                    try {
                        String body = com.pmcl.core.download.CurlFallback.getString(VERSION_MANIFEST_URL);
                        return parseManifest(body);
                    } catch (Exception curlEx) {
                        throw new RuntimeException("拉取版本清单失败（curl fallback）：" + curlEx.getMessage(), curlEx);
                    }
                }
                throw new RuntimeException("拉取版本清单失败", e);
            }
        });
    }

    private List<McVersion> parseManifest(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray versions = root.has("versions") ? root.getAsJsonArray("versions") : null;
        if (versions == null) return new ArrayList<>();
        List<McVersion> result = new ArrayList<>(versions.size());
        for (JsonElement e : versions) {
            JsonObject v = e.getAsJsonObject();
            result.add(new McVersion(
                    v.has("id") && !v.get("id").isJsonNull() ? v.get("id").getAsString() : "",
                    v.has("type") && !v.get("type").isJsonNull() ? v.get("type").getAsString() : "",
                    v.has("releaseTime") && !v.get("releaseTime").isJsonNull() ? v.get("releaseTime").getAsString() : "",
                    v.has("url") && !v.get("url").isJsonNull() ? v.get("url").getAsString() : ""
            ));
        }
        return result;
    }

    /**
     * 扫描本地已安装的版本。
     */
    public List<String> listLocalVersions() {
        Path dir = config.getVersionsDir();
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                  .forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException e) {
            throw new RuntimeException("扫描本地版本失败", e);
        }
        return names;
    }

    /**
     * 本地版本详细信息：含修改时间、是否含 jar、是否含 json、inheritsFrom。
     */
    public static final class LocalVersionInfo {
        private final String id;
        private final long lastModified;
        private final boolean hasJar;
        private final boolean hasJson;
        private final String inheritsFrom;
        private final String mainClass;
        private final String assets;

        public LocalVersionInfo(String id, long lastModified, boolean hasJar, boolean hasJson,
                                String inheritsFrom, String mainClass, String assets) {
            this.id = id; this.lastModified = lastModified;
            this.hasJar = hasJar; this.hasJson = hasJson;
            this.inheritsFrom = inheritsFrom; this.mainClass = mainClass; this.assets = assets;
        }
        public String getId() { return id; }
        public long getLastModified() { return lastModified; }
        public boolean isHasJar() { return hasJar; }
        public boolean isHasJson() { return hasJson; }
        public String getInheritsFrom() { return inheritsFrom; }
        public String getMainClass() { return mainClass; }
        public String getAssets() { return assets; }

        /** 是否可启动：必须含 json（jar 可继承自父版本） */
        public boolean isLaunchable() { return hasJson; }
    }

    /**
     * 扫描进度信息：当前扫描目录、已处理数、总数、当前版本 ID。
     */
    public static final class ScanProgress {
        private final String currentDir;
        private final int scanned;
        private final int total;
        private final String currentVersion;

        public ScanProgress(String currentDir, int scanned, int total, String currentVersion) {
            this.currentDir = currentDir;
            this.scanned = scanned;
            this.total = total;
            this.currentVersion = currentVersion;
        }
        public String getCurrentDir() { return currentDir; }
        public int getScanned() { return scanned; }
        public int getTotal() { return total; }
        public String getCurrentVersion() { return currentVersion; }
        /** 进度比例 0~1 */
        public float getFraction() { return total > 0 ? (float) scanned / total : 0f; }
        /** 是否完成 */
        public boolean isDone() { return scanned >= total; }
    }

    /**
     * 扫描本地已安装版本的详细信息（解析 version json 提取 inheritsFrom/mainClass/assets）。
     * 默认扫描配置的 versions 目录（~/.pmcl/versions）。
     */
    public List<LocalVersionInfo> scanLocalVersions() {
        return scanVersionsDir(config.getVersionsDir(), null);
    }

    /**
     * 扫描指定 versions 目录（用于扫描外部 Minecraft 安装，如 ~/.minecraft/versions）。
     */
    public List<LocalVersionInfo> scanVersionsDir(Path versionsDir) {
        return scanVersionsDir(versionsDir, null);
    }

    /**
     * 扫描指定 versions 目录，支持进度回调。
     * @param versionsDir 要扫描的目录
     * @param onProgress  进度回调（每个版本处理完触发一次），为 null 则不回调
     */
    public List<LocalVersionInfo> scanVersionsDir(Path versionsDir,
                                                  java.util.function.Consumer<ScanProgress> onProgress) {
        if (!Files.isDirectory(versionsDir)) return Collections.emptyList();
        // 先列出所有子目录，算总数
        List<Path> subDirs = new ArrayList<>();
        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(subDirs::add);
        } catch (IOException e) {
            throw new RuntimeException("扫描本地版本失败: " + versionsDir, e);
        }
        String dirName = versionsDir.getFileName() != null ? versionsDir.getFileName().toString() : versionsDir.toString();
        int total = subDirs.size();
        List<LocalVersionInfo> result = new ArrayList<>(total);
        int[] scanned = {0};
        for (Path p : subDirs) {
            String id = p.getFileName().toString();
            Path json = p.resolve(id + ".json");
            Path jar = p.resolve(id + ".jar");
            boolean hasJson = Files.exists(json);
            boolean hasJar = Files.exists(jar);
            long mtime = 0;
            try { mtime = Files.getLastModifiedTime(json).toMillis(); }
            catch (IOException ignored) {}
            String inheritsFrom = null;
            String mainClass = null;
            String assets = null;
            if (hasJson) {
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(json, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    if (root.has("inheritsFrom") && !root.get("inheritsFrom").isJsonNull())
                        inheritsFrom = root.get("inheritsFrom").getAsString();
                    if (root.has("mainClass") && !root.get("mainClass").isJsonNull())
                        mainClass = root.get("mainClass").getAsString();
                    if (root.has("assets") && !root.get("assets").isJsonNull())
                        assets = root.get("assets").getAsString();
                } catch (Throwable ignored) {}
            }
            result.add(new LocalVersionInfo(id, mtime, hasJar, hasJson,
                    inheritsFrom, mainClass, assets));
            scanned[0]++;
            if (onProgress != null) {
                onProgress.accept(new ScanProgress(dirName, scanned[0], total, id));
            }
        }
        // 按修改时间倒序（最新在前）
        result.sort((a, b) -> Long.compare(b.getLastModified(), a.getLastModified()));
        return result;
    }

    /**
     * 扫描所有已知 versions 目录（.pmcl/versions + 自动检测的 ~/.minecraft/versions），
     * 合并去重（同名版本以 .pmcl 优先）。
     */
    public List<LocalVersionInfo> scanAllLocalVersions() {
        return scanAllLocalVersions(null);
    }

    /**
     * 自动检测系统默认 Minecraft versions 目录（返回第一个找到的）。
     * 推荐使用 {@link #detectAllMinecraftVersionsDirs()} 以扫描所有候选目录。
     * @return 检测到的目录，不存在返回 null
     */
    public static Path detectDefaultMinecraftVersionsDir() {
        java.util.List<Path> all = detectAllMinecraftVersionsDirs();
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 检测系统上所有存在的 Minecraft versions 候选目录。
     * macOS 可能同时存在官方与 HMCL 两个目录，都需要扫描以合并版本。
     * <ul>
     *   <li>macOS: ~/Library/Application Support/minecraft/versions（官方启动器）</li>
     *   <li>macOS: ~/Library/Application Support/.minecraft/versions（HMCL 等第三方，带点隐藏目录）</li>
     *   <li>macOS: ~/.minecraft/versions（软链接兼容）</li>
     *   <li>Windows: %APPDATA%\.minecraft\versions</li>
     *   <li>Linux: ~/.minecraft/versions</li>
     * </ul>
     */
    public static java.util.List<Path> detectAllMinecraftVersionsDirs() {
        java.util.List<Path> result = new java.util.ArrayList<>();
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac")) {
            Path p1 = Paths.get(home, "Library", "Application Support", "minecraft", "versions");
            Path p2 = Paths.get(home, "Library", "Application Support", ".minecraft", "versions");
            Path p3 = Paths.get(home, ".minecraft", "versions");
            for (Path p : new Path[]{p1, p2, p3}) {
                if (Files.isDirectory(p) && !result.contains(p)) result.add(p);
            }
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                Path p = Paths.get(appData, ".minecraft", "versions");
                if (Files.isDirectory(p)) result.add(p);
            }
        } else {
            Path p = Paths.get(home, ".minecraft", "versions");
            if (Files.isDirectory(p)) result.add(p);
        }
        return result;
    }

    /**
     * 扫描所有已知 versions 目录，支持进度回调。
     * 进度统计跨目录累计：先扫 .pmcl/versions，再扫外部目录，回调中的 currentDir 标识当前目录。
     */
    public List<LocalVersionInfo> scanAllLocalVersions(java.util.function.Consumer<ScanProgress> onProgress) {
        // 先确定要扫描的目录列表
        List<Path> dirs = new ArrayList<>();
        dirs.add(config.getVersionsDir());
        // 自动检测系统上所有 Minecraft versions 候选目录（macOS 可能同时有官方 + HMCL 两个）
        for (Path mcDir : detectAllMinecraftVersionsDirs()) {
            if (!mcDir.equals(config.getVersionsDir()) && !dirs.contains(mcDir) && Files.isDirectory(mcDir)) {
                dirs.add(mcDir);
            }
        }

        // 预计算各目录的版本数 + 总数
        List<Integer> dirCounts = new ArrayList<>();
        final int[] grandTotalArr = {0};
        for (Path d : dirs) {
            int cnt = 0;
            if (Files.isDirectory(d)) {
                try (var stream = Files.list(d)) {
                    var it = stream.filter(Files::isDirectory).iterator();
                    while (it.hasNext()) { it.next(); cnt++; }
                } catch (IOException ignored) {}
            }
            dirCounts.add(cnt);
            grandTotalArr[0] += cnt;
        }
        final int grandTotal = grandTotalArr[0];

        // 逐目录扫描，跨目录累计进度
        List<LocalVersionInfo> pmcl = new ArrayList<>();
        int[] scanned = {0};
        for (int i = 0; i < dirs.size(); i++) {
            Path d = dirs.get(i);
            if (!Files.isDirectory(d)) continue;
            String dirName = d.getFileName() != null ? d.getFileName().toString() : d.toString();
            final int dirTotal = dirCounts.get(i);
            final String fDirName = dirName;
            final int[] dirScanned = {0};
            List<LocalVersionInfo> part = scanVersionsDir(d, p -> {
                dirScanned[0]++;
                scanned[0]++;
                // 用全局 scanned/total 回调，currentDir 标识当前目录
                if (onProgress != null) {
                    onProgress.accept(new ScanProgress(fDirName, scanned[0], grandTotal, p.getCurrentVersion()));
                }
            });
            // 去重：pmcl 已有的不再加入
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (var v : pmcl) existing.add(v.getId());
            for (var v : part) {
                if (!existing.contains(v.getId())) pmcl.add(v);
            }
        }
        return pmcl;
    }
}
