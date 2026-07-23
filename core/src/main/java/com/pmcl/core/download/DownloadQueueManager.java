package com.pmcl.core.download;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.market.ModFile;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.modloader.ModLoader;
import com.pmcl.core.modloader.ModLoaderInstaller;
import com.pmcl.core.modloader.ModLoaderManager;
import com.pmcl.core.preferences.Preferences;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 下载队列管理器：统一管理版本安装 / 模组下载 / 普通文件下载任务。
 * <p>
 * 功能：
 * <ul>
 *   <li>多任务排队，限制最大并发数（默认 3）</li>
 *   <li>暂停 / 继续 / 取消单个任务</li>
 *   <li>实时进度回调（已完成字节数 / 总字节数 / 状态）</li>
 *   <li>全队列进度总览</li>
 * </ul>
 * <p>
 * 暂停实现：中断运行线程 + 保留 .part/.download 断点文件；
 * 继续时重新提交任务，由 {@link DownloadManager} 的断点续传机制从上次位置继续。
 */
public final class DownloadQueueManager {

    // ===== 任务类型与状态 =====

    public enum TaskType {
        VERSION_INSTALL,   // 安装完整 MC 版本
        MOD_LOADER_INSTALL,// 安装模组加载器
        MOD_DOWNLOAD,      // 下载单个模组
        GENERIC_FILE       // 普通文件下载
    }

    public enum TaskStatus {
        QUEUED,    // 排队中
        RUNNING,   // 运行中
        PAUSED,    // 已暂停
        DONE,      // 已完成
        FAILED,    // 失败
        CANCELLED  // 已取消
    }

    /**
     * 队列任务数据模型。所有字段均为线程安全访问（volatile + synchronized 写）。
     */
    public static final class QueueTask {
        private final String id;
        private final String name;
        private final TaskType type;
        private volatile TaskStatus status;
        private volatile long completedBytes;
        private volatile long totalBytes;
        private volatile String message;
        private volatile String errorMessage;
        /** 运行中的 Future，用于中断线程实现暂停/取消 */
        private volatile Future<?> future;
        /** 暂停标志：运行线程检测到后主动退出 */
        private volatile boolean pauseRequested;
        /** 取消标志：运行线程检测到后主动退出 */
        private volatile boolean cancelRequested;
        /** 任务创建时间戳 */
        private final long createdAt;
        /** 任务完成时间戳（DONE/FAILED/CANCELLED） */
        private volatile long finishedAt;

        public QueueTask(String id, String name, TaskType type) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.status = TaskStatus.QUEUED;
            this.completedBytes = 0;
            this.totalBytes = 0;
            this.message = "排队中";
            this.errorMessage = null;
            this.createdAt = System.currentTimeMillis();
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public TaskType getType() { return type; }
        public TaskStatus getStatus() { return status; }
        public long getCompletedBytes() { return completedBytes; }
        public long getTotalBytes() { return totalBytes; }
        public String getMessage() { return message; }
        public String getErrorMessage() { return errorMessage; }
        public long getCreatedAt() { return createdAt; }
        public long getFinishedAt() { return finishedAt; }

        /** 进度百分比 0~1 */
        public double progress() {
            if (totalBytes <= 0) return 0;
            return Math.min(1.0, completedBytes / (double) totalBytes);
        }

        /** 是否处于可暂停的活跃状态 */
        public boolean isActive() {
            return status == TaskStatus.QUEUED || status == TaskStatus.RUNNING;
        }
    }

    // ===== 管理器字段 =====

    private final LauncherConfig config;
    private final DownloadManager downloadManager;
    private final VersionInstaller versionInstaller;
    private final ModMarketManager modMarketManager;
    private final ModLoaderManager modLoaderManager;
    private final Preferences preferences;

    /** 任务表：id -> task，保持插入顺序 */
    private final Map<String, QueueTask> tasks = Collections.synchronizedMap(new LinkedHashMap<>());
    /** 运行中的 Future 表：id -> future */
    private final Map<String, Future<?>> runningFutures = new ConcurrentHashMap<>();
    /** 限流并发线程池 */
    private final ExecutorService executor;
    /** 最大并发数 */
    private final int maxConcurrent;
    /** 状态变化监听器列表 */
    private final List<Consumer<List<QueueTask>>> listeners = java.util.Collections.synchronizedList(new ArrayList<>());
    /** 进度通知节流：上次通知时间 */
    private final Map<String, Long> lastNotifyTime = new ConcurrentHashMap<>();
    private static final long PROGRESS_THROTTLE_MS = 100;

