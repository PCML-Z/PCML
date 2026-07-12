package com.pmcl.core.download;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
public final class ChunkedDownloader {

    /** 缓冲区大小：256KB（原 16KB） */
    private static final int BUFFER_SIZE = 256 * 1024;

    /** 单分片最小大小：2MB（避免小文件过度分片） */
    private static final long MIN_CHUNK_SIZE = 2L * 1024 * 1024;

    /** 单文件最大分片数：16（避免对服务器造成过大压力） */
    private static final int MAX_CHUNKS = 16;

    /** 进度通知节流间隔：50ms（避免 UI 抖动） */
    private static final long PROGRESS_THROTTLE_MS = 50;

    /** 单分片失败重试次数 */
    private static final int CHUNK_RETRY = 2;

    /** 分片进度记录文件后缀（用于断点续传） */
    private static final String PROGRESS_SUFFIX = ".chunks";

    private final OkHttpClient http;
    private final int chunkCount;
    private final ExecutorService pool;
    private volatile int speedLimitBytesPerSec = 0;

    /**
     * @param http       共享 OkHttpClient（必须配 Dispatcher.maxRequestsPerHost ≥ chunkCount）
     * @param chunkCount 期望分片数（实际会自适应，1-{@value #MAX_CHUNKS}）
     * @param pool       外部线程池（复用，避免每次创建）
     */
    public ChunkedDownloader(OkHttpClient http, int chunkCount, ExecutorService pool) {
        this.http = http;
        this.chunkCount = Math.max(1, chunkCount);
        this.pool = pool;
    }

    /** 设置限速（bytes/sec，0=不限） */
    public void setSpeedLimit(int bytesPerSec) {
        this.speedLimitBytesPerSec = Math.max(0, bytesPerSec);
    }

    /**
     * 分片下载文件。
     *
     * @param url        资源 URL（已镜像重写）
     * @param target     目标文件路径
     * @param onProgress 进度回调（已完成字节数，节流到 50ms 一次）
     */
    public CompletableFuture<Void> download(String url, Path target, Consumer<Long> onProgress) {
        return CompletableFuture.runAsync(() -> {
            try {
                doDownload(url, target, onProgress);
            } catch (IOException e) {
                throw new RuntimeException("分片下载失败: " + url, e);
            }
        }, pool);
    }

    private void doDownload(String url, Path target, Consumer<Long> onProgress) throws IOException {
        Files.createDirectories(target.getParent());

        // HEAD 请求获取大小与 Range 支持
        long size;
        boolean acceptRanges;
        Request head = new Request.Builder().url(url).head().build();
        try (Response resp = http.newCall(head).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HEAD 失败 code=" + resp.code() + " url=" + url);
            }
            String len = resp.header("Content-Length");
            if (len != null) {
                try {
                    size = Long.parseLong(len);
                } catch (NumberFormatException ex) {
                    size = -1L;
                }
            } else {
                size = -1L;
            }
            String ar = resp.header("Accept-Ranges");
            acceptRanges = ar != null && ar.equalsIgnoreCase("bytes");
        }

        // 自适应分片数：每片至少 MIN_CHUNK_SIZE，最多 MAX_CHUNKS
        int actualChunks = calcChunkCount(size, acceptRanges);
        if (actualChunks == 1) {
            singleDownload(url, target, onProgress);
            return;
        }

        // 分片下载到 .part 文件
        Path partFile = target.resolveSibling(target.getFileName() + ".part");
        // 加载已完成的分片进度（断点续传）
        long[] chunkCompleted = loadChunkProgress(target, actualChunks);
        // 预分配文件
        try (RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
            raf.setLength(size);
        }

