package com.pmcl.core.update;

import com.pmcl.core.download.DownloadManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 把清单里变化的文件下载到暂存目录，并在移动前核对大小、哈希和 jar。 */
public final class HotUpdateDownloader {
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    /** 同时拉取的文件数。每个文件还要跟一次重定向，串行时延迟会叠起来。 */
    static final int PARALLEL = 6;
    private static final long PROGRESS_INTERVAL_NS = 50_000_000L;

    private HotUpdateDownloader() {}

    public static Path stage(DownloadManager downloads, Path stagingRoot,
                             List<HotUpdateManifest.FileEntry> files,
                             Consumer<Long> onProgress) throws IOException {
        return stage(downloads, stagingRoot, files, onProgress, null);
    }

    /**
     * @param rewrite 把清单里的浏览器地址换成 API 资产地址。校验仍按清单上的哈希。
     */
    public static Path stage(DownloadManager downloads, Path stagingRoot,
                             List<HotUpdateManifest.FileEntry> files,
                             Consumer<Long> onProgress,
                             java.util.function.Function<String, String> rewrite) throws IOException {
        if (downloads == null) throw new IOException("下载组件不可用");
        return stage(stagingRoot, files, onProgress, rewrite, (url, dest, progress) ->
                downloads.downloadToSsrfChecked(url, dest, progress, MAX_FILE_BYTES));
    }

    /** 测试注入下载动作。正式路径仍走带 SSRF 检查的下载。 */
    static Path stage(Path stagingRoot, List<HotUpdateManifest.FileEntry> files,
                      Consumer<Long> onProgress,
                      java.util.function.Function<String, String> rewrite,
                      Fetcher fetcher) throws IOException {
        Files.createDirectories(stagingRoot);
        if (files.isEmpty()) return stagingRoot;
        int threads = Math.min(PARALLEL, files.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "pmcl-hotupdate");
            t.setDaemon(true);
            return t;
        });
        long[] current = new long[files.size()];
        AtomicLong lastReport = new AtomicLong();
        AtomicReference<IOException> failure = new AtomicReference<>();
        try {
            List<Future<?>> jobs = new ArrayList<>(files.size());
            for (int i = 0; i < files.size(); i++) {
                int index = i;
                jobs.add(pool.submit(() -> {
                    if (failure.get() != null) return;
                    try {
                        fetchOne(stagingRoot, files.get(index), rewrite, fetcher, read ->
                                report(current, index, read, onProgress, lastReport, false));
                        report(current, index, files.get(index).size(), onProgress, lastReport, true);
                    } catch (IOException e) {
                        failure.compareAndSet(null, e);
                    }
                }));
            }
            for (Future<?> job : jobs) {
                try {
                    job.get();
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new IOException("热更新下载已取消", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof IOException io) throw io;
                    throw new IOException("热更新下载失败", cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        IOException error = failure.get();
        if (error != null) throw error;
        return stagingRoot;
    }

    private static void fetchOne(Path stagingRoot, HotUpdateManifest.FileEntry file,
                                 java.util.function.Function<String, String> rewrite,
                                 Fetcher fetcher, Consumer<Long> progress) throws IOException {
        HotUpdateManifest.FileEntry.validatePath(file.path());
        if (file.url().isBlank()) throw new IOException("热更新文件没有下载地址: " + file.path());
        String url = file.url();
        if (rewrite != null) {
            String next = rewrite.apply(url);
            if (next != null && !next.isBlank()) url = next;
        }
        if (file.size() > MAX_FILE_BYTES) {
            throw new IOException("热更新文件超过上限: " + file.path());
        }
        Path dest = stagingRoot.resolve(file.path()).normalize();
        if (!dest.startsWith(stagingRoot.normalize())) {
            throw new IOException("热更新暂存路径越界: " + file.path());
        }
        Files.createDirectories(dest.getParent());
        fetcher.fetch(url, dest, progress);
        if (Files.size(dest) != file.size()) {
            Files.deleteIfExists(dest);
            throw new IOException("热更新文件大小不符: " + file.path());
        }
        String actual = FileDigest.sha256(dest);
        if (!actual.equals(file.sha256()) || !FileDigest.contentLooksValid(dest)) {
            Files.deleteIfExists(dest);
            throw new IOException("热更新文件校验失败: " + file.path());
        }
    }

    private static void report(long[] current, int index, long read, Consumer<Long> onProgress,
                               AtomicLong lastReport, boolean force) {
        if (onProgress == null) return;
        long now = System.nanoTime();
        long sum;
        synchronized (current) {
            current[index] = read;
            if (!force && now - lastReport.get() < PROGRESS_INTERVAL_NS) return;
            lastReport.set(now);
            sum = 0;
            for (long value : current) sum += value;
        }
        onProgress.accept(sum);
    }

    @FunctionalInterface
    interface Fetcher {
        void fetch(String url, Path dest, Consumer<Long> progress) throws IOException;
    }
}