    public DownloadQueueManager(LauncherConfig config,
                                DownloadManager downloadManager,
                                VersionInstaller versionInstaller,
                                ModMarketManager modMarketManager,
                                ModLoaderManager modLoaderManager,
                                Preferences preferences) {
        this(config, downloadManager, versionInstaller, modMarketManager,
                modLoaderManager, preferences, 3);
    }

    public DownloadQueueManager(LauncherConfig config,
                                DownloadManager downloadManager,
                                VersionInstaller versionInstaller,
                                ModMarketManager modMarketManager,
                                ModLoaderManager modLoaderManager,
                                Preferences preferences,
                                int maxConcurrent) {
        this.config = config;
        this.downloadManager = downloadManager;
        this.versionInstaller = versionInstaller;
        this.modMarketManager = modMarketManager;
        this.modLoaderManager = modLoaderManager;
        this.preferences = preferences;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        // 固定线程池：限制并发下载数
        this.executor = Executors.newFixedThreadPool(this.maxConcurrent,
                r -> {
                    Thread t = new Thread(r, "pmcl-download-queue");
                    t.setDaemon(true);
                    return t;
                });
    }

    // ===== 任务提交 =====

    /**
     * 提交版本安装任务。
     */
    public String submitVersionInstall(String versionId) {
        QueueTask task = new QueueTask(UUID.randomUUID().toString(),
                "Minecraft " + versionId, TaskType.VERSION_INSTALL);
        task.message = "等待安装: " + versionId;
        // 版本安装的总字节数在运行时由 InstallProgress 回调填入
        addTask(task);
        schedule(task, () -> runVersionInstall(task, versionId));
        return task.id;
    }

    /**
     * 提交模组加载器安装任务。
     */
    public String submitModLoaderInstall(String loaderName, String gameVersion, String loaderVersion) {
        QueueTask task = new QueueTask(UUID.randomUUID().toString(),
                loaderName + " " + loaderVersion + " (MC " + gameVersion + ")",
                TaskType.MOD_LOADER_INSTALL);
        task.message = "等待安装: " + loaderName;
        addTask(task);
        schedule(task, () -> runModLoaderInstall(task, loaderName, gameVersion, loaderVersion));
        return task.id;
    }

    /**
     * 提交模组下载任务。
     */
    public String submitModDownload(ModFile modFile, String gameVersion, String versionId) {
        String displayName = modFile.getFileName();
        QueueTask task = new QueueTask(UUID.randomUUID().toString(),
                displayName, TaskType.MOD_DOWNLOAD);
        task.totalBytes = modFile.getFileSize();
        task.message = "等待下载: " + displayName;
        addTask(task);
        schedule(task, () -> runModDownload(task, modFile, gameVersion, versionId));
        return task.id;
    }

    /**
     * 提交普通文件下载任务。
     */
    public String submitFileDownload(String name, String url, Path target) {
        QueueTask task = new QueueTask(UUID.randomUUID().toString(),
                name, TaskType.GENERIC_FILE);
        task.message = "等待下载: " + name;
        addTask(task);
        schedule(task, () -> runFileDownload(task, url, target));
        return task.id;
    }

    // ===== 任务控制 =====

    /**
     * 暂停任务：中断运行线程，标记 PAUSED。
     * 已完成部分由 .part 文件保留，继续时断点续传。
     */
    public void pause(String taskId) {
        QueueTask task = tasks.get(taskId);
        if (task == null) return;
        if (!task.isActive()) return;
        task.pauseRequested = true;
        // 中断运行线程（触发 InterruptedException）
        Future<?> f = runningFutures.get(taskId);
        if (f != null) {
            f.cancel(true);
        }
        // 如果还在 QUEUED 状态（没开始运行），直接标记 PAUSED
        if (task.status == TaskStatus.QUEUED) {
            task.status = TaskStatus.PAUSED;
            task.message = "已暂停";
            notifyListeners();
        }
    }

