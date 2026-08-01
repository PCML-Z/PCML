package com.lash.pmcl.core.download

import com.lash.pmcl.core.util.FileUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * 多线程分片下载：单文件分多个连接并行下载。
 * <p>
 * 性能优化（对比 PCL 提速关键路径）：
 * <ul>
 *   <li>复用外部线程池（原每次调用创建新线程池，造成 GC 压力）</li>
 *   <li>256KB 缓冲区（原 16KB，减少 syscall 16 倍）</li>
 *   <li>自适应分片数：每片至少 2MB，最多 16 片（原固定 chunkCount）</li>
 *   <li>进度回调节流：每 50ms 通知一次（原每 read 都通知，UI 抖动严重）</li>
 *   <li>分片失败重试：单分片失败自动重试 2 次，不影响其他分片</li>
 * </ul>
 * <p>
 * 流程：
 * <ol>
 *   <li>HEAD 请求获取 Content-Length 与 Accept-Ranges</li>
 *   <li>若服务器支持 ranges 且文件 > 8MB，按 chunk 切分并行下载到 .part 文件</li>
 *   <li>否则回退到单连接下载</li>
 *   <li>全部完成后原子重命名为最终文件</li>
 * </ol>
 * 适用场景：大文件（>8MB）如 client.jar、Java runtime 归档、Forge installer。
 */