        long chunkSize = size / actualChunks;
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicLong completed = new AtomicLong(0);
        // 统计已完成字节数（含续传的已下载部分）
        for (int i = 0; i < actualChunks; i++) {
            completed.addAndGet(chunkCompleted[i]);
        }
        // 进度节流：避免每 read 都通知造成 UI 抖动
        final long[] lastNotifyTime = {0};
        // 初始通知一次当前进度
        if (onProgress != null && completed.get() > 0) {
            onProgress.accept(completed.get());
        }

        for (int i = 0; i < actualChunks; i++) {
            long chunkStart = i * chunkSize;
            long chunkEnd = (i == actualChunks - 1) ? size - 1 : (chunkStart + chunkSize - 1);
            long alreadyDone = chunkCompleted[i];
            // 该分片已完整下载，跳过
            if (alreadyDone >= (chunkEnd - chunkStart + 1)) continue;
            final long s = chunkStart + alreadyDone;
            final long e = chunkEnd;
            final int idx = i;
            final long skipBytes = alreadyDone;
            futures.add(CompletableFuture.runAsync(() -> {
                // 用数组模拟引用，让 lambda 内部可变
                final long[] sessionBytes = {0};
                try {
                    downloadChunkWithRetry(url, partFile, s, e, idx, deltaBytes -> {
                        sessionBytes[0] += deltaBytes;
                        chunkCompleted[idx] = skipBytes + sessionBytes[0];
                        long now = completed.addAndGet(deltaBytes);
                        // 节流：50ms 内只通知一次
                        long t = System.currentTimeMillis();
                        if (onProgress != null && t - lastNotifyTime[0] >= PROGRESS_THROTTLE_MS) {
                            lastNotifyTime[0] = t;
                            onProgress.accept(now);
                        }
                    });
                } catch (IOException ex) {
                    throw new RuntimeException("分片 " + idx + " 失败", ex);
                }
            }, pool));
        }