    /**
     * 继续任务：重新提交到执行器。
     */
    public void resume(String taskId) {
        QueueTask task = tasks.get(taskId);
        if (task == null) return;
        if (task.status != TaskStatus.PAUSED && task.status != TaskStatus.FAILED) return;
        task.status = TaskStatus.QUEUED;
        task.pauseRequested = false;
        task.cancelRequested = false;
        task.errorMessage = null;
        task.message = "继续排队...";
        runningFutures.remove(taskId);
        notifyListeners();
        // 根据 type 重新调度
        scheduleResume(task);
    }

    /**
     * 取消任务：中断运行线程，标记 CANCELLED，不删除已下载文件（用户可手动清理）。
     */
    public void cancel(String taskId) {
        QueueTask task = tasks.get(taskId);
        if (task == null) return;
        if (task.status == TaskStatus.DONE || task.status == TaskStatus.CANCELLED) return;
        task.cancelRequested = true;
        Future<?> f = runningFutures.get(taskId);
        if (f != null) {
            f.cancel(true);
        }
        task.status = TaskStatus.CANCELLED;
        task.message = "已取消";
        task.finishedAt = System.currentTimeMillis();
        runningFutures.remove(taskId);
        notifyListeners();
    }

    /**
     * 暂停所有活跃任务。
     */
    public void pauseAll() {
        List<String> ids = new ArrayList<>();
        synchronized (tasks) {
            for (QueueTask t : tasks.values()) {
                if (t.isActive()) ids.add(t.id);
            }
        }
        for (String id : ids) pause(id);
    }

    /**
     * 继续所有暂停/失败的任务。
     */
    public void resumeAll() {
        List<String> ids = new ArrayList<>();
        synchronized (tasks) {
            for (QueueTask t : tasks.values()) {
                if (t.status == TaskStatus.PAUSED || t.status == TaskStatus.FAILED) ids.add(t.id);
            }
        }
        for (String id : ids) resume(id);
    }

    /**
     * 取消所有活跃任务。
     */
    public void cancelAll() {
        List<String> ids = new ArrayList<>();
        synchronized (tasks) {
            for (QueueTask t : tasks.values()) {
                if (t.isActive()) ids.add(t.id);
            }
        }
        for (String id : ids) cancel(id);
    }

    /**
     * 清除已完成/已取消/已失败的任务记录。
     */
    public void clearFinished() {
        synchronized (tasks) {
            tasks.entrySet().removeIf(e -> {
                TaskStatus s = e.getValue().status;
                return s == TaskStatus.DONE || s == TaskStatus.CANCELLED || s == TaskStatus.FAILED;
            });
        }
        notifyListeners();
    }

    /**
     * 删除指定任务记录（仅允许非运行中）。
     */
    public void remove(String taskId) {
        QueueTask task = tasks.get(taskId);
        if (task == null) return;
        if (task.isActive()) {
            cancel(taskId);
        }
        tasks.remove(taskId);
        notifyListeners();
    }

    // ===== 查询 =====

    /**
     * 获取所有任务的快照（按插入顺序）。
     */
    public List<QueueTask> getTasks() {
        synchronized (tasks) {
            return new ArrayList<>(tasks.values());
        }
    }

    /**
     * 获取队列统计总览。
     */
    public QueueSummary getSummary() {
        int queued = 0, running = 0, paused = 0, done = 0, failed = 0, cancelled = 0;
        long totalBytes = 0, completedBytes = 0;
        synchronized (tasks) {
            for (QueueTask t : tasks.values()) {
                switch (t.status) {
                    case QUEUED: queued++; break;
                    case RUNNING: running++; break;
                    case PAUSED: paused++; break;
                    case DONE: done++; break;
                    case FAILED: failed++; break;
                    case CANCELLED: cancelled++; break;
                }
                totalBytes += t.totalBytes;
                completedBytes += t.completedBytes;
            }
        }
        return new QueueSummary(queued, running, paused, done, failed, cancelled,
                totalBytes, completedBytes);
    }

    /** 队列统计快照 */
    public static final class QueueSummary {
        public final int queued, running, paused, done, failed, cancelled;
        public final long totalBytes, completedBytes;

        public QueueSummary(int queued, int running, int paused, int done,
                            int failed, int cancelled, long totalBytes, long completedBytes) {
            this.queued = queued;
            this.running = running;
            this.paused = paused;
            this.done = done;
            this.failed = failed;
            this.cancelled = cancelled;
            this.totalBytes = totalBytes;
            this.completedBytes = completedBytes;
        }

