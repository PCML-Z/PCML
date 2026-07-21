package com.pmcl.core.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 *   <li>解析 Release 的 assets，查找包含 "pmcl" 字样的 .jar 文件作为更新包</li>
 *   <li>版本号取 tag_name（去掉 v 前缀），与当前版本比较</li>
 * </ul>
 * <p>
 * GitHub API 速率限制：未认证 60 次/小时，30 分钟轮询 = 48 次/小时，足够使用。
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
    }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** 配置 GitHub 仓库（格式 "owner/repo"），null 或空表示禁用 */
    public void setGithubRepo(String repo) {
        this.githubRepo = (repo == null) ? "" : repo.trim();
    }

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

    /** 立即触发一次检查（不影响定时调度） */
    public void checkNow() {
        scheduler.submit(this::doCheck);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        if (checkTask != null) checkTask.cancel(false);
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // 定时调度
    // -------------------------------------------------------------------------

    private void scheduleCheck(long delay, TimeUnit unit) {
        if (!running.get()) return;
        if (checkTask != null) checkTask.cancel(false);
        checkTask = scheduler.scheduleAtFixedRate(
                this::doCheck,
                unit.toSeconds(delay),
                currentInterval * 60,
                TimeUnit.SECONDS
        );
    }

    /** 遇到速率限制后重新调度 */
    private void rescheduleWithRateLimit() {
        currentInterval = RATE_LIMITED_INTERVAL_MINUTES;
        notifyRateLimited(currentInterval);
        // 取消当前任务，用新间隔重新调度
        if (checkTask != null) checkTask.cancel(false);
        checkTask = scheduler.scheduleAtFixedRate(
                this::doCheck,
                currentInterval * 60,
                currentInterval * 60,
                TimeUnit.SECONDS
        );
    }

    // -------------------------------------------------------------------------
    // GitHub API 调用
    // -------------------------------------------------------------------------

    private void doCheck() {
        if (!running.get()) return;
        if (githubRepo == null || githubRepo.isEmpty()) return;
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
            if (resp.statusCode() == 403) {
                String remaining = resp.headers().firstValue("X-RateLimit-Remaining").orElse("1");
                if ("0".equals(remaining)) {
                    rescheduleWithRateLimit();
                    return;
                }
            }
            if (resp.statusCode() == 404) {
                // 没有 Release（仓库从未发布过 Release）
                notifyUpToDate();
                return;
            }
            if (resp.statusCode() != 200) {
                notifyError("GitHub API 返回 " + resp.statusCode(), null);
                return;
            }
            JsonObject release = JsonParser.parseString(resp.body()).getAsJsonObject();
            SelfUpdater.UpdateInfo info = parseRelease(release);
            if (info == null) {
                notifyUpToDate();
                return;
            }
            // 版本比较：tag_name 去掉 v 前缀后与 currentVersion 比较
            if (!info.getVersion().equals(clientVersion)
                    && isNewer(info.getVersion(), clientVersion)) {
                notifyUpdateAvailable(info);
            } else {
                // 恢复正常间隔
                if (currentInterval != CHECK_INTERVAL_MINUTES) {
                    currentInterval = CHECK_INTERVAL_MINUTES;
                    scheduleCheck(currentInterval, TimeUnit.MINUTES);
                }
                notifyUpToDate();
            }
        } catch (java.net.http.HttpTimeoutException e) {
            notifyError("GitHub API 请求超时", e);
        } catch (Exception e) {
            notifyError("检查 GitHub Release 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 GitHub Release JSON，提取更新信息。
     * 从 assets 中查找包含 "pmcl" 字样的 .jar 文件。
     */
    private SelfUpdater.UpdateInfo parseRelease(JsonObject release) {
        String tagName = release.has("tag_name") && !release.get("tag_name").isJsonNull()
                ? release.get("tag_name").getAsString() : "";
        String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
        if (version.isEmpty()) return null;
        String notes = release.has("body") && !release.get("body").isJsonNull()
                ? release.get("body").getAsString() : "";
        // 从 assets 中查找 pmcl jar
        if (!release.has("assets") || !release.get("assets").isJsonArray()) return null;
        for (var assetElem : release.getAsJsonArray("assets")) {
            JsonObject asset = assetElem.getAsJsonObject();
            String name = asset.has("name") && !asset.get("name").isJsonNull()
                    ? asset.get("name").getAsString() : "";
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                    && name.toLowerCase(java.util.Locale.ROOT).contains("pmcl")) {
                String url = asset.has("browser_download_url")
                        && !asset.get("browser_download_url").isJsonNull()
                        ? asset.get("browser_download_url").getAsString() : "";
                long size = asset.has("size") && !asset.get("size").isJsonNull()
                        ? asset.get("size").getAsLong() : 0L;
                return new SelfUpdater.UpdateInfo(version, url, "", "", size, notes);
            }
        }
        return null;
    }

    /**
     * 简单的版本比较：按点分段比较数字大小。
     * 例: "1.0.1" > "1.0.0", "1.1.0" > "1.0.9"
     */
    private static boolean isNewer(String remote, String current) {
        if (remote.equals(current)) return false;
        String[] r = remote.split("\\.");
        String[] c = current.split("\\.");
        int len = Math.max(r.length, c.length);
        for (int i = 0; i < len; i++) {
            int ri = i < r.length ? parseIntSafe(r[i]) : 0;
            int ci = i < c.length ? parseIntSafe(c[i]) : 0;
            if (ri > ci) return true;
            if (ri < ci) return false;
        }
        return false; // 完全相等
    }

    private static int parseIntSafe(String s) {
        try {
            // 去掉可能的后缀（如 1.0.0-beta → 0）
            String num = s.replaceAll("[^0-9].*$", "");
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
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
}
