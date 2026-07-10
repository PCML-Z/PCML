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

    private final OkHttpClient http;
    private final int chunkCount;
    private final ExecutorService pool;

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
            size = len != null ? Long.parseLong(len) : -1L;
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
        try (RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
            raf.setLength(size);
        }

        long chunkSize = size / actualChunks;
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicLong completed = new AtomicLong(0);
        // 进度节流：避免每 read 都通知造成 UI 抖动
        final long[] lastNotifyTime = {0};

        for (int i = 0; i < actualChunks; i++) {
            long start = i * chunkSize;
            long end = (i == actualChunks - 1) ? size - 1 : (start + chunkSize - 1);
            final long s = start, e = end;
            final int idx = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    downloadChunkWithRetry(url, partFile, s, e, idx, bytes -> {
                        long now = completed.addAndGet(bytes);
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
            Files.deleteIfExists(partFile);
            throw ex;
        }

        // 原子重命名
        Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (onProgress != null) onProgress.accept(size);
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
        if (!acceptRanges || size < 0 || chunkCount == 1 || size < 8 * 1024 * 1024) {
            return 1;
        }
        int bySize = (int) (size / MIN_CHUNK_SIZE);
        return Math.min(Math.max(1, bySize), Math.min(chunkCount, MAX_CHUNKS));
    }

    /**
     * 单分片下载（带重试）：失败自动重试 {@value #CHUNK_RETRY} 次。
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
                    try { Thread.sleep(300L * (attempt + 1)); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ex;
                    }
                }
            }
        }
        throw last;
    }

    private void downloadChunk(String url, Path partFile, long start, long end, int idx,
                               Consumer<Long> onBytes) throws IOException {
        Request req = new Request.Builder().url(url)
                .header("Range", "bytes=" + start + "-" + end)
                .get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() != 206 && resp.code() != 200) {
                throw new IOException("分片 " + idx + " code=" + resp.code());
            }
            try (var in = resp.body().byteStream();
                 RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
                raf.seek(start);
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buf)) != -1) {
                    raf.write(buf, 0, n);
                    if (onBytes != null) onBytes.accept((long) n);
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
            try (var in = resp.body().byteStream();
                 RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                raf.setLength(0); // 显式截断，避免旧临时文件残留
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                long total = 0;
                long lastNotify = 0;
                while ((n = in.read(buf)) != -1) {
                    raf.write(buf, 0, n);
                    total += n;
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