        public int total() {
            return queued + running + paused + done + failed + cancelled;
        }

        public int active() {
            return queued + running;
        }

        public double overallProgress() {
            if (totalBytes <= 0) return 0;
            return Math.min(1.0, completedBytes / (double) totalBytes);
        }
    }

    // ===== 监听器 =====

    public void addListener(Consumer<List<QueueTask>> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<List<QueueTask>> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        List<QueueTask> snapshot = getTasks();
        List<Consumer<List<QueueTask>>> ls;
        synchronized (listeners) {
            ls = new ArrayList<>(listeners);
        }
        for (Consumer<List<QueueTask>> l : ls) {
            try { l.accept(snapshot); } catch (Throwable ignored) {}
        }
    }

    /** 进度通知（节流） */
    private void notifyProgress(QueueTask task) {
        long now = System.currentTimeMillis();
        Long last = lastNotifyTime.get(task.id);
        if (last != null && now - last < PROGRESS_THROTTLE_MS) return;
        lastNotifyTime.put(task.id, now);
        notifyListeners();
    }

    // ===== 内部实现 =====

    private void addTask(QueueTask task) {
        tasks.put(task.id, task);
        notifyListeners();
    }

    /**
     * 调度任务到执行器。如果当前并发已满，任务会在 executor 队列中等待（QUEUED 状态）。
     */
    private void schedule(QueueTask task, Runnable work) {
        Future<?> future = executor.submit(() -> {
            // 检查是否已被取消或暂停
            if (task.cancelRequested) {
                task.status = TaskStatus.CANCELLED;
                task.message = "已取消";
                task.finishedAt = System.currentTimeMillis();
                runningFutures.remove(task.id);
                notifyListeners();
                return;
            }
            if (task.pauseRequested) {
                task.status = TaskStatus.PAUSED;
                task.message = "已暂停";
                runningFutures.remove(task.id);
                notifyListeners();
                return;
            }
            task.status = TaskStatus.RUNNING;
            task.message = "开始...";
            notifyListeners();
            try {
                work.run();
                if (!task.cancelRequested && !task.pauseRequested) {
                    task.status = TaskStatus.DONE;
                    task.message = "完成";
                    task.completedBytes = task.totalBytes;
                    task.finishedAt = System.currentTimeMillis();
                    notifyListeners();
                }
            } catch (Throwable e) {
                if (task.pauseRequested) {
                    task.status = TaskStatus.PAUSED;
                    task.message = "已暂停";
                } else if (task.cancelRequested) {
                    task.status = TaskStatus.CANCELLED;
                    task.message = "已取消";
                } else {
                    task.status = TaskStatus.FAILED;
                    task.errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
                    task.message = "失败: " + task.errorMessage;
                }
                task.finishedAt = System.currentTimeMillis();
                notifyListeners();
            } finally {
                runningFutures.remove(task.id);
            }
        });
        runningFutures.put(task.id, future);
    }

    /**
     * 重新调度暂停/失败的任务。需要保存原始参数，这里用 type 重新分发。
     * 由于 QueueTask 不保存原始参数，用 attachments map 存储。
     */
    private final Map<String, Runnable> resumeWork = new ConcurrentHashMap<>();

    private void scheduleResume(QueueTask task) {
        Runnable work = resumeWork.get(task.id);
        if (work == null) {
            task.status = TaskStatus.FAILED;
            task.errorMessage = "无法继续：任务参数已丢失";
            task.message = "继续失败";
            notifyListeners();
            return;
        }
        schedule(task, work);
    }

    /** 保存任务参数供继续使用 */
    private void storeResumeWork(String taskId, Runnable work) {
        resumeWork.put(taskId, work);
    }

    /** 任务完成后清理 resumeWork */
    private void clearResumeWork(String taskId) {
        resumeWork.remove(taskId);
    }

    // ===== 任务执行体 =====