        // 等待所有分片完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (RuntimeException ex) {
            // 分片失败：保留 .part 文件和进度，尝试降级为单连接续传剩余分片
            saveChunkProgress(target, chunkCompleted);
            try {
                fallbackSingleConnection(url, partFile, target, size, chunkCompleted,
                        chunkSize, actualChunks, onProgress);
            } catch (IOException fallbackErr) {
                throw ex; // 降级也失败，抛原始异常
            }
            return;
        }

        // 全部完成，清理进度文件
        deleteChunkProgress(target);
        // 原子重命名
        Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (onProgress != null) onProgress.accept(size);
    }

    /**
     * 加载分片进度（断点续传）。
     * 返回每个分片已下载的字节数，无进度文件则返回全 0 数组。
     */
    private long[] loadChunkProgress(Path target, int chunkCount) {
        Path progressFile = target.resolveSibling(target.getFileName() + PROGRESS_SUFFIX);
        if (!Files.exists(progressFile)) return new long[chunkCount];
        try {
            List<String> lines = Files.readAllLines(progressFile);
            long[] result = new long[chunkCount];
            for (int i = 0; i < Math.min(lines.size(), chunkCount); i++) {
                try {
                    result[i] = Long.parseLong(lines.get(i).trim());
                } catch (NumberFormatException ignored) {
                    result[i] = 0;
                }
            }
            return result;
        } catch (Exception e) {
            return new long[chunkCount];
        }
    }

    /** 保存分片进度到 .chunks 文件 */
    private void saveChunkProgress(Path target, long[] chunkCompleted) {
        Path progressFile = target.resolveSibling(target.getFileName() + PROGRESS_SUFFIX);
        try {
            StringBuilder sb = new StringBuilder();
            for (long c : chunkCompleted) {
                sb.append(c).append('\n');
            }
            Files.writeString(progressFile, sb.toString());
        } catch (Exception ignored) {
            // 保存失败不影响下载流程
        }
    }

    /** 删除分片进度文件 */
    private void deleteChunkProgress(Path target) {
        try {
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + PROGRESS_SUFFIX));
        } catch (Exception ignored) {}
    }

    /**
     * 自适应计算分片数。
     * <ul>
     *   <li>文件 < 8MB 或服务器不支持 ranges → 单连接</li>
     *   <li>每片至少 {@value #MIN_CHUNK_SIZE} bytes</li>
     *   <li>最多 {@value #MAX_CHUNKS} 片</li>
     *   <li>不超过用户配置的 chunkCount</li>
     * </ul>
     */
    private int calcChunkCount(long size, boolean acceptRanges) {
        if (!acceptRanges || size < 0 || chunkCount == 1 || size < 4 * 1024 * 1024) {
            return 1;
        }
        int bySize = (int) (size / MIN_CHUNK_SIZE);
        return Math.min(Math.max(1, bySize), Math.min(chunkCount, MAX_CHUNKS));
    }

    /**
     * 单分片下载（带重试）：失败自动重试 {@value #CHUNK_RETRY} 次。
     * 使用指数退避 + 随机抖动避免 thundering herd。
     */
    private void downloadChunkWithRetry(String url, Path partFile, long start, long end, int idx,
                                        Consumer<Long> onBytes) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= CHUNK_RETRY; attempt++) {
            try {
                downloadChunk(url, partFile, start, end, idx, onBytes);
                return;
            } catch (IOException ex) {
                last = ex;
                if (attempt < CHUNK_RETRY) {
                    long base = 300L * (1L << attempt); // 300ms, 600ms, 1200ms
                    long jitter = ThreadLocalRandom.current().nextLong(100);
                    try { Thread.sleep(base + jitter); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ex;
                    }
                }
            }
        }
        throw last;
    }

    /**
     * 降级单连接续传：分片下载失败后，用单连接顺序下载未完成的分片。
     * 避免一个分片失败导致整个文件重下。
     */
    private void fallbackSingleConnection(String url, Path partFile, Path target, long size,
                                          long[] chunkCompleted, long chunkSize, int actualChunks,
                                          Consumer<Long> onProgress) throws IOException {
        long completedTotal = 0;
        for (long c : chunkCompleted) completedTotal += c;
        final long[] lastNotify = {0};

        for (int i = 0; i < actualChunks; i++) {
            long chunkStart = i * chunkSize;
            long chunkEnd = (i == actualChunks - 1) ? size - 1 : (chunkStart + chunkSize - 1);
            long chunkLen = chunkEnd - chunkStart + 1;
            if (chunkCompleted[i] >= chunkLen) continue; // 已完成

            long resumeFrom = chunkStart + chunkCompleted[i];
            Request req = new Request.Builder().url(url)
                    .header("Range", "bytes=" + resumeFrom + "-" + chunkEnd)
                    .get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.code() == 200) {
                    // 服务器不支持 Range：单连接降级也无效，交给上层 singleDownload 处理
                    throw new IOException("服务器不支持 Range 请求（返回 200），无法续传");
                }
                if (resp.code() != 206) {
                    throw new IOException("降级下载分片 " + i + " code=" + resp.code());
                }
                if (resp.body() == null) throw new IOException("响应体为空: " + url);
                try (var in = resp.body().byteStream();
                     RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
                    raf.seek(resumeFrom);
                    byte[] buf = new byte[BUFFER_SIZE];
                    int n;
                    long lastThrottleTime = System.currentTimeMillis();
                    long bytesInWindow = 0;
                    while ((n = in.read(buf)) != -1) {
                        raf.write(buf, 0, n);
                        completedTotal += n;
                        chunkCompleted[i] += n;
                        bytesInWindow += n;
                        // 限速
                        if (speedLimitBytesPerSec > 0) {
                            long now = System.currentTimeMillis();
                            long elapsed = now - lastThrottleTime;
                            if (elapsed >= 100) {
                                long allowed = (speedLimitBytesPerSec * elapsed) / 1000L;
                                if (bytesInWindow > allowed) {
                                    long sleepMs = (bytesInWindow - allowed) * 1000L / speedLimitBytesPerSec;
                                    try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                lastThrottleTime = System.currentTimeMillis();
                                bytesInWindow = 0;
                            }
                        }
                        // 进度节流
                        long t = System.currentTimeMillis();
                        if (onProgress != null && t - lastNotify[0] >= PROGRESS_THROTTLE_MS) {
                            lastNotify[0] = t;
                            onProgress.accept(completedTotal);
                        }
                    }
                }
            }
        }
        // 全部完成，清理进度文件并重命名
        deleteChunkProgress(target);
        Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (onProgress != null) onProgress.accept(size);
    }

    private void downloadChunk(String url, Path partFile, long start, long end, int idx,
                               Consumer<Long> onBytes) throws IOException {
        Request req = new Request.Builder().url(url)
                .header("Range", "bytes=" + start + "-" + end)
                .get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 200) {
                // 服务器忽略 Range，返回整个文件。分片模式下不能 seek+write，否则数据错乱。
                // 抛出异常触发上层 fallbackSingleConnection 或 singleDownload 处理。
                throw new IOException("服务器不支持 Range 请求（返回 200），无法分片下载");
            }
            if (resp.code() != 206) {
                throw new IOException("分片 " + idx + " code=" + resp.code());
            }
            if (resp.body() == null) throw new IOException("响应体为空: " + url);
            try (var in = resp.body().byteStream();
                 RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
                raf.seek(start);
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                long lastThrottleTime = System.currentTimeMillis();
                long bytesInWindow = 0;
                while ((n = in.read(buf)) != -1) {
                    raf.write(buf, 0, n);
                    if (onBytes != null) onBytes.accept((long) n);
                    // 限速
                    if (speedLimitBytesPerSec > 0) {
                        bytesInWindow += n;
                        long now = System.currentTimeMillis();
                        long elapsed = now - lastThrottleTime;
                        if (elapsed >= 100) {
                            long allowed = (speedLimitBytesPerSec * elapsed) / 1000L;
                            if (bytesInWindow > allowed) {
                                long sleepMs = (bytesInWindow - allowed) * 1000L / speedLimitBytesPerSec;
                                try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            lastThrottleTime = System.currentTimeMillis();
                            bytesInWindow = 0;
                        }
                    }
                }
            }
        }
    }

    private void singleDownload(String url, Path target, Consumer<Long> onProgress) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        // 先写入临时文件，完成后原子重命名（避免旧文件残留导致损坏）
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("下载失败 code=" + resp.code() + " url=" + url);
            }
            if (resp.body() == null) throw new IOException("响应体为空: " + url);
            try (var in = resp.body().byteStream();
                 RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.setLength(0); // 显式截断，避免旧临时文件残留
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                long total = 0;
                long lastNotify = 0;
                long lastThrottleTime = System.currentTimeMillis();
                long bytesInWindow = 0;
                while ((n = in.read(buf)) != -1) {
                    raf.write(buf, 0, n);
                    total += n;
                    bytesInWindow += n;
                    // 限速
                    if (speedLimitBytesPerSec > 0) {
                        long now = System.currentTimeMillis();
                        long elapsed = now - lastThrottleTime;
                        if (elapsed >= 100) {
                            long allowed = (speedLimitBytesPerSec * elapsed) / 1000L;
                            if (bytesInWindow > allowed) {
                                long sleepMs = (bytesInWindow - allowed) * 1000L / speedLimitBytesPerSec;
                                try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            lastThrottleTime = System.currentTimeMillis();
                            bytesInWindow = 0;
                        }
                    }
                    long t = System.currentTimeMillis();
                    if (onProgress != null && t - lastNotify >= PROGRESS_THROTTLE_MS) {
                        lastNotify = t;
                        onProgress.accept(total);
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (onProgress != null) {
                long size = Files.exists(target) ? Files.size(target) : 0L;
                onProgress.accept(size);
            }
        }
    }
}
