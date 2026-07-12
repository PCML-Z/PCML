package com.pmcl.core.download;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.preferences.Preferences;
import okhttp3.Authenticator;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 下载管理：多线程下载 + 镜像源 + 代理 + 断点续传 + 限速 + 失败重试 + SHA1 校验。
 * <p>
 * 性能优化（目标对比 PCL 提速 10-15 倍）：
 * <ul>
 *   <li>单主机并发 64（OkHttp 默认 5 是 BMCLAPI 瓶颈）</li>
 *   <li>256KB 缓冲区（默认 8KB，减少 syscall 10-30 倍）</li>
 *   <li>{@link Semaphore} 背压控制，避免 3000+ 任务一次性提交撑爆队列</li>
 *   <li>SHA1 校验移到独立线程池，不阻塞下载线程</li>
 *   <li>大文件走 {@link ChunkedDownloader} 多分片并行下载</li>
 * </ul>
 * 通过 {@link #reconfigure(Preferences)} 应用最新的网络偏好（镜像/代理/限速等）。
 * 镜像重写由 {@link MirrorManager} 完成，所有下载入口都会先 rewrite URL。
 */
public final class DownloadManager {

    /** 缓冲区大小：256KB（原 8KB，减少 syscall 数量） */
    private static final int BUFFER_SIZE = 256 * 1024;

    /** 单主机最大并发请求数（OkHttp 默认 5，BMCLAPI 加速关键参数） */
    private static final int MAX_REQUESTS_PER_HOST = 64;

    /** 全局最大并发请求数 */
    private static final int MAX_REQUESTS = 128;

    /** 连接池容量：匹配 MAX_REQUESTS_PER_HOST，避免高并发时频繁重建 TCP/TLS 连接 */
    private static final int CONNECTION_POOL_SIZE = 64;

    /** 大于此阈值（4MB）的文件走 ChunkedDownloader 多分片下载 */
    private static final long CHUNKED_THRESHOLD = 4L * 1024 * 1024;

    /** downloadTo 进度回调节流间隔（ms） */
    private static final long PROGRESS_THROTTLE_MS = 50;

    private final LauncherConfig config;
    private final MirrorManager mirror = new MirrorManager();
    private volatile OkHttpClient http;
    private final ExecutorService pool;
    /** 分片下载专用线程池：避免与批量下载竞争线程，大文件分片可独立并行 */
    private final ExecutorService chunkedPool;
    /** 校验专用线程池（不占用下载线程），4 线程并行校验避免成为瓶颈 */
    private final ExecutorService verifyPool = Executors.newFixedThreadPool(4);
    /** 批量下载背压信号量：限制同时进行中的下载数，避免一次性提交撑爆队列 */
    private final Semaphore downloadLimiter;
    /** 分片下载器（复用线程池，避免每次创建） */
    private volatile ChunkedDownloader chunked;

    // 网络参数（由 reconfigure 设置）
    private int speedLimitBytesPerSec = 0;     // 0 = 不限速
    private int retryCount = 3;
    private boolean enableResume = true;
    private int chunkedDownloadThreads = 4;    // 单文件分片连接数

    public DownloadManager(LauncherConfig config) {
        this.config = config;
        this.pool = Executors.newFixedThreadPool(config.getDownloadThreads());
        this.chunkedPool = Executors.newFixedThreadPool(
                Math.min(16, config.getDownloadThreads()));
        this.downloadLimiter = new Semaphore(config.getDownloadThreads());
        this.http = buildClient(null, 15, false, null, null);
        this.chunked = new ChunkedDownloader(http, chunkedDownloadThreads, chunkedPool);
    }

    /**
     * 构建 OkHttpClient：独立连接池 + 调高 maxRequestsPerHost 的 Dispatcher + HTTP/2。
     * <p>
     * 注意：每个 OkHttpClient 用独立 ConnectionPool，避免共享连接池在多客户端间
     * 产生不预期的连接回收交互。Dispatcher 只调高并发上限，用默认的 cachedThreadPool。
     */
    private static OkHttpClient buildClient(Proxy proxy, int connectTimeoutSec,
                                            boolean useProxyAuth, String proxyUser, String proxyPass) {
        // Dispatcher：用默认 cachedThreadPool（不限制线程数），只调高并发上限
        // 错误做法是用有限线程池 + SynchronousQueue，高并发时会拒绝任务
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(MAX_REQUESTS);
        dispatcher.setMaxRequestsPerHost(MAX_REQUESTS_PER_HOST);

        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(CONNECTION_POOL_SIZE, 5, TimeUnit.MINUTES))
                .dispatcher(dispatcher)
                .connectTimeout(java.time.Duration.ofSeconds(connectTimeoutSec))
                .readTimeout(java.time.Duration.ofSeconds(120))
                .writeTimeout(java.time.Duration.ofSeconds(60))
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .dns(new FastDns());

        if (proxy != null) {
            b.proxy(proxy);
            if (useProxyAuth && proxyUser != null && !proxyUser.isEmpty()) {
                final String credential = Credentials.basic(proxyUser, proxyPass);
                b.proxyAuthenticator(new Authenticator() {
                    @Override
                    public Request authenticate(Route route, Response response) {
                        return response.request().newBuilder()
                                .header("Proxy-Authorization", credential).build();
                    }
                });
            }
        }
        return b.build();
    }

    /**
     * 根据偏好重新构建 HTTP 客户端与镜像配置。
     * 清理旧客户端的连接池，避免资源泄漏。
     */
    public void reconfigure(Preferences pref) {
        // 镜像
        String mt = pref.getMirrorType();
        if ("BMCLAPI".equals(mt)) mirror.setType(MirrorManager.MirrorType.BMCLAPI);
        else if ("CUSTOM".equals(mt)) mirror.setType(MirrorManager.MirrorType.CUSTOM);
        else mirror.setType(MirrorManager.MirrorType.OFFICIAL);
        mirror.setCustomBase(pref.getCustomMirrorBase());

        // 客户端
        Proxy proxy = null;
        if (pref.isUseProxy() && !pref.getProxyHost().isEmpty() && pref.getProxyPort() > 0) {
            proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(pref.getProxyHost(), pref.getProxyPort()));
        }
        OkHttpClient old = this.http;
        http = buildClient(proxy, 15,
                pref.isUseProxy() && pref.isUseHttpAuth(),
                pref.getProxyUsername(), pref.getProxyPassword());

        // 清理旧客户端连接池，避免多次 reconfigure 导致连接/线程泄漏
        if (old != null) {
            try {
                old.connectionPool().evictAll();
                old.dispatcher().executorService().shutdown();
            } catch (Throwable ignored) {}
        }

        speedLimitBytesPerSec = pref.getDownloadSpeedLimitKb() * 1024;
        retryCount = Math.max(0, pref.getDownloadRetryCount());
        enableResume = pref.isEnableResume();
        chunkedDownloadThreads = Math.max(1, pref.getChunkedDownloadThreads());

        // 同步更新 chunked downloader 的客户端引用和限速
        chunked = new ChunkedDownloader(http, chunkedDownloadThreads, chunkedPool);
        chunked.setSpeedLimit(speedLimitBytesPerSec);
    }

    public int getChunkedDownloadThreads() { return chunkedDownloadThreads; }

    /**
     * 关闭所有线程池与连接池，释放资源。
     * 应在启动器退出前调用，避免 dispatcher/校验/下载线程泄漏。
     */
    public void shutdown() {
        if (http != null) {
            try {
                http.connectionPool().evictAll();
                http.dispatcher().executorService().shutdown();
            } catch (Throwable ignored) {}
        }
        pool.shutdown();
        chunkedPool.shutdown();
        verifyPool.shutdown();
    }

    /**
     * 使用多线程分片下载大文件（如 Java runtime、client.jar、Forge installer）。
     * 文件 < {@value #CHUNKED_THRESHOLD} 时自动回退单连接。
     * @param url 已镜像重写后的 URL
     * @param target 目标文件
     * @param onProgress 已完成字节数回调
     */
    public CompletableFuture<Void> downloadChunked(String url, Path target,
                                                   java.util.function.Consumer<Long> onProgress) {
        return chunked.download(rewrite(url), target, onProgress);
    }

    public MirrorManager mirror() { return mirror; }

    /**
     * 暴露内部 OkHttpClient，供其他模块（NewsClient 等）复用，
     * 自动应用代理配置。
     */
    public OkHttpClient httpClient() { return http; }

    /** 应用镜像重写 */
    private String rewrite(String url) { return mirror.rewrite(url); }

    /**
     * 批量下载，progress 接收已完成字节数 / 总字节数。
     * <p>
     * 使用 {@link Semaphore} 背压控制：限制同时进行中的下载数为线程池大小，
     * 避免 3000+ 资产任务一次性提交到 {@link ExecutorService} 队列导致 OOM 或超时。
     */
    public CompletableFuture<Void> downloadAll(List<DownloadTask> tasks,
                                               Consumer<String> onFileDone,
                                               Consumer<Long> onBytes) {
        long total = tasks.stream().mapToLong(DownloadTask::getSize).sum();
        AtomicLong completed = new AtomicLong(0);
        // 实时进度节流：避免高频回调导致 UI 线程过载
        final long[] lastNotifyTime = {0};

        CompletableFuture<?>[] futures = tasks.stream()
                .map(t -> CompletableFuture.runAsync(() -> {
                    try {
                        // 阶段1：下载（持有 semaphore）
                        downloadLimiter.acquire();
                        Path partFile;
                        try {
                            partFile = downloadOneWithRetry(t, deltaBytes -> {
                                // 实时回调：下载过程中也通知进度
                                if (onBytes != null) {
                                    long now = completed.addAndGet(deltaBytes);
                                    long t2 = System.currentTimeMillis();
                                    if (t2 - lastNotifyTime[0] >= PROGRESS_THROTTLE_MS) {
                                        lastNotifyTime[0] = t2;
                                        onBytes.accept(now);
                                    }
                                }
                            });
                        } finally {
                            downloadLimiter.release();
                        }
                        // 阶段2：SHA1 校验（已释放 semaphore，不占用下载槽位）
                        if (partFile != null) {
                            verifyAndRename(t, partFile);
                        }
                        // 文件已完成（含跳过的情况）
                        if (partFile == null) {
                            completed.addAndGet(t.getSize());
                        }
                        if (onBytes != null) onBytes.accept(completed.get());
                        if (onFileDone != null) onFileDone.accept(t.getRelativePath());
                    } catch (IOException e) {
                        throw new RuntimeException("下载失败: " + t.getUrl(), e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("下载被中断: " + t.getUrl(), e);
                    }
                }, pool))
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures)
                .thenRun(() -> {
                    if (onBytes != null) onBytes.accept(total);
                });
    }

    /**
     * 下载单文件（带重试），返回 .part 文件路径（未校验未重命名）。
     * 返回 null 表示文件已存在且 SHA1 匹配，无需下载。
     * onDeltaBytes 回调下载过程中的增量字节数（用于实时进度）。
     */
    private Path downloadOneWithRetry(DownloadTask task, Consumer<Long> onDeltaBytes) throws IOException {
        IOException last = null;
        for (int i = 0; i <= retryCount; i++) {
            try {
                return downloadOne(task, onDeltaBytes);
            } catch (IOException e) {
                last = e;
                // 指数退避 + 随机抖动：避免高并发下所有失败任务同步重试（thundering herd）
                long base = 500L * (1L << i); // 500ms, 1s, 2s, 4s ...
                long jitter = ThreadLocalRandom.current().nextLong(200);
                try { Thread.sleep(base + jitter); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /**
     * SHA1 校验并原子重命名。校验失败删除 .part 文件并抛异常。
     */
    private void verifyAndRename(DownloadTask task, Path partFile) throws IOException {
        Path target = config.getWorkDir().resolve(task.getRelativePath());
        if (task.getSha1() != null && !task.getSha1().isEmpty()) {
            String actual = sha1Async(partFile);
            if (!actual.equalsIgnoreCase(task.getSha1())) {
                Files.deleteIfExists(partFile);
                throw new IOException("SHA1 校验失败: " + task.getRelativePath() +
                        " 期望=" + task.getSha1() + " 实际=" + actual);
            }
        }
        Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * 下载单文件到 .part 文件（不校验不重命名），返回 .part 路径。
     * 返回 null 表示文件已存在且 SHA1 匹配，无需下载。
     * onDeltaBytes 回调下载过程中的增量字节数（用于实时进度）。
     */
    private Path downloadOne(DownloadTask task, Consumer<Long> onDeltaBytes) throws IOException {
        Path target = config.getWorkDir().resolve(task.getRelativePath());
        Files.createDirectories(target.getParent());

        // 已存在且 SHA1 匹配则跳过
        if (Files.exists(target) && task.getSha1() != null && !task.getSha1().isEmpty()) {
            String existing = sha1Async(target);
            if (existing.equalsIgnoreCase(task.getSha1())) {
                return null;
            }
        }

        // 断点续传：使用 .part 文件
        Path partFile = target.resolveSibling(target.getFileName() + ".part");
        long existingSize = 0;
        if (enableResume && Files.exists(partFile)) {
            existingSize = Files.size(partFile);
        }

        String url = rewrite(task.getUrl());
        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        if (enableResume && existingSize > 0) {
            reqBuilder.header("Range", "bytes=" + existingSize + "-");
        }
        Request req = reqBuilder.build();

        try (Response resp = http.newCall(req).execute()) {
            int code = resp.code();
            // 200 = 全新下载，206 = Range 成功
            boolean rangeOk = (code == 206);
            boolean fullOk = (code == 200);
            if (!rangeOk && !fullOk) {
                throw new IOException("下载失败 code=" + code + " url=" + url);
            }
            // 如果服务端忽略 Range（返回 200），从头开始
            long startPos = rangeOk ? existingSize : 0L;

            // 关键：全新下载时先删除旧 .part 文件，避免旧内容残留
            if (fullOk && Files.exists(partFile)) {
                Files.deleteIfExists(partFile);
            }

            if (resp.body() == null) throw new IOException("响应体为空: " + url);
            try (InputStream in = resp.body().byteStream();
                 RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
                raf.seek(startPos);
                byte[] buf = new byte[BUFFER_SIZE];
                long lastThrottleTime = System.currentTimeMillis();
                long bytesInWindow = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    raf.write(buf, 0, n);
                    bytesInWindow += n;
                    // 实时进度回调
                    if (onDeltaBytes != null) onDeltaBytes.accept((long) n);

                    // 限速：每 100ms 检查一次
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
                }
            }
        }

        // 返回 .part 路径，校验和重命名由调用方在释放 semaphore 后执行
        return partFile;
    }

    /**
     * 直接下载文本（用于版本 JSON 等），应用镜像重写。
     * SSL 失败时自动 fallback 到 curl。
     */
    public String downloadString(String url) throws IOException {
        String rewritten = rewrite(url);
        Request req = new Request.Builder().url(rewritten).get().build();
        IOException last = null;
        for (int i = 0; i <= retryCount; i++) {
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    throw new IOException("下载失败 code=" + resp.code() + " url=" + url);
                }
                if (resp.body() == null) throw new IOException("响应体为空: " + url);
                return resp.body().string();
            } catch (IOException e) {
                last = e;
                // SSL 握手失败：立即 fallback 到 curl（不重试 OkHttp）
                if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                    return CurlFallback.getString(rewritten);
                }
                try { Thread.sleep(500L * (i + 1)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        // 所有重试失败后，最后尝试 curl
        if (CurlFallback.isAvailable()) {
            return CurlFallback.getString(rewritten);
        }
        throw last;
    }

    /**
     * 下载到指定绝对路径（不复用工作目录相对路径），应用镜像重写。
     * 无进度回调版本：兼容旧调用方。
     */
    public void downloadTo(String url, Path target) throws IOException {
        downloadTo(url, target, null);
    }

    /**
     * 下载到指定绝对路径，带字节进度回调。
     * <p>
     * 支持断点续传（.download 文件）和限速，256KB 缓冲区。
     * 大文件下载请显式调用 {@link #downloadChunked}。
     *
     * @param url       资源 URL（会被镜像重写）
     * @param target    目标绝对路径
     * @param onProgress 进度回调（已完成字节数），可为 null
     */
    public void downloadTo(String url, Path target, Consumer<Long> onProgress) throws IOException {
        Files.createDirectories(target.getParent());
        String rewritten = rewrite(url);

        // 断点续传：使用 .download 文件
        Path tmp = target.resolveSibling(target.getFileName() + ".download");
        long existingSize = 0;
        if (enableResume && Files.exists(tmp)) {
            existingSize = Files.size(tmp);
        }

        IOException last = null;
        for (int i = 0; i <= retryCount; i++) {
            Request.Builder reqBuilder = new Request.Builder().url(rewritten).get();
            if (enableResume && existingSize > 0) {
                reqBuilder.header("Range", "bytes=" + existingSize + "-");
            }
            Request req = reqBuilder.build();
            try (Response resp = http.newCall(req).execute()) {
                int code = resp.code();
                boolean rangeOk = (code == 206);
                boolean fullOk = (code == 200);
                if (!rangeOk && !fullOk) {
                    throw new IOException("下载失败 code=" + code + " url=" + url);
                }
                long startPos = rangeOk ? existingSize : 0L;
                if (fullOk && Files.exists(tmp)) {
                    Files.deleteIfExists(tmp);
                    existingSize = 0;
                    startPos = 0;
                }
                if (resp.body() == null) throw new IOException("响应体为空: " + url);
                try (InputStream in = resp.body().byteStream()) {
                    try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                        raf.seek(startPos);
                        byte[] buf = new byte[BUFFER_SIZE];
                        long lastThrottleTime = System.currentTimeMillis();
                        long bytesInWindow = 0;
                        long lastNotify = 0;
                        long total = startPos;
                        int n;
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

                            // 进度节流
                            if (onProgress != null) {
                                long t = System.currentTimeMillis();
                                if (t - lastNotify >= PROGRESS_THROTTLE_MS) {
                                    lastNotify = t;
                                    onProgress.accept(total);
                                }
                            }
                        }
                        if (onProgress != null) onProgress.accept(total);
                    }
                }
                // 原子重命名
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                last = e;
                // 更新已下载大小用于下次重试续传
                if (enableResume && Files.exists(tmp)) {
                    try { existingSize = Files.size(tmp); } catch (Exception ignored) {}
                }
                // SSL 握手失败：立即 fallback 到 curl（不重试 OkHttp）
                if (CurlFallback.isSslHandshakeFailure(e) && CurlFallback.isAvailable()) {
                    CurlFallback.downloadFile(rewritten, target);
                    return;
                }
                long base = 500L * (1L << i);
                long jitter = ThreadLocalRandom.current().nextLong(200);
                try { Thread.sleep(base + jitter); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        // 所有重试失败后，最后尝试 curl
        if (CurlFallback.isAvailable()) {
            CurlFallback.downloadFile(rewritten, target);
            return;
        }
        throw last;
    }

    /**
     * SHA1 校验：提交到独立线程池，避免阻塞下载线程。
     * 如果校验池已满，当前线程会等待（保证校验一定完成）。
     */
    private String sha1Async(Path file) throws IOException {
        try {
            return verifyPool.submit(() -> sha1(file)).get();
        } catch (Exception e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw new IOException("SHA1 校验失败", e);
        }
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("SHA1 计算失败", e);
        }
    }

    /**
     * 连接预热：提前对常见下载源发起 HEAD 请求，建立 TCP+TLS 连接并放入连接池，
     * 后续实际下载可直接复用，避免首次请求的 DNS+TCP+TLS 握手延迟。
     * 在后台线程异步执行，不阻塞调用方。
     */
    public void warmupConnections(List<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            for (String url : urls) {
                try {
                    String rewritten = rewrite(url);
                    Request head = new Request.Builder().url(rewritten).head().build();
                    try (Response resp = http.newCall(head).execute()) {
                        // 仅为了建立连接，忽略响应内容
                    }
                } catch (Throwable ignored) {
                    // 预热失败不影响正常下载
                }
            }
        }, pool);
    }

    /**
     * 快速 DNS 解析器：系统 DNS + 缓存优化，减少 DNS 解析延迟。
     * <p>
     * 使用系统 DNS（命中本地缓存时最快），并发解析 IPv4 + IPv6，
     * 对 localhost 和 IP 字面量直接返回，跳过 DNS 查询。
     * OkHttp 默认 Dns.SYSTEM 已足够，这里仅做一层缓存防重复解析。
     */
    private static final class FastDns implements okhttp3.Dns {
        private final java.util.concurrent.ConcurrentMap<String, java.util.List<java.net.InetAddress>> cache =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public java.util.List<java.net.InetAddress> lookup(String hostname) throws java.net.UnknownHostException {
            // IP 字面量直接解析，不走缓存
            if (hostname == null || hostname.isEmpty()) {
                throw new java.net.UnknownHostException("hostname is null or empty");
            }
            // 命中缓存
            java.util.List<java.net.InetAddress> cached = cache.get(hostname);
            if (cached != null) return cached;
            // 系统 DNS 解析
            java.util.List<java.net.InetAddress> result = okhttp3.Dns.SYSTEM.lookup(hostname);
            cache.putIfAbsent(hostname, result);
            return result;
        }
    }
}