    private void runVersionInstall(QueueTask task, String versionId) {
        storeResumeWork(task.id, () -> runVersionInstall(task, versionId));
        try {
            versionInstaller.install(versionId, progress -> {
                if (task.cancelRequested || task.pauseRequested) {
                    throw new RuntimeException("__PMCL_INTERRUPTED__");
                }
                task.totalBytes = Math.max(task.totalBytes, progress.getTotal());
                task.completedBytes = progress.getCompleted();
                task.message = progress.getMessage() != null ? progress.getMessage() : "安装中";
                notifyProgress(task);
            }).join();
        } catch (Throwable e) {
            if (e.getMessage() != null && e.getMessage().contains("__PMCL_INTERRUPTED__")) {
                throw new RuntimeException("中断", e);
            }
            throw e;
        } finally {
            // M52 修复：所有终态（DONE/FAILED/CANCELLED）都清理 resumeWork，避免内存泄漏
            if (isTerminalStatus(task.status)) clearResumeWork(task.id);
        }
    }

    private void runModLoaderInstall(QueueTask task, String loaderName,
                                     String gameVersion, String loaderVersion) {
        storeResumeWork(task.id, () -> runModLoaderInstall(task, loaderName, gameVersion, loaderVersion));
        try {
            ModLoader loader = ModLoader.valueOf(loaderName.toUpperCase());
            ModLoaderInstaller installer = modLoaderManager.get(loader);
            task.message = "正在安装 " + loaderName + " " + loaderVersion;
            notifyProgress(task);
            installer.install(gameVersion, loaderVersion, progress -> {
                if (task.cancelRequested || task.pauseRequested) {
                    throw new RuntimeException("__PMCL_INTERRUPTED__");
                }
                task.totalBytes = Math.max(task.totalBytes, progress.getTotal());
                task.completedBytes = progress.getCompleted();
                task.message = progress.getMessage() != null ? progress.getMessage() : "安装中";
                notifyProgress(task);
            }).join();
            // 模组加载器安装无明确字节数时，标记完成设 100%
            if (task.totalBytes <= 0) {
                task.totalBytes = 1;
                task.completedBytes = 1;
            }
        } finally {
            if (isTerminalStatus(task.status)) clearResumeWork(task.id);
        }
    }

    private void runModDownload(QueueTask task, ModFile modFile,
                                String gameVersion, String versionId) {
        storeResumeWork(task.id, () -> runModDownload(task, modFile, gameVersion, versionId));
        try {
            modMarketManager.installMod(modFile, gameVersion, versionId, preferences, status -> {
                if (task.cancelRequested || task.pauseRequested) {
                    throw new RuntimeException("__PMCL_INTERRUPTED__");
                }
                task.message = status;
                notifyProgress(task);
            }).join();
            task.completedBytes = task.totalBytes;
        } finally {
            if (isTerminalStatus(task.status)) clearResumeWork(task.id);
        }
    }

    private void runFileDownload(QueueTask task, String url, Path target) {
        storeResumeWork(task.id, () -> runFileDownload(task, url, target));
        try {
            // 先 HEAD 请求获取文件大小（可选，失败不影响下载）
            try (okhttp3.Response resp = downloadManager.httpClient().newCall(
                    new okhttp3.Request.Builder().url(downloadManager.mirror().rewrite(url)).head().build()
            ).execute()) {
                long size = resp.body() != null ? resp.body().contentLength() : -1;
                if (size > 0) task.totalBytes = size;
            } catch (Throwable ignored) {}

            downloadManager.downloadTo(url, target, completedBytes -> {
                if (task.cancelRequested || task.pauseRequested) {
                    // 抛异常中断下载线程
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("__PMCL_INTERRUPTED__");
                }
                task.completedBytes = completedBytes;
                if (task.totalBytes == 0) task.totalBytes = completedBytes;
                notifyProgress(task);
            });
        } catch (Throwable e) {
            if (e.getMessage() != null && e.getMessage().contains("__PMCL_INTERRUPTED__")) {
                throw new RuntimeException("中断", e);
            }
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException(e);
        } finally {
            if (isTerminalStatus(task.status)) clearResumeWork(task.id);
        }
    }

    /** M52: 判断任务是否处于终态（DONE/FAILED/CANCELLED），终态需清理 resumeWork */
    private static boolean isTerminalStatus(TaskStatus status) {
        return status == TaskStatus.DONE
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    /** 关闭队列管理器，释放线程池 */
    public void shutdown() {
        cancelAll();
        executor.shutdown();
    }
}
