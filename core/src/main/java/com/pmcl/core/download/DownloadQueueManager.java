package com.pmcl.core.download;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.install.VersionStaging;
import com.pmcl.core.market.ModFile;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.modloader.ModLoader;
import com.pmcl.core.modloader.ModLoaderInstaller;
import com.pmcl.core.modloader.ModLoaderManager;
import com.pmcl.core.modpack.ModpackManager;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.util.Exceptions;

import java.nio.file.Files;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        GENERIC_FILE,      // 普通文件下载
        NATIVE_CLIENT,     // 社区客户端发行包或源码
        MARKET_CONTENT     // 市场光影、材质或整合包
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
        /** 运行代次：每次 schedule 递增，lambda 执行前校验是否为当前代次，防止 resume 后旧 lambda 双重执行 */
        private volatile long runGeneration;
        /** 任务创建时间戳 */
        private final long createdAt;
        /** 任务完成时间戳（DONE/FAILED/CANCELLED） */
        private volatile long finishedAt;
        /**
         * 取消时需丢弃的版本 staging id（原版安装目录名）。
         * 暂停保留 staging；取消则清理，避免孤儿 .staging。
         */
        private volatile String cleanupVersionId;
        /** 模组下载完成事件用：显示名 / 文件名 */
        private volatile String eventModName;
        private volatile String eventModVersion;
        /** 社区客户端 id，用来避免同一个客户端重复进队列。 */
        private volatile String nativeClientId;

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
    /** 整合包导入。在 LauncherCore 里队列建好后注入，避免和 ModpackManager 循环构造。 */
    private volatile ModpackManager modpackManager;
    /** 同一时间只导入一个整合包，避免两个安装同时写版本目录。 */
    private final Object modpackImportLock = new Object();

    /** Optional plugin event bus. */
    private volatile com.pmcl.core.plugin.PluginManager pluginManager;

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

    public void setPluginManager(com.pmcl.core.plugin.PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    public void setModpackManager(ModpackManager modpackManager) {
        this.modpackManager = modpackManager;
    }

    // ===== 任务提交 =====

    /**
     * 提交版本安装任务。
     */
    public String submitVersionInstall(String versionId) {
        QueueTask task = new QueueTask(UUID.randomUUID().toString(),
                "Minecraft " + versionId, TaskType.VERSION_INSTALL);
        task.message = "等待安装: " + versionId;
        task.cleanupVersionId = versionId;
        // 版本安装的总字节数在运行时由 InstallProgress 回调填入
        addTask(task);
        schedule(task, () -> runVersionInstall(task, versionId));
        return task.id;
    }

    /**
     * 提交「原版 → 可选加载器」串联安装任务（同一队列任务内顺序执行，避免并发抢跑）。
     *
     * @param loaderName    加载器名（如 FORGE），null 表示仅原版
     * @param loaderVersion 加载器版本，loaderName 非 null 时必填
     */
    public String submitVersionInstallThenLoader(String versionId,
                                                 String loaderName,
                                                 String loaderVersion) {
        final boolean withLoader = loaderName != null && !loaderName.isBlank()
                && loaderVersion != null && !loaderVersion.isBlank();
        final String loaderNameF = loaderName;
        final String loaderVersionF = loaderVersion;
        String name = withLoader
                ? "Minecraft " + versionId + " + " + loaderName + " " + loaderVersion
                : "Minecraft " + versionId;
        QueueTask task = new QueueTask(UUID.randomUUID().toString(), name, TaskType.VERSION_INSTALL);
        task.message = "等待安装: " + versionId;
        task.cleanupVersionId = versionId;
        addTask(task);
        // 自引用 Runnable：暂停后续传时仍能跑完「原版 + 加载器」整条链
        final Runnable[] combined = new Runnable[1];
        combined[0] = () -> {
            storeResumeWork(task.id, combined[0]);
            try {
                versionInstaller.install(versionId, progress -> {
                    throwIfTaskInterrupted(task);
                    applyInstallProgress(task, progress.getCompleted(), progress.getTotal(), progress.getMessage());
                }).join();
                if (withLoader) {
                    throwIfTaskInterrupted(task);
                    ModLoader loader = ModLoader.valueOf(loaderNameF.toUpperCase());
                    ModLoaderInstaller installer = modLoaderManager.get(loader);
                    task.message = "原版完成，安装 " + loaderNameF + "...";
                    notifyProgress(task);
                    installer.install(versionId, loaderVersionF, progress -> {
                        throwIfTaskInterrupted(task);
                        task.totalBytes = Math.max(task.totalBytes, progress.getTotal());
                        task.completedBytes = progress.getCompleted();
                        task.message = progress.getMessage() != null ? progress.getMessage() : "安装中";
                        notifyProgress(task);
                    }).join();
                    if (task.totalBytes <= 0) {
                        task.totalBytes = 1;
                        task.completedBytes = 1;
                    }
                }
            } catch (Throwable e) {
                rethrowQueueFailure(e);
            } finally {
                if (!(task.pauseRequested || task.status == TaskStatus.PAUSED)) {
                    clearResumeWork(task.id);
                }
            }
        };
        schedule(task, combined[0]);
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
        return submitModDownload(modFile, gameVersion, versionId, null);
    }

    public String submitModDownload(ModFile modFile, String gameVersion, String versionId, String instanceId) {
        String displayName = modFile.getFileName();
        QueueTask task = new QueueTask(UUID.randomUUID().toString(),
                displayName, TaskType.MOD_DOWNLOAD);
        task.totalBytes = modFile.getFileSize();
        task.message = "等待下载: " + displayName;
        task.eventModName = modFile.getFileName();
        task.eventModVersion = modFile.getFileId() != null ? modFile.getFileId() : displayName;
        addTask(task);
        schedule(task, () -> runModDownload(task, modFile, gameVersion, versionId, instanceId));
        return task.id;
    }

    /**
     * 把社区客户端的发行包或源码放进下载队列，由 {@link DownloadManager} 下载。
     * 同一个客户端已在排队、下载或暂停时不重复提交。
     */
    public String submitNativeClient(Path pmclHome, String id, String displayName, String compileRuntime,
                                     Runnable onInstalled) {
        synchronized (tasks) {
            for (QueueTask existing : tasks.values()) {
                if (!id.equals(existing.nativeClientId)) continue;
                if (existing.isActive() || existing.status == TaskStatus.PAUSED) return existing.id;
            }
        }
        String name = displayName == null || displayName.isBlank() ? id : displayName;
        QueueTask task = new QueueTask(UUID.randomUUID().toString(), name, TaskType.NATIVE_CLIENT);
        task.nativeClientId = id;
        task.message = "等待下载: " + name;
        addTask(task);
        schedule(task, () -> runNativeClient(task, pmclHome, id, compileRuntime, onInstalled));
        return task.id;
    }

    /**
     * 市场光影或材质：用下载器写入所选游戏的 shaderpacks / resourcepacks，并走队列进度。
     *
     * @param folder shaderpacks 或 resourcepacks
     */
    public String submitMarketContent(ModFile file, String folder, String versionId, String instanceId,
                                      Runnable onInstalled) {
        String displayName = file.getFileName() != null && !file.getFileName().isBlank()
                ? file.getFileName() : folder;
        QueueTask task = new QueueTask(UUID.randomUUID().toString(), displayName, TaskType.MARKET_CONTENT);
        task.totalBytes = Math.max(0, file.getFileSize());
        task.message = "等待下载: " + displayName;
        task.eventModName = displayName;
        addTask(task);
        schedule(task, () -> runMarketContent(task, file, folder, versionId, instanceId, onInstalled));
        return task.id;
    }

    /** 市场整合包：先校验下载压缩包，再在队列线程里导入为新游戏。 */
    public String submitMarketModpack(ModFile file, Runnable onInstalled) {
        String displayName = file.getFileName() != null && !file.getFileName().isBlank()
                ? file.getFileName() : "modpack";
        QueueTask task = new QueueTask(UUID.randomUUID().toString(), displayName, TaskType.MARKET_CONTENT);
        task.totalBytes = Math.max(0, file.getFileSize());
        task.message = "等待下载: " + displayName;
        addTask(task);
        schedule(task, () -> runMarketModpack(task, file, onInstalled));
        return task.id;
    }

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
     * 取消任务：中断运行线程，标记 CANCELLED，丢弃版本 staging 与续传句柄。
     * 已下载到 libraries/assets 的完整文件保留（可复用）；半成品 staging 清除。
     */
    public void cancel(String taskId) {
        QueueTask task = tasks.get(taskId);
        if (task == null) return;
        if (task.status == TaskStatus.DONE || task.status == TaskStatus.CANCELLED) return;
        task.cancelRequested = true;
        Future<?> f = runningFutures.get(taskId);
        if (f != null) {
            f.cancel(true);
            // 等待工作线程退出后再清理 staging 目录，避免工作线程仍在写入时删除导致竞态
            try {
                f.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                // 工作线程未在限时内退出，强制清理
            } catch (Throwable ignored) {
                // 任务因中断/取消而终止，属于预期行为
            }
        }
        discardCancelledArtifacts(task);
        task.status = TaskStatus.CANCELLED;
        task.message = "已取消";
        task.finishedAt = System.currentTimeMillis();
        runningFutures.remove(taskId);
        notifyListeners();
    }

    /** 取消时清理 staging 与续传，与暂停（保留断点）区分。 */
    private void discardCancelledArtifacts(QueueTask task) {
        clearResumeWork(task.id);
        String vid = task.cleanupVersionId;
        if (vid != null && !vid.isBlank()) {
            VersionStaging.discard(config.getVersionsDir(), vid);
        }
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
        synchronized (tasks) {
            return summarize(new ArrayList<>(tasks.values()));
        }
    }

    /** 用同一次任务快照算总览，避免卡片还是旧字节、标题已经是新字节。 */
    public static QueueSummary summarize(List<QueueTask> snapshot) {
        int queued = 0, running = 0, paused = 0, done = 0, failed = 0, cancelled = 0;
        long totalBytes = 0, completedBytes = 0;
        if (snapshot != null) {
            for (QueueTask t : snapshot) {
                if (t == null) continue;
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

    /**
     * 安装器的占位进度（例如「下载资产索引」0/1）不能把已经记下的字节清成 0。
     * 真正的字节或文件计数到来时，换成这一阶段的总量，不再和上一个阶段取最大值。
     *
     * @return null 表示字节不变
     */
    static long[] mergeInstallProgress(long currentCompleted, long currentTotal,
                                       long reportedCompleted, long reportedTotal) {
        if (reportedTotal <= 1 && reportedCompleted <= 0) return null;
        long total = reportedTotal > 1 ? reportedTotal : Math.max(currentTotal, reportedCompleted);
        long completed = Math.max(0, reportedCompleted);
        if (total < completed) total = completed;
        if (total <= 0) return null;
        return new long[]{completed, total};
    }

    /** 去掉数字后比较阶段，避免「下载中 12 / 100」这类句子每次都打断节流。 */
    static String phaseKey(String message) {
        if (message == null || message.isBlank()) return "";
        return message.replaceAll("\\d+", "#");
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
            try {
                l.accept(snapshot);
            } catch (Throwable ex) {
                System.err.println("[DownloadQueue] listener error: " + Exceptions.rootMessage(ex));
            }
        }
    }

    /** 进度通知（节流）。阶段文字变化时立刻刷新，避免还停在上一句。 */
    private void notifyProgress(QueueTask task) {
        notifyProgress(task, false);
    }

    private void notifyProgress(QueueTask task, boolean force) {
        long now = System.currentTimeMillis();
        Long last = lastNotifyTime.get(task.id);
        if (!force && last != null && now - last < PROGRESS_THROTTLE_MS) return;
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
     * <p>
     * 使用 runGeneration 代次计数器防止 resume/cancel 竞态：
     * 每次 schedule 递增代次，lambda 执行前校验自身代次是否仍为最新。
     * 若 resume() 已重新调度（代次递增），旧的 lambda 会检测到代次不匹配并退出，
     * 避免双重执行。
     */
    private void schedule(QueueTask task, Runnable work) {
        final long gen = ++task.runGeneration;
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
            // 代次校验：若 resume() 已重新调度，本 lambda 为过期副本，直接退出避免双重执行
            if (gen != task.runGeneration) {
                runningFutures.remove(task.id);
                return;
            }
            if (task.pauseRequested) {
                task.status = TaskStatus.PAUSED;
                task.message = "已暂停";
                runningFutures.remove(task.id);
                notifyListeners();
                return;
            }
            // 二次代次校验：防止 pause/resume 在此窗口内发生
            if (gen != task.runGeneration) {
                runningFutures.remove(task.id);
                return;
            }
            task.status = TaskStatus.RUNNING;
            task.message = "开始...";
            notifyListeners();
            try {
                work.run();
                // work 正常返回时也必须结算状态：暂停/取消不得残留 RUNNING（否则无法 resume）
                if (task.cancelRequested) {
                    discardCancelledArtifacts(task);
                    task.status = TaskStatus.CANCELLED;
                    task.message = "已取消";
                    task.finishedAt = System.currentTimeMillis();
                    notifyListeners();
                } else if (task.pauseRequested) {
                    task.status = TaskStatus.PAUSED;
                    task.message = "已暂停";
                    notifyListeners();
                } else {
                    task.status = TaskStatus.DONE;
                    task.message = "完成";
                    task.completedBytes = task.totalBytes;
                    task.finishedAt = System.currentTimeMillis();
                    notifyListeners();
                    firePluginTaskDone(task);
                }
            } catch (Throwable e) {
                if (task.cancelRequested) {
                    discardCancelledArtifacts(task);
                    task.status = TaskStatus.CANCELLED;
                    task.message = "已取消";
                } else if (task.pauseRequested || InstallInterruptedException.isInterrupted(e)) {
                    // Future.cancel(true) 可能只带中断、尚未写 pauseRequested：按暂停保留续传
                    task.pauseRequested = true;
                    task.status = TaskStatus.PAUSED;
                    task.message = "已暂停";
                } else {
                    task.status = TaskStatus.FAILED;
                    task.errorMessage = Exceptions.rootMessage(e);
                    task.message = "失败: " + task.errorMessage;
                }
                task.finishedAt = System.currentTimeMillis();
                notifyListeners();
            } finally {
                if (gen == task.runGeneration) {
                    runningFutures.remove(task.id);
                }
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
                throwIfTaskInterrupted(task);
                applyInstallProgress(task, progress.getCompleted(), progress.getTotal(), progress.getMessage());
            }).join();
        } catch (Throwable e) {
            rethrowQueueFailure(e);
        } finally {
            // pause 时 work.finally 早于 schedule 结算状态：必须用 pauseRequested 保留 resumeWork
            if (!(task.pauseRequested || task.status == TaskStatus.PAUSED)) {
                clearResumeWork(task.id);
            }
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
                throwIfTaskInterrupted(task);
                // total 为 0 的回调只更新文字。不能把已有进度清成 0，
                // 否则 Forbric 构建阶段会把刚下完的安装器显示成 0%。
                if (progress.getTotal() > 0) {
                    task.totalBytes = Math.max(task.totalBytes, progress.getTotal());
                    task.completedBytes = progress.getCompleted();
                }
                task.message = progress.getMessage() != null ? progress.getMessage() : "安装中";
                notifyProgress(task);
            }).join();
            // 模组加载器安装无明确字节数时，标记完成设 100%
            if (task.totalBytes <= 0) {
                task.totalBytes = 1;
                task.completedBytes = 1;
            }
        } catch (Throwable e) {
            rethrowQueueFailure(e);
        } finally {
            if (!(task.pauseRequested || task.status == TaskStatus.PAUSED)) {
                clearResumeWork(task.id);
            }
        }
    }

    private void runModDownload(QueueTask task, ModFile modFile,
                                String gameVersion, String versionId, String instanceId) {
        storeResumeWork(task.id, () -> runModDownload(task, modFile, gameVersion, versionId, instanceId));
        try {
            modMarketManager.installMod(modFile, gameVersion, versionId, instanceId, preferences, status -> {
                throwIfTaskInterrupted(task);
                task.message = status;
                notifyProgress(task);
            }).join();
            task.completedBytes = task.totalBytes;
        } catch (Throwable e) {
            rethrowQueueFailure(e);
        } finally {
            if (!(task.pauseRequested || task.status == TaskStatus.PAUSED)) {
                clearResumeWork(task.id);
            }
        }
    }

    private void runNativeClient(QueueTask task, Path pmclHome, String id, String compileRuntime, Runnable onInstalled) {
        storeResumeWork(task.id, () -> runNativeClient(task, pmclHome, id, compileRuntime, onInstalled));
        try {
            com.pmcl.core.nativeclient.NativeClientInstaller.install(
                    pmclHome, downloadManager, id, compileRuntime,
                    message -> {
                        throwIfTaskInterrupted(task);
                        task.message = message;
                        notifyProgress(task);
                    },
                    (completed, total) -> {
                        throwIfTaskInterrupted(task);
                        if (total > 0) task.totalBytes = total;
                        task.completedBytes = completed;
                        notifyProgress(task);
                    });
            if (onInstalled != null) {
                try {
                    onInstalled.run();
                } catch (Throwable ignored) {
                    // 安装已经完成，刷新界面失败不影响队列结果
                }
            }
        } catch (Throwable e) {
            rethrowQueueFailure(e);
        } finally {
            if (!(task.pauseRequested || task.status == TaskStatus.PAUSED)) {
                clearResumeWork(task.id);
            }
        }
    }

    private void runMarketContent(QueueTask task, ModFile file, String folder,
                                  String versionId, String instanceId, Runnable onInstalled) {
        storeResumeWork(task.id, () -> runMarketContent(task, file, folder, versionId, instanceId, onInstalled));
        try {
            modMarketManager.installContentFile(file, folder, versionId, instanceId, preferences,
                    status -> {
                        throwIfTaskInterrupted(task);
                        task.message = status;
                        notifyProgress(task);
                    },
                    bytes -> noteDownloadBytes(task, bytes));
            if (task.totalBytes > 0) task.completedBytes = task.totalBytes;
            runInstalledCallback(onInstalled);
        } catch (Throwable e) {
            rethrowQueueFailure(e);
        } finally {
            if (!(task.pauseRequested || task.status == TaskStatus.PAUSED)) {
                clearResumeWork(task.id);
            }
        }
    }

    private void runMarketModpack(QueueTask task, ModFile file, Runnable onInstalled) {
        storeResumeWork(task.id, () -> runMarketModpack(task, file, onInstalled));
        Path zip = null;
        try {
            ModpackManager packs = modpackManager;
            if (packs == null) {
                throw new java.io.IOException("整合包管理器未就绪");
            }
            task.message = "正在下载: " + task.name;
            notifyProgress(task);
            zip = modMarketManager.downloadModpackArchive(file, bytes -> noteDownloadBytes(task, bytes));
            if (task.totalBytes > 0) task.completedBytes = task.totalBytes;
            final Path archive = zip;
            synchronized (modpackImportLock) {
                throwIfTaskInterrupted(task);
                task.message = "正在安装整合包";
                notifyProgress(task);
                packs.importModpack(archive, progress -> {
                    throwIfTaskInterrupted(task);
                    applyInstallProgress(task, progress.getCompleted(), progress.getTotal(), progress.getMessage());
                }).join();
            }
            runInstalledCallback(onInstalled);
        } catch (Throwable e) {
            rethrowQueueFailure(e);
        } finally {
            boolean paused = task.pauseRequested || task.status == TaskStatus.PAUSED;
            if (!paused && zip != null) {
                try {
                    Files.deleteIfExists(zip);
                } catch (java.io.IOException ignored) {
                }
            }
            if (!paused) clearResumeWork(task.id);
        }
    }

    private void applyInstallProgress(QueueTask task, long completed, long total, String message) {
        long[] merged = mergeInstallProgress(task.completedBytes, task.totalBytes, completed, total);
        if (merged != null) {
            task.completedBytes = merged[0];
            task.totalBytes = merged[1];
        }
        boolean phaseChanged = false;
        if (message != null && !message.isBlank() && !message.equals(task.message)) {
            phaseChanged = !phaseKey(task.message).equals(phaseKey(message));
            task.message = message;
        }
        notifyProgress(task, phaseChanged);
    }

    /** 只接受正数进度，避免 0 字节回调把已经走起来的进度条打回 0。 */
    private void noteDownloadBytes(QueueTask task, long completed) {
        throwIfTaskInterrupted(task);
        if (completed <= 0) return;
        if (task.totalBytes < completed) task.totalBytes = completed;
        task.completedBytes = completed;
        notifyProgress(task);
    }

    private static void runInstalledCallback(Runnable onInstalled) {
        if (onInstalled == null) return;
        try {
            onInstalled.run();
        } catch (Throwable ignored) {
            // 文件已经落盘，刷新界面失败不影响队列结果
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
                throwIfTaskInterrupted(task);
                task.completedBytes = completedBytes;
                if (task.totalBytes == 0) task.totalBytes = completedBytes;
                notifyProgress(task);
            });
        } catch (Throwable e) {
            rethrowQueueFailure(e);
        } finally {
            if (!(task.pauseRequested || task.status == TaskStatus.PAUSED)) {
                clearResumeWork(task.id);
            }
        }
    }

    private static void throwIfTaskInterrupted(QueueTask task) {
        if (task.cancelRequested || task.pauseRequested) {
            Thread.currentThread().interrupt();
            throw new InstallInterruptedException(
                    task.cancelRequested ? "任务已取消" : "任务已暂停");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InstallInterruptedException("任务已中断");
        }
    }

    private static void rethrowQueueFailure(Throwable e) {
        if (InstallInterruptedException.isInterrupted(e)) {
            throw e instanceof InstallInterruptedException
                    ? (InstallInterruptedException) e
                    : new InstallInterruptedException("任务已中断", e);
        }
        if (e instanceof RuntimeException) throw (RuntimeException) e;
        throw new RuntimeException(e);
    }

    private void firePluginTaskDone(QueueTask task) {
        com.pmcl.core.plugin.PluginManager pm = pluginManager;
        if (pm == null || task == null) return;
        try {
            if (task.type == TaskType.VERSION_INSTALL && task.cleanupVersionId != null
                    && !task.cleanupVersionId.isBlank()) {
                pm.fireEvent(new com.pmcl.plugin.VersionInstalledEvent(task.cleanupVersionId));
            } else if (task.type == TaskType.MOD_DOWNLOAD) {
                String name = task.eventModName != null ? task.eventModName : task.name;
                String ver = task.eventModVersion != null ? task.eventModVersion : "";
                pm.fireEvent(new com.pmcl.plugin.ModInstalledEvent(name, ver));
            }
        } catch (Throwable ignored) {}
    }

    /** 关闭队列管理器，释放线程池 */
    public void shutdown() {
        cancelAll();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                System.err.println("[DownloadQueue] 线程池未能在 3s 内退出");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
