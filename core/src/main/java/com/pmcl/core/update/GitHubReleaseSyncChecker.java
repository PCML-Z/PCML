package com.pmcl.core.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GitHub Release 同步检查器：定时轮询 GitHub Releases API 检查新版本。
 * <p>
 * 不需要独立推送服务器，直接从 GitHub 获取更新信息。
 * <ul>
 *   <li>启动时立即检查一次</li>
 *   <li>之后每 30 分钟检查一次（遇到 API 速率限制时自动延长到 2 小时）</li>
 *   <li>使用 GitHub REST API: {@code https://api.github.com/repos/{owner}/{repo}/releases/latest}</li>
 *   <li>按当前操作系统/架构选择原生安装包，缺失时回退跨平台 fat JAR</li>
 *   <li>版本号取 tag_name（去掉 v 前缀），与当前版本比较</li>
 * </ul>
 * <p>
 * GitHub API 速率限制：未认证 60 次/小时，30 分钟轮询 = 2 次/小时。
 */
public final class GitHubReleaseSyncChecker implements AutoCloseable {

    /** 正常检查间隔（分钟） */
    private static final long CHECK_INTERVAL_MINUTES = 30;
    /** 遇到速率限制后的间隔（分钟） */
    private static final long RATE_LIMITED_INTERVAL_MINUTES = 120;
    /** HTTP 超时（秒） */
    private static final int HTTP_TIMEOUT_SECONDS = 15;

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** GitHub 仓库（格式 "owner/repo"，如 "peddlejumper/PMCL"） */
    private volatile String githubRepo;
    /** 当前客户端版本号 */
    private volatile String clientVersion;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> checkTask;
    private volatile long currentInterval = CHECK_INTERVAL_MINUTES;

    public GitHubReleaseSyncChecker(String clientVersion) {
        this.clientVersion = (clientVersion == null) ? "0.0.0" : clientVersion;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .build();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "pmcl-github-sync");
            t.setDaemon(true);
            return t;
        });
    }

    /** 监听器接口 */
    public interface Listener {
        /** 检查完成，发现新版本 */
        default void onUpdateAvailable(SelfUpdater.UpdateInfo info) {}
        /** 检查完成，已是最新版本 */
        default void onUpToDate() {}
        /** 检查过程中发生错误 */
        default void onError(String message, Throwable cause) {}
        /** 速率限制触发，将在指定分钟后重试 */
        default void onRateLimited(long retryAfterMinutes) {}
        /** 一次检查开始 */
        default void onCheckStarted() {}
        /** 一次检查结束（成功或失败） */
        default void onCheckFinished() {}
    }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** 配置 GitHub 仓库（格式 "owner/repo"），null 或空表示禁用 */
    public void setGithubRepo(String repo) {
        if (repo == null || repo.isBlank()) {
            this.githubRepo = "";
            return;
        }
        String trimmed = repo.trim();
        if (!isValidGithubRepo(trimmed)) {
            System.err.println("[GitHubSync] 非法 repo 格式: " + repo);
            this.githubRepo = "";
            return;
        }
        this.githubRepo = trimmed;
    }

    public static boolean isValidGithubRepo(String repo) {
        return repo != null
                && repo.trim().matches("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$");
    }

    public String getGithubRepo() { return githubRepo == null ? "" : githubRepo; }
    public boolean isRunning() { return running.get(); }
    public boolean isChecking() { return checking.get(); }

    /** 更新当前客户端版本号 */
    public void setClientVersion(String version) {
        this.clientVersion = (version == null) ? "0.0.0" : version;
    }

    /** 启动定时检查 */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        // 启动后 5 秒检查一次（避免阻塞启动流程）
        scheduleCheck(5, TimeUnit.SECONDS);
    }

    /** 用户主动启用或修改仓库时立即检查，之后继续按正常周期调度。 */
    public void startNow() {
        if (running.compareAndSet(false, true)) {
            scheduleCheck(0, TimeUnit.SECONDS);
        } else {
            checkNow();
        }
    }

    /**
     * 停止定时检查但不销毁调度器，以便之后再次 {@link #start()}。
     * 与 {@link #close()} 不同：close 会 shutdownNow 线程池，无法重启。
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (checkTask != null) {
            checkTask.cancel(false);
            checkTask = null;
        }
    }

    /** 立即触发一次检查（允许在自动同步关闭时手动检查，不影响定时调度） */
    public void checkNow() {
        scheduler.submit(() -> doCheck(true));
    }

    @Override
    public void close() {
        stop();
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // 定时调度
    // -------------------------------------------------------------------------

    private void scheduleCheck(long delay, TimeUnit unit) {
        if (!running.get()) return;
        if (checkTask != null) checkTask.cancel(false);
        checkTask = scheduler.scheduleAtFixedRate(
                () -> doCheck(false),
                unit.toSeconds(delay),
                currentInterval * 60,
                TimeUnit.SECONDS
        );
    }

    /** 遇到速率限制后重新调度 */
    private void rescheduleWithRateLimit() {
        currentInterval = RATE_LIMITED_INTERVAL_MINUTES;
        notifyRateLimited(currentInterval);
        if (!running.get()) return;
        // 取消当前任务，用新间隔重新调度
        if (checkTask != null) checkTask.cancel(false);
        checkTask = scheduler.scheduleAtFixedRate(
                () -> doCheck(false),
                currentInterval * 60,
                currentInterval * 60,
                TimeUnit.SECONDS
        );
    }

    // -------------------------------------------------------------------------
    // GitHub API 调用
    // -------------------------------------------------------------------------

    private void doCheck(boolean manual) {
        if (!manual && !running.get()) return;
        if (githubRepo == null || githubRepo.isEmpty()) {
            if (manual) notifyError("请先配置 GitHub 仓库", null);
            return;
        }
        // 定时任务、手动按钮和保存仓库可能同时触发；只允许一个检查占用网络与解析流程
        if (!checking.compareAndSet(false, true)) return;
        notifyCheckStarted();
        try {
            String apiUrl = "https://api.github.com/repos/" + githubRepo + "/releases/latest";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "PMCL-Updater")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            // 检查速率限制
            if (resp.statusCode() == 403 || resp.statusCode() == 429) {
                String remaining = resp.headers().firstValue("X-RateLimit-Remaining").orElse("1");
                if (resp.statusCode() == 429 || "0".equals(remaining)) {
                    rescheduleWithRateLimit();
                    return;
                }
            }
            if (resp.statusCode() == 404) {
                notifyError("仓库不存在、不可访问或尚未发布 Release", null);
                return;
            }
            if (resp.statusCode() != 200) {
                notifyError("GitHub API 返回 " + resp.statusCode(), null);
                return;
            }
            JsonObject release = JsonParser.parseString(resp.body()).getAsJsonObject();
            String remoteVersion = releaseVersion(release);
            if (remoteVersion.isEmpty() || !isNewer(remoteVersion, clientVersion)) {
                restoreNormalInterval();
                notifyUpToDate();
                return;
            }
            SelfUpdater.UpdateInfo info = parseRelease(release);
            if (info == null) {
                notifyError("Release 中没有可用的 PMCL 更新资产", null);
                return;
            }
            restoreNormalInterval();
            notifyUpdateAvailable(info);
        } catch (java.net.http.HttpTimeoutException e) {
            notifyError("GitHub API 请求超时", e);
        } catch (Exception e) {
            notifyError("检查 GitHub Release 失败: " + e.getMessage(), e);
        } finally {
            checking.set(false);
            notifyCheckFinished();
        }
    }

    private void restoreNormalInterval() {
        if (currentInterval == CHECK_INTERVAL_MINUTES) return;
        currentInterval = CHECK_INTERVAL_MINUTES;
        if (running.get()) scheduleCheck(currentInterval, TimeUnit.MINUTES);
    }

    /**
     * 解析 GitHub Release JSON，提取当前操作系统最合适的更新资产。
     * 优先级：macOS pkg/dmg、Windows msi/exe、Linux deb/rpm/AppImage，均缺失时回退 fat JAR。
     * 每个安装资产必须有同名 .sig（或去扩展名 .sig）并带 GitHub SHA-256 digest。
     */
    private SelfUpdater.UpdateInfo parseRelease(JsonObject release) throws IOException {
        String version = releaseVersion(release);
        if (version.isEmpty()) return null;
        String notes = release.has("body") && !release.get("body").isJsonNull()
                ? release.get("body").getAsString() : "";
        // 从 assets 中选择当前平台安装包，并解析 GitHub 提供的 SHA-256 digest
        if (!release.has("assets") || !release.get("assets").isJsonArray()) return null;
        JsonObject selectedAsset = null;
        int selectedScore = Integer.MIN_VALUE;
        java.util.Map<String, String> sha256ByName = new java.util.HashMap<>();
        java.util.Map<String, JsonObject> assetsByLowerName = new java.util.HashMap<>();
        for (var assetElem : release.getAsJsonArray("assets")) {
            JsonObject asset = assetElem.getAsJsonObject();
            String name = asset.has("name") && !asset.get("name").isJsonNull()
                    ? asset.get("name").getAsString() : "";
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            assetsByLowerName.put(lower, asset);
            if (lower.endsWith(".sha256") || lower.endsWith(".sha256.txt")) {
                // 旁路摘要文件本身不含 digest 字段；摘要需下载后读取——此处仅记录 URL 供后续
                // 优先使用 GitHub API digest 字段（见下）
                continue;
            }
            if (asset.has("digest") && !asset.get("digest").isJsonNull()) {
                String dig = asset.get("digest").getAsString();
                if (dig.toLowerCase(java.util.Locale.ROOT).startsWith("sha256:")) {
                    sha256ByName.put(name, dig.substring("sha256:".length()).trim());
                }
            }
            int score = platformAssetScore(lower);
            if (score > selectedScore) {
                selectedScore = score;
                selectedAsset = asset;
            }
        }
        if (selectedAsset == null || selectedScore < 0) return null;
        String name = selectedAsset.get("name").getAsString();
        String lowerAssetName = name.toLowerCase(java.util.Locale.ROOT);
        JsonObject sigAsset = assetsByLowerName.get(lowerAssetName + ".sig");
        if (sigAsset == null) {
            String withoutExtension = stripPackageExtension(lowerAssetName);
            sigAsset = assetsByLowerName.get(withoutExtension + ".sig");
        }
        String url = selectedAsset.has("browser_download_url")
                && !selectedAsset.get("browser_download_url").isJsonNull()
                ? selectedAsset.get("browser_download_url").getAsString() : "";
        long size = selectedAsset.has("size") && !selectedAsset.get("size").isJsonNull()
                ? selectedAsset.get("size").getAsLong() : 0L;
        String sha256 = sha256ByName.getOrDefault(name, "");
        if (sha256.isEmpty()) {
            System.err.println("[GitHubReleaseSync] Release asset 缺少 SHA-256 digest（"
                    + name + "），SelfUpdater 将拒绝安装。请在 GitHub Release 启用 asset digests。");
            return null;
        }
        // 下载 Ed25519 签名（.sig 资产），作为 HTTPS 之外的密码学兜底
        String signature = downloadSignatureAsset(sigAsset, name);
        if (signature == null) return null;
        return new SelfUpdater.UpdateInfo(version, url, "", sha256, size, notes, signature,
                SelfUpdater.TrustedChannel.GITHUB_RELEASE);
    }

    private static int platformAssetScore(String lowerName) {
        if (lowerName == null || !lowerName.contains("pmcl")
                || lowerName.endsWith(".sig")
                || lowerName.endsWith(".sha256")
                || lowerName.endsWith(".sha256.txt")) {
            return -1;
        }

        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        boolean mac = os.contains("mac");
        boolean win = os.contains("win");
        boolean assetMac = lowerName.contains("macos") || lowerName.contains("mac-");
        boolean assetWin = lowerName.contains("windows") || lowerName.contains("win-");
        boolean assetLinux = lowerName.contains("linux");
        if ((assetMac && !mac) || (assetWin && !win) || (assetLinux && (mac || win))) {
            return -1;
        }
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        boolean assetArm = lowerName.contains("arm64") || lowerName.contains("aarch64");
        boolean assetX64 = lowerName.contains("x86_64") || lowerName.contains("amd64")
                || lowerName.contains("x64");
        if ((assetArm && !arm) || (assetX64 && arm)) return -1;

        int archBonus = (assetArm || assetX64) ? 10 : 0;
        int platformBonus = (assetMac || assetWin || assetLinux) ? 10 : 0;
        if (mac) {
            if (lowerName.endsWith(".pkg")) return 120 + archBonus;
            if (lowerName.endsWith(".dmg")) return 100 + archBonus;
            if (lowerName.endsWith(".jar")) return 20 + platformBonus + archBonus;
        } else if (win) {
            if (lowerName.endsWith(".msi")) return 120 + archBonus;
            if (lowerName.endsWith(".exe")) return 100 + archBonus;
            if (lowerName.endsWith(".jar")) return 20 + platformBonus + archBonus;
        } else {
            if (lowerName.endsWith(".deb")) return 120 + archBonus;
            if (lowerName.endsWith(".rpm")) return 110 + archBonus;
            if (lowerName.endsWith(".appimage")) return 100 + archBonus;
            if (lowerName.endsWith(".tar.gz")) return 80 + archBonus;
            if (lowerName.endsWith(".jar")) return 20 + platformBonus + archBonus;
        }
        return -1;
    }

    private static String stripPackageExtension(String lowerName) {
        String[] extensions = {".tar.gz", ".appimage", ".jar", ".pkg", ".dmg",
                ".msi", ".exe", ".deb", ".rpm"};
        for (String extension : extensions) {
            if (lowerName.endsWith(extension)) {
                return lowerName.substring(0, lowerName.length() - extension.length());
            }
        }
        int dot = lowerName.lastIndexOf('.');
        return dot > 0 ? lowerName.substring(0, dot) : lowerName;
    }

    private static String releaseVersion(JsonObject release) {
        String tagName = release.has("tag_name") && !release.get("tag_name").isJsonNull()
                ? release.get("tag_name").getAsString().trim() : "";
        return tagName.startsWith("v") || tagName.startsWith("V")
                ? tagName.substring(1) : tagName;
    }

    /**
     * 下载 Release 的 Ed25519 签名资产（.sig 文件）。
     * <p>
     * 签名是对 canonical payload（version/url/sha256/sha1/size）的 Base64 编码签名，
     * 由发布流水线用与 {@link UpdateSignatureVerifier} 公钥配对的私钥生成。
     *
     * @param sigAsset GitHub API 返回的 .sig asset JSON，可为 null
     * @param jarName  jar 资产名（用于错误提示）
     * @return Base64 签名字符串，失败返回 null
     */
    private String downloadSignatureAsset(JsonObject sigAsset, String jarName) throws IOException {
        if (sigAsset == null) {
            System.err.println("[GitHubReleaseSync] Release 缺少 Ed25519 签名资产（.sig），"
                    + "SelfUpdater 将拒绝安装。请在 Release 上传 " + jarName + ".sig");
            return null;
        }
        String sigUrl = sigAsset.has("browser_download_url")
                && !sigAsset.get("browser_download_url").isJsonNull()
                ? sigAsset.get("browser_download_url").getAsString() : "";
        if (sigUrl.isEmpty()) {
            System.err.println("[GitHubReleaseSync] 签名资产缺少 browser_download_url");
            return null;
        }
        HttpRequest sigReq = HttpRequest.newBuilder()
                .uri(URI.create(sigUrl))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .header("Accept", "text/plain, */*")
                .header("User-Agent", "PMCL-Updater")
                .GET()
                .build();
        HttpResponse<String> sigResp;
        try {
            sigResp = httpClient.send(sigReq, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载签名被中断: " + sigUrl, e);
        }
        if (sigResp.statusCode() != 200) {
            System.err.println("[GitHubReleaseSync] 下载签名失败 HTTP " + sigResp.statusCode()
                    + " url=" + sigUrl);
            return null;
        }
        String sig = sigResp.body().trim();
        // Ed25519 签名 = 64 字节，Base64 ≈ 88 字符；设上限防滥用
        if (sig.isEmpty() || sig.length() > 4096) {
            System.err.println("[GitHubReleaseSync] 签名内容异常（长度 " + sig.length() + "）");
            return null;
        }
        return sig;
    }

    /** @see UpdateVersions#isNewer */
    private static boolean isNewer(String remote, String current) {
        return UpdateVersions.isNewer(remote, current);
    }

    // -------------------------------------------------------------------------
    // 监听器通知
    // -------------------------------------------------------------------------

    private void notifyUpdateAvailable(SelfUpdater.UpdateInfo info) {
        for (Listener l : listeners) {
            try { l.onUpdateAvailable(info); } catch (Exception ignored) {}
        }
    }

    private void notifyUpToDate() {
        for (Listener l : listeners) {
            try { l.onUpToDate(); } catch (Exception ignored) {}
        }
    }

    private void notifyError(String message, Throwable cause) {
        for (Listener l : listeners) {
            try { l.onError(message, cause); } catch (Exception ignored) {}
        }
    }

    private void notifyRateLimited(long retryAfterMinutes) {
        for (Listener l : listeners) {
            try { l.onRateLimited(retryAfterMinutes); } catch (Exception ignored) {}
        }
    }

    private void notifyCheckStarted() {
        for (Listener l : listeners) {
            try { l.onCheckStarted(); } catch (Exception ignored) {}
        }
    }

    private void notifyCheckFinished() {
        for (Listener l : listeners) {
            try { l.onCheckFinished(); } catch (Exception ignored) {}
        }
    }
}
