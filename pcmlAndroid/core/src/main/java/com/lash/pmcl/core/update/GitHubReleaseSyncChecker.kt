package com.lash.pmcl.core.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub Release 同步检查器：定时轮询 GitHub Releases API 检查新版本。
 *
 * Android 版本：使用 OkHttp 替代 java.net.http.HttpClient（API 33+ 才有）。
 *
 * - 启动时立即检查一次
 * - 之后每 30 分钟检查一次（遇到 API 速率限制时自动延长到 2 小时）
 * - 使用 GitHub REST API: `https://api.github.com/repos/{owner}/{repo}/releases/latest`
 * - 解析 Release 的 assets，查找包含 "pmcl" 字样的 .jar 文件作为更新包
 * - 版本号取 tag_name（去掉 v 前缀），与当前版本比较
 */
class GitHubReleaseSyncChecker(
    clientVersion: String?,
    private val signatureVerifier: UpdateSignatureVerifier
) : AutoCloseable {

    /** 正常检查间隔（分钟） */
    private val checkIntervalMinutes = 30L
    /** 遇到速率限制后的间隔（分钟） */
    private val rateLimitedIntervalMinutes = 120L
    /** HTTP 超时（秒） */
    private val httpTimeoutSeconds = 15L

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(httpTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(httpTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "pmcl-github-sync").apply { isDaemon = true }
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** GitHub 仓库（格式 "owner/repo"，如 "peddlejumper/PMCL"） */
    @Volatile
    private var githubRepo: String = ""

    /** 当前客户端版本号 */
    @Volatile
    private var clientVersion: String = clientVersion ?: "0.0.0"

    private val running = AtomicBoolean(false)

    @Volatile
    private var checkTask: ScheduledFuture<*>? = null

    @Volatile
    private var currentInterval = checkIntervalMinutes

    /** 监听器接口 */
    interface Listener {
        /** 检查完成，发现新版本 */
        fun onUpdateAvailable(info: SelfUpdater.UpdateInfo) {}
        /** 检查完成，已是最新版本 */
        fun onUpToDate() {}
        /** 检查过程中发生错误 */
        fun onError(message: String, cause: Throwable?) {}
        /** 速率限制触发，将在指定分钟后重试 */
        fun onRateLimited(retryAfterMinutes: Long) {}
    }

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** 配置 GitHub 仓库（格式 "owner/repo"），null 或空表示禁用 */
    fun setGithubRepo(repo: String?) {
        if (repo.isNullOrBlank()) {
            githubRepo = ""
            return
        }
        val trimmed = repo.trim()
        if (!trimmed.matches(Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$"))) {
            System.err.println("[GitHubSync] 非法 repo 格式: $repo")
            githubRepo = ""
            return
        }
        githubRepo = trimmed
    }

    /** 更新当前客户端版本号 */
    fun setClientVersion(version: String?) {
        clientVersion = version ?: "0.0.0"
    }

    /** 启动定时检查 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        // 启动后 5 秒检查一次（避免阻塞启动流程）
        scheduleCheck(5, TimeUnit.SECONDS)
    }

    /**
     * 停止定时检查但不销毁调度器，以便之后再次 [start]。
     * 与 [close] 不同：close 会 shutdownNow 线程池，无法重启。
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        checkTask?.cancel(false)
        checkTask = null
    }

    /** 立即触发一次检查（不影响定时调度） */
    fun checkNow() {
        scheduler.submit { doCheck() }
    }

    override fun close() {
        stop()
        scheduler.shutdownNow()
    }

    // -------------------------------------------------------------------------
    // 定时调度
    // -------------------------------------------------------------------------

    private fun scheduleCheck(delay: Long, unit: TimeUnit) {
        if (!running.get()) return
        checkTask?.cancel(false)
        checkTask = scheduler.scheduleAtFixedRate(
            { doCheck() },
            unit.toSeconds(delay),
            currentInterval * 60,
            TimeUnit.SECONDS
        )
    }

    /** 遇到速率限制后重新调度 */
    private fun rescheduleWithRateLimit() {
        currentInterval = rateLimitedIntervalMinutes
        notifyRateLimited(currentInterval)
        checkTask?.cancel(false)
        checkTask = scheduler.scheduleAtFixedRate(
            { doCheck() },
            currentInterval * 60,
            currentInterval * 60,
            TimeUnit.SECONDS
        )
    }

    // -------------------------------------------------------------------------
    // GitHub API 调用
    // -------------------------------------------------------------------------

    private fun doCheck() {
        if (!running.get()) return
        if (githubRepo.isEmpty()) return
        try {
            val apiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"
            val req = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PMCL-Updater")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                // 检查速率限制
                if (resp.code == 403) {
                    val remaining = resp.header("X-RateLimit-Remaining") ?: "1"
                    if (remaining == "0") {
                        rescheduleWithRateLimit()
                        return
                    }
                }
                if (resp.code == 404) {
                    notifyUpToDate()
                    return
                }
                if (resp.code != 200) {
                    notifyError("GitHub API 返回 ${resp.code}", null)
                    return
                }
                val body = resp.body ?: run {
                    notifyError("GitHub API 响应体为空", null)
                    return
                }
                val release = JsonParser.parseString(body.string()).asJsonObject
                val info = parseRelease(release) ?: run {
                    notifyUpToDate()
                    return
                }
                // 版本比较
                if (info.version != clientVersion && isNewer(info.version, clientVersion)) {
                    notifyUpdateAvailable(info)
                } else {
                    // 恢复正常间隔
                    if (currentInterval != checkIntervalMinutes) {
                        currentInterval = checkIntervalMinutes
                        scheduleCheck(currentInterval, TimeUnit.MINUTES)
                    }
                    notifyUpToDate()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            notifyError("GitHub API 请求超时", e)
        } catch (e: Exception) {
            notifyError("检查 GitHub Release 失败: ${e.message}", e)
        }
    }

    /**
     * 解析 GitHub Release JSON，提取更新信息。
     * 从 assets 中查找包含 "pmcl" 字样的 .jar 文件，并下载同名 .sig 签名资产。
     */
    private fun parseRelease(release: JsonObject): SelfUpdater.UpdateInfo? {
        val tagName = if (release.has("tag_name") && !release.get("tag_name").isJsonNull)
            release.get("tag_name").asString else ""
        val version = if (tagName.startsWith("v")) tagName.substring(1) else tagName
        if (version.isEmpty()) return null
        val notes = if (release.has("body") && !release.get("body").isJsonNull)
            release.get("body").asString else ""
        // 从 assets 中查找 pmcl jar、.sig 签名，并解析 SHA-256
        if (!release.has("assets") || !release.get("assets").isJsonArray) return null
        var jarAsset: JsonObject? = null
        var sigAsset: JsonObject? = null
        val sha256ByName = HashMap<String, String>()
        for (assetElem in release.getAsJsonArray("assets")) {
            val asset = assetElem.asJsonObject
            val name = if (asset.has("name") && !asset.get("name").isJsonNull)
                asset.get("name").asString else ""
            val lower = name.lowercase(Locale.ROOT)
            if (lower.endsWith(".sha256") || lower.endsWith(".sha256.txt")) {
                continue
            }
            if (asset.has("digest") && !asset.get("digest").isJsonNull) {
                val dig = asset.get("digest").asString
                if (dig.lowercase(Locale.ROOT).startsWith("sha256:")) {
                    sha256ByName[name] = dig.substring("sha256:".length).trim()
                }
            }
            if (lower.endsWith(".jar") && lower.contains("pmcl") && jarAsset == null) {
                jarAsset = asset
            }
            if (lower.endsWith(".sig") && sigAsset == null) {
                sigAsset = asset
            }
        }
        val jar = jarAsset ?: return null
        val name = jar.get("name").asString
        val url = if (jar.has("browser_download_url") && !jar.get("browser_download_url").isJsonNull)
            jar.get("browser_download_url").asString else ""
        val size = if (jar.has("size") && !jar.get("size").isJsonNull) jar.get("size").asLong else 0L
        val sha256 = sha256ByName.getOrDefault(name, "")
        if (sha256.isEmpty()) {
            System.err.println("[GitHubReleaseSync] Release asset 缺少 SHA-256 digest（$name），SelfUpdater 将拒绝安装。")
            return null
        }
        // 下载 Ed25519 签名（.sig 资产）
        val signature = downloadSignatureAsset(sigAsset, name) ?: return null
        return SelfUpdater.UpdateInfo(
            version, url, "", sha256, size, notes, signature,
            SelfUpdater.TrustedChannel.GITHUB_RELEASE
        )
    }

    /**
     * 下载 Release 的 Ed25519 签名资产（.sig 文件）。
     */
    private fun downloadSignatureAsset(sigAsset: JsonObject?, jarName: String): String? {
        if (sigAsset == null) {
            System.err.println("[GitHubReleaseSync] Release 缺少 Ed25519 签名资产（.sig），SelfUpdater 将拒绝安装。")
            return null
        }
        val sigUrl = if (sigAsset.has("browser_download_url") && !sigAsset.get("browser_download_url").isJsonNull)
            sigAsset.get("browser_download_url").asString else ""
        if (sigUrl.isEmpty()) {
            System.err.println("[GitHubReleaseSync] 签名资产缺少 browser_download_url")
            return null
        }
        val sigReq = Request.Builder()
            .url(sigUrl)
            .header("Accept", "text/plain, */*")
            .header("User-Agent", "PMCL-Updater")
            .get()
            .build()
        try {
            httpClient.newCall(sigReq).execute().use { sigResp ->
                if (sigResp.code != 200) {
                    System.err.println("[GitHubReleaseSync] 下载签名失败 HTTP ${sigResp.code} url=$sigUrl")
                    return null
                }
                val body = sigResp.body ?: return null
                val sig = body.string().trim()
                // Ed25519 签名 = 64 字节，Base64 ≈ 88 字符；设上限防滥用
                if (sig.isEmpty() || sig.length > 4096) {
                    System.err.println("[GitHubReleaseSync] 签名内容异常（长度 ${sig.length}）")
                    return null
                }
                return sig
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            System.err.println("[GitHubReleaseSync] 下载签名被中断: $sigUrl")
            return null
        }
    }

    private fun isNewer(remote: String, current: String): Boolean =
        UpdateVersions.isNewer(remote, current)

    // -------------------------------------------------------------------------
    // 监听器通知
    // -------------------------------------------------------------------------

    private fun notifyUpdateAvailable(info: SelfUpdater.UpdateInfo) {
        for (l in listeners) {
            try { l.onUpdateAvailable(info) } catch (_: Exception) {}
        }
    }

    private fun notifyUpToDate() {
        for (l in listeners) {
            try { l.onUpToDate() } catch (_: Exception) {}
        }
    }

    private fun notifyError(message: String, cause: Throwable?) {
        for (l in listeners) {
            try { l.onError(message, cause) } catch (_: Exception) {}
        }
    }

    private fun notifyRateLimited(retryAfterMinutes: Long) {
        for (l in listeners) {
            try { l.onRateLimited(retryAfterMinutes) } catch (_: Exception) {}
        }
    }
}
