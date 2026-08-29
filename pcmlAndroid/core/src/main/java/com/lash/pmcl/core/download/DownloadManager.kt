package com.lash.pmcl.core.download

import com.lash.pmcl.core.util.SsrfChecker
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/**
 * 下载管理：多线程下载 + 镜像源 + 代理 + 断点续传 + 限速 + 失败重试 + SHA1 校验。
 * <p>
 * Android 版本：移除了 CurlFallback（Android 有 OkHttp 不需要 curl）、
 * POSIX 权限、LauncherConfig/Preferences 依赖，改为通过构造函数和 reconfigure 配置。
 */
class DownloadManager(
    /** 工作目录（游戏 versions/libraries/assets 等的根目录） */
    var workDir: Path,
    /** 期望分片数（实际会自适应，1-16） */
    private var chunkedDownloadThreads: Int = 4,
    /** 下载线程池大小 */
    downloadThreads: Int = 8
) {
    /** 缓冲区大小：256KB */
    private val bufferSize = 256 * 1024

    /** 单主机最大并发请求数 */
    private val maxRequestsPerHost = 64

    /** 全局最大并发请求数 */
    private val maxRequests = 128

    /** 连接池容量 */
    private val connectionPoolSize = 64

    /** 大于此阈值的文件走 ChunkedDownloader */
    private val chunkedThreshold = 4L * 1024 * 1024

    /** 进度回调节流间隔（ms） */
    private val progressThrottleMs = 50L

    /** 文本下载上限，防 OOM */
    private val maxStringBytes = 16L * 1024 * 1024

    /** 用户可控 URL 下载上限 */
    private val maxSsrfDownloadBytes = 100L * 1024 * 1024

    /** 镜像管理器 */
    val mirror = MirrorManager()

    /** HTTP 客户端 */
    @Volatile
    private var http: OkHttpClient

    /** 下载线程池 */
    private val pool: ExecutorService = Executors.newFixedThreadPool(downloadThreads)

    /** 分片下载专用线程池 */
    private val chunkedPool: ExecutorService = Executors.newFixedThreadPool(minOf(16, downloadThreads))

    /** 校验专用线程池 */
    private val verifyPool: ExecutorService = Executors.newFixedThreadPool(4)

    /** 批量下载背压信号量 */
    private val downloadLimiter = Semaphore(downloadThreads)

    /** 分片下载器 */
    private val chunked: ChunkedDownloader

    /** per-file 锁：防止并发下载同一文件导致 .part 数据竞争 */
    private val fileLocks = ConcurrentHashMap<Path, Any>()

    /** 网络参数 */
    @Volatile
    private var speedLimitBytesPerSec: Long = 0
    @Volatile
    private var retryCount: Int = 3
    @Volatile
    private var enableResume: Boolean = true

    init {
        this.http = buildClient(null, 15, false, null, null)
        this.chunked = ChunkedDownloader(http, chunkedDownloadThreads, chunkedPool)
    }

    /** 获取指定目标路径的 per-file 锁对象 */
    private fun fileLockFor(path: Path): Any =
        fileLocks.computeIfAbsent(path.toAbsolutePath().normalize()) { Any() }

    /**
     * 构建 OkHttpClient：独立连接池 + 调高 maxRequestsPerHost 的 Dispatcher + HTTP/2。
     */
    private fun buildClient(
        proxy: Proxy?, connectTimeoutSec: Int,
        useProxyAuth: Boolean, proxyUser: String?, proxyPass: String?
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = maxRequests
            maxRequestsPerHost = maxRequestsPerHost
        }

        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(connectionPoolSize, 5, TimeUnit.MINUTES))
            .dispatcher(dispatcher)
            .connectTimeout(java.time.Duration.ofSeconds(connectTimeoutSec.toLong()))
            .readTimeout(java.time.Duration.ofSeconds(120))
            .writeTimeout(java.time.Duration.ofSeconds(60))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .dns(FastDns())

        if (proxy != null) {
            builder.proxy(proxy)
            if (useProxyAuth && !proxyUser.isNullOrEmpty()) {
                val credential = Credentials.basic(proxyUser, proxyPass ?: "")
                builder.proxyAuthenticator(Authenticator { _: Route?, response: Response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential).build()
                })
            }
        }
        return builder.build()
    }

    /**
     * 根据偏好重新构建 HTTP 客户端与镜像配置。
     * 延迟清理旧客户端，给在途请求 30 秒完成时间。
     */
    fun reconfigure(
        mirrorType: MirrorManager.MirrorType,
        customMirrorBase: String,
        speedLimitKb: Int,
        downloadRetryCount: Int,
        enableResume: Boolean,
        chunkedDownloadThreads: Int,
        proxy: Proxy? = null,
        useProxyAuth: Boolean = false,
        proxyUser: String? = null,
        proxyPass: String? = null
    ) {
        mirror.type = mirrorType
        mirror.customBase = customMirrorBase

        val old = this.http
        http = buildClient(proxy, 15, useProxyAuth, proxyUser, proxyPass)

        // 延迟清理旧客户端
        if (old != http) {
            Thread {
                try { Thread.sleep(30_000) } catch (e: InterruptedException) { return@Thread }
                try {
                    old.connectionPool.evictAll()
                    old.dispatcher.executorService.shutdown()
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
        }

        this.speedLimitBytesPerSec = speedLimitKb.toLong() * 1024L
        this.retryCount = maxOf(0, downloadRetryCount)
        this.enableResume = enableResume
        this.chunkedDownloadThreads = maxOf(1, chunkedDownloadThreads)

        chunked.updateHttpClient(http)
        chunked.setSpeedLimit(speedLimitBytesPerSec)
    }

    fun getChunkedDownloadThreads(): Int = chunkedDownloadThreads

    /**
     * 用当前 HttpClient（含代理）探测连通性。成功返回 HTTP 状态码描述。
     */
    fun testConnection(url: String): String {
        if (url.isEmpty()) throw IOException("empty url")
        val probe = http.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8))
            .readTimeout(java.time.Duration.ofSeconds(8))
            .callTimeout(java.time.Duration.ofSeconds(12))
            .build()
        val req = Request.Builder().url(url).header("Range", "bytes=0-255").get().build()
        probe.newCall(req).execute().use { r ->
            val code = r.code
            if (code in 200..499) return "HTTP $code"
            throw IOException("HTTP $code")
        }
    }

    /** 关闭所有线程池与连接池 */
    fun shutdown() {
        try {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        } catch (_: Throwable) {}
        pool.shutdownNow()
        chunkedPool.shutdownNow()
        verifyPool.shutdownNow()
        awaitPool(pool, "download")
        awaitPool(chunkedPool, "chunked")
        awaitPool(verifyPool, "verify")
    }

    private fun awaitPool(es: ExecutorService, name: String) {
        try {
            if (!es.awaitTermination(3, TimeUnit.SECONDS)) {
                System.err.println("[DownloadManager] $name 线程池未能在 3s 内退出")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 下载循环中检查中断 */
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw InterruptedIOException("下载已中断")
        }
    }

    /**
     * 分片下载大文件（如 Java runtime、client.jar）。
     */
    fun downloadChunked(url: String, target: Path, onProgress: ((Long) -> Unit)?): CompletableFuture<Void> {
        return chunked.download(rewrite(url), target, onProgress)
    }

    /**
     * 分片下载大文件（带 SHA-1 校验）。
     */
    fun downloadChunked(url: String, target: Path, expectedSha1: String?, onProgress: ((Long) -> Unit)?): CompletableFuture<Void> {
        return chunked.download(rewrite(url), target, expectedSha1, onProgress)
    }

    /** 应用镜像重写 */
    private fun rewrite(url: String): String = mirror.rewrite(url)

    /** 暴露内部 OkHttpClient */
    fun httpClient(): OkHttpClient = http

    /**
     * 批量下载，使用 Semaphore 背压控制。
     */
    fun downloadAll(
        tasks: List<DownloadTask>,
        onFileDone: Consumer<String>?,
        onBytes: Consumer<Long>?
    ): CompletableFuture<Void> {
        val total = tasks.sumOf { it.size }
        val completed = AtomicLong(0)
        val lastNotifyTime = AtomicLong(0)
        val aborted = AtomicBoolean(false)

        val futures = tasks.map { t ->
            // 阶段1：下载
            val downloadFuture = CompletableFuture.supplyAsync({
                if (aborted.get() || Thread.currentThread().isInterrupted()) {
                    throw RuntimeException(InterruptedIOException("批量下载已中止"))
                }
                try {
                    downloadLimiter.acquire()
                    try {
                        if (aborted.get()) {
                            throw InterruptedIOException("批量下载已中止")
                        }
                        downloadOneWithRetry(t) { deltaBytes: Long ->
                            if (onBytes != null && !aborted.get()) {
                                completed.addAndGet(deltaBytes)
                                val t2 = System.currentTimeMillis()
                                if (t2 - lastNotifyTime.get() >= progressThrottleMs) {
                                    lastNotifyTime.set(t2)
                                    onBytes.accept(completed.get()) // 用全局最新值而非局部变量
                                }
                            }
                        }
                    } finally {
                        downloadLimiter.release()
                    }
                } catch (e: IOException) {
                    throw RuntimeException("下载失败: ${t.url}", e)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw RuntimeException("下载被中断: ${t.url}", e)
                }
            }, pool)
            // 阶段2：SHA1 校验 + 重命名
            downloadFuture.thenCompose { partFile: Path? ->
                if (aborted.get()) {
                    partFile?.let { try { Files.deleteIfExists(it) } catch (_: IOException) {} }
                    return@thenCompose CompletableFuture.failedFuture<Void>(
                        InterruptedIOException("批量下载已中止"))
                }
                if (partFile == null) {
                    completed.addAndGet(t.size)
                    onBytes?.accept(completed.get())
                    onFileDone?.accept(t.relativePath)
                    return@thenCompose CompletableFuture.completedFuture(null)
                }
                verifyAndRename(t, partFile, aborted).thenRun {
                    if (aborted.get()) return@thenRun
                    onBytes?.accept(completed.get())
                    onFileDone?.accept(t.relativePath)
                }
            }
        }.toTypedArray<CompletableFuture<*>>()

        val all = CompletableFuture.allOf(*futures)
        all.whenComplete { _, err ->
            if (err != null) {
                aborted.set(true)
                for (f in futures) { f.cancel(true) }
            }
        }
        return all.thenRun { onBytes?.accept(total) }
    }

    /**
     * 下载单文件（带重试），返回 .part 文件路径。null 表示已存在且 SHA1 匹配。
     */
    private fun downloadOneWithRetry(task: DownloadTask, onDeltaBytes: ((Long) -> Unit)?): Path? {
        var last: IOException? = null
        for (i in 0..retryCount) {
            try {
                return downloadOne(task, onDeltaBytes)
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                last = e
                // 第 2 次重试起删除 .part 文件从头下载
                if (i >= 1) {
                    val target = workAbs(task.relativePath)
                    val partFile = target.resolveSibling(target.fileName.toString() + ".part")
                    try { Files.deleteIfExists(partFile) } catch (_: IOException) {}
                }
                val base = 500L * (1L shl i)
                val jitter = ThreadLocalRandom.current().nextLong(200)
                try { Thread.sleep(base + jitter) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("下载已中断")
                }
            }
        }
        throw last!!
    }

    /** 解析相对路径为工作目录下的绝对路径 */
    private fun workAbs(relativePath: String): Path {
        val workAbs = workDir.toAbsolutePath().normalize()
        return workAbs.resolve(relativePath).normalize()
    }

    /**
     * SHA1 校验并原子重命名。
     */
    private fun verifyAndRename(task: DownloadTask, partFile: Path): CompletableFuture<Void> =
        verifyAndRename(task, partFile, null)

    private fun verifyAndRename(task: DownloadTask, partFile: Path, aborted: AtomicBoolean?): CompletableFuture<Void> {
        val target = workAbs(task.relativePath)
        if (task.sha1.isNullOrEmpty()) {
            return CompletableFuture.runAsync({
                try { Files.deleteIfExists(partFile) } catch (_: IOException) {}
                throw RuntimeException(IOException("拒绝无 SHA-1 的下载任务: ${task.relativePath}"))
            }, pool)
        }
        val expected = task.sha1
        return sha1Async(partFile).thenAcceptAsync({ actual: String ->
            if (aborted?.get() == true) {
                throw RuntimeException(InterruptedIOException("批量下载已中止: ${task.relativePath}"))
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                try { Files.deleteIfExists(partFile) } catch (_: IOException) {}
                throw RuntimeException(IOException("SHA1 校验失败: ${task.relativePath} 期望=$expected 实际=$actual"))
            }
            synchronized(fileLockFor(target)) {
                if (aborted?.get() == true) {
                    throw RuntimeException(InterruptedIOException("批量下载已中止: ${task.relativePath}"))
                }
                movePartFile(partFile, target)
            }
        }, pool)
    }

    /** 原子移动 .part 文件到目标路径 */
    private fun movePartFile(partFile: Path, target: Path) {
        try {
            if (!Files.exists(partFile)) {
                if (Files.exists(target)) return
                throw java.nio.file.NoSuchFileException("$partFile -> $target")
            }
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            try { Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING) }
            catch (e2: IOException) { throw RuntimeException(e2) }
        } catch (e: java.nio.file.NoSuchFileException) {
            if (Files.exists(target)) return
            throw RuntimeException(e)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    /**
     * 下载单文件到 .part 文件。null 表示已存在且 SHA1 匹配。
     */
    private fun downloadOne(task: DownloadTask, onDeltaBytes: ((Long) -> Unit)?): Path? {
        val rel = task.relativePath
        require(!(rel.isBlank() || rel.contains("..") || rel.startsWith("/") ||
                rel.startsWith("\\") || rel.indexOf('\u0000') >= 0)) {
            "非法下载相对路径: $rel"
        }
        val workAbs = workDir.toAbsolutePath().normalize()
        val target = workAbs.resolve(rel).normalize()
        require(target.startsWith(workAbs)) { "下载路径越界: $rel" }
        Files.createDirectories(target.parent)

        synchronized(fileLockFor(target)) {
            // 已存在且 SHA1 匹配则跳过
            if (Files.exists(target) && !task.sha1.isNullOrEmpty()) {
                val existing = sha1(target)
                if (existing.equals(task.sha1, ignoreCase = true)) {
                    return null
                }
            }

            val partFile = target.resolveSibling(target.fileName.toString() + ".part")
            var existingSize = 0L
            if (enableResume && Files.exists(partFile)) {
                existingSize = Files.size(partFile)
            }

            val url = rewrite(task.url)
            val reqBuilder = Request.Builder().url(url).get()
            if (enableResume && existingSize > 0) {
                reqBuilder.header("Range", "bytes=$existingSize-")
            }
            val req = reqBuilder.build()

            val parsedUrl = url.toHttpUrlOrNull()
            val reqHost = parsedUrl?.host ?: ""
            try {
                http.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val rangeOk = code == 206
                    val fullOk = code == 200
                    if (!rangeOk && !fullOk) {
                        if (code >= 400) mirror.markFailure(reqHost)
                        throw IOException("下载失败 code=$code url=$url")
                    }
                    val startPos = if (rangeOk) existingSize else 0L

                    if (rangeOk) {
                        val contentRange = resp.header("Content-Range")
                        if (!DownloadUtils.contentRangeMatches(contentRange, existingSize)) {
                            Files.deleteIfExists(partFile)
                            throw IOException("Content-Range 不匹配: $contentRange (expected start=$existingSize)")
                        }
                    }
                    if (fullOk && Files.exists(partFile)) {
                        Files.deleteIfExists(partFile)
                    }

                    val body = resp.body ?: throw IOException("响应体为空: $url")
                    body.byteStream().use { inp ->
                        RandomAccessFile(partFile.toFile(), "rw").use { raf ->
                            raf.seek(startPos)
                            val buf = ByteArray(bufferSize)
                            var lastThrottleTime = System.currentTimeMillis()
                            var bytesInWindow = 0L
                            while (true) {
                                throwIfInterrupted()
                                val n = inp.read(buf)
                                if (n == -1) break
                                raf.write(buf, 0, n)
                                bytesInWindow += n
                                onDeltaBytes?.invoke(n.toLong())
                                if (speedLimitBytesPerSec > 0) {
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - lastThrottleTime
                                    if (elapsed >= 100) {
                                        val allowed = speedLimitBytesPerSec * elapsed / 1000L
                                        if (bytesInWindow > allowed) {
                                            val sleepMs = (bytesInWindow - allowed) * 1000L / speedLimitBytesPerSec
                                            try { Thread.sleep(sleepMs) }
                                            catch (_: InterruptedException) {
                                                Thread.currentThread().interrupt()
                                                throw InterruptedIOException("下载已中断")
                                            }
                                        }
                                        lastThrottleTime = System.currentTimeMillis()
                                        bytesInWindow = 0
                                    }
                                }
                            }
                        }
                    }
                    mirror.markSuccess(reqHost)
                }
            } catch (e: IOException) {
                mirror.markFailure(reqHost)
                throw e
            }
            return partFile
        }
    }

    /**
     * 直接下载文本（用于版本 JSON 等），应用镜像重写。
     */
    @Throws(IOException::class)
    fun downloadString(url: String): String {
        val rewritten = rewrite(url)
        val req = Request.Builder().url(rewritten).get().build()
        var last: IOException? = null
        for (i in 0..retryCount) {
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("下载失败 code=${resp.code} url=$url")
                    }
                    val body = resp.body ?: throw IOException("响应体为空: $url")
                    val cl = body.contentLength()
                    if (cl > maxStringBytes) {
                        throw IOException("响应体过大 ($cl > $maxStringBytes): $url")
                    }
                    val bytes = body.bytes()
                    if (bytes.size > maxStringBytes) {
                        throw IOException("响应体过大 (${bytes.size} > $maxStringBytes): $url")
                    }
                    return String(bytes, StandardCharsets.UTF_8)
                }
            } catch (e: IOException) {
                last = e
                try { Thread.sleep(500L * (i + 1)) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw last!!
    }

    /**
     * 带 SSRF 防护的文本下载。
     */
    @Throws(IOException::class)
    fun downloadStringSsrfChecked(url: String): String {
        val err = SsrfChecker.validate(url)
        if (err != null) throw IOException("SSRF blocked: $err")

        val safe = http.newBuilder()
            .addNetworkInterceptor { chain ->
                val hop = chain.request().url.toString()
                val hopErr = SsrfChecker.validate(hop)
                if (hopErr != null) throw IOException("SSRF redirect blocked: $hopErr")
                chain.proceed(chain.request())
            }
            .build()
        val req = Request.Builder().url(url).get().build()
        safe.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败 code=${resp.code} url=$url")
            val body = resp.body ?: throw IOException("响应体为空: $url")
            val cl = body.contentLength()
            if (cl > maxStringBytes) throw IOException("响应体过大 ($cl > $maxStringBytes): $url")
            val bytes = body.bytes()
            if (bytes.size > maxStringBytes) throw IOException("响应体过大 (${bytes.size} > $maxStringBytes): $url")
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    /**
     * 下载文本并按 SHA-1 校验。
     */
    @Throws(IOException::class)
    fun downloadStringVerified(url: String, expectedSha1: String?): String {
        if (expectedSha1.isNullOrBlank()) throw IOException("拒绝无 SHA-1 的文本下载: $url")
        val body = downloadString(url)
        val actual = sha1OfBytes(body.toByteArray(StandardCharsets.UTF_8))
        if (!actual.equals(expectedSha1.trim(), ignoreCase = true)) {
            throw IOException("文本 SHA-1 校验失败: $url 期望=$expectedSha1 实际=$actual")
        }
        return body
    }

    private fun sha1OfBytes(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(data)
        return md.digest().joinToString("") { String.format("%02x", it.toInt() and 0xff) }
    }

    /** 下载到指定绝对路径（无进度回调） */
    @Throws(IOException::class)
    fun downloadTo(url: String, target: Path) {
        downloadTo(url, target, null)
    }

    /**
     * 下载后按 SHA-512（优先）或 SHA-1 校验。
     */
    @Throws(IOException::class)
    fun downloadToVerified(url: String, target: Path, sha1: String?, sha512: String?) {
        if (sha1.isNullOrBlank() && sha512.isNullOrBlank()) {
            throw IOException("拒绝无哈希校验的下载: $url")
        }
        Files.createDirectories(target.parent)
        val verifiedTmp = target.resolveSibling(target.fileName.toString() + ".verified-tmp")
        Files.deleteIfExists(verifiedTmp)
        try {
            downloadTo(url, verifiedTmp)
            verifyHashesOrWarn(verifiedTmp, sha1, sha512)
            try {
                Files.move(verifiedTmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(verifiedTmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            try { Files.deleteIfExists(verifiedTmp) } catch (_: IOException) {}
            throw e
        }
    }

    /** 校验已下载文件 */
    @Throws(IOException::class)
    fun verifyHashesOrWarn(file: Path, sha1: String?, sha512: String?) {
        if (!sha512.isNullOrBlank()) {
            val actual = sha512Hex(file)
            if (!actual.equals(sha512.trim(), ignoreCase = true)) {
                Files.deleteIfExists(file)
                throw IOException("SHA-512 校验失败: ${file.fileName} 期望 $sha512 实际 $actual")
            }
            return
        }
        if (!sha1.isNullOrBlank()) {
            val actual = sha1(file)
            if (!actual.equals(sha1.trim(), ignoreCase = true)) {
                Files.deleteIfExists(file)
                throw IOException("SHA-1 校验失败: ${file.fileName} 期望 $sha1 实际 $actual")
            }
            return
        }
        Files.deleteIfExists(file)
        throw IOException("拒绝无哈希校验的下载: ${file.fileName}")
    }

    /**
     * 带 SSRF 防护的下载。
     */
    @Throws(IOException::class)
    fun downloadToSsrfChecked(url: String, target: Path) {
        downloadToSsrfChecked(url, target, null)
    }

    @Throws(IOException::class)
    fun downloadToSsrfChecked(url: String, target: Path, onProgress: Consumer<Long>?) {
        val err = SsrfChecker.validate(url)
        if (err != null) throw IOException("SSRF blocked: $err")
        Files.createDirectories(target.parent)
        val safe = http.newBuilder()
            .addNetworkInterceptor { chain ->
                val hop = chain.request().url.toString()
                val hopErr = SsrfChecker.validate(hop)
                if (hopErr != null) throw IOException("SSRF redirect blocked: $hopErr")
                chain.proceed(chain.request())
            }
            .build()
        val req = Request.Builder().url(url).get().build()
        safe.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful || resp.body == null) {
                throw IOException("HTTP ${resp.code} downloading $url")
            }
            val cl = resp.body!!.contentLength()
            if (cl > maxSsrfDownloadBytes) {
                throw IOException("下载过大 ($cl > $maxSsrfDownloadBytes): $url")
            }
            val tmp = target.resolveSibling(target.fileName.toString() + ".ssrf-tmp-${UUID.randomUUID()}")
            try {
                resp.body!!.byteStream().use { inp ->
                    Files.newOutputStream(tmp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                    ).use { out ->
                        val buf = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > maxSsrfDownloadBytes) {
                                throw IOException("下载过大 (>$maxSsrfDownloadBytes): $url")
                            }
                            out.write(buf, 0, n)
                            onProgress?.accept(total)
                        }
                    }
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                try { Files.deleteIfExists(tmp) } catch (_: IOException) {}
            }
        }
    }

    /**
     * 下载到指定绝对路径，带字节进度回调。支持断点续传和限速。
     */
    @Throws(IOException::class)
    fun downloadTo(url: String, target: Path, onProgress: ((Long) -> Unit)?) {
        Files.createDirectories(target.parent)
        val rewritten = rewrite(url)

        synchronized(fileLockFor(target)) {
            val tmp = target.resolveSibling(target.fileName.toString() + ".download")
            var existingSize = 0L
            if (enableResume && Files.exists(tmp)) {
                existingSize = Files.size(tmp)
            }

            var last: IOException? = null
            for (i in 0..retryCount) {
                val reqBuilder = Request.Builder().url(rewritten).get()
                if (enableResume && existingSize > 0) {
                    reqBuilder.header("Range", "bytes=$existingSize-")
                }
                val req = reqBuilder.build()
                try {
                    http.newCall(req).execute().use { resp ->
                        val code = resp.code
                        val rangeOk = code == 206
                        val fullOk = code == 200
                        if (!rangeOk && !fullOk) {
                            throw IOException("下载失败 code=$code url=$url")
                        }
                        val startPos = if (rangeOk) existingSize else 0L
                        if (rangeOk) {
                            val contentRange = resp.header("Content-Range")
                            if (!DownloadUtils.contentRangeMatches(contentRange, existingSize)) {
                                val expectedStart = existingSize
                                Files.deleteIfExists(tmp)
                                existingSize = 0
                                throw IOException("Content-Range 不匹配: $contentRange (expected start=$expectedStart)")
                            }
                        }
                        if (fullOk && Files.exists(tmp)) {
                            Files.deleteIfExists(tmp)
                            existingSize = 0
                        }
                        val body = resp.body ?: throw IOException("响应体为空: $url")
                        body.byteStream().use { inp ->
                            RandomAccessFile(tmp.toFile(), "rw").use { raf ->
                                raf.seek(startPos)
                                val buf = ByteArray(bufferSize)
                                var lastThrottleTime = System.currentTimeMillis()
                                var bytesInWindow = 0L
                                var lastNotify = 0L
                                var total = startPos
                                while (true) {
                                    throwIfInterrupted()
                                    val n = inp.read(buf)
                                    if (n == -1) break
                                    raf.write(buf, 0, n)
                                    total += n
                                    bytesInWindow += n
                                    if (speedLimitBytesPerSec > 0) {
                                        val now = System.currentTimeMillis()
                                        val elapsed = now - lastThrottleTime
                                        if (elapsed >= 100) {
                                            val allowed = speedLimitBytesPerSec * elapsed / 1000L
                                            if (bytesInWindow > allowed) {
                                                val sleepMs = (bytesInWindow - allowed) * 1000L / speedLimitBytesPerSec
                                                try { Thread.sleep(sleepMs) }
                                                catch (_: InterruptedException) {
                                                    Thread.currentThread().interrupt()
                                                    throw InterruptedIOException("下载已中断")
                                                }
                                            }
                                            lastThrottleTime = System.currentTimeMillis()
                                            bytesInWindow = 0
                                        }
                                    }
                                    if (onProgress != null) {
                                        val t = System.currentTimeMillis()
                                        if (t - lastNotify >= progressThrottleMs) {
                                            lastNotify = t
                                            onProgress.invoke(total)
                                        }
                                    }
                                }
                                onProgress?.invoke(total)
                            }
                        }
                        // 原子重命名
                        try {
                            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                        return
                    }
                } catch (e: InterruptedIOException) {
                    throw e
                } catch (e: IOException) {
                    last = e
                    if (enableResume && Files.exists(tmp)) {
                        try { existingSize = Files.size(tmp) } catch (_: Exception) {}
                    }
                    if (i >= 1) {
                        try { Files.deleteIfExists(tmp) } catch (_: IOException) {}
                        existingSize = 0
                    }
                    val base = 500L * (1L shl i)
                    val jitter = ThreadLocalRandom.current().nextLong(200)
                    try { Thread.sleep(base + jitter) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("下载已中断")
                    }
                }
            }
            throw last!!
        }
    }

    /** SHA1 异步校验 */
    private fun sha1Async(file: Path): CompletableFuture<String> =
        CompletableFuture.supplyAsync({
            try { sha1(file) }
            catch (e: IOException) { throw RuntimeException(e) }
        }, verifyPool)

    @Throws(IOException::class)
    private fun sha1(file: Path): String = digestHex(file, "SHA-1")

    @Throws(IOException::class)
    private fun sha512Hex(file: Path): String = digestHex(file, "SHA-512")

    @Throws(IOException::class)
    private fun digestHex(file: Path, algo: String): String {
        val md = MessageDigest.getInstance(algo)
        Files.newInputStream(file).use { inp ->
            val buf = ByteArray(bufferSize)
            while (true) {
                val n = inp.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it.toInt() and 0xff) }
    }

    /** 连接预热 */
    fun warmupConnections(urls: List<String>?) {
        if (urls.isNullOrEmpty()) return
        CompletableFuture.runAsync({
            for (url in urls) {
                try {
                    val rewritten = rewrite(url)
                    val head = Request.Builder().url(rewritten).head().build()
                    http.newCall(head).execute().use { }
                } catch (_: Throwable) {}
            }
        }, pool)
    }
}