class ChunkedDownloader(
    // H6: http 改为 volatile，使 reconfigure 时可切换 http 引用而不丢弃在途分片状态
    // 原实现直接 new ChunkedDownloader 替换实例，在途分片下载使用旧 http 完成，
    // 但进度状态丢失，.part 文件数据竞争（新旧实例同时写同一文件）
    http: OkHttpClient,
    /** 期望分片数（实际会自适应，1-MAX_CHUNKS） */
    chunkCount: Int,
    /** 外部线程池（复用，避免每次创建） */
    private val pool: ExecutorService
) {
    @Volatile
    private var http: OkHttpClient = http

    private val chunkCount: Int = Math.max(1, chunkCount)

    @Volatile
    private var speedLimitBytesPerSec: Long = 0L

    /**
     * H6: 更新 HttpClient 引用（reconfigure 时调用）。
     * 不替换 ChunkedDownloader 实例，保留在途分片状态和 .part 文件写入。
     */
    fun updateHttpClient(newHttp: OkHttpClient) {
        this.http = newHttp
    }

    /** 设置限速（bytes/sec，0=不限） */
    fun setSpeedLimit(bytesPerSec: Long) {
        this.speedLimitBytesPerSec = Math.max(0, bytesPerSec)
    }

    /**
     * 分片下载文件。
     *
     * @param url        资源 URL（已镜像重写）
     * @param target     目标文件路径
     * @param onProgress 进度回调（已完成字节数，节流到 50ms 一次）
     */
    fun download(url: String, target: Path, onProgress: ((Long) -> Unit)?): CompletableFuture<Void> {
        return download(url, target, null, onProgress)
    }

    /**
     * 分片下载文件（带 SHA1 校验）。
     * P1-2: 完成后强制校验 SHA1，防止分片续传错位或 CDN 行为不一致导致损坏文件静默通过。
     *
     * @param url           资源 URL（已镜像重写）
     * @param target        目标文件路径
     * @param expectedSha1  期望的 SHA-1（null 表示不校验）
     * @param onProgress    进度回调（已完成字节数，节流到 50ms 一次）
     */
    fun download(
        url: String, target: Path, expectedSha1: String?,
        onProgress: ((Long) -> Unit)?
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync({
            try {
                doDownload(url, target, onProgress)
                // P1-2: 下载完成后校验 SHA1，防止分片续传错位/CDN 行为不一致导致损坏文件
                if (!expectedSha1.isNullOrEmpty()) {
                    val actual = sha1(target)
                    if (!actual.equals(expectedSha1, ignoreCase = true)) {
                        try { Files.deleteIfExists(target) } catch (_: IOException) {}
                        throw IOException("分片下载 SHA1 校验失败: $target 期望=$expectedSha1 实际=$actual")
                    }
                }
            } catch (e: IOException) {
                throw RuntimeException("分片下载失败: $url", e)
            }
        }, pool)
    }

    private fun doDownload(url: String, target: Path, onProgress: ((Long) -> Unit)?) {
        Files.createDirectories(target.parent)

        // HEAD 请求获取大小与 Range 支持
        val size: Long
        val acceptRanges: Boolean
        val head = Request.Builder().url(url).head().build()
        http.newCall(head).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HEAD 失败 code=${resp.code} url=$url")
            }
            val len = resp.header("Content-Length")
            size = if (len != null) {
                try {
                    len.toLong()
                } catch (ex: NumberFormatException) {
                    -1L
                }
            } else {
                -1L
            }
            val ar = resp.header("Accept-Ranges")
            acceptRanges = ar != null && ar.equals("bytes", ignoreCase = true)
        }

        // 自适应分片数：每片至少 MIN_CHUNK_SIZE，最多 MAX_CHUNKS
        val actualChunks = calcChunkCount(size, acceptRanges)
        if (actualChunks == 1) {
            singleDownload(url, target, onProgress)
            return
        }

        // 分片下载到 .part 文件
        val partFile = target.resolveSibling(target.fileName.toString() + ".part")
        // 加载已完成的分片进度（断点续传）
        // S15: 传入 url 校验，切换镜像后 hash 不匹配则丢弃旧进度，避免拼接不同文件数据
        val chunkCompleted = loadChunkProgress(target, actualChunks, url, size)
        // 预分配文件
        RandomAccessFile(partFile.toFile(), "rw").use { raf ->
            raf.setLength(size)
        }

        val chunkSize = size / actualChunks
        val futures = ArrayList<CompletableFuture<Void>>()
        val completed = AtomicLong(0)
        // 统计已完成字节数（含续传的已下载部分）
        for (i in 0 until actualChunks) {
            completed.addAndGet(chunkCompleted[i])
        }
        // 进度节流：用 AtomicLong 避免多分片线程 check-then-act 竞态
        val lastNotifyTime = AtomicLong(0)
        // 初始通知一次当前进度
        if (onProgress != null && completed.get() > 0) {
            onProgress.invoke(completed.get())
        }

        for (i in 0 until actualChunks) {
            val chunkStart = i * chunkSize
            val chunkEnd = if (i == actualChunks - 1) size - 1 else chunkStart + chunkSize - 1
            val alreadyDone = chunkCompleted[i]
            // 该分片已完整下载，跳过
            if (alreadyDone >= chunkEnd - chunkStart + 1) continue
            val s = chunkStart + alreadyDone
            val e = chunkEnd
            val idx = i
            val skipBytes = alreadyDone
            futures.add(CompletableFuture.runAsync({
                // 用数组模拟引用，让 lambda 内部可变
                val sessionBytes = longArrayOf(0)
                try {
                    downloadChunkWithRetry(url, partFile, s, e, idx) { deltaBytes ->
                        sessionBytes[0] += deltaBytes
                        chunkCompleted[idx] = skipBytes + sessionBytes[0]
                        val now = completed.addAndGet(deltaBytes)
                        // 节流：50ms 内只通知一次
                        val t = System.currentTimeMillis()
                        if (onProgress != null && t - lastNotifyTime.get() >= PROGRESS_THROTTLE_MS) {
                            lastNotifyTime.set(t)
                            onProgress.invoke(now)
                        }
                    }
                } catch (ex: IOException) {
                    throw RuntimeException("分片 $idx 失败", ex)
                }
            }, pool))
        }

        // 等待所有分片完成
        try {
            CompletableFuture.allOf(*futures.toTypedArray()).join()
        } catch (ex: RuntimeException) {
            // 先取消兄弟分片，避免继续写同一 .part
            for (f in futures) {
                f.cancel(true)
            }
            val cause = ex.cause ?: ex
            if (isInterruptCause(cause) || Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("分片下载已中断")
            }
            // 非中断失败：保留 .part 与进度，降级单连接
            saveChunkProgress(target, chunkCompleted, url, size)
            try {
                fallbackSingleConnection(url, partFile, target, size, chunkCompleted,
                    chunkSize, actualChunks, onProgress)
            } catch (fallbackErr: IOException) {
                if (isInterruptCause(fallbackErr) || Thread.currentThread().isInterrupted) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("分片下载已中断")
                }
                throw ex
            }
            return
        }

        // 全部完成：校验总长度后再提升
        if (!Files.isRegularFile(partFile) || Files.size(partFile) != size) {
            throw IOException("分片合并后大小不匹配: expected=$size actual=${if (Files.exists(partFile)) Files.size(partFile) else -1}")
        }
        deleteChunkProgress(target)
        try {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING)
        }
        if (onProgress != null) onProgress.invoke(size)
    }

    /**
     * 加载分片进度（断点续传）。
     * 返回每个分片已下载的字节数，无进度文件或 URL 不匹配则返回全 0 数组。
     * <p>
     * S15: 进度文件首行存储 URL hash，切换镜像后续传会因 hash 不匹配而丢弃旧进度，
     * 避免拼接不同文件数据导致文件损坏。
     * <p>
     * P1-2: 第 2 行存储 expectedSize + chunkCount，Content-Length 或分片数变化时丢弃进度，
     * 避免旧分片边界被强行套到新分片导致 seek 写入错误偏移。
     */
    private fun loadChunkProgress(target: Path, chunkCount: Int, url: String, expectedSize: Long): LongArray {
        val progressFile = target.resolveSibling(target.fileName.toString() + PROGRESS_SUFFIX)
        if (!Files.exists(progressFile)) return LongArray(chunkCount)
        return try {
            val lines = Files.readAllLines(progressFile, StandardCharsets.UTF_8)
            if (lines.isEmpty()) return LongArray(chunkCount)
            // 首行是 URL hash，校验不匹配则丢弃旧进度
            val expectedHash = calculateUrlHash(url)
            if (expectedHash != lines[0].trim()) {
                return LongArray(chunkCount)
            }
            // P1-2: 第 2 行是 "size|chunkCount"，不匹配则丢弃（CDN 行为不一致或分片算法调整）
            if (lines.size >= 2) {
                val meta = lines[1].trim().split("\\|".toRegex())
                if (meta.size == 2) {
                    try {
                        val savedSize = meta[0].toLong()
                        val savedChunks = meta[1].toInt()
                        if (savedSize != expectedSize || savedChunks != chunkCount) {
                            return LongArray(chunkCount)
                        }
                    } catch (_: NumberFormatException) {
                        return LongArray(chunkCount)
                    }
                } else {
                    // 旧格式无 meta 行，丢弃进度（安全第一）
                    return LongArray(chunkCount)
                }
            }
            val result = LongArray(chunkCount)
            // 从第 3 行开始解析分片进度
            for (i in 0 until Math.min(lines.size - 2, chunkCount)) {
                try {
                    result[i] = lines[i + 2].trim().toLong()
                } catch (_: NumberFormatException) {
                    result[i] = 0
                }
            }
            result
        } catch (e: Exception) {
            LongArray(chunkCount)
        }
    }

    /** 保存分片进度到 .chunks 文件（首行 URL hash，第 2 行 size|chunkCount） */
    private fun saveChunkProgress(target: Path, chunkCompleted: LongArray, url: String, expectedSize: Long) {
        val progressFile = target.resolveSibling(target.fileName.toString() + PROGRESS_SUFFIX)
        try {
            val sb = StringBuilder()
            sb.append(calculateUrlHash(url)).append('\n')
            sb.append(expectedSize).append('|').append(chunkCompleted.size).append('\n')
            for (c in chunkCompleted) {
                sb.append(c).append('\n')
            }
            FileUtils.writeString(progressFile, sb.toString(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // 保存失败不影响下载流程
        }
    }

    /** 计算 URL 的短 hash（SHA-256 前 16 字符），用于断点续传校验 */
    private fun calculateUrlHash(url: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(url.toByteArray(StandardCharsets.UTF_8))
            val hex = StringBuilder()
            for (i in 0 until Math.min(8, digest.size)) {
                hex.append(String.format("%02x", digest[i].toInt() and 0xff))
            }
            hex.toString()
        } catch (e: Exception) {
            // fallback: 用 url 长度 + hashCode
            "fallback_" + url.length + "_" + url.hashCode()
        }
    }

    /** 删除分片进度文件 */
    private fun deleteChunkProgress(target: Path) {
        try {
            Files.deleteIfExists(target.resolveSibling(target.fileName.toString() + PROGRESS_SUFFIX))
        } catch (_: Exception) {
        }
    }

    /**
     * 自适应计算分片数。
     * <ul>
     *   <li>文件 < 8MB 或服务器不支持 ranges → 单连接</li>
     *   <li>每片至少 MIN_CHUNK_SIZE bytes</li>
     *   <li>最多 MAX_CHUNKS 片</li>
     *   <li>不超过用户配置的 chunkCount</li>
     * </ul>
     */
    private fun calcChunkCount(size: Long, acceptRanges: Boolean): Int {
        if (!acceptRanges || size < 0 || chunkCount == 1 || size < 4 * 1024 * 1024) {
            return 1
        }
        val bySize = (size / MIN_CHUNK_SIZE).toInt()
        return Math.min(Math.max(1, bySize), Math.min(chunkCount, MAX_CHUNKS))
    }

    /**
     * 单分片下载（带重试）：失败自动重试 CHUNK_RETRY 次。
     * 使用指数退避 + 随机抖动避免 thundering herd。
     */
    @Throws(IOException::class)
    private fun downloadChunkWithRetry(
        url: String, partFile: Path, start: Long, end: Long, idx: Int,
        onBytes: ((Long) -> Unit)?
    ) {
        var last: IOException? = null
        for (attempt in 0..CHUNK_RETRY) {
            try {
                downloadChunk(url, partFile, start, end, idx, onBytes)
                return
            } catch (ex: InterruptedIOException) {
                throw ex
            } catch (ex: IOException) {
                last = ex
                if (attempt < CHUNK_RETRY) {
                    val base = 300L * (1L shl attempt) // 300ms, 600ms, 1200ms
                    val jitter = ThreadLocalRandom.current().nextLong(100)
                    try {
                        Thread.sleep(base + jitter)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("分片下载已中断")
                    }
                }
            }
        }
        throw last!!
    }

    /**
     * 降级单连接续传：分片下载失败后，用单连接顺序下载未完成的分片。
     * 避免一个分片失败导致整个文件重下。
     */
    @Throws(IOException::class)
    private fun fallbackSingleConnection(
        url: String, partFile: Path, target: Path, size: Long,
        chunkCompleted: LongArray, chunkSize: Long, actualChunks: Int,
        onProgress: ((Long) -> Unit)?
    ) {
        var completedTotal = 0L
        for (c in chunkCompleted) completedTotal += c
        val lastNotify = longArrayOf(0)

        for (i in 0 until actualChunks) {
            val chunkStart = i * chunkSize
            val chunkEnd = if (i == actualChunks - 1) size - 1 else chunkStart + chunkSize - 1
            val chunkLen = chunkEnd - chunkStart + 1
            if (chunkCompleted[i] >= chunkLen) continue // 已完成

            val resumeFrom = chunkStart + chunkCompleted[i]
            val req = Request.Builder().url(url)
                .header("Range", "bytes=$resumeFrom-$chunkEnd")
                .get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 200) {
                    // 服务器不支持 Range：单连接降级也无效，交给上层 singleDownload 处理
                    throw IOException("服务器不支持 Range 请求（返回 200），无法续传")
                }
                if (resp.code != 206) {
                    throw IOException("降级下载分片 $i code=${resp.code}")
                }
                val contentRange = resp.header("Content-Range")
                if (!DownloadUtils.contentRangeMatches(contentRange, resumeFrom)) {
                    throw IOException("降级分片 $i Content-Range 不匹配: $contentRange (expected start=$resumeFrom)")
                }
                if (resp.body == null) throw IOException("响应体为空: $url")
                resp.body!!.byteStream().use { inp ->
                    RandomAccessFile(partFile.toFile(), "rw").use { raf ->
                        raf.seek(resumeFrom)
                        val buf = ByteArray(BUFFER_SIZE)
                        var lastThrottleTime = System.currentTimeMillis()
                        var bytesInWindow = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n == -1) break
                            throwIfInterrupted()
                            raf.write(buf, 0, n)
                            completedTotal += n
                            chunkCompleted[i] += n
                            bytesInWindow += n
                            // 限速
                            if (speedLimitBytesPerSec > 0) {
                                val now = System.currentTimeMillis()
                                val elapsed = now - lastThrottleTime
                                if (elapsed >= 100) {
                                    val allowed = speedLimitBytesPerSec * elapsed / 1000L
                                    if (bytesInWindow > allowed) {
                                        val sleepMs = (bytesInWindow - allowed) * 1000L / speedLimitBytesPerSec
                                        try {
                                            Thread.sleep(sleepMs)
                                        } catch (ie: InterruptedException) {
                                            Thread.currentThread().interrupt()
                                            throw InterruptedIOException("分片下载已中断")
                                        }
                                    }
                                    lastThrottleTime = System.currentTimeMillis()
                                    bytesInWindow = 0
                                }
                            }
                            // 进度节流
                            val t = System.currentTimeMillis()
                            if (onProgress != null && t - lastNotify[0] >= PROGRESS_THROTTLE_MS) {
                                lastNotify[0] = t
                                onProgress.invoke(completedTotal)
                            }
                        }
                    }
                }
            }
        }
        // 全部完成，清理进度文件并重命名
        deleteChunkProgress(target)
        try {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING)
        }
        if (onProgress != null) onProgress.invoke(size)
    }

    @Throws(IOException::class)
    private fun downloadChunk(
        url: String, partFile: Path, start: Long, end: Long, idx: Int,
        onBytes: ((Long) -> Unit)?
    ) {
        val req = Request.Builder().url(url)
            .header("Range", "bytes=$start-$end")
            .get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 200) {
                // 服务器忽略 Range，返回整个文件。分片模式下不能 seek+write，否则数据错乱。
                // 抛出异常触发上层 fallbackSingleConnection 或 singleDownload 处理。
                throw IOException("服务器不支持 Range 请求（返回 200），无法分片下载")
            }
            if (resp.code != 206) {
                throw IOException("分片 $idx code=${resp.code}")
            }
            val contentRange = resp.header("Content-Range")
            if (!DownloadUtils.contentRangeMatches(contentRange, start)) {
                throw IOException("分片 $idx Content-Range 不匹配: $contentRange (expected start=$start)")
            }
            if (resp.body == null) throw IOException("响应体为空: $url")
            val expected = end - start + 1
            var written = 0L
            resp.body!!.byteStream().use { inp ->
                RandomAccessFile(partFile.toFile(), "rw").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(BUFFER_SIZE)
                    var lastThrottleTime = System.currentTimeMillis()
                    var bytesInWindow = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n == -1) break
                        throwIfInterrupted()
                        if (written + n > expected) {
                            throw IOException("分片 $idx 写入超出 Range: ${written + n}/$expected")
                        }
                        raf.write(buf, 0, n)
                        written += n
                        if (onBytes != null) onBytes.invoke(n.toLong())
                        // 限速
                        if (speedLimitBytesPerSec > 0) {
                            bytesInWindow += n
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastThrottleTime
                            if (elapsed >= 100) {
                                val allowed = speedLimitBytesPerSec * elapsed / 1000L
                                if (bytesInWindow > allowed) {
                                    val sleepMs = (bytesInWindow - allowed) * 1000L / speedLimitBytesPerSec
                                    try {
                                        Thread.sleep(sleepMs)
                                    } catch (ie: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        throw InterruptedIOException("分片下载已中断")
                                    }
                                }
                                lastThrottleTime = System.currentTimeMillis()
                                bytesInWindow = 0
                            }
                        }
                    }
                }
            }
            if (written != expected) {
                throw IOException("分片 $idx 长度不足: $written/$expected")
            }
        }
    }

    @Throws(IOException::class)
    private fun singleDownload(url: String, target: Path, onProgress: ((Long) -> Unit)?) {
        val req = Request.Builder().url(url).get().build()
        // 先写入临时文件，完成后原子重命名（避免旧文件残留导致损坏）
        val tmp = target.resolveSibling(target.fileName.toString() + ".part")
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("下载失败 code=${resp.code} url=$url")
            }
            if (resp.body == null) throw IOException("响应体为空: $url")
            resp.body!!.byteStream().use { inp ->
                RandomAccessFile(tmp.toFile(), "rw").use { raf ->
                    raf.setLength(0) // 显式截断，避免旧临时文件残留
                    val buf = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    var lastNotify = 0L
                    var lastThrottleTime = System.currentTimeMillis()
                    var bytesInWindow = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n == -1) break
                        throwIfInterrupted()
                        raf.write(buf, 0, n)
                        total += n
                        bytesInWindow += n
                        // 限速
                        if (speedLimitBytesPerSec > 0) {
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastThrottleTime
                            if (elapsed >= 100) {
                                val allowed = speedLimitBytesPerSec * elapsed / 1000L
                                if (bytesInWindow > allowed) {
                                    val sleepMs = (bytesInWindow - allowed) * 1000L / speedLimitBytesPerSec
                                    try {
                                        Thread.sleep(sleepMs)
                                    } catch (ie: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        throw InterruptedIOException("分片下载已中断")
                                    }
                                }
                                lastThrottleTime = System.currentTimeMillis()
                                bytesInWindow = 0
                            }
                        }
                        val t = System.currentTimeMillis()
                        if (onProgress != null && t - lastNotify >= PROGRESS_THROTTLE_MS) {
                            lastNotify = t
                            onProgress.invoke(total)
                        }
                    }
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            if (onProgress != null) {
                val size = if (Files.exists(target)) Files.size(target) else 0L
                onProgress.invoke(size)
            }
        }
    }

    @Throws(InterruptedIOException::class)
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("分片下载已中断")
        }
    }

    /** P1-2: 计算文件 SHA-1，用于分片下载完成后校验完整性 */
    @Throws(IOException::class)
    private fun sha1(file: Path): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(file).use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n == -1) break
                    md.update(buf, 0, n)
                }
            }
            val digest = md.digest()
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append(String.format("%02x", b))
            sb.toString()
        } catch (e: NoSuchAlgorithmException) {
            throw IOException("SHA-1 不可用", e)
        }
    }

    companion object {
        /** 缓冲区大小：256KB（原 16KB） */
        private const val BUFFER_SIZE = 256 * 1024

        /** 单分片最小大小：2MB（避免小文件过度分片） */
        private const val MIN_CHUNK_SIZE = 2L * 1024 * 1024

        /** 单文件最大分片数：16（避免对服务器造成过大压力） */
        private const val MAX_CHUNKS = 16

        /** 进度通知节流间隔：50ms（避免 UI 抖动） */
        private const val PROGRESS_THROTTLE_MS = 50L

        /** 单分片失败重试次数 */
        private const val CHUNK_RETRY = 2

        /** 分片进度记录文件后缀（用于断点续传） */
        private const val PROGRESS_SUFFIX = ".chunks"

        private fun isInterruptCause(e: Throwable): Boolean {
            var cur: Throwable? = e
            while (cur != null) {
                if (cur is InterruptedException || cur is InterruptedIOException) {
                    return true
                }
                if (cur is CancellationException) return true
                cur = cur.cause
            }
            return false
        }
    }
}
